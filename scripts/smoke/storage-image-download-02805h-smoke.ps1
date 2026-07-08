param(
    [string]$RabbitBaseUrl = "http://127.0.0.1:19075",
    [int]$RabbitPort = 19075
)

$ErrorActionPreference = "Stop"

$stamp = Get-Date -Format "yyyyMMddHHmmss"
$rabbitPfOut = Join-Path $env:TEMP "ircs-02805h-rabbit-pf.out.log"
$rabbitPfErr = Join-Path $env:TEMP "ircs-02805h-rabbit-pf.err.log"
$rabbitPf = $null

$fixtureName = "ircs-02805h-fixture"
$fixtureConfigMap = "ircs-02805h-fixture"
$fixtureService = "ircs-02805h-fixture"
$fixtureFile = Join-Path $env:TEMP "ircs-02805h-cover-$stamp.png"
$sourceDomainId = [guid]::NewGuid().ToString()
$coverImageId = [guid]::NewGuid().ToString()
$domainHash = "codex02805h$stamp"
$domainValue = "http://$fixtureService.ircs-dev.svc.cluster.local"
$originalPath = "cover.png"
$storagePath = $null
$originalStorageEnv = @{}
$storageEnvCaptured = $false

function Mark {
    param([string]$Name, [int]$Index, [int]$Total)
    Write-Output ("MARK {0}/{1} {2}" -f $Index, $Total, $Name)
}

function Exec-Sql {
    param([string]$Sql)
    $Sql | kubectl -n ircs-dev exec -i postgres-0 -- psql -v ON_ERROR_STOP=1 -U postgres -d ircs
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed with exit code $LASTEXITCODE"
    }
}

function Invoke-SqlScalar {
    param([string]$Sql)
    $rows = $Sql | kubectl -n ircs-dev exec -i postgres-0 -- psql -v ON_ERROR_STOP=1 -U postgres -d ircs -t -A
    if ($LASTEXITCODE -ne 0) {
        throw "psql scalar failed with exit code $LASTEXITCODE"
    }
    $value = $rows | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Last 1
    if ($null -eq $value) {
        return $null
    }
    return $value.Trim()
}

function Start-RabbitPortForward {
    Remove-Item -LiteralPath $rabbitPfOut, $rabbitPfErr -ErrorAction SilentlyContinue
    $pf = Start-Process -FilePath kubectl `
        -ArgumentList @("-n", "ircs-dev", "port-forward", "svc/rabbitmq-svc", "$($RabbitPort):15672") `
        -RedirectStandardOutput $rabbitPfOut `
        -RedirectStandardError $rabbitPfErr `
        -WindowStyle Hidden `
        -PassThru
    Start-Sleep -Seconds 5
    if ($pf.HasExited) {
        $err = if (Test-Path $rabbitPfErr) { Get-Content -Path $rabbitPfErr -Raw } else { "" }
        throw "rabbitmq port-forward exited: $err"
    }
    return $pf
}

function Get-SecretValue {
    param([string]$Key)
    $value64 = kubectl -n ircs-dev get secret ircs-dev-secrets -o jsonpath="{.data.$Key}"
    if (-not $value64) {
        throw "$Key not found"
    }
    return [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($value64))
}

function Invoke-RabbitPublish {
    param([string]$ImageId)
    $rabbitPassword = Get-SecretValue "RABBITMQ_PASSWORD"
    $body = @{
        properties = @{ content_type = "text/plain" }
        routing_key = "image.download"
        payload = $ImageId
        payload_encoding = "string"
    } | ConvertTo-Json -Depth 8 -Compress
    $requestFile = Join-Path $env:TEMP ("ircs-02805h-rabbit-publish-" + [guid]::NewGuid() + ".json")
    $responseFile = Join-Path $env:TEMP ("ircs-02805h-rabbit-response-" + [guid]::NewGuid() + ".json")
    [System.IO.File]::WriteAllText($requestFile, $body, [System.Text.UTF8Encoding]::new($false))
    try {
        $code = & curl.exe -sS -u "admin:$rabbitPassword" `
            -H "Content-Type: application/json" `
            -o $responseFile `
            -w "%{http_code}" `
            --data-binary "@$requestFile" `
            "$RabbitBaseUrl/api/exchanges/%2F/x.storage/publish"
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
    $lines = kubectl -n ircs-dev exec rabbitmq-0 -- rabbitmqctl list_queues name messages consumers
    $line = $lines | Where-Object { $_ -match "^$([regex]::Escape($Name))\s" } | Select-Object -First 1
    if (-not $line) {
        throw "queue not found: $Name"
    }
    return $line -split "\s+"
}

