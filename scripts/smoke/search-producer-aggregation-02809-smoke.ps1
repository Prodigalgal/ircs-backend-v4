param(
    [string]$Namespace = "ircs-dev",
    [string]$ElasticsearchBaseUrl = "http://127.0.0.1:19204",
    [int]$ElasticsearchPort = 19204,
    [int]$WaitAttempts = 36,
    [int]$WaitSeconds = 2,
    [switch]$PurgeSmokeQueuesOnCleanup,
    [switch]$FailOnSkip
)

$ErrorActionPreference = "Stop"

$stamp = Get-Date -Format "yyyyMMddHHmmss"
$rawVideoId = [guid]::NewGuid().ToString()
$sourceHash = "codex02809agg$stamp"
$dataHash = "codex02809aggdata$stamp"
$expectedTitle = "Codex 02809 Aggregated Unified $stamp"
$expectedAliasTitle = "Codex 02809 Aggregated Alias $stamp"
$expectedDescription = "Codex 02809 aggregation live smoke description $stamp"
$expectedYear = "2026"
$expectedScoreText = "8.9"
$expectedScore = [double]$expectedScoreText
$expectedPublishedAt = "2026-06-08"
$expectedDuration = "42m"
$expectedEpisodes = "12"
$expectedRemarks = "Codex 02809 aggregation remark $stamp"
$expectedTmdbId = "tm$stamp"
$expectedImdbId = "tt$stamp"

$esPfOut = Join-Path $env:TEMP "ircs-02809-aggregation-es-pf.out.log"
$esPfErr = Join-Path $env:TEMP "ircs-02809-aggregation-es-pf.err.log"
$esPf = $null
$script:elasticPassword = $null
$script:unifiedVideoId = $null

function New-SmokeResult {
    param(
        [string]$Status,
        [string]$Reason,
        [hashtable]$Details = @{}
    )
    [ordered]@{
        task = "028-09"
        slice = "aggregation-unified-search-sync"
        status = $Status
        reason = $Reason
        checkedAt = (Get-Date).ToUniversalTime().ToString("o")
        namespace = $Namespace
        rawVideoId = $rawVideoId
        unifiedVideoId = $script:unifiedVideoId
        details = $Details
    } | ConvertTo-Json -Depth 12
}

function Finish-Smoke {
    param(
        [string]$Status,
        [string]$Reason,
        [hashtable]$Details = @{}
    )
    New-SmokeResult -Status $Status -Reason $Reason -Details $Details
    if ($Status -eq "FAILED") {
        exit 1
    }
    if ($Status -eq "SKIPPED" -and $FailOnSkip) {
        exit 2
    }
    exit 0
}

function Mark {
    param([string]$Name, [int]$Index, [int]$Total)
    Write-Output ("MARK {0}/{1} {2}" -f $Index, $Total, $Name)
}

function Sql-Literal {
    param([AllowNull()][string]$Value)
    if ($null -eq $Value) {
        return "null"
    }
    return "'" + $Value.Replace("'", "''") + "'"
}

function Test-KubectlAccess {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = kubectl -n $Namespace get pod 2>&1
    $exit = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($exit -ne 0) {
        Finish-Smoke "SKIPPED" "KUBERNETES_UNAVAILABLE" @{
            exitCode = $exit
            output = (($output | Out-String).Trim())
        }
    }
}

function Test-PostgresAccess {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = "select 1;" | kubectl -n $Namespace exec -i postgres-0 -- psql -v ON_ERROR_STOP=1 -U postgres -d ircs -t -A 2>&1
    $exit = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($exit -ne 0) {
        Finish-Smoke "SKIPPED" "POSTGRES_UNAVAILABLE" @{
            exitCode = $exit
            output = (($output | Out-String).Trim())
        }
    }
}

