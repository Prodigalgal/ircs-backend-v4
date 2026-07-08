param(
    [string]$Namespace = "ircs-dev",
    [int]$MaxSmokeResidueAgeMinutes = 30,
    [string]$OutputJsonPath = ""
)

$ErrorActionPreference = "Stop"

$marks = 0
$totalMarks = 7
$failures = New-Object System.Collections.Generic.List[string]
$warnings = New-Object System.Collections.Generic.List[string]
$details = [ordered]@{
    namespace = $Namespace
    checkedAt = (Get-Date).ToUniversalTime().ToString("o")
}
$configMapCache = @{}

function Mark {
    param([string]$Message)
    $script:marks++
    Write-Output ("028-18 MARK {0}/{1} {2}" -f $script:marks, $script:totalMarks, $Message)
}

function Add-Failure {
    param([string]$Message)
    $script:failures.Add($Message) | Out-Null
}

function Add-Warning {
    param([string]$Message)
    $script:warnings.Add($Message) | Out-Null
}

function Get-ObjectProperty {
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

function Invoke-KubectlText {
    param([string[]]$Arguments, [switch]$AllowMissingResource)
    if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) {
        throw "kubectl is not available"
    }
    $output = & kubectl @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String).Trim()
    if ($exitCode -ne 0) {
        if ($AllowMissingResource -and ($text -match "the server doesn't have a resource type|no matches for kind|NotFound|not found")) {
            return $null
        }
        throw "kubectl $($Arguments -join ' ') failed: $text"
    }
    return $text
}

function Get-KubectlJson {
    param([string[]]$Arguments, [switch]$AllowMissingResource)
    $text = Invoke-KubectlText -Arguments $Arguments -AllowMissingResource:$AllowMissingResource
    if ([string]::IsNullOrWhiteSpace($text)) {
        return $null
    }
    return $text | ConvertFrom-Json
}

function Get-ConfigMapValue {
    param([string]$Name, [string]$Key)
    $cacheKey = "$Namespace/$Name"
    if (-not $script:configMapCache.ContainsKey($cacheKey)) {
        $script:configMapCache[$cacheKey] = Get-KubectlJson -Arguments @("-n", $Namespace, "get", "configmap", $Name, "-o", "json")
    }
    $configMap = $script:configMapCache[$cacheKey]
    $value = Get-ObjectProperty -Object $configMap.data -Name $Key
    if ($null -eq $value) {
        return "<missing-configmap-key:$Name/$Key>"
    }
    return [string]$value
}

function Get-EnvEntryValue {
    param([object]$Entry)
    if ($null -eq $Entry) {
        return "<missing>"
    }
    if ($null -ne (Get-ObjectProperty -Object $Entry -Name "value")) {
        return [string]$Entry.value
    }
    $valueFrom = Get-ObjectProperty -Object $Entry -Name "valueFrom"
    if ($null -eq $valueFrom) {
        return "<missing-value>"
    }
    $configMapRef = Get-ObjectProperty -Object $valueFrom -Name "configMapKeyRef"
    if ($null -ne $configMapRef) {
        return Get-ConfigMapValue -Name $configMapRef.name -Key $configMapRef.key
    }
    $secretRef = Get-ObjectProperty -Object $valueFrom -Name "secretKeyRef"
    if ($null -ne $secretRef) {
        return "<secret:$($secretRef.name)/$($secretRef.key)>"
    }
    return "<valueFrom>"
}

function Find-DeploymentEnv {
    param([object]$Deployment, [string]$EnvName)
    foreach ($container in @($Deployment.spec.template.spec.containers)) {
        foreach ($entry in @($container.env)) {
            if ($entry.name -eq $EnvName) {
                return [ordered]@{
                    container = $container.name
                    value = Get-EnvEntryValue -Entry $entry
                }
            }
        }
    }
    return [ordered]@{
        container = "<missing>"
        value = "<missing>"
    }
}

function Convert-ToIntOrNull {
    param([object]$Value)
    $number = 0
    if ([int]::TryParse([string]$Value, [ref]$number)) {
        return $number
    }
    return $null
}

function Test-SmokeSignature {
    param([object]$Resource)
    $tokens = New-Object System.Collections.Generic.List[string]
    if ($Resource.metadata.name) { $tokens.Add([string]$Resource.metadata.name) | Out-Null }
    foreach ($bagName in @("labels", "annotations")) {
        $bag = Get-ObjectProperty -Object $Resource.metadata -Name $bagName
        if ($null -ne $bag) {
            foreach ($property in $bag.PSObject.Properties) {
                $tokens.Add([string]$property.Name) | Out-Null
                $tokens.Add([string]$property.Value) | Out-Null
            }
        }
    }
    return (($tokens -join " ") -match "(?i)(smoke|codex|028[-_])")
}

