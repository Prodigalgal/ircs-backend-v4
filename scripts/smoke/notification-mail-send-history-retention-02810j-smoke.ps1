param(
    [string]$Namespace = "ircs-dev",
    [string]$TaskId = "028-10-J",
    [string]$SliceName = "send-history-retention-cleanup",
    [string]$JobName = "",
    [int]$RetentionDays = 180,
    [int]$BatchSize = 50,
    [int]$MaxBatches = 5,
    [int]$OpsPort = 18135,
    [string]$OpsBaseUrl = "http://127.0.0.1:18135",
    [switch]$StartOpsPortForward,
    [switch]$KeepJob,
    [switch]$FailOnUnavailable
)

$ErrorActionPreference = "Stop"
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$opsPf = $null
$opsPfOut = Join-Path $env:TEMP "ircs-02810j-mail-ops-pf.out.log"
$opsPfErr = Join-Path $env:TEMP "ircs-02810j-mail-ops-pf.err.log"
$runId = ([Guid]::NewGuid().ToString("N")).Substring(0, 12)
$correlationPrefix = "codex-02810j-$runId"
$oldCorrelationId = "$correlationPrefix-old"
$freshCorrelationId = "$correlationPrefix-fresh"
$smokeStatus = "cleanup_smoke"
if ([string]::IsNullOrWhiteSpace($JobName)) {
    $JobName = "notification-mail-history-cleanup-02810j-$runId"
}
$jobYaml = Join-Path $env:TEMP "$JobName.yaml"

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
    } | ConvertTo-Json -Depth 18

    if ($Status -eq "FAILED") {
        exit 1
    }
    if ($Status -eq "UNAVAILABLE" -and $FailOnUnavailable) {
        exit 2
    }
    exit 0
}

function Sql-Literal {
    param([string]$Value)
    if ($null -eq $Value) {
        return "null"
    }
    return "'" + $Value.Replace("'", "''") + "'"
}

function Invoke-PostgresRows {
    param([string]$Sql)

    $value = $Sql | kubectl -n $Namespace exec -i postgres-0 -- psql -v ON_ERROR_STOP=1 -U postgres -d ircs -t -A 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed: $($value | Out-String)"
    }
    return @($value | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object { $_.Trim() })
}

function Invoke-PostgresScalar {
    param([string]$Sql)
    $rows = @(Invoke-PostgresRows $Sql)
    if ($rows.Count -eq 0) {
        return $null
    }
    return $rows[-1]
}

function Get-KubectlJson {
    param([string[]]$KubectlArgs)

    $raw = kubectl @KubectlArgs -o json 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "kubectl failed: $($raw | Out-String)"
    }
    return $raw | ConvertFrom-Json
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
        } catch {
        } finally {
            $client.Close()
        }
        Start-Sleep -Milliseconds 300
    }
    throw "Timed out waiting for $HostName`:$Port"
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

function Remove-SmokeRows {
    $prefix = Sql-Literal "$correlationPrefix%"
    Invoke-PostgresRows "delete from notification_mail_send_history where correlation_id like $prefix;" | Out-Null
}

