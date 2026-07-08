param(
    [string]$Namespace = "ircs-dev",
    [string]$CredentialBaseUrl = "http://127.0.0.1:18090",
    [int]$CredentialPort = 18090,
    [string]$CredentialToken = $env:APP_METADATA_CREDENTIAL_SERVICE_TOKEN,
    [string]$BucketName = "",
    [string]$PublicDomain = "",
    [switch]$UseV1ParityBucketOverride,
    [switch]$StartCredentialPortForward,
    [switch]$UseKubernetesSecretToken,
    [switch]$PreflightOnly,
    [switch]$NoWrite,
    [switch]$ConfirmLiveR2Write,
    [switch]$FailOnSkip
)

$ErrorActionPreference = "Stop"

$stamp = Get-Date -Format "yyyyMMddHHmmss"
$objectKey = "codex-smoke/02807/$stamp-r2-live-object.txt"
$credentialPfOut = Join-Path $env:TEMP "ircs-02807-r2-credential-pf.out.log"
$credentialPfErr = Join-Path $env:TEMP "ircs-02807-r2-credential-pf.err.log"
$credentialPf = $null

function New-SmokeResult {
    param(
        [string]$Status,
        [string]$Reason,
        [hashtable]$Details = @{}
    )
    [ordered]@{
        task = "028-07"
        slice = "storage-r2-live-object"
        status = $Status
        reason = $Reason
        checkedAt = (Get-Date).ToUniversalTime().ToString("o")
        namespace = $Namespace
        credentialBaseUrl = $CredentialBaseUrl
        objectKey = $objectKey
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

function Finish-R2LiveObjectPreflight {
    $explicitBucketOverride = -not [string]::IsNullOrWhiteSpace($BucketName)
    if (-not $explicitBucketOverride -and -not $UseV1ParityBucketOverride) {
        Finish-Smoke "FAILED" "EXPLICIT_BUCKET_OVERRIDE_REQUIRED_FOR_PREFLIGHT" @{
            hint = "pass -UseV1ParityBucketOverride or -BucketName ircs before an explicit V1 bucket smoke"
            preflightOnly = [bool]$PreflightOnly
            noWrite = [bool]$NoWrite
            confirmLiveR2Write = [bool]$ConfirmLiveR2Write
            willWriteR2Objects = $false
            willPatchDeployment = $false
        }
    }

    $preflightBucket = if ($explicitBucketOverride) { $BucketName.Trim() } else { "ircs" }
    Finish-Smoke "SUCCESS" "R2_LIVE_OBJECT_PREFLIGHT_OK" @{
        bucket = $preflightBucket
        bucketSource = if ($explicitBucketOverride) { "BucketName" } else { "UseV1ParityBucketOverride" }
        publicDomain = if ([string]::IsNullOrWhiteSpace($PublicDomain)) { $null } else { $PublicDomain.Trim() }
        objectKey = $objectKey
        preflightOnly = [bool]$PreflightOnly
        noWrite = [bool]$NoWrite
        confirmLiveR2Write = [bool]$ConfirmLiveR2Write
        confirmIgnoredByNoWrite = [bool](($PreflightOnly -or $NoWrite) -and $ConfirmLiveR2Write)
        willWriteR2Objects = $false
        willPatchDeployment = $false
        requiresExplicitBucketOverride = $true
        k8sAccessed = $false
        credentialLeaseAccessed = $false
    }
}

function Test-KubectlAccess {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = kubectl -n $Namespace get pod 2>&1
    $exit = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($exit -ne 0) {
        Finish-Smoke "SKIPPED" "KUBERNETES_UNAVAILABLE" @{
            exitCode = $exit
            output = (($output | Out-String).Trim())
        }
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

function Get-SecretValue {
    param([string]$Key)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $value64 = kubectl -n $Namespace get secret ircs-dev-secrets -o jsonpath="{.data.$Key}" 2>&1
    $exit = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($exit -ne 0 -or [string]::IsNullOrWhiteSpace($value64)) {
        Finish-Smoke "SKIPPED" "KUBERNETES_SECRET_MISSING" @{
            key = $Key
            exitCode = $exit
            output = (($value64 | Out-String).Trim())
        }
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

function Get-ConfigMapValue {
    param([string]$Key)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $value = kubectl -n $Namespace get configmap ircs-dev-app-config -o jsonpath="{.data.$Key}" 2>&1
    $exit = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($exit -ne 0 -or [string]::IsNullOrWhiteSpace($value)) {
        Finish-Smoke "SKIPPED" "KUBERNETES_CONFIG_MISSING" @{
            key = $Key
            exitCode = $exit
            output = (($value | Out-String).Trim())
        }
    }
    return [string]$value
}

function Get-R2CredentialLease {
    if ($UseKubernetesSecretToken -and [string]::IsNullOrWhiteSpace($CredentialToken)) {
        $script:CredentialToken = Get-CredentialServiceSecretToken
    }
    if ([string]::IsNullOrWhiteSpace($CredentialToken)) {
        Finish-Smoke "SKIPPED" "CREDENTIAL_TOKEN_MISSING" @{
            hint = "pass -UseKubernetesSecretToken or set APP_METADATA_CREDENTIAL_SERVICE_TOKEN"
        }
    }

    $leaseUrl = "$($CredentialBaseUrl.TrimEnd('/'))/internal/credentials/providers/R2/leases?requiredPayloadKey=access_key&limit=1"
    try {
        $response = Invoke-RestMethod -Method Get -Uri $leaseUrl -Headers @{
            "X-IRCS-INTERNAL-TOKEN" = $CredentialToken
        } -TimeoutSec 20
    } catch {
        Finish-Smoke "SKIPPED" "CREDENTIAL_SERVICE_UNAVAILABLE" @{
            error = $_.Exception.Message
        }
    }

    $leases = @($response)
    if ($leases.Count -eq 0) {
        Finish-Smoke "SKIPPED" "NO_ENABLED_R2_CREDENTIALS" @{}
    }

    $credential = $leases | Where-Object {
        $null -ne $_.secretPayload `
            -and -not [string]::IsNullOrWhiteSpace([string]$_.secretPayload.access_key) `
            -and -not [string]::IsNullOrWhiteSpace([string]$_.secretPayload.secret_key) `
            -and -not [string]::IsNullOrWhiteSpace([string]$_.secretPayload.account_id)
    } | Select-Object -First 1
    if ($null -eq $credential) {
        $payloadKeys = @()
        if ($leases[0].secretPayload) {
            $payloadKeys = @($leases[0].secretPayload.PSObject.Properties.Name | Sort-Object)
        }
        Finish-Smoke "SKIPPED" "R2_CREDENTIAL_INCOMPLETE" @{
            leaseCount = $leases.Count
            payloadKeys = $payloadKeys
        }
    }
    return $credential
}

function ConvertTo-Hex {
    param([byte[]]$Bytes)
    return (($Bytes | ForEach-Object { $_.ToString("x2") }) -join "")
}

function Get-Sha256Hex {
    param([byte[]]$Bytes)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ConvertTo-Hex ($sha.ComputeHash($Bytes))
    } finally {
        $sha.Dispose()
    }
}

function Get-HmacSha256 {
    param(
        [byte[]]$Key,
        [string]$Data
    )
    $hmac = [System.Security.Cryptography.HMACSHA256]::new($Key)
    try {
        return $hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Data))
    } finally {
        $hmac.Dispose()
    }
}

function New-SigningKey {
    param(
        [string]$SecretKey,
        [string]$DateStamp
    )
    $kSecret = [System.Text.Encoding]::UTF8.GetBytes("AWS4$SecretKey")
    $kDate = Get-HmacSha256 $kSecret $DateStamp
    $kRegion = Get-HmacSha256 $kDate "auto"
    $kService = Get-HmacSha256 $kRegion "s3"
    return Get-HmacSha256 $kService "aws4_request"
}

function ConvertTo-S3Path {
    param(
        [string]$Bucket,
        [string]$Key
    )
    $encodedBucket = [System.Uri]::EscapeDataString($Bucket)
    $encodedKey = (($Key -split "/") | ForEach-Object { [System.Uri]::EscapeDataString($_) }) -join "/"
    return "/$encodedBucket/$encodedKey"
}

function Invoke-R2Request {
    param(
        [string]$Method,
        [string]$Endpoint,
        [string]$Bucket,
        [string]$Key,
        [string]$AccessKey,
        [string]$SecretKey,
        [byte[]]$Body = [byte[]]::new(0),
        [string]$ContentType = "application/octet-stream",
        [switch]$AllowNotFound
    )
    $endpointUri = [System.Uri]$Endpoint.TrimEnd("/")
    $canonicalUri = ConvertTo-S3Path -Bucket $Bucket -Key $Key
    $requestUri = "$($endpointUri.Scheme)://$($endpointUri.Host)$canonicalUri"
    $amzDate = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
    $dateStamp = $amzDate.Substring(0, 8)
    $payloadHash = Get-Sha256Hex $Body
    $hostHeader = $endpointUri.Host
    $canonicalHeaders = "host:$hostHeader`n" +
            "x-amz-content-sha256:$payloadHash`n" +
            "x-amz-date:$amzDate`n"
    $signedHeaders = "host;x-amz-content-sha256;x-amz-date"
    $canonicalRequest = "$Method`n$canonicalUri`n`n$canonicalHeaders`n$signedHeaders`n$payloadHash"
    $algorithm = "AWS4-HMAC-SHA256"
    $credentialScope = "$dateStamp/auto/s3/aws4_request"
    $stringToSign = "$algorithm`n$amzDate`n$credentialScope`n$(Get-Sha256Hex ([System.Text.Encoding]::UTF8.GetBytes($canonicalRequest)))"
    $signature = ConvertTo-Hex (Get-HmacSha256 (New-SigningKey -SecretKey $SecretKey -DateStamp $dateStamp) $stringToSign)
    $authorization = "$algorithm Credential=$AccessKey/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"
    $headers = @{
        Authorization = $authorization
        "x-amz-content-sha256" = $payloadHash
        "x-amz-date" = $amzDate
    }

    try {
        if ($Method -eq "PUT") {
            $response = Invoke-WebRequest -Method Put -Uri $requestUri -Headers $headers -Body $Body -ContentType $ContentType -TimeoutSec 30 -UseBasicParsing
        } elseif ($Method -eq "HEAD") {
            $response = Invoke-WebRequest -Method Head -Uri $requestUri -Headers $headers -TimeoutSec 30 -UseBasicParsing
        } elseif ($Method -eq "DELETE") {
            $response = Invoke-WebRequest -Method Delete -Uri $requestUri -Headers $headers -TimeoutSec 30 -UseBasicParsing
        } else {
            throw "Unsupported R2 method: $Method"
        }
        return @{
            ok = $true
            statusCode = [int]$response.StatusCode
            notFound = $false
        }
    } catch {
        $statusCode = $null
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }
        if ($AllowNotFound -and $statusCode -eq 404) {
            return @{
                ok = $true
                statusCode = 404
                notFound = $true
            }
        }
        return @{
            ok = $false
            statusCode = $statusCode
            notFound = $false
            error = $_.Exception.Message
        }
    }
}

try {
    if ($PreflightOnly) {
        Finish-R2LiveObjectPreflight
    }
    if ($NoWrite -and [string]::IsNullOrWhiteSpace($BucketName) -and -not $UseV1ParityBucketOverride) {
        Finish-Smoke "FAILED" "EXPLICIT_BUCKET_OVERRIDE_REQUIRED_FOR_NO_WRITE" @{
            hint = "pass -UseV1ParityBucketOverride or -BucketName ircs before an explicit V1 bucket smoke"
            preflightOnly = [bool]$PreflightOnly
            noWrite = [bool]$NoWrite
            confirmLiveR2Write = [bool]$ConfirmLiveR2Write
            willWriteR2Objects = $false
            willPatchDeployment = $false
        }
    }

    Test-KubectlAccess
    if ($StartCredentialPortForward) {
        $credentialPf = Start-PortForward "svc/ircs-credential-service" $CredentialPort 8080 $credentialPfOut $credentialPfErr
    }

    $configBucket = Get-ConfigMapValue "R2_BUCKET_NAME"
    $explicitBucketOverride = -not [string]::IsNullOrWhiteSpace($BucketName)
    $bucketName = if ($explicitBucketOverride) {
        $BucketName.Trim()
    } elseif ($UseV1ParityBucketOverride) {
        "ircs"
    } else {
        $configBucket.Trim()
    }
    $publicDomain = if ([string]::IsNullOrWhiteSpace($PublicDomain)) { Get-ConfigMapValue "R2_PUBLIC_DOMAIN" } else { $PublicDomain.Trim() }
    $credential = Get-R2CredentialLease
    $payloadKeys = @($credential.secretPayload.PSObject.Properties.Name | Sort-Object)
    $accountId = [string]$credential.secretPayload.account_id
    $accessKey = [string]$credential.secretPayload.access_key
    $secretKey = [string]$credential.secretPayload.secret_key
    $endpoint = "https://$accountId.r2.cloudflarestorage.com"

    Mark "R2 credential lease has required payload keys without printing secrets" 1 5
    Mark "R2 bucket and temporary object key are resolved" 2 5

    if ($NoWrite) {
        Finish-Smoke "SUCCESS" "R2_LIVE_OBJECT_NO_WRITE_OK" @{
            credentialId = $credential.id
            payloadKeys = $payloadKeys
            configMapBucket = $configBucket
            bucket = $bucketName
            bucketSource = if ($explicitBucketOverride) { "BucketName" } elseif ($UseV1ParityBucketOverride) { "UseV1ParityBucketOverride" } else { "ConfigMap" }
            publicDomain = $publicDomain
            endpointHost = ([System.Uri]$endpoint).Host
            objectKey = $objectKey
            preflightOnly = [bool]$PreflightOnly
            noWrite = [bool]$NoWrite
            confirmLiveR2Write = [bool]$ConfirmLiveR2Write
            confirmIgnoredByNoWrite = [bool]($NoWrite -and $ConfirmLiveR2Write)
            willWriteR2Objects = $false
            willPatchDeployment = $false
            requiresExplicitBucketOverride = $true
        }
    }

    if (-not $ConfirmLiveR2Write) {
        Finish-Smoke "SKIPPED" "LIVE_R2_WRITE_NOT_CONFIRMED" @{
            credentialId = $credential.id
            payloadKeys = $payloadKeys
            configMapBucket = $configBucket
            bucket = $bucketName
            bucketSource = if ($explicitBucketOverride) { "BucketName" } elseif ($UseV1ParityBucketOverride) { "UseV1ParityBucketOverride" } else { "ConfigMap" }
            publicDomain = $publicDomain
            endpointHost = ([System.Uri]$endpoint).Host
            objectKey = $objectKey
            preflightOnly = [bool]$PreflightOnly
            noWrite = [bool]$NoWrite
            confirmLiveR2Write = [bool]$ConfirmLiveR2Write
            willWriteR2Objects = $false
            willPatchDeployment = $false
            hint = "rerun with -ConfirmLiveR2Write and explicit -BucketName or -UseV1ParityBucketOverride to put/head/delete the temporary object"
        }
    }

    if ($bucketName -eq "ircs" -and -not $explicitBucketOverride -and -not $UseV1ParityBucketOverride) {
        Finish-Smoke "SKIPPED" "PRODUCTION_BUCKET_AS_DEV_CONFIG_BLOCKED" @{
            configMapBucket = $configBucket
            bucket = $bucketName
            objectKey = $objectKey
            hint = "dev ConfigMap must not use V1 production bucket as its standing bucket; pass -UseV1ParityBucketOverride or -BucketName ircs for a temporary parity smoke"
        }
    }

    if (-not $explicitBucketOverride -and -not $UseV1ParityBucketOverride) {
        Finish-Smoke "SKIPPED" "EXPLICIT_BUCKET_OVERRIDE_REQUIRED_FOR_R2_WRITE" @{
            configMapBucket = $configBucket
            bucket = $bucketName
            objectKey = $objectKey
            preflightOnly = [bool]$PreflightOnly
            noWrite = [bool]$NoWrite
            confirmLiveR2Write = [bool]$ConfirmLiveR2Write
            willWriteR2Objects = $false
            willPatchDeployment = $false
            hint = "pass -UseV1ParityBucketOverride or -BucketName ircs before an explicit V1 bucket smoke"
        }
    }

    $bodyText = "ircs 02807 r2 live smoke $stamp`n"
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($bodyText)
    $put = Invoke-R2Request -Method "PUT" -Endpoint $endpoint -Bucket $bucketName -Key $objectKey `
        -AccessKey $accessKey -SecretKey $secretKey -Body $bodyBytes -ContentType "text/plain"
    if (-not $put.ok) {
        Finish-Smoke "FAILED" "R2_PUT_FAILED" @{
            credentialId = $credential.id
            bucket = $bucketName
            objectKey = $objectKey
            statusCode = $put.statusCode
            error = $put.error
        }
    }
    Mark "R2 putObject succeeds for temporary smoke object" 3 5

    $head = Invoke-R2Request -Method "HEAD" -Endpoint $endpoint -Bucket $bucketName -Key $objectKey `
        -AccessKey $accessKey -SecretKey $secretKey
    if (-not $head.ok -or $head.notFound) {
        Finish-Smoke "FAILED" "R2_HEAD_AFTER_PUT_FAILED" @{
            credentialId = $credential.id
            bucket = $bucketName
            objectKey = $objectKey
            statusCode = $head.statusCode
            error = $head.error
        }
    }
    Mark "R2 headObject confirms object exists" 4 5

    $delete = Invoke-R2Request -Method "DELETE" -Endpoint $endpoint -Bucket $bucketName -Key $objectKey `
        -AccessKey $accessKey -SecretKey $secretKey
    if (-not $delete.ok) {
        Finish-Smoke "FAILED" "R2_DELETE_FAILED" @{
            credentialId = $credential.id
            bucket = $bucketName
            objectKey = $objectKey
            statusCode = $delete.statusCode
            error = $delete.error
        }
    }
    $missing = Invoke-R2Request -Method "HEAD" -Endpoint $endpoint -Bucket $bucketName -Key $objectKey `
        -AccessKey $accessKey -SecretKey $secretKey -AllowNotFound
    if (-not $missing.ok -or -not $missing.notFound) {
        Finish-Smoke "FAILED" "R2_OBJECT_NOT_CLEANED" @{
            credentialId = $credential.id
            bucket = $bucketName
            objectKey = $objectKey
            statusCode = $missing.statusCode
            error = $missing.error
        }
    }
    Mark "R2 deleteObject succeeds and headObject confirms cleanup" 5 5
    Finish-Smoke "SUCCESS" "R2_LIVE_OBJECT_SMOKE_OK" @{
        credentialId = $credential.id
        payloadKeys = $payloadKeys
        configMapBucket = $configBucket
        bucket = $bucketName
        bucketSource = if ($explicitBucketOverride) { "BucketName" } elseif ($UseV1ParityBucketOverride) { "UseV1ParityBucketOverride" } else { "ConfigMap" }
        publicDomain = $publicDomain
        publicUrl = "$($publicDomain.TrimEnd('/'))/$objectKey"
        endpointHost = ([System.Uri]$endpoint).Host
        objectKey = $objectKey
        putStatusCode = $put.statusCode
        headAfterPutStatusCode = $head.statusCode
        deleteStatusCode = $delete.statusCode
        headAfterDeleteStatusCode = $missing.statusCode
        cleanupConfirmed = [bool]$missing.notFound
        preflightOnly = [bool]$PreflightOnly
        noWrite = [bool]$NoWrite
        confirmLiveR2Write = [bool]$ConfirmLiveR2Write
        willWriteR2Objects = $true
        willPatchDeployment = $false
        requiresExplicitBucketOverride = $true
    }
} catch {
    Finish-Smoke "FAILED" "R2_LIVE_OBJECT_SMOKE_FAILED" @{
        message = $_.Exception.Message
        objectKey = $objectKey
    }
} finally {
    if ($credentialPf -and -not $credentialPf.HasExited) {
        Stop-Process -Id $credentialPf.Id -Force -ErrorAction SilentlyContinue
    }
}
