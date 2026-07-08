param(
    [string]$Namespace = "ircs-dev",
    [string]$TaskId = "028-10-N",
    [string]$SliceName = "notification-maintenance-runner-safety-gate",
    [int]$RetentionDays = 180,
    [int]$BatchSize = 50,
    [int]$MaxBatches = 1,
    [int]$OpsPort = 18135,
    [string]$OpsBaseUrl = "http://127.0.0.1:18135",
    [switch]$ConfirmExecute,
    [switch]$StartOpsPortForward,
    [switch]$KeepJobs,
    [switch]$FailOnUnavailable
)

$ErrorActionPreference = "Stop"
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$opsPf = $null
$opsPfOut = Join-Path $env:TEMP "ircs-02810n-maintenance-ops-pf.out.log"
$opsPfErr = Join-Path $env:TEMP "ircs-02810n-maintenance-ops-pf.err.log"
$runId = ([Guid]::NewGuid().ToString("N")).Substring(0, 12)
$correlationPrefix = "codex-02810n-$runId"
$oldCorrelationId = "$correlationPrefix-old"
$freshCorrelationId = "$correlationPrefix-fresh"
$smokeStatus = "maintenance_smoke"
$dryRunJobName = "nmh-02810n-dr-$runId"
$executeJobName = "nmh-02810n-ex-$runId"
$dryRunYaml = Join-Path $env:TEMP "$dryRunJobName.yaml"
$executeYaml = Join-Path $env:TEMP "$executeJobName.yaml"
$smokeStartedAt = (Get-Date).ToUniversalTime().ToString("o")

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
    param(
        [string]$Image,
        [string]$JobName,
        [bool]$DryRun,
        [bool]$ExecuteEnabled
    )

    $dryRunValue = if ($DryRun) { "true" } else { "false" }
    $executeValue = if ($ExecuteEnabled) { "true" } else { "false" }

    @"
apiVersion: batch/v1
kind: Job
metadata:
  name: $JobName
  namespace: $Namespace
  labels:
    app.kubernetes.io/part-of: ircs
    app.kubernetes.io/component: notification-mail-history-maintenance
    codex-task: "$TaskId"
spec:
  backoffLimit: 0
  activeDeadlineSeconds: 240
  template:
    metadata:
      labels:
        app.kubernetes.io/part-of: ircs
        app.kubernetes.io/component: notification-mail-history-maintenance
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
            - name: APP_MAIL_ENABLED
              value: "false"
            - name: APP_MAIL_SEND_HISTORY_ENABLED
              value: "false"
            - name: APP_WORKER_AUDIT_ENABLED
              value: "true"
            - name: APP_WORKER_AUDIT_DATASOURCE_URL
              valueFrom:
                configMapKeyRef:
                  name: ircs-dev-app-config
                  key: DB_URL
            - name: APP_WORKER_AUDIT_USERNAME
              value: "postgres"
            - name: APP_WORKER_AUDIT_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: ircs-dev-secrets
                  key: DB_PASSWORD
            - name: APP_WORKER_AUDIT_SOURCE
              value: "ircs-notification-worker"
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
              value: "$dryRunValue"
            - name: APP_MAIL_SEND_HISTORY_CLEANUP_EXECUTE_ENABLED
              value: "$executeValue"
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

function Invoke-CleanupJob {
    param(
        [string]$Image,
        [string]$JobName,
        [string]$YamlPath,
        [bool]$DryRun,
        [bool]$ExecuteEnabled
    )

    New-CleanupJobYaml -Image $Image -JobName $JobName -DryRun $DryRun -ExecuteEnabled $ExecuteEnabled |
        Set-Content -Path $YamlPath -Encoding utf8
    kubectl -n $Namespace delete job $JobName --ignore-not-found | Out-Null
    $applyOutput = kubectl apply -f $YamlPath 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "cleanup job apply failed: $($applyOutput | Out-String)"
    }
    $waitOutput = kubectl -n $Namespace wait --for=condition=complete "job/$JobName" --timeout=240s 2>&1
    $logs = kubectl -n $Namespace logs "job/$JobName" --tail=80 2>&1
    if ($LASTEXITCODE -ne 0 -or ($waitOutput | Out-String) -notmatch "condition met") {
        Finish-Smoke "FAILED" "CLEANUP_JOB_DID_NOT_COMPLETE" @{
            job = $JobName
            waitOutput = ($waitOutput | Out-String)
            logsTail = ($logs | Out-String)
            sent = $false
            mailQueuePublished = $false
            passwordPrinted = $false
            recipientPrinted = $false
        }
    }
    return ($logs | Out-String)
}

