param(
    [string]$Namespace = "ircs-dev",
    [string]$RabbitBaseUrl = "http://127.0.0.1:19084",
    [int]$RabbitPort = 19084,
    [string]$ElasticsearchBaseUrl = "http://127.0.0.1:19208",
    [int]$ElasticsearchPort = 19208,
    [int]$WaitAttempts = 40,
    [int]$WaitSeconds = 2,
    [switch]$FailOnSkip
)

$ErrorActionPreference = "Stop"

$stamp = Get-Date -Format "yyyyMMddHHmmss"
$trace = "02808-normalization-deep-$stamp"

$rawVideoId = [guid]::NewGuid().ToString()
$dataSourceId = [guid]::NewGuid().ToString()
$standardGenreAId = [guid]::NewGuid().ToString()
$standardGenreBId = [guid]::NewGuid().ToString()
$standardLanguageId = [guid]::NewGuid().ToString()
$standardAreaId = [guid]::NewGuid().ToString()
$standardCategoryId = [guid]::NewGuid().ToString()

$sourceHash = "codex02808hash$stamp"
$expectedTitle = "Codex 02808 Deep Mapping Raw $stamp"
$expectedYear = "2026"
$expectedScore = "8.4"
$expectedGenreA = "Codex 02808 Genre $stamp A"
$expectedGenreB = "Codex 02808 Genre $stamp B"
$expectedLanguage = "L$($stamp.Substring(2))"
$expectedArea = "Codex02808Area$stamp"
$expectedActorA = "Codex 02808 Actor $stamp A"
$expectedActorB = "Codex 02808 Actor $stamp B"
$expectedDirector = "Codex 02808 Director $stamp"
$expectedCategoryCode = "codex02808cat$stamp"
$expectedCategoryName = "Codex 02808 Category $stamp"
$dataSourceName = "Codex 02808 Data Source $stamp"

$rabbitPfOut = Join-Path $env:TEMP "ircs-02808-rabbit-pf.out.log"
$rabbitPfErr = Join-Path $env:TEMP "ircs-02808-rabbit-pf.err.log"
$esPfOut = Join-Path $env:TEMP "ircs-02808-es-pf.out.log"
$esPfErr = Join-Path $env:TEMP "ircs-02808-es-pf.err.log"
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
        task = "028-08"
        slice = "normalization-deep-mapping-live-smoke"
        status = $Status
        reason = $Reason
        checkedAt = (Get-Date).ToUniversalTime().ToString("o")
        namespace = $Namespace
        trace = $trace
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

