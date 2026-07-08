param(
    [string]$RabbitBaseUrl = "http://127.0.0.1:19072",
    [int]$RabbitPort = 19072
)

$ErrorActionPreference = "Stop"

$stamp = Get-Date -Format "yyyyMMddHHmmss"
$rabbitPfOut = Join-Path $env:TEMP "ircs-02805a-rabbit-pf.out.log"
$rabbitPfErr = Join-Path $env:TEMP "ircs-02805a-rabbit-pf.err.log"
$rabbitPf = $null

$memberId = [guid]::NewGuid().ToString()
$unifiedVideoId = [guid]::NewGuid().ToString()
$email = "codex.02805a.$stamp@example.invalid"

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

function Start-RabbitPortForward {
    Remove-Item -LiteralPath $rabbitPfOut, $rabbitPfErr -ErrorAction SilentlyContinue
    $pf = Start-Process -FilePath kubectl `
        -ArgumentList @("-n", "ircs-dev", "port-forward", "svc/rabbitmq-svc", "$($RabbitPort):15672") `
        -RedirectStandardOutput $rabbitPfOut `
        -RedirectStandardError $rabbitPfErr `
        -WindowStyle Hidden `
        -PassThru
    Start-Sleep -Seconds 5
    if ($pf.HasExited) {
        $err = if (Test-Path $rabbitPfErr) { Get-Content -Path $rabbitPfErr -Raw } else { "" }
        throw "rabbitmq port-forward exited: $err"
    }
    return $pf
}

function Get-RabbitPassword {
    $password64 = kubectl -n ircs-dev get secret ircs-dev-secrets -o jsonpath="{.data.RABBITMQ_PASSWORD}"
    if (-not $password64) {
        throw "RABBITMQ_PASSWORD not found"
    }
    return [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($password64))
}

function Invoke-RabbitPublish {
    param([hashtable]$Payload)
    $rabbitPassword = Get-RabbitPassword
    $payloadJson = $Payload | ConvertTo-Json -Depth 8 -Compress
    $body = @{
        properties = @{ content_type = "application/json" }
        routing_key = "interaction.progress"
        payload = $payloadJson
        payload_encoding = "string"
    } | ConvertTo-Json -Depth 8 -Compress
    $requestFile = Join-Path $env:TEMP ("ircs-02805a-rabbit-publish-" + [guid]::NewGuid() + ".json")
    $responseFile = Join-Path $env:TEMP ("ircs-02805a-rabbit-response-" + [guid]::NewGuid() + ".json")
    [System.IO.File]::WriteAllText($requestFile, $body, [System.Text.UTF8Encoding]::new($false))
    try {
        $code = & curl.exe -sS -u "admin:$rabbitPassword" `
            -H "Content-Type: application/json" `
            -o $responseFile `
            -w "%{http_code}" `
            --data-binary "@$requestFile" `
            "$RabbitBaseUrl/api/exchanges/%2F/x.interaction/publish"
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

function Wait-History {
    param(
        [int]$ExpectedProgress,
        [int]$ExpectedDuration,
        [string]$ExpectedEpisode
    )
    $sql = @"
select progress_seconds::text || '|' || duration_seconds::text || '|' || episode_name
from member_watch_histories
where member_id = '$memberId'
  and unified_video_id = '$unifiedVideoId';
"@
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        Start-Sleep -Seconds 2
        $value = Invoke-SqlScalar $sql
        if ($value -eq "$ExpectedProgress|$ExpectedDuration|$ExpectedEpisode") {
            return $value
        }
    }
    throw "history did not reach expected value $ExpectedProgress|$ExpectedDuration|$ExpectedEpisode"
}

function Get-QueueLine {
    param([string]$Name)
    $lines = kubectl -n ircs-dev exec rabbitmq-0 -- rabbitmqctl list_queues name messages consumers
    return $lines | Where-Object { $_ -match "^$([regex]::Escape($Name))\s" } | Select-Object -First 1
}

function Assert-QueueEmpty {
    param([string]$Name)
    $line = Get-QueueLine $Name
    if (-not $line) {
        throw "queue not found: $Name"
    }
    $parts = $line -split "\s+"
    if ([int]$parts[1] -ne 0) {
        throw "queue $Name has $($parts[1]) messages"
    }
}

