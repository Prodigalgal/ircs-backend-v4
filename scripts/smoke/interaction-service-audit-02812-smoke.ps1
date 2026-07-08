param(
    [string]$Namespace = "ircs-dev",
    [int]$LocalPort = 18091
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

$traceId = "02812-interaction-audit-" + (Get-Date -Format "yyyyMMddHHmmss")
$requestPath = "/api/portal/feedback/wall"
$portForward = $null

try {
    $podStatus = kubectl -n $Namespace get pod -l app=ircs-interaction-service -o jsonpath="{.items[0].status.containerStatuses[0].ready}"
    if ($podStatus -ne "true") {
        throw "ircs-interaction-service pod is not Ready"
    }
    Write-Host "MARK 1/4 interaction-service pod is Ready"

    Invoke-PostgresScalar "delete from request_audit_logs where request_source = 'ircs-interaction-service' and trace_id = '$traceId';" | Out-Null

    $portForward = Start-Process `
        -FilePath "kubectl" `
        -ArgumentList @("-n", $Namespace, "port-forward", "svc/ircs-interaction-service", "$LocalPort`:8080") `
        -PassThru `
        -WindowStyle Hidden
    Wait-ForTcpPort -HostName "127.0.0.1" -Port $LocalPort -TimeoutSeconds 30

    $headers = @{
        "X-Trace-Id" = $traceId
        "X-Authenticated-User" = "interaction-audit-smoke@example.com"
        "X-Forwarded-For" = "198.51.100.91, 10.0.0.1"
        "User-Agent" = "interaction-service-audit-02812-smoke"
    }
    $uri = "http://127.0.0.1:${LocalPort}${requestPath}?token=query-secret-token&page=0&size=1"
    Invoke-RestMethod -Method Get -Uri $uri -Headers $headers | Out-Null
    Write-Host "MARK 2/4 interaction-service feedback wall request returns success"

    $matchCount = Invoke-PostgresScalar @"
select count(*)
  from request_audit_logs
 where request_source = 'ircs-interaction-service'
   and trace_id = '$traceId'
   and path = '$requestPath'
   and query_string = 'token=***&page=0&size=1'
   and status_code = 200
   and success = true;
"@
    if ($matchCount -ne "1") {
        throw "Expected one interaction-service audit row for $traceId, got $matchCount"
    }

    $leakCount = Invoke-PostgresScalar @"
select count(*)
  from request_audit_logs
 where request_source = 'ircs-interaction-service'
   and trace_id = '$traceId'
   and coalesce(query_string, '') like '%query-secret-token%';
"@
    if ($leakCount -ne "0") {
        throw "Expected no interaction-service query token leak for $traceId, got $leakCount"
    }
    Write-Host "MARK 3/4 interaction-service live audit row redacts query token"

    Invoke-PostgresScalar "delete from request_audit_logs where request_source = 'ircs-interaction-service' and trace_id = '$traceId';" | Out-Null
    $residue = Invoke-PostgresScalar "select count(*) from request_audit_logs where request_source = 'ircs-interaction-service' and trace_id = '$traceId';"
    if ($residue -ne "0") {
        throw "Expected no interaction-service audit residue for $traceId, got $residue"
    }

    $httpRoutes = kubectl -n $Namespace get httproute --ignore-not-found 2>&1
    if (-not $httpRoutes) {
        $httpRoutes = "No resources found in $Namespace namespace."
    }
    $quota = kubectl -n $Namespace describe resourcequota ircs-dev-quota
    Write-Host "MARK 4/4 interaction-service live smoke cleanup leaves no DB residue"
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
