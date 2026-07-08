param(
    [string]$Namespace = "ircs-dev",
    [string]$RabbitBaseUrl = "http://127.0.0.1:19078",
    [int]$RabbitPort = 19078,
    [string]$ElasticsearchBaseUrl = "http://127.0.0.1:19202",
    [int]$ElasticsearchPort = 19202,
    [int]$WaitAttempts = 30,
    [int]$WaitSeconds = 2,
    [switch]$PurgeSmokeQueuesOnCleanup,
    [switch]$FailOnSkip
)

$ErrorActionPreference = "Stop"

$stamp = Get-Date -Format "yyyyMMddHHmmss"
$rawVideoId = [guid]::NewGuid().ToString()
$sourceHash = "codex02809meta$stamp"
$dataHash = "codex02809metadata$stamp"
$fixtureTitle = "Codex 02809 Metadata Raw $stamp"
$expectedAliasTitle = "Codex 02809 Metadata Original $stamp"
$expectedDescription = "Codex 02809 metadata result smoke description $stamp"
$expectedYear = "2026"
$expectedScoreText = "8.7"
$expectedScore = [double]$expectedScoreText
$expectedTmdbId = "tm$stamp"
$expectedImdbId = "tt$stamp"

$rabbitPfOut = Join-Path $env:TEMP "ircs-02809-metadata-rabbit-pf.out.log"
$rabbitPfErr = Join-Path $env:TEMP "ircs-02809-metadata-rabbit-pf.err.log"
$esPfOut = Join-Path $env:TEMP "ircs-02809-metadata-es-pf.out.log"
$esPfErr = Join-Path $env:TEMP "ircs-02809-metadata-es-pf.err.log"
$rabbitPf = $null
$esPf = $null
$script:rabbitPassword = $null
$script:elasticPassword = $null

