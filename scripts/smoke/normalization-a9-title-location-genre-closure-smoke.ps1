param(
    [string]$Namespace = "ircs-dev",
    [string]$RabbitBaseUrl = "http://127.0.0.1:19094",
    [int]$RabbitPort = 19094,
    [int]$WaitAttempts = 40,
    [int]$WaitSeconds = 2,
    [switch]$FailOnSkip
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$stamp = Get-Date -Format "yyyyMMddHHmmss"
$trace = "02816-a9-normalization-$stamp"
$rawVideoId = [guid]::NewGuid().ToString()
$dataSourceId = [guid]::NewGuid().ToString()
$sourceHash = "codex02816a9hash$stamp"
$dataSourceName = "Codex 02816 A9 Data Source $stamp"

$expectedTitle = "庆余年 第2季:特别篇"
$expectedAliasTitle = "Joy of Life 2"
$expectedSeason = "2"
$expectedSubtitle = "特别篇"
$expectedLanguage = "国粤双语/日语中字"
$expectedArea = "港台,日本地区"
$genreValues = @("纪录片", "纪录", "微短剧", "短剧", "古装剧", "古装")
$languageValues = @("国粤双语", "国语", "粤语", "日语中字", "日语")
$areaValues = @("港台", "香港", "台湾", "日本地区", "日本")

$rabbitPfOut = Join-Path $env:TEMP "ircs-02816-a9-rabbit-pf.out.log"
$rabbitPfErr = Join-Path $env:TEMP "ircs-02816-a9-rabbit-pf.err.log"
$rabbitPf = $null
$script:rabbitPassword = $null
$script:baselineGenreIds = @()
$script:baselineLanguageIds = @()
$script:baselineAreaIds = @()

function New-SmokeResult {
    param([string]$Status, [string]$Reason, [hashtable]$Details = @{})
    [ordered]@{
        task = "028-16-A9"
        slice = "title-location-genre-pipeline-first-closure"
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
    param([string]$Status, [string]$Reason, [hashtable]$Details = @{})
    New-SmokeResult -Status $Status -Reason $Reason -Details $Details
    if ($Status -eq "FAILED") { exit 1 }
    if ($Status -eq "SKIPPED" -and $FailOnSkip) { exit 2 }
    exit 0
}

function Mark {
    param([string]$Name, [int]$Index, [int]$Total)
    Write-Output ("MARK {0}/{1} {2}" -f $Index, $Total, $Name)
}

function Sql-Literal {
    param([AllowNull()][string]$Value)
    if ($null -eq $Value) { return "null" }
    return "'" + $Value.Replace("'", "''") + "'"
}

function Sql-InList {
    param([string[]]$Values)
    return ($Values | ForEach-Object { Sql-Literal $_ }) -join ", "
}

function Sql-UuidArray {
    param([string[]]$Values)
    if ($null -eq $Values -or $Values.Count -eq 0) {
        return "array[]::uuid[]"
    }
    return "array[" + (($Values | ForEach-Object { "'" + $_.Replace("'", "''") + "'::uuid" }) -join ", ") + "]"
}

function Invoke-PostgresSqlFile {
    param(
        [string]$Sql,
        [switch]$Rows
    )
    $sqlFileName = "ircs-02816-a9-$([System.Guid]::NewGuid().ToString('N')).sql"
    $localSqlDir = Join-Path ".\build\smoke-tmp" "normalization-a9"
    New-Item -ItemType Directory -Path $localSqlDir -Force | Out-Null
    $localSql = Join-Path $localSqlDir $sqlFileName
    $remoteSql = "/tmp/$sqlFileName"
    [System.IO.File]::WriteAllText($localSql, $Sql, [System.Text.UTF8Encoding]::new($false))
    try {
        $copyOutput = $null
        for ($attempt = 1; $attempt -le 3; $attempt++) {
            $copyResult = Invoke-NativeCommand @("kubectl", "-n", $Namespace, "cp", $localSql, "postgres-0:$remoteSql")
            $copyOutput = $copyResult.output
            if ($copyResult.exitCode -eq 0) {
                break
            }
            if ($attempt -eq 3 -or -not (Test-TransientKubectlOutput $copyOutput)) {
                throw "kubectl cp SQL failed with exit code $($copyResult.exitCode): $($copyOutput | Out-String)"
            }
            Start-Sleep -Seconds 2
        }
        if ($Rows) {
            $result = $null
            for ($attempt = 1; $attempt -le 3; $attempt++) {
                $commandResult = Invoke-NativeCommand @("kubectl", "-n", $Namespace, "exec", "postgres-0", "--", "psql", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", "ircs", "-t", "-A", "-f", $remoteSql)
                $result = $commandResult.output
                if ($commandResult.exitCode -eq 0) {
                    break
                }
                if ($attempt -eq 3 -or -not (Test-TransientKubectlOutput $result)) {
                    throw "psql rows failed with exit code $($commandResult.exitCode): $($result | Out-String)"
                }
                Start-Sleep -Seconds 2
            }
            return @(Remove-KubectlDiagnosticLines $result | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object { $_.Trim() })
        }
        $result = $null
        for ($attempt = 1; $attempt -le 3; $attempt++) {
            $commandResult = Invoke-NativeCommand @("kubectl", "-n", $Namespace, "exec", "postgres-0", "--", "psql", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", "ircs", "-f", $remoteSql)
            $result = $commandResult.output
            if ($commandResult.exitCode -eq 0) {
                break
            }
            if ($attempt -eq 3 -or -not (Test-TransientKubectlOutput $result)) {
                throw "psql failed with exit code $($commandResult.exitCode): $($result | Out-String)"
            }
            Start-Sleep -Seconds 2
        }
        return @()
    } finally {
        Remove-Item -LiteralPath $localSql -Force -ErrorAction SilentlyContinue
        $previousErrorAction = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            kubectl -n $Namespace exec postgres-0 -- rm -f $remoteSql 2>$null | Out-Null
        } catch {
        } finally {
            $ErrorActionPreference = $previousErrorAction
        }
    }
}

function Invoke-NativeCommand {
    param([string[]]$Command)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $executable = $Command[0]
        $commandArgs = @($Command | Select-Object -Skip 1)
        $output = & $executable @commandArgs 2>&1
        return @{
            exitCode = $LASTEXITCODE
            output = @($output)
        }
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Test-TransientKubectlOutput {
    param([object[]]$Output)
    $text = ($Output | Out-String)
    return $text -match "Unknown stream id|TLS handshake timeout|http2: client connection lost|websocket|connection reset"
}

function Remove-KubectlDiagnosticLines {
    param([object[]]$Output)
    return @($Output | Where-Object {
            $line = [string]$_
            $line -notmatch "^[EWI][0-9]{4}\s" -and
            $line -notmatch "^error:\s" -and
            $line -notmatch "^command terminated with exit code"
        })
}

function Exec-Sql {
    param([string]$Sql)
    Invoke-PostgresSqlFile -Sql $Sql | Out-Null
}

function Invoke-SqlRows {
    param([string]$Sql)
    return Invoke-PostgresSqlFile -Sql $Sql -Rows
}

function Invoke-SqlScalar {
    param([string]$Sql)
    $rows = @(Invoke-SqlRows $Sql)
    if ($rows.Count -eq 0) { return $null }
    return $rows[-1]
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
    Remove-Item -LiteralPath $rabbitPfOut, $rabbitPfErr -ErrorAction SilentlyContinue
    $pf = Start-Process -FilePath kubectl `
        -ArgumentList @("-n", $Namespace, "port-forward", "svc/rabbitmq-svc", "$($RabbitPort):15672") `
        -RedirectStandardOutput $rabbitPfOut `
        -RedirectStandardError $rabbitPfErr `
        -WindowStyle Hidden `
        -PassThru
    Start-Sleep -Seconds 5
    if ($pf.HasExited) {
        $err = if (Test-Path $rabbitPfErr) { Get-Content -LiteralPath $rabbitPfErr -Raw } else { "" }
        Finish-Smoke "SKIPPED" "PORT_FORWARD_UNAVAILABLE" @{ error = $err }
    }
    return $pf
}

function Stop-PortForwardByPort {
    param([int]$Port)
    Get-CimInstance Win32_Process -Filter "name = 'kubectl.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match "port-forward" -and $_.CommandLine -match ([regex]::Escape("$($Port):")) } |
        ForEach-Object {
            try { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue } catch { }
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
        Finish-Smoke "SKIPPED" "KUBERNETES_SECRET_MISSING" @{ key = $Key; output = (($value64 | Out-String).Trim()) }
    }
    return [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($value64))
}

function New-RabbitHeaders {
    if ([string]::IsNullOrWhiteSpace($script:rabbitPassword)) {
        $script:rabbitPassword = Get-SecretValue "RABBITMQ_PASSWORD"
    }
    $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("admin:$($script:rabbitPassword)"))
    return @{ Authorization = "Basic $basic" }
}

function Get-RabbitQueue {
    param([string]$QueueName)
    Invoke-RestMethod -Method Get -Headers (New-RabbitHeaders) -Uri "$RabbitBaseUrl/api/queues/%2F/$QueueName" -TimeoutSec 10
}

function Invoke-RabbitPublish {
    $body = @{
        properties = @{ content_type = "text/plain"; message_id = $trace }
        routing_key = "video.normalize"
        payload = $rawVideoId
        payload_encoding = "string"
    } | ConvertTo-Json -Depth 8 -Compress
    Invoke-RestMethod -Method Post -Headers (New-RabbitHeaders) -ContentType "application/json" -Body $body -Uri "$RabbitBaseUrl/api/exchanges/%2F/x.process/publish" -TimeoutSec 10
}

function Assert-QueueBaseline {
    $details = @{}
    foreach ($queueName in @("q.normalize.video", "q.normalize.video.dlq")) {
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
    foreach ($queueName in $details.Keys) {
        if ($details[$queueName].messages -ne 0) {
            Finish-Smoke "SKIPPED" "QUEUE_BASELINE_NOT_EMPTY" $details
        }
    }
}

function Assert-QueuesEmpty {
    $details = @{}
    foreach ($queueName in @("q.normalize.video", "q.normalize.video.dlq")) {
        $queue = Get-RabbitQueue $queueName
        $details[$queueName] = @{
            messages = [int]$queue.messages
            consumers = [int]$queue.consumers
            ready = [int]$queue.messages_ready
            unacked = [int]$queue.messages_unacknowledged
        }
        if ([int]$queue.messages -ne 0) { throw "queue $queueName has $($queue.messages) messages" }
    }
    return $details
}

function Capture-BaselineRawIds {
    $script:baselineGenreIds = Invoke-SqlRows "select id::text from raw_genres where source_value in ($(Sql-InList $genreValues));"
    $script:baselineLanguageIds = Invoke-SqlRows "select id::text from raw_languages where source_value in ($(Sql-InList $languageValues));"
    $script:baselineAreaIds = Invoke-SqlRows "select id::text from raw_areas where source_value in ($(Sql-InList $areaValues));"
}

function Cleanup-SmokeData {
    $sql = @"
delete from video_raw_genres where video_id = '$rawVideoId';
delete from video_raw_languages where video_id = '$rawVideoId';
delete from video_raw_areas where video_id = '$rawVideoId';
delete from raw_videos where id = '$rawVideoId' or source_hash = $(Sql-Literal $sourceHash);
delete from raw_genres rg
 where rg.source_value in ($(Sql-InList $genreValues))
   and rg.id <> all($(Sql-UuidArray $script:baselineGenreIds))
   and not exists (select 1 from video_raw_genres vrg where vrg.raw_genre_id = rg.id);
delete from raw_languages rl
 where rl.source_value in ($(Sql-InList $languageValues))
   and rl.id <> all($(Sql-UuidArray $script:baselineLanguageIds))
   and not exists (select 1 from video_raw_languages vrl where vrl.raw_language_id = rl.id);
delete from raw_areas ra
 where ra.source_value in ($(Sql-InList $areaValues))
   and ra.id <> all($(Sql-UuidArray $script:baselineAreaIds))
   and not exists (select 1 from video_raw_areas vra where vra.raw_area_id = ra.id);
delete from data_sources where id = '$dataSourceId' or name = $(Sql-Literal $dataSourceName);
"@
    Exec-Sql $sql
}

function Seed-SmokeData {
    $metadata = @{
        title = "庆余年 第二季：特别篇 2026 1080p WEB-DL"
        year = "2026"
        aliasTitle = "庆余年 第2季 / Joy of Life 2 / Joy of Life 2 / 慶餘年 第二季"
        genreNames = "纪录片/微短剧/短剧/古装剧"
        language = $expectedLanguage
        area = $expectedArea
    } | ConvertTo-Json -Depth 8 -Compress
    $sql = @"
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
    '$rawVideoId', now(), now(), 0, $(Sql-Literal "02816-a9-raw-$stamp"), $(Sql-Literal $sourceHash),
    $(Sql-Literal "codex02816a9data$stamp"), $(Sql-Literal "Codex 02816 A9 Pending Raw $stamp"),
    $(Sql-Literal $metadata)::jsonb, '$dataSourceId', 'SUCCESS', 'PENDING', 0, 'BOUND'
);
"@
    Exec-Sql $sql
}

function Wait-DbReady {
    $lastState = "not checked"
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        Start-Sleep -Seconds $WaitSeconds
        $state = Invoke-SqlScalar @"
select coalesce(normalization_status, '') || '|' ||
       coalesce(title, '') || '|' ||
       coalesce(alias_title, '') || '|' ||
       coalesce(season::text, '') || '|' ||
       coalesce(subtitle, '') || '|' ||
       coalesce(raw_language_str, '') || '|' ||
       coalesce(area, '')
from raw_videos
where id = '$rawVideoId';
"@
        $lastState = $state
        if ($state -eq "READY|$expectedTitle|$expectedAliasTitle|$expectedSeason|$expectedSubtitle|$expectedLanguage|$expectedArea") {
            return
        }
    }
    throw "raw video did not become READY with expected normalized fields; last state: $lastState"
}

function Assert-RelationValues {
    param([string]$Sql, [string[]]$Expected, [string]$Name)
    $actual = @(Invoke-SqlRows $Sql)
    $missing = @($Expected | Where-Object { $actual -notcontains $_ })
    if ($missing.Count -gt 0) {
        throw "$Name relation values missing: $($missing -join ','); actual=$($actual -join ',')"
    }
}

function Assert-NoDbResidue {
    $residue = Invoke-SqlScalar @"
select
  (select count(*) from raw_videos where id = '$rawVideoId' or source_hash = $(Sql-Literal $sourceHash))::text || '|' ||
  (select count(*) from data_sources where id = '$dataSourceId' or name = $(Sql-Literal $dataSourceName))::text || '|' ||
  (select count(*) from raw_genres rg where rg.source_value in ($(Sql-InList $genreValues)) and rg.id <> all($(Sql-UuidArray $script:baselineGenreIds)))::text || '|' ||
  (select count(*) from raw_languages rl where rl.source_value in ($(Sql-InList $languageValues)) and rl.id <> all($(Sql-UuidArray $script:baselineLanguageIds)))::text || '|' ||
  (select count(*) from raw_areas ra where ra.source_value in ($(Sql-InList $areaValues)) and ra.id <> all($(Sql-UuidArray $script:baselineAreaIds)))::text;
"@
    if ($residue -ne "0|0|0|0|0") {
        throw "smoke cleanup left DB residue: $residue"
    }
}

try {
    Test-KubectlAccess
    $rabbitPf = Start-PortForward
    Assert-QueueBaseline
    Mark "normalization consumer is ready and queues are clean" 1 5

    Capture-BaselineRawIds
    Cleanup-SmokeData
    Seed-SmokeData

    $publishResult = Invoke-RabbitPublish
    if (-not $publishResult.routed) {
        Finish-Smoke "FAILED" "NORMALIZE_FIXTURE_NOT_ROUTED" @{ publishResult = $publishResult }
    }
    Wait-DbReady
    Mark "q.normalize.video fixture is consumed and title/alias/season/subtitle scalars are normalized" 2 5

    Assert-RelationValues "select rg.source_value from video_raw_genres vrg join raw_genres rg on rg.id = vrg.raw_genre_id where vrg.video_id = '$rawVideoId';" $genreValues "genre"
    Assert-RelationValues "select rl.source_value from video_raw_languages vrl join raw_languages rl on rl.id = vrl.raw_language_id where vrl.video_id = '$rawVideoId';" $languageValues "language"
    Assert-RelationValues "select ra.source_value from video_raw_areas vra join raw_areas ra on ra.id = vra.raw_area_id where vra.video_id = '$rawVideoId';" $areaValues "area"
    Mark "genre/language/area alias relation rows are written" 3 5

    Cleanup-SmokeData
    Assert-NoDbResidue
    Mark "DB fixture residue is cleaned" 4 5

    $queueDetails = Assert-QueuesEmpty
    Mark "normalization Rabbit queue remains empty after smoke" 5 5
    Finish-Smoke "PASSED" "NORMALIZATION_A9_PIPELINE_CLOSURE_SMOKE_PASSED" @{
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
    if ($rabbitPf -and -not $rabbitPf.HasExited) {
        Stop-Process -Id $rabbitPf.Id -Force -ErrorAction SilentlyContinue
    }
    Stop-PortForwardByPort $RabbitPort
}
