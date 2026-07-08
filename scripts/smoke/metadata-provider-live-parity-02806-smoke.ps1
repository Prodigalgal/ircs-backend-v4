param(
    [string]$CredentialBaseUrl = $(if ($env:APP_METADATA_CREDENTIAL_SERVICE_BASE_URL) { $env:APP_METADATA_CREDENTIAL_SERVICE_BASE_URL } else { "http://127.0.0.1:18088" }),
    [string]$CredentialToken = $env:APP_METADATA_CREDENTIAL_SERVICE_TOKEN,
    [string]$TmdbBaseUrl = $(if ($env:APP_METADATA_TMDB_BASE_URL) { $env:APP_METADATA_TMDB_BASE_URL } else { "https://api.themoviedb.org/3" }),
    [string]$Namespace = "ircs-dev",
    [string]$CredentialService = "svc/ircs-credential-service",
    [int]$CredentialPort = 18088,
    [int]$TimeoutSeconds = 20,
    [string]$Query = "The Matrix",
    [string]$Year = "1999",
    [switch]$StartCredentialPortForward,
    [switch]$UseKubernetesSecretToken,
    [switch]$FailOnSkip
)

$ErrorActionPreference = "Stop"

$pfOut = Join-Path $env:TEMP "ircs-02806-credential-pf.out.log"
$pfErr = Join-Path $env:TEMP "ircs-02806-credential-pf.err.log"
$credentialPf = $null

function New-SmokeResult {
    param(
        [string]$Status,
        [string]$Reason,
        [hashtable]$Details = @{}
    )
    $result = [ordered]@{
        task = "028-06"
        slice = "metadata-provider-live-parity"
        provider = "TMDB"
        status = $Status
        reason = $Reason
        checkedAt = (Get-Date).ToUniversalTime().ToString("o")
        credentialBaseUrl = $CredentialBaseUrl
        tmdbBaseUrl = $TmdbBaseUrl
        details = $Details
    }
    $result | ConvertTo-Json -Depth 10
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

function Start-PortForward {
    Remove-Item -LiteralPath $pfOut, $pfErr -ErrorAction SilentlyContinue
    $process = Start-Process -FilePath kubectl `
        -ArgumentList @("-n", $Namespace, "port-forward", $CredentialService, "$($CredentialPort):8080") `
        -RedirectStandardOutput $pfOut `
        -RedirectStandardError $pfErr `
        -WindowStyle Hidden `
        -PassThru
    Start-Sleep -Seconds 5
    if ($process.HasExited) {
        $err = if (Test-Path $pfErr) { Get-Content -LiteralPath $pfErr -Raw } else { "" }
        throw "$CredentialService port-forward exited: $err"
    }
    return $process
}

function Get-SecretValue {
    param([string]$Key)
    $value64 = kubectl -n $Namespace get secret ircs-dev-secrets -o jsonpath="{.data.$Key}"
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($value64)) {
        throw "$Key not found in ircs-dev-secrets"
    }
    return [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($value64))
}

function Get-OptionalSecretValue {
    param([string]$Key)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $value64 = kubectl -n $Namespace get secret ircs-dev-secrets -o jsonpath="{.data.$Key}" 2>&1
    $exit = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($exit -ne 0 -or [string]::IsNullOrWhiteSpace($value64)) {
        return $null
    }
    return [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($value64))
}

function Get-CredentialServiceSecretToken {
    $token = Get-OptionalSecretValue "SERVICE_CREDENTIAL_TOKEN"
    if (-not [string]::IsNullOrWhiteSpace($token)) {
        return $token
    }
    return Get-SecretValue "INTERNAL_CREDENTIAL_TOKEN"
}

function Invoke-CurlJson {
    param(
        [string]$Url,
        [string[]]$Headers = @()
    )
    $tmp = Join-Path $env:TEMP ("ircs-02806-response-" + [guid]::NewGuid() + ".json")
    $args = @("-sS", "--max-time", [string]$TimeoutSeconds, "-o", $tmp, "-w", "%{http_code}")
    foreach ($header in $Headers) {
        $args += @("-H", $header)
    }
    $args += $Url

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & curl.exe @args 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $body = if (Test-Path $tmp) { Get-Content -LiteralPath $tmp -Raw -ErrorAction SilentlyContinue } else { "" }
    Remove-Item -LiteralPath $tmp -ErrorAction SilentlyContinue

    if ($exitCode -ne 0) {
        return @{
            ok = $false
            code = $null
            body = $body
            error = ($output -join "`n")
            exitCode = $exitCode
        }
    }

    return @{
        ok = $true
        code = [int]$output
        body = $body
        error = $null
        exitCode = 0
    }
}

function Convert-JsonBody {
    param(
        [string]$Body,
        [string]$FailureReason
    )
    try {
        return $Body | ConvertFrom-Json
    } catch {
        Finish-Smoke "FAILED" $FailureReason @{
            parseError = $_.Exception.Message
            bodyPreview = if ($Body.Length -gt 500) { $Body.Substring(0, 500) } else { $Body }
        }
    }
}

