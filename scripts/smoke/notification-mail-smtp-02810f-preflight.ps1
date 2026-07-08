param(
    [string]$TaskId = "028-10-F",
    [string]$SliceName = "smtp-staging-live-preflight",
    [string]$Namespace = "ircs-dev",
    [string]$CredentialBaseUrl = "http://127.0.0.1:18091",
    [int]$CredentialPort = 18091,
    [string]$OpsBaseUrl = "http://127.0.0.1:18131",
    [int]$OpsPort = 18131,
    [string]$RabbitBaseUrl = "http://127.0.0.1:19079",
    [int]$RabbitPort = 19079,
    [string]$CredentialToken = $env:APP_MAIL_CREDENTIAL_SERVICE_TOKEN,
    [string]$TestRecipient = "",
    [switch]$AllowSend,
    [switch]$UseKubernetesSecretToken,
    [switch]$StartCredentialPortForward,
    [switch]$StartOpsPortForward,
    [switch]$StartRabbitPortForward,
    [int]$WaitAttempts = 30,
    [int]$WaitSeconds = 2,
    [switch]$FailOnSkip
)

$ErrorActionPreference = "Stop"

$stamp = Get-Date -Format "yyyyMMddHHmmss"
$trace = ($TaskId.ToLowerInvariant() -replace '[^a-z0-9]+', '') + "-smtp-smoke-$stamp"
$credentialPfOut = Join-Path $env:TEMP "ircs-02810f-mail-credential-pf.out.log"
$credentialPfErr = Join-Path $env:TEMP "ircs-02810f-mail-credential-pf.err.log"
$opsPfOut = Join-Path $env:TEMP "ircs-02810f-mail-ops-pf.out.log"
$opsPfErr = Join-Path $env:TEMP "ircs-02810f-mail-ops-pf.err.log"
$rabbitPfOut = Join-Path $env:TEMP "ircs-02810f-mail-rabbit-pf.out.log"
$rabbitPfErr = Join-Path $env:TEMP "ircs-02810f-mail-rabbit-pf.err.log"
$credentialPf = $null
$opsPf = $null
$rabbitPf = $null
$script:patchedDeployment = $false
$script:restoreEnv = @{}
$script:restoreResult = $null
$markTotal = if ($TaskId -eq "028-10-I" -or $TaskId -eq "028-10-L") { 6 } else { 5 }

function New-SmokeResult {
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
        trace = $trace
        details = $Details
    } | ConvertTo-Json -Depth 16
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

function Mask-Email {
    param([AllowNull()][string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $null
    }
    $trimmed = $Value.Trim()
    if ($trimmed -match "<([^>]+)>") {
        $email = $Matches[1]
        return $trimmed.Replace($email, (Mask-Email $email))
    }
    $parts = $trimmed -split "@", 2
    if ($parts.Count -ne 2) {
        return "[redacted]"
    }
    $local = $parts[0]
    $domain = $parts[1]
    $safeLocal = if ($local.Length -le 2) { $local.Substring(0, 1) + "***" } else { $local.Substring(0, 2) + "***" }
    return "$safeLocal@$domain"
}

function Extract-EmailAddress {
    param([AllowNull()][string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $null
    }
    $trimmed = $Value.Trim()
    if ($trimmed -match "<([^>]+)>") {
        return $Matches[1].Trim().ToLowerInvariant()
    }
    return $trimmed.ToLowerInvariant()
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
            sent = $false
        }
    }
}

function Get-KubectlJson {
    param([string[]]$KubectlArgs)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $raw = kubectl @KubectlArgs -o json 2>&1
    $exit = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($exit -ne 0) {
        Finish-Smoke "SKIPPED" "KUBERNETES_QUERY_FAILED" @{
            args = ($KubectlArgs -join " ")
            exitCode = $exit
            output = (($raw | Out-String).Trim())
            sent = $false
        }
    }
    return ($raw | ConvertFrom-Json)
}

function Get-ConfigMapValue {
    param([string]$Key)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $value = kubectl -n $Namespace get configmap ircs-dev-app-config -o jsonpath="{.data.$Key}" 2>&1
    $exit = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($exit -ne 0) {
        return $null
    }
    $text = (($value | Out-String).Trim())
    if ([string]::IsNullOrWhiteSpace($text)) {
        return $null
    }
    return $text
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
            sent = $false
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
            sent = $false
        }
    }
    return $pf
}

