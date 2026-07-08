param(
    [string]$Namespace = "ircs-dev",
    [string]$TaskBaseUrl = "http://127.0.0.1:18122",
    [int]$TaskPort = 18122,
    [string]$ScraperBaseUrl = "http://127.0.0.1:18123",
    [int]$ScraperPort = 18123,
    [switch]$AllowProviderExternal,
    [switch]$SkipCleanup,
    [switch]$FailOnSkip,
    [string]$TaskServiceToken = "",
    [string]$ScraperServiceToken = "",
    [int]$WaitSeconds = 30,
    [int]$CurlTimeoutSeconds = 30
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

if ([string]::IsNullOrWhiteSpace($TaskServiceToken)) {
    $TaskServiceToken = $env:APP_TASK_SERVICE_TOKEN
}
if ([string]::IsNullOrWhiteSpace($ScraperServiceToken)) {
    $ScraperServiceToken = $env:APP_SCRAPER_SERVICE_TOKEN
}

$stamp = Get-Date -Format "yyyyMMddHHmmss"
$keyword = "Codex02822 Smoke $stamp"
$correlationId = "02822f-$stamp"
$taskPf = $null
$scraperPf = $null
$taskPfOut = Join-Path $env:TEMP "ircs-02822f-task-pf.out.log"
$taskPfErr = Join-Path $env:TEMP "ircs-02822f-task-pf.err.log"
$scraperPfOut = Join-Path $env:TEMP "ircs-02822f-scraper-pf.out.log"
$scraperPfErr = Join-Path $env:TEMP "ircs-02822f-scraper-pf.err.log"

function Mark {
    param([string]$Name, [int]$Index, [int]$Total)
    Write-Output ("MARK {0}/{1} {2}" -f $Index, $Total, $Name)
}

function Sql-Literal {
    param([string]$Value)
    if ($null -eq $Value) {
        return "null"
    }
    return "'" + $Value.Replace("'", "''") + "'"
}

function Exec-Sql {
    param([string]$Sql)
    $result = $Sql | kubectl -n $Namespace exec -i postgres-0 -- psql -v ON_ERROR_STOP=1 -U postgres -d ircs -t -A -F "|"
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed with exit code $LASTEXITCODE"
    }
    return $result
}

function Get-ScalarInt {
    param([string]$Sql)
    $raw = Exec-Sql $Sql
    $line = @($raw | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Last 1)
    if ($line.Count -eq 0) {
        return 0
    }
    return [int]$line[0].Trim()
}