function Assert-QueueEmpty {
    param([string]$Name)
    $parts = Get-QueueParts $Name
    if ([int]$parts[1] -ne 0) {
        throw "queue $Name has $($parts[1]) messages"
    }
}

function Wait-QueueEmpty {
    param([string]$Name)
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        Start-Sleep -Seconds 2
        $parts = Get-QueueParts $Name
        if ([int]$parts[1] -eq 0) {
            return
        }
    }
    throw "queue $Name did not drain"
}

function Get-StoragePodName {
    $pod = kubectl -n ircs-dev get pod -l app=ircs-storage-service -o jsonpath="{.items[0].metadata.name}"
    if (-not $pod) {
        throw "storage-service pod not found"
    }
    return $pod
}

function Test-StorageObject {
    param([string]$Path)
    $pod = Get-StoragePodName
    kubectl -n ircs-dev exec $pod -- test -s "/app/storage/$Path"
    return $LASTEXITCODE -eq 0
}

function Remove-StorageObject {
    param([string]$Path)
    if (-not $Path -or $Path -notmatch "^covers/[a-f0-9]{64}\.png$") {
        return
    }
    $pod = Get-StoragePodName
    kubectl -n ircs-dev exec $pod -- rm -f "/app/storage/$Path" | Out-Null
}

function Capture-StorageEnv {
    $script:originalStorageEnv = @{}
    $deployment = kubectl -n ircs-dev get deploy ircs-storage-service -o json | ConvertFrom-Json
    $envList = $deployment.spec.template.spec.containers[0].env
    foreach ($name in @("APP_STORAGE_IMAGE_ALLOW_LOCAL_ADDRESSES", "APP_STORAGE_IMAGE_DOWNLOAD_ENABLED")) {
        $entry = $envList | Where-Object { $_.name -eq $name } | Select-Object -First 1
        if ($entry) {
            $script:originalStorageEnv[$name] = $entry.value
        } else {
            $script:originalStorageEnv[$name] = $null
        }
    }
    $script:storageEnvCaptured = $true
}

function Set-StorageSmokeEnv {
    Capture-StorageEnv
    kubectl -n ircs-dev set env deployment/ircs-storage-service `
        APP_STORAGE_IMAGE_ALLOW_LOCAL_ADDRESSES=true `
        APP_STORAGE_IMAGE_DOWNLOAD_ENABLED=true | Out-Null
    kubectl -n ircs-dev rollout status deployment/ircs-storage-service --timeout=240s
    if ($LASTEXITCODE -ne 0) {
        throw "storage-service rollout after smoke env failed"
    }
}

function Restore-StorageEnv {
    if (-not $storageEnvCaptured) {
        return
    }
    $args = @("-n", "ircs-dev", "set", "env", "deployment/ircs-storage-service")
    foreach ($name in @("APP_STORAGE_IMAGE_ALLOW_LOCAL_ADDRESSES", "APP_STORAGE_IMAGE_DOWNLOAD_ENABLED")) {
        $value = $originalStorageEnv[$name]
        if ($null -eq $value) {
            $args += "$name-"
        } else {
            $args += "$name=$value"
        }
    }
    & kubectl @args | Out-Null
    kubectl -n ircs-dev rollout status deployment/ircs-storage-service --timeout=240s | Out-Null
}

