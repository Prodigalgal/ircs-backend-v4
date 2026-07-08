param(
    [string]$Namespace = "ircs-dev",
    [string]$RtUrl = "https://www.rottentomatoes.com/search?search=The%20Matrix",
    [int]$TimeoutSeconds = 20,
    [int]$CredentialPort = 18091,
    [switch]$SkipKubernetesChecks,
    [switch]$FailOnExternalFailure
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$providerSourceRoot = Join-Path $repoRoot "services\ircs-metadata-worker\src\main\java\com\prodigalgal\ircs\metadata\provider"
$metadataManifest = Join-Path $repoRoot "deploy\k8s\dev\metadata-worker-dev.yaml"
$applicationYaml = Join-Path $repoRoot "services\ircs-metadata-worker\src\main\resources\application.yaml"
$credentialCatalog = Join-Path $repoRoot "services\ircs-credential-service\src\main\java\com\prodigalgal\ircs\credential\CredentialProviderCatalog.java"
$credentialBaseUrl = "http://127.0.0.1:$CredentialPort"
$credentialPfOut = Join-Path $env:TEMP "ircs-02806-rt-credential-pf.out.log"
$credentialPfErr = Join-Path $env:TEMP "ircs-02806-rt-credential-pf.err.log"
$credentialPf = $null

function ConvertTo-RepoPath {
    param([string]$Path)
    if ($Path.StartsWith($repoRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $Path.Substring($repoRoot.Length + 1)
    }
    return $Path
}

function Get-Preview {
    param(
        [AllowNull()][string]$Text,
        [int]$Limit = 300
    )
    if ($null -eq $Text) {
        return ""
    }
    $normalized = ($Text -replace "\s+", " ").Trim()
    if ($normalized.Length -le $Limit) {
        return $normalized
    }
    return $normalized.Substring(0, $Limit)
}

function Get-Sha256 {
    param([byte[]]$Bytes)
    if ($null -eq $Bytes) {
        return $null
    }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return [System.BitConverter]::ToString($sha.ComputeHash($Bytes)).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Convert-ForJson {
    param($Value)
    if ($null -eq $Value) {
        return $null
    }
    if ($Value -is [System.Collections.IDictionary]) {
        $object = [ordered]@{}
        foreach ($key in $Value.Keys) {
            $object[[string]$key] = Convert-ForJson $Value[$key]
        }
        return [pscustomobject]$object
    }
    if ($Value -is [System.Collections.IEnumerable] -and -not ($Value -is [string])) {
        return @($Value | ForEach-Object { Convert-ForJson $_ })
    }
    return $Value
}

function Invoke-CommandCapture {
    param(
        [string]$FilePath,
        [string[]]$Arguments
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        try {
            $output = & $FilePath @Arguments 2>&1
            $exitCode = $LASTEXITCODE
        } catch {
            return @{
                ok = $false
                exitCode = -1
                output = $_.Exception.Message
            }
        }
    } finally {
        $ErrorActionPreference = $previous
    }
    return @{
        ok = ($exitCode -eq 0)
        exitCode = $exitCode
        output = (($output | Out-String).Trim())
    }
}

function Get-YamlEnvValue {
    param(
        [string]$Text,
        [string]$Name
    )
    $pattern = "(?ms)-\s+name:\s*$([regex]::Escape($Name))\s*?\r?\n\s*value:\s*[""']?([^""'\r\n]+)"
    $match = [regex]::Match($Text, $pattern)
    if ($match.Success) {
        return $match.Groups[1].Value.Trim()
    }
    return $null
}

function Get-LocalGateEvidence {
    $manifestText = if (Test-Path $metadataManifest) { Get-Content -LiteralPath $metadataManifest -Raw } else { "" }
    $applicationText = if (Test-Path $applicationYaml) { Get-Content -LiteralPath $applicationYaml -Raw } else { "" }
    $appYamlDefault = $null
    $appYamlMatch = [regex]::Match($applicationText, "rotten-tomatoes-enabled:\s*\$\{APP_METADATA_RT_ENABLED:([^}]+)\}")
    if ($appYamlMatch.Success) {
        $appYamlDefault = $appYamlMatch.Groups[1].Value.Trim()
    }
    return [ordered]@{
        manifestPath = ConvertTo-RepoPath $metadataManifest
        applicationYamlPath = ConvertTo-RepoPath $applicationYaml
        appMetadataRtEnabled = Get-YamlEnvValue $manifestText "APP_METADATA_RT_ENABLED"
        appMetadataTmdbWorkerEnabled = Get-YamlEnvValue $manifestText "APP_METADATA_TMDB_WORKER_ENABLED"
        appMetadataListenerEnabled = Get-YamlEnvValue $manifestText "APP_METADATA_LISTENER_ENABLED"
        applicationYamlRtDefault = $appYamlDefault
    }
}

function Get-KubernetesGateEvidence {
    if ($SkipKubernetesChecks) {
        return [ordered]@{
            status = "SKIPPED"
            reason = "SKIP_KUBERNETES_CHECKS"
        }
    }
    $result = Invoke-CommandCapture "kubectl" @("-n", $Namespace, "get", "deployment", "ircs-metadata-worker", "-o", "json")
    if (-not $result.ok) {
        return [ordered]@{
            status = "UNAVAILABLE"
            reason = "KUBERNETES_UNAVAILABLE"
            exitCode = $result.exitCode
            output = Get-Preview $result.output
        }
    }
    try {
        $deployment = $result.output | ConvertFrom-Json
        $envMap = @{}
        foreach ($entry in @($deployment.spec.template.spec.containers[0].env)) {
            if ($null -ne $entry.value) {
                $envMap[$entry.name] = [string]$entry.value
            } elseif ($null -ne $entry.valueFrom) {
                $envMap[$entry.name] = "<valueFrom>"
            } else {
                $envMap[$entry.name] = $null
            }
        }
        return [ordered]@{
            status = "OK"
            appMetadataRtEnabled = $envMap["APP_METADATA_RT_ENABLED"]
            appMetadataTmdbWorkerEnabled = $envMap["APP_METADATA_TMDB_WORKER_ENABLED"]
            appMetadataListenerEnabled = $envMap["APP_METADATA_LISTENER_ENABLED"]
            namespace = $Namespace
        }
    } catch {
        return [ordered]@{
            status = "FAILED"
            reason = "KUBERNETES_DEPLOYMENT_JSON_PARSE_FAILED"
            message = $_.Exception.Message
        }
    }
}

function Get-ProviderImplementationEvidence {
    $implementationFiles = @()
    if (Test-Path $providerSourceRoot) {
        foreach ($file in Get-ChildItem -LiteralPath $providerSourceRoot -Recurse -Filter *.java) {
            if ($file.Name -eq "MetadataProviderWorker.java") {
                continue
            }
            $text = Get-Content -LiteralPath $file.FullName -Raw
            if ([regex]::IsMatch($text, "implements\s+MetadataProvider") -and [regex]::IsMatch($text, "ProviderType\.ROTTEN_TOMATOES")) {
                $implementationFiles += ConvertTo-RepoPath $file.FullName
            }
        }
    }
    return [ordered]@{
        sourceRoot = ConvertTo-RepoPath $providerSourceRoot
        exists = ($implementationFiles.Count -gt 0)
        files = $implementationFiles
    }
}

function Get-CredentialCatalogEvidence {
    $text = if (Test-Path $credentialCatalog) { Get-Content -LiteralPath $credentialCatalog -Raw } else { "" }
    $supportedProviders = @()
    foreach ($match in [regex]::Matches($text, 'case\s+"([^"]+)"')) {
        $supportedProviders += $match.Groups[1].Value
    }
    $supportedProviders = @($supportedProviders | Sort-Object -Unique)
    return [ordered]@{
        catalogPath = ConvertTo-RepoPath $credentialCatalog
        rottenTomatoesSupported = ($supportedProviders -contains "ROTTEN_TOMATOES")
        supportedProviders = $supportedProviders
    }
}

function Start-CredentialPortForward {
    Remove-Item -LiteralPath $credentialPfOut, $credentialPfErr -ErrorAction SilentlyContinue
    $process = Start-Process -FilePath kubectl `
        -ArgumentList @("-n", $Namespace, "port-forward", "svc/ircs-credential-service", "$($CredentialPort):8080") `
        -RedirectStandardOutput $credentialPfOut `
        -RedirectStandardError $credentialPfErr `
        -WindowStyle Hidden `
        -PassThru
    Start-Sleep -Seconds 4
    if ($process.HasExited) {
        $err = if (Test-Path $credentialPfErr) { Get-Content -LiteralPath $credentialPfErr -Raw } else { "" }
        return @{
            ok = $false
            error = Get-Preview $err
        }
    }
    return @{
        ok = $true
        process = $process
    }
}

function Get-SecretValue {
    param([string]$Key)
    $result = Invoke-CommandCapture "kubectl" @("-n", $Namespace, "get", "secret", "ircs-dev-secrets", "-o", "jsonpath={.data.$Key}")
    if (-not $result.ok -or [string]::IsNullOrWhiteSpace($result.output)) {
        return @{
            ok = $false
            reason = "KUBERNETES_SECRET_MISSING"
            exitCode = $result.exitCode
            output = Get-Preview $result.output
        }
    }
    try {
        return @{
            ok = $true
            value = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($result.output))
        }
    } catch {
        return @{
            ok = $false
            reason = "KUBERNETES_SECRET_DECODE_FAILED"
            message = $_.Exception.Message
        }
    }
}

function Get-CredentialServiceSecretToken {
    $token = Get-SecretValue "SERVICE_CREDENTIAL_TOKEN"
    if ($token.ok) {
        return $token
    }
    return Get-SecretValue "INTERNAL_CREDENTIAL_TOKEN"
}

function Invoke-CurlJson {
    param(
        [string]$Url,
        [string[]]$Headers = @()
    )
    $tmp = Join-Path $env:TEMP ("ircs-02806-rt-credential-response-" + [guid]::NewGuid() + ".json")
    $args = @("-sS", "--max-time", [string]$TimeoutSeconds, "-o", $tmp, "-w", "%{http_code}")
    foreach ($header in $Headers) {
        $args += @("-H", $header)
    }
    $args += $Url
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & curl.exe @args 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    $body = if (Test-Path $tmp) { Get-Content -LiteralPath $tmp -Raw -ErrorAction SilentlyContinue } else { "" }
    Remove-Item -LiteralPath $tmp -ErrorAction SilentlyContinue
    return @{
        ok = ($exitCode -eq 0)
        exitCode = $exitCode
        httpStatus = if ($exitCode -eq 0 -and "$output" -match "^\d+$") { [int]"$output" } else { $null }
        body = $body
        error = if ($exitCode -eq 0) { $null } else { (($output | Out-String).Trim()) }
    }
}

function Get-CredentialLeaseEvidence {
    if ($SkipKubernetesChecks) {
        return [ordered]@{
            status = "SKIPPED"
            reason = "SKIP_KUBERNETES_CHECKS"
        }
    }
    $pf = Start-CredentialPortForward
    if (-not $pf.ok) {
        return [ordered]@{
            status = "UNAVAILABLE"
            reason = "CREDENTIAL_SERVICE_PORT_FORWARD_UNAVAILABLE"
            error = $pf.error
        }
    }
    $script:credentialPf = $pf.process

    $token = Get-CredentialServiceSecretToken
    if (-not $token.ok) {
        return [ordered]@{
            status = "UNAVAILABLE"
            reason = $token.reason
            exitCode = $token.exitCode
            output = $token.output
        }
    }

    $leaseUrl = "$credentialBaseUrl/internal/credentials/providers/ROTTEN_TOMATOES/leases?limit=1"
    $response = Invoke-CurlJson -Url $leaseUrl -Headers @("X-IRCS-INTERNAL-TOKEN: $($token.value)")
    if (-not $response.ok) {
        return [ordered]@{
            status = "UNAVAILABLE"
            reason = "CREDENTIAL_SERVICE_CURL_FAILED"
            curlExitCode = $response.exitCode
            error = Get-Preview $response.error
        }
    }
    if ($response.httpStatus -lt 200 -or $response.httpStatus -ge 300) {
        return [ordered]@{
            status = "HTTP_$($response.httpStatus)"
            httpStatus = $response.httpStatus
            bodyPreview = Get-Preview $response.body
        }
    }
    try {
        $leases = $response.body | ConvertFrom-Json
        $count = if ($null -eq $leases) {
            0
        } elseif ($leases -is [System.Array]) {
            $leases.Count
        } else {
            1
        }
        $providers = @($leases | ForEach-Object { $_.provider } | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Sort-Object -Unique)
        return [ordered]@{
            status = "OK"
            httpStatus = $response.httpStatus
            leaseCount = $count
            providers = $providers
            secretPayloadOmitted = $true
        }
    } catch {
        return [ordered]@{
            status = "FAILED"
            reason = "CREDENTIAL_LEASE_JSON_PARSE_FAILED"
            message = $_.Exception.Message
            bodyPreview = Get-Preview $response.body
        }
    }
}

function Classify-RtHttp {
    param([AllowNull()][int]$StatusCode)
    if ($null -eq $StatusCode) {
        return "RT_HTTP_STATUS_UNKNOWN"
    }
    if ($StatusCode -ge 200 -and $StatusCode -lt 300) {
        return "RT_HTTP_OK"
    }
    if ($StatusCode -eq 403) {
        return "RT_FORBIDDEN"
    }
    if ($StatusCode -eq 429) {
        return "RT_RATE_LIMITED"
    }
    if ($StatusCode -ge 500) {
        return "RT_UPSTREAM_UNAVAILABLE"
    }
    return "RT_HTTP_$StatusCode"
}

function Get-FirstRtCandidate {
    param([string]$Html)
    $patterns = @(
        '<search-page-media-row[\s\S]*?<a(?=[^>]*slot=["'']title["''])(?=[^>]*href=["'']([^"'']+)["''])[^>]*>([\s\S]*?)</a>',
        '<search-page-media-row[\s\S]*?<a(?=[^>]*data-qa=["'']info-name["''])(?=[^>]*href=["'']([^"'']+)["''])[^>]*>([\s\S]*?)</a>',
        '<ul[^>]*slot=["'']list["''][^>]*>[\s\S]*?<li[\s\S]*?<a[^>]*href=["'']([^"'']+)["''][^>]*>([\s\S]*?)</a>'
    )
    foreach ($pattern in $patterns) {
        $match = [regex]::Match($Html, $pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
        if ($match.Success) {
            $href = $match.Groups[1].Value
            $title = ([regex]::Replace($match.Groups[2].Value, "<[^>]+>", "") -replace "\s+", " ").Trim()
            $path = $href
            try {
                if (-not $path.StartsWith("http", [System.StringComparison]::OrdinalIgnoreCase)) {
                    $path = "https://www.rottentomatoes.com" + ($(if ($path.StartsWith("/")) { "" } else { "/" })) + $path
                }
                $path = ([uri]$path).AbsolutePath.TrimEnd("/")
            } catch {
                $path = $href.TrimEnd("/")
            }
            $segments = @($path -split "/" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
            return [ordered]@{
                title = $title
                href = $href
                extractedId = if ($segments.Count -gt 0) { $segments[-1] } else { $null }
            }
        }
    }
    return $null
}

function Invoke-RtReadonlyHttp {
    $tmp = Join-Path $env:TEMP ("ircs-02806-rt-http-response-" + [guid]::NewGuid() + ".txt")
    $args = @(
        "-sS",
        "--max-time", [string]$TimeoutSeconds,
        "-L",
        "-o", $tmp,
        "-w", "%{http_code}|%{content_type}|%{time_total}|%{size_download}|%{url_effective}",
        "-H", "User-Agent: ircs-codex-smoke/02806 (+read-only)",
        "-H", "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        $RtUrl
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & curl.exe @args 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    $bodyBytes = if (Test-Path $tmp) { [System.IO.File]::ReadAllBytes($tmp) } else { $null }
    $bodyText = if (Test-Path $tmp) { Get-Content -LiteralPath $tmp -Raw -ErrorAction SilentlyContinue } else { "" }
    Remove-Item -LiteralPath $tmp -ErrorAction SilentlyContinue

    if ($exitCode -ne 0) {
        return [ordered]@{
            status = "FAILED"
            reason = "RT_NETWORK_ERROR"
            curlExitCode = $exitCode
            error = Get-Preview (($output | Out-String).Trim())
            requestCount = 1
        }
    }

    $parts = "$output" -split "\|", 5
    $httpStatus = if ($parts.Count -ge 1 -and $parts[0] -match "^\d+$") { [int]$parts[0] } else { $null }
    $reason = Classify-RtHttp $httpStatus
    $candidateSummary = [ordered]@{
        parseStatus = "SKIPPED"
        candidateCount = 0
    }
    if ($reason -eq "RT_HTTP_OK") {
        $candidate = Get-FirstRtCandidate $bodyText
        if ($null -ne $candidate) {
            $candidateSummary = [ordered]@{
                parseStatus = "OK"
                candidateCount = 1
                firstTitle = $candidate.title
                firstHref = $candidate.href
                firstExtractedId = $candidate.extractedId
                fullBodyOmitted = $true
            }
        } else {
            $candidateSummary = [ordered]@{
                parseStatus = "EMPTY"
                candidateCount = 0
                fullBodyOmitted = $true
            }
        }
    }
    return [ordered]@{
        status = if ($reason -eq "RT_HTTP_OK") { "OK" } else { "HTTP_FAILURE" }
        reason = $reason
        httpStatus = $httpStatus
        contentType = if ($parts.Count -ge 2) { $parts[1] } else { $null }
        timeTotalSeconds = if ($parts.Count -ge 3) { $parts[2] } else { $null }
        sizeDownloadBytes = if ($parts.Count -ge 4) { $parts[3] } else { $null }
        urlEffective = if ($parts.Count -ge 5) { $parts[4] } else { $RtUrl }
        bodySha256 = Get-Sha256 $bodyBytes
        bodyPreview = Get-Preview $bodyText 240
        candidateSummary = $candidateSummary
        requestCount = 1
    }
}

try {
    $localGate = Get-LocalGateEvidence
    $kubernetesGate = Get-KubernetesGateEvidence
    $implementation = Get-ProviderImplementationEvidence
    $credentialCatalogEvidence = Get-CredentialCatalogEvidence
    $credentialLeaseEvidence = Get-CredentialLeaseEvidence
    $rtHttp = Invoke-RtReadonlyHttp

    $blockingReasons = @()
    if (-not $implementation.exists) {
        $blockingReasons += "PROVIDER_IMPLEMENTATION_MISSING"
    }
    if (-not $credentialCatalogEvidence.rottenTomatoesSupported) {
        $blockingReasons += "CREDENTIAL_CATALOG_UNSUPPORTED"
    }
    if ($localGate.appMetadataRtEnabled -eq "false" -or $kubernetesGate.appMetadataRtEnabled -eq "false") {
        $blockingReasons += "DEV_PROVIDER_GATE_DISABLED"
    }
    if ($rtHttp.status -ne "OK") {
        $blockingReasons += $rtHttp.reason
    }

    $uniqueBlockingReasons = @($blockingReasons | Select-Object -Unique)
    $onlyDevGateDisabled = $uniqueBlockingReasons.Count -eq 1 `
        -and $uniqueBlockingReasons[0] -eq "DEV_PROVIDER_GATE_DISABLED"

    $overallStatus = if (-not $implementation.exists) {
        "BLOCKED"
    } elseif (-not $credentialCatalogEvidence.rottenTomatoesSupported) {
        "BLOCKED"
    } elseif ($rtHttp.status -ne "OK") {
        "FAILED"
    } elseif ($onlyDevGateDisabled) {
        "SUCCESS_WITH_DEV_GATE_DISABLED"
    } else {
        "SUCCESS"
    }
    $overallReason = if ($blockingReasons.Count -gt 0) {
        ($blockingReasons | Select-Object -Unique) -join ";"
    } else {
        "RT_MINIMAL_LIVE_SMOKE_OK"
    }

    $resultObject = [ordered]@{
        task = "028-06-H"
        slice = "rt-provider-implementation-first-slice-smoke"
        provider = "ROTTEN_TOMATOES"
        status = $overallStatus
        reason = $overallReason
        checkedAt = (Get-Date).ToUniversalTime().ToString("o")
        externalRequestCount = $rtHttp.requestCount
        writeDb = $false
        publishedQueue = $false
        patchedKubernetesEnv = $false
        cleanup = [ordered]@{
            runtimeStateChanged = $false
            credentialPortForwardStoppedByFinally = $true
        }
        evidence = [ordered]@{
            localGate = $localGate
            kubernetesGate = $kubernetesGate
            providerImplementation = $implementation
            credentialCatalog = $credentialCatalogEvidence
            credentialLease = $credentialLeaseEvidence
            rtHttp = $rtHttp
        }
    }
    Convert-ForJson $resultObject | ConvertTo-Json -Depth 20

    if ($overallStatus -eq "FAILED" -and $FailOnExternalFailure) {
        exit 1
    }
    exit 0
} finally {
    if ($credentialPf -and -not $credentialPf.HasExited) {
        Stop-Process -Id $credentialPf.Id -Force -ErrorAction SilentlyContinue
    }
}
