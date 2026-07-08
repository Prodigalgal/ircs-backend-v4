param(
    [string]$Namespace = "ircs-dev",
    [int]$LocalPort = 18089
)

$ErrorActionPreference = "Stop"

function Wait-ForTcpPort {
    param(
        [string]$HostName,
        [int]$Port,
        [int]$TimeoutSeconds = 30
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $client = [System.Net.Sockets.TcpClient]::new()
        try {
            $async = $client.BeginConnect($HostName, $Port, $null, $null)
            if ($async.AsyncWaitHandle.WaitOne(500)) {
                $client.EndConnect($async)
                return
            }
        }
        catch {
        }
        finally {
            $client.Close()
        }
        Start-Sleep -Milliseconds 300
    }
    throw "Timed out waiting for $HostName`:$Port"
}

function Invoke-PostgresScalar {
    param([string]$Sql)

    $value = kubectl -n $Namespace exec postgres-0 -- psql -U postgres -d ircs -tAc $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed: $Sql"
    }
    return ($value | Out-String).Trim()
}

$traceId = "02812-credential-audit-" + (Get-Date -Format "yyyyMMddHHmmss")
$requestPath = "/api/v1/credentials/templates"
$portForward = $null

try {
    $podStatus = kubectl -n $Namespace get pod -l app=ircs-credential-service -o jsonpath="{.items[0].status.containerStatuses[0].ready}"
    if ($podStatus -ne "true") {
        throw "ircs-credential-service pod is not Ready"
    }
    Write-Host "MARK 1/4 credential-service pod is Ready"

    Invoke-PostgresScalar "delete from request_audit_logs where request_source = 'ircs-credential-service' and trace_id = '$traceId';" | Out-Null

    $portForward = Start-Process `
        -FilePath "kubectl" `
        -ArgumentList @("-n", $Namespace, "port-forward", "svc/ircs-credential-service", "$LocalPort`:8080") `
        -PassThru `
        -WindowStyle Hidden
    Wait-ForTcpPort -HostName "127.0.0.1" -Port $LocalPort -TimeoutSeconds 30

    $headers = @{
        "X-Trace-Id" = $traceId
        "X-Authenticated-User" = "credential-audit-smoke@example.com"
        "X-Forwarded-For" = "198.51.100.67, 10.0.0.1"
        "User-Agent" = "credential-service-audit-02812-smoke"
    }
    $uri = "http://127.0.0.1:${LocalPort}${requestPath}?provider=TMDB"
    $response = Invoke-RestMethod -Method Get -Uri $uri -Headers $headers
    $keys = @($response | ForEach-Object { $_.key })
    if ($keys -notcontains "api_key") {
        throw "Expected TMDB template response to contain api_key field"
    }
    Write-Host "MARK 2/4 credential-service safe template request returns success"

    $matchCount = Invoke-PostgresScalar @"
select count(*)
  from request_audit_logs
 where request_source = 'ircs-credential-service'
   and trace_id = '$traceId'
   and path = '$requestPath'
   and query_string = 'provider=TMDB'
   and status_code = 200
   and success = true;
"@
    if ($matchCount -ne "1") {
        throw "Expected one credential-service audit row for $traceId, got $matchCount"
    }
    Write-Host "MARK 3/4 credential-service live audit row is queryable by trace_id"

    Invoke-PostgresScalar "delete from request_audit_logs where request_source = 'ircs-credential-service' and trace_id = '$traceId';" | Out-Null
    $residue = Invoke-PostgresScalar "select count(*) from request_audit_logs where request_source = 'ircs-credential-service' and trace_id = '$traceId';"
    if ($residue -ne "0") {
        throw "Expected no credential-service audit residue for $traceId, got $residue"
    }

    $httpRoutes = kubectl -n $Namespace get httproute --ignore-not-found 2>&1
    if (-not $httpRoutes) {
        $httpRoutes = "No resources found in $Namespace namespace."
    }
    $quota = kubectl -n $Namespace describe resourcequota ircs-dev-quota
    Write-Host "MARK 4/4 credential-service live smoke cleanup leaves no DB residue"
    Write-Host ""
    Write-Host "trace: $traceId"
    Write-Host "HTTPRoute: $httpRoutes"
    Write-Host "ResourceQuota:"
    Write-Host $quota
}
finally {
    if ($portForward -and -not $portForward.HasExited) {
        Stop-Process -Id $portForward.Id -Force -ErrorAction SilentlyContinue
    }
}