try {
    if ($StartCredentialPortForward) {
        $credentialPf = Start-PortForward
        $CredentialBaseUrl = "http://127.0.0.1:$CredentialPort"
    }

    if ($UseKubernetesSecretToken -and [string]::IsNullOrWhiteSpace($CredentialToken)) {
        $CredentialToken = Get-CredentialServiceSecretToken
    }

    $headers = @()
    if (-not [string]::IsNullOrWhiteSpace($CredentialToken)) {
        $headers += "X-IRCS-INTERNAL-TOKEN: $CredentialToken"
    }

    $leaseUrl = "$($CredentialBaseUrl.TrimEnd('/'))/internal/credentials/providers/TMDB/leases?requiredPayloadKey=api_key&limit=1"
    $leaseResponse = Invoke-CurlJson -Url $leaseUrl -Headers $headers
    if (-not $leaseResponse.ok) {
        Finish-Smoke "SKIPPED" "CREDENTIAL_SERVICE_UNAVAILABLE" @{
            curlExitCode = $leaseResponse.exitCode
            error = $leaseResponse.error
        }
    }
    if ($leaseResponse.code -lt 200 -or $leaseResponse.code -ge 300) {
        Finish-Smoke "SKIPPED" "CREDENTIAL_SERVICE_HTTP_$($leaseResponse.code)" @{
            httpStatus = $leaseResponse.code
            bodyPreview = if ($leaseResponse.body.Length -gt 500) { $leaseResponse.body.Substring(0, 500) } else { $leaseResponse.body }
        }
    }

    $leases = Convert-JsonBody -Body $leaseResponse.body -FailureReason "CREDENTIAL_LEASE_RESPONSE_PARSE_ERROR"
    if ($null -eq $leases -or $leases.Count -eq 0) {
        Finish-Smoke "SKIPPED" "NO_ENABLED_TMDB_CREDENTIALS" @{
            leaseHttpStatus = $leaseResponse.code
        }
    }

    $lease = @($leases | Where-Object {
            $null -ne $_.secretPayload -and -not [string]::IsNullOrWhiteSpace($_.secretPayload.api_key)
        } | Select-Object -First 1)
    if ($lease.Count -eq 0) {
        Finish-Smoke "SKIPPED" "TMDB_CREDENTIAL_WITHOUT_API_KEY" @{
            leaseHttpStatus = $leaseResponse.code
            leaseCount = $leases.Count
        }
    }

    $credential = $lease[0]
    $apiKey = [string]$credential.secretPayload.api_key
    $encodedQuery = [System.Uri]::EscapeDataString($Query)
    $encodedYear = [System.Uri]::EscapeDataString($Year)
    $tmdbUrl = "$($TmdbBaseUrl.TrimEnd('/'))/search/movie?api_key=$([System.Uri]::EscapeDataString($apiKey))&query=$encodedQuery&language=zh-CN&include_adult=false&page=1&year=$encodedYear"
    $tmdbResponse = Invoke-CurlJson -Url $tmdbUrl
    if (-not $tmdbResponse.ok) {
        Finish-Smoke "FAILED" "TMDB_NETWORK_ERROR" @{
            curlExitCode = $tmdbResponse.exitCode
            error = $tmdbResponse.error
            credentialId = $credential.id
            priority = $credential.priority
            rateLimit = $credential.rateLimit
            rateLimitUnit = $credential.rateLimitUnit
        }
    }
    if ($tmdbResponse.code -lt 200 -or $tmdbResponse.code -ge 300) {
        $reason = if ($tmdbResponse.code -eq 401) {
            "TMDB_CREDENTIAL_REJECTED"
        } elseif ($tmdbResponse.code -eq 429) {
            "TMDB_RATE_LIMITED"
        } elseif ($tmdbResponse.code -ge 500) {
            "TMDB_UPSTREAM_UNAVAILABLE"
        } else {
            "TMDB_HTTP_$($tmdbResponse.code)"
        }
        Finish-Smoke "FAILED" $reason @{
            httpStatus = $tmdbResponse.code
            credentialId = $credential.id
            priority = $credential.priority
            rateLimit = $credential.rateLimit
            rateLimitUnit = $credential.rateLimitUnit
            bodyPreview = if ($tmdbResponse.body.Length -gt 500) { $tmdbResponse.body.Substring(0, 500) } else { $tmdbResponse.body }
        }
    }

    $tmdbBody = Convert-JsonBody -Body $tmdbResponse.body -FailureReason "TMDB_RESPONSE_PARSE_ERROR"
    $totalResults = [int]$tmdbBody.total_results
    if ($totalResults -le 0) {
        Finish-Smoke "FAILED" "TMDB_SEARCH_RETURNED_NO_RESULTS" @{
            httpStatus = $tmdbResponse.code
            query = $Query
            year = $Year
            credentialId = $credential.id
        }
    }

    Finish-Smoke "SUCCESS" "TMDB_LIVE_SEARCH_OK" @{
        httpStatus = $tmdbResponse.code
        query = $Query
        year = $Year
        totalResults = $totalResults
        firstResultId = $tmdbBody.results[0].id
        firstResultTitle = $tmdbBody.results[0].title
        credentialId = $credential.id
        priority = $credential.priority
        rateLimit = $credential.rateLimit
        rateLimitUnit = $credential.rateLimitUnit
    }
} finally {
    if ($credentialPf -and -not $credentialPf.HasExited) {
        Stop-Process -Id $credentialPf.Id -Force -ErrorAction SilentlyContinue
    }
}
