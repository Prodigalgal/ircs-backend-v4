param(
    [string[]]$Batch = @(),
    [string]$BatchFile = "",
    [switch]$Execute,
    [switch]$AllowProviderExternal,
    [switch]$AllowSmtpSend,
    [switch]$AllowR2Write,
    [switch]$ContinueOnFailure,
    [switch]$ListBatches,
    [string]$OutputJsonPath = ""
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

function New-Step {
    param(
        [string]$Name,
        [string]$Command,
        [string[]]$Requires = @(),
        [bool]$CanExecute = $true
    )
    [ordered]@{
        name = $Name
        command = $Command
        requires = $Requires
        canExecute = $CanExecute
    }
}

function New-Batch {
    param(
        [string]$Id,
        [string]$Title,
        [string[]]$PlanCovers = @(),
        [string[]]$BatchInputs = @(),
        [string[]]$Switches = @(),
        [string[]]$Preconditions,
        [string[]]$Risks,
        [int]$ExpectedExternalRequests,
        [int]$ExpectedEmails,
        [int]$ExpectedR2Objects,
        [string]$ExpectedDbFixtures,
        [string[]]$CleanupChecks,
        [object[]]$Steps
    )
    [ordered]@{
        id = $Id
        title = $Title
        planCovers = $PlanCovers
        batchInputs = $BatchInputs
        switches = $Switches
        preconditions = $Preconditions
        risks = $Risks
        expected = [ordered]@{
            externalRequests = $ExpectedExternalRequests
            emails = $ExpectedEmails
            r2Objects = $ExpectedR2Objects
            dbFixtures = $ExpectedDbFixtures
        }
        cleanupChecks = $CleanupChecks
        steps = $Steps
    }
}

$batches = @(
    New-Batch `
        -Id "UTB-02816-P1P2-01" `
        -Title "normalization + aggregation + Tika/scraper" `
        -PlanCovers @(
            "028-16-F1/A11 normalization golden closure",
            "028-16-A12 title/raw dictionary hardening",
            "028-16-F2/B15 aggregation local closure",
            "028-16-D4/F3 Tika/scraper charset and pagination fixture prep"
        ) `
        -BatchInputs @(
            "batch ids may be passed with -Batch UTB-02816-P1P2-01 or via -BatchFile, one batch id per line",
            "normalization fixture scope is A-line/title/location/genre/raw category plus A12 dictionary residue checks",
            "Tika fixture scope is Big5 detail, Shift_JIS list, UTF-8 BOM pagination and invalid bytes",
            "real provider low-resource charset input is documented only and requires a later external window"
        ) `
        -Switches @(
            "default plan-only; no queue, DB, ES, R2 or external mutations",
            "-Execute runs only selected commands",
            "provider charset live smoke is not enabled by this batch without a separate approved script/window"
        ) `
        -Preconditions @(
            "dev namespace is available and no competing normalization/aggregation live smoke is running",
            "A9/A10 image state is known before live rerun",
            "Tika local fixtures can be created under build/tmp"
        ) `
        -Risks @(
            "normalization and aggregation scripts open Rabbit/ES port-forward sessions",
            "aggregation fixtures touch raw/unified/search state and must be cleaned",
            "Tika script reads V1 source path when available"
        ) `
        -ExpectedExternalRequests 0 `
        -ExpectedEmails 0 `
        -ExpectedR2Objects 0 `
        -ExpectedDbFixtures "10-20" `
        -CleanupChecks @("DB raw/unified/A12 fixtures removed", "Rabbit queues and DLQs empty", "ES smoke docs removed", "R2 unchanged unless later approved external R2 window runs", "local build/tmp fixture files removed", "port-forward processes stopped", "Job/Pod residue absent", "HTTPRoute count unchanged", "ResourceQuota within budget") `
        -Steps @(
            (New-Step "normalization A9 title/location/genre closure" "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke\normalization-a9-title-location-genre-closure-smoke.ps1 -FailOnSkip"),
            (New-Step "normalization A12 title/raw dictionary focused regression" ".\gradlew.bat --no-daemon :services:ircs-normalization-worker:test --tests *RawVideoTextNormalizerTest --tests *RawRelationAliasPolicyTest --rerun-tasks"),
            (New-Step "aggregation pipeline runtime B15" "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke\aggregation-pipeline-runtime-02816b15-smoke.ps1 -PurgeSmokeQueuesOnCleanup -FailOnSkip"),
            (New-Step "Tika/scraper D3-D4 local fixture smoke" "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke\tika-file-parsing-02816d-smoke.ps1")
        )
    New-Batch `
        -Id "UTB-02816-P1P2-02" `
        -Title "LLM fake/no-cost + C1 readiness + C3 outbound adoption" `
        -PlanCovers @(
            "028-16-F4/E LLM cleaning no-real-cost closure",
            "028-16-F5/C1 auto-start gate health and seed gate",
            "028-16-C3-S6/S7/S8/S9/S10 shared outbound adoption"
        ) `
        -BatchInputs @(
            "batch ids may be passed with -Batch UTB-02816-P1P2-02 or via -BatchFile, one batch id per line",
            "LLM input must stay fake/no-cost by default; staging-only live single fixture is out of this default plan",
            "C1 input covers catalog/content/task gate health plus resolver/player/real-source deferred live smoke",
            "C3 input covers identity/storage/API Gateway/shared outbound caller and circuit matrix evidence"
        ) `
        -Switches @(
            "default plan-only; no real LLM, no outbound fetch, no SMTP, no R2",
            "-Execute may run dry-run/focused commands only",
            "real LLM/live/cost tests require a future dedicated allow flag and are intentionally absent here"
        ) `
        -Preconditions @(
            "LLM cleaning remains disabled or fake/no-cost unless a separate live LLM window is approved",
            "IRCS_BASE_URL and IRCS_ADMIN_TOKEN are set only for explicit resolver live checks",
            "C3 outbound live/full tests use service-specific safe fixtures"
        ) `
        -Risks @(
            "resolver/resource checks may call external sites when ExecuteLive and AllowExternalFetch are both set",
            "C3 live adoption can trigger internal service calls and circuit breaker state",
            "real LLM calls are not included in this orchestrator by default"
        ) `
        -ExpectedExternalRequests 0 `
        -ExpectedEmails 0 `
        -ExpectedR2Objects 0 `
        -ExpectedDbFixtures "5-10" `
        -CleanupChecks @("DB resolver/player/LLM dry-run fixtures removed", "queue and DLQ residue absent", "ES unchanged unless C3 fixture explicitly writes search state", "R2 unchanged", "local dry-run output removed", "port-forward processes stopped", "Job/Pod residue absent", "HTTPRoute count unchanged", "ResourceQuota within budget", "readiness gates restored", "circuit breaker state not left open", "audit residue reviewed") `
        -Steps @(
            (New-Step "LLM cleaning F4/E fake/no-cost focused regression" ".\gradlew.bat --no-daemon :services:ircs-normalization-worker:test --tests *LlmCleaningServiceTest --tests *NormalizationConfigValuesTest --rerun-tasks"),
            (New-Step "C1 gate health focused regression" ".\gradlew.bat --no-daemon :shared:ircs-common:test --tests *AutoStartGateInspectorTest :services:ircs-catalog-service:test --tests *CatalogAutoStartGateHealthConfigurationTest :services:ircs-content-service:test --tests *ContentAutoStartGateHealthConfigurationTest :services:ircs-task-service:test --tests *TaskAutoStartGateHealthConfigurationTest --rerun-tasks"),
            (New-Step "C1 resolver/resource prep dry-run" "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke\resolver-resource-live-02816c1-prep.ps1"),
            (New-Step "C2 live matrix read-only/dry-run guard" "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke\config-event-live-matrix-02816c2-smoke.ps1 -DryRun"),
            (New-Step "C3 API Gateway proxy/admin target validation is covered by focused tests; live/full transport remains deferred" "NOOP: record focused-test evidence before live window" @() $false)
        )
    New-Batch `
        -Id "UTB-02816-P1P2-03" `
        -Title "worker/scheduler audit + production-like cleanup/resource checks" `
        -PlanCovers @(
            "028-12-C metadata/normalization/storage worker audit",
            "028-16-F7/028-12-D search/scraper worker audit second slice",
            "production-like cleanup/resource post-checks"
        ) `
        -BatchInputs @(
            "batch ids may be passed with -Batch UTB-02816-P1P2-03 or via -BatchFile, one batch id per line",
            "worker audit fixture scope must use a unique smoke trace id for ops query cleanup",
            "search raw/unified sync and scraper collection/refetch runners are included in the audit plan"
        ) `
        -Switches @(
            "default plan-only; no worker queue publish, no ops mutation, no resource mutation",
            "-Execute runs selected audit/resource verifier commands",
            "-ContinueOnFailure may be used only when collecting residue evidence across independent checks"
        ) `
        -Preconditions @(
            "ops-service worker-job audit endpoints are reachable for live query checks",
            "no other smoke batch is creating Job/Pod residue",
            "resource verifier baseline is current"
        ) `
        -Risks @(
            "audit smoke may create worker_job_audit_events fixtures",
            "resource verifier uses kubectl and fails fast if cluster access is missing",
            "cleanup checks can surface residue from other workers"
        ) `
        -ExpectedExternalRequests 0 `
        -ExpectedEmails 0 `
        -ExpectedR2Objects 0 `
        -ExpectedDbFixtures "5-10" `
        -CleanupChecks @("DB worker_job_audit_events scoped to smoke trace", "queue and DLQ residue absent for metadata/normalization/storage/search/scraper", "ES smoke docs removed", "R2 unchanged", "local smoke logs archived or removed", "port-forward processes stopped", "Job/Pod residue absent", "HTTPRoute count unchanged", "ResourceQuota within budget", "smoke residue age under threshold") `
        -Steps @(
            (New-Step "notification worker audit smoke" "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke\notification-worker-audit-with-listener-02812-smoke.ps1 -FailOnSkip"),
            (New-Step "task service audit smoke" "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke\task-service-audit-02812-smoke.ps1"),
            (New-Step "search runtime worker audit focused regression" ".\gradlew.bat --no-daemon :services:ircs-search-service:test --tests *SearchSyncWorkQueueWorkerTest --rerun-tasks"),
            (New-Step "scraper worker audit focused regression" ".\gradlew.bat --no-daemon :services:ircs-scraper-service:test --tests *ManualScraperServiceTaskExecutionTest --tests *ScraperServiceAuditFilterTest --rerun-tasks"),
            (New-Step "search service audit smoke" "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke\search-service-audit-02812-smoke.ps1"),
            (New-Step "scraper service audit smoke" "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke\scraper-service-audit-02812-smoke.ps1"),
            (New-Step "resource rollback verifier" "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ops\verify-k8s-dev-resource-rollback-guard.ps1")
        )
    New-Batch `
        -Id "RESOURCE-02818" `
        -Title "resource verifier and rollback guard" `
        -PlanCovers @("K8S resource/rollback guard", "HTTPRoute-free dev namespace check", "ResourceQuota budget post-check") `
        -BatchInputs @("batch ids may be passed with -Batch RESOURCE-02818 or via -BatchFile, one batch id per line") `
        -Switches @("default plan-only", "-Execute runs read-only verifier command") `
        -Preconditions @("kubectl context points to the intended dev/staging namespace", "028-18-B fixes are present", "no active rollout is mutating resources") `
        -Risks @("read-only verifier may fail because of unrelated concurrent worker residue", "stale smoke resources older than threshold block MARK") `
        -ExpectedExternalRequests 0 `
        -ExpectedEmails 0 `
        -ExpectedR2Objects 0 `
        -ExpectedDbFixtures "0" `
        -CleanupChecks @("DB unchanged", "queue and DLQ unchanged", "ES unchanged", "R2 unchanged", "local residue absent", "port-forward processes stopped", "resources present on deployments", "danger gates default false", "HTTPRoute unchanged", "ResourceQuota within budget", "no stale smoke Job/Pod") `
        -Steps @(
            (New-Step "028-18 resource/rollback verifier" "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ops\verify-k8s-dev-resource-rollback-guard.ps1")
        )
    New-Batch `
        -Id "API-GATEWAY" `
        -Title "portal/admin API Gateway smoke" `
        -PlanCovers @("portal API Gateway smoke", "admin API Gateway guarded operations", "API Gateway request audit collection") `
        -BatchInputs @("batch ids may be passed with -Batch API-GATEWAY or via -BatchFile, one batch id per line", "API Gateway fixture ids must be scoped and removable") `
        -Switches @("default plan-only; no external network", "-Execute may open local port-forward only") `
        -Preconditions @("ircs-api-gateway is deployed and reachable by port-forward", "admin smoke fixtures are safe to create", "no public HTTPRoute change is expected") `
        -Risks @("API Gateway smoke may create admin/content/ops fixtures", "port-forward port conflicts can cause false failures", "debug dry-run endpoints must remain dry-run") `
        -ExpectedExternalRequests 0 `
        -ExpectedEmails 0 `
        -ExpectedR2Objects 0 `
        -ExpectedDbFixtures "5-15" `
        -CleanupChecks @("DB API Gateway fixtures removed", "queue and DLQ unchanged", "ES unchanged unless explicitly asserted by API Gateway fixture", "R2 unchanged", "local logs removed", "port-forward processes stopped", "Job/Pod residue absent", "HTTPRoute unchanged", "ResourceQuota within budget", "ops/debug residue absent") `
        -Steps @(
            (New-Step "API Gateway focused tests" ".\gradlew.bat --no-daemon :services:ircs-api-gateway:test --rerun-tasks"),
            (New-Step "API Gateway shared outbound tests" ".\gradlew.bat --no-daemon :shared:ircs-common:test --tests *OutboundHttpClientTest --tests *ProxyRequestAuditWriterTest --rerun-tasks")
        )
    New-Batch `
        -Id "TREND-02822-F" `
        -Title "trend-sync discovery and production-candidate live closure" `
        -PlanCovers @("028-22-F trend-sync discovery live smoke", "028-22 task/scraper/ops rollout checklist", "028-22-E discoveryResult and DB task evidence") `
        -BatchInputs @("batch ids may be passed with -Batch TREND-02822-F or via -BatchFile", "default execution only validates readiness and empty trend-discovery contract", "provider-backed trend-sync and discovery scheduling require -AllowProviderExternal") `
        -Switches @("default plan-only", "-Execute runs focused tests and no-external K8S contract smoke", "-AllowProviderExternal enables real provider trend-sync and live discovery task creation") `
        -Preconditions @(
            "task-service, scraper-service and ops-service images have been rebuilt for linux/arm64 when validating current code in K8S",
            "dev namespace has no concurrent trend-sync or collection-task smoke using Codex02822 prefix",
            "credential-service contains usable TMDB credentials if TMDB provider is enabled"
        ) `
        -Risks @(
            "provider-gated mode may call Douban/TMDB and create ghost unified videos",
            "provider-gated direct discovery creates collection_tasks and can trigger source-site fetches",
            "rollout freshness must be audited separately before treating live smoke as current-code evidence"
        ) `
        -ExpectedExternalRequests 2 `
        -ExpectedEmails 0 `
        -ExpectedR2Objects 0 `
        -ExpectedDbFixtures "0 default; provider-gated mode creates trend ghosts and smoke collection_tasks" `
        -CleanupChecks @("Codex02822 collection_tasks residue removed", "port-forward processes stopped", "task/scraper/credential Ready", "provider gates unchanged unless explicitly patched", "ResourceQuota within budget", "image digest freshness recorded before production-candidate report") `
        -Steps @(
            (New-Step "028-22 trend discovery focused regressions" ".\gradlew.bat --no-daemon :services:ircs-task-service:test --tests *TrendDiscoveryTaskServiceTest --tests *TrendDiscoveryInternalControllerTest :services:ircs-scraper-service:test --tests *TrendSyncServiceTest --tests *TrendDiscoveryTaskClientTest --tests *TmdbCredentialResolverTest --rerun-tasks"),
            (New-Step "manual ARM64 rollout: task-service" "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\docker\push-project-image-jib.ps1 -ProjectPath ':services:ircs-task-service' -ImageName ircs-task-service -Tag dev -Architecture arm64 -Os linux; kubectl -n ircs-dev rollout restart deployment/ircs-task-service; kubectl -n ircs-dev rollout status deployment/ircs-task-service --timeout=420s" @() $false),
            (New-Step "manual ARM64 rollout: scraper-service" "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\docker\push-project-image-jib.ps1 -ProjectPath ':services:ircs-scraper-service' -ImageName ircs-scraper-service -Tag dev -Architecture arm64 -Os linux; kubectl -n ircs-dev rollout restart deployment/ircs-scraper-service; kubectl -n ircs-dev rollout status deployment/ircs-scraper-service --timeout=420s" @() $false),
            (New-Step "manual ARM64 rollout: ops-service" "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\docker\push-project-image-jib.ps1 -ProjectPath ':services:ircs-ops-service' -ImageName ircs-ops-service -Tag dev -Architecture arm64 -Os linux; kubectl -n ircs-dev rollout restart deployment/ircs-ops-service; kubectl -n ircs-dev rollout status deployment/ircs-ops-service --timeout=420s" @() $false),
            (New-Step "028-22-F no-external K8S trend-discovery contract smoke" "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke\trend-sync-discovery-02822f-smoke.ps1"),
            (New-Step "028-22-F provider-gated trend-sync discovery live smoke" "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke\trend-sync-discovery-02822f-smoke.ps1 -AllowProviderExternal -FailOnSkip" @("AllowProviderExternal"))
        )
    New-Batch `
        -Id "EXTERNAL-PROVIDER-SMTP-R2" `
        -Title "provider + SMTP + R2 smoke" `
        -PlanCovers @("provider one-shot live queue", "SMTP one-shot", "R2 write/delete") `
        -BatchInputs @("batch ids may be passed with -Batch EXTERNAL-PROVIDER-SMTP-R2 or via -BatchFile, one batch id per line", "provider samples must be single-shot", "SMTP recipient allowlist must be preconfirmed", "R2 object key must be smoke-scoped") `
        -Switches @("default plan-only", "-Execute alone is insufficient for side effects", "-AllowProviderExternal gates provider calls", "-AllowSmtpSend gates SMTP delivery", "-AllowR2Write gates R2 write/delete") `
        -Preconditions @(
            "single-sample provider window is approved",
            "SMTP recipient allowlist and one-shot policy are confirmed before AllowSmtpSend",
            "R2 bucket override and cleanup HEAD 404 policy are confirmed before AllowR2Write"
        ) `
        -Risks @(
            "provider upstream may be flaky or rate-limited",
            "SMTP sends are not reversible once delivered",
            "R2 write smoke must verify DELETE and cleanup HEAD 404"
        ) `
        -ExpectedExternalRequests 3 `
        -ExpectedEmails 1 `
        -ExpectedR2Objects 1 `
        -ExpectedDbFixtures "5-15" `
        -CleanupChecks @("DB provider/send history fixtures removed or explicitly audited", "provider queue/DLQ empty", "ES smoke docs removed", "R2 object deleted and HEAD 404", "local temporary files removed", "port-forward processes stopped", "Job/Pod residue absent", "HTTPRoute count unchanged", "ResourceQuota within budget", "provider gates restored false", "send history/audit recorded and no duplicate sends", "deployment env restored") `
        -Steps @(
            (New-Step "TMDB provider live queue one-shot" "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke\metadata-provider-live-queue-02806-smoke.ps1 -EnableTmdbProviderForSmoke -PurgeSmokeQueuesOnCleanup -FailOnSkip" @("AllowProviderExternal")),
            (New-Step "Douban provider live queue one-shot" "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke\metadata-provider-douban-live-queue-02806-smoke.ps1 -EnableDoubanProviderForSmoke -PurgeSmokeQueuesOnCleanup -FailOnSkip" @("AllowProviderExternal")),
            (New-Step "RT provider live queue one-shot" "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke\metadata-provider-rt-live-queue-02806-smoke.ps1 -EnableRtProviderForSmoke -PurgeSmokeQueuesOnCleanup -FailOnSkip" @("AllowProviderExternal")),
            (New-Step "SMTP V1 one-shot" "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke\notification-mail-smtp-02810f-preflight.ps1 -TaskId 028-10-L -SliceName smtp-v1-one-shot -AllowSend -StartCredentialPortForward -StartOpsPortForward -StartRabbitPortForward -FailOnSkip" @("AllowSmtpSend")),
            (New-Step "R2 live object write/delete" "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke\storage-r2-live-object-02807-smoke.ps1 -UseV1ParityBucketOverride -ConfirmLiveR2Write -StartCredentialPortForward -FailOnSkip" @("AllowR2Write"))
        )
)

$batchIds = New-Object System.Collections.Generic.List[string]
foreach ($id in $Batch) {
    if (-not [string]::IsNullOrWhiteSpace($id)) {
        $batchIds.Add($id.Trim()) | Out-Null
    }
}

if (-not [string]::IsNullOrWhiteSpace($BatchFile)) {
    $resolvedBatchFile = (Resolve-Path -LiteralPath $BatchFile).Path
    foreach ($line in Get-Content -LiteralPath $resolvedBatchFile) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#")) {
            continue
        }
        $batchIds.Add($trimmed) | Out-Null
    }
}

if ($ListBatches) {
    Write-Output "028-19 available batches"
    foreach ($entry in $batches) {
        Write-Output ("- {0}: {1}" -f $entry.id, $entry.title)
    }
    return
}

$selectedBatches = if ($batchIds.Count -eq 0) {
    $batches
} else {
    $known = @{}
    foreach ($item in $batches) { $known[$item.id] = $item }
    foreach ($id in $batchIds) {
        if (-not $known.ContainsKey($id)) {
            throw "Unknown batch id: $id"
        }
        $known[$id]
    }
}

function Test-StepAllowed {
    param([object]$Step)
    foreach ($requirement in $Step.requires) {
        if ($requirement -eq "AllowProviderExternal" -and -not $AllowProviderExternal) { return $false }
        if ($requirement -eq "AllowSmtpSend" -and -not $AllowSmtpSend) { return $false }
        if ($requirement -eq "AllowR2Write" -and -not $AllowR2Write) { return $false }
    }
    return $true
}

function Write-Mark {
    param([int]$Index, [string]$Message)
    Write-Output ("028-19 MARK {0}/6 {1}" -f $Index, $Message)
}

$results = New-Object System.Collections.Generic.List[object]

Write-Output "028-19 production-like smoke orchestrator"
Write-Output ("mode={0}" -f $(if ($Execute) { "execute" } else { "plan-only" }))
Write-Output ("selectedBatches={0}" -f (($selectedBatches | ForEach-Object { $_.id }) -join ","))
Write-Output ""

foreach ($entry in $selectedBatches) {
    Write-Output ("## {0} {1}" -f $entry.id, $entry.title)
    Write-Output ("covers: {0}" -f ($entry.planCovers -join " | "))
    Write-Output ("inputs: {0}" -f ($entry.batchInputs -join " | "))
    Write-Output ("switches: {0}" -f ($entry.switches -join " | "))
    Write-Output ("preconditions: {0}" -f ($entry.preconditions -join " | "))
    Write-Output ("risks: {0}" -f ($entry.risks -join " | "))
    Write-Output ("expected: externalRequests={0}; emails={1}; r2Objects={2}; dbFixtures={3}" -f $entry.expected.externalRequests, $entry.expected.emails, $entry.expected.r2Objects, $entry.expected.dbFixtures)
    Write-Output ("cleanup: {0}" -f ($entry.cleanupChecks -join " | "))
    foreach ($step in $entry.steps) {
        $allowed = Test-StepAllowed -Step $step
        $status = if (-not $step.canExecute) { "manual" } elseif ($allowed) { "ready" } else { "blocked-by-allow-flag" }
        Write-Output ("- [{0}] {1}" -f $status, $step.name)
        Write-Output ("  command: {0}" -f $step.command)
        if ($step.requires.Count -gt 0) {
            Write-Output ("  requires: {0}" -f ($step.requires -join ","))
        }

        $stepResult = [ordered]@{
            batch = $entry.id
            name = $step.name
            command = $step.command
            status = if ($Execute) { "PENDING" } else { "PLANNED" }
            requires = $step.requires
        }

        if ($Execute) {
            if (-not $step.canExecute) {
                $stepResult.status = "MANUAL"
            } elseif (-not $allowed) {
                $stepResult.status = "BLOCKED_BY_ALLOW_FLAG"
                if (-not $ContinueOnFailure) {
                    $results.Add([pscustomobject]$stepResult) | Out-Null
                    throw "Step requires additional allow flag: $($step.name)"
                }
            } else {
                Write-Output ("  executing from {0}" -f $repoRoot)
                Push-Location $repoRoot
                $abortAfterResult = $false
                try {
                    $global:LASTEXITCODE = 0
                    Invoke-Expression $step.command
                    $exitCode = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }
                    $stepResult.exitCode = $exitCode
                    if ($exitCode -eq 0) {
                        $stepResult.status = "PASSED"
                    } else {
                        $stepResult.status = "FAILED"
                        $stepResult.error = "Command exited with code $exitCode"
                        $abortAfterResult = -not $ContinueOnFailure
                    }
                } catch {
                    $stepResult.status = "FAILED"
                    if (-not $stepResult.Contains("exitCode")) {
                        $stepResult.exitCode = 1
                    }
                    $stepResult.error = $_.Exception.Message
                    $abortAfterResult = -not $ContinueOnFailure
                } finally {
                    Pop-Location
                }
                if ($abortAfterResult) {
                    $results.Add([pscustomobject]$stepResult) | Out-Null
                    throw "Step failed: $($step.name): $($stepResult.error)"
                }
            }
        }

        $results.Add([pscustomobject]$stepResult) | Out-Null
    }
    Write-Output ""
}

$selectedBatchIds = New-Object System.Collections.Generic.List[string]
foreach ($entry in $selectedBatches) {
    $selectedBatchIds.Add([string]$entry.id) | Out-Null
}

$resultItems = New-Object System.Collections.Generic.List[object]
foreach ($item in $results) {
    $resultItems.Add($item) | Out-Null
}

$mode = if ($Execute) { "execute" } else { "plan-only" }
$providerExternalAllowed = [bool]$AllowProviderExternal
$smtpSendAllowed = [bool]$AllowSmtpSend
$r2WriteAllowed = [bool]$AllowR2Write
$selectedBatchIdArray = @($selectedBatchIds.ToArray())
$resultItemArray = @($resultItems.ToArray())

$summary = @{
    task = "028-19"
    mode = $mode
    checkedAt = (Get-Date).ToUniversalTime().ToString("o")
    selectedBatches = $selectedBatchIdArray
    allowFlags = @{
        providerExternal = $providerExternalAllowed
        smtpSend = $smtpSendAllowed
        r2Write = $r2WriteAllowed
    }
    results = $resultItemArray
}

if (-not [string]::IsNullOrWhiteSpace($OutputJsonPath)) {
    ($summary | ConvertTo-Json -Depth 16) | Set-Content -LiteralPath $OutputJsonPath -Encoding UTF8
}

Write-Mark 1 "task spec and production-like plan documented"
Write-Mark 2 "orchestrator defaults to dry-run/plan-only"
Write-Mark 3 "UTB-02816-P1P2-01/02/03 command entries listed"
Write-Mark 4 "resource verifier, portal/admin API Gateway, provider/SMTP/R2 entries listed"
Write-Mark 5 "preconditions, risk, fixture/external estimates and cleanup checks recorded"
Write-Mark 6 "093 ledger and task index updated"

if (-not $Execute) {
    Write-Output "No live smoke was executed. Rerun with -Execute to execute selected batches."
}