function Invoke-SqlScalar {
    param([string]$Sql)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $rows = $Sql | kubectl -n $Namespace exec -i postgres-0 -- psql -v ON_ERROR_STOP=1 -U postgres -d ircs -t -A 2>&1
    $exit = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($exit -ne 0) {
        Finish-Smoke "SKIPPED" "POSTGRES_QUERY_FAILED" @{
            exitCode = $exit
            output = (($rows | Out-String).Trim())
            sent = $false
        }
    }
    $value = $rows | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Last 1
    if ($null -eq $value) {
        return $null
    }
    return $value.Trim()
}

function Invoke-SqlJson {
    param([string]$Sql)
    $raw = Invoke-SqlScalar $Sql
    if ([string]::IsNullOrWhiteSpace($raw)) {
        return $null
    }
    return $raw | ConvertFrom-Json
}

function Get-EnvValue {
    param(
        [object]$Deployment,
        [string]$Name
    )
    $container = $Deployment.spec.template.spec.containers | Where-Object { $_.name -eq "app" } | Select-Object -First 1
    $env = $container.env | Where-Object { $_.name -eq $Name } | Select-Object -First 1
    if ($null -eq $env) {
        return $null
    }
    if ($null -ne $env.value) {
        return [string]$env.value
    }
    if ($null -ne $env.valueFrom.configMapKeyRef) {
        return Get-ConfigMapValue ([string]$env.valueFrom.configMapKeyRef.key)
    }
    if ($null -ne $env.valueFrom.secretKeyRef) {
        return "[secret:$($env.valueFrom.secretKeyRef.key)]"
    }
    return $null
}

function Get-EnvSource {
    param(
        [object]$Deployment,
        [string]$Name
    )
    $container = $Deployment.spec.template.spec.containers | Where-Object { $_.name -eq "app" } | Select-Object -First 1
    $env = $container.env | Where-Object { $_.name -eq $Name } | Select-Object -First 1
    if ($null -eq $env) {
        return "missing"
    }
    if ($null -ne $env.value) {
        return "literal"
    }
    if ($null -ne $env.valueFrom.configMapKeyRef) {
        return "configMap:$($env.valueFrom.configMapKeyRef.name)/$($env.valueFrom.configMapKeyRef.key)"
    }
    if ($null -ne $env.valueFrom.secretKeyRef) {
        return "secret:$($env.valueFrom.secretKeyRef.name)/$($env.valueFrom.secretKeyRef.key)"
    }
    return "unknown"
}

function Test-SafeRecipient {
    param([string]$Recipient)
    if ([string]::IsNullOrWhiteSpace($Recipient)) {
        return $false
    }
    $normalized = $Recipient.Trim().ToLowerInvariant()
    if ($normalized -match "@example\.(com|net|org|invalid|test)$") {
        return $false
    }
    if ($normalized -match "(\+|\.|-)test" -or $normalized -match "(^|[._+-])codex" -or $normalized -match "(^|[._+-])smoke") {
        return $true
    }
    if ($normalized -match "@(mailosaur\.io|mailinator\.com|ethereal\.email)$") {
        return $true
    }
    return $false
}

function Get-CredentialToken {
    if ($UseKubernetesSecretToken -and [string]::IsNullOrWhiteSpace($script:CredentialToken)) {
        $script:CredentialToken = Get-CredentialServiceSecretToken
    }
    return $script:CredentialToken
}

function Get-MailCredentialLeaseEvidence {
    $token = Get-CredentialToken
    if ([string]::IsNullOrWhiteSpace($token)) {
        return @{
            available = $false
            reason = "CREDENTIAL_TOKEN_MISSING"
            hint = "pass -UseKubernetesSecretToken or set APP_MAIL_CREDENTIAL_SERVICE_TOKEN"
        }
    }
    $leaseUrl = "$($CredentialBaseUrl.TrimEnd('/'))/internal/credentials/providers/MAIL/leases?requiredPayloadKey=username&limit=1"
    try {
        $response = Invoke-RestMethod -Method Get -Uri $leaseUrl -Headers @{
            "X-IRCS-INTERNAL-TOKEN" = $token
        } -TimeoutSec 20
    } catch {
        return @{
            available = $false
            reason = "CREDENTIAL_SERVICE_UNAVAILABLE"
            error = $_.Exception.Message
        }
    }
    $leases = @($response)
    if ($leases.Count -eq 0) {
        return @{
            available = $false
            reason = "NO_ENABLED_MAIL_CREDENTIAL"
            leaseCount = 0
        }
    }
    $lease = $leases | Where-Object {
        $null -ne $_.secretPayload `
            -and -not [string]::IsNullOrWhiteSpace([string]$_.secretPayload.username) `
            -and -not [string]::IsNullOrWhiteSpace([string]$_.secretPayload.password)
    } | Select-Object -First 1
    if ($null -eq $lease) {
        $payloadKeys = @()
        if ($leases[0].secretPayload) {
            $payloadKeys = @($leases[0].secretPayload.PSObject.Properties.Name | Sort-Object)
        }
        return @{
            available = $false
            reason = "MAIL_CREDENTIAL_INCOMPLETE"
            leaseCount = $leases.Count
            payloadKeys = $payloadKeys
        }
    }
    return @{
        available = $true
        credentialId = $lease.id
        provider = $lease.provider
        name = $lease.name
        usernameMasked = Mask-Email ([string]$lease.secretPayload.username)
        payloadKeys = @($lease.secretPayload.PSObject.Properties.Name | Sort-Object)
        passwordPresent = $true
        passwordPrinted = $false
    }
}