function Cleanup-SmokeData {
    $sql = @"
delete from member_watch_histories
where member_id = '$memberId'
   or unified_video_id = '$unifiedVideoId';
delete from members where id = '$memberId' or email = '$email';
delete from unified_videos where id = '$unifiedVideoId';
"@
    Exec-Sql $sql | Out-Null
}

try {
    $rabbitPf = Start-RabbitPortForward
    Cleanup-SmokeData

    $setupSql = @"
insert into members (id, created_at, updated_at, version, email, password_hash, nickname, role, status)
values ('$memberId', now(), now(), 0, '$email', 'noop', 'Codex 02805A Smoke', 'MEMBER', 'ACTIVE');
insert into unified_videos (id, created_at, updated_at, version, title, metadata_status)
values ('$unifiedVideoId', now(), now(), 0, 'Codex 02805A Watch Progress', 'DIRTY');
"@
    Exec-Sql $setupSql | Out-Null

    $total = 4
    $i = 1

    $first = @{
        memberId = $memberId
        unifiedVideoId = $unifiedVideoId
        videoId = $null
        episodeId = $null
        episodeName = "第1集"
        progressSeconds = 42
        durationSeconds = 100
        timestamp = "2026-06-07T02:00:00Z"
    }
    Invoke-RabbitPublish $first
    Wait-History 42 100 "第1集" | Out-Null
    Mark "WATCH_PROGRESS consumer listens on q.interaction.watch_progress" $i $total; $i++
    Mark "consumer upserts member_watch_histories from fixture payload" $i $total; $i++

    $newer = @{
        memberId = $memberId
        unifiedVideoId = $unifiedVideoId
        videoId = $null
        episodeId = $null
        episodeName = " "
        progressSeconds = 88
        durationSeconds = 120
        timestamp = "2026-06-07T02:02:00Z"
    }
    $older = @{
        memberId = $memberId
        unifiedVideoId = $unifiedVideoId
        videoId = $null
        episodeId = $null
        episodeName = "旧进度"
        progressSeconds = 10
        durationSeconds = 120
        timestamp = "2026-06-07T02:01:00Z"
    }
    Invoke-RabbitPublish $newer
    Wait-History 88 120 "未知剧集" | Out-Null
    Invoke-RabbitPublish $older
    Start-Sleep -Seconds 4
    $latest = Invoke-SqlScalar @"
select progress_seconds::text || '|' || duration_seconds::text || '|' || episode_name
from member_watch_histories
where member_id = '$memberId'
  and unified_video_id = '$unifiedVideoId';
"@
    if ($latest -ne "88|120|未知剧集") {
        throw "older message overwrote latest progress: $latest"
    }
    Mark "duplicate messages keep latest timestamp per member/unified pair" $i $total; $i++

    Cleanup-SmokeData
    $residue = Invoke-SqlScalar @"
select
  (select count(*) from member_watch_histories where member_id = '$memberId' or unified_video_id = '$unifiedVideoId')::text
  || '|' ||
  (select count(*) from members where id = '$memberId' or email = '$email')::text
  || '|' ||
  (select count(*) from unified_videos where id = '$unifiedVideoId')::text;
"@
    if ($residue -ne "0|0|0") {
        throw "smoke cleanup left DB residue: $residue"
    }
    Assert-QueueEmpty "q.interaction.watch_progress"
    Assert-QueueEmpty "q.interaction.watch_progress.dlq"
    Mark "smoke cleanup leaves no DB residue and WATCH_PROGRESS queues empty" $i $total
} finally {
    try { Cleanup-SmokeData } catch { Write-Warning $_ }
    foreach ($queue in @("q.interaction.watch_progress", "q.interaction.watch_progress.dlq")) {
        try { kubectl -n ircs-dev exec rabbitmq-0 -- rabbitmqctl purge_queue $queue | Out-Null } catch { Write-Warning $_ }
    }
    if ($rabbitPf -and -not $rabbitPf.HasExited) {
        Stop-Process -Id $rabbitPf.Id -Force -ErrorAction SilentlyContinue
    }
}