function New-CleanupJobYaml {
    param([string]$Image)

    @"
apiVersion: batch/v1
kind: Job
metadata:
  name: $JobName
  namespace: $Namespace
  labels:
    app.kubernetes.io/part-of: ircs
    app.kubernetes.io/component: notification-mail-history-cleanup
    codex-task: "$TaskId"
spec:
  backoffLimit: 0
  activeDeadlineSeconds: 240
  template:
    metadata:
      labels:
        app.kubernetes.io/part-of: ircs
        app.kubernetes.io/component: notification-mail-history-cleanup
        codex-task: "$TaskId"
    spec:
      restartPolicy: Never
      imagePullSecrets:
        - name: harbor-secret
      containers:
        - name: app
          image: $Image
          imagePullPolicy: Always
          env:
            - name: SPRING_MAIN_WEB_APPLICATION_TYPE
              value: "none"
            - name: SPRING_MAIN_LAZY_INITIALIZATION
              value: "true"
            - name: SPRING_APPLICATION_NAME
              value: "ircs-notification-worker"
            - name: SPRING_RABBITMQ_HOST
              valueFrom:
                configMapKeyRef:
                  name: ircs-dev-app-config
                  key: RABBITMQ_HOST
            - name: SPRING_RABBITMQ_USERNAME
              value: "admin"
            - name: SPRING_RABBITMQ_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: ircs-dev-secrets
                  key: RABBITMQ_PASSWORD
            - name: APP_NOTIFICATION_LISTENER_ENABLED
              value: "false"
            - name: APP_NOTIFICATION_CONFIG_LISTENER_ENABLED
              value: "false"
            - name: APP_WORKER_AUDIT_ENABLED
              value: "false"
            - name: APP_MAIL_ENABLED
              value: "false"
            - name: APP_MAIL_SEND_HISTORY_ENABLED
              value: "false"
            - name: APP_MAIL_SEND_HISTORY_DATASOURCE_URL
              valueFrom:
                configMapKeyRef:
                  name: ircs-dev-app-config
                  key: DB_URL
            - name: APP_MAIL_SEND_HISTORY_USERNAME
              value: "postgres"
            - name: APP_MAIL_SEND_HISTORY_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: ircs-dev-secrets
                  key: DB_PASSWORD
            - name: APP_MAIL_SEND_HISTORY_CLEANUP_ENABLED
              value: "true"
            - name: APP_MAIL_SEND_HISTORY_CLEANUP_DRY_RUN
              value: "false"
            - name: APP_MAIL_SEND_HISTORY_CLEANUP_EXECUTE_ENABLED
              value: "true"
            - name: APP_MAIL_SEND_HISTORY_RETENTION_DAYS
              value: "$RetentionDays"
            - name: APP_MAIL_SEND_HISTORY_CLEANUP_STATUSES
              value: "$smokeStatus"
            - name: APP_MAIL_SEND_HISTORY_CLEANUP_BATCH_SIZE
              value: "$BatchSize"
            - name: APP_MAIL_SEND_HISTORY_CLEANUP_MAX_BATCHES
              value: "$MaxBatches"
            - name: APP_MAIL_SEND_HISTORY_CLEANUP_RATE_LIMIT_DELAY_MS
              value: "0"
            - name: APP_MAIL_SEND_HISTORY_CLEANUP_EXIT_ON_COMPLETE
              value: "true"
          resources:
            requests:
              cpu: 25m
              memory: 128Mi
            limits:
              cpu: 250m
              memory: 512Mi
"@
}

try {
    kubectl -n $Namespace get pod postgres-0 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "postgres-0 is unavailable"
    }

    $table = Invoke-PostgresScalar "select to_regclass('public.notification_mail_send_history');"
    if ($table -ne "notification_mail_send_history") {
        Finish-Smoke "UNAVAILABLE" "SEND_HISTORY_TABLE_MISSING" @{
            table = $table
            sent = $false
            passwordPrinted = $false
            recipientPrinted = $false
        }
    }

    $deployment = Get-KubectlJson @("-n", $Namespace, "get", "deployment", "ircs-notification-worker")
    $container = $deployment.spec.template.spec.containers | Where-Object { $_.name -eq "app" } | Select-Object -First 1
    if ($null -eq $container) {
        $container = $deployment.spec.template.spec.containers | Select-Object -First 1
    }
    $image = [string]$container.image
    Mark "send history table and notification-worker image located" 1 5

    Remove-SmokeRows
    $oldId = [Guid]::NewGuid().ToString()
    $freshId = [Guid]::NewGuid().ToString()
    $oldCorrelationSql = Sql-Literal $oldCorrelationId
    $freshCorrelationSql = Sql-Literal $freshCorrelationId
    $smokeStatusSql = Sql-Literal $smokeStatus
    $oldIdSql = Sql-Literal $oldId
    $freshIdSql = Sql-Literal $freshId
    Invoke-PostgresScalar @"
insert into notification_mail_send_history (
    id, created_at, updated_at, version, correlation_id, recipient, subject,
    template_code, delivery_mode, status
) values
    ($oldIdSql::uuid, now() - interval '$($RetentionDays + 2) days', now() - interval '$($RetentionDays + 2) days', 0, $oldCorrelationSql, 'codex@example.invalid', '028-10-J old fixture', 'mail/cleanup-smoke', 'sink', $smokeStatusSql),
    ($freshIdSql::uuid, now(), now(), 0, $freshCorrelationSql, 'codex@example.invalid', '028-10-J fresh fixture', 'mail/cleanup-smoke', 'sink', $smokeStatusSql);