function Get-MailCredentialSqlEvidence {
    $table = Invoke-SqlScalar "select to_regclass('public.sys_credentials');"
    if ($table -ne "sys_credentials") {
        return @{
            table = $table
            available = $false
            reason = "SYS_CREDENTIALS_TABLE_MISSING"
        }
    }
    $sql = @"
select coalesce(jsonb_agg(row_to_json(t)), '[]'::jsonb)::text
from (
    select id::text,
           name,
           enabled,
           priority,
           payload ? 'username' as has_username,
           payload ? 'password' as has_password,
           case when payload ? 'username' then regexp_replace(payload ->> 'username', '(^.{1,2}).*(@.*$)', '\1***\2') else null end as username_masked
      from sys_credentials
     where provider = 'MAIL'
     order by enabled desc, priority desc nulls last, created_at asc
     limit 5
) t;
"@
    $rows = Invoke-SqlJson $sql
    return @{
        table = $table
        available = ($rows.Count -gt 0)
        rows = @($rows)
        passwordPrinted = $false
    }
}

function Get-SendHistoryEvidence {
    $table = Invoke-SqlScalar "select to_regclass('public.notification_mail_send_history');"
    $recentCount = $null
    if ($table -eq "notification_mail_send_history") {
        $recentCount = Invoke-SqlScalar "select count(*) from notification_mail_send_history where created_at >= now() - interval '24 hours';"
    }
    return @{
        table = $table
        present = ($table -eq "notification_mail_send_history")
        recent24hCount = $recentCount
    }
}

function Test-OpsSendHistoryQuery {
    param([string]$CorrelationId)
    $encodedTrace = [uri]::EscapeDataString($CorrelationId)
    try {
        $page = Invoke-RestMethod `
            -Method Get `
            -Uri "$OpsBaseUrl/api/v1/ops/notification-mail-send-history?size=5&correlationId=$encodedTrace" `
            -TimeoutSec 10
        return @{
            available = $true
            totalElements = $page.totalElements
            firstStatus = if ($page.content.Count -gt 0) { $page.content[0].status } else { $null }
            firstRecipientMasked = if ($page.content.Count -gt 0) { Mask-Email $page.content[0].recipient } else { $null }
            recipientPrinted = $false
        }
    } catch {
        return @{
            available = $false
            error = $_.Exception.Message
        }
    }
}

function Get-OpsSendHistoryEvidence {
    if (-not $StartOpsPortForward) {
        return @{
            queried = $false
            reason = "START_OPS_PORT_FORWARD_NOT_SET"
        }
    }
    if ($null -eq $script:opsPf -or $script:opsPf.HasExited) {
        $script:opsPf = Start-PortForward "svc/ircs-ops-service" $OpsPort 8080 $opsPfOut $opsPfErr
    }
    try {
        $summary = Invoke-RestMethod `
            -Method Get `
            -Uri "$OpsBaseUrl/api/v1/ops/notification-mail-send-history/summary" `
            -TimeoutSec 10
        return @{
            queried = $true
            available = $true
            totalLast24h = $summary.totalLast24h
            sentLast24h = $summary.sentLast24h
            failedLast24h = $summary.failedLast24h
            skippedLast24h = $summary.skippedLast24h
            sentSemantics = $summary.sentSemantics
        }
    } catch {
        return @{
            queried = $true
            available = $false
            error = $_.Exception.Message
        }
    }
}