function New-FixtureServer {
    $pngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
    [System.IO.File]::WriteAllBytes($fixtureFile, [System.Convert]::FromBase64String($pngBase64))

    kubectl -n ircs-dev delete pod $fixtureName --ignore-not-found=true | Out-Null
    kubectl -n ircs-dev delete service $fixtureService --ignore-not-found=true | Out-Null
    kubectl -n ircs-dev delete configmap $fixtureConfigMap --ignore-not-found=true | Out-Null

    kubectl -n ircs-dev create configmap $fixtureConfigMap --from-file=cover.png=$fixtureFile --dry-run=client -o yaml |
        kubectl apply -f - | Out-Null

    $yaml = @"
apiVersion: v1
kind: Pod
metadata:
  name: $fixtureName
  namespace: ircs-dev
  labels:
    app: $fixtureName
spec:
  restartPolicy: Never
  containers:
    - name: nginx
      image: nginx:1.25-alpine
      ports:
        - name: http
          containerPort: 80
      resources:
        requests:
          cpu: 5m
          memory: 16Mi
        limits:
          cpu: 25m
          memory: 64Mi
      volumeMounts:
        - name: fixture-volume
          mountPath: /usr/share/nginx/html/cover.png
          subPath: cover.png
  volumes:
    - name: fixture-volume
      configMap:
        name: $fixtureConfigMap
---
apiVersion: v1
kind: Service
metadata:
  name: $fixtureService
  namespace: ircs-dev
  labels:
    app: $fixtureName
spec:
  type: ClusterIP
  selector:
    app: $fixtureName
  ports:
    - name: http
      port: 80
      targetPort: http
"@
    $yaml | kubectl apply -f - | Out-Null
    kubectl -n ircs-dev wait --for=condition=Ready pod/$fixtureName --timeout=180s
    if ($LASTEXITCODE -ne 0) {
        throw "fixture pod did not become ready"
    }
}

function Remove-FixtureServer {
    kubectl -n ircs-dev delete service $fixtureService --ignore-not-found=true | Out-Null
    kubectl -n ircs-dev delete pod $fixtureName --ignore-not-found=true --wait=false | Out-Null
    kubectl -n ircs-dev delete configmap $fixtureConfigMap --ignore-not-found=true | Out-Null
    Remove-Item -LiteralPath $fixtureFile -ErrorAction SilentlyContinue
}

function Assert-NoHttpRoute {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = kubectl -n ircs-dev get httproute --no-headers 2>&1
    $exit = $LASTEXITCODE
    $ErrorActionPreference = $previous
    $text = ($output | Out-String).Trim()
    if ($exit -ne 0 -and $text -notmatch "No resources found") {
        throw "failed to list HTTPRoute: $text"
    }
    if ($exit -eq 0 -and $text -and $text -notmatch "No resources found") {
        throw "unexpected HTTPRoute in ircs-dev: $text"
    }
}

function Cleanup-SmokeData {
    if ($storagePath) {
        try { Remove-StorageObject $storagePath } catch { Write-Warning $_ }
    }
    $sql = @"
delete from cover_images where id = '$coverImageId';
delete from source_domains where id = '$sourceDomainId' or domain_hash = '$domainHash';
"@
    Exec-Sql $sql | Out-Null
}