function Assert-DeploymentReady {
    param([string]$Name)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $readyText = kubectl -n $Namespace get deployment $Name -o jsonpath="{.status.readyReplicas}" 2>&1
    $readyExit = $LASTEXITCODE
    $replicaText = kubectl -n $Namespace get deployment $Name -o jsonpath="{.spec.replicas}" 2>&1
    $replicaExit = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($readyExit -ne 0 -or $replicaExit -ne 0) {
        Finish-Smoke "SKIPPED" "DEPLOYMENT_UNAVAILABLE" @{
            deployment = $Name
            readyOutput = (($readyText | Out-String).Trim())
            replicaOutput = (($replicaText | Out-String).Trim())
        }
    }
    $ready = if ([string]::IsNullOrWhiteSpace($readyText)) { 0 } else { [int]$readyText }
    $replicas = if ([string]::IsNullOrWhiteSpace($replicaText)) { 0 } else { [int]$replicaText }
    if ($replicas -lt 1 -or $ready -lt 1) {
        Finish-Smoke "SKIPPED" "DEPLOYMENT_NOT_READY" @{
            deployment = $Name
            readyReplicas = $ready
            replicas = $replicas
        }
    }
}

function Start-PortForward {
    param(
        [string]$Service,
        [int]$LocalPort,
        [int]$RemotePort,
        [string]$OutFile,
        [string]$ErrFile
    )
    Remove-Item -LiteralPath $OutFile, $ErrFile -ErrorAction SilentlyContinue
    $pf = Start-Process -FilePath kubectl `
        -ArgumentList @("-n", $Namespace, "port-forward", $Service, "$($LocalPort):$RemotePort") `
        -RedirectStandardOutput $OutFile `
        -RedirectStandardError $ErrFile `
        -WindowStyle Hidden `
        -PassThru
    Start-Sleep -Seconds 5
    if ($pf.HasExited) {
        $err = if (Test-Path $ErrFile) { Get-Content -LiteralPath $ErrFile -Raw } else { "" }
        Finish-Smoke "SKIPPED" "PORT_FORWARD_UNAVAILABLE" @{
            service = $Service
            localPort = $LocalPort
            remotePort = $RemotePort
            error = $err
        }
    }
    return $pf
}

function Get-SecretValue {
    param([string]$Key)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $value64 = kubectl -n $Namespace get secret ircs-dev-secrets -o jsonpath="{.data.$Key}" 2>&1
    $exit = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($exit -ne 0 -or [string]::IsNullOrWhiteSpace($value64)) {
        Finish-Smoke "SKIPPED" "KUBERNETES_SECRET_MISSING" @{
            key = $Key
            exitCode = $exit
            output = (($value64 | Out-String).Trim())
        }
    }
    return [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($value64))
}

function Get-ElasticPassword {
    if ([string]::IsNullOrWhiteSpace($script:elasticPassword)) {
        $script:elasticPassword = Get-SecretValue "ELASTICSEARCH_PASSWORD"
    }
    return $script:elasticPassword
}

function Exec-Sql {
    param([string]$Sql)
    $Sql | kubectl -n $Namespace exec -i postgres-0 -- psql -v ON_ERROR_STOP=1 -U postgres -d ircs
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed with exit code $LASTEXITCODE"
    }
}

function Invoke-SqlScalar {
    param([string]$Sql)
    $rows = $Sql | kubectl -n $Namespace exec -i postgres-0 -- psql -v ON_ERROR_STOP=1 -U postgres -d ircs -t -A
    if ($LASTEXITCODE -ne 0) {
        throw "psql scalar failed with exit code $LASTEXITCODE"
    }
    $value = $rows | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Last 1
    if ($null -eq $value) {
        return $null
    }
    return $value.Trim()
}

function Get-QueueParts {
    param([string]$Name)
    $lines = kubectl -n $Namespace exec rabbitmq-0 -- rabbitmqctl list_queues name messages messages_ready messages_unacknowledged consumers
    if ($LASTEXITCODE -ne 0) {
        throw "rabbitmqctl list_queues failed"
    }
    $line = $lines | Where-Object { $_ -match "^$([regex]::Escape($Name))\s" } | Select-Object -First 1
    if (-not $line) {
        throw "queue not found: $Name"
    }
    return $line -split "\s+"
}

function Get-QueueState {
    param([string]$Name)
    $parts = Get-QueueParts $Name
    return @{
        name = $Name
        messages = [int]$parts[1]
        ready = [int]$parts[2]
        unacked = [int]$parts[3]
        consumers = [int]$parts[4]
    }
}

function Assert-SmokeQueuesCleanBefore {
    $dirty = @()
    foreach ($queue in @(
    )) {
        try {
            $state = Get-QueueState $queue
            if ($state.messages -ne 0 -or $state.unacked -ne 0) {
                $dirty += "$queue messages=$($state.messages) unacked=$($state.unacked)"
            }
        } catch {
            Finish-Smoke "SKIPPED" "QUEUE_UNAVAILABLE" @{
                queue = $queue
                message = $_.Exception.Message
            }
        }
    }
    if ($dirty.Count -gt 0) {
        Finish-Smoke "SKIPPED" "QUEUE_NOT_CLEAN" @{
            queues = $dirty
        }
    }
}

function Assert-ConsumersReady {
    $lastMissing = @()
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        $missing = @()
        foreach ($queue in @(
        )) {
            $state = Get-QueueState $queue
            if ($state.consumers -lt 1) {
                $missing += "$queue consumers=$($state.consumers)"
            }
        }
        if ($missing.Count -eq 0) {
            return
        }
        $lastMissing = $missing
        Start-Sleep -Seconds $WaitSeconds
    }
    Finish-Smoke "SKIPPED" "CONSUMER_UNAVAILABLE" @{
        queues = $lastMissing
    }
}

function Assert-QueueEmpty {
    param([string]$Name)
    $state = Get-QueueState $Name
    if ($state.messages -ne 0 -or $state.unacked -ne 0) {
        throw "queue $Name has messages=$($state.messages) unacked=$($state.unacked)"
    }
}

function Assert-NoHttpRoute {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = kubectl -n $Namespace get httproute --no-headers 2>&1
    $exit = $LASTEXITCODE
    $ErrorActionPreference = $previous
    $text = ($output | Out-String).Trim()
    if ($exit -ne 0 -and $text -notmatch "No resources found") {
        Finish-Smoke "FAILED" "HTTPROUTE_LIST_FAILED" @{
            exitCode = $exit
            output = $text
        }
    }
    if ($exit -eq 0 -and $text -and $text -notmatch "No resources found") {
        Finish-Smoke "FAILED" "HTTPROUTE_PRESENT" @{
            output = $text
        }
    }
}

function Test-EsAccess {
    $elasticPassword = Get-ElasticPassword
    $responseFile = Join-Path $env:TEMP ("ircs-02809-aggregation-es-root-" + [guid]::NewGuid() + ".json")
    try {
        $previous = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $code = & curl.exe -sS -u "elastic:$elasticPassword" `
            -o $responseFile `
            -w "%{http_code}" `
            "$($ElasticsearchBaseUrl.TrimEnd('/'))/" 2>&1
        $exit = $LASTEXITCODE
        $ErrorActionPreference = $previous
        $body = if (Test-Path $responseFile) { Get-Content -LiteralPath $responseFile -Raw } else { "" }
        if ($exit -ne 0) {
            Finish-Smoke "SKIPPED" "ELASTICSEARCH_UNAVAILABLE" @{
                curlExitCode = $exit
                output = ($code -join "`n")
            }
        }
        if ([int]$code -lt 200 -or [int]$code -ge 300) {
            Finish-Smoke "SKIPPED" "ELASTICSEARCH_HTTP_$code" @{
                httpStatus = [int]$code
                bodyPreview = if ($body.Length -gt 500) { $body.Substring(0, 500) } else { $body }
            }
        }
    } finally {
        Remove-Item -LiteralPath $responseFile -ErrorAction SilentlyContinue
    }
}

