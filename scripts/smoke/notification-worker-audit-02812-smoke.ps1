param(
    [string]$Namespace = "ircs-dev",
    [string]$RabbitBaseUrl = "http://127.0.0.1:19079",
    [int]$RabbitPort = 19079,
    [string]$OpsBaseUrl = "http://127.0.0.1:18130",
    [int]$OpsPort = 18130,
    [int]$WaitAttempts = 30,
    [int]$WaitSeconds = 2,
    [switch]$FailOnSkip
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$stamp = Get-Date -Format "yyyyMMddHHmmss"
$trace = "02812-notification-audit-$stamp"
$mailTo = "codex-smoke@example.invalid"
$mailSubject = "IRCS 02812 notification audit smoke $trace"
$mailContent = "IRCS 02812 notification audit smoke content $trace"

$rabbitPfOut = Join-Path $env:TEMP "ircs-02812-notification-rabbit-pf.out.log"
$rabbitPfErr = Join-Path $env:TEMP "ircs-02812-notification-rabbit-pf.err.log"
$opsPfOut = Join-Path $env:TEMP "ircs-02812-notification-ops-pf.out.log"
$opsPfErr = Join-Path $env:TEMP "ircs-02812-notification-ops-pf.err.log"
$rabbitPf = $null
$opsPf = $null
$baselineMailMessages = $null
$baselineDlqMessages = $null
$script:rabbitPassword = $null

function New-SmokeResult {
    param(
        [string]$Status,
        [string]$Reason,
        [hashtable]$Details = @{}
    )
    [ordered]@{
        task = "028-12"
        slice = "notification-worker-job-audit-live-smoke"
        status = $Status
        reason = $Reason
        checkedAt = (Get-Date).ToUniversalTime().ToString("o")
        namespace = $Namespace
        trace = $trace
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

function Test-PostgresAccess {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = "select 1;" | kubectl -n $Namespace exec -i postgres-0 -- psql -v ON_ERROR_STOP=1 -U postgres -d ircs -t -A 2>&1
    $exit = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($exit -ne 0) {
        Finish-Smoke "SKIPPED" "POSTGRES_UNAVAILABLE" @{
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

function Get-RabbitPassword {
    if ([string]::IsNullOrWhiteSpace($script:rabbitPassword)) {
        $script:rabbitPassword = Get-SecretValue "RABBITMQ_PASSWORD"
    }
    return $script:rabbitPassword
}

function New-RabbitHeaders {
    $rabbitPassword = Get-RabbitPassword
    $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("admin:$($rabbitPassword)"))
    return @{ Authorization = "Basic $basic" }
}

function Get-RabbitQueue {
    param([string]$QueueName)
    Invoke-RestMethod `
        -Method Get `
        -Headers (New-RabbitHeaders) `
        -Uri "$RabbitBaseUrl/api/queues/%2F/$QueueName" `
        -TimeoutSec 10
}

function Clear-RabbitQueue {
    param([string]$QueueName)
    Invoke-RestMethod `
        -Method Delete `
        -Headers (New-RabbitHeaders) `
        -Uri "$RabbitBaseUrl/api/queues/%2F/$QueueName/contents" `
        -TimeoutSec 10 | Out-Null
}

function Invoke-RabbitPublish {
    param([string]$PayloadBase64)
    $body = @{
        properties = @{
            content_type = "application/x-java-serialized-object"
            message_id = $trace
        }
        routing_key = "notification.mail"
        payload = $PayloadBase64
        payload_encoding = "base64"
    } | ConvertTo-Json -Depth 8 -Compress
    Invoke-RestMethod `
        -Method Post `
        -Headers (New-RabbitHeaders) `
        -ContentType "application/json" `
        -Body $body `
        -Uri "$RabbitBaseUrl/api/exchanges/%2F/x.notification/publish" `
        -TimeoutSec 10
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

function New-MailPayloadBase64 {
    $contractsClass = Join-Path $repoRoot "shared\ircs-contracts\build\classes\java\main\com\prodigalgal\ircs\contracts\notification\MailMessageDTO.class"
    if (-not (Test-Path $contractsClass)) {
        & (Join-Path $repoRoot "gradlew.bat") :shared:ircs-contracts:classes | Out-Host
        if ($LASTEXITCODE -ne 0) {
            Finish-Smoke "SKIPPED" "CONTRACTS_COMPILE_UNAVAILABLE" @{ exitCode = $LASTEXITCODE }
        }
    }

    $tmpDir = Join-Path $env:TEMP ("ircs-02812-mail-serializer-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Path $tmpDir | Out-Null
    $sourceFile = Join-Path $tmpDir "SerializeMailMessage.java"
    $serializerSource = @'
import com.prodigalgal.ircs.contracts.notification.MailMessageDTO;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.Base64;

public class SerializeMailMessage {
    public static void main(String[] args) throws Exception {
        MailMessageDTO message = MailMessageDTO.builder()
                .to(args[0])
                .subject(args[1])
                .content(args[2])
                .html(false)
                .build();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(message);
        }
        System.out.print(Base64.getEncoder().encodeToString(bytes.toByteArray()));
    }
}
'@
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($sourceFile, $serializerSource, $utf8NoBom)

    $contractsClasses = Join-Path $repoRoot "shared\ircs-contracts\build\classes\java\main"
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & javac -encoding UTF-8 -cp $contractsClasses -d $tmpDir $sourceFile
    $javacExit = $LASTEXITCODE
    if ($javacExit -eq 0) {
        $payload = & java -cp "$tmpDir;$contractsClasses" SerializeMailMessage $mailTo $mailSubject $mailContent
        $javaExit = $LASTEXITCODE
    } else {
        $payload = ""
        $javaExit = 1
    }
    $ErrorActionPreference = $previous
    Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue

    if ($javacExit -ne 0 -or $javaExit -ne 0 -or [string]::IsNullOrWhiteSpace($payload)) {
        Finish-Smoke "SKIPPED" "JAVA_SERIALIZER_UNAVAILABLE" @{
            javacExit = $javacExit
            javaExit = $javaExit
        }
    }
    return $payload.Trim()
}

function Wait-ForAuditEvent {
    for ($i = 0; $i -lt $WaitAttempts; $i++) {
        $count = Invoke-SqlScalar @"
select count(*)
  from worker_job_audit_events
 where correlation_id = $(Sql-Literal $trace)
   and job_source = 'ircs-notification-worker'
   and job_type = 'queue_consumer'
   and job_name = 'notification.mail'
   and status = 'succeeded';
"@
        if ($count -eq "1") {
            return $true
        }
        Start-Sleep -Seconds $WaitSeconds
    }
    return $false
}

function Wait-ForRabbitQueuesEmpty {
    for ($i = 0; $i -lt $WaitAttempts; $i++) {
        $queue = Get-RabbitQueue "q.notification.mail"
        $dlq = Get-RabbitQueue "q.notification.mail.dlq"
        if ([int]$queue.messages -eq 0 -and [int]$dlq.messages -eq 0) {
            return @{
                mailMessages = [int]$queue.messages
                dlqMessages = [int]$dlq.messages
            }
        }
        Start-Sleep -Seconds $WaitSeconds
    }
    $queue = Get-RabbitQueue "q.notification.mail"
    $dlq = Get-RabbitQueue "q.notification.mail.dlq"
    return @{
        mailMessages = [int]$queue.messages
        dlqMessages = [int]$dlq.messages
    }
}

function Test-OpsQuery {
    $encodedTrace = [uri]::EscapeDataString($trace)
    $page = Invoke-RestMethod `
        -Method Get `
        -Uri "$OpsBaseUrl/api/v1/ops/worker-job-audit?size=5&jobSource=ircs-notification-worker&status=succeeded&correlationId=$encodedTrace" `
        -TimeoutSec 10
    return ($page.totalElements -eq 1 -and $page.content[0].correlationId -eq $trace)
}

try {
    Test-KubectlAccess
    Test-PostgresAccess
    $table = Invoke-SqlScalar "select to_regclass('public.worker_job_audit_events');"
    if ($table -ne "worker_job_audit_events") {
        Finish-Smoke "SKIPPED" "WORKER_JOB_AUDIT_TABLE_MISSING" @{ table = $table }
    }

    $rabbitPf = Start-PortForward "svc/rabbitmq-svc" $RabbitPort 15672 $rabbitPfOut $rabbitPfErr
    $opsPf = Start-PortForward "svc/ircs-ops-service" $OpsPort 8080 $opsPfOut $opsPfErr

    $queue = Get-RabbitQueue "q.notification.mail"
    $dlq = Get-RabbitQueue "q.notification.mail.dlq"
    $baselineMailMessages = [int]$queue.messages
    $baselineDlqMessages = [int]$dlq.messages
    if ($baselineMailMessages -ne 0 -or $baselineDlqMessages -ne 0) {
        Finish-Smoke "SKIPPED" "MAIL_QUEUE_NOT_EMPTY" @{
            mailMessages = $baselineMailMessages
            dlqMessages = $baselineDlqMessages
        }
    }
    if ([int]$queue.consumers -lt 1) {
        Finish-Smoke "FAILED" "NOTIFICATION_CONSUMER_MISSING" @{
            consumers = [int]$queue.consumers
        }
    }
    Mark "notification worker has q.notification.mail consumer" 1 5

    Exec-Sql "delete from worker_job_audit_events where correlation_id = $(Sql-Literal $trace);"
    $payloadBase64 = New-MailPayloadBase64
    $publishResult = Invoke-RabbitPublish $payloadBase64
    if (-not $publishResult.routed) {
        Finish-Smoke "FAILED" "MAIL_FIXTURE_NOT_ROUTED" @{ publishResult = $publishResult }
    }
    Mark "live smoke publishes MailMessageDTO fixture with Rabbit message_id" 2 5

    if (-not (Wait-ForAuditEvent)) {
        Finish-Smoke "FAILED" "AUDIT_EVENT_NOT_WRITTEN" @{
            trace = $trace
            waitAttempts = $WaitAttempts
            waitSeconds = $WaitSeconds
        }
    }
    Mark "notification worker writes succeeded worker_job_audit_events with correlation_id" 3 5

    if (-not (Test-OpsQuery)) {
        Finish-Smoke "FAILED" "OPS_WORKER_JOB_AUDIT_QUERY_MISSING_EVENT" @{ trace = $trace }
    }
    Mark "ops worker-job audit query exposes notification worker event" 4 5

    Exec-Sql "delete from worker_job_audit_events where correlation_id = $(Sql-Literal $trace);"
    $residue = Invoke-SqlScalar "select count(*) from worker_job_audit_events where correlation_id = $(Sql-Literal $trace);"
    $queueState = Wait-ForRabbitQueuesEmpty
    if ($residue -ne "0" -or [int]$queueState.mailMessages -ne 0 -or [int]$queueState.dlqMessages -ne 0) {
        Finish-Smoke "FAILED" "SMOKE_RESIDUE_REMAINED" @{
            residue = $residue
            mailMessages = [int]$queueState.mailMessages
            dlqMessages = [int]$queueState.dlqMessages
        }
    }
    Mark "live smoke cleanup leaves no DB residue and mail queues empty" 5 5

    Finish-Smoke "PASSED" "NOTIFICATION_WORKER_AUDIT_LIVE_SMOKE_PASSED" @{
        mailMessages = [int]$queueState.mailMessages
        dlqMessages = [int]$queueState.dlqMessages
        residue = $residue
    }
} catch {
    Finish-Smoke "FAILED" "UNEXPECTED_ERROR" @{
        message = $_.Exception.Message
        stack = $_.ScriptStackTrace
    }
} finally {
    try {
        Exec-Sql "delete from worker_job_audit_events where correlation_id = $(Sql-Literal $trace);"
    } catch {
    }
    if ($baselineMailMessages -eq 0 -and $baselineDlqMessages -eq 0) {
        try {
            $queue = if ($rabbitPf -and -not $rabbitPf.HasExited) { Get-RabbitQueue "q.notification.mail" } else { $null }
            $dlq = if ($rabbitPf -and -not $rabbitPf.HasExited) { Get-RabbitQueue "q.notification.mail.dlq" } else { $null }
            if ($queue -and [int]$queue.messages -gt 0) {
                Clear-RabbitQueue "q.notification.mail"
            }
            if ($dlq -and [int]$dlq.messages -gt 0) {
                Clear-RabbitQueue "q.notification.mail.dlq"
            }
        } catch {
        }
    }
    if ($rabbitPf -and -not $rabbitPf.HasExited) {
        Stop-Process -Id $rabbitPf.Id -Force
    }
    if ($opsPf -and -not $opsPf.HasExited) {
        Stop-Process -Id $opsPf.Id -Force
    }
}
