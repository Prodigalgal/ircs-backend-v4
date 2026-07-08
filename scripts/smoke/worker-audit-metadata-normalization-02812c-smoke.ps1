param(
    [string]$Namespace = "ircs-dev",
    [string]$RabbitBaseUrl = "http://127.0.0.1:19095",
    [int]$RabbitPort = 19095,
    [string]$OpsBaseUrl = "http://127.0.0.1:18131",
    [int]$OpsPort = 18131,
    [int]$WaitAttempts = 45,
    [int]$WaitSeconds = 2,
    [switch]$AllowProviderEnabled,
    [switch]$PurgeSmokeQueuesOnCleanup,
    [switch]$FailOnSkip
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$stamp = Get-Date -Format "yyyyMMddHHmmss"
$trace = "02812c-worker-audit-$stamp"
$metadataVideoId = [guid]::NewGuid().ToString()
$normalizationVideoId = [guid]::NewGuid().ToString()

$rabbitPfOut = Join-Path $env:TEMP "ircs-02812c-rabbit-pf.out.log"
$rabbitPfErr = Join-Path $env:TEMP "ircs-02812c-rabbit-pf.err.log"
$opsPfOut = Join-Path $env:TEMP "ircs-02812c-ops-pf.out.log"
$opsPfErr = Join-Path $env:TEMP "ircs-02812c-ops-pf.err.log"
$rabbitPf = $null
$opsPf = $null
$script:rabbitPassword = $null
$script:baselineQueues = @{}

$targetQueues = @(
    "q.fetch.metadata.tmdb",
    "q.fetch.metadata.tmdb.dlq",
    "q.enrich.metadata.result",
    "q.enrich.metadata.result.dlq",
    "q.normalize.video",
    "q.normalize.video.dlq"
)

$requiredConsumers = @(
    "q.fetch.metadata.tmdb",
    "q.enrich.metadata.result",
    "q.normalize.video"
)

function New-SmokeResult {
    param(
        [string]$Status,
        [string]$Reason,
        [hashtable]$Details = @{}
    )
    [ordered]@{
        task = "028-12"
        slice = "metadata-normalization-worker-audit-live-smoke"
        status = $Status
        reason = $Reason
        checkedAt = (Get-Date).ToUniversalTime().ToString("o")
        namespace = $Namespace
        trace = $trace
        correlationIds = [ordered]@{
            metadata = $metadataVideoId
            normalization = $normalizationVideoId
        }
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

function Sql-InList {
    param([string[]]$Values)
    return ($Values | ForEach-Object { Sql-Literal $_ }) -join ", "
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
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = $Sql | kubectl -n $Namespace exec -i postgres-0 -- psql -v ON_ERROR_STOP=1 -U postgres -d ircs 2>&1
        $exit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($exit -ne 0) {
        throw "psql failed with exit code $exit`: $(($output | Out-String).Trim())"
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
        throw "psql scalar failed with exit code $exit`: $(($rows | Out-String).Trim())"
    }
    $clean = Remove-KubectlDiagnosticLines @($rows)
    $value = $clean | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Last 1
    if ($null -eq $value) {
        return $null
    }
    return $value.Trim()
}

function Test-KubectlAccess {
    $result = Invoke-NativeCommand @("kubectl", "-n", $Namespace, "get", "pod")
    if ($result.exitCode -ne 0) {
        Finish-Smoke "SKIPPED" "KUBERNETES_UNAVAILABLE" @{
            exitCode = $result.exitCode
            output = (($result.output | Out-String).Trim())
        }
    }
}

function Test-PostgresAccess {
    $value = Invoke-SqlScalar "select 1;"
    if ($value -ne "1") {
        Finish-Smoke "SKIPPED" "POSTGRES_UNAVAILABLE" @{ value = $value }
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
    param([int]$Port)
    Get-CimInstance Win32_Process -Filter "name = 'kubectl.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match "port-forward" -and $_.CommandLine -match ([regex]::Escape("$($Port):")) } |
        ForEach-Object {
            try { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue } catch { }
        }
}

function Get-SecretValue {
    param([string]$Key)
    $result = Invoke-NativeCommand @("kubectl", "-n", $Namespace, "get", "secret", "ircs-dev-secrets", "-o", "jsonpath={.data.$Key}")
    $value64 = (($result.output | Out-String).Trim())
    if ($result.exitCode -ne 0 -or [string]::IsNullOrWhiteSpace($value64)) {
        Finish-Smoke "SKIPPED" "KUBERNETES_SECRET_MISSING" @{
            key = $Key
            exitCode = $result.exitCode
            output = $value64
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

function New-RabbitHeaders {
    $rabbitPassword = Get-RabbitPassword
    $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("admin:$rabbitPassword"))
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

function Clear-RabbitQueue {
    param([string]$QueueName)
    Invoke-RestMethod `
        -Method Delete `
        -Headers (New-RabbitHeaders) `
        -Uri "$RabbitBaseUrl/api/queues/%2F/$QueueName/contents" `
        -TimeoutSec 10 | Out-Null
}

function Invoke-RabbitPublish {
    param(
        [string]$Exchange,
        [string]$RoutingKey,
        [string]$Payload,
        [string]$ContentType,
        [string]$MessageId
    )
    $body = @{
        properties = @{
            content_type = $ContentType
            message_id = $MessageId
            correlation_id = $trace
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

function Get-DeploymentEnvMap {
    param([string]$Deployment)
    $result = Invoke-NativeCommand @("kubectl", "-n", $Namespace, "get", "deploy", $Deployment, "-o", "json")
    if ($result.exitCode -ne 0) {
        Finish-Smoke "SKIPPED" "DEPLOYMENT_UNAVAILABLE" @{
            deployment = $Deployment
            output = (($result.output | Out-String).Trim())
        }
    }
    $json = ($result.output | Out-String)
    $deploymentJson = $json | ConvertFrom-Json
    $map = @{}
    foreach ($envItem in $deploymentJson.spec.template.spec.containers[0].env) {
        if ($null -ne $envItem.value) {
            $map[$envItem.name] = [string]$envItem.value
        } else {
            $map[$envItem.name] = "<valueFrom>"
        }
    }
    return $map
}

function Assert-MetadataProviderSafe {
    $envMap = Get-DeploymentEnvMap "ircs-metadata-worker"
    $listenerEnabled = $envMap["APP_METADATA_LISTENER_ENABLED"]
    $workerEnabled = $envMap["APP_METADATA_TMDB_WORKER_ENABLED"]
    $tmdbEnabled = $envMap["APP_METADATA_TMDB_ENABLED"]
    $dbTmdbValue = Invoke-SqlScalar "select config_value from system_configs where config_key = 'app.metadata.tmdb.enabled';"

    if ($listenerEnabled -ne "true" -or $workerEnabled -ne "true") {
        Finish-Smoke "SKIPPED" "METADATA_WORKER_LISTENER_DISABLED" @{
            appMetadataListenerEnabled = $listenerEnabled
            appMetadataTmdbWorkerEnabled = $workerEnabled
        }
    }
    if ($tmdbEnabled -ne "false" -and -not $AllowProviderEnabled) {
        Finish-Smoke "SKIPPED" "TMDB_PROVIDER_ENABLED_FOR_EXTERNAL_CALLS" @{
            appMetadataTmdbEnabled = $tmdbEnabled
            systemConfigTmdbEnabled = $dbTmdbValue
            hint = "rerun with -AllowProviderEnabled only for the external provider smoke batch"
        }
    }
    return @{
        appMetadataListenerEnabled = $listenerEnabled
        appMetadataTmdbWorkerEnabled = $workerEnabled
        appMetadataTmdbEnabled = $tmdbEnabled
        systemConfigTmdbEnabled = $dbTmdbValue
    }
}

function Capture-QueueBaseline {
    $details = @{}
    foreach ($queueName in $targetQueues) {
        $queue = Get-RabbitQueue $queueName
        $details[$queueName] = @{
            messages = [int]$queue.messages
            ready = [int]$queue.messages_ready
            unacked = [int]$queue.messages_unacknowledged
            consumers = [int]$queue.consumers
        }
    }
    $script:baselineQueues = $details
    return $details
}

function Assert-QueuesReadyAndClean {
    $details = Capture-QueueBaseline
    foreach ($queueName in $requiredConsumers) {
        if ($details[$queueName].consumers -lt 1) {
            Finish-Smoke "FAILED" "REQUIRED_QUEUE_CONSUMER_MISSING" @{
                queue = $queueName
                queues = $details
            }
        }
    }
    foreach ($queueName in $details.Keys) {
        if ($details[$queueName].messages -ne 0) {
            Finish-Smoke "SKIPPED" "QUEUE_BASELINE_NOT_EMPTY" @{
                queue = $queueName
                queues = $details
            }
        }
    }
    return $details
}

function Wait-QueuesEmpty {
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        $details = @{}
        $allEmpty = $true
        foreach ($queueName in $targetQueues) {
            $queue = Get-RabbitQueue $queueName
            $details[$queueName] = @{
                messages = [int]$queue.messages
                ready = [int]$queue.messages_ready
                unacked = [int]$queue.messages_unacknowledged
                consumers = [int]$queue.consumers
            }
            if ([int]$queue.messages -ne 0) {
                $allEmpty = $false
            }
        }
        if ($allEmpty) {
            return $details
        }
        Start-Sleep -Seconds $WaitSeconds
    }
    $last = Capture-QueueBaseline
    throw "target queues did not drain: $($last | ConvertTo-Json -Depth 8 -Compress)"
}

function Wait-ForAuditEvent {
    param(
        [string]$JobSourcePrefix,
        [string]$JobName,
        [string]$CorrelationId,
        [string]$Status
    )
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        $count = Invoke-SqlScalar @"
select count(*)
  from worker_job_audit_events
 where lower(job_source) like lower($(Sql-Literal "$JobSourcePrefix%"))
   and job_type = 'queue-consumer'
   and job_name = $(Sql-Literal $JobName)
   and correlation_id = $(Sql-Literal $CorrelationId)
   and status = $(Sql-Literal $Status);
"@
        if ($count -ge 1) {
            return $true
        }
        Start-Sleep -Seconds $WaitSeconds
    }
    return $false
}

function Test-OpsQuery {
    param(
        [string]$JobSource,
        [string]$JobName,
        [string]$CorrelationId,
        [string]$Status
    )
    $encodedSource = [uri]::EscapeDataString($JobSource)
    $encodedName = [uri]::EscapeDataString($JobName)
    $encodedCorrelation = [uri]::EscapeDataString($CorrelationId)
    $encodedStatus = [uri]::EscapeDataString($Status)
    $page = Invoke-RestMethod `
        -Method Get `
        -Uri "$OpsBaseUrl/api/v1/ops/worker-job-audit?size=10&jobSource=$encodedSource&jobName=$encodedName&status=$encodedStatus&correlationId=$encodedCorrelation" `
        -TimeoutSec 10
    if ([int]$page.totalElements -lt 1) {
        return $false
    }
    foreach ($row in @($page.content)) {
        if ($row.correlationId -eq $CorrelationId -and $row.jobName -eq $JobName -and $row.status -eq $Status) {
            return $true
        }
    }
    return $false
}

function Cleanup-AuditRows {
    $ids = @($metadataVideoId, $normalizationVideoId)
    Exec-Sql "delete from worker_job_audit_events where correlation_id in ($(Sql-InList $ids));"
}

function Assert-NoAuditResidue {
    $ids = @($metadataVideoId, $normalizationVideoId)
    $count = Invoke-SqlScalar "select count(*) from worker_job_audit_events where correlation_id in ($(Sql-InList $ids));"
    if ($count -ne "0") {
        throw "audit residue remained: $count"
    }
}

try {
    Test-KubectlAccess
    Test-PostgresAccess
    $auditTable = Invoke-SqlScalar "select to_regclass('public.worker_job_audit_events');"
    if ($auditTable -ne "worker_job_audit_events") {
        Finish-Smoke "SKIPPED" "WORKER_JOB_AUDIT_TABLE_MISSING" @{ table = $auditTable }
    }

    $rabbitPf = Start-PortForward "svc/rabbitmq-svc" $RabbitPort 15672 $rabbitPfOut $rabbitPfErr
    $opsPf = Start-PortForward "svc/ircs-ops-service" $OpsPort 8080 $opsPfOut $opsPfErr

    $providerSafety = Assert-MetadataProviderSafe
    $queueBaseline = Assert-QueuesReadyAndClean
    Cleanup-AuditRows
    Mark "metadata provider worker is no-cost safe and target queues are clean" 1 5

    $metadataPayload = [ordered]@{
        videoId = $metadataVideoId
        title = "Codex worker audit smoke $stamp"
        year = "2026"
        categorySlug = "movie"
    } | ConvertTo-Json -Compress
    $metadataPublish = Invoke-RabbitPublish "x.process" "metadata.fetch.tmdb" $metadataPayload "application/json" "$trace-metadata"
    $normalizationPublish = Invoke-RabbitPublish "x.process" "video.normalize" $normalizationVideoId "text/plain" "$trace-normalization"
    if (-not $metadataPublish.routed -or -not $normalizationPublish.routed) {
        Finish-Smoke "FAILED" "WORKER_AUDIT_FIXTURE_NOT_ROUTED" @{
            metadata = $metadataPublish
            normalization = $normalizationPublish
        }
    }
    Mark "metadata/normalization queue fixtures are routed" 2 5

    $queueState = Wait-QueuesEmpty
    if (-not (Wait-ForAuditEvent "ircs-metadata-worker" "metadata.provider.tmdb" $metadataVideoId "skipped")) {
        Finish-Smoke "FAILED" "METADATA_PROVIDER_AUDIT_MISSING" @{ correlationId = $metadataVideoId }
    }
    Mark "metadata provider worker writes skipped audit without external provider call" 3 5

    if (-not (Wait-ForAuditEvent "ircs-normalization-worker" "normalization.raw-video" $normalizationVideoId "succeeded")) {
        Finish-Smoke "FAILED" "NORMALIZATION_WORKER_AUDIT_MISSING" @{ correlationId = $normalizationVideoId }
    }
    Mark "normalization worker writes succeeded audit for queue consumer" 4 5

    $opsMetadata = Test-OpsQuery "ircs-metadata-worker" "metadata.provider.tmdb" $metadataVideoId "skipped"
    $opsNormalization = Test-OpsQuery "ircs-normalization-worker" "normalization.raw-video" $normalizationVideoId "succeeded"
    if (-not ($opsMetadata -and $opsNormalization)) {
        Finish-Smoke "FAILED" "OPS_WORKER_JOB_AUDIT_QUERY_MISSING_EVENT" @{
            metadata = $opsMetadata
            normalization = $opsNormalization
        }
    }

    Cleanup-AuditRows
    Assert-NoAuditResidue
    $finalQueues = Wait-QueuesEmpty
    Mark "ops query exposes all worker audit events and cleanup leaves no residue" 5 5

    Finish-Smoke "PASSED" "WORKER_AUDIT_METADATA_NORMALIZATION_SMOKE_PASSED" @{
        providerSafety = $providerSafety
        queueBaseline = $queueBaseline
        queueState = $queueState
        finalQueues = $finalQueues
        ops = @{
            metadata = $opsMetadata
            normalization = $opsNormalization
        }
    }
} catch {
    Finish-Smoke "FAILED" "UNEXPECTED_ERROR" @{
        message = $_.Exception.Message
        stack = $_.ScriptStackTrace
    }
} finally {
    try { Cleanup-AuditRows } catch { }
    if ($PurgeSmokeQueuesOnCleanup -and $script:baselineQueues.Count -gt 0) {
        foreach ($queueName in $targetQueues) {
            try {
                if ($script:baselineQueues[$queueName].messages -eq 0) {
                    $queue = if ($rabbitPf -and -not $rabbitPf.HasExited) { Get-RabbitQueue $queueName } else { $null }
                    if ($queue -and [int]$queue.messages -gt 0) {
                        Clear-RabbitQueue $queueName
                    }
                }
            } catch {
            }
        }
    }
    if ($rabbitPf -and -not $rabbitPf.HasExited) {
        Stop-Process -Id $rabbitPf.Id -Force -ErrorAction SilentlyContinue
    }
    if ($opsPf -and -not $opsPf.HasExited) {
        Stop-Process -Id $opsPf.Id -Force -ErrorAction SilentlyContinue
    }
    Stop-PortForwardByPort $RabbitPort
    Stop-PortForwardByPort $OpsPort
}