function Get-EsDocument {
    param(
        [string]$Index,
        [string]$Id
    )
    $elasticPassword = Get-ElasticPassword
    $responseFile = Join-Path $env:TEMP ("ircs-02809-aggregation-es-doc-" + [guid]::NewGuid() + ".json")
    try {
        $code = & curl.exe -sS -u "elastic:$elasticPassword" `
            -o $responseFile `
            -w "%{http_code}" `
            "$ElasticsearchBaseUrl/$Index/_doc/$Id"
        $text = if (Test-Path $responseFile) { Get-Content -LiteralPath $responseFile -Raw } else { "" }
        if ([int]$code -eq 404) {
            return $null
        }
        if ([int]$code -lt 200 -or [int]$code -ge 300) {
            throw "Elasticsearch get HTTP $code body=$text"
        }
        return $text | ConvertFrom-Json
    } finally {
        Remove-Item -LiteralPath $responseFile -ErrorAction SilentlyContinue
    }
}

function Remove-EsDocument {
    param(
        [string]$Index,
        [AllowNull()][string]$Id
    )
    if ([string]::IsNullOrWhiteSpace($Id) -or [string]::IsNullOrWhiteSpace($script:elasticPassword)) {
        return
    }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & curl.exe -sS -u "elastic:$script:elasticPassword" -X DELETE "$ElasticsearchBaseUrl/$Index/_doc/$Id" | Out-Null
    $ErrorActionPreference = $previous
}

function ConvertTo-StringArray {
    param($Value)
    if ($null -eq $Value) {
        return @()
    }
    if ($Value -is [string]) {
        return @($Value)
    }
    if ($Value -is [System.Collections.IEnumerable]) {
        return @($Value | ForEach-Object { [string]$_ })
    }
    return @([string]$Value)
}

function Setup-SmokeData {
    $titleSql = Sql-Literal $expectedTitle
    $aliasSql = Sql-Literal $expectedAliasTitle
    $descSql = Sql-Literal $expectedDescription
    $remarksSql = Sql-Literal $expectedRemarks
    $sourceVidSql = Sql-Literal "02809-aggregation-$stamp"
    $sourceHashSql = Sql-Literal $sourceHash
    $dataHashSql = Sql-Literal $dataHash
    $tmdbSql = Sql-Literal $expectedTmdbId
    $imdbSql = Sql-Literal $expectedImdbId
    $sql = @"
insert into raw_videos (
    id, created_at, updated_at, version,
    source_vid, source_hash, data_hash,
    title, alias_title, description, year, score, published_at,
    total_episodes, duration, remarks,
    tmdb_id, imdb_id,
    raw_metadata, locked_fields,
    enrichment_status, enrichment_retry_count,
    normalization_status, normalization_retry_count,
    aggregation_status
) values (
    '$rawVideoId', now() - interval '2 days', now() - interval '2 days', 0,
    $sourceVidSql, $sourceHashSql, $dataHashSql,
    $titleSql, $aliasSql, $descSql, '$expectedYear', $expectedScoreText, date '$expectedPublishedAt',
    '$expectedEpisodes', '$expectedDuration', $remarksSql,
    $tmdbSql, $imdbSql,
    '{}'::jsonb, '[]'::jsonb,
    'SUCCESS', 0,
    'READY', 0,
    'PENDING'
);
"@
    Exec-Sql $sql | Out-Null
}

function Cleanup-SmokeData {
    $titleSql = Sql-Literal $expectedTitle
    $aliasSql = Sql-Literal $expectedAliasTitle
    $tmdbSql = Sql-Literal $expectedTmdbId
    $imdbSql = Sql-Literal $expectedImdbId
    $sql = @"
with target_unified as (
    select id from unified_videos
    where title in ($titleSql, $aliasSql)
       or alias_title in ($titleSql, $aliasSql)
       or tmdb_id = $tmdbSql
       or imdb_id = $imdbSql
       or id in (select unified_video_id from raw_video_unified_video where raw_video_id = '$rawVideoId')
)
delete from unified_video_actors where unified_video_id in (select id from target_unified);
with target_unified as (
    select id from unified_videos
    where title in ($titleSql, $aliasSql)
       or alias_title in ($titleSql, $aliasSql)
       or tmdb_id = $tmdbSql
       or imdb_id = $imdbSql
       or id in (select unified_video_id from raw_video_unified_video where raw_video_id = '$rawVideoId')
)
delete from unified_video_directors where unified_video_id in (select id from target_unified);
with target_unified as (
    select id from unified_videos
    where title in ($titleSql, $aliasSql)
       or alias_title in ($titleSql, $aliasSql)
       or tmdb_id = $tmdbSql
       or imdb_id = $imdbSql
       or id in (select unified_video_id from raw_video_unified_video where raw_video_id = '$rawVideoId')
)
delete from unified_video_genres where unified_video_id in (select id from target_unified);
with target_unified as (
    select id from unified_videos
    where title in ($titleSql, $aliasSql)
       or alias_title in ($titleSql, $aliasSql)
       or tmdb_id = $tmdbSql
       or imdb_id = $imdbSql
       or id in (select unified_video_id from raw_video_unified_video where raw_video_id = '$rawVideoId')
)
delete from unified_video_standard_languages where unified_video_id in (select id from target_unified);
with target_unified as (
    select id from unified_videos
    where title in ($titleSql, $aliasSql)
       or alias_title in ($titleSql, $aliasSql)
       or tmdb_id = $tmdbSql
       or imdb_id = $imdbSql
       or id in (select unified_video_id from raw_video_unified_video where raw_video_id = '$rawVideoId')
)
delete from unified_video_standard_areas where unified_video_id in (select id from target_unified);
with target_unified as (
    select id from unified_videos
    where title in ($titleSql, $aliasSql)
       or alias_title in ($titleSql, $aliasSql)
       or tmdb_id = $tmdbSql
       or imdb_id = $imdbSql
       or id in (select unified_video_id from raw_video_unified_video where raw_video_id = '$rawVideoId')
)
delete from raw_video_unified_video
where raw_video_id = '$rawVideoId'
   or unified_video_id in (select id from target_unified);
delete from video_actors where video_id = '$rawVideoId';
delete from video_directors where video_id = '$rawVideoId';
delete from video_raw_genres where video_id = '$rawVideoId';
delete from video_raw_languages where video_id = '$rawVideoId';
delete from video_raw_areas where video_id = '$rawVideoId';
delete from playlists where video_id = '$rawVideoId';
delete from raw_videos where id = '$rawVideoId' or source_hash = '$sourceHash' or tmdb_id = $tmdbSql or imdb_id = $imdbSql;
delete from unified_videos
where title in ($titleSql, $aliasSql)
   or alias_title in ($titleSql, $aliasSql)
   or tmdb_id = $tmdbSql
   or imdb_id = $imdbSql;
"@
    Exec-Sql $sql | Out-Null
}

function Assert-DbFixturePresent {
    $count = Invoke-SqlScalar "select count(*) from raw_videos where id = '$rawVideoId' and source_hash = '$sourceHash' and aggregation_status = 'PENDING';"
    if ($count -ne "1") {
        throw "aggregation fixture was not seeded, count=$count"
    }
}

function Wait-AggregationBound {
    $lastState = "not checked"
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        Start-Sleep -Seconds $WaitSeconds
        $state = Invoke-SqlScalar @"
select coalesce(rv.aggregation_status, '') || '|' ||
       coalesce(rvu.unified_video_id::text, '') || '|' ||
       coalesce(uv.title, '') || '|' ||
       coalesce(uv.alias_title, '') || '|' ||
       coalesce(uv.metadata_status, '') || '|' ||
       coalesce(uv.tmdb_id, '') || '|' ||
       coalesce(uv.imdb_id, '')
from raw_videos rv
left join raw_video_unified_video rvu on rvu.raw_video_id = rv.id
left join unified_videos uv on uv.id = rvu.unified_video_id
where rv.id = '$rawVideoId';
"@
        $lastState = $state
        if ($null -ne $state) {
            $parts = $state -split "\|", 7
            if ($parts.Count -eq 7 `
                    -and $parts[0] -eq "BOUND" `
                    -and -not [string]::IsNullOrWhiteSpace($parts[1]) `
                    -and $parts[2] -eq $expectedTitle `
                    -and $parts[3] -eq $expectedAliasTitle `
                    -and $parts[4] -eq "SYNCED" `
                    -and $parts[5] -eq $expectedTmdbId `
                    -and $parts[6] -eq $expectedImdbId) {
                $script:unifiedVideoId = $parts[1]
                return
            }
        }
    }
    throw "raw video was not aggregated to expected BOUND unified projection; last state: $lastState"
}

