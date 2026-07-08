param(
    [string]$Namespace = "ircs-dev",
    [string]$RabbitBaseUrl = "http://127.0.0.1:19076",
    [int]$RabbitPort = 19076,
    [string]$CredentialBaseUrl = "http://127.0.0.1:18089",
    [int]$CredentialPort = 18089,
    [string]$TmdbBaseUrl = $(if ($env:APP_METADATA_TMDB_BASE_URL) { $env:APP_METADATA_TMDB_BASE_URL } else { "https://api.themoviedb.org/3" }),
    [string]$ElasticsearchBaseUrl = "http://127.0.0.1:39201",
    [int]$ElasticsearchPort = 39201,
    [int]$WaitAttempts = 45,
    [int]$WaitSeconds = 2,
    [string]$Query = "The Matrix",
    [string]$Year = "1999",
    [string]$ExpectedProviderTmdbId = "603",
    [switch]$SkipPreflightTmdbDiagnostic,
    [switch]$EnableTmdbProviderForSmoke,
    [switch]$PurgeSmokeQueuesOnCleanup,
    [switch]$FailOnSkip
)

$ErrorActionPreference = "Stop"

$stamp = Get-Date -Format "yyyyMMddHHmmss"
$rawVideoId = [guid]::NewGuid().ToString()
$sourceHash = "codex02806tmdb$stamp"
$dataHash = "codex02806tmdbdata$stamp"
$rabbitPfOut = Join-Path $env:TEMP "ircs-02806-live-rabbit-pf.out.log"
$rabbitPfErr = Join-Path $env:TEMP "ircs-02806-live-rabbit-pf.err.log"
$credentialPfOut = Join-Path $env:TEMP "ircs-02806-live-credential-pf.out.log"
$credentialPfErr = Join-Path $env:TEMP "ircs-02806-live-credential-pf.err.log"
$esPfOut = Join-Path $env:TEMP "ircs-02806-live-es-pf.out.log"
$esPfErr = Join-Path $env:TEMP "ircs-02806-live-es-pf.err.log"
$rabbitPf = $null
$credentialPf = $null
$esPf = $null
$metadataEnvCaptured = $false
$metadataEnvPatched = $false
$originalMetadataEnv = @{}
$script:expectedTmdbId = $null

