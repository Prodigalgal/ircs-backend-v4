param(
    [switch]$UseCluster
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

$checks = @(
    @{
        name = "normalization-watchdog"
        appPath = "services/ircs-normalization-worker/src/main/resources/application.yaml"
        manifestPath = "deploy/k8s/dev/normalization-worker-dev.yaml"
        envName = "APP_NORMALIZATION_WATCHDOG_ENABLED"
        deployment = "ircs-normalization-worker"
    },
    @{
        name = "storage-r2-work-queue-worker"
        appPath = "services/ircs-storage-service/src/main/resources/application.yaml"
        manifestPath = "deploy/k8s/dev/storage-service-dev.yaml"
        envName = "APP_STORAGE_R2_WORK_QUEUE_WORKER_ENABLED"
        deployment = "ircs-storage-service"
    }
)

function Read-Text([string]$path) {
    return Get-Content -Raw -Path (Join-Path $root $path)
}

function Assert-Contains([string]$text, [string]$needle, [string]$message) {
    if (-not $text.Contains($needle)) {
        throw $message
    }
}

function Get-ManifestEnvValue([string]$text, [string]$envName) {
    $pattern = "(?s)- name:\s*$([regex]::Escape($envName))\s+value:\s*`"([^`"]+)`""
    $match = [regex]::Match($text, $pattern)
    if (-not $match.Success) {
        return $null
    }
    return $match.Groups[1].Value
}

$mark = 0
$details = [ordered]@{}

foreach ($check in $checks) {
    $appText = Read-Text $check.appPath
    $manifestText = Read-Text $check.manifestPath
    Assert-Contains $appText "$($check.envName):false" "$($check.name) application.yaml default is not false"
    $manifestValue = Get-ManifestEnvValue $manifestText $check.envName
    if ($manifestValue -ne "false") {
        throw "$($check.name) dev manifest gate is not false"
    }
    $details[$check.name] = @{
        envName = $check.envName
        source = "manifest"
        value = $manifestValue
        blocked = $true
    }
}
$mark++
Write-Host "MARK $mark/3 local application defaults keep dangerous gates false"

$mark++
Write-Host "MARK $mark/3 dev manifests explicitly keep dangerous gates false"

if ($UseCluster) {
    $cluster = [ordered]@{}
    foreach ($check in $checks) {
        $json = kubectl -n ircs-dev get deploy $check.deployment -o json | ConvertFrom-Json
        $env = @{}
        foreach ($entry in $json.spec.template.spec.containers[0].env) {
            if ($entry.value) {
                $env[$entry.name] = $entry.value
            }
        }
        $clusterValue = if ($env.ContainsKey($check.envName)) { $env[$check.envName] } else { "<missing>" }
        if ($clusterValue -ne "false") {
            throw "$($check.name) live deployment gate is $clusterValue"
        }
        $cluster[$check.name] = @{
            deployment = $check.deployment
            envName = $check.envName
            value = $clusterValue
            readyReplicas = $json.status.readyReplicas
            replicas = $json.status.replicas
        }
    }
    $details["cluster"] = $cluster
    $mark++
    Write-Host "MARK $mark/3 live deployments keep dangerous gates false"
} else {
    $mark++
    Write-Host "MARK $mark/3 live cluster check skipped by default no-write mode"
}

$details | ConvertTo-Json -Depth 8