function Start-PortForward {
    param(
        [string]$Service,
        [int]$LocalPort,
        [string]$OutFile,
        [string]$ErrFile
    )
    Remove-Item -LiteralPath $OutFile, $ErrFile -ErrorAction SilentlyContinue
    $process = Start-Process -FilePath kubectl `
        -ArgumentList @("-n", $Namespace, "port-forward", "svc/$Service", "$($LocalPort):8080") `
        -RedirectStandardOutput $OutFile `
        -RedirectStandardError $ErrFile `
        -WindowStyle Hidden `
        -PassThru
    Start-Sleep -Seconds 4
    if ($process.HasExited) {
        $err = if (Test-Path $ErrFile) { Get-Content -LiteralPath $ErrFile -Raw } else { "" }
        throw "$Service port-forward exited: $err"
    }
    return $process
}

function Stop-PortForward {
    param($Process)
    if ($null -ne $Process -and -not $Process.HasExited) {
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-JsonRequest {
    param(
        [string]$Method,
        [string]$Url,
        [object]$Body = $null,
        [hashtable]$Headers = @{}
    )
    $responseFile = Join-Path $env:TEMP ("ircs-02822f-response-" + [guid]::NewGuid() + ".json")
    $requestFile = $null
    $args = @("-sS", "--max-time", [string]$CurlTimeoutSeconds, "-o", $responseFile, "-w", "%{http_code}", "-X", $Method)
    foreach ($entry in $Headers.GetEnumerator()) {
        if (-not [string]::IsNullOrWhiteSpace([string]$entry.Value)) {
            $args += @("-H", "$($entry.Key): $($entry.Value)")
        }
    }
    if ($null -ne $Body) {
        $requestFile = Join-Path $env:TEMP ("ircs-02822f-request-" + [guid]::NewGuid() + ".json")
        $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 16 -Compress }
        [System.IO.File]::WriteAllText($requestFile, $json, [System.Text.UTF8Encoding]::new($false))
        $args += @("-H", "Content-Type: application/json", "--data-binary", "@$requestFile")
    }
    $args += $Url
    $code = & curl.exe @args
    $text = ""
    if (Test-Path $responseFile) {
        $text = Get-Content -LiteralPath $responseFile -Raw -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $responseFile -ErrorAction SilentlyContinue
    if ($requestFile) {
        Remove-Item -LiteralPath $requestFile -ErrorAction SilentlyContinue
    }
    if ([int]$code -lt 200 -or [int]$code -ge 300) {
        throw "HTTP $code for $Method $Url body=$text"
    }
    if ([string]::IsNullOrWhiteSpace($text)) {
        return $null
    }
    return $text | ConvertFrom-Json
}

function Invoke-Text {
    param([string]$Url)
    $tmp = Join-Path $env:TEMP ("ircs-02822f-text-" + [guid]::NewGuid() + ".txt")
    $code = & curl.exe -sS --max-time $CurlTimeoutSeconds -o $tmp -w "%{http_code}" $Url
    $text = ""
    if (Test-Path $tmp) {
        $text = Get-Content -LiteralPath $tmp -Raw -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $tmp -ErrorAction SilentlyContinue
    }
    if ([int]$code -lt 200 -or [int]$code -ge 300) {
        throw "HTTP $code for GET $Url body=$text"
    }
    return $text
}

function Smoke-TaskPredicate {
    $lit = Sql-Literal $keyword
    return "(filter_keywords = $lit or name like " + (Sql-Literal "%$keyword%") + ")"
}

function Cleanup-SmokeTasks {
    if ($SkipCleanup) {
        Write-Output "cleanup skipped by -SkipCleanup"
        return
    }
    $predicate = Smoke-TaskPredicate
    $deadline = (Get-Date).AddSeconds([Math]::Max(1, $WaitSeconds))
    do {
        $active = Get-ScalarInt "select count(*) from collection_tasks where $predicate and status in ('QUEUED','RUNNING','STOPPING');"
        if ($active -eq 0) {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    $activeAfterWait = Get-ScalarInt "select count(*) from collection_tasks where $predicate and status in ('QUEUED','RUNNING','STOPPING');"
    if ($activeAfterWait -gt 0) {
        Write-Output "cleanup deferred: $activeAfterWait smoke task(s) still active"
        if ($FailOnSkip) {
            throw "smoke tasks still active after wait window"
        }
        return
    }
    Exec-Sql "delete from collection_tasks where $predicate;" | Out-Null
}

function New-TaskHeaders {
    $headers = @{
        "Accept" = "application/json"
        "X-Correlation-Id" = $correlationId
        "X-IRCS-SERVICE-ID" = "smoke-02822f"
        "X-IRCS-SERVICE-SCOPES" = "task:maintenance scraper:maintenance ops:maintenance"
    }
    if (-not [string]::IsNullOrWhiteSpace($TaskServiceToken)) {
        $headers["X-IRCS-SERVICE-TOKEN"] = $TaskServiceToken
    }
    return $headers
}

function New-ScraperHeaders {
    $headers = @{
        "Accept" = "application/json"
        "X-Correlation-Id" = $correlationId
        "X-IRCS-SERVICE-ID" = "smoke-02822f"
        "X-IRCS-SERVICE-SCOPES" = "scraper:maintenance task:maintenance ops:maintenance"
    }
    if (-not [string]::IsNullOrWhiteSpace($ScraperServiceToken)) {
        $headers["X-IRCS-SERVICE-TOKEN"] = $ScraperServiceToken
    }
    return $headers
}

try {
    kubectl -n $Namespace rollout status deployment/ircs-task-service --timeout=90s | Out-Host
    kubectl -n $Namespace rollout status deployment/ircs-scraper-service --timeout=90s | Out-Host
    kubectl -n $Namespace rollout status deployment/ircs-credential-service --timeout=90s | Out-Host
    Mark "task/scraper/credential deployments are Ready" 1 6

    $taskPf = Start-PortForward "ircs-task-service" $TaskPort $taskPfOut $taskPfErr
    $health = Invoke-Text "$TaskBaseUrl/actuator/health/readiness"
    if ($health -notmatch '"status"\s*:\s*"UP"') {
        throw "task-service readiness is not UP: $health"
    }
    Mark "task-service readiness endpoint reachable" 2 6

    $emptyResponse = Invoke-JsonRequest `
        -Method "POST" `
        -Url "$TaskBaseUrl/internal/v1/tasks/trend-discovery" `
        -Headers (New-TaskHeaders) `
        -Body @{ keywords = @(); startPage = 1; endPage = 1; fixedDelayMs = 0; force = $false }
    if ($emptyResponse.taskName -ne "trend-discovery" -or [int]$emptyResponse.requestedKeywords -ne 0) {
        throw "empty trend discovery response mismatch"
    }
    Mark "task-service trend-discovery internal contract accepts empty request" 3 6

    $sourceCount = Get-ScalarInt "select count(*) from data_sources where name is not null and btrim(name) <> '';"
    if ($sourceCount -lt 1) {
        throw "no data_sources available for trend discovery"
    }
    Cleanup-SmokeTasks
    $smokePredicate = Smoke-TaskPredicate
    $residueBefore = Get-ScalarInt "select count(*) from collection_tasks where $smokePredicate;"
    if ($residueBefore -ne 0) {
        throw "pre-existing smoke residue remains: $residueBefore"
    }
    Mark "DB data source candidates available and smoke residue is clean" 4 6

    if ($AllowProviderExternal) {
        $scheduleResponse = Invoke-JsonRequest `
            -Method "POST" `
            -Url "$TaskBaseUrl/internal/v1/tasks/trend-discovery" `
            -Headers (New-TaskHeaders) `
            -Body @{ keywords = @($keyword); startPage = 1; endPage = 1; fixedDelayMs = 0; force = $false; maxDataSources = 1 }
        if ([int]$scheduleResponse.requestedKeywords -ne 1) {
            throw "trend discovery keyword count mismatch"
        }
        if ([int]$scheduleResponse.dataSourceCount -lt 1 -or [int]$scheduleResponse.dataSourceCount -gt 1) {
            throw "trend discovery datasource count mismatch: $($scheduleResponse.dataSourceCount)"
        }
        if (([int]$scheduleResponse.createdTasks + [int]$scheduleResponse.reusedTasks) -lt 1) {
            throw "trend discovery did not create or reuse any task"
        }
        $smokePredicate = Smoke-TaskPredicate
        $rowCount = Get-ScalarInt "select count(*) from collection_tasks where $smokePredicate;"
        if ($rowCount -lt 1) {
            throw "trend discovery DB rows were not found"
        }
        Mark "provider-gated direct discovery created/reused and queued smoke tasks" 5 6

        $scraperPf = Start-PortForward "ircs-scraper-service" $ScraperPort $scraperPfOut $scraperPfErr
        $syncResponse = Invoke-JsonRequest `
            -Method "POST" `
            -Url "$ScraperBaseUrl/internal/v1/scraper/trends/sync" `
            -Headers (New-ScraperHeaders)
        if ($syncResponse.taskName -ne "trend-sync") {
            throw "trend sync task name mismatch"
        }
        if ($null -eq $syncResponse.discoveryResult) {
            throw "trend sync response did not include discoveryResult"
        }
        Write-Output ("trend-sync fetchedItems={0} discoveryQueued={1} providerErrors={2}" -f `
                $syncResponse.fetchedItems, `
                $syncResponse.discoveryResult.queuedTasks, `
                (($syncResponse.providerErrors | ForEach-Object { $_ }) -join ";"))
    } else {
        Write-Output "provider-gated direct discovery and scraper trend-sync were skipped; rerun with -AllowProviderExternal"
        if ($FailOnSkip) {
            throw "provider external checks skipped"
        }
        Mark "provider-gated direct discovery and scraper trend-sync skipped" 5 6
    }

    Cleanup-SmokeTasks
    $smokePredicate = Smoke-TaskPredicate
    $residueAfter = Get-ScalarInt "select count(*) from collection_tasks where $smokePredicate;"
    if ($residueAfter -ne 0) {
        throw "smoke collection_tasks residue remains: $residueAfter"
    }
    Mark "smoke collection task residue cleaned" 6 6
} finally {
    Stop-PortForward $scraperPf
    Stop-PortForward $taskPf
}