function New-MailSendPayloadBase64 {
    param(
        [string]$Recipient,
        [string]$Subject,
        [string]$Content
    )
    $repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
    $contractsClass = Join-Path $repoRoot "shared\ircs-contracts\build\classes\java\main\com\prodigalgal\ircs\contracts\notification\MailMessageDTO.class"
    if (-not (Test-Path $contractsClass)) {
        & (Join-Path $repoRoot "gradlew.bat") :shared:ircs-contracts:classes | Out-Host
        if ($LASTEXITCODE -ne 0) {
            Finish-Smoke "SKIPPED" "CONTRACTS_COMPILE_UNAVAILABLE" @{ exitCode = $LASTEXITCODE; sent = $false }
        }
    }

    $tmpDir = Join-Path $env:TEMP ("ircs-02810f-mail-serializer-" + [guid]::NewGuid())
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
        $payload = & java -cp "$tmpDir;$contractsClasses" SerializeMailMessage $Recipient $Subject $Content
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
            sent = $false
        }
    }
    return $payload.Trim()
}

function Get-RabbitHeaders {
    $rabbitPassword = Get-SecretValue "RABBITMQ_PASSWORD"
    $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("admin:$($rabbitPassword)"))
    return @{ Authorization = "Basic $basic" }
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
        -Headers (Get-RabbitHeaders) `
        -ContentType "application/json" `
        -Body $body `
        -Uri "$RabbitBaseUrl/api/exchanges/%2F/x.notification/publish" `
        -TimeoutSec 10
}

function Wait-ForSendHistory {
    param([string]$CorrelationId)
    for ($i = 0; $i -lt $WaitAttempts; $i++) {
        $count = Invoke-SqlScalar "select count(*) from notification_mail_send_history where correlation_id = $(Sql-Literal $CorrelationId);"
        if ([int]$count -ge 1) {
            return $true
        }
        Start-Sleep -Seconds $WaitSeconds
    }
    return $false
}

function Get-SendHistoryRow {
    param([string]$CorrelationId)
    $sql = @"
select row_to_json(t)::text
from (
    select correlation_id,
           regexp_replace(recipient, '(^.{1,2}).*(@.*$)', '\1***\2') as recipient_masked,
           subject,
           template_code,
           delivery_mode,
           status,
           credential_id::text,
           failure_code,
           failure_message,
           created_at,
           updated_at
      from notification_mail_send_history
     where correlation_id = $(Sql-Literal $CorrelationId)
     order by created_at desc
     limit 1
) t;
"@
    return Invoke-SqlJson $sql
}

function Get-DeploymentEnvEntry {
    param(
        [object]$Deployment,
        [string]$Name
    )
    $container = $Deployment.spec.template.spec.containers | Where-Object { $_.name -eq "app" } | Select-Object -First 1
    return $container.env | Where-Object { $_.name -eq $Name } | Select-Object -First 1
}

function Save-EnvForRestore {
    param(
        [object]$Deployment,
        [string[]]$Names
    )
    foreach ($name in $Names) {
        $entry = Get-DeploymentEnvEntry $Deployment $name
        if ($null -eq $entry) {
            $script:restoreEnv[$name] = $null
        } else {
            $script:restoreEnv[$name] = ($entry | ConvertTo-Json -Depth 12 -Compress)
        }
    }
}

function Patch-DeploymentEnvEntry {
    param([hashtable[]]$Entries)
    $normalizedEntries = @()
    foreach ($entry in $Entries) {
        $copy = @{}
        foreach ($key in $entry.Keys) {
            $copy[$key] = $entry[$key]
        }
        if ($copy.ContainsKey("value") -and -not $copy.ContainsKey("valueFrom")) {
            $copy["valueFrom"] = $null
        }
        if ($copy.ContainsKey("valueFrom") -and -not $copy.ContainsKey("value")) {
            $copy["value"] = $null
        }
        $normalizedEntries += $copy
    }
    $patch = @{
        spec = @{
            template = @{
                metadata = @{
                    annotations = @{
                        "ircs.codex/smtp-smoke-trace" = $trace
                    }
                }
                spec = @{
                    containers = @(
                        @{
                            name = "app"
                            env = $normalizedEntries
                        }
                    )
                }
            }
        }
    } | ConvertTo-Json -Depth 24 -Compress
    $patchFile = Join-Path $env:TEMP ("ircs-02810-mail-runtime-patch-" + [guid]::NewGuid() + ".json")
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($patchFile, $patch, $utf8NoBom)
    try {
        $previous = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $output = kubectl -n $Namespace patch deployment ircs-notification-worker --type=strategic --patch-file $patchFile 2>&1
        $exit = $LASTEXITCODE
        $ErrorActionPreference = $previous
        if ($exit -ne 0) {
            Finish-Smoke "FAILED" "DEPLOYMENT_ENV_PATCH_FAILED" @{
                exitCode = $exit
                output = (($output | Out-String).Trim())
                sent = $false
                passwordPrinted = $false
            }
        }
    } finally {
        Remove-Item -LiteralPath $patchFile -Force -ErrorAction SilentlyContinue
    }
    $script:patchedDeployment = $true
}

