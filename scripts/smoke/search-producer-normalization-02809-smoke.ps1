param(
    [string]$RabbitBaseUrl = "http://127.0.0.1:19074",
    [int]$RabbitPort = 19074,
    [string]$ElasticsearchBaseUrl = "http://127.0.0.1:19200",
    [int]$ElasticsearchPort = 19200,
    [int]$WaitAttempts = 30,
    [int]$WaitSeconds = 2,
    [switch]$PurgeSmokeQueuesOnCleanup
)

$ErrorActionPreference = "Stop"

$stamp = Get-Date -Format "yyyyMMddHHmmss"
$rabbitPfOut = Join-Path $env:TEMP "ircs-02809-rabbit-pf.out.log"
$rabbitPfErr = Join-Path $env:TEMP "ircs-02809-rabbit-pf.err.log"
$esPfOut = Join-Path $env:TEMP "ircs-02809-es-pf.out.log"
$esPfErr = Join-Path $env:TEMP "ircs-02809-es-pf.err.log"
$rabbitPf = $null
$esPf = $null

$rawVideoId = [guid]::NewGuid().ToString()
$sourceHash = "codex02809hash$stamp"
$expectedTitle = "Codex 02809 Normalized Raw $stamp"
$expectedYear = "2026"
$expectedScore = 8.2
$expectedGenreA = "Codex Raw Genre 02809 $stamp A"
$expectedGenreB = "Codex Raw Genre 02809 $stamp B"
$expectedLanguage = "Codex Raw Language 02809 $stamp"
$expectedArea = "Codex Raw Area 02809 $stamp"

function Mark {
    param([string]$Name, [int]$Index, [int]$Total)
    Write-Output ("MARK {0}/{1} {2}" -f $Index, $Total, $Name)
}

function Exec-Sql {
    param([string]$Sql)
    $Sql | kubectl -n ircs-dev exec -i postgres-0 -- psql -v ON_ERROR_STOP=1 -U postgres -d ircs
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed with exit code $LASTEXITCODE"
    }
}

function Invoke-SqlScalar {
    param([string]$Sql)
    $rows = $Sql | kubectl -n ircs-dev exec -i postgres-0 -- psql -v ON_ERROR_STOP=1 -U postgres -d ircs -t -A
    if ($LASTEXITCODE -ne 0) {
        throw "psql scalar failed with exit code $LASTEXITCODE"
    }
    $value = $rows | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Last 1
    if ($null -eq $value) {
        return $null
    }
    return $value.Trim()
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
        -ArgumentList @("-n", "ircs-dev", "port-forward", $Service, "$($LocalPort):$RemotePort") `
        -RedirectStandardOutput $OutFile `
        -RedirectStandardError $ErrFile `
        -WindowStyle Hidden `
        -PassThru
    Start-Sleep -Seconds 5
    if ($pf.HasExited) {
        $err = if (Test-Path $ErrFile) { Get-Content -Path $ErrFile -Raw } else { "" }
        throw "$Service port-forward exited: $err"
    }
    return $pf
}

function Get-SecretValue {
    param([string]$Key)
    $value64 = kubectl -n ircs-dev get secret ircs-dev-secrets -o jsonpath="{.data.$Key}"
    if (-not $value64) {
        throw "$Key not found"
    }
    return [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($value64))
}

