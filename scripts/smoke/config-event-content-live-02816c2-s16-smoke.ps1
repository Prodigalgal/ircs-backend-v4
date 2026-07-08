param(
    [string]$Namespace = "ircs-dev",
    [string]$RabbitBaseUrl = "http://127.0.0.1:19086",
    [int]$RabbitPort = 19086,
    [int]$WaitAttempts = 20,
    [int]$WaitSeconds = 2,
    [switch]$ConfirmLiveRabbit,
    [switch]$FailOnSkip
)

$ErrorActionPreference = "Stop"

$taskId = "028-16-C2-S16"
$configKey = "app.content.internal.scraper-base-url"
$queueName = "q.content.config_changed"
$exchange = "x.domain.events"
$routingKey = "config.system.changed"
$stamp = Get-Date -Format "yyyyMMddHHmmss"
$temporaryValue = "http://codex-c2-s16-scraper-$stamp.example.invalid:8080"

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
        slice = "content-config-event-live-safe-smoke"
        status = $Status
        reason = $Reason
        checkedAt = (Get-Date).ToUniversalTime().ToString("o")
        namespace = $Namespace
        mode = if ($ConfirmLiveRabbit) { "live-confirmed" } else { "dry-run" }
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

function Sql-Literal {
    param([AllowNull()][string]$Value)
    if ($null -eq $Value) {
        return "null"
    }
    return "'" + $Value.Replace("'", "''") + "'"
}

function Get-EventPayloadPreview {
    [ordered]@{
        key = $configKey
        action = "UPDATED"
        effectiveSource = "DB"
        sensitive = $false
        changedAt = "<runtime UTC timestamp>"
    }
}

$restorePlan = @(
    "capture existing system_configs row for key before any write",
    "restore previous config_value/description/version if row existed",
    "delete temporary row if key did not exist before smoke",
    "publish restore event only after DB restore succeeds",
    "abort without publish if DB write or restore precondition fails"
)

$livePlan = [ordered]@{
    candidateKey = $configKey
    queue = $queueName
    exchange = $exchange
    routingKey = $routingKey
    temporaryDbValue = $temporaryValue
    eventPayloadShape = Get-EventPayloadPreview
    forbiddenPayloadFields = @("value", "configValue", "effectiveValue", "secret", "password", "token")
    writeSteps = @(
        "preflight kubectl namespace, postgres-0, rabbitmq-0, q.content.config_changed",
        "abort if APP_CONTENT_INTERNAL_SCRAPER_BASE_URL or APP_CONTENT_SCRAPER_BASE_URL runtime override is present",
        "read and cache current DB value for app.content.internal.scraper-base-url",
        "write temporary non-sensitive DB value",
        "publish value-free SystemConfigChangedEvent to x.domain.events/config.system.changed",
        "verify content-service observable path if a dedicated smoke endpoint exists",
        "restore original DB row and publish restore event",
        "verify q.content.config_changed has no leftover smoke messages"
    )
    cleanup = $restorePlan
    unresolvedRisks = @(
        "Rabbit outage or missed-message TTL fallback is still undecided",
        "without an enabled content smoke observer, live script cannot prove cache evict automatically",
        "runtime env override can mask DB fallback and must abort real live mode"
    )
}

Mark "live-safe content config event plan prepared without secrets" 1 4
Mark "candidate key is non-sensitive app.content.internal.scraper-base-url" 2 4
Mark "cleanup and restore plan recorded before any live branch" 3 4

if (-not $ConfirmLiveRabbit) {
    Mark "dry-run completed; no DB/Rabbit/deployment writes were attempted" 4 4
    Finish-Smoke "SUCCESS" "DRY_RUN_ONLY_CONFIRM_LIVE_RABBIT_REQUIRED" @{
        rabbitBaseUrl = $RabbitBaseUrl
        rabbitPort = $RabbitPort
        waitAttempts = $WaitAttempts
        waitSeconds = $WaitSeconds
        livePlan = $livePlan
    }
}

Mark "live mode was explicitly requested but implementation is intentionally guarded" 4 4
Finish-Smoke "SKIPPED" "LIVE_MODE_DESIGNED_NOT_IMPLEMENTED_IN_THIS_SLICE" @{
    reason = "User requested no real live writes for this slice; keep live design but do not execute."
    livePlan = $livePlan
}