function Remove-DeploymentEnvNames {
    param([string[]]$Names)
    foreach ($name in $Names) {
        $previous = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $output = kubectl -n $Namespace set env deployment/ircs-notification-worker "$name-" 2>&1
        $exit = $LASTEXITCODE
        $ErrorActionPreference = $previous
        if ($exit -ne 0) {
            Write-Warning ("restore remove env {0} failed: {1}" -f $name, (($output | Out-String).Trim()))
        }
    }
}

function Wait-NotificationRollout {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = kubectl -n $Namespace rollout status deployment/ircs-notification-worker --timeout=180s 2>&1
    $exit = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($exit -ne 0) {
        Finish-Smoke "FAILED" "NOTIFICATION_WORKER_ROLLOUT_FAILED" @{
            exitCode = $exit
            output = (($output | Out-String).Trim())
            sent = $false
            passwordPrinted = $false
        }
    }
}

function Enable-OneShotRuntime {
    param([object]$Deployment)
    $names = @(
        "APP_MAIL_ENABLED",
        "APP_MAIL_SEND_HISTORY_ENABLED",
        "APP_NOTIFICATION_LISTENER_ENABLED",
        "APP_MAIL_CREDENTIAL_PROVIDER",
        "APP_MAIL_CREDENTIAL_SERVICE_BASE_URL",
        "APP_MAIL_CREDENTIAL_SERVICE_TOKEN"
    )
    Save-EnvForRestore $Deployment $names
    Patch-DeploymentEnvEntry @(
        @{ name = "APP_MAIL_ENABLED"; value = "true" },
        @{ name = "APP_MAIL_SEND_HISTORY_ENABLED"; value = "true" },
        @{ name = "APP_NOTIFICATION_LISTENER_ENABLED"; value = "true" },
        @{ name = "APP_MAIL_CREDENTIAL_PROVIDER"; value = "credential-service" },
        @{ name = "APP_MAIL_CREDENTIAL_SERVICE_BASE_URL"; value = "http://ircs-credential-service.ircs-dev.svc.cluster.local:8080" },
        @{ name = "APP_MAIL_CREDENTIAL_SERVICE_TOKEN"; valueFrom = @{ secretKeyRef = @{ name = "ircs-dev-secrets"; key = "SERVICE_CREDENTIAL_TOKEN" } } }
    )
    Wait-NotificationRollout
}

function Restore-OneShotRuntime {
    if (-not $script:patchedDeployment) {
        return @{
            attempted = $false
            appMailEnabledRestored = $true
        }
    }
    $entries = New-Object System.Collections.Generic.List[hashtable]
    $missing = New-Object System.Collections.Generic.List[string]
    foreach ($name in $script:restoreEnv.Keys) {
        $json = $script:restoreEnv[$name]
        if ($null -eq $json) {
            $missing.Add($name)
        } else {
            $entries.Add(($json | ConvertFrom-Json | ConvertTo-Hashtable))
        }
    }
    if ($entries.Count -gt 0) {
        Patch-DeploymentEnvEntry -Entries $entries.ToArray()
    }
    if ($missing.Count -gt 0) {
        Remove-DeploymentEnvNames @($missing)
    }
    Wait-NotificationRollout
    $restored = Get-KubectlJson @("-n", $Namespace, "get", "deployment", "ircs-notification-worker")
    $restoredMailEnabled = Get-EnvValue $restored "APP_MAIL_ENABLED"
    return @{
        attempted = $true
        appMailEnabled = $restoredMailEnabled
        appMailEnabledRestored = ($restoredMailEnabled -eq "false")
        listenerEnabled = Get-EnvValue $restored "APP_NOTIFICATION_LISTENER_ENABLED"
        sendHistoryEnabled = Get-EnvValue $restored "APP_MAIL_SEND_HISTORY_ENABLED"
        passwordPrinted = $false
    }
}

