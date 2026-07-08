param(
    [string]$Namespace = "ircs-dev",
    [int]$LocalPort = 18097,
    [switch]$ExecuteLive
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

if (-not $ExecuteLive) {
    Write-Host "MARK 1/1 scraper-service audit smoke is dev-safe dry-run"
    Write-Host ""
    Write-Host "scraper-service has no truly read-only business endpoint."
    Write-Host "This script does not call live service unless -ExecuteLive is provided."
    Write-Host "Live mode calls GET /api/v1/scraper/manual/stream/{invalid-session} and expects a handled failure."
    Write-Host "It never calls /init, /refetch, or /task-executions, so it does not create sessions or start scraper work."
    return
}

$traceId = "02812-scraper-audit-" + (Get-Date -Format "yyyyMMddHHmmss")
$invalidSessionId = [guid]::NewGuid().ToString()
$requestPath = "/api/v1/scraper/manual/stream/$invalidSessionId"
$portForward = $null

try {
    $podStatus = kubectl -n $Namespace get pod -l app=ircs-scraper-service -o jsonpath="{.items[0].status.containerStatuses[0].ready}"
    if ($podStatus -ne "true") {
        throw "ircs-scraper-service pod is not Ready"
    }
    Write-Host "MARK 1/4 scraper-service pod is Ready"

    Invoke-PostgresScalar "delete from request_audit_logs where request_source = 'ircs-scraper-service' and trace_id = '$traceId';" | Out-Null

    $portForward = Start-Process `
        -FilePath "kubectl" `
        -ArgumentList @("-n", $Namespace, "port-forward", "svc/ircs-scraper-service", "$LocalPort`:8080") `
        -PassThru `
        -WindowStyle Hidden
    Wait-ForTcpPort -HostName "127.0.0.1" -Port $LocalPort -TimeoutSeconds 30

    $headers = @{
        "X-Trace-Id" = $traceId
        "X-Authenticated-User" = "scraper-audit-smoke@example.com"
        "X-Forwarded-For" = "198.51.100.97, 10.0.0.1"
        "User-Agent" = "scraper-service-audit-02812-smoke"
    }
    $uri = "http://127.0.0.1:${LocalPort}${requestPath}?token=query-secret-token&password=query-secret-password"

    $statusCode = $null
    try {
        $response = Invoke-WebRequest -Method Get -Uri $uri -Headers $headers
        $statusCode = [int]$response.StatusCode
    }
    catch {
        if ($_.Exception.Response -eq $null) {
            throw
        }
        $statusCode = [int]$_.Exception.Response.StatusCode
    }
    if ($statusCode -lt 400) {
        throw "Expected invalid scraper stream request to fail, got HTTP $statusCode"
    }
    Write-Host "MARK 2/4 scraper-service invalid stream request fails without scraper execution"

    $matchCount = Invoke-PostgresScalar @"
select count(*)
  from request_audit_logs
 where request_source = 'ircs-scraper-service'
   and trace_id = '$traceId'
   and path = '$requestPath'
   and query_string = 'token=***&password=***'
   and status_code >= 400
   and success = false;
"@
    if ($matchCount -ne "1") {
        throw "Expected one scraper-service audit row for $traceId, got $matchCount"
    }

    $leakCount = Invoke-PostgresScalar @"
select count(*)
  from request_audit_logs
 where request_source = 'ircs-scraper-service'
   and trace_id = '$traceId'
   and (
        coalesce(query_string, '') like '%query-secret-token%'
        or coalesce(query_string, '') like '%query-secret-password%'
        or coalesce(error_message, '') like '%query-secret-token%'
        or coalesce(error_message, '') like '%query-secret-password%'
   );
"@
    if ($leakCount -ne "0") {
        throw "Expected no scraper-service query secret leak for $traceId, got $leakCount"
    }
    Write-Host "MARK 3/4 scraper-service live audit row redacts query token/password"

    Invoke-PostgresScalar "delete from request_audit_logs where request_source = 'ircs-scraper-service' and trace_id = '$traceId';" | Out-Null
    $residue = Invoke-PostgresScalar "select count(*) from request_audit_logs where request_source = 'ircs-scraper-service' and trace_id = '$traceId';"
    if ($residue -ne "0") {
        throw "Expected no scraper-service audit residue for $traceId, got $residue"
    }

    Write-Host "MARK 4/4 scraper-service live smoke cleanup leaves no DB residue"
    Write-Host ""
    Write-Host "trace: $traceId"
    Write-Host "invalidSessionId: $invalidSessionId"
    Write-Host "No init/refetch/task-executions call was made."
}
finally {
    if ($portForward -and -not $portForward.HasExited) {
        Stop-Process -Id $portForward.Id -Force -ErrorAction SilentlyContinue
    }
}