function Get-ResourceIdentity {
    param([string]$Kind, [object]$Resource)
    return "$Kind/$($Resource.metadata.name)"
}

try {
    $quotaList = Get-KubectlJson -Arguments @("-n", $Namespace, "get", "resourcequota", "-o", "json")
    $quota = @($quotaList.items | Where-Object { $_.metadata.name -eq "ircs-dev-quota" } | Select-Object -First 1)[0]
    if ($null -eq $quota) {
        $quota = @($quotaList.items | Select-Object -First 1)[0]
    }
    if ($null -eq $quota) {
        Add-Failure "no ResourceQuota found in $Namespace"
    } else {
        $requiredQuotaKeys = @("pods", "services", "configmaps", "secrets", "requests.cpu", "requests.memory", "limits.cpu", "limits.memory")
        $quotaSummary = [ordered]@{
            name = $quota.metadata.name
            used = [ordered]@{}
            hard = [ordered]@{}
        }
        foreach ($key in $requiredQuotaKeys) {
            $hard = Get-ObjectProperty -Object $quota.status.hard -Name $key
            $used = Get-ObjectProperty -Object $quota.status.used -Name $key
            $quotaSummary.hard[$key] = [string]$hard
            $quotaSummary.used[$key] = [string]$used
            if ([string]::IsNullOrWhiteSpace([string]$hard) -or [string]::IsNullOrWhiteSpace([string]$used)) {
                Add-Failure "ResourceQuota $($quota.metadata.name) missing $key hard/used"
            }
        }
        foreach ($scarceKey in @("pods", "services")) {
            $usedInt = Convert-ToIntOrNull (Get-ObjectProperty -Object $quota.status.used -Name $scarceKey)
            $hardInt = Convert-ToIntOrNull (Get-ObjectProperty -Object $quota.status.hard -Name $scarceKey)
            if ($null -ne $usedInt -and $null -ne $hardInt) {
                $remaining = $hardInt - $usedInt
                if ($remaining -lt 0) {
                    Add-Failure "ResourceQuota $scarceKey is over hard limit: used=$usedInt hard=$hardInt"
                } elseif ($remaining -lt 2) {
                    Add-Warning "ResourceQuota $scarceKey remaining buffer is $remaining (<2)"
                }
            }
        }
        $details["resourceQuota"] = $quotaSummary
    }
    Mark "ircs-dev ResourceQuota pods/services/configmaps/secrets/cpu/memory can be read"

    $deploymentList = Get-KubectlJson -Arguments @("-n", $Namespace, "get", "deployments", "-o", "json")
    $deploymentResourceDetails = New-Object System.Collections.Generic.List[object]
    foreach ($deployment in @($deploymentList.items)) {
        $replicas = if ($null -ne $deployment.spec.replicas) { [int]$deployment.spec.replicas } else { 1 }
        if ($replicas -gt 1) {
            Add-Warning "deployment/$($deployment.metadata.name) replicas=$replicas exceeds dev single-replica baseline"
        }
        foreach ($container in @($deployment.spec.template.spec.containers)) {
            $resources = $container.resources
            $requests = Get-ObjectProperty -Object $resources -Name "requests"
            $limits = Get-ObjectProperty -Object $resources -Name "limits"
            $missing = @()
            foreach ($path in @("requests.cpu", "requests.memory", "limits.cpu", "limits.memory")) {
                $parts = $path.Split(".")
                $root = if ($parts[0] -eq "requests") { $requests } else { $limits }
                if ([string]::IsNullOrWhiteSpace([string](Get-ObjectProperty -Object $root -Name $parts[1]))) {
                    $missing += $path
                }
            }
            if ($missing.Count -gt 0) {
                Add-Failure "deployment/$($deployment.metadata.name) container/$($container.name) missing resources: $($missing -join ',')"
            }
            $deploymentResourceDetails.Add([ordered]@{
                deployment = $deployment.metadata.name
                container = $container.name
                replicas = $replicas
                requestsCpu = [string](Get-ObjectProperty -Object $requests -Name "cpu")
                requestsMemory = [string](Get-ObjectProperty -Object $requests -Name "memory")
                limitsCpu = [string](Get-ObjectProperty -Object $limits -Name "cpu")
                limitsMemory = [string](Get-ObjectProperty -Object $limits -Name "memory")
            }) | Out-Null
        }
    }
    $details["deploymentResources"] = $deploymentResourceDetails.ToArray()
    Mark "every live Deployment container has requests and limits"

    $httpRoutes = Get-KubectlJson -Arguments @("-n", $Namespace, "get", "httproute", "-o", "json") -AllowMissingResource
    if ($null -eq $httpRoutes) {
        $details["httpRoutes"] = @{ unavailable = $true; count = 0 }
    } else {
        $routeNames = @($httpRoutes.items | ForEach-Object { $_.metadata.name })
        if ($routeNames.Count -gt 0) {
            Add-Failure "HTTPRoute exists in ${Namespace}: $($routeNames -join ',')"
        }
        $details["httpRoutes"] = @{ unavailable = $false; count = $routeNames.Count; names = $routeNames }
    }
    Mark "no HTTPRoute exists in ircs-dev"

    $now = Get-Date
    $jobResidue = New-Object System.Collections.Generic.List[object]
    $podResidue = New-Object System.Collections.Generic.List[object]
    $jobList = Get-KubectlJson -Arguments @("-n", $Namespace, "get", "jobs", "-o", "json") -AllowMissingResource
    if ($null -ne $jobList) {
        foreach ($job in @($jobList.items | Where-Object { Test-SmokeSignature $_ })) {
            $status = if ($job.status.failed -gt 0) { "Failed" } elseif ($job.status.succeeded -gt 0) { "Succeeded" } elseif ($job.status.active -gt 0) { "Active" } else { "Unknown" }
            $ageMinutes = $null
            if ($job.status.completionTime) {
                $ageMinutes = [math]::Round(($now - [datetime]$job.status.completionTime).TotalMinutes, 2)
            }
            $entry = [ordered]@{
                name = $job.metadata.name
                status = $status
                ageMinutes = $ageMinutes
            }
            $jobResidue.Add($entry) | Out-Null
            if (($status -eq "Succeeded" -or $status -eq "Failed") -and $null -ne $ageMinutes -and $ageMinutes -gt $MaxSmokeResidueAgeMinutes) {
                Add-Failure "smoke job residue exceeds $MaxSmokeResidueAgeMinutes minutes: job/$($job.metadata.name) age=${ageMinutes}m"
            } elseif ($status -eq "Active") {
                Add-Warning "smoke job is still active: job/$($job.metadata.name)"
            }
        }
    }
    $podList = Get-KubectlJson -Arguments @("-n", $Namespace, "get", "pods", "-o", "json")
    foreach ($pod in @($podList.items | Where-Object { Test-SmokeSignature $_ })) {
        $phase = [string]$pod.status.phase
        $entry = [ordered]@{
            name = $pod.metadata.name
            phase = $phase
            node = [string]$pod.spec.nodeName
        }
        $podResidue.Add($entry) | Out-Null
        if ($phase -eq "Succeeded" -or $phase -eq "Failed") {
            Add-Failure "smoke pod residue remains: pod/$($pod.metadata.name) phase=$phase"
        } elseif ($phase -ne "Running") {
            Add-Warning "smoke pod is not Running: pod/$($pod.metadata.name) phase=$phase"
        }
    }
    $details["jobResidue"] = $jobResidue.ToArray()
    $details["podResidue"] = $podResidue.ToArray()
    Mark "no completed/failed smoke Job residue exceeds TTL budget and no smoke Pod residue remains"

    $deploymentsByName = @{}
    foreach ($deployment in @($deploymentList.items)) {
        $deploymentsByName[$deployment.metadata.name] = $deployment
    }
    $gateChecks = @(
        @{ deployment = "ircs-storage-service"; env = "APP_STORAGE_R2_ENABLED"; expected = "false" },
        @{ deployment = "ircs-storage-service"; env = "APP_STORAGE_R2_WORK_QUEUE_WORKER_ENABLED"; expected = "false" },
        @{ deployment = "ircs-notification-worker"; env = "APP_MAIL_ENABLED"; expected = "false" },
        @{ deployment = "ircs-notification-worker"; env = "APP_MAIL_SEND_HISTORY_CLEANUP_ENABLED"; expected = "false" },
        @{ deployment = "ircs-notification-worker"; env = "APP_MAIL_SEND_HISTORY_CLEANUP_DRY_RUN"; expected = "true" },
        @{ deployment = "ircs-notification-worker"; env = "APP_MAIL_SEND_HISTORY_CLEANUP_EXECUTE_ENABLED"; expected = "false" },
        @{ deployment = "ircs-task-service"; env = "APP_TASK_SCHEDULER_ENABLED"; expected = "false" },
        @{ deployment = "ircs-task-service"; env = "APP_TASK_WATCHDOG_ENABLED"; expected = "false" },
        @{ deployment = "ircs-aggregation-worker"; env = "APP_AGGREGATION_WORK_QUEUE_BATCH_SIZE"; expected = "1" },
        @{ deployment = "ircs-normalization-worker"; env = "APP_NORMALIZATION_WATCHDOG_ENABLED"; expected = "false" },
        @{ deployment = "ircs-metadata-worker"; env = "APP_METADATA_DOUBAN_ENABLED"; expected = "false" },
        @{ deployment = "ircs-metadata-worker"; env = "APP_METADATA_TMDB_ENABLED"; expected = "false" },
        @{ deployment = "ircs-metadata-worker"; env = "APP_METADATA_RT_ENABLED"; expected = "false" },
        @{ deployment = "ircs-ops-service"; env = "OPS_MAINTENANCE_REINDEX_DEV_LIMIT"; expected = "5" }
    )
    $gateDetails = New-Object System.Collections.Generic.List[object]
    foreach ($check in $gateChecks) {
        $deployment = $deploymentsByName[$check.deployment]
        if ($null -eq $deployment) {
            Add-Failure "gate check deployment missing: $($check.deployment)"
            continue
        }
        $actual = Find-DeploymentEnv -Deployment $deployment -EnvName $check.env
        $gateDetails.Add([ordered]@{
            deployment = $check.deployment
            env = $check.env
            expected = $check.expected
            actual = $actual.value
            container = $actual.container
        }) | Out-Null
        if ([string]$actual.value -ne [string]$check.expected) {
            Add-Failure "gate mismatch $($check.deployment) $($check.env): expected=$($check.expected) actual=$($actual.value)"
        }
    }
    $details["gates"] = $gateDetails.ToArray()
    Mark "dangerous live gates remain at dev-safe defaults"

    $residueKinds = @("configmaps", "secrets", "services")
    $recentResidue = New-Object System.Collections.Generic.List[object]
    foreach ($kind in $residueKinds) {
        $resourceList = Get-KubectlJson -Arguments @("-n", $Namespace, "get", $kind, "-o", "json") -AllowMissingResource
        if ($null -eq $resourceList) {
            continue
        }
        foreach ($resource in @($resourceList.items | Where-Object { Test-SmokeSignature $_ })) {
            $identity = Get-ResourceIdentity -Kind $kind -Resource $resource
            $recentResidue.Add([ordered]@{
                identity = $identity
                createdAt = [string]$resource.metadata.creationTimestamp
            }) | Out-Null
            Add-Warning "recent smoke/codex residue label or name remains: $identity"
        }
    }
    foreach ($entry in $jobResidue.ToArray()) {
        $recentResidue.Add([ordered]@{ identity = "jobs/$($entry.name)"; status = $entry.status; ageMinutes = $entry.ageMinutes }) | Out-Null
    }
    foreach ($entry in $podResidue.ToArray()) {
        $recentResidue.Add([ordered]@{ identity = "pods/$($entry.name)"; status = $entry.phase }) | Out-Null
    }
    $details["recentSmokeResidue"] = $recentResidue.ToArray()
    Mark "recent smoke residue labels are absent or explicitly listed"

    $summaryStatus = if ($failures.Count -gt 0) { "FAILED" } elseif ($warnings.Count -gt 0) { "WARN" } else { "PASSED" }
    $summary = [ordered]@{
        task = "028-18"
        status = $summaryStatus
        failures = $failures.ToArray()
        warnings = $warnings.ToArray()
        details = $details
    }
    $summaryJson = $summary | ConvertTo-Json -Depth 20
    if (-not [string]::IsNullOrWhiteSpace($OutputJsonPath)) {
        $summaryJson | Set-Content -LiteralPath $OutputJsonPath -Encoding UTF8
    }
    Mark "verifier emits machine-readable summary for docs/tasks回写"
    $summaryJson

    if ($failures.Count -gt 0) {
        exit 1
    }
    exit 0
} catch {
    $line = if ($_.InvocationInfo.ScriptLineNumber) { $_.InvocationInfo.ScriptLineNumber } else { "unknown" }
    Add-Failure "line ${line}: $($_.Exception.Message)"
    $summary = [ordered]@{
        task = "028-18"
        status = "FAILED"
        failures = $failures.ToArray()
        warnings = $warnings.ToArray()
        details = $details
    }
    $summaryJson = $summary | ConvertTo-Json -Depth 20
    if (-not [string]::IsNullOrWhiteSpace($OutputJsonPath)) {
        $summaryJson | Set-Content -LiteralPath $OutputJsonPath -Encoding UTF8
    }
    $summaryJson
    exit 1
}