function ConvertTo-Hashtable {
    param([Parameter(ValueFromPipeline)]$InputObject)
    process {
        if ($null -eq $InputObject) { return $null }
        if ($InputObject -is [System.Collections.IDictionary]) {
            $hash = @{}
            foreach ($key in $InputObject.Keys) {
                $hash[$key] = ConvertTo-Hashtable $InputObject[$key]
            }
            return $hash
        }
        if ($InputObject -is [System.Collections.IEnumerable] -and $InputObject -isnot [string]) {
            $list = @()
            foreach ($item in $InputObject) {
                $list += ConvertTo-Hashtable $item
            }
            return $list
        }
        if ($InputObject.PSObject.Properties.Count -gt 0 -and $InputObject.GetType().Name -eq "PSCustomObject") {
            $hash = @{}
            foreach ($prop in $InputObject.PSObject.Properties) {
                $hash[$prop.Name] = ConvertTo-Hashtable $prop.Value
            }
            return $hash
        }
        return $InputObject
    }
}

try {
    Test-KubectlAccess
    $deployment = Get-KubectlJson @("-n", $Namespace, "get", "deployment", "ircs-notification-worker")
    $ready = kubectl -n $Namespace get deployment ircs-notification-worker -o jsonpath="{.status.readyReplicas}" 2>$null
    $mailHost = Get-EnvValue $deployment "APP_MAIL_HOST"
    $mailPort = Get-EnvValue $deployment "APP_MAIL_PORT"
    $mailFrom = Get-EnvValue $deployment "APP_MAIL_FROM"
    $mailEnabled = Get-EnvValue $deployment "APP_MAIL_ENABLED"
    $mailDeliveryMode = Get-EnvValue $deployment "APP_MAIL_DELIVERY_MODE"
    $credentialProvider = Get-EnvValue $deployment "APP_MAIL_CREDENTIAL_PROVIDER"
    $sendHistoryEnabled = Get-EnvValue $deployment "APP_MAIL_SEND_HISTORY_ENABLED"
    $listenerEnabled = Get-EnvValue $deployment "APP_NOTIFICATION_LISTENER_ENABLED"
    $standingDisabled = ($mailEnabled -eq "false")

    $k8sEvidence = @{
        deploymentReadyReplicas = if ([string]::IsNullOrWhiteSpace($ready)) { "0" } else { [string]$ready }
        host = $mailHost
        port = $mailPort
        fromMasked = Mask-Email $mailFrom
        fromPrinted = $false
        appMailEnabled = $mailEnabled
        appMailEnabledSource = Get-EnvSource $deployment "APP_MAIL_ENABLED"
        appMailDeliveryMode = if ([string]::IsNullOrWhiteSpace($mailDeliveryMode)) { "smtp(default)" } else { $mailDeliveryMode }
        appMailCredentialProvider = if ([string]::IsNullOrWhiteSpace($credentialProvider)) { "static(default)" } else { $credentialProvider }
        appMailSendHistoryEnabled = if ([string]::IsNullOrWhiteSpace($sendHistoryEnabled)) { "false(default)" } else { $sendHistoryEnabled }
        appNotificationListenerEnabled = if ([string]::IsNullOrWhiteSpace($listenerEnabled)) { "false(default)" } else { $listenerEnabled }
        standingDevAppMailEnabledFalse = $standingDisabled
    }
    Mark "K8S notification-worker mail host/from/enabled gate checked without printing secrets" 1 $markTotal

    if ($StartCredentialPortForward) {
        $credentialPf = Start-PortForward "svc/ircs-credential-service" $CredentialPort 8080 $credentialPfOut $credentialPfErr
    }
    $credentialSqlEvidence = Get-MailCredentialSqlEvidence
    $credentialLeaseEvidence = Get-MailCredentialLeaseEvidence
    Mark "MAIL credential evidence checked without password output" 2 $markTotal

    $sendHistoryEvidence = Get-SendHistoryEvidence
    $opsSendHistoryEvidence = Get-OpsSendHistoryEvidence
    $mailFromAddress = Extract-EmailAddress $mailFrom
    $testRecipientAddress = Extract-EmailAddress $TestRecipient
    $v1MailFromTestTarget = ($TaskId -eq "028-10-L" `
            -and -not [string]::IsNullOrWhiteSpace($mailFromAddress) `
            -and $testRecipientAddress -eq $mailFromAddress)
    $recipientSafe = ((Test-SafeRecipient $TestRecipient) -or $v1MailFromTestTarget)
    $stagingOnly = ($mailHost -match "(?i)(mailosaur|mailtrap|ethereal|sandbox|staging|test|smtp4dev)" `
            -or $mailFrom -match "(?i)(test|staging|smoke|codex)")
    $standardSmtpAllowed = ($TaskId -eq "028-10-L")
    $smtpProviderGate = ($stagingOnly -or $standardSmtpAllowed)
    $temporarySendHistoryEnableAllowed = ($TaskId -eq "028-10-I" -or $TaskId -eq "028-10-L")
    $lowFrequency = $true

    $preflight = @{
        k8s = $k8sEvidence
        credentialSql = $credentialSqlEvidence
        credentialLease = $credentialLeaseEvidence
        sendHistory = $sendHistoryEvidence
        opsSendHistory = $opsSendHistoryEvidence
        recipient = @{
            provided = -not [string]::IsNullOrWhiteSpace($TestRecipient)
            masked = Mask-Email $TestRecipient
            safeTestRecipient = $recipientSafe
            v1MailFromTestTarget = $v1MailFromTestTarget
            printed = $false
        }
        gates = @{
            allowSend = [bool]$AllowSend
            standingDevAppMailEnabledFalse = $standingDisabled
            stagingOnlyConfig = $stagingOnly
            standardSmtpProviderAllowed = $standardSmtpAllowed
            smtpProviderGate = $smtpProviderGate
            lowFrequencyOneShot = $lowFrequency
            safeTestRecipient = $recipientSafe
            v1MailFromTestTarget = $v1MailFromTestTarget
            mailCredentialAvailable = [bool]$credentialLeaseEvidence.available
            sendHistoryTablePresent = [bool]$sendHistoryEvidence.present
            sendHistoryEnabledInDeployment = ($sendHistoryEnabled -eq "true")
            temporarySendHistoryEnableAllowed = $temporarySendHistoryEnableAllowed
            deliveryModeSmtp = ([string]::IsNullOrWhiteSpace($mailDeliveryMode) -or $mailDeliveryMode -match "^(?i)smtp$")
        }
        sent = $false
        appMailEnabledRestored = $standingDisabled
        passwordPrinted = $false
    }

    $blockReasons = New-Object System.Collections.Generic.List[string]
    if (-not $AllowSend) { $blockReasons.Add("ALLOW_SEND_NOT_SET") }
    if (-not $standingDisabled) { $blockReasons.Add("STANDING_DEV_APP_MAIL_ENABLED_NOT_FALSE") }
    if (-not $smtpProviderGate) { $blockReasons.Add("SMTP_CONFIG_NOT_STAGING_ONLY_OR_STANDARD_ALLOWED") }
    if (-not $recipientSafe) { $blockReasons.Add("SAFE_TEST_RECIPIENT_NOT_CONFIRMED") }
    if (-not [bool]$credentialLeaseEvidence.available) { $blockReasons.Add("MAIL_CREDENTIAL_UNAVAILABLE") }
    if (-not [bool]$sendHistoryEvidence.present) { $blockReasons.Add("SEND_HISTORY_TABLE_MISSING") }
    if ($sendHistoryEnabled -ne "true" -and -not $temporarySendHistoryEnableAllowed) { $blockReasons.Add("SEND_HISTORY_NOT_ENABLED_IN_DEPLOYMENT") }
    if (-not ([string]::IsNullOrWhiteSpace($mailDeliveryMode) -or $mailDeliveryMode -match "^(?i)smtp$")) { $blockReasons.Add("DELIVERY_MODE_NOT_SMTP") }

    if ($blockReasons.Count -gt 0) {
        Mark "unsafe or unclear recipient/config blocks SMTP and emits evidence" 3 $markTotal
        Mark "one-shot SMTP smoke not executed because preflight gate blocked it" 4 $markTotal
        if ($TaskId -eq "028-10-I" -or $TaskId -eq "028-10-L") {
            Mark "send history row not expected because SMTP was not sent" 5 $markTotal
            Mark "no runtime patch applied and dev APP_MAIL_ENABLED remains false" 6 $markTotal
        } else {
            Mark "dev APP_MAIL_ENABLED remains false" 5 $markTotal
        }
        $preflight.blockReasons = @($blockReasons)
        Finish-Smoke "SKIPPED" "SMTP_PREFLIGHT_BLOCKED" $preflight
    }

    if ($StartOpsPortForward -and ($null -eq $opsPf -or $opsPf.HasExited)) {
        $opsPf = Start-PortForward "svc/ircs-ops-service" $OpsPort 8080 $opsPfOut $opsPfErr
    }

    if (-not $StartRabbitPortForward -and $RabbitBaseUrl -match "^https?://(127\.0\.0\.1|localhost)(:|/)") {
        Finish-Smoke "SKIPPED" "RABBIT_PORT_FORWARD_NOT_SET_FOR_LOCAL_BASE_URL" @{
            preflight = $preflight
            sent = $false
            appMailEnabledRestored = $standingDisabled
            passwordPrinted = $false
        }
    }
    Mark "one-shot SMTP gates passed before runtime patch" 3 $markTotal

    if ($StartRabbitPortForward) {
        $rabbitPf = Start-PortForward "svc/rabbitmq-svc" $RabbitPort 15672 $rabbitPfOut $rabbitPfErr
    }

    Enable-OneShotRuntime $deployment
    $subject = "IRCS $TaskId SMTP smoke $trace"
    $content = "IRCS $TaskId SMTP one-shot smoke $trace"
    $payloadBase64 = New-MailSendPayloadBase64 $TestRecipient $subject $content
    $publishResult = Invoke-RabbitPublish $payloadBase64
    if (-not $publishResult.routed) {
        Finish-Smoke "FAILED" "SMTP_ONE_SHOT_MESSAGE_NOT_ROUTED" @{
            preflight = $preflight
            publishResult = $publishResult
            sent = $false
            passwordPrinted = $false
        }
    }
    Mark "one-shot SMTP smoke message published at most once" 4 $markTotal

    if (-not (Wait-ForSendHistory $trace)) {
        Finish-Smoke "FAILED" "SEND_HISTORY_ROW_NOT_WRITTEN" @{
            preflight = $preflight
            trace = $trace
            sent = $true
            waitAttempts = $WaitAttempts
            waitSeconds = $WaitSeconds
            passwordPrinted = $false
        }
    }
    $historyRow = Get-SendHistoryRow $trace
    $opsRow = Test-OpsSendHistoryQuery $trace
    if ($null -eq $historyRow -or $historyRow.status -ne "sent") {
        $script:restoreResult = Restore-OneShotRuntime
        Finish-Smoke "FAILED" "SMTP_ONE_SHOT_NOT_SENT" @{
            preflight = $preflight
            trace = $trace
            sent = $false
            sendHistory = @{
                row = $historyRow
                opsQuery = $opsRow
                recipientPrinted = $false
                passwordPrinted = $false
            }
            restore = $script:restoreResult
            passwordPrinted = $false
        }
    }
    Mark "send history row written and ops endpoint queried" 5 $markTotal

    $script:restoreResult = Restore-OneShotRuntime
    Mark "temporary runtime switches restored and helper resources ready for cleanup" 6 $markTotal

    $successReason = if ($TaskId -eq "028-10-L") { "SMTP_V1_ONE_SHOT_SMOKE_OK" } else { "SMTP_STAGING_ONE_SHOT_SMOKE_OK" }
    Finish-Smoke "PASS" $successReason @{
        preflight = $preflight
        sent = $true
        publishResult = @{
            routed = $publishResult.routed
        }
        sendHistory = @{
            row = $historyRow
            opsQuery = $opsRow
            recipientPrinted = $false
            passwordPrinted = $false
        }
        restore = $script:restoreResult
        auditRecordRetained = $true
        auditRecordRetainedReason = "notification_mail_send_history is business audit history"
        passwordPrinted = $false
    }
} catch {
    Finish-Smoke "FAILED" "SMTP_PREFLIGHT_FAILED" @{
        message = $_.Exception.Message
        stack = $_.ScriptStackTrace
        sent = $false
        passwordPrinted = $false
    }
} finally {
    if ($script:patchedDeployment -and $null -eq $script:restoreResult) {
        try {
            $script:restoreResult = Restore-OneShotRuntime
        } catch {
            Write-Warning ("restore runtime failed: {0}" -f $_.Exception.Message)
        }
    }
    if ($credentialPf -and -not $credentialPf.HasExited) {
        Stop-Process -Id $credentialPf.Id -Force -ErrorAction SilentlyContinue
    }
    if ($opsPf -and -not $opsPf.HasExited) {
        Stop-Process -Id $opsPf.Id -Force -ErrorAction SilentlyContinue
    }
    if ($rabbitPf -and -not $rabbitPf.HasExited) {
        Stop-Process -Id $rabbitPf.Id -Force -ErrorAction SilentlyContinue
    }
}
