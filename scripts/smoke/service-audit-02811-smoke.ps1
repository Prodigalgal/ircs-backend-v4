param(
    [string]$ConfigBaseUrl = "http://127.0.0.1:19083",
    [string]$OpsBaseUrl = "http://127.0.0.1:19084",
    [int]$ConfigPort = 19083,
    [int]$OpsPort = 19084
)

$ErrorActionPreference = "Stop"

$stamp = Get-Date -Format "yyyyMMddHHmmss"
$traceId = "codex-02811-$stamp"
$username = "codex-audit-$stamp@example.com"
$configPfOut = Join-Path $env:TEMP "ircs-02811-config-pf.out.log"
$configPfErr = Join-Path $env:TEMP "ircs-02811-config-pf.err.log"
$opsPfOut = Join-Path $env:TEMP "ircs-02811-ops-pf.out.log"
$opsPfErr = Join-Path $env:TEMP "ircs-02811-ops-pf.err.log"
$configPf = $null
$opsPf = $null

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

function Start-PortForward {
    param(
        [string]$Service,
        [int]$LocalPort,
        [string]$OutPath,
        [string]$ErrPath
    )
    Remove-Item -LiteralPath $OutPath, $ErrPath -ErrorAction SilentlyContinue
    $process = Start-Process -FilePath kubectl `
        -ArgumentList @("-n", "ircs-dev", "port-forward", "svc/$Service", "$($LocalPort):8080") `
        -RedirectStandardOutput $OutPath `
        -RedirectStandardError $ErrPath `
        -WindowStyle Hidden `
        -PassThru
    Start-Sleep -Seconds 5
    if ($process.HasExited) {
        $err = if (Test-Path $ErrPath) { Get-Content -Path $ErrPath -Raw } else { "" }
        throw "$Service port-forward exited: $err"
    }
    return $process
}

function Invoke-Json {
    param(
        [string]$Method,
        [string]$Url,
        [hashtable]$Headers = @{}
    )
    return Invoke-RestMethod -Method $Method -Uri $Url -Headers $Headers -TimeoutSec 30
}

function Cleanup {
    try {
        Exec-Sql "delete from request_audit_logs where trace_id = '$traceId';"
    } catch {
        Write-Warning "cleanup failed: $($_.Exception.Message)"
    }
}

try {
    $hasSource = Invoke-SqlScalar "select exists (select 1 from information_schema.columns where table_name = 'request_audit_logs' and column_name = 'request_source');"
    if ($hasSource -ne "t") {
        throw "request_audit_logs.request_source column missing"
    }
    Mark "request_audit_logs schema can store request_source" 1 3

    Cleanup
    $configPf = Start-PortForward "ircs-config-service" $ConfigPort $configPfOut $configPfErr
    $opsPf = Start-PortForward "ircs-ops-service" $OpsPort $opsPfOut $opsPfErr

    $headers = @{
        "X-Trace-Id" = $traceId
        "X-Authenticated-User" = $username
        "X-Forwarded-For" = "203.0.113.211, 10.0.0.1"
        "User-Agent" = "codex-02811-service-audit"
    }
    $timezones = Invoke-Json "GET" "$ConfigBaseUrl/api/v1/common/timezones" $headers
    if (-not ($timezones -contains "Asia/Shanghai")) {
        throw "config-service timezone response missing Asia/Shanghai"
    }

    $deadline = (Get-Date).AddSeconds(30)
    $dbRow = $null
    while ((Get-Date) -lt $deadline) {
        $dbRow = Invoke-SqlScalar @"
select request_source || '|' || username || '|' || method || '|' || path || '|' || status_code || '|' ||
       case when success then 't' else 'f' end
from request_audit_logs
where trace_id = '$traceId'
order by created_at desc
limit 1;
"@
        if ($dbRow) {
            break
        }
        Start-Sleep -Seconds 2
    }
    if (-not $dbRow) {
        throw "service audit row not found for trace $traceId"
    }
    $expectedDb = "ircs-config-service|$username|GET|/api/v1/common/timezones|200|t"
    if ($dbRow -ne $expectedDb) {
        throw "service audit row mismatch: $dbRow"
    }
    Mark "config-service handler request writes service audit row" 2 3

    $encodedSource = [uri]::EscapeDataString("ircs-config-service")
    $page = Invoke-Json "GET" "$OpsBaseUrl/api/v1/ops/request-audit?size=100&requestSource=$encodedSource" @{}
    $content = @($page.content)
    $match = $content | Where-Object {
        $_.traceId -eq $traceId -and
        $_.requestSource -eq "ircs-config-service" -and
        $_.path -eq "/api/v1/common/timezones" -and
        $_.statusCode -eq 200
    } | Select-Object -First 1
    if (-not $match) {
        throw "ops request audit response did not expose service requestSource for trace $traceId"
    }
    Mark "ops-service request audit response exposes requestSource" 3 3

    Cleanup
    $residue = Invoke-SqlScalar "select count(*) from request_audit_logs where trace_id = '$traceId';"
    if ($residue -ne "0") {
        throw "request_audit_logs residue after cleanup: $residue"
    }
} finally {
    if ($configPf -and -not $configPf.HasExited) {
        Stop-Process -Id $configPf.Id -Force -ErrorAction SilentlyContinue
    }
    if ($opsPf -and -not $opsPf.HasExited) {
        Stop-Process -Id $opsPf.Id -Force -ErrorAction SilentlyContinue
    }
}
