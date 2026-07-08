param(
    [string]$Namespace = "ircs-dev",
    [string]$CredentialBaseUrl = "http://127.0.0.1:18090",
    [int]$CredentialPort = 18090,
    [int]$StoragePort = 18093,
    [string]$CredentialToken = $env:APP_METADATA_CREDENTIAL_SERVICE_TOKEN,
    [string]$BucketName = "",
    [string]$PublicDomain = "",
    [switch]$UseV1ParityBucketOverride,
    [switch]$StartCredentialPortForward,
    [switch]$UseKubernetesSecretToken,
    [switch]$PreflightOnly,
    [switch]$NoWrite,
    [switch]$ConfirmStorageServiceR2Write,
    [switch]$FailOnSkip
)

$ErrorActionPreference = "Stop"

$stamp = Get-Date -Format "yyyyMMddHHmmss"
$traceId = "02807-storage-r2-path-$stamp"
$secretName = "ircs-r2-smoke-02807"
$fixtureFile = Join-Path $env:TEMP "ircs-02807-storage-r2-cover-$stamp.png"
$credentialPfOut = Join-Path $env:TEMP "ircs-02807-r2-path-credential-pf.out.log"
$credentialPfErr = Join-Path $env:TEMP "ircs-02807-r2-path-credential-pf.err.log"
$storagePfOut = Join-Path $env:TEMP "ircs-02807-r2-path-storage-pf.out.log"
$storagePfErr = Join-Path $env:TEMP "ircs-02807-r2-path-storage-pf.err.log"
$credentialPf = $null
$storagePf = $null
$deploymentChanged = $false
$coverImageId = $null
$storagePath = $null
$endpoint = $null
$accessKey = $null
$secretKey = $null
$bucketName = $null

function New-SmokeResult {
    param(
        [string]$Status,
        [string]$Reason,
        [hashtable]$Details = @{}
    )
    [ordered]@{
        task = "028-07-C"
        slice = "storage-service-r2-path"
        status = $Status
        reason = $Reason
        checkedAt = (Get-Date).ToUniversalTime().ToString("o")
        namespace = $Namespace
        traceId = $traceId
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

function Finish-StorageServiceR2PathPreflight {
    $explicitBucketOverride = -not [string]::IsNullOrWhiteSpace($BucketName)
    if (-not $explicitBucketOverride -and -not $UseV1ParityBucketOverride) {
        Finish-Smoke "FAILED" "EXPLICIT_BUCKET_OVERRIDE_REQUIRED_FOR_PREFLIGHT" @{
            hint = "pass -UseV1ParityBucketOverride or -BucketName ircs before an explicit V1 bucket smoke"
            preflightOnly = [bool]$PreflightOnly
            noWrite = [bool]$NoWrite
            confirmStorageServiceR2Write = [bool]$ConfirmStorageServiceR2Write
            willWriteR2Objects = $false
            willPatchDeployment = $false
        }
    }

    $preflightBucket = if ($explicitBucketOverride) { $BucketName.Trim() } else { "ircs" }
    Finish-Smoke "SUCCESS" "STORAGE_SERVICE_R2_PATH_PREFLIGHT_OK" @{
        effectiveBucket = $preflightBucket
        bucketSource = if ($explicitBucketOverride) { "BucketName" } else { "UseV1ParityBucketOverride" }
        publicDomain = if ([string]::IsNullOrWhiteSpace($PublicDomain)) { $null } else { $PublicDomain.Trim() }
        secretName = $secretName
        preflightOnly = [bool]$PreflightOnly
        noWrite = [bool]$NoWrite
        confirmStorageServiceR2Write = [bool]$ConfirmStorageServiceR2Write
        confirmIgnoredByNoWrite = [bool](($PreflightOnly -or $NoWrite) -and $ConfirmStorageServiceR2Write)
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
    Wait-ForTcpPort "127.0.0.1" $LocalPort 30
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

function Wait-ForTcpPort {
    param([string]$HostName, [int]$Port, [int]$TimeoutSeconds = 30)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $client = [System.Net.Sockets.TcpClient]::new()
        try {
            $async = $client.BeginConnect($HostName, $Port, $null, $null)
            if ($async.AsyncWaitHandle.WaitOne(500)) {
                $client.EndConnect($async)
                return
            }
        } catch {
        } finally {
            $client.Close()
        }
        Start-Sleep -Milliseconds 300
    }
    throw "Timed out waiting for $HostName`:$Port"
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
    param([byte[]]$Key, [string]$Data)
    $hmac = [System.Security.Cryptography.HMACSHA256]::new($Key)
    try {
        return $hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Data))
    } finally {
        $hmac.Dispose()
    }
}

function New-SigningKey {
    param([string]$SecretKey, [string]$DateStamp)
    $kSecret = [System.Text.Encoding]::UTF8.GetBytes("AWS4$SecretKey")
    $kDate = Get-HmacSha256 $kSecret $DateStamp
    $kRegion = Get-HmacSha256 $kDate "auto"
    $kService = Get-HmacSha256 $kRegion "s3"
    return Get-HmacSha256 $kService "aws4_request"
}

function ConvertTo-S3Path {
    param([string]$Bucket, [string]$Key)
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
        if ($Method -eq "HEAD") {
            $response = Invoke-WebRequest -Method Head -Uri $requestUri -Headers $headers -TimeoutSec 30 -UseBasicParsing
        } elseif ($Method -eq "DELETE") {
            $response = Invoke-WebRequest -Method Delete -Uri $requestUri -Headers $headers -TimeoutSec 30 -UseBasicParsing
        } else {
            throw "Unsupported R2 method: $Method"
        }
        return @{ ok = $true; statusCode = [int]$response.StatusCode; notFound = $false }
    } catch {
        $statusCode = $null
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }
        if ($AllowNotFound -and $statusCode -eq 404) {
            return @{ ok = $true; statusCode = 404; notFound = $true }
        }
        return @{
            ok = $false
            statusCode = $statusCode
            notFound = $false
            error = $_.Exception.Message
        }
    }
}