function Exec-Sql {
    param([string]$Sql)
    $Sql | kubectl -n $Namespace exec -i postgres-0 -- psql -v ON_ERROR_STOP=1 -U postgres -d ircs | Out-Null
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

function Stop-PortForwardByPort {
    param([int[]]$Ports)
    $pattern = ($Ports | ForEach-Object { [regex]::Escape(("{0}:" -f $_)) }) -join "|"
    Get-CimInstance Win32_Process -Filter "name = 'kubectl.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match "port-forward" -and $_.CommandLine -match $pattern } |
        ForEach-Object {
            try {
                Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
            } catch {
            }
        }
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

function New-RabbitHeaders {
    $rabbitPassword = Get-RabbitPassword
    $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("admin:$($rabbitPassword)"))
    return @{ Authorization = "Basic $basic" }
}

function Get-RabbitQueue {
    param([string]$QueueName)
    Invoke-RestMethod `
        -Method Get `
        -Headers (New-RabbitHeaders) `
        -Uri "$RabbitBaseUrl/api/queues/%2F/$QueueName" `
        -TimeoutSec 10
}

function Invoke-RabbitPublish {
    param(
        [string]$Exchange,
        [string]$RoutingKey,
        [string]$Payload,
        [string]$ContentType = "text/plain"
    )
    $body = @{
        properties = @{
            content_type = $ContentType
            message_id = $trace
        }
        routing_key = $RoutingKey
        payload = $Payload
        payload_encoding = "string"
    } | ConvertTo-Json -Depth 8 -Compress
    Invoke-RestMethod `
        -Method Post `
        -Headers (New-RabbitHeaders) `
        -ContentType "application/json" `
        -Body $body `
        -Uri "$RabbitBaseUrl/api/exchanges/%2F/$Exchange/publish" `
        -TimeoutSec 10
}

function Get-EsDocument {
    param([string]$Index, [string]$Id)
    $elasticPassword = Get-ElasticPassword
    $responseFile = Join-Path $env:TEMP ("ircs-02808-es-doc-" + [guid]::NewGuid() + ".json")
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
    param([string]$Index, [string]$Id)
    $elasticPassword = Get-ElasticPassword
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

function Assert-QueueBaseline {
    $queues = @(
        "q.normalize.video",
        "q.normalize.video.dlq"
    )
    $details = @{}
    foreach ($queueName in $queues) {
        $queue = Get-RabbitQueue $queueName
        $details[$queueName] = @{
            messages = [int]$queue.messages
            consumers = [int]$queue.consumers
            ready = [int]$queue.messages_ready
            unacked = [int]$queue.messages_unacknowledged
        }
    }
    if ($details["q.normalize.video"].consumers -lt 1) {
        Finish-Smoke "SKIPPED" "REQUIRED_CONSUMER_MISSING" $details
    }
    foreach ($queueName in $queues) {
        if ($details[$queueName].messages -ne 0) {
            Finish-Smoke "SKIPPED" "QUEUE_BASELINE_NOT_EMPTY" $details
        }
    }
}

function Assert-QueuesEmpty {
    $details = @{}
    foreach ($queueName in @(
        "q.normalize.video",
        "q.normalize.video.dlq"
    )) {
        $queue = Get-RabbitQueue $queueName
        $details[$queueName] = @{
            messages = [int]$queue.messages
            consumers = [int]$queue.consumers
            ready = [int]$queue.messages_ready
            unacked = [int]$queue.messages_unacknowledged
        }
        if ([int]$queue.messages -ne 0) {
            throw "queue $queueName has $($queue.messages) messages"
        }
    }
    return $details
}

function Cleanup-SmokeData {
    $sql = @"
delete from raw_video_unified_video where raw_video_id = '$rawVideoId'
  or unified_video_id in (select id from unified_videos where title = $(Sql-Literal $expectedTitle));
delete from unified_video_actors where unified_video_id in (select id from unified_videos where title = $(Sql-Literal $expectedTitle));
delete from unified_video_directors where unified_video_id in (select id from unified_videos where title = $(Sql-Literal $expectedTitle));
delete from unified_video_genres where unified_video_id in (select id from unified_videos where title = $(Sql-Literal $expectedTitle));
delete from unified_video_standard_languages where unified_video_id in (select id from unified_videos where title = $(Sql-Literal $expectedTitle));
delete from unified_video_standard_areas where unified_video_id in (select id from unified_videos where title = $(Sql-Literal $expectedTitle));
delete from unified_videos where title = $(Sql-Literal $expectedTitle);
delete from video_actors where video_id = '$rawVideoId';
delete from video_directors where video_id = '$rawVideoId';
delete from video_raw_genres where video_id = '$rawVideoId'
  or raw_genre_id in (select id from raw_genres where source_value in ($(Sql-Literal $expectedGenreA), $(Sql-Literal $expectedGenreB)));
delete from video_raw_languages where video_id = '$rawVideoId'
  or raw_language_id in (select id from raw_languages where source_value = $(Sql-Literal $expectedLanguage));
delete from video_raw_areas where video_id = '$rawVideoId'
  or raw_area_id in (select id from raw_areas where source_value = $(Sql-Literal $expectedArea));
delete from raw_videos where id = '$rawVideoId' or source_hash = $(Sql-Literal $sourceHash);
delete from raw_genres where source_value in ($(Sql-Literal $expectedGenreA), $(Sql-Literal $expectedGenreB));
delete from raw_languages where source_value = $(Sql-Literal $expectedLanguage);
delete from raw_areas where source_value = $(Sql-Literal $expectedArea);
delete from actors where name in ($(Sql-Literal $expectedActorA), $(Sql-Literal $expectedActorB));
delete from directors where name = $(Sql-Literal $expectedDirector);
delete from raw_category where data_source_id = '$dataSourceId' or source_code = $(Sql-Literal $expectedCategoryCode);
delete from standard_genre where id in ('$standardGenreAId', '$standardGenreBId')
  or name in ($(Sql-Literal $expectedGenreA), $(Sql-Literal $expectedGenreB));
delete from standard_languages where id = '$standardLanguageId' or name = $(Sql-Literal "Codex 02808 Language $stamp");
delete from standard_areas where id = '$standardAreaId' or name = $(Sql-Literal $expectedArea);
delete from standard_category where id = '$standardCategoryId' or slug = $(Sql-Literal $expectedCategoryCode);
delete from data_sources where id = '$dataSourceId' or name = $(Sql-Literal $dataSourceName);
"@
    Exec-Sql $sql
}

function Seed-SmokeData {
    $metadata = @{
        title = $expectedTitle
        year = $expectedYear
        genreNames = @($expectedGenreA, $expectedGenreB)
        language = $expectedLanguage
        area = $expectedArea
        score = $expectedScore
        description = "Codex 02808 normalization deep mapping fixture"
        actorNames = @($expectedActorA, $expectedActorB)
        directorNames = @($expectedDirector)
        rawTypeId = $expectedCategoryCode
        rawTypeName = $expectedCategoryName
        dataSourceId = $dataSourceId
    } | ConvertTo-Json -Depth 8 -Compress
    $metadataSql = Sql-Literal $metadata
    $sql = @"
insert into standard_genre (id, created_at, updated_at, version, name)
values
  ('$standardGenreAId', now(), now(), 0, $(Sql-Literal $expectedGenreA)),
  ('$standardGenreBId', now(), now(), 0, $(Sql-Literal $expectedGenreB));

insert into standard_languages (id, created_at, updated_at, version, name, code, english_name, native_name)
values ('$standardLanguageId', now(), now(), 0, $(Sql-Literal "Codex 02808 Language $stamp"), $(Sql-Literal $expectedLanguage), $(Sql-Literal "Codex Language $stamp"), $(Sql-Literal "Codex Native $stamp"));

insert into standard_areas (id, created_at, updated_at, version, name, code, region)
values ('$standardAreaId', now(), now(), 0, $(Sql-Literal $expectedArea), $(Sql-Literal "A$($stamp.Substring(8))"), 'codex');

insert into standard_category (id, created_at, updated_at, version, name, slug)
values ('$standardCategoryId', now(), now(), 0, $(Sql-Literal "Codex Standard Category $stamp"), $(Sql-Literal $expectedCategoryCode));

insert into data_sources (
    id, created_at, updated_at, version, name, base_url, list_path, detail_path, field_mapping
) values (
    '$dataSourceId', now(), now(), 0, $(Sql-Literal $dataSourceName),
    'https://codex.invalid', '/list', '/detail', '{}'::jsonb
);

insert into raw_videos (
    id, created_at, updated_at, version, source_vid, source_hash, data_hash, title,
    raw_metadata, data_source_id, enrichment_status, normalization_status,
    normalization_retry_count, aggregation_status
) values (
    '$rawVideoId', now(), now(), 0, $(Sql-Literal "02808-raw-$stamp"), $(Sql-Literal $sourceHash),
    $(Sql-Literal "codex02808data$stamp"), $(Sql-Literal "Codex 02808 Pending Raw $stamp"),
    $metadataSql::jsonb, '$dataSourceId', 'SUCCESS', 'PENDING', 0, 'BOUND'
);
"@
    Exec-Sql $sql
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

function Assert-DeepMappingRows {
    $state = Invoke-SqlScalar @"
select
  (select count(*) from video_actors where video_id = '$rawVideoId')::text || '|' ||
  (select count(*) from video_directors where video_id = '$rawVideoId')::text || '|' ||
  (select count(*) from video_raw_genres where video_id = '$rawVideoId')::text || '|' ||
  (select count(*) from video_raw_languages where video_id = '$rawVideoId')::text || '|' ||
  (select count(*) from video_raw_areas where video_id = '$rawVideoId')::text || '|' ||
  coalesce((select raw_language_str from raw_videos where id = '$rawVideoId'), '') || '|' ||
  coalesce((select aggregation_status from raw_videos where id = '$rawVideoId'), '');
"@
    if ($state -ne "2|1|2|1|1|$expectedLanguage|BOUND") {
        throw "deep mapping join rows did not match expected counts; actual=$state"
    }
}

function Assert-StandardMappings {
    $state = Invoke-SqlScalar @"
select
  (select count(*) from raw_genres where source_value in ($(Sql-Literal $expectedGenreA), $(Sql-Literal $expectedGenreB))
      and standard_genre_id in ('$standardGenreAId', '$standardGenreBId'))::text || '|' ||
  (select count(*) from raw_languages where source_value = $(Sql-Literal $expectedLanguage)
      and standard_language_id = '$standardLanguageId')::text || '|' ||
  (select count(*) from raw_areas where source_value = $(Sql-Literal $expectedArea)
      and standard_area_id = '$standardAreaId')::text || '|' ||
  (select count(*) from raw_category where data_source_id = '$dataSourceId'
      and source_code = $(Sql-Literal $expectedCategoryCode)
      and source_name = $(Sql-Literal $expectedCategoryName)
      and category_id = '$standardCategoryId')::text || '|' ||
  (select count(*) from raw_videos rv
      join raw_category rc on rv.data_source_category_id = rc.id
      where rv.id = '$rawVideoId'
        and rc.data_source_id = '$dataSourceId'
        and rc.category_id = '$standardCategoryId')::text;
"@
    if ($state -ne "2|1|1|1|1") {
        throw "standard mapping state did not match expected values; actual=$state"
    }
}

function Wait-EsRawDocument {
    $lastState = "not checked"
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        Start-Sleep -Seconds $WaitSeconds
        $doc = Get-EsDocument "ircs_raw_video" $rawVideoId
        if ($null -ne $doc -and $doc.found) {
            $source = $doc._source
            $genres = ConvertTo-StringArray $source.rawGenres
            $languages = ConvertTo-StringArray $source.rawLanguages
            $areas = ConvertTo-StringArray $source.rawAreas
            $actors = ConvertTo-StringArray $source.actors
            $directors = ConvertTo-StringArray $source.directors
            $lastState = "found title='$($source.title)' status='$($source.normalizationStatus)' category='$($source.categoryName)' actors='$($actors -join ',')'"
            if ($source.title -eq $expectedTitle `
                    -and $source.normalizationStatus -eq "READY" `
                    -and $source.categoryName -eq "Codex Standard Category $stamp" `
                    -and $source.sourceCategoryName -eq $expectedCategoryName `
                    -and ($genres -contains $expectedGenreA) `
                    -and ($genres -contains $expectedGenreB) `
                    -and ($languages -contains $expectedLanguage) `
                    -and ($areas -contains $expectedArea) `
                    -and ($actors -contains $expectedActorA) `
                    -and ($actors -contains $expectedActorB) `
                    -and ($directors -contains $expectedDirector)) {
                return
            }
        } else {
            $lastState = "missing"
        }
    }
    throw "Elasticsearch raw document did not reach expected deep mapping projection; last state: $lastState"
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
    $residue = Invoke-SqlScalar @"
select
  (select count(*) from raw_videos where id = '$rawVideoId' or source_hash = $(Sql-Literal $sourceHash))::text || '|' ||
  (select count(*) from raw_category where data_source_id = '$dataSourceId' or source_code = $(Sql-Literal $expectedCategoryCode))::text || '|' ||
  (select count(*) from data_sources where id = '$dataSourceId' or name = $(Sql-Literal $dataSourceName))::text || '|' ||
  (select count(*) from raw_genres where source_value in ($(Sql-Literal $expectedGenreA), $(Sql-Literal $expectedGenreB)))::text || '|' ||
  (select count(*) from raw_languages where source_value = $(Sql-Literal $expectedLanguage))::text || '|' ||
  (select count(*) from raw_areas where source_value = $(Sql-Literal $expectedArea))::text || '|' ||
  (select count(*) from actors where name in ($(Sql-Literal $expectedActorA), $(Sql-Literal $expectedActorB)))::text || '|' ||
  (select count(*) from directors where name = $(Sql-Literal $expectedDirector))::text || '|' ||
  (select count(*) from standard_genre where id in ('$standardGenreAId', '$standardGenreBId')
      or name in ($(Sql-Literal $expectedGenreA), $(Sql-Literal $expectedGenreB)))::text || '|' ||
  (select count(*) from standard_languages where id = '$standardLanguageId' or name = $(Sql-Literal "Codex 02808 Language $stamp"))::text || '|' ||
  (select count(*) from standard_areas where id = '$standardAreaId' or name = $(Sql-Literal $expectedArea))::text || '|' ||
  (select count(*) from standard_category where id = '$standardCategoryId' or slug = $(Sql-Literal $expectedCategoryCode))::text;
"@
    if ($residue -ne "0|0|0|0|0|0|0|0|0|0|0|0") {
        throw "smoke cleanup left DB residue: $residue"
    }
}

try {
    Test-KubectlAccess
    $rabbitPf = Start-PortForward "svc/rabbitmq-svc" $RabbitPort 15672 $rabbitPfOut $rabbitPfErr
    $esPf = Start-PortForward "svc/elasticsearch-svc" $ElasticsearchPort 9200 $esPfOut $esPfErr

    Assert-QueueBaseline
    Mark "normalization consumer is ready and queues are clean" 1 6

    Cleanup-SmokeData
    Remove-EsDocument "ircs_raw_video" $rawVideoId
    Seed-SmokeData

    $publishResult = Invoke-RabbitPublish "x.process" "video.normalize" $rawVideoId "text/plain"
    if (-not $publishResult.routed) {
        Finish-Smoke "FAILED" "NORMALIZE_FIXTURE_NOT_ROUTED" @{ publishResult = $publishResult }
    }
    Wait-DbReady
    Mark "q.normalize.video fixture is consumed and raw video becomes READY" 2 6

    Assert-DeepMappingRows
    Mark "actor/director and raw relation join rows are written" 3 6

    Assert-StandardMappings
    Mark "raw relations and raw category map to existing standard dictionaries" 4 6

    Wait-EsRawDocument
    Mark "runtime search sync refreshes ES document with deep mapping fields" 5 6

    Cleanup-SmokeData
    Remove-EsDocument "ircs_raw_video" $rawVideoId
    Wait-EsMissing
    Assert-NoDbResidue
    $queueDetails = Assert-QueuesEmpty
    Mark "smoke cleanup leaves no DB/ES residue and queues remain empty" 6 6

    Finish-Smoke "PASSED" "NORMALIZATION_DEEP_MAPPING_LIVE_SMOKE_PASSED" @{
        residue = "0"
        queues = $queueDetails
    }
} catch {
    Finish-Smoke "FAILED" "UNEXPECTED_ERROR" @{
        message = $_.Exception.Message
        stack = $_.ScriptStackTrace
    }
} finally {
    try { Cleanup-SmokeData } catch { }
    try { Remove-EsDocument "ircs_raw_video" $rawVideoId } catch { }
    if ($rabbitPf -and -not $rabbitPf.HasExited) {
        Stop-Process -Id $rabbitPf.Id -Force -ErrorAction SilentlyContinue
    }
    if ($esPf -and -not $esPf.HasExited) {
        Stop-Process -Id $esPf.Id -Force -ErrorAction SilentlyContinue
    }
    Stop-PortForwardByPort @($RabbitPort, $ElasticsearchPort)
}
