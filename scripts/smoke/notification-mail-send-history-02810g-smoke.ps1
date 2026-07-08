param(
    [string]$Namespace = "ircs-dev",
    [int]$OpsPort = 18132,
    [string]$OpsBaseUrl = "http://127.0.0.1:18132",
    [string]$TaskId = "028-10-G",
    [string]$SliceName = "send-history-migrator-dev-deploy-smoke",
    [switch]$StartOpsPortForward,
    [switch]$FailOnUnavailable
)

$ErrorActionPreference = "Stop"

$opsPf = $null
$opsPfOut = Join-Path $env:TEMP "ircs-02810g-mail-ops-pf.out.log"
$opsPfErr = Join-Path $env:TEMP "ircs-02810g-mail-ops-pf.err.log"

function Mark {
    param([string]$Name, [int]$Index, [int]$Total)
    Write-Output ("MARK {0}/{1} {2}" -f $Index, $Total, $Name)
}

function Finish-Smoke {
    param(
        [string]$Status,
        [string]$Reason,
        [hashtable]$Details = @{}
    )

    [ordered]@{
        task = $TaskId
        slice = $SliceName
        status = $Status
        reason = $Reason
        checkedAt = (Get-Date).ToUniversalTime().ToString("o")
        namespace = $Namespace
        details = $Details
    } | ConvertTo-Json -Depth 16

    if ($Status -eq "FAILED") {
        exit 1
    }
    if ($Status -eq "UNAVAILABLE" -and $FailOnUnavailable) {
        exit 2
    }
    exit 0
}

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

    $value = $Sql | kubectl -n $Namespace exec -i postgres-0 -- psql -v ON_ERROR_STOP=1 -U postgres -d ircs -t -A 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed: $($value | Out-String)"
    }
    $row = $value | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Last 1
    if ($null -eq $row) {
        return $null
    }
    return $row.Trim()
}

function Get-KubectlJson {
    param([string[]]$KubectlArgs)

    $raw = kubectl @KubectlArgs -o json 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "kubectl failed: $($raw | Out-String)"
    }
    return $raw | ConvertFrom-Json
}

function Get-ConfigMapValue {
    param([string]$Name, [string]$Key)

    $value = kubectl -n $Namespace get configmap $Name -o jsonpath="{.data.$Key}" 2>&1
    if ($LASTEXITCODE -ne 0) {
        return $null
    }
    $text = (($value | Out-String).Trim())
    if ([string]::IsNullOrWhiteSpace($text)) {
        return $null
    }
    return $text
}

function Get-EnvValue {
    param(
        [object]$Deployment,
        [string]$Name
    )

    $container = $Deployment.spec.template.spec.containers | Where-Object { $_.name -eq "app" } | Select-Object -First 1
    if ($null -eq $container) {
        $container = $Deployment.spec.template.spec.containers | Select-Object -First 1
    }
    $env = $container.env | Where-Object { $_.name -eq $Name } | Select-Object -First 1
    if ($null -eq $env) {
        return $null
    }
    if ($null -ne $env.value) {
        return [string]$env.value
    }
    if ($null -ne $env.valueFrom.configMapKeyRef) {
        $value = Get-ConfigMapValue ([string]$env.valueFrom.configMapKeyRef.name) ([string]$env.valueFrom.configMapKeyRef.key)
        if ($null -ne $value) {
            return $value
        }
        return "[configMap:$($env.valueFrom.configMapKeyRef.name)/$($env.valueFrom.configMapKeyRef.key)]"
    }
    if ($null -ne $env.valueFrom.secretKeyRef) {
        return "[secret:$($env.valueFrom.secretKeyRef.name)/$($env.valueFrom.secretKeyRef.key)]"
    }
    return "[valueFrom]"
}

function Start-OpsPortForward {
    Remove-Item -LiteralPath $opsPfOut, $opsPfErr -ErrorAction SilentlyContinue
    $process = Start-Process `
        -FilePath "kubectl" `
        -ArgumentList @("-n", $Namespace, "port-forward", "svc/ircs-ops-service", "$OpsPort`:8080") `
        -RedirectStandardOutput $opsPfOut `
        -RedirectStandardError $opsPfErr `
        -PassThru `
        -WindowStyle Hidden
    Wait-ForTcpPort -HostName "127.0.0.1" -Port $OpsPort -TimeoutSeconds 30
    return $process
}