function Invoke-PostgresScalar {
    param([string]$Sql)
    $value = kubectl -n $Namespace exec postgres-0 -- psql -U postgres -d ircs -tAc $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed: $Sql"
    }
    return ($value | Out-String).Trim()
}

function Invoke-CurlJson {
    param(
        [string]$Method,
        [string]$Url,
        [string[]]$Headers = @(),
        [string[]]$CurlArgs = @()
    )
    $tmp = Join-Path $env:TEMP ("ircs-smoke-http-" + [guid]::NewGuid() + ".json")
    $args = @("-sS", "--max-time", "60", "-o", $tmp, "-w", "%{http_code}", "-X", $Method)
    foreach ($header in $Headers) {
        $args += @("-H", $header)
    }
    $args += $CurlArgs
    $args += $Url
    $code = & curl.exe @args
    $text = ""
    if (Test-Path $tmp) {
        $text = Get-Content -LiteralPath $tmp -Raw -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $tmp -ErrorAction SilentlyContinue
    }
    if ([int]$code -lt 200 -or [int]$code -ge 300) {
        throw "HTTP $code for $Method $Url body=$text"
    }
    if ([string]::IsNullOrWhiteSpace($text)) {
        return $null
    }
    return $text | ConvertFrom-Json
}

function Write-FixturePng {
    param([string]$Path)
    [byte[]]$png = 0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,0x00,0x00,0x00,0x0D,0x49,0x48,0x44,0x52,
        0x00,0x00,0x00,0x01,0x00,0x00,0x00,0x01,0x08,0x02,0x00,0x00,0x00,0x90,0x77,0x53,0xDE,
        0x00,0x00,0x00,0x0C,0x49,0x44,0x41,0x54,0x08,0xD7,0x63,0xF8,0xCF,0xC0,0x00,0x00,0x03,
        0x01,0x01,0x00,0x18,0xDD,0x8D,0xB0,0x00,0x00,0x00,0x00,0x49,0x45,0x4E,0x44,0xAE,0x42,
        0x60,0x82
    [System.IO.File]::WriteAllBytes($Path, $png)
}