function Invoke-RabbitPublish {
    param(
        [string]$Exchange,
        [string]$RoutingKey,
        [string]$Payload,
        [string]$ContentType = "text/plain"
    )
    $rabbitPassword = Get-SecretValue "RABBITMQ_PASSWORD"
    $body = @{
        properties = @{ content_type = $ContentType }
        routing_key = $RoutingKey
        payload = $Payload
        payload_encoding = "string"
    } | ConvertTo-Json -Depth 8 -Compress
    $requestFile = Join-Path $env:TEMP ("ircs-02809-rabbit-publish-" + [guid]::NewGuid() + ".json")
    $responseFile = Join-Path $env:TEMP ("ircs-02809-rabbit-response-" + [guid]::NewGuid() + ".json")
    [System.IO.File]::WriteAllText($requestFile, $body, [System.Text.UTF8Encoding]::new($false))
    try {
        $code = & curl.exe -sS -u "admin:$rabbitPassword" `
            -H "Content-Type: application/json" `
            -o $responseFile `
            -w "%{http_code}" `
            --data-binary "@$requestFile" `
            "$RabbitBaseUrl/api/exchanges/%2F/$Exchange/publish"
        $text = if (Test-Path $responseFile) { Get-Content -LiteralPath $responseFile -Raw } else { "" }
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
    $lines = kubectl -n ircs-dev exec rabbitmq-0 -- rabbitmqctl list_queues name messages consumers
    $line = $lines | Where-Object { $_ -match "^$([regex]::Escape($Name))\s" } | Select-Object -First 1
    if (-not $line) {
        throw "queue not found: $Name"
    }
    return $line -split "\s+"
}

function Assert-QueueEmpty {
    param([string]$Name)
    $parts = Get-QueueParts $Name
    if ([int]$parts[1] -ne 0) {
        throw "queue $Name has $($parts[1]) messages"
    }
}

function Get-EsDocument {
    param(
        [string]$Index,
        [string]$Id
    )
    $elasticPassword = Get-SecretValue "ELASTICSEARCH_PASSWORD"
    $responseFile = Join-Path $env:TEMP ("ircs-02809-es-doc-" + [guid]::NewGuid() + ".json")
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
    $elasticPassword = Get-SecretValue "ELASTICSEARCH_PASSWORD"
    & curl.exe -sS -u "elastic:$elasticPassword" -X DELETE "$ElasticsearchBaseUrl/$Index/_doc/$Id" | Out-Null
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

function Wait-DbReady {
    $lastState = "not checked"
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        Start-Sleep -Seconds $WaitSeconds
        $state = Invoke-SqlScalar @"
select coalesce(normalization_status, '') || '|' || coalesce(title, '')
from raw_videos
where id = '$rawVideoId';
"@
        $lastState = $state
        if ($state -eq "READY|$expectedTitle") {
            return
        }
    }
    throw "raw video did not become READY with expected title; last state: $lastState"
}

function Assert-NormalizedDbFields {
    $state = Invoke-SqlScalar @"
select
  coalesce(year, '') || '|' ||
  coalesce(raw_language_str, '') || '|' ||
  coalesce(score::text, '') || '|' ||
  coalesce(aggregation_status, '')
from raw_videos
where id = '$rawVideoId';
"@
    if ($state -ne "$expectedYear|$expectedLanguage|$expectedScore|BOUND") {
        throw "normalized DB fields did not match expected projection, actual=$state"
    }
}

function Wait-EsRawDocument {
    $lastState = "not checked"
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        Start-Sleep -Seconds $WaitSeconds
        $doc = Get-EsDocument "ircs_raw_video" $rawVideoId
        if ($null -ne $doc -and $doc.found) {
            $source = $doc._source
            $languages = ConvertTo-StringArray $source.rawLanguages
            $scoreMatches = $false
            if ($null -ne $source.score) {
                $scoreMatches = [Math]::Abs(([double]$source.score) - $expectedScore) -lt 0.001
            }
            $lastState = "found title='$($source.title)' status='$($source.normalizationStatus)' year='$($source.year)' score='$($source.score)' languages='$($languages -join ',')'"
            if ($source.title -eq $expectedTitle `
                    -and $source.normalizationStatus -eq "READY" `
                    -and $source.year -eq $expectedYear `
                    -and $scoreMatches `
                    -and ($languages -contains $expectedLanguage)) {
                return
            }
        } else {
            $lastState = "missing"
        }
    }
    throw "Elasticsearch raw document did not reach expected normalization projection; last state: $lastState"
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

function Cleanup-SmokeData {
    $sql = @"
delete from unified_video_actors where unified_video_id in (select id from unified_videos where title = '$expectedTitle');
delete from unified_video_directors where unified_video_id in (select id from unified_videos where title = '$expectedTitle');
delete from unified_video_genres where unified_video_id in (select id from unified_videos where title = '$expectedTitle');
delete from unified_video_standard_languages where unified_video_id in (select id from unified_videos where title = '$expectedTitle');
delete from unified_video_standard_areas where unified_video_id in (select id from unified_videos where title = '$expectedTitle');
delete from raw_video_unified_video where raw_video_id = '$rawVideoId'
  or unified_video_id in (select id from unified_videos where title = '$expectedTitle');
delete from video_raw_genres where video_id = '$rawVideoId'
  or raw_genre_id in (select id from raw_genres where source_value in ('$expectedGenreA', '$expectedGenreB'));
delete from video_raw_languages where video_id = '$rawVideoId'
  or raw_language_id in (select id from raw_languages where source_value = '$expectedLanguage');
delete from video_raw_areas where video_id = '$rawVideoId'
  or raw_area_id in (select id from raw_areas where source_value = '$expectedArea');
delete from raw_videos where id = '$rawVideoId' or source_hash = '$sourceHash';
delete from unified_videos where title = '$expectedTitle';
delete from raw_genres where source_value in ('$expectedGenreA', '$expectedGenreB');
delete from raw_languages where source_value = '$expectedLanguage';
delete from raw_areas where source_value = '$expectedArea';
"@
    Exec-Sql $sql | Out-Null
}

function Assert-NoDbResidue {
    $residue = Invoke-SqlScalar @"
select
  (select count(*) from raw_videos where id = '$rawVideoId' or source_hash = '$sourceHash')::text
  || '|' ||
  (select count(*) from video_raw_genres where video_id = '$rawVideoId')::text
  || '|' ||
  (select count(*) from video_raw_languages where video_id = '$rawVideoId')::text
  || '|' ||
  (select count(*) from video_raw_areas where video_id = '$rawVideoId')::text
  || '|' ||
  (select count(*) from raw_video_unified_video where raw_video_id = '$rawVideoId')::text
  || '|' ||
  (select count(*) from unified_videos where title = '$expectedTitle')::text
  || '|' ||
  (select count(*) from raw_genres where source_value in ('$expectedGenreA', '$expectedGenreB'))::text
  || '|' ||
  (select count(*) from raw_languages where source_value = '$expectedLanguage')::text
  || '|' ||
  (select count(*) from raw_areas where source_value = '$expectedArea')::text;
"@
    if ($residue -ne "0|0|0|0|0|0|0|0|0") {
        throw "smoke cleanup left DB residue: $residue"
    }
}

try {
    $rabbitPf = Start-PortForward "svc/rabbitmq-svc" $RabbitPort 15672 $rabbitPfOut $rabbitPfErr
    $esPf = Start-PortForward "svc/elasticsearch-svc" $ElasticsearchPort 9200 $esPfOut $esPfErr
    Cleanup-SmokeData
    Remove-EsDocument "ircs_raw_video" $rawVideoId

    $setupSql = @"
insert into raw_videos (
    id, created_at, updated_at, version, source_vid, source_hash, data_hash, title,
    raw_metadata, enrichment_status, normalization_status, normalization_retry_count,
    aggregation_status
) values (
    '$rawVideoId', now(), now(), 0, '02809-raw-$stamp', '$sourceHash', 'codex02809data$stamp',
    'Codex 02809 Pending Raw $stamp',
    '{"title":"$expectedTitle","year":"$expectedYear","genreNames":["$expectedGenreA","$expectedGenreB"],"language":"$expectedLanguage","area":"$expectedArea","score":"$expectedScore","description":"Codex 02809 normalization live smoke fixture"}'::jsonb,
    'SUCCESS', 'PENDING', 0, 'BOUND'
);
"@
    Exec-Sql $setupSql | Out-Null

    $total = 5
    $i = 1

    Invoke-RabbitPublish "x.process" "video.normalize" $rawVideoId "text/plain"
    Wait-DbReady
    Mark "normalization worker consumes q.normalize.video and marks raw video READY" $i $total; $i++

    Assert-NormalizedDbFields
    Mark "normalization writes normalized raw fields from raw_metadata without triggering aggregation" $i $total; $i++

    Wait-EsRawDocument
    Mark "normalization enqueues raw runtime search sync and ES raw document is refreshed" $i $total; $i++

    foreach ($queue in @(
        "q.normalize.video",
        "q.normalize.video.dlq"
    )) {
        Assert-QueueEmpty $queue
    }
    Mark "normalize queue and DLQ are empty for the smoke window" $i $total; $i++

    Cleanup-SmokeData
    Remove-EsDocument "ircs_raw_video" $rawVideoId
    Wait-EsMissing
    Assert-NoDbResidue
    Mark "smoke cleanup leaves no DB/ES fixture residue" $i $total
} finally {
    try { Cleanup-SmokeData } catch { Write-Warning $_ }
    try { Remove-EsDocument "ircs_raw_video" $rawVideoId } catch { Write-Warning $_ }
    if ($PurgeSmokeQueuesOnCleanup) {
        foreach ($queue in @(
            "q.normalize.video",
            "q.normalize.video.dlq"
        )) {
            try { kubectl -n ircs-dev exec rabbitmq-0 -- rabbitmqctl purge_queue $queue | Out-Null } catch { Write-Warning $_ }
        }
    }
    if ($rabbitPf -and -not $rabbitPf.HasExited) {
        Stop-Process -Id $rabbitPf.Id -Force -ErrorAction SilentlyContinue
    }
    if ($esPf -and -not $esPf.HasExited) {
        Stop-Process -Id $esPf.Id -Force -ErrorAction SilentlyContinue
    }
}