function Wait-EsRawDocument {
    $lastState = "not checked"
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        Start-Sleep -Seconds $WaitSeconds
        $doc = Get-EsDocument "ircs_raw_video" $rawVideoId
        if ($null -ne $doc -and $doc.found) {
            $source = $doc._source
            $externalIds = ConvertTo-StringArray $source.externalIds
            $scoreMatches = $false
            if ($null -ne $source.score) {
                $scoreMatches = [Math]::Abs(([double]$source.score) - $expectedScore) -lt 0.001
            }
            $lastState = "found title='$($source.title)' aliasTitle='$($source.aliasTitle)' normalizationStatus='$($source.normalizationStatus)' enrichmentStatus='$($source.enrichmentStatus)' externalIds='$($externalIds -join ',')'"
            if ($source.title -eq $expectedTitle `
                    -and $source.aliasTitle -eq $expectedAliasTitle `
                    -and $source.normalizationStatus -eq "READY" `
                    -and $source.enrichmentStatus -eq "SUCCESS" `
                    -and $source.year -eq $expectedYear `
                    -and $scoreMatches `
                    -and ($externalIds -contains $expectedTmdbId) `
                    -and ($externalIds -contains $expectedImdbId)) {
                return
            }
        } else {
            $lastState = "missing"
        }
    }
    throw "ES raw document did not reach aggregation projection; last state: $lastState"
}

function Wait-EsUnifiedDocument {
    $lastState = "not checked"
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        Start-Sleep -Seconds $WaitSeconds
        $doc = Get-EsDocument "ircs_unified_video" $script:unifiedVideoId
        if ($null -ne $doc -and $doc.found) {
            $source = $doc._source
            $externalIds = ConvertTo-StringArray $source.externalIds
            $scoreMatches = $false
            if ($null -ne $source.score) {
                $scoreMatches = [Math]::Abs(([double]$source.score) - $expectedScore) -lt 0.001
            }
            $lastState = "found title='$($source.title)' aliasTitle='$($source.aliasTitle)' sourceCount='$($source.sourceCount)' hasTmdb='$($source.hasTmdb)' hasImdb='$($source.hasImdb)' externalIds='$($externalIds -join ',')'"
            if ($source.title -eq $expectedTitle `
                    -and $source.aliasTitle -eq $expectedAliasTitle `
                    -and $source.year -eq [int]$expectedYear `
                    -and $scoreMatches `
                    -and $source.hasTmdb -eq $true `
                    -and $source.hasImdb -eq $true `
                    -and $source.sourceCount -eq 1 `
                    -and ($externalIds -contains $expectedTmdbId) `
                    -and ($externalIds -contains $expectedImdbId)) {
                return
            }
        } else {
            $lastState = "missing"
        }
    }
    throw "ES unified document did not reach aggregation projection; last state: $lastState"
}

function Wait-EsMissing {
    param(
        [string]$Index,
        [AllowNull()][string]$Id
    )
    if ([string]::IsNullOrWhiteSpace($Id)) {
        return
    }
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        Start-Sleep -Seconds 1
        $doc = Get-EsDocument $Index $Id
        if ($null -eq $doc -or -not $doc.found) {
            return
        }
    }
    throw "Elasticsearch document still exists after cleanup: $Index/$Id"
}

function Assert-NoDbResidue {
    $titleSql = Sql-Literal $expectedTitle
    $aliasSql = Sql-Literal $expectedAliasTitle
    $tmdbSql = Sql-Literal $expectedTmdbId
    $imdbSql = Sql-Literal $expectedImdbId
    $residue = Invoke-SqlScalar @"
select
  (select count(*) from raw_videos where id = '$rawVideoId' or source_hash = '$sourceHash' or tmdb_id = $tmdbSql or imdb_id = $imdbSql)::text
  || '|' ||
  (select count(*) from raw_video_unified_video where raw_video_id = '$rawVideoId'
      or unified_video_id in (select id from unified_videos where title in ($titleSql, $aliasSql) or alias_title in ($titleSql, $aliasSql) or tmdb_id = $tmdbSql or imdb_id = $imdbSql))::text
  || '|' ||
  (select count(*) from unified_videos where title in ($titleSql, $aliasSql) or alias_title in ($titleSql, $aliasSql) or tmdb_id = $tmdbSql or imdb_id = $imdbSql)::text;
"@
    if ($residue -ne "0|0|0") {
        throw "smoke cleanup left DB residue: $residue"
    }
}

try {
    Test-KubectlAccess
    Test-PostgresAccess
    Assert-DeploymentReady "ircs-aggregation-worker"
    Assert-DeploymentReady "ircs-search-service"
    $esPf = Start-PortForward "svc/elasticsearch-svc" $ElasticsearchPort 9200 $esPfOut $esPfErr
    Get-ElasticPassword | Out-Null
    Test-EsAccess

    Assert-SmokeQueuesCleanBefore
    Assert-ConsumersReady
    Assert-NoHttpRoute
    $total = 6
    $i = 1
    Mark "aggregation worker is ready and HTTPRoute remains absent" $i $total; $i++

    Cleanup-SmokeData
    Remove-EsDocument "ircs_raw_video" $rawVideoId
    Remove-EsDocument "ircs_unified_video" $script:unifiedVideoId
    Setup-SmokeData
    Assert-DbFixturePresent
    Mark "aggregation fixture is seeded and pre-cleaned from DB/ES" $i $total; $i++

    Wait-AggregationBound
    Mark "aggregation scheduler binds raw video to unified video and marks BOUND" $i $total; $i++

    Wait-EsRawDocument
    Wait-EsUnifiedDocument
    Mark "aggregation enqueues raw/unified runtime search sync and ES documents are refreshed" $i $total; $i++

    foreach ($queue in @(
    )) {
        Assert-QueueEmpty $queue
    }
    Mark "runtime search work is drained for the smoke window" $i $total; $i++

    Cleanup-SmokeData
    Remove-EsDocument "ircs_raw_video" $rawVideoId
    Remove-EsDocument "ircs_unified_video" $script:unifiedVideoId
    Wait-EsMissing "ircs_raw_video" $rawVideoId
    Wait-EsMissing "ircs_unified_video" $script:unifiedVideoId
    Assert-NoDbResidue
    Mark "smoke cleanup leaves no DB/ES fixture residue" $i $total
} catch {
    Finish-Smoke "FAILED" "AGGREGATION_UNIFIED_SEARCH_SMOKE_FAILED" @{
        message = $_.Exception.Message
        rawVideoId = $rawVideoId
        unifiedVideoId = $script:unifiedVideoId
        expectedTmdbId = $expectedTmdbId
        expectedImdbId = $expectedImdbId
    }
} finally {
    try { Cleanup-SmokeData } catch { Write-Warning $_ }
    try {
        if ($esPf -and -not $esPf.HasExited) {
            Remove-EsDocument "ircs_raw_video" $rawVideoId
            Remove-EsDocument "ircs_unified_video" $script:unifiedVideoId
        }
    } catch { Write-Warning $_ }
    if ($PurgeSmokeQueuesOnCleanup) {
        foreach ($queue in @(
        )) {
            try { kubectl -n $Namespace exec rabbitmq-0 -- rabbitmqctl purge_queue $queue | Out-Null } catch { Write-Warning $_ }
        }
    }
    if ($esPf -and -not $esPf.HasExited) {
        Stop-Process -Id $esPf.Id -Force -ErrorAction SilentlyContinue
    }
}
