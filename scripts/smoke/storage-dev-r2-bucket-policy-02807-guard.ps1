param(
    [string]$ConfigMapManifest = "deploy/k8s/dev/notification-worker-dev.yaml",
    [string]$StorageManifest = "deploy/k8s/dev/storage-service-dev.yaml",
    [string]$ExpectedDevBucket = "ircs-dev",
    [string]$V1ProductionBucket = "ircs",
    [switch]$FailOnSkip
)

$ErrorActionPreference = "Stop"

function New-GuardResult {
    param(
        [string]$Status,
        [string]$Reason,
        [hashtable]$Details = @{}
    )
    [ordered]@{
        task = "028-07-E"
        slice = "dev-r2-bucket-policy-guard"
        status = $Status
        reason = $Reason
        checkedAt = (Get-Date).ToUniversalTime().ToString("o")
        details = $Details
    } | ConvertTo-Json -Depth 12
}

function Finish-Guard {
    param(
        [string]$Status,
        [string]$Reason,
        [hashtable]$Details = @{}
    )
    New-GuardResult -Status $Status -Reason $Reason -Details $Details
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

function Convert-ManifestToObjects {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        Finish-Guard "FAILED" "MANIFEST_NOT_FOUND" @{ path = $Path }
    }
    if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) {
        Finish-Guard "SKIPPED" "KUBECTL_NOT_AVAILABLE" @{ path = $Path }
    }

    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $json = kubectl apply --dry-run=client --validate=false -f $Path -o json 2>&1
    $exit = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($exit -ne 0) {
        Finish-Guard "FAILED" "KUBECTL_CLIENT_DRY_RUN_FAILED" @{
            path = $Path
            exitCode = $exit
            output = (($json | Out-String).Trim())
        }
    }

    $parsed = ($json | Out-String) | ConvertFrom-Json
    if ($parsed.kind -eq "List") {
        return @($parsed.items)
    }
    return @($parsed)
}

