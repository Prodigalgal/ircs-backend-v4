param(
    [string]$Namespace = "ircs-dev",
    [switch]$FailOnSkip
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$smokeScript = Join-Path $PSScriptRoot "notification-worker-audit-02812-smoke.ps1"
$smokeExit = 999

try {
    kubectl -n $Namespace set env deploy/ircs-notification-worker APP_NOTIFICATION_LISTENER_ENABLED=true
    if ($LASTEXITCODE -ne 0) {
        throw "failed to enable APP_NOTIFICATION_LISTENER_ENABLED"
    }

    kubectl -n $Namespace rollout status deploy/ircs-notification-worker --timeout=180s
    if ($LASTEXITCODE -ne 0) {
        throw "notification-worker rollout failed after enabling listener"
    }

    $args = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $smokeScript, "-Namespace", $Namespace)
    if ($FailOnSkip) {
        $args += "-FailOnSkip"
    }
    & powershell @args
    $smokeExit = $LASTEXITCODE
} finally {
    kubectl -n $Namespace set env deploy/ircs-notification-worker APP_NOTIFICATION_LISTENER_ENABLED- | Out-Host
    $restoreSetExit = $LASTEXITCODE
    kubectl -n $Namespace rollout status deploy/ircs-notification-worker --timeout=180s | Out-Host
    $restoreRolloutExit = $LASTEXITCODE

    Write-Output "RESTORE_SET_EXIT=$restoreSetExit"
    Write-Output "RESTORE_ROLLOUT_EXIT=$restoreRolloutExit"
    kubectl -n $Namespace exec rabbitmq-0 -- rabbitmqctl list_queues name messages_ready messages_unacknowledged messages consumers `
        | Select-String -Pattern 'q.notification.mail|q.notification.mail.dlq' `
        | Out-Host

    if ($restoreSetExit -ne 0 -or $restoreRolloutExit -ne 0) {
        if ($smokeExit -eq 0) {
            $smokeExit = 1
        }
    }
}

exit $smokeExit