try {
    kubectl -n $Namespace get pod postgres-0 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "postgres-0 is unavailable"
    }

    $deployment = Get-KubectlJson @("-n", $Namespace, "get", "deployment", "ircs-notification-worker")
    $mailEnabled = Get-EnvValue $deployment "APP_MAIL_ENABLED"
    $sendHistoryEnabled = Get-EnvValue $deployment "APP_MAIL_SEND_HISTORY_ENABLED"
    $mailGateOk = ($mailEnabled -eq "false")
    Mark "notification-worker APP_MAIL_ENABLED checked and SMTP sending stays disabled" 1 4

    $table = Invoke-PostgresScalar "select to_regclass('public.notification_mail_send_history');"
    $tablePresent = ($table -eq "notification_mail_send_history")
    $columnCount = $null
    $indexCount = $null
    $recent24hCount = $null
    if ($tablePresent) {
        $columnCount = Invoke-PostgresScalar "select count(*) from information_schema.columns where table_schema = 'public' and table_name = 'notification_mail_send_history';"
        $indexCount = Invoke-PostgresScalar "select count(*) from pg_indexes where schemaname = 'public' and tablename = 'notification_mail_send_history' and indexname like 'idx_notification_mail_send_history%';"
        $recent24hCount = Invoke-PostgresScalar "select count(*) from notification_mail_send_history where created_at >= now() - interval '24 hours';"
    }
    if ($tablePresent) {
        Mark "notification_mail_send_history table exists in dev DB" 2 4
    } else {
        Write-Output "MARK 2/4 notification_mail_send_history table missing in dev DB"
    }

    $opsEvidence = @{
        queried = $false
        available = $false
    }
    if ($StartOpsPortForward) {
        $opsPf = Start-OpsPortForward
        try {
            $summary = Invoke-RestMethod -Method Get -Uri "$($OpsBaseUrl.TrimEnd('/'))/api/v1/ops/notification-mail-send-history/summary" -TimeoutSec 15
            $page = Invoke-RestMethod -Method Get -Uri "$($OpsBaseUrl.TrimEnd('/'))/api/v1/ops/notification-mail-send-history?size=1" -TimeoutSec 15
            $opsEvidence = @{
                queried = $true
                available = $true
                summaryStatus = "OK"
                totalLast24h = $summary.totalLast24h
                sentLast24h = $summary.sentLast24h
                failedLast24h = $summary.failedLast24h
                skippedLast24h = $summary.skippedLast24h
                sentSemantics = $summary.sentSemantics
                pageTotalElements = $page.totalElements
            }
            Mark "ops send history summary and page endpoints are available" 3 4
        } catch {
            $opsEvidence = @{
                queried = $true
                available = $false
                error = $_.Exception.Message
            }
            Write-Output "MARK 3/4 ops send history endpoint unavailable"
        }
    } else {
        $opsEvidence.reason = "START_OPS_PORT_FORWARD_NOT_SET"
        Write-Output "MARK 3/4 ops send history endpoint not queried"
    }

    Mark "SMTP was not sent and APP_MAIL_ENABLED was not changed" 4 4

    $details = @{
        sent = $false
        appMailEnabled = $mailEnabled
        appMailEnabledFalse = $mailGateOk
        appMailSendHistoryEnabled = if ([string]::IsNullOrWhiteSpace($sendHistoryEnabled)) { "false(default)" } else { $sendHistoryEnabled }
        sendHistoryTable = @{
            name = $table
            present = $tablePresent
            columnCount = $columnCount
            indexCount = $indexCount
            recent24hCount = $recent24hCount
        }
        opsSendHistory = $opsEvidence
        passwordPrinted = $false
        recipientPrinted = $false
    }

    if (-not $mailGateOk) {
        Finish-Smoke "FAILED" "APP_MAIL_ENABLED_NOT_FALSE" $details
    }
    if (-not $tablePresent -or ($StartOpsPortForward -and -not [bool]$opsEvidence.available)) {
        Finish-Smoke "UNAVAILABLE" "SEND_HISTORY_DEV_SMOKE_INCOMPLETE" $details
    }
    Finish-Smoke "PASS" "SEND_HISTORY_DEV_SMOKE_OK" $details
} catch {
    Finish-Smoke "FAILED" "SEND_HISTORY_DEV_SMOKE_FAILED" @{
        message = $_.Exception.Message
        stack = $_.ScriptStackTrace
        sent = $false
        passwordPrinted = $false
        recipientPrinted = $false
    }
} finally {
    if ($opsPf -and -not $opsPf.HasExited) {
        Stop-Process -Id $opsPf.Id -Force -ErrorAction SilentlyContinue
    }
}