select count(*) from notification_mail_send_history where correlation_id like $(Sql-Literal "$correlationPrefix%");
"@ | Out-Null
    Mark "temporary old and fresh send history rows inserted" 2 5

    New-CleanupJobYaml -Image $image | Set-Content -Path $jobYaml -Encoding utf8
    kubectl -n $Namespace delete job $JobName --ignore-not-found | Out-Null
    $applyOutput = kubectl apply -f $jobYaml 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "cleanup job apply failed: $($applyOutput | Out-String)"
    }
    $waitOutput = kubectl -n $Namespace wait --for=condition=complete "job/$JobName" --timeout=240s 2>&1
    if ($LASTEXITCODE -ne 0) {
        $logs = kubectl -n $Namespace logs "job/$JobName" --tail=80 2>&1
        Finish-Smoke "FAILED" "CLEANUP_JOB_DID_NOT_COMPLETE" @{
            job = $JobName
            waitOutput = ($waitOutput | Out-String)
            logsTail = ($logs | Out-String)
            sent = $false
            passwordPrinted = $false
            recipientPrinted = $false
        }
    }
    $jobLogs = kubectl -n $Namespace logs "job/$JobName" --tail=80 2>&1
    Mark "cleanup one-shot Job completed with low resource limits" 3 5

    $oldRemaining = [int](Invoke-PostgresScalar "select count(*) from notification_mail_send_history where correlation_id = $oldCorrelationSql;")
    $freshRemaining = [int](Invoke-PostgresScalar "select count(*) from notification_mail_send_history where correlation_id = $freshCorrelationSql;")
    if ($oldRemaining -ne 0 -or $freshRemaining -ne 1) {
        Finish-Smoke "FAILED" "CLEANUP_RETENTION_ASSERTION_FAILED" @{
            oldRemaining = $oldRemaining
            freshRemaining = $freshRemaining
            job = $JobName
            sent = $false
            passwordPrinted = $false
            recipientPrinted = $false
        }
    }
    Mark "old temporary row deleted and fresh temporary row retained by cutoff" 4 5

    $opsEvidence = @{
        queried = $false
        available = $false
    }
    if ($StartOpsPortForward) {
        $opsPf = Start-OpsPortForward
        $summary = Invoke-RestMethod -Method Get -Uri "$($OpsBaseUrl.TrimEnd('/'))/api/v1/ops/notification-mail-send-history/summary" -TimeoutSec 15
        $opsEvidence = @{
            queried = $true
            available = $true
            totalLast24h = $summary.totalLast24h
            sentLast24h = $summary.sentLast24h
            failedLast24h = $summary.failedLast24h
            skippedLast24h = $summary.skippedLast24h
        }
    }
    Mark "ops summary remains queryable or explicitly skipped" 5 5

    $freshRetainedAfterCleanup = ($freshRemaining -eq 1)
    Remove-SmokeRows
    $residue = [int](Invoke-PostgresScalar "select count(*) from notification_mail_send_history where correlation_id like $(Sql-Literal "$correlationPrefix%");")

    $details = @{
        job = $JobName
        image = $image
        retentionDays = $RetentionDays
        batchSize = $BatchSize
        maxBatches = $MaxBatches
        cleanupStatusFilter = $smokeStatus
        oldCorrelationId = $oldCorrelationId
        freshCorrelationId = $freshCorrelationId
        oldRemainingAfterCleanup = $oldRemaining
        freshRetainedAfterCleanup = $freshRetainedAfterCleanup
        finalSmokeResidue = $residue
        opsSendHistory = $opsEvidence
        jobLogsTail = ($jobLogs | Out-String)
        sent = $false
        passwordPrinted = $false
        recipientPrinted = $false
    }

    if ($residue -ne 0) {
        Finish-Smoke "FAILED" "SMOKE_ROW_CLEANUP_LEFT_RESIDUE" $details
    }
    Finish-Smoke "PASS" "SEND_HISTORY_RETENTION_CLEANUP_OK" $details
} catch {
    try { Remove-SmokeRows } catch {}
    Finish-Smoke "FAILED" "SEND_HISTORY_RETENTION_CLEANUP_FAILED" @{
        message = $_.Exception.Message
        stack = $_.ScriptStackTrace
        job = $JobName
        sent = $false
        passwordPrinted = $false
        recipientPrinted = $false
    }
} finally {
    if ($opsPf -and -not $opsPf.HasExited) {
        Stop-Process -Id $opsPf.Id -Force -ErrorAction SilentlyContinue
    }
    if (-not $KeepJob) {
        kubectl -n $Namespace delete job $JobName --ignore-not-found | Out-Null
    }
    Remove-Item -LiteralPath $jobYaml -ErrorAction SilentlyContinue
}
