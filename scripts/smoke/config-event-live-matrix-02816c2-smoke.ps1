param(
    [string]$Namespace = "ircs-dev",
    [switch]$DryRun,
    [switch]$CheckClusterReadOnly,
    [switch]$FailOnSkip
)

$ErrorActionPreference = "Stop"

$matrix = @(
    [ordered]@{
        service = "ircs-search-service"
        task = "028-16-C2-S5"
        queue = "q.search.config_changed"
        listenerEnv = "APP_SEARCH_CONFIG_LISTENER_ENABLED"
        representativeKey = "app.storage.public-path"
        evidence = "unit listener/topology/cache tests"
        next = "live Rabbit publish/consume smoke"
    },
    [ordered]@{
        service = "ircs-interaction-service"
        task = "028-16-C2-S6"
        queue = "q.interaction.config_changed"
        listenerEnv = "APP_INTERACTION_CONFIG_LISTENER_ENABLED"
        representativeKey = "app.storage.public-path"
        evidence = "unit listener/topology/cache tests"
        next = "live Rabbit publish/consume smoke"
    },
    [ordered]@{
        service = "ircs-portal-service"
        task = "028-16-C2-S7"
        queue = "q.portal.config_changed"
        listenerEnv = "APP_PORTAL_CONFIG_LISTENER_ENABLED"
        representativeKey = "app.storage.public-path"
        evidence = "unit listener/topology/cache tests"
        next = "live Rabbit publish/consume smoke"
    },
    [ordered]@{
        service = "ircs-storage-service"
        task = "028-16-C2-S8"
        queue = "q.storage.config_changed"
        listenerEnv = "APP_STORAGE_CONFIG_LISTENER_ENABLED"
        representativeKey = "app.storage.r2.public-domain"
        evidence = "unit listener/topology/cache tests"
        next = "live Rabbit publish/consume smoke"
    },
    [ordered]@{
        service = "ircs-normalization-worker"
        task = "028-16-C2-S9"
        queue = "q.normalization.config_changed"
        listenerEnv = "APP_NORMALIZATION_CONFIG_LISTENER_ENABLED"
        representativeKey = "normalization.max-retries"
        evidence = "unit listener/topology/cache tests"
        next = "live Rabbit publish/consume smoke"
    },
    [ordered]@{
        service = "ircs-notification-worker"
        task = "028-16-C2-S11"
        queue = "q.notification.config_changed"
        listenerEnv = "APP_NOTIFICATION_CONFIG_LISTENER_ENABLED"
        representativeKey = "app.mail.enabled"
        evidence = "unit listener/topology/cache tests"
        next = "live Rabbit publish/consume smoke"
    },
    [ordered]@{
        service = "ircs-metadata-worker"
        task = "028-16-C2-S12"
        queue = "q.metadata.config_changed"
        listenerEnv = "APP_METADATA_CONFIG_LISTENER_ENABLED"
        representativeKey = "app.metadata.tmdb.enabled"
        evidence = "unit listener/topology/cache tests"
        next = "live Rabbit publish/consume smoke"
    },
    [ordered]@{
        service = "ircs-ops-service"
        task = "028-16-C2-S13"
        queue = "q.ops.config_changed"
        listenerEnv = "APP_OPS_CONFIG_LISTENER_ENABLED"
        representativeKey = "app.ops.maintenance.reindex.dev-limit"
        evidence = "unit listener/topology/cache tests"
        next = "live Rabbit publish/consume smoke"
    },
    [ordered]@{
        service = "ircs-content-service"
        task = "028-16-C2-S14/S15"
        queue = "q.content.config_changed"
        listenerEnv = "APP_CONTENT_CONFIG_LISTENER_ENABLED"
        representativeKey = "app.content.internal.scraper-base-url"
        evidence = "unit listener/topology/cache tests plus S15 representative evict/read-refresh test"
        next = "first live-safe Rabbit smoke candidate"
    }
)

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
        task = "028-16-C2-S15"
        slice = "config-event-live-matrix"
        status = $Status
        reason = $Reason
        checkedAt = (Get-Date).ToUniversalTime().ToString("o")
        namespace = $Namespace
        mode = if ($CheckClusterReadOnly -and -not $DryRun) { "read-only-cluster-check" } else { "dry-run" }
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