function New-SmokeResult {
    param(
        [string]$Status,
        [string]$Reason,
        [hashtable]$Details = @{}
    )
    [ordered]@{
        task = "028-06"
        slice = "metadata-provider-live-queue"
        provider = "TMDB"
        status = $Status
        reason = $Reason
        checkedAt = (Get-Date).ToUniversalTime().ToString("o")
        namespace = $Namespace
        rawVideoId = $rawVideoId
        credentialBaseUrl = $CredentialBaseUrl
        tmdbBaseUrl = $TmdbBaseUrl
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

function Sql-Literal {
    param([AllowNull()][string]$Value)
    if ($null -eq $Value) {
        return "null"
    }
    return "'" + $Value.Replace("'", "''") + "'"
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

function Invoke-CurlJson {
    param(
        [string]$Url,
        [string[]]$Headers = @(),
        [int]$TimeoutSeconds = 20
    )
    $tmp = Join-Path $env:TEMP ("ircs-02806-live-response-" + [guid]::NewGuid() + ".json")
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

function Get-TmdbCredentialLease {
    $token = Get-CredentialServiceSecretToken
    $headers = @("X-IRCS-INTERNAL-TOKEN: $token")
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

    $leases = @(Convert-JsonBody -Body $leaseResponse.body -FailureReason "CREDENTIAL_LEASE_RESPONSE_PARSE_ERROR")
    if ($leases.Count -eq 0) {
        Finish-Smoke "SKIPPED" "NO_ENABLED_TMDB_CREDENTIALS" @{
            leaseHttpStatus = $leaseResponse.code
        }
    }

    $credential = $leases | Where-Object {
        $null -ne $_.secretPayload -and -not [string]::IsNullOrWhiteSpace([string]$_.secretPayload.api_key)
    } | Select-Object -First 1
    if ($null -eq $credential) {
        Finish-Smoke "SKIPPED" "TMDB_CREDENTIAL_WITHOUT_API_KEY" @{
            leaseHttpStatus = $leaseResponse.code
            leaseCount = $leases.Count
        }
    }
    return $credential
}

function Classify-TmdbHttpFailure {
    param([int]$StatusCode)
    if ($StatusCode -eq 401) {
        return "TMDB_CREDENTIAL_REJECTED"
    }
    if ($StatusCode -eq 429) {
        return "TMDB_RATE_LIMITED"
    }
    if ($StatusCode -ge 500) {
        return "TMDB_UPSTREAM_UNAVAILABLE"
    }
    return "TMDB_HTTP_$StatusCode"
}

function Test-TmdbUpstream {
    param($Credential)
    $apiKey = [string]$Credential.secretPayload.api_key
    $encodedQuery = [System.Uri]::EscapeDataString($Query)
    $encodedYear = [System.Uri]::EscapeDataString($Year)
    $searchUrl = "$($TmdbBaseUrl.TrimEnd('/'))/search/movie?api_key=$([System.Uri]::EscapeDataString($apiKey))&query=$encodedQuery&language=zh-CN&include_adult=false&page=1&year=$encodedYear"
    $searchResponse = Invoke-CurlJson -Url $searchUrl
    if (-not $searchResponse.ok) {
        Finish-Smoke "FAILED" "TMDB_NETWORK_ERROR" @{
            curlExitCode = $searchResponse.exitCode
            error = $searchResponse.error
            credentialId = $Credential.id
        }
    }
    if ($searchResponse.code -lt 200 -or $searchResponse.code -ge 300) {
        Finish-Smoke "FAILED" (Classify-TmdbHttpFailure $searchResponse.code) @{
            httpStatus = $searchResponse.code
            credentialId = $Credential.id
            bodyPreview = if ($searchResponse.body.Length -gt 500) { $searchResponse.body.Substring(0, 500) } else { $searchResponse.body }
        }
    }

    $searchBody = Convert-JsonBody -Body $searchResponse.body -FailureReason "TMDB_SEARCH_RESPONSE_PARSE_ERROR"
    if ([int]$searchBody.total_results -le 0 -or $null -eq $searchBody.results -or $searchBody.results.Count -eq 0) {
        Finish-Smoke "FAILED" "TMDB_SEARCH_RETURNED_NO_RESULTS" @{
            query = $Query
            year = $Year
            credentialId = $Credential.id
        }
    }

    $first = $searchBody.results[0]
    $tmdbId = [string]$first.id
    $detailUrl = "$($TmdbBaseUrl.TrimEnd('/'))/movie/$tmdbId?api_key=$([System.Uri]::EscapeDataString($apiKey))&language=zh-CN&append_to_response=credits,external_ids"
    $detailResponse = Invoke-CurlJson -Url $detailUrl
    if (-not $detailResponse.ok) {
        Finish-Smoke "FAILED" "TMDB_DETAIL_NETWORK_ERROR" @{
            curlExitCode = $detailResponse.exitCode
            error = $detailResponse.error
            credentialId = $Credential.id
            tmdbId = $tmdbId
        }
    }
    if ($detailResponse.code -lt 200 -or $detailResponse.code -ge 300) {
        Finish-Smoke "FAILED" (Classify-TmdbHttpFailure $detailResponse.code) @{
            httpStatus = $detailResponse.code
            credentialId = $Credential.id
            tmdbId = $tmdbId
            bodyPreview = if ($detailResponse.body.Length -gt 500) { $detailResponse.body.Substring(0, 500) } else { $detailResponse.body }
        }
    }
    $detailBody = Convert-JsonBody -Body $detailResponse.body -FailureReason "TMDB_DETAIL_RESPONSE_PARSE_ERROR"
    if ([string]$detailBody.id -ne $tmdbId) {
        Finish-Smoke "FAILED" "TMDB_DETAIL_ID_MISMATCH" @{
            searchResultId = $tmdbId
            detailId = [string]$detailBody.id
        }
    }
    return @{
        tmdbId = $tmdbId
        title = [string]$first.title
        totalResults = [int]$searchBody.total_results
        credentialId = $Credential.id
    }
}

function Exec-Sql {
    param([string]$Sql)
    $Sql | kubectl -n $Namespace exec -i postgres-0 -- psql -v ON_ERROR_STOP=1 -U postgres -d ircs
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed with exit code $LASTEXITCODE"
    }
}

function Invoke-SqlScalar {
    param([string]$Sql)
    $rows = $Sql | kubectl -n $Namespace exec -i postgres-0 -- psql -v ON_ERROR_STOP=1 -U postgres -d ircs -t -A
    if ($LASTEXITCODE -ne 0) {
        throw "psql scalar failed with exit code $LASTEXITCODE"
    }
    $value = $rows | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Last 1
    if ($null -eq $value) {
        return $null
    }
    return $value.Trim()
}

function Get-RabbitPassword {
    return Get-SecretValue "RABBITMQ_PASSWORD"
}

function Invoke-RabbitPublish {
    param(
        [string]$Exchange,
        [string]$RoutingKey,
        [string]$Payload,
        [string]$ContentType = "application/json"
    )
    $rabbitPassword = Get-RabbitPassword
    $body = @{
        properties = @{ content_type = $ContentType }
        routing_key = $RoutingKey
        payload = $Payload
        payload_encoding = "string"
    } | ConvertTo-Json -Depth 8 -Compress
    $requestFile = Join-Path $env:TEMP ("ircs-02806-live-rabbit-publish-" + [guid]::NewGuid() + ".json")
    $responseFile = Join-Path $env:TEMP ("ircs-02806-live-rabbit-response-" + [guid]::NewGuid() + ".json")
    [System.IO.File]::WriteAllText($requestFile, $body, [System.Text.UTF8Encoding]::new($false))
    try {
        $code = & curl.exe -sS -u "admin:$rabbitPassword" `
            -H "Content-Type: application/json" `
            -o $responseFile `
            -w "%{http_code}" `
            --data-binary "@$requestFile" `
            "$RabbitBaseUrl/api/exchanges/%2F/$Exchange/publish"
        $text = if (Test-Path $responseFile) { Get-Content -LiteralPath $responseFile -Raw } else { "" }
        if ([int]$code -lt 200 -or [int]$code -ge 300) {
            throw "RabbitMQ publish HTTP $code body=$text"
        }
        $response = $text | ConvertFrom-Json
        if (-not $response.routed) {
            throw "RabbitMQ publish was not routed: $text"
        }
    } finally {
        Remove-Item -LiteralPath $requestFile, $responseFile -ErrorAction SilentlyContinue
    }
}

function Get-QueueParts {
    param([string]$Name)
    $lines = kubectl -n $Namespace exec rabbitmq-0 -- rabbitmqctl list_queues name messages consumers
    if ($LASTEXITCODE -ne 0) {
        throw "rabbitmqctl list_queues failed"
    }
    $line = $lines | Where-Object { $_ -match "^$([regex]::Escape($Name))\s" } | Select-Object -First 1
    if (-not $line) {
        throw "queue not found: $Name"
    }
    return $line -split "\s+"
}

function Get-QueueState {
    param([string]$Name)
    $parts = Get-QueueParts $Name
    return @{
        name = $Name
        messages = [int]$parts[1]
        consumers = [int]$parts[2]
    }
}

function Assert-QueueEmpty {
    param([string]$Name)
    $state = Get-QueueState $Name
    if ($state.messages -ne 0) {
        throw "queue $Name has $($state.messages) messages"
    }
}

function Wait-QueueEmpty {
    param([string]$Name)
    $last = $null
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        Start-Sleep -Seconds $WaitSeconds
        $state = Get-QueueState $Name
        $last = $state
        if ($state.messages -eq 0) {
            return
        }
    }
    throw "queue $Name did not drain, last messages=$($last.messages)"
}

function Assert-SmokeQueuesCleanBefore {
    $dirty = @()
    foreach ($queue in @(
        "q.fetch.metadata.tmdb",
        "q.fetch.metadata.tmdb.dlq",
        "q.enrich.metadata.result",
        "q.enrich.metadata.result.dlq"
    )) {
        $state = Get-QueueState $queue
        if ($state.messages -ne 0) {
            $dirty += "$queue=$($state.messages)"
        }
    }
    if ($dirty.Count -gt 0) {
        Finish-Smoke "SKIPPED" "QUEUE_NOT_CLEAN" @{
            queues = $dirty
        }
    }
}

function Assert-ConsumersReady {
    $lastMissing = @()
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        $missing = @()
        foreach ($queue in @(
            "q.fetch.metadata.tmdb",
            "q.enrich.metadata.result"
        )) {
            $state = Get-QueueState $queue
            if ($state.consumers -lt 1) {
                $missing += "$queue consumers=$($state.consumers)"
            }
        }
        if ($missing.Count -eq 0) {
            return
        }
        $lastMissing = $missing
        Start-Sleep -Seconds $WaitSeconds
    }
    Finish-Smoke "SKIPPED" "CONSUMER_UNAVAILABLE" @{
        queues = $lastMissing
    }
}

function Assert-NoHttpRoute {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = kubectl -n $Namespace get httproute --no-headers 2>&1
    $exit = $LASTEXITCODE
    $ErrorActionPreference = $previous
    $text = ($output | Out-String).Trim()
    if ($exit -ne 0 -and $text -notmatch "No resources found") {
        Finish-Smoke "FAILED" "HTTPROUTE_LIST_FAILED" @{
            exitCode = $exit
            output = $text
        }
    }
    if ($exit -eq 0 -and $text -and $text -notmatch "No resources found") {
        Finish-Smoke "FAILED" "HTTPROUTE_PRESENT" @{
            output = $text
        }
    }
}

function Get-DeploymentEnvMap {
    param([string]$Deployment)
    $json = kubectl -n $Namespace get deploy $Deployment -o json | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) {
        Finish-Smoke "SKIPPED" "DEPLOYMENT_UNAVAILABLE" @{
            deployment = $Deployment
        }
    }
    $map = @{}
    foreach ($entry in @($json.spec.template.spec.containers[0].env)) {
        if ($null -ne $entry.value) {
            $map[$entry.name] = [string]$entry.value
        } else {
            $map[$entry.name] = $null
        }
    }
    return $map
}

function Capture-MetadataEnv {
    $map = Get-DeploymentEnvMap "ircs-metadata-worker"
    foreach ($name in @("APP_METADATA_TMDB_ENABLED", "APP_METADATA_TMDB_WORKER_ENABLED", "APP_METADATA_LISTENER_ENABLED")) {
        if ($map.ContainsKey($name)) {
            $script:originalMetadataEnv[$name] = $map[$name]
        } else {
            $script:originalMetadataEnv[$name] = $null
        }
    }
    $script:metadataEnvCaptured = $true
}

function Enable-MetadataTmdbForSmoke {
    Capture-MetadataEnv
    kubectl -n $Namespace set env deployment/ircs-metadata-worker `
        APP_METADATA_TMDB_ENABLED=true `
        APP_METADATA_TMDB_WORKER_ENABLED=true `
        APP_METADATA_LISTENER_ENABLED=true | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Finish-Smoke "SKIPPED" "METADATA_WORKER_ENV_PATCH_FAILED" @{}
    }
    $script:metadataEnvPatched = $true
    kubectl -n $Namespace rollout status deployment/ircs-metadata-worker --timeout=240s
    if ($LASTEXITCODE -ne 0) {
        Finish-Smoke "SKIPPED" "METADATA_WORKER_ROLLOUT_FAILED" @{}
    }
}

function Restore-MetadataEnv {
    if (-not $script:metadataEnvCaptured) {
        return
    }
    $args = @("-n", $Namespace, "set", "env", "deployment/ircs-metadata-worker")
    foreach ($name in @("APP_METADATA_TMDB_ENABLED", "APP_METADATA_TMDB_WORKER_ENABLED", "APP_METADATA_LISTENER_ENABLED")) {
        $value = $script:originalMetadataEnv[$name]
        if ($null -eq $value) {
            $args += "$name-"
        } else {
            $args += "$name=$value"
        }
    }
    & kubectl @args | Out-Null
    if ($LASTEXITCODE -eq 0) {
        kubectl -n $Namespace rollout status deployment/ircs-metadata-worker --timeout=240s | Out-Null
    }
}

function Ensure-TmdbProviderEnabled {
    $map = Get-DeploymentEnvMap "ircs-metadata-worker"
    $providerEnabled = $map["APP_METADATA_TMDB_ENABLED"]
    $workerEnabled = $map["APP_METADATA_TMDB_WORKER_ENABLED"]
    $listenerEnabled = $map["APP_METADATA_LISTENER_ENABLED"]
    if ($providerEnabled -eq "true" -and $workerEnabled -eq "true" -and $listenerEnabled -eq "true") {
        return
    }
    if (-not $EnableTmdbProviderForSmoke) {
        Finish-Smoke "SKIPPED" "TMDB_PROVIDER_DISABLED" @{
            appMetadataTmdbEnabled = $providerEnabled
            appMetadataTmdbWorkerEnabled = $workerEnabled
            appMetadataListenerEnabled = $listenerEnabled
            hint = "rerun with -EnableTmdbProviderForSmoke after confirming dev credential/upstream safety"
        }
    }
    Enable-MetadataTmdbForSmoke
}

function Setup-SmokeData {
    $titleSql = Sql-Literal $Query
    $sourceVidSql = Sql-Literal "02806-tmdb-$stamp"
    $sourceHashSql = Sql-Literal $sourceHash
    $dataHashSql = Sql-Literal $dataHash
    $yearSql = Sql-Literal $Year
    $rawMetadataSql = Sql-Literal "{}"
    $sql = @"
insert into raw_videos (
    id, created_at, updated_at, version, source_vid, source_hash, data_hash, title,
    year, locked_fields, enrichment_status, enrichment_retry_count,
    normalization_status, normalization_retry_count, aggregation_status, raw_metadata
) values (
    '$rawVideoId', now(), now(), 0, $sourceVidSql, $sourceHashSql, $dataHashSql, $titleSql,
    $yearSql, '[]'::jsonb, 'PENDING', 0,
    'READY', 0, 'BOUND', $rawMetadataSql::jsonb
);
"@
    Exec-Sql $sql | Out-Null
}

function Cleanup-SmokeData {
    $sql = @"
delete from raw_video_unified_video where raw_video_id = '$rawVideoId';
delete from video_actors where video_id = '$rawVideoId';
delete from video_directors where video_id = '$rawVideoId';
delete from video_raw_genres where video_id = '$rawVideoId';
delete from video_raw_languages where video_id = '$rawVideoId';
delete from video_raw_areas where video_id = '$rawVideoId';
delete from playlists where video_id = '$rawVideoId';
delete from raw_videos where id = '$rawVideoId' or source_hash = '$sourceHash';
"@
    Exec-Sql $sql | Out-Null
}

function Assert-NoDbResidue {
    $residue = Invoke-SqlScalar @"
select
  (select count(*) from raw_videos where id = '$rawVideoId' or source_hash = '$sourceHash')::text
  || '|' ||
  (select count(*) from raw_video_unified_video where raw_video_id = '$rawVideoId')::text
  || '|' ||
  (select count(*) from video_actors where video_id = '$rawVideoId')::text
  || '|' ||
  (select count(*) from video_directors where video_id = '$rawVideoId')::text
  || '|' ||
  (select count(*) from video_raw_genres where video_id = '$rawVideoId')::text
  || '|' ||
  (select count(*) from video_raw_languages where video_id = '$rawVideoId')::text
  || '|' ||
  (select count(*) from video_raw_areas where video_id = '$rawVideoId')::text;
"@
    if ($residue -ne "0|0|0|0|0|0|0") {
        throw "smoke cleanup left DB residue: $residue"
    }
}

function Get-ElasticPassword {
    return Get-SecretValue "ELASTICSEARCH_PASSWORD"
}

function Get-EsDocument {
    param(
        [string]$Index,
        [string]$Id
    )
    $elasticPassword = Get-ElasticPassword
    $responseFile = Join-Path $env:TEMP ("ircs-02806-live-es-doc-" + [guid]::NewGuid() + ".json")
    try {
        $code = & curl.exe -sS -u "elastic:$elasticPassword" `
            -o $responseFile `
            -w "%{http_code}" `
            "$ElasticsearchBaseUrl/$Index/_doc/$Id"
        $text = if (Test-Path $responseFile) { Get-Content -LiteralPath $responseFile -Raw } else { "" }
        if ([int]$code -eq 404) {
            return $null
        }
        if ([int]$code -lt 200 -or [int]$code -ge 300) {
            throw "Elasticsearch get HTTP $code body=$text"
        }
        return $text | ConvertFrom-Json
    } finally {
        Remove-Item -LiteralPath $responseFile -ErrorAction SilentlyContinue
    }
}

function Remove-EsDocument {
    param(
        [string]$Index,
        [string]$Id
    )
    $elasticPassword = Get-ElasticPassword
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & curl.exe -sS -u "elastic:$elasticPassword" -X DELETE "$ElasticsearchBaseUrl/$Index/_doc/$Id" | Out-Null
    $ErrorActionPreference = $previous
}

function ConvertTo-StringArray {
    param($Value)
    if ($null -eq $Value) {
        return @()
    }
    if ($Value -is [string]) {
        return @($Value)
    }
    if ($Value -is [System.Collections.IEnumerable]) {
        return @($Value | ForEach-Object { [string]$_ })
    }
    return @([string]$Value)
}

function Wait-RawMetadataSuccess {
    $lastState = "not checked"
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        Start-Sleep -Seconds $WaitSeconds
        $state = Invoke-SqlScalar @"
select coalesce(enrichment_status, '') || '|' ||
       coalesce(tmdb_id, '') || '|' ||
       coalesce(imdb_id, '') || '|' ||
       coalesce(year, '') || '|' ||
       coalesce(score::text, '') || '|' ||
       coalesce(description, '')
from raw_videos
where id = '$rawVideoId';
"@
        $lastState = $state
        if ($null -ne $state) {
            $parts = $state -split "\|", 6
            if ($parts[0] -eq "SUCCESS" -and $parts[1] -eq $script:expectedTmdbId) {
                return
            }
            if ($parts[0] -in @("FAILED", "PERMANENT_FAILURE")) {
                throw "raw metadata reached failure state: $state"
            }
        }
    }
    throw "raw metadata did not reach SUCCESS with expected tmdb_id=$($script:expectedTmdbId); last state: $lastState"
}

function Wait-EsRawDocument {
    $lastState = "not checked"
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        Start-Sleep -Seconds $WaitSeconds
        $doc = Get-EsDocument "ircs_raw_video" $rawVideoId
        if ($null -ne $doc -and $doc.found) {
            $source = $doc._source
            $externalIds = ConvertTo-StringArray $source.externalIds
            $lastState = "found enrichmentStatus='$($source.enrichmentStatus)' hasTmdbId='$($source.hasTmdbId)' externalIds='$($externalIds -join ',')'"
            if ($source.enrichmentStatus -eq "SUCCESS" `
                    -and $source.hasTmdbId -eq $true `
                    -and ($externalIds -contains $script:expectedTmdbId)) {
                return
            }
        } else {
            $lastState = "missing"
        }
    }
    throw "ES raw document did not reach TMDB enrichment projection; last state: $lastState"
}

function Wait-EsMissing {
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        Start-Sleep -Seconds 1
        $doc = Get-EsDocument "ircs_raw_video" $rawVideoId
        if ($null -eq $doc -or -not $doc.found) {
            return
        }
    }
    throw "Elasticsearch raw document still exists after cleanup: $rawVideoId"
}

try {
    Test-KubectlAccess
    $rabbitPf = Start-PortForward "svc/rabbitmq-svc" $RabbitPort 15672 $rabbitPfOut $rabbitPfErr
    $credentialPf = Start-PortForward "svc/ircs-credential-service" $CredentialPort 8080 $credentialPfOut $credentialPfErr
    $esPf = Start-PortForward "svc/elasticsearch-svc" $ElasticsearchPort 9200 $esPfOut $esPfErr

    $credential = Get-TmdbCredentialLease
    if ($SkipPreflightTmdbDiagnostic) {
        $script:expectedTmdbId = $ExpectedProviderTmdbId
        Mark "credential lease succeeded; TMDB upstream diagnostic skipped for direct queue verification" 1 6
    } else {
        $tmdbDiagnostic = Test-TmdbUpstream $credential
        $script:expectedTmdbId = [string]$tmdbDiagnostic.tmdbId
        Mark "credential lease and TMDB upstream diagnostic succeeded" 1 6
    }

    Ensure-TmdbProviderEnabled
    Assert-SmokeQueuesCleanBefore
    Assert-ConsumersReady
    Assert-NoHttpRoute
    Mark "metadata consumers are ready and HTTPRoute remains absent" 2 6

    Cleanup-SmokeData
    Remove-EsDocument "ircs_raw_video" $rawVideoId
    Setup-SmokeData

    $payload = [ordered]@{
        videoId = $rawVideoId
        title = $Query
        categorySlug = "movie"
        year = $Year
    } | ConvertTo-Json -Compress
    Invoke-RabbitPublish "x.process" "metadata.fetch.tmdb" $payload "application/json"
    Wait-QueueEmpty "q.fetch.metadata.tmdb"
    Mark "TMDB provider queue task is published and q.fetch.metadata.tmdb drains" 3 6

    Wait-RawMetadataSuccess
    Mark "provider result updates raw metadata to SUCCESS with tmdb_id" 4 6

    Wait-EsRawDocument
    Mark "raw search sync refreshes ES document with TMDB enrichment fields" 5 6

    foreach ($queue in @(
        "q.fetch.metadata.tmdb",
        "q.fetch.metadata.tmdb.dlq",
        "q.enrich.metadata.result",
        "q.enrich.metadata.result.dlq"
    )) {
        Assert-QueueEmpty $queue
    }
    Cleanup-SmokeData
    Remove-EsDocument "ircs_raw_video" $rawVideoId
    Wait-EsMissing
    Assert-NoDbResidue
    Assert-NoHttpRoute
    Mark "provider/result queues and DLQs are empty; smoke fixture is cleaned" 6 6
} catch {
    Finish-Smoke "FAILED" "LIVE_QUEUE_SMOKE_FAILED" @{
        message = $_.Exception.Message
        rawVideoId = $rawVideoId
        expectedTmdbId = $script:expectedTmdbId
    }
} finally {
    try { Cleanup-SmokeData } catch { Write-Warning $_ }
    try {
        if ($esPf -and -not $esPf.HasExited) {
            Remove-EsDocument "ircs_raw_video" $rawVideoId
        }
    } catch { Write-Warning $_ }
    if ($PurgeSmokeQueuesOnCleanup) {
        foreach ($queue in @(
            "q.fetch.metadata.tmdb",
            "q.fetch.metadata.tmdb.dlq",
            "q.enrich.metadata.result",
            "q.enrich.metadata.result.dlq"
        )) {
            try { kubectl -n $Namespace exec rabbitmq-0 -- rabbitmqctl purge_queue $queue | Out-Null } catch { Write-Warning $_ }
        }
    }
    if ($metadataEnvPatched) {
        try { Restore-MetadataEnv } catch { Write-Warning $_ }
    }
    if ($rabbitPf -and -not $rabbitPf.HasExited) {
        Stop-Process -Id $rabbitPf.Id -Force -ErrorAction SilentlyContinue
    }
    if ($credentialPf -and -not $credentialPf.HasExited) {
        Stop-Process -Id $credentialPf.Id -Force -ErrorAction SilentlyContinue
    }
    if ($esPf -and -not $esPf.HasExited) {
        Stop-Process -Id $esPf.Id -Force -ErrorAction SilentlyContinue
    }
}