function New-SmokeResult {
    param(
        [string]$Status,
        [string]$Reason,
        [hashtable]$Details = @{}
    )
    [ordered]@{
        task = "028-09"
        slice = "metadata-result-raw-search-sync"
        status = $Status
        reason = $Reason
        checkedAt = (Get-Date).ToUniversalTime().ToString("o")
        namespace = $Namespace
        rawVideoId = $rawVideoId
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

function Get-RabbitPassword {
    if ([string]::IsNullOrWhiteSpace($script:rabbitPassword)) {
        $script:rabbitPassword = Get-SecretValue "RABBITMQ_PASSWORD"
    }
    return $script:rabbitPassword
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

function Invoke-RabbitPublish {
    param(
        [string]$Exchange,
        [string]$RoutingKey,
        [string]$Payload,
        [string]$ContentType = "application/json"
    )
    $rabbitPassword = Get-RabbitPassword
    $body = @{
        properties = @{ content_type = $ContentType }
        routing_key = $RoutingKey
        payload = $Payload
        payload_encoding = "string"
    } | ConvertTo-Json -Depth 8 -Compress
    $requestFile = Join-Path $env:TEMP ("ircs-02809-metadata-rabbit-publish-" + [guid]::NewGuid() + ".json")
    $responseFile = Join-Path $env:TEMP ("ircs-02809-metadata-rabbit-response-" + [guid]::NewGuid() + ".json")
    [System.IO.File]::WriteAllText($requestFile, $body, [System.Text.UTF8Encoding]::new($false))
    try {
        $previous = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $code = & curl.exe -sS -u "admin:$rabbitPassword" `
            -H "Content-Type: application/json" `
            -o $responseFile `
            -w "%{http_code}" `
            --data-binary "@$requestFile" `
            "$RabbitBaseUrl/api/exchanges/%2F/$Exchange/publish" 2>&1
        $exit = $LASTEXITCODE
        $ErrorActionPreference = $previous
        $text = if (Test-Path $responseFile) { Get-Content -LiteralPath $responseFile -Raw } else { "" }
        if ($exit -ne 0) {
            throw "RabbitMQ publish curl failed with exit code $exit output=$($code -join "`n")"
        }
        if ([int]$code -lt 200 -or [int]$code -ge 300) {
            throw "RabbitMQ publish HTTP $code body=$text"
        }
        $response = $text | ConvertFrom-Json
        if (-not $response.routed) {
            throw "RabbitMQ publish was not routed: $text"
        }
    } finally {
        Remove-Item -LiteralPath $requestFile, $responseFile -ErrorAction SilentlyContinue
    }
}

function Get-QueueParts {
    param([string]$Name)
    $lines = kubectl -n $Namespace exec rabbitmq-0 -- rabbitmqctl list_queues name messages consumers
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
        consumers = [int]$parts[2]
    }
}

function Assert-SmokeQueuesCleanBefore {
    $dirty = @()
    foreach ($queue in @(
        "q.enrich.metadata.result",
        "q.enrich.metadata.result.dlq"
    )) {
        try {
            $state = Get-QueueState $queue
            if ($state.messages -ne 0) {
                $dirty += "$queue=$($state.messages)"
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
            "q.enrich.metadata.result"
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
    if ($state.messages -ne 0) {
        throw "queue $Name has $($state.messages) messages"
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
    $responseFile = Join-Path $env:TEMP ("ircs-02809-metadata-es-root-" + [guid]::NewGuid() + ".json")
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
    $responseFile = Join-Path $env:TEMP ("ircs-02809-metadata-es-doc-" + [guid]::NewGuid() + ".json")
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
        [string]$Id
    )
    if ([string]::IsNullOrWhiteSpace($script:elasticPassword)) {
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
    $titleSql = Sql-Literal $fixtureTitle
    $sourceVidSql = Sql-Literal "02809-metadata-$stamp"
    $sourceHashSql = Sql-Literal $sourceHash
    $dataHashSql = Sql-Literal $dataHash
    $sql = @"
insert into raw_videos (
    id, created_at, updated_at, version, source_vid, source_hash, data_hash, title,
    raw_metadata, locked_fields, enrichment_status, enrichment_retry_count,
    normalization_status, normalization_retry_count, aggregation_status
) values (
    '$rawVideoId', now(), now(), 0, $sourceVidSql, $sourceHashSql, $dataHashSql, $titleSql,
    '{}'::jsonb, '[]'::jsonb, 'PENDING', 0,
    'PENDING', 0, 'BOUND'
);
"@
    Exec-Sql $sql | Out-Null
}

function Cleanup-SmokeData {
    $titleSql = Sql-Literal $fixtureTitle
    $aliasSql = Sql-Literal $expectedAliasTitle
    $sql = @"
delete from unified_video_actors where unified_video_id in (
    select unified_video_id from raw_video_unified_video where raw_video_id = '$rawVideoId'
) or unified_video_id in (select id from unified_videos where title in ($titleSql, $aliasSql));
delete from unified_video_directors where unified_video_id in (
    select unified_video_id from raw_video_unified_video where raw_video_id = '$rawVideoId'
) or unified_video_id in (select id from unified_videos where title in ($titleSql, $aliasSql));
delete from unified_video_genres where unified_video_id in (
    select unified_video_id from raw_video_unified_video where raw_video_id = '$rawVideoId'
) or unified_video_id in (select id from unified_videos where title in ($titleSql, $aliasSql));
delete from unified_video_standard_languages where unified_video_id in (
    select unified_video_id from raw_video_unified_video where raw_video_id = '$rawVideoId'
) or unified_video_id in (select id from unified_videos where title in ($titleSql, $aliasSql));
delete from unified_video_standard_areas where unified_video_id in (
    select unified_video_id from raw_video_unified_video where raw_video_id = '$rawVideoId'
) or unified_video_id in (select id from unified_videos where title in ($titleSql, $aliasSql));
delete from raw_video_unified_video where raw_video_id = '$rawVideoId'
   or unified_video_id in (select id from unified_videos where title in ($titleSql, $aliasSql));
delete from video_actors where video_id = '$rawVideoId';
delete from video_directors where video_id = '$rawVideoId';
delete from video_raw_genres where video_id = '$rawVideoId';
delete from video_raw_languages where video_id = '$rawVideoId';
delete from video_raw_areas where video_id = '$rawVideoId';
delete from playlists where video_id = '$rawVideoId';
delete from raw_videos where id = '$rawVideoId' or source_hash = '$sourceHash';
delete from unified_videos where title in ($titleSql, $aliasSql);
"@
    Exec-Sql $sql | Out-Null
}

function Assert-DbFixturePresent {
    $count = Invoke-SqlScalar "select count(*) from raw_videos where id = '$rawVideoId' and source_hash = '$sourceHash';"
    if ($count -ne "1") {
        throw "metadata result fixture was not seeded, count=$count"
    }
}

function Wait-RawMetadataSuccess {
    $lastState = "not checked"
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        Start-Sleep -Seconds $WaitSeconds
        $state = Invoke-SqlScalar @"
select coalesce(enrichment_status, '') || '|' ||
       coalesce(tmdb_id, '') || '|' ||
       coalesce(imdb_id, '') || '|' ||
       coalesce(description, '') || '|' ||
       coalesce(year, '') || '|' ||
       coalesce(score::text, '') || '|' ||
       coalesce(alias_title, '') || '|' ||
       coalesce(aggregation_status, '')
from raw_videos
where id = '$rawVideoId';
"@
        $lastState = $state
        if ($null -ne $state) {
            $parts = $state -split "\|", 8
            if ($parts.Count -eq 8) {
                $scoreMatches = $false
                if (-not [string]::IsNullOrWhiteSpace($parts[5])) {
                    $scoreMatches = [Math]::Abs(([double]$parts[5]) - $expectedScore) -lt 0.001
                }
                if ($parts[0] -eq "SUCCESS" `
                        -and $parts[1] -eq $expectedTmdbId `
                        -and $parts[2] -eq $expectedImdbId `
                        -and $parts[3] -eq $expectedDescription `
                        -and $parts[4] -eq $expectedYear `
                        -and $scoreMatches `
                        -and $parts[6] -eq $expectedAliasTitle) {
                    return
                }
                if ($parts[0] -in @("FAILED", "PERMANENT_FAILURE")) {
                    throw "raw metadata reached failure state: $state"
                }
            }
        }
    }
    throw "raw metadata did not reach expected SUCCESS projection; last state: $lastState"
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
            $lastState = "found title='$($source.title)' aliasTitle='$($source.aliasTitle)' enrichmentStatus='$($source.enrichmentStatus)' hasTmdbId='$($source.hasTmdbId)' externalIds='$($externalIds -join ',')'"
            if ($source.title -eq $fixtureTitle `
                    -and $source.aliasTitle -eq $expectedAliasTitle `
                    -and $source.enrichmentStatus -eq "SUCCESS" `
                    -and $source.hasTmdbId -eq $true `
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
    throw "ES raw document did not reach metadata result projection; last state: $lastState"
}

function Wait-EsMissing {
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        Start-Sleep -Seconds 1
        $doc = Get-EsDocument "ircs_raw_video" $rawVideoId
        if ($null -eq $doc -or -not $doc.found) {
            return
        }
    }
    throw "Elasticsearch raw document still exists after cleanup: $rawVideoId"
}

function Assert-NoDbResidue {
    $titleSql = Sql-Literal $fixtureTitle
    $aliasSql = Sql-Literal $expectedAliasTitle
    $residue = Invoke-SqlScalar @"
select
  (select count(*) from raw_videos where id = '$rawVideoId' or source_hash = '$sourceHash')::text
  || '|' ||
  (select count(*) from raw_video_unified_video where raw_video_id = '$rawVideoId')::text
  || '|' ||
  (select count(*) from video_actors where video_id = '$rawVideoId')::text
  || '|' ||
  (select count(*) from video_directors where video_id = '$rawVideoId')::text
  || '|' ||
  (select count(*) from video_raw_genres where video_id = '$rawVideoId')::text
  || '|' ||
  (select count(*) from video_raw_languages where video_id = '$rawVideoId')::text
  || '|' ||
  (select count(*) from video_raw_areas where video_id = '$rawVideoId')::text
  || '|' ||
  (select count(*) from playlists where video_id = '$rawVideoId')::text
  || '|' ||
  (select count(*) from unified_videos where title in ($titleSql, $aliasSql))::text;
"@
    if ($residue -ne "0|0|0|0|0|0|0|0|0") {
        throw "smoke cleanup left DB residue: $residue"
    }
}

try {
    Test-KubectlAccess
    Test-PostgresAccess
    $rabbitPf = Start-PortForward "svc/rabbitmq-svc" $RabbitPort 15672 $rabbitPfOut $rabbitPfErr
    $esPf = Start-PortForward "svc/elasticsearch-svc" $ElasticsearchPort 9200 $esPfOut $esPfErr
    Get-RabbitPassword | Out-Null
    Test-EsAccess

    Assert-SmokeQueuesCleanBefore
    Assert-ConsumersReady
    Assert-NoHttpRoute
    $total = 6
    $i = 1
    Mark "metadata consumers are ready and HTTPRoute remains absent" $i $total; $i++

    Cleanup-SmokeData
    Remove-EsDocument "ircs_raw_video" $rawVideoId
    Setup-SmokeData
    Assert-DbFixturePresent
    Mark "metadata result fixture is seeded and pre-cleaned from DB/ES" $i $total; $i++

    $payload = [ordered]@{
        videoId = $rawVideoId
        providerType = "TMDB"
        success = $true
        retryable = $false
        metadata = [ordered]@{
            tmdbId = $expectedTmdbId
            imdbId = $expectedImdbId
            description = $expectedDescription
            year = $expectedYear
            score = $expectedScore
            originalTitle = $expectedAliasTitle
            title = $fixtureTitle
        }
    } | ConvertTo-Json -Depth 8 -Compress
    Invoke-RabbitPublish "x.process" "metadata.result" $payload "application/json"
    Wait-RawMetadataSuccess
    Mark "metadata result consumer updates raw_videos to SUCCESS" $i $total; $i++

    Wait-EsRawDocument
    Mark "metadata result enqueues raw runtime search sync and ES raw document is refreshed" $i $total; $i++

    foreach ($queue in @(
        "q.enrich.metadata.result",
        "q.enrich.metadata.result.dlq"
    )) {
        Assert-QueueEmpty $queue
    }
    Mark "metadata queues and DLQs are empty for the smoke window" $i $total; $i++

    Cleanup-SmokeData
    Remove-EsDocument "ircs_raw_video" $rawVideoId
    Wait-EsMissing
    Assert-NoDbResidue
    Mark "smoke cleanup leaves no DB/ES fixture residue" $i $total
} catch {
    Finish-Smoke "FAILED" "METADATA_RESULT_RAW_SEARCH_SMOKE_FAILED" @{
        message = $_.Exception.Message
        rawVideoId = $rawVideoId
        expectedTmdbId = $expectedTmdbId
        expectedImdbId = $expectedImdbId
    }
} finally {
    try { Cleanup-SmokeData } catch { Write-Warning $_ }
    try {
        if ($esPf -and -not $esPf.HasExited) {
            Remove-EsDocument "ircs_raw_video" $rawVideoId
        }
    } catch { Write-Warning $_ }
    if ($PurgeSmokeQueuesOnCleanup) {
        foreach ($queue in @(
            "q.enrich.metadata.result",
            "q.enrich.metadata.result.dlq"
        )) {
            try { kubectl -n $Namespace exec rabbitmq-0 -- rabbitmqctl purge_queue $queue | Out-Null } catch { Write-Warning $_ }
        }
    }
    if ($rabbitPf -and -not $rabbitPf.HasExited) {
        Stop-Process -Id $rabbitPf.Id -Force -ErrorAction SilentlyContinue
    }
    if ($esPf -and -not $esPf.HasExited) {
        Stop-Process -Id $esPf.Id -Force -ErrorAction SilentlyContinue
    }
}