try {
    $rabbitPf = Start-RabbitPortForward
    New-FixtureServer
    Set-StorageSmokeEnv
    Cleanup-SmokeData

    $setupSql = @"
insert into source_domains (id, created_at, updated_at, version, domain_hash, domain_value, remark)
values ('$sourceDomainId', now(), now(), 0, '$domainHash', '$domainValue', '02805h smoke');
insert into cover_images (id, created_at, updated_at, version, storage_type, original_url, source_domain_id, status, retry_count)
values ('$coverImageId', now(), now(), 0, 'EXTERNAL', '$originalPath', '$sourceDomainId', 'UNPROCESSED', 0);
"@
    Exec-Sql $setupSql | Out-Null

    $total = 5
    $i = 1

    $queueParts = Get-QueueParts "q.storage.image"
    if ([int]$queueParts[2] -lt 1) {
        throw "q.storage.image has no consumers"
    }
    Mark "DOWNLOAD_IMAGE consumer listens on q.storage.image" $i $total; $i++

    Invoke-RabbitPublish $coverImageId
    Wait-QueueEmpty "q.storage.image"

    $state = $null
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        Start-Sleep -Seconds 2
        $state = Invoke-SqlScalar @"
select storage_type || '|' || status || '|' || coalesce(storage_path, '') || '|' ||
       coalesce(file_size::text, '') || '|' || coalesce(mime_type, '') || '|' ||
       coalesce(retry_count::text, '') || '|' || coalesce(last_error, '')
from cover_images
where id = '$coverImageId';
"@
        if ($state -and $state.StartsWith("LOCAL|LOCAL_STORED|")) {
            break
        }
    }
    if (-not $state -or -not $state.StartsWith("LOCAL|LOCAL_STORED|")) {
        throw "cover image did not finalize as LOCAL_STORED; state=$state"
    }
    $parts = $state -split "\|", 7
    $storagePath = $parts[2]
    if (-not $storagePath -or $storagePath -notmatch "^covers/[a-f0-9]{64}\.png$") {
        throw "unexpected storage_path: $storagePath"
    }
    if ($parts[4] -ne "image/png") {
        throw "unexpected mime_type: $($parts[4])"
    }
    if ($parts[5] -ne "0") {
        throw "unexpected retry_count: $($parts[5])"
    }
    if ($parts[6]) {
        throw "unexpected last_error: $($parts[6])"
    }
    Mark "DOWNLOAD_IMAGE fixture finalizes cover_images as LOCAL_STORED" $i $total; $i++

    if (-not (Test-StorageObject $storagePath)) {
        throw "local storage object not found: $storagePath"
    }
    Mark "downloaded cover object exists under storage-service local storage" $i $total; $i++

    Assert-QueueEmpty "q.storage.image"
    Assert-QueueEmpty "q.storage.image.dlq"
    Mark "q.storage.image and DLQ are empty after live download" $i $total; $i++

    Cleanup-SmokeData
    $residue = Invoke-SqlScalar @"
select
  (select count(*) from cover_images where id = '$coverImageId')::text
  || '|' ||
  (select count(*) from source_domains where id = '$sourceDomainId' or domain_hash = '$domainHash')::text;
"@
    if ($residue -ne "0|0") {
        throw "smoke cleanup left DB residue: $residue"
    }
    if ($storagePath -and (Test-StorageObject $storagePath)) {
        throw "smoke cleanup left local object residue: $storagePath"
    }
    Assert-QueueEmpty "q.storage.image"
    Assert-QueueEmpty "q.storage.image.dlq"
    Assert-NoHttpRoute
    Remove-FixtureServer
    Mark "live smoke cleanup leaves no DB/object residue, q.storage.image and DLQ empty" $i $total
} finally {
    try { Cleanup-SmokeData } catch { Write-Warning $_ }
    foreach ($queue in @("q.storage.image", "q.storage.image.dlq")) {
        try { kubectl -n ircs-dev exec rabbitmq-0 -- rabbitmqctl purge_queue $queue | Out-Null } catch { Write-Warning $_ }
    }
    try { Remove-FixtureServer } catch { Write-Warning $_ }
    try { Restore-StorageEnv } catch { Write-Warning $_ }
    if ($rabbitPf -and -not $rabbitPf.HasExited) {
        Stop-Process -Id $rabbitPf.Id -Force -ErrorAction SilentlyContinue
    }
}