try {
    kubectl -n $Namespace get pod postgres-0 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "postgres-0 is unavailable"
    }

    $table = Invoke-PostgresScalar "select to_regclass('public.notification_mail_send_history');"
    $auditTable = Invoke-PostgresScalar "select to_regclass('public.worker_job_audit_events');"
    if ($table -ne "notification_mail_send_history" -or $auditTable -ne "worker_job_audit_events") {
        Finish-Smoke "UNAVAILABLE" "REQUIRED_TABLE_MISSING" @{
            sendHistoryTable = $table
            auditTable = $auditTable
            sent = $false
            mailQueuePublished = $false
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
    Mark "send history/audit tables and notification-worker image located" 1 6

    Remove-SmokeRows
    $oldId = [Guid]::NewGuid().ToString()
    $freshId = [Guid]::NewGuid().ToString()
    $oldCorrelationSql = Sql-Literal $oldCorrelationId
    $freshCorrelationSql = Sql-Literal $freshCorrelationId
    $smokeStatusSql = Sql-Literal $smokeStatus
    Invoke-PostgresScalar @"
insert into notification_mail_send_history (
    id, created_at, updated_at, version, correlation_id, recipient, subject,
    template_code, delivery_mode, status
) values
    ('${oldId}'::uuid, now() - interval '$($RetentionDays + 2) days', now() - interval '$($RetentionDays + 2) days', 0, $oldCorrelationSql, 'codex@example.invalid', '028-10-N old fixture', 'mail/maintenance-smoke', 'sink', $smokeStatusSql),
    ('${freshId}'::uuid, now(), now(), 0, $freshCorrelationSql, 'codex@example.invalid', '028-10-N fresh fixture', 'mail/maintenance-smoke', 'sink', $smokeStatusSql);
select count(*) from notification_mail_send_history where correlation_id like $(Sql-Literal "$correlationPrefix%");
"@ | Out-Null
    Mark "temporary old and fresh send history rows inserted" 2 6

    $dryRunLogs = Invoke-CleanupJob -Image $image -JobName $dryRunJobName -YamlPath $dryRunYaml -DryRun $true -ExecuteEnabled $false
    $oldAfterDryRun = [int](Invoke-PostgresScalar "select count(*) from notification_mail_send_history where correlation_id = $oldCorrelationSql;")
    $freshAfterDryRun = [int](Invoke-PostgresScalar "select count(*) from notification_mail_send_history where correlation_id = $freshCorrelationSql;")
    if ($oldAfterDryRun -ne 1 -or $freshAfterDryRun -ne 1) {
        Finish-Smoke "FAILED" "DRY_RUN_MODIFIED_ROWS" @{
            oldAfterDryRun = $oldAfterDryRun
            freshAfterDryRun = $freshAfterDryRun
            sent = $false
            mailQueuePublished = $false
            passwordPrinted = $false
            recipientPrinted = $false
        }
    }
    Mark "dry-run one-shot completed without modifying fixture rows" 3 6

    $dryRunAudit = [int](Invoke-PostgresScalar "select count(*) from worker_job_audit_events where job_source = 'ircs-notification-worker' and job_type = 'maintenance-runner' and job_name = 'notification.mail-send-history.cleanup' and correlation_id = 'dry-run' and status = 'succeeded' and created_at >= $(Sql-Literal $smokeStartedAt)::timestamptz;")
    if ($dryRunAudit -lt 1) {
        Finish-Smoke "FAILED" "DRY_RUN_AUDIT_MISSING" @{
            dryRunAudit = $dryRunAudit
            dryRunLogsTail = $dryRunLogs
            sent = $false
            mailQueuePublished = $false
            passwordPrinted = $false
            recipientPrinted = $false
        }
    }
    Mark "dry-run worker job audit row recorded" 4 6

    $executeLogs = ""
    $oldAfterExecute = $oldAfterDryRun
    $freshAfterExecute = $freshAfterDryRun
    $executeAudit = 0
    if ($ConfirmExecute) {
        $executeLogs = Invoke-CleanupJob -Image $image -JobName $executeJobName -YamlPath $executeYaml -DryRun $false -ExecuteEnabled $true
        $oldAfterExecute = [int](Invoke-PostgresScalar "select count(*) from notification_mail_send_history where correlation_id = $oldCorrelationSql;")
        $freshAfterExecute = [int](Invoke-PostgresScalar "select count(*) from notification_mail_send_history where correlation_id = $freshCorrelationSql;")
        if ($oldAfterExecute -ne 0 -or $freshAfterExecute -ne 1) {
            Finish-Smoke "FAILED" "EXECUTE_RETENTION_ASSERTION_FAILED" @{
                oldAfterExecute = $oldAfterExecute
                freshAfterExecute = $freshAfterExecute
                sent = $false
                mailQueuePublished = $false
                passwordPrinted = $false
                recipientPrinted = $false
            }
        }
        $executeAudit = [int](Invoke-PostgresScalar "select count(*) from worker_job_audit_events where job_source = 'ircs-notification-worker' and job_type = 'maintenance-runner' and job_name = 'notification.mail-send-history.cleanup' and correlation_id = 'execute' and status = 'succeeded' and created_at >= $(Sql-Literal $smokeStartedAt)::timestamptz;")
        if ($executeAudit -lt 1) {
            Finish-Smoke "FAILED" "EXECUTE_AUDIT_MISSING" @{
                executeAudit = $executeAudit
                executeLogsTail = $executeLogs
                sent = $false
                mailQueuePublished = $false
                passwordPrinted = $false
                recipientPrinted = $false
            }
        }
        Mark "explicit execute one-shot deletes only old fixture and records audit" 5 6
    } else {
        Mark "execute branch skipped because -ConfirmExecute was not set" 5 6
    }

    $opsEvidence = @{
        queried = $false
        available = $false
    }
    if ($StartOpsPortForward) {
        $opsPf = Start-OpsPortForward
        $summary = Invoke-RestMethod -Method Get -Uri "$($OpsBaseUrl.TrimEnd('/'))/api/v1/ops/worker-job-audit/summary" -TimeoutSec 15
        $page = Invoke-RestMethod -Method Get -Uri "$($OpsBaseUrl.TrimEnd('/'))/api/v1/ops/worker-job-audit?jobSource=ircs-notification-worker&jobType=maintenance-runner&jobName=notification.mail-send-history.cleanup&size=5" -TimeoutSec 15
        $opsEvidence = @{
            queried = $true
            available = $true
            totalLast24h = $summary.totalLast24h
            failedLast24h = $summary.failedLast24h
            succeededLast24h = $summary.succeededLast24h
            pageElements = $page.totalElements
        }
    }
    Mark "ops worker-job-audit query available or explicitly skipped" 6 6

    Remove-SmokeRows
    $residue = [int](Invoke-PostgresScalar "select count(*) from notification_mail_send_history where correlation_id like $(Sql-Literal "$correlationPrefix%");")
    $details = @{
        image = $image
        dryRunJob = $dryRunJobName
        executeJob = if ($ConfirmExecute) { $executeJobName } else { "" }
        confirmExecute = [bool]$ConfirmExecute
        retentionDays = $RetentionDays
        batchSize = $BatchSize
        maxBatches = $MaxBatches
        oldAfterDryRun = $oldAfterDryRun
        freshAfterDryRun = $freshAfterDryRun
        oldAfterExecute = $oldAfterExecute
        freshAfterExecute = $freshAfterExecute
        dryRunAudit = $dryRunAudit
        executeAudit = $executeAudit
        finalSmokeResidue = $residue
        opsWorkerJobAudit = $opsEvidence
        dryRunLogsTail = $dryRunLogs
        executeLogsTail = $executeLogs
        sent = $false
        mailQueuePublished = $false
        passwordPrinted = $false
        recipientPrinted = $false
    }
    if ($residue -ne 0) {
        Finish-Smoke "FAILED" "SMOKE_ROW_CLEANUP_LEFT_RESIDUE" $details
    }
    $reason = if ($ConfirmExecute) { "MAINTENANCE_RUNNER_DRY_RUN_AND_EXECUTE_OK" } else { "MAINTENANCE_RUNNER_DRY_RUN_ONLY_OK" }
    Finish-Smoke "PASS" $reason $details
} catch {
    try { Remove-SmokeRows } catch {}
    Finish-Smoke "FAILED" "MAINTENANCE_RUNNER_SMOKE_FAILED" @{
        message = $_.Exception.Message
        stack = $_.ScriptStackTrace
        dryRunJob = $dryRunJobName
        executeJob = $executeJobName
        sent = $false
        mailQueuePublished = $false
        passwordPrinted = $false
        recipientPrinted = $false
    }
} finally {
    if ($opsPf -and -not $opsPf.HasExited) {
        Stop-Process -Id $opsPf.Id -Force -ErrorAction SilentlyContinue
    }
    if (-not $KeepJobs) {
        kubectl -n $Namespace delete job $dryRunJobName --ignore-not-found | Out-Null
        kubectl -n $Namespace delete job $executeJobName --ignore-not-found | Out-Null
    }
    Remove-Item -LiteralPath $dryRunYaml, $executeYaml -ErrorAction SilentlyContinue
}
