param(
    [string]$Namespace = "ircs-dev",
    [string]$ConfigBaseUrl = "http://127.0.0.1:19087",
    [int]$ConfigPort = 19087,
    [string]$RabbitBaseUrl = "http://127.0.0.1:19088",
    [int]$RabbitPort = 19088,
    [string]$ContentBaseUrl = "http://127.0.0.1:19089",
    [int]$ContentPort = 19089,
    [int]$WaitAttempts = 30,
    [int]$WaitSeconds = 2,
    [switch]$ConfirmLiveRabbit,
    [switch]$ProbeContentRead,
    [switch]$TemporarilyRemoveContentRuntimeOverride,
    [switch]$CreateTemporaryRawVideoFixture,
    [switch]$FailOnPartial,
    [switch]$FailOnSkip
)

$ErrorActionPreference = "Stop"

$taskId = "028-16-C2-S17"
$configKey = "app.content.internal.scraper-base-url"
$queueName = "q.content.config_changed"
$contentRuntimeOverrideEnvNames = @("APP_CONTENT_INTERNAL_SCRAPER_BASE_URL", "APP_CONTENT_SCRAPER_BASE_URL")
$runId = (Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss')
$temporaryValue = "http://content-config-live-smoke.invalid/$runId"
$temporaryDescription = "C2-S17 temporary live Rabbit config event smoke; restore immediately."
$temporaryRawVideoId = [guid]::NewGuid().ToString()
$temporaryRawVideoSourceVid = "C2-S17-PROBE-$runId"

$configPfOut = Join-Path $env:TEMP "ircs-02816c2-s17-config-pf.out.log"
$configPfErr = Join-Path $env:TEMP "ircs-02816c2-s17-config-pf.err.log"
$rabbitPfOut = Join-Path $env:TEMP "ircs-02816c2-s17-rabbit-pf.out.log"
$rabbitPfErr = Join-Path $env:TEMP "ircs-02816c2-s17-rabbit-pf.err.log"
$contentPfOut = Join-Path $env:TEMP "ircs-02816c2-s17-content-pf.out.log"
$contentPfErr = Join-Path $env:TEMP "ircs-02816c2-s17-content-pf.err.log"

$script:configPf = $null
$script:rabbitPf = $null
$script:contentPf = $null
$script:rabbitPassword = $null
$script:originalConfig = $null
$script:originalExists = $false
$script:tempWriteApplied = $false
$script:finalStatus = "SUCCESS"
$script:finalReason = "LIVE_CONFIG_EVENT_SMOKE_COMPLETED"
$script:abortStatus = $null
$script:abortReason = $null
$script:abortDetails = @{}
$script:contentRuntimeOverridePatchApplied = $false
$script:contentRuntimeOverrideOriginal = $null
$script:temporaryRawVideoFixtureCreated = $false
$script:details = [ordered]@{
    configKey = $configKey
    queue = $queueName
    temporaryValue = $temporaryValue
    contentRuntimeOverrideProbe = if ($TemporarilyRemoveContentRuntimeOverride) { "temporary-remove-and-restore" } else { "observe-only" }
    rawVideoFixture = if ($CreateTemporaryRawVideoFixture) { "create-and-clean-if-needed" } else { "observe-existing-only" }
}

function Mark {
    param([string]$Name, [int]$Index, [int]$Total)
    Write-Output ("MARK {0}/{1} {2}" -f $Index, $Total, $Name)
}

function New-SmokeResult {
    param(
        [string]$Status,
        [string]$Reason,
        [hashtable]$Details = @{}
    )
    [ordered]@{
        task = $taskId
        slice = "content-config-event-real-live-rabbit"
        status = $Status
        reason = $Reason
        checkedAt = (Get-Date).ToUniversalTime().ToString("o")
        namespace = $Namespace
        mode = if ($ConfirmLiveRabbit) { "live-confirmed" } else { "dry-run" }
        details = $Details
    } | ConvertTo-Json -Depth 16
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
    if ($Status -eq "PARTIAL" -and $FailOnPartial) {
        exit 3
    }
    exit 0
}

function Set-Detail {
    param([string]$Name, [AllowNull()][object]$Value)
    $script:details[$Name] = $Value
}

function Abort-Smoke {
    param(
        [string]$Status,
        [string]$Reason,
        [hashtable]$Details = @{}
    )
    $script:abortStatus = $Status
    $script:abortReason = $Reason
    $script:abortDetails = $Details
    throw "SMOKE_ABORT:${Status}:${Reason}"
}

function ConvertTo-SafeError {
    param([AllowNull()][object]$Value)
    if ($null -eq $Value) {
        return ""
    }
    return (($Value | Out-String).Trim())
}

function Sql-Literal {
    param([AllowNull()][string]$Value)
    if ($null -eq $Value) {
        return "null"
    }
    return "'" + $Value.Replace("'", "''") + "'"
}

function Get-Sha256 {
    param([AllowNull()][string]$Value)
    if ($null -eq $Value) {
        return $null
    }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
    $hash = [System.Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    return ([System.BitConverter]::ToString($hash)).Replace("-", "").ToLowerInvariant()
}

function Get-ValueEvidence {
    param([AllowNull()][string]$Value)
    [ordered]@{
        value = $Value
        length = if ($null -eq $Value) { $null } else { $Value.Length }
        sha256 = Get-Sha256 $Value
    }
}

function Invoke-Kubectl {
    param([string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & kubectl @Arguments 2>&1
        $exit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    [ordered]@{
        exitCode = $exit
        output = ConvertTo-SafeError $output
    }
}

function Invoke-KubectlRetry {
    param(
        [string[]]$Arguments,
        [int]$Attempts = 3,
        [int]$DelaySeconds = 3
    )
    $last = $null
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        $last = Invoke-Kubectl $Arguments
        if ($last.exitCode -eq 0) {
            return $last
        }
        if ($attempt -lt $Attempts) {
            Start-Sleep -Seconds $DelaySeconds
        }
    }
    return $last
}

function Assert-KubectlAvailable {
    $result = Invoke-KubectlRetry @("-n", $Namespace, "get", "pod")
    if ($result.exitCode -ne 0) {
        Abort-Smoke "SKIPPED" "KUBERNETES_UNAVAILABLE" $result
    }
    Set-Detail "podsPreview" (($result.output -split "`r?`n") | Select-Object -First 12)
}

function Assert-RolloutReady {
    param([string]$Deployment)
    $result = Invoke-KubectlRetry @("-n", $Namespace, "rollout", "status", "deployment/$Deployment", "--timeout=120s")
    if ($result.exitCode -ne 0) {
        Abort-Smoke "SKIPPED" "DEPLOYMENT_NOT_READY" @{
            deployment = $Deployment
            output = $result.output
            exitCode = $result.exitCode
        }
    }
    return $result.output
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
    $process = Start-Process -FilePath kubectl `
        -ArgumentList @("-n", $Namespace, "port-forward", $Service, "$($LocalPort):$RemotePort") `
        -RedirectStandardOutput $OutFile `
        -RedirectStandardError $ErrFile `
        -WindowStyle Hidden `
        -PassThru
    Start-Sleep -Seconds 5
    if ($process.HasExited) {
        $err = if (Test-Path $ErrFile) { Get-Content -LiteralPath $ErrFile -Raw } else { "" }
        Abort-Smoke "SKIPPED" "PORT_FORWARD_UNAVAILABLE" @{
            service = $Service
            localPort = $LocalPort
            remotePort = $RemotePort
            error = $err
        }
    }
    return $process
}

function Stop-PortForward {
    param([AllowNull()][System.Diagnostics.Process]$Process)
    if ($Process -and -not $Process.HasExited) {
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
    }
}

function Get-SecretValue {
    param([string]$Key)
    $result = Invoke-KubectlRetry @("-n", $Namespace, "get", "secret", "ircs-dev-secrets", "-o", "jsonpath={.data.$Key}")
    if ($result.exitCode -ne 0 -or [string]::IsNullOrWhiteSpace($result.output)) {
        Abort-Smoke "SKIPPED" "KUBERNETES_SECRET_MISSING" @{
            key = $Key
            exitCode = $result.exitCode
            output = $result.output
        }
    }
    return [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($result.output))
}

function Get-RabbitPassword {
    if ([string]::IsNullOrWhiteSpace($script:rabbitPassword)) {
        $script:rabbitPassword = Get-SecretValue "RABBITMQ_PASSWORD"
    }
    return $script:rabbitPassword
}

function Invoke-HttpJson {
    param(
        [string]$Method,
        [string]$Url,
        [AllowNull()][object]$Body = $null,
        [AllowNull()][string]$BasicAuth = $null
    )
    $requestFile = $null
    $responseFile = Join-Path $env:TEMP ("ircs-02816c2-s17-http-response-" + [guid]::NewGuid() + ".json")
    $args = @("-sS", "-o", $responseFile, "-w", "%{http_code}", "-X", $Method)
    if (-not [string]::IsNullOrWhiteSpace($BasicAuth)) {
        $args += @("-u", $BasicAuth)
    }
    if ($null -ne $Body) {
        $requestFile = Join-Path $env:TEMP ("ircs-02816c2-s17-http-request-" + [guid]::NewGuid() + ".json")
        $json = $Body | ConvertTo-Json -Depth 12 -Compress
        [System.IO.File]::WriteAllText($requestFile, $json, [System.Text.UTF8Encoding]::new($false))
        $args += @("-H", "Content-Type: application/json", "--data-binary", "@$requestFile")
    }
    $args += $Url
    try {
        $previous = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $codeOutput = & curl.exe @args 2>&1
        $exit = $LASTEXITCODE
        $ErrorActionPreference = $previous
        $statusCodeText = ConvertTo-SafeError $codeOutput
        $text = if (Test-Path $responseFile) { Get-Content -LiteralPath $responseFile -Raw } else { "" }
        $jsonBody = $null
        if (-not [string]::IsNullOrWhiteSpace($text)) {
            try {
                $jsonBody = $text | ConvertFrom-Json
            } catch {
                $jsonBody = $null
            }
        }
        if ($statusCodeText -notmatch "^\d{3}$") {
            $statusCodeText = "000"
        }
        return [ordered]@{
            exitCode = $exit
            statusCode = [int]$statusCodeText
            bodyText = $text
            json = $jsonBody
        }
    } finally {
        if ($requestFile) {
            Remove-Item -LiteralPath $requestFile -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath $responseFile -ErrorAction SilentlyContinue
    }
}

function Invoke-ConfigApi {
    param(
        [string]$Method,
        [string]$Path,
        [AllowNull()][object]$Body = $null
    )
    $result = Invoke-HttpJson -Method $Method -Url "$ConfigBaseUrl$Path" -Body $Body
    if ($result.exitCode -ne 0) {
        throw "config-service curl failed method=$Method path=$Path exit=$($result.exitCode) output=$($result.bodyText)"
    }
    return $result
}

function Get-ConfigApiRecord {
    $encodedKey = [uri]::EscapeDataString($configKey)
    $result = Invoke-ConfigApi -Method "GET" -Path "/api/v1/configs/$encodedKey"
    if ($result.statusCode -eq 404) {
        return $null
    }
    if ($result.statusCode -lt 200 -or $result.statusCode -ge 300) {
        throw "config-service GET returned HTTP $($result.statusCode) body=$($result.bodyText)"
    }
    return $result.json
}

function Write-ConfigApiRecord {
    param(
        [bool]$Exists,
        [string]$Value,
        [AllowNull()][string]$Description
    )
    $body = [ordered]@{
        key = $configKey
        value = $Value
        description = if ($null -eq $Description) { $temporaryDescription } else { $Description }
    }
    $encodedKey = [uri]::EscapeDataString($configKey)
    if ($Exists) {
        $result = Invoke-ConfigApi -Method "PUT" -Path "/api/v1/configs/$encodedKey" -Body $body
    } else {
        $result = Invoke-ConfigApi -Method "POST" -Path "/api/v1/configs" -Body $body
    }
    if ($result.statusCode -lt 200 -or $result.statusCode -ge 300) {
        throw "config-service write returned HTTP $($result.statusCode) body=$($result.bodyText)"
    }
    return $result.json
}

function Remove-ConfigApiRecord {
    $encodedKey = [uri]::EscapeDataString($configKey)
    $result = Invoke-ConfigApi -Method "DELETE" -Path "/api/v1/configs/$encodedKey"
    if ($result.statusCode -lt 200 -or $result.statusCode -ge 300) {
        throw "config-service delete returned HTTP $($result.statusCode) body=$($result.bodyText)"
    }
    return $result.statusCode
}

function Get-RabbitQueueDetail {
    $rabbitPassword = Get-RabbitPassword
    $encodedQueue = [uri]::EscapeDataString($queueName)
    $result = Invoke-HttpJson `
        -Method "GET" `
        -Url "$RabbitBaseUrl/api/queues/%2F/$encodedQueue" `
        -BasicAuth "admin:$rabbitPassword"
    if ($result.exitCode -ne 0) {
        throw "RabbitMQ queue detail curl failed exit=$($result.exitCode) body=$($result.bodyText)"
    }
    if ($result.statusCode -eq 404) {
        Abort-Smoke "SKIPPED" "CONTENT_CONFIG_QUEUE_MISSING" @{
            queue = $queueName
            httpStatus = $result.statusCode
            body = $result.bodyText
            note = "Rabbit broker does not currently expose q.content.config_changed, so live write is not attempted."
        }
    }
    if ($result.statusCode -lt 200 -or $result.statusCode -ge 300) {
        throw "RabbitMQ queue detail HTTP $($result.statusCode) body=$($result.bodyText)"
    }
    $queue = $result.json
    $stats = $queue.message_stats
    [ordered]@{
        name = $queue.name
        consumers = [int]$queue.consumers
        messages = [int]$queue.messages
        messagesReady = [int]$queue.messages_ready
        messagesUnacknowledged = [int]$queue.messages_unacknowledged
        publish = Get-RabbitStat $stats "publish"
        deliver = Get-RabbitStat $stats "deliver"
        deliverGet = Get-RabbitStat $stats "deliver_get"
        ack = Get-RabbitStat $stats "ack"
    }
}

function Get-RabbitStat {
    param(
        [AllowNull()][object]$Stats,
        [string]$Name
    )
    if ($null -eq $Stats) {
        return 0
    }
    $property = $Stats.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) {
        return 0
    }
    return [int64]$property.Value
}

function Wait-RabbitQueueEvent {
    param(
        [object]$Before,
        [string]$Label
    )
    $last = $null
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        $current = Get-RabbitQueueDetail
        $last = $current
        $publishDelta = [int64]$current.publish - [int64]$Before.publish
        $deliverDelta = ([int64]$current.deliver + [int64]$current.deliverGet) - ([int64]$Before.deliver + [int64]$Before.deliverGet)
        $ackDelta = [int64]$current.ack - [int64]$Before.ack
        if ($publishDelta -ge 1 -and ($deliverDelta -ge 1 -or $ackDelta -ge 1) -and $current.messages -eq 0) {
            return [ordered]@{
                label = $Label
                before = $Before
                after = $current
                publishDelta = $publishDelta
                deliverDelta = $deliverDelta
                ackDelta = $ackDelta
            }
        }
        Start-Sleep -Seconds $WaitSeconds
    }
    throw "RabbitMQ queue event not observed for $Label; last=$($last | ConvertTo-Json -Compress)"
}

function Get-ContentRuntimeOverride {
    $result = Invoke-KubectlRetry @(
        "-n", $Namespace,
        "get", "deployment", "ircs-content-service",
        "-o", "json"
    )
    if ($result.exitCode -ne 0) {
        throw "failed to inspect content-service env: $($result.output)"
    }
    $deployment = $result.output | ConvertFrom-Json
    $containers = @($deployment.spec.template.spec.containers)
    $container = $containers | Where-Object { $_.name -eq "app" } | Select-Object -First 1
    if ($null -eq $container) {
        $container = $containers | Select-Object -First 1
    }
    $envEntries = @($container.env)
    $matches = @()
    foreach ($targetName in $contentRuntimeOverrideEnvNames) {
        $entry = $envEntries | Where-Object { $_.name -eq $targetName } | Select-Object -First 1
        if ($null -ne $entry) {
            $hasDirectValue = $null -ne $entry.PSObject.Properties["value"]
            $hasValueFrom = $null -ne $entry.PSObject.Properties["valueFrom"]
            $value = if ($hasDirectValue) { [string]$entry.value } else { $null }
            $matches += [ordered]@{
                name = $entry.name
                source = if ($hasValueFrom) { "valueFrom" } elseif ($hasDirectValue) { "value" } else { "unknown" }
                value = $value
                valueEvidence = Get-ValueEvidence $value
                valueFrom = if ($hasValueFrom) { $entry.valueFrom | ConvertTo-Json -Depth 8 -Compress } else { $null }
            }
        }
    }
    [ordered]@{
        present = ($matches.Count -gt 0)
        env = $matches
    }
}

function Remove-ContentRuntimeOverrideForProbe {
    param([object]$RuntimeOverride)
    if (-not $RuntimeOverride.present) {
        return [ordered]@{
            status = "NOT_NEEDED"
            reason = "CONTENT_RUNTIME_OVERRIDE_NOT_PRESENT"
            before = $RuntimeOverride
        }
    }
    if (-not $TemporarilyRemoveContentRuntimeOverride) {
        return [ordered]@{
            status = "NOT_REQUESTED"
            reason = "TEMPORARY_CONTENT_RUNTIME_OVERRIDE_REMOVAL_NOT_REQUESTED"
            before = $RuntimeOverride
        }
    }
    foreach ($envEntry in @($RuntimeOverride.env)) {
        if ($envEntry.source -ne "value") {
            Abort-Smoke "SKIPPED" "CONTENT_RUNTIME_OVERRIDE_NOT_PATCHABLE" @{
                env = $RuntimeOverride.env
                note = "Only direct value env overrides are patched automatically; valueFrom must be handled manually to avoid an unsafe restore."
            }
        }
    }
    $script:contentRuntimeOverrideOriginal = $RuntimeOverride
    $patches = @()
    foreach ($envEntry in @($RuntimeOverride.env)) {
        $result = Invoke-KubectlRetry @(
            "-n", $Namespace,
            "set", "env", "deployment/ircs-content-service",
            "$($envEntry.name)-"
        )
        $patches += [ordered]@{
            name = $envEntry.name
            exitCode = $result.exitCode
            output = $result.output
        }
        if ($result.exitCode -ne 0) {
            throw "failed to remove content runtime override $($envEntry.name): $($result.output)"
        }
    }
    $script:contentRuntimeOverridePatchApplied = $true
    $rollout = Assert-RolloutReady "ircs-content-service"
    $after = Get-ContentRuntimeOverride
    if ($after.present) {
        throw "content runtime override is still present after temporary removal: $($after | ConvertTo-Json -Depth 8 -Compress)"
    }
    [ordered]@{
        status = "TEMPORARILY_REMOVED"
        before = $RuntimeOverride
        patches = $patches
        rollout = $rollout
        after = $after
    }
}

function Restore-ContentRuntimeOverrideForProbe {
    if (-not $script:contentRuntimeOverridePatchApplied) {
        return [ordered]@{
            status = "NOT_NEEDED"
            reason = "CONTENT_RUNTIME_OVERRIDE_PATCH_NOT_APPLIED"
        }
    }
    $patches = @()
    foreach ($envEntry in @($script:contentRuntimeOverrideOriginal.env)) {
        if ($envEntry.source -ne "value") {
            throw "cannot restore non-direct value content runtime override $($envEntry.name)"
        }
        $result = Invoke-KubectlRetry @(
            "-n", $Namespace,
            "set", "env", "deployment/ircs-content-service",
            "$($envEntry.name)=$($envEntry.value)"
        )
        $patches += [ordered]@{
            name = $envEntry.name
            exitCode = $result.exitCode
            output = $result.output
        }
        if ($result.exitCode -ne 0) {
            throw "failed to restore content runtime override $($envEntry.name): $($result.output)"
        }
    }
    $rollout = Assert-RolloutReady "ircs-content-service"
    $after = Get-ContentRuntimeOverride
    $restored = $true
    foreach ($envEntry in @($script:contentRuntimeOverrideOriginal.env)) {
        $current = @($after.env) | Where-Object { $_.name -eq $envEntry.name } | Select-Object -First 1
        if ($null -eq $current -or $current.source -ne $envEntry.source -or $current.value -ne $envEntry.value) {
            $restored = $false
        }
    }
    if (-not $restored) {
        throw "content runtime override restore verification failed: $($after | ConvertTo-Json -Depth 8 -Compress)"
    }
    [ordered]@{
        status = "RESTORED"
        patches = $patches
        rollout = $rollout
        after = $after
    }
}

function Invoke-SqlScalar {
    param([string]$Sql)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $rows = $Sql | kubectl -n $Namespace exec -i postgres-0 -- psql -v ON_ERROR_STOP=1 -U postgres -d ircs -t -A 2>&1
        $exit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($exit -ne 0) {
        throw "psql scalar failed with exit code $exit output=$(ConvertTo-SafeError $rows)"
    }
    $value = $rows | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Last 1
    if ($null -eq $value) {
        return $null
    }
    return $value.Trim()
}

function Invoke-SqlCommand {
    param([string]$Sql)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = $Sql | kubectl -n $Namespace exec -i postgres-0 -- psql -v ON_ERROR_STOP=1 -U postgres -d ircs 2>&1
        $exit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($exit -ne 0) {
        throw "psql command failed with exit code $exit output=$(ConvertTo-SafeError $output)"
    }
    return [ordered]@{
        exitCode = $exit
        output = ConvertTo-SafeError $output
    }
}

function New-TemporaryRawVideoFixture {
    $idSql = Sql-Literal $temporaryRawVideoId
    $sourceVidSql = Sql-Literal $temporaryRawVideoSourceVid
    $sourceHashSql = Sql-Literal (Get-Sha256 $temporaryRawVideoSourceVid)
    $titleSql = Sql-Literal "C2-S17 content read probe temporary fixture"
    $sql = @"
insert into raw_videos (
    id, created_at, updated_at, version, source_vid, source_hash, data_hash,
    title, cover_image_id, description, year, area, raw_language_str, remarks, score,
    published_at, douban_id, tmdb_id, imdb_id, rotten_tomatoes_id, locked_fields,
    data_source_category_id, enrichment_status, enrichment_retry_count,
    normalization_status, raw_metadata, next_normalization_retry_time,
    normalization_retry_count, data_source_id, playlist_retry_count,
    alias_title, total_episodes, duration, season, subtitle,
    aggregation_status, aggregation_status_updated_at
) values (
    $idSql::uuid, now(), now(), 0, $sourceVidSql, $sourceHashSql, null,
    $titleSql, null, null, null, null, null, 'temporary smoke fixture; delete immediately', null,
    null, null, null, null, null, '[]'::jsonb,
    null, 'PENDING', 0,
    'READY', '{}'::jsonb, null,
    0, null, 0,
    null, null, null, null, null,
    'PENDING', now()
);
"@
    $result = Invoke-SqlCommand $sql
    $script:temporaryRawVideoFixtureCreated = $true
    [ordered]@{
        status = "CREATED"
        id = $temporaryRawVideoId
        sourceVid = $temporaryRawVideoSourceVid
        sourceHash = Get-Sha256 $temporaryRawVideoSourceVid
        result = $result
    }
}

function Remove-TemporaryRawVideoFixture {
    if (-not $script:temporaryRawVideoFixtureCreated) {
        return [ordered]@{
            status = "NOT_NEEDED"
            reason = "TEMPORARY_RAW_VIDEO_FIXTURE_NOT_CREATED"
        }
    }
    $idSql = Sql-Literal $temporaryRawVideoId
    $sourceVidSql = Sql-Literal $temporaryRawVideoSourceVid
    $sql = @"
delete from raw_videos
where id = $idSql::uuid
  and source_vid = $sourceVidSql;
select count(*)::text
from raw_videos
where id = $idSql::uuid
   or source_vid = $sourceVidSql;
"@
    $result = Invoke-SqlCommand $sql
    $remaining = Invoke-SqlScalar "select count(*)::text from raw_videos where id = $idSql::uuid or source_vid = $sourceVidSql;"
    if ($remaining -ne "0") {
        throw "temporary raw video fixture cleanup failed; remaining=$remaining"
    }
    [ordered]@{
        status = "REMOVED"
        id = $temporaryRawVideoId
        sourceVid = $temporaryRawVideoSourceVid
        remaining = [int]$remaining
        result = $result
    }
}

function Invoke-ContentReadProbe {
    param([bool]$RuntimeOverridePresent)
    if ($RuntimeOverridePresent) {
        return [ordered]@{
            status = "BLOCKED"
            reason = "RUNTIME_OVERRIDE_PRESENT"
            detail = "content-service has APP_CONTENT_* scraper base URL override, so DB fallback read cannot be observed without a temporary deployment change"
        }
    }
    if (-not ($ProbeContentRead -or $TemporarilyRemoveContentRuntimeOverride)) {
        return [ordered]@{
            status = "SKIPPED"
            reason = "PROBE_CONTENT_READ_NOT_REQUESTED"
        }
    }
    $fixtureEvidence = $null
    $rawVideoId = Invoke-SqlScalar "select id::text from raw_videos order by created_at desc limit 1;"
    if ([string]::IsNullOrWhiteSpace($rawVideoId)) {
        if ($CreateTemporaryRawVideoFixture) {
            $fixtureEvidence = New-TemporaryRawVideoFixture
            $rawVideoId = $temporaryRawVideoId
        } else {
            return [ordered]@{
                status = "SKIPPED"
                reason = "NO_RAW_VIDEO_FIXTURE_FOR_REFETCH_PROBE"
            }
        }
    }
    $script:contentPf = Start-PortForward "svc/ircs-content-service" $ContentPort 8080 $contentPfOut $contentPfErr
    $response = Invoke-HttpJson -Method "POST" -Url "$ContentBaseUrl/api/v1/raw-videos/$rawVideoId/refetch"
    $containsTemp = $response.bodyText -like "*$temporaryValue*"
    [ordered]@{
        status = if ($containsTemp) { "OBSERVED" } else { "NOT_OBSERVED" }
        reason = if ($containsTemp) { "REFETCH_ERROR_CONTAINED_TEMPORARY_DB_BASE_URL" } else { "REFETCH_RESPONSE_DID_NOT_CONTAIN_TEMPORARY_DB_BASE_URL" }
        rawVideoId = $rawVideoId
        temporaryFixture = $fixtureEvidence
        httpStatus = $response.statusCode
        temporaryValueObserved = $containsTemp
        bodyPreview = if ([string]::IsNullOrWhiteSpace($response.bodyText)) { "" } else { $response.bodyText.Substring(0, [Math]::Min(500, $response.bodyText.Length)) }
    }
}

function Restore-OriginalConfig {
    if (-not $script:tempWriteApplied) {
        return [ordered]@{
            status = "NOT_NEEDED"
            reason = "TEMP_WRITE_NOT_APPLIED"
        }
    }
    $beforeRestoreQueue = Get-RabbitQueueDetail
    if ($script:originalExists) {
        $restored = Write-ConfigApiRecord `
            -Exists $true `
            -Value $script:originalConfig.value `
            -Description $script:originalConfig.description
        $writeMode = "PUT_ORIGINAL_VALUE"
    } else {
        $statusCode = Remove-ConfigApiRecord
        $restored = [ordered]@{
            deleteStatus = $statusCode
        }
        $writeMode = "DELETE_TEMPORARY_ROW"
    }
    $queueEvidence = Wait-RabbitQueueEvent -Before $beforeRestoreQueue -Label "restore-original"
    $after = Get-ConfigApiRecord
    $matchesOriginal = $false
    if ($script:originalExists) {
        $matchesOriginal = ($null -ne $after -and $after.value -eq $script:originalConfig.value)
    } else {
        $matchesOriginal = ($null -eq $after)
    }
    if (-not $matchesOriginal) {
        throw "restore verification failed; config-service still differs from original state"
    }
    [ordered]@{
        status = "RESTORED"
        mode = $writeMode
        response = $restored
        queueEvidence = $queueEvidence
        verifiedOriginalState = $matchesOriginal
        currentValue = if ($null -eq $after) { $null } else { Get-ValueEvidence $after.value }
    }
}

function Restore-OriginalConfigSqlFallback {
    if (-not $script:tempWriteApplied) {
        return [ordered]@{
            status = "NOT_NEEDED"
            reason = "TEMP_WRITE_NOT_APPLIED"
        }
    }
    $keySql = Sql-Literal $configKey
    if ($script:originalExists) {
        $valueSql = Sql-Literal $script:originalConfig.value
        $descriptionSql = Sql-Literal $script:originalConfig.description
        $sql = "update system_configs set config_value = $valueSql, description = $descriptionSql, updated_at = now(), version = coalesce(version, 0) + 1 where config_key = $keySql;"
    } else {
        $sql = "delete from system_configs where config_key = $keySql;"
    }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = $sql | kubectl -n $Namespace exec -i postgres-0 -- psql -v ON_ERROR_STOP=1 -U postgres -d ircs 2>&1
        $exit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    [ordered]@{
        status = if ($exit -eq 0) { "SQL_FALLBACK_RESTORED" } else { "SQL_FALLBACK_FAILED" }
        exitCode = $exit
        output = ConvertTo-SafeError $output
    }
}

if (-not $ConfirmLiveRabbit) {
    Mark "dry-run prepared; no config-service/Rabbit writes were attempted" 1 1
    Finish-Smoke "SUCCESS" "DRY_RUN_CONFIRM_LIVE_RABBIT_REQUIRED" @{
        configKey = $configKey
        queue = $queueName
        temporaryValue = $temporaryValue
        requiredSwitch = "-ConfirmLiveRabbit"
        optionalProbeSwitches = @("-TemporarilyRemoveContentRuntimeOverride", "-ProbeContentRead", "-CreateTemporaryRawVideoFixture")
    }
}

try {
    Assert-KubectlAvailable
    Mark "kubectl can access target namespace" 1 7

    $rollouts = [ordered]@{
        config = Assert-RolloutReady "ircs-config-service"
        content = Assert-RolloutReady "ircs-content-service"
    }
    Set-Detail "rollouts" $rollouts
    Mark "config-service and content-service deployments are ready" 2 7

    $script:rabbitPf = Start-PortForward "svc/rabbitmq-svc" $RabbitPort 15672 $rabbitPfOut $rabbitPfErr
    $script:configPf = Start-PortForward "svc/ircs-config-service" $ConfigPort 8080 $configPfOut $configPfErr

    $runtimeOverride = Get-ContentRuntimeOverride
    Set-Detail "contentRuntimeOverrideBeforeProbe" $runtimeOverride
    $runtimeOverridePatch = Remove-ContentRuntimeOverrideForProbe -RuntimeOverride $runtimeOverride
    Set-Detail "contentRuntimeOverridePatch" $runtimeOverridePatch
    if ($runtimeOverridePatch.status -eq "TEMPORARILY_REMOVED") {
        $runtimeOverride = $runtimeOverridePatch.after
    }
    Set-Detail "contentRuntimeOverride" $runtimeOverride

    $queueBefore = Get-RabbitQueueDetail
    if ($queueBefore.consumers -lt 1) {
        Abort-Smoke "SKIPPED" "CONTENT_CONFIG_QUEUE_HAS_NO_CONSUMER" @{
            queueBefore = $queueBefore
            runtimeOverride = $runtimeOverride
        }
    }
    Set-Detail "queueBefore" $queueBefore
    Mark "Rabbit content config queue is present with a consumer" 3 7

    $script:originalConfig = Get-ConfigApiRecord
    $script:originalExists = ($null -ne $script:originalConfig)
    $originalEvidence = if ($script:originalExists) {
        [ordered]@{
            exists = $true
            value = Get-ValueEvidence $script:originalConfig.value
            description = $script:originalConfig.description
            effectiveSource = $script:originalConfig.effectiveSource
            sensitive = $script:originalConfig.sensitive
        }
    } else {
        [ordered]@{
            exists = $false
        }
    }
    Set-Detail "originalConfig" $originalEvidence
    Mark "original config-service value captured before write" 4 7

    $temporaryResponse = Write-ConfigApiRecord `
        -Exists $script:originalExists `
        -Value $temporaryValue `
        -Description $(if ($script:originalExists) { $script:originalConfig.description } else { $temporaryDescription })
    $script:tempWriteApplied = $true
    Set-Detail "temporaryWrite" ([ordered]@{
        responseKey = $temporaryResponse.key
        value = Get-ValueEvidence $temporaryResponse.value
        effectiveSource = $temporaryResponse.effectiveSource
        sensitive = $temporaryResponse.sensitive
        updatedAt = $temporaryResponse.updatedAt
    })
    Mark "config-service wrote temporary non-sensitive value" 5 7

    $temporaryQueueEvidence = Wait-RabbitQueueEvent -Before $queueBefore -Label "temporary-update"
    Set-Detail "temporaryRabbitEvidence" $temporaryQueueEvidence
    Mark "Rabbit publish and content consumer ack observed for temporary update" 6 7

    $readEvidence = Invoke-ContentReadProbe -RuntimeOverridePresent $runtimeOverride.present
    Set-Detail "contentReadEvidence" $readEvidence
    if ($readEvidence.status -eq "OBSERVED") {
        Mark "content-service read probe observed temporary DB fallback value" 7 7
    } else {
        Mark "content-service read probe was not fully observable in current live environment" 7 7
        if ($readEvidence.status -eq "BLOCKED") {
            $script:finalStatus = "PARTIAL"
            $script:finalReason = "CONTENT_READ_BLOCKED_BY_RUNTIME_OVERRIDE"
        } elseif ($readEvidence.status -eq "NOT_OBSERVED") {
            $script:finalStatus = "PARTIAL"
            $script:finalReason = "CONTENT_READ_NOT_OBSERVED"
        } else {
            $script:finalStatus = "PARTIAL"
            $script:finalReason = $readEvidence.reason
        }
    }
} catch {
    if ($script:abortReason) {
        $script:finalStatus = $script:abortStatus
        $script:finalReason = $script:abortReason
        Set-Detail "abort" $script:abortDetails
    } else {
        $script:finalStatus = "FAILED"
        $script:finalReason = "LIVE_SMOKE_ERROR"
        Set-Detail "error" $_.Exception.Message
    }
} finally {
    try {
        if ($script:contentRuntimeOverridePatchApplied) {
            $runtimeOverrideRestoreEvidence = Restore-ContentRuntimeOverrideForProbe
            Set-Detail "contentRuntimeOverrideRestoration" $runtimeOverrideRestoreEvidence
        } else {
            Set-Detail "contentRuntimeOverrideRestoration" ([ordered]@{
                status = "NOT_NEEDED"
                reason = "CONTENT_RUNTIME_OVERRIDE_PATCH_NOT_APPLIED"
            })
        }
    } catch {
        Set-Detail "contentRuntimeOverrideRestoration" ([ordered]@{
            status = "FAILED"
            error = $_.Exception.Message
        })
        if ($script:finalStatus -eq "SUCCESS" -or $script:finalStatus -eq "PARTIAL" -or $script:finalStatus -eq "SKIPPED") {
            $script:finalStatus = "FAILED"
            $script:finalReason = "CONTENT_RUNTIME_OVERRIDE_RESTORE_FAILED"
        } else {
            $script:finalReason = "$($script:finalReason)_CONTENT_RUNTIME_OVERRIDE_RESTORE_FAILED"
        }
    }
    try {
        if ($script:tempWriteApplied) {
            $restoreEvidence = Restore-OriginalConfig
            Set-Detail "restoration" $restoreEvidence
        } else {
            Set-Detail "restoration" ([ordered]@{
                status = "NOT_NEEDED"
                reason = "TEMP_WRITE_NOT_APPLIED"
            })
        }
    } catch {
        $restoreError = $_.Exception.Message
        Set-Detail "restoration" ([ordered]@{
            status = "FAILED"
            error = $restoreError
            fallback = Restore-OriginalConfigSqlFallback
        })
        if ($script:finalStatus -eq "SUCCESS" -or $script:finalStatus -eq "PARTIAL" -or $script:finalStatus -eq "SKIPPED") {
            $script:finalStatus = "FAILED"
            $script:finalReason = "RESTORE_FAILED"
        } else {
            $script:finalReason = "$($script:finalReason)_RESTORE_FAILED"
        }
    }
    try {
        if ($script:temporaryRawVideoFixtureCreated) {
            $fixtureCleanup = Remove-TemporaryRawVideoFixture
            Set-Detail "temporaryRawVideoFixtureCleanup" $fixtureCleanup
        } else {
            Set-Detail "temporaryRawVideoFixtureCleanup" ([ordered]@{
                status = "NOT_NEEDED"
                reason = "TEMPORARY_RAW_VIDEO_FIXTURE_NOT_CREATED"
            })
        }
    } catch {
        Set-Detail "temporaryRawVideoFixtureCleanup" ([ordered]@{
            status = "FAILED"
            error = $_.Exception.Message
        })
        if ($script:finalStatus -eq "SUCCESS" -or $script:finalStatus -eq "PARTIAL" -or $script:finalStatus -eq "SKIPPED") {
            $script:finalStatus = "FAILED"
            $script:finalReason = "TEMPORARY_RAW_VIDEO_FIXTURE_CLEANUP_FAILED"
        } else {
            $script:finalReason = "$($script:finalReason)_TEMPORARY_RAW_VIDEO_FIXTURE_CLEANUP_FAILED"
        }
    }
    Stop-PortForward $script:contentPf
    Stop-PortForward $script:configPf
    Stop-PortForward $script:rabbitPf
}

Finish-Smoke $script:finalStatus $script:finalReason $script:details