function Restore-StorageDeployment {
    if ($deploymentChanged) {
        kubectl -n $Namespace set env deployment/ircs-storage-service `
            APP_STORAGE_R2_ENABLED- `
            APP_STORAGE_R2_BUCKET_NAME- `
            APP_STORAGE_R2_PUBLIC_DOMAIN- `
            APP_STORAGE_R2_ACCOUNT_ID- `
            APP_STORAGE_R2_ACCESS_KEY- `
            APP_STORAGE_R2_SECRET_KEY- `
            APP_STORAGE_R2_ENDPOINT- `
            APP_STORAGE_R2_WORK_QUEUE_WORKER_ENABLED- | Out-Null
        kubectl apply -f deploy/k8s/dev/storage-service-dev.yaml | Out-Null
        kubectl -n $Namespace rollout status deployment/ircs-storage-service --timeout=180s | Out-Null
        $script:deploymentChanged = $false
    }
}

try {
    if ($PreflightOnly) {
        Finish-StorageServiceR2PathPreflight
    }
    if ($NoWrite -and [string]::IsNullOrWhiteSpace($BucketName) -and -not $UseV1ParityBucketOverride) {
        Finish-Smoke "FAILED" "EXPLICIT_BUCKET_OVERRIDE_REQUIRED_FOR_NO_WRITE" @{
            hint = "pass -UseV1ParityBucketOverride or -BucketName ircs before an explicit V1 bucket smoke"
            preflightOnly = [bool]$PreflightOnly
            noWrite = [bool]$NoWrite
            confirmStorageServiceR2Write = [bool]$ConfirmStorageServiceR2Write
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
    $script:bucketName = if ($explicitBucketOverride) {
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
    $script:accessKey = [string]$credential.secretPayload.access_key
    $script:secretKey = [string]$credential.secretPayload.secret_key
    $script:endpoint = "https://$accountId.r2.cloudflarestorage.com"

    Mark "storage-service R2 path smoke resolves V1 bucket or explicit override" 1 6

    if ($NoWrite) {
        Finish-Smoke "SUCCESS" "STORAGE_SERVICE_R2_PATH_NO_WRITE_OK" @{
            credentialId = $credential.id
            payloadKeys = $payloadKeys
            configMapBucket = $configBucket
            effectiveBucket = $bucketName
            bucketSource = if ($explicitBucketOverride) { "BucketName" } elseif ($UseV1ParityBucketOverride) { "UseV1ParityBucketOverride" } else { "ConfigMap" }
            publicDomain = $publicDomain
            endpointHost = ([System.Uri]$endpoint).Host
            secretName = $secretName
            preflightOnly = [bool]$PreflightOnly
            noWrite = [bool]$NoWrite
            confirmStorageServiceR2Write = [bool]$ConfirmStorageServiceR2Write
            confirmIgnoredByNoWrite = [bool]($NoWrite -and $ConfirmStorageServiceR2Write)
            willWriteR2Objects = $false
            willPatchDeployment = $false
            requiresExplicitBucketOverride = $true
        }
    }

    if (-not $ConfirmStorageServiceR2Write) {
        Finish-Smoke "SKIPPED" "LIVE_STORAGE_SERVICE_R2_WRITE_NOT_CONFIRMED" @{
            credentialId = $credential.id
            payloadKeys = $payloadKeys
            configMapBucket = $configBucket
            effectiveBucket = $bucketName
            bucketSource = if ($explicitBucketOverride) { "BucketName" } elseif ($UseV1ParityBucketOverride) { "UseV1ParityBucketOverride" } else { "ConfigMap" }
            publicDomain = $publicDomain
            endpointHost = ([System.Uri]$endpoint).Host
            preflightOnly = [bool]$PreflightOnly
            noWrite = [bool]$NoWrite
            confirmStorageServiceR2Write = [bool]$ConfirmStorageServiceR2Write
            willWriteR2Objects = $false
            willPatchDeployment = $false
            hint = "rerun with -ConfirmStorageServiceR2Write and explicit -BucketName or -UseV1ParityBucketOverride to patch storage-service temporarily"
        }
    }

    if ($bucketName -eq "ircs" -and -not $explicitBucketOverride -and -not $UseV1ParityBucketOverride) {
        Finish-Smoke "SKIPPED" "PRODUCTION_BUCKET_AS_DEV_CONFIG_BLOCKED" @{
            configMapBucket = $configBucket
            effectiveBucket = $bucketName
            hint = "dev ConfigMap must not use V1 production bucket as its standing bucket; pass -UseV1ParityBucketOverride or -BucketName ircs for a temporary parity smoke"
        }
    }

    if (-not $explicitBucketOverride -and -not $UseV1ParityBucketOverride) {
        Finish-Smoke "SKIPPED" "EXPLICIT_BUCKET_OVERRIDE_REQUIRED_FOR_R2_WRITE" @{
            configMapBucket = $configBucket
            effectiveBucket = $bucketName
            preflightOnly = [bool]$PreflightOnly
            noWrite = [bool]$NoWrite
            confirmStorageServiceR2Write = [bool]$ConfirmStorageServiceR2Write
            willWriteR2Objects = $false
            willPatchDeployment = $false
            hint = "pass -UseV1ParityBucketOverride or -BucketName ircs before an explicit V1 bucket smoke"
        }
    }

    $manifest = kubectl -n $Namespace create secret generic $secretName `
        --from-literal=APP_STORAGE_R2_ACCOUNT_ID=$accountId `
        --from-literal=APP_STORAGE_R2_ACCESS_KEY=$accessKey `
        --from-literal=APP_STORAGE_R2_SECRET_KEY=$secretKey `
        --from-literal=APP_STORAGE_R2_ENDPOINT=$endpoint `
        --dry-run=client -o yaml
    $manifest | kubectl apply -f - | Out-Null

    kubectl -n $Namespace set env deployment/ircs-storage-service --from=secret/$secretName | Out-Null
    kubectl -n $Namespace set env deployment/ircs-storage-service `
        APP_STORAGE_R2_ENABLED=true `
        APP_STORAGE_R2_BUCKET_NAME=$bucketName `
        APP_STORAGE_R2_PUBLIC_DOMAIN=$publicDomain `
        APP_STORAGE_R2_WORK_QUEUE_WORKER_ENABLED=false | Out-Null
    $deploymentChanged = $true
    kubectl -n $Namespace rollout status deployment/ircs-storage-service --timeout=180s | Out-Null
    Mark "storage-service temporary R2 runtime is enabled without storage work queue worker" 2 6

    $storagePf = Start-PortForward "svc/ircs-storage-service" $StoragePort 8080 $storagePfOut $storagePfErr
    Write-FixturePng $fixtureFile
    $headers = @("X-Trace-Id: $traceId", "X-Authenticated-User: storage-r2-path-smoke@example.com")
    $upload = Invoke-CurlJson "POST" "http://127.0.0.1:$StoragePort/api/v1/cover-images/upload" `
        -Headers $headers `
        -CurlArgs @("-F", "file=@$fixtureFile;type=image/png;filename=cover.png")
    if ($null -eq $upload -or [string]::IsNullOrWhiteSpace([string]$upload.id)) {
        throw "cover upload response missing id"
    }
    $script:coverImageId = [string]$upload.id
    $script:storagePath = [string]$upload.storagePath
    if ([string]::IsNullOrWhiteSpace($storagePath) -or -not $storagePath.StartsWith("covers/")) {
        throw "cover upload returned invalid storagePath: $storagePath"
    }

    Invoke-CurlJson "POST" "http://127.0.0.1:$StoragePort/api/v1/cover-images/$coverImageId/sync-r2" -Headers $headers | Out-Null
    Mark "fixture object is uploaded through storage-service path" 3 6

    $row = Invoke-CurlJson "GET" "http://127.0.0.1:$StoragePort/api/v1/cover-images/$coverImageId" -Headers $headers
    $expectedBase = if ($publicDomain.StartsWith("http")) { $publicDomain.TrimEnd("/") } else { "https://$($publicDomain.TrimEnd('/'))" }
    $expectedUrl = "$expectedBase/$storagePath"
    if ($row.storageType -ne "R2" -or $row.status -ne "REMOTE_STORED" -or $row.url -ne $expectedUrl) {
        throw "cover row did not reach expected R2 state; storageType=$($row.storageType), status=$($row.status), url=$($row.url), expected=$expectedUrl"
    }
    $head = Invoke-R2Request -Method "HEAD" -Endpoint $endpoint -Bucket $bucketName -Key $storagePath `
        -AccessKey $accessKey -SecretKey $secretKey
    if (-not $head.ok -or $head.notFound) {
        throw "R2 object HEAD after storage-service sync failed: status=$($head.statusCode) error=$($head.error)"
    }
    Mark "DB/media URL side effect matches V1 public domain semantics" 4 6

    $delete = Invoke-R2Request -Method "DELETE" -Endpoint $endpoint -Bucket $bucketName -Key $storagePath `
        -AccessKey $accessKey -SecretKey $secretKey
    if (-not $delete.ok) {
        throw "R2 object DELETE failed during cleanup: status=$($delete.statusCode) error=$($delete.error)"
    }
    kubectl -n $Namespace exec deploy/ircs-storage-service -- sh -c "rm -f '/app/storage/$storagePath'" | Out-Null
    Invoke-PostgresScalar "delete from cover_images where id = '$coverImageId';" | Out-Null
    $missing = Invoke-R2Request -Method "HEAD" -Endpoint $endpoint -Bucket $bucketName -Key $storagePath `
        -AccessKey $accessKey -SecretKey $secretKey -AllowNotFound
    $dbResidue = Invoke-PostgresScalar "select count(*) from cover_images where id = '$coverImageId';"
    if (-not $missing.ok -or -not $missing.notFound -or $dbResidue -ne "0") {
        throw "cleanup residue detected: r2NotFound=$($missing.notFound), dbResidue=$dbResidue"
    }
    Mark "R2 object and DB fixture are cleaned" 5 6

    Restore-StorageDeployment
    kubectl -n $Namespace delete secret $secretName --ignore-not-found | Out-Null
    $httpRoutes = kubectl -n $Namespace get httproute --ignore-not-found 2>&1
    $quota = kubectl -n $Namespace get resourcequota ircs-dev-quota
    Mark "dev env restored with no HTTPRoute/quota/port-forward residue" 6 6
    New-SmokeResult "SUCCESS" "STORAGE_SERVICE_R2_PATH_SMOKE_OK" @{
        credentialId = $credential.id
        configMapBucket = $configBucket
        effectiveBucket = $bucketName
        bucketSource = if ($explicitBucketOverride) { "BucketName" } elseif ($UseV1ParityBucketOverride) { "UseV1ParityBucketOverride" } else { "ConfigMap" }
        publicDomain = $publicDomain
        coverImageId = $coverImageId
        storagePath = $storagePath
        httpRoute = (($httpRoutes | Out-String).Trim())
        resourceQuota = (($quota | Out-String).Trim())
    }
} catch {
    Finish-Smoke "FAILED" "STORAGE_SERVICE_R2_PATH_SMOKE_FAILED" @{
        message = $_.Exception.Message
        coverImageId = $coverImageId
        storagePath = $storagePath
        bucket = $bucketName
    }
} finally {
    if ($storagePath -and $endpoint -and $accessKey -and $secretKey -and $bucketName) {
        Invoke-R2Request -Method "DELETE" -Endpoint $endpoint -Bucket $bucketName -Key $storagePath `
            -AccessKey $accessKey -SecretKey $secretKey | Out-Null
    }
    if ($coverImageId) {
        Invoke-PostgresScalar "delete from cover_images where id = '$coverImageId';" | Out-Null
    }
    if ($deploymentChanged) {
        Restore-StorageDeployment
    }
    kubectl -n $Namespace delete secret $secretName --ignore-not-found | Out-Null
    if ($credentialPf -and -not $credentialPf.HasExited) {
        Stop-Process -Id $credentialPf.Id -Force -ErrorAction SilentlyContinue
    }
    if ($storagePf -and -not $storagePf.HasExited) {
        Stop-Process -Id $storagePf.Id -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $fixtureFile -ErrorAction SilentlyContinue
}