function Invoke-KubectlReadOnly {
    param([string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & kubectl @Arguments 2>&1
        $exit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    return @{
        exitCode = $exit
        output = (($output | Out-String).Trim())
    }
}

function Get-QueueState {
    param([string]$QueueName)
    $result = Invoke-KubectlReadOnly @("-n", $Namespace, "exec", "rabbitmq-0", "--", "rabbitmqctl", "list_queues", "name", "messages", "consumers")
    if ($result.exitCode -ne 0) {
        return [ordered]@{
            queue = $QueueName
            status = "UNKNOWN"
            reason = "RABBITMQCTL_FAILED"
            output = $result.output
        }
    }
    $line = ($result.output -split "`r?`n") | Where-Object { $_ -match "^$([regex]::Escape($QueueName))\s" } | Select-Object -First 1
    if (-not $line) {
        return [ordered]@{
            queue = $QueueName
            status = "MISSING"
            messages = $null
            consumers = $null
        }
    }
    $parts = $line -split "\s+"
    return [ordered]@{
        queue = $QueueName
        status = "PRESENT"
        messages = [int]$parts[1]
        consumers = [int]$parts[2]
    }
}

function Get-ReadOnlyClusterSnapshot {
    $access = Invoke-KubectlReadOnly @("-n", $Namespace, "get", "pod")
    if ($access.exitCode -ne 0) {
        Finish-Smoke "SKIPPED" "KUBERNETES_UNAVAILABLE" @{
            exitCode = $access.exitCode
            output = $access.output
            matrix = $matrix
        }
    }

    $queues = @()
    foreach ($item in $matrix) {
        $queues += Get-QueueState $item.queue
    }
    return @{
        podsPreview = $access.output
        queues = $queues
    }
}

Mark "config event matrix loaded without write operations" 1 4

$safePayloadShape = [ordered]@{
    exchange = "x.domain.events"
    routingKey = "config.system.changed"
    fields = @("key", "action", "effectiveSource", "sensitive", "changedAt")
    forbiddenFields = @("value", "configValue", "effectiveValue", "secret", "password", "token")
}
Mark "publisher payload shape is value-free by contract" 2 4

$suggestedCommands = @(
    ".\gradlew.bat --no-daemon :services:ircs-config-service:test :services:ircs-content-service:test --rerun-tasks",
    "kubectl -n $Namespace exec rabbitmq-0 -- rabbitmqctl list_queues name messages consumers",
    "kubectl -n $Namespace get deploy -o jsonpath='{range .items[*]}{.metadata.name}{`" `"}{range .spec.template.spec.containers[0].env[*]}{.name}{`"=`"}{.value}{`" `"}{end}{`"`n`"}{end}'"
)
Mark "no-write follow-up commands prepared" 3 4

$details = @{
    matrix = $matrix
    safePayloadShape = $safePayloadShape
    suggestedCommands = $suggestedCommands
    remaining = @(
        "publish/consume live Rabbit smoke per service",
        "TTL fallback decision for event outage or missed message",
        "explicit restart-only annotation for startup-bound configs"
    )
}

if ($CheckClusterReadOnly -and -not $DryRun) {
    $details.cluster = Get-ReadOnlyClusterSnapshot
    Mark "read-only Rabbit queue snapshot collected" 4 4
    Finish-Smoke "SUCCESS" "READ_ONLY_CLUSTER_MATRIX_COLLECTED" $details
}

Mark "dry-run completed; no DB/Rabbit/deployment writes were attempted" 4 4
Finish-Smoke "SUCCESS" "DRY_RUN_MATRIX_ONLY" $details