function Get-PropertyValue {
    param([object]$Object, [string]$Name)
    if ($null -eq $Object) {
        return $null
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

function Get-EnvVar {
    param([object[]]$Env, [string]$Name)
    return @($Env | Where-Object { $_.name -eq $Name } | Select-Object -First 1)[0]
}

function Assert-PowerShellParses {
    param([string[]]$Paths)
    $allErrors = @()
    foreach ($path in $Paths) {
        if (-not (Test-Path -LiteralPath $path)) {
            $allErrors += "missing:$path"
            continue
        }
        $tokens = $null
        $errors = $null
        [System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors) | Out-Null
        if ($errors.Count -gt 0) {
            foreach ($error in $errors) {
                $allErrors += "${path}:$($error.Message)"
            }
        }
    }
    if ($allErrors.Count -gt 0) {
        Finish-Guard "FAILED" "POWERSHELL_PARSE_FAILED" @{ errors = $allErrors }
    }
}

try {
    $configObjects = Convert-ManifestToObjects $ConfigMapManifest
    $configMap = $configObjects | Where-Object {
        $_.kind -eq "ConfigMap" -and $_.metadata.name -eq "ircs-dev-app-config"
    } | Select-Object -First 1
    if ($null -eq $configMap) {
        Finish-Guard "FAILED" "DEV_APP_CONFIGMAP_NOT_FOUND" @{ manifest = $ConfigMapManifest }
    }
    $bucket = [string](Get-PropertyValue $configMap.data "R2_BUCKET_NAME")
    if ([string]::IsNullOrWhiteSpace($bucket)) {
        Finish-Guard "FAILED" "DEV_R2_BUCKET_MISSING" @{ manifest = $ConfigMapManifest }
    }
    if ($bucket -eq $V1ProductionBucket) {
        Finish-Guard "FAILED" "PRODUCTION_BUCKET_AS_DEV_STANDING_CONFIG" @{
            manifest = $ConfigMapManifest
            bucket = $bucket
            expectedDevBucket = $ExpectedDevBucket
        }
    }
    if ($bucket -ne $ExpectedDevBucket) {
        Finish-Guard "FAILED" "DEV_R2_BUCKET_POLICY_MISMATCH" @{
            manifest = $ConfigMapManifest
            bucket = $bucket
            expectedDevBucket = $ExpectedDevBucket
        }
    }
    Mark "dev app ConfigMap manifest keeps R2_BUCKET_NAME away from V1 production bucket" 1 4

    $storageObjects = Convert-ManifestToObjects $StorageManifest
    $deployment = $storageObjects | Where-Object {
        $_.kind -eq "Deployment" -and $_.metadata.name -eq "ircs-storage-service"
    } | Select-Object -First 1
    if ($null -eq $deployment) {
        Finish-Guard "FAILED" "STORAGE_DEPLOYMENT_NOT_FOUND" @{ manifest = $StorageManifest }
    }
    $container = @($deployment.spec.template.spec.containers | Where-Object { $_.name -eq "app" } | Select-Object -First 1)[0]
    if ($null -eq $container) {
        Finish-Guard "FAILED" "STORAGE_APP_CONTAINER_NOT_FOUND" @{ manifest = $StorageManifest }
    }
    $env = @($container.env)
    $r2Enabled = Get-EnvVar $env "APP_STORAGE_R2_ENABLED"
    $r2Bucket = Get-EnvVar $env "APP_STORAGE_R2_BUCKET_NAME"
    $r2PublicDomain = Get-EnvVar $env "APP_STORAGE_R2_PUBLIC_DOMAIN"
    if ($null -eq $r2Enabled -or [string]$r2Enabled.value -ne "false") {
        Finish-Guard "FAILED" "STORAGE_R2_STANDING_RUNTIME_NOT_DISABLED" @{
            manifest = $StorageManifest
            value = if ($r2Enabled) { $r2Enabled.value } else { $null }
        }
    }
    if ($null -eq $r2Bucket -or $null -ne (Get-PropertyValue $r2Bucket "value")) {
        Finish-Guard "FAILED" "STORAGE_R2_BUCKET_NOT_CONFIGMAP_VALUEFROM" @{ manifest = $StorageManifest }
    }
    if ($r2Bucket.valueFrom.configMapKeyRef.name -ne "ircs-dev-app-config" `
            -or $r2Bucket.valueFrom.configMapKeyRef.key -ne "R2_BUCKET_NAME") {
        Finish-Guard "FAILED" "STORAGE_R2_BUCKET_VALUEFROM_MISMATCH" @{
            manifest = $StorageManifest
            valueFrom = $r2Bucket.valueFrom
        }
    }
    if ($null -eq $r2PublicDomain -or $r2PublicDomain.valueFrom.configMapKeyRef.name -ne "ircs-dev-app-config" `
            -or $r2PublicDomain.valueFrom.configMapKeyRef.key -ne "R2_PUBLIC_DOMAIN") {
        Finish-Guard "FAILED" "STORAGE_R2_PUBLIC_DOMAIN_VALUEFROM_MISMATCH" @{
            manifest = $StorageManifest
            valueFrom = if ($r2PublicDomain) { $r2PublicDomain.valueFrom } else { $null }
        }
    }
    $standingSecretEnv = @(
        "APP_STORAGE_R2_ACCOUNT_ID",
        "APP_STORAGE_R2_ACCESS_KEY",
        "APP_STORAGE_R2_SECRET_KEY",
        "APP_STORAGE_R2_ENDPOINT"
    ) | Where-Object { $null -ne (Get-EnvVar $env $_) }
    if ($standingSecretEnv.Count -gt 0) {
        Finish-Guard "FAILED" "STORAGE_R2_STANDING_CREDENTIAL_ENV_FOUND" @{
            manifest = $StorageManifest
            envNames = $standingSecretEnv
        }
    }
    Mark "storage-service dev manifest keeps R2 disabled and bucket bound by ConfigMap valueFrom" 2 4

    $storageSmokeScripts = @(
        "scripts/smoke/storage-r2-live-object-02807-smoke.ps1",
        "scripts/smoke/storage-service-r2-path-02807-smoke.ps1",
        "scripts/smoke/storage-avatar-r2-live-02807-smoke.ps1"
    )
    $scriptViolations = @()
    foreach ($path in $storageSmokeScripts) {
        if (-not (Test-Path -LiteralPath $path)) {
            $scriptViolations += "missing:$path"
            continue
        }
        $text = Get-Content -LiteralPath $path -Raw
        if ($text -notmatch "\[switch\]\`$UseV1ParityBucketOverride") {
            $scriptViolations += "missing explicit V1 override switch:$path"
        }
        if ($text -notmatch "PRODUCTION_BUCKET_AS_DEV_CONFIG_BLOCKED") {
            $scriptViolations += "missing production bucket standing-config guard:$path"
        }
        if ($text -match "IsNullOrWhiteSpace\(\`$BucketName\)\)\s*\{\s*`"ircs`"\s*\}") {
            $scriptViolations += "implicit V1 bucket default remains:$path"
        }
    }
    if ($scriptViolations.Count -gt 0) {
        Finish-Guard "FAILED" "STORAGE_SMOKE_BUCKET_GUARD_MISSING" @{ violations = $scriptViolations }
    }
    Mark "storage live smoke requires explicit V1 parity bucket override before temporary R2 writes" 3 4

    Assert-PowerShellParses $storageSmokeScripts
    Assert-PowerShellParses @("scripts/smoke/storage-dev-r2-bucket-policy-02807-guard.ps1")
    Mark "local parser/guard verification passes without writing R2 objects" 4 4

    New-GuardResult "SUCCESS" "DEV_R2_BUCKET_POLICY_GUARD_OK" @{
        configMapManifest = $ConfigMapManifest
        storageManifest = $StorageManifest
        devBucket = $bucket
        v1ProductionBucket = $V1ProductionBucket
        storageR2Enabled = $r2Enabled.value
        checkedScripts = $storageSmokeScripts
        writesR2Objects = $false
    }
} catch {
    Finish-Guard "FAILED" "DEV_R2_BUCKET_POLICY_GUARD_FAILED" @{
        message = $_.Exception.Message
    }
}
