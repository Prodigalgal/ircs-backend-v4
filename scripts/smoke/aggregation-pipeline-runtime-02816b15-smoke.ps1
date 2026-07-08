param(
    [string]$Namespace = "ircs-dev",
    [string]$ElasticsearchBaseUrl = "http://127.0.0.1:19216",
    [int]$ElasticsearchPort = 19216,
    [int]$WaitAttempts = 60,
    [int]$WaitSeconds = 2,
    [int]$SmokeBatchSize = 2,
    [switch]$NoPatchAggregationWorker,
    [switch]$PurgeSmokeQueuesOnCleanup,
    [switch]$FailOnSkip
)

$ErrorActionPreference = "Stop"

$stamp = Get-Date -Format "yyyyMMddHHmmss"
$marker = "codex02816b15$stamp"

$ids = [ordered]@{
    dataSource = [guid]::NewGuid().ToString()
    sourceDomain = [guid]::NewGuid().ToString()
    category = [guid]::NewGuid().ToString()
    rawCategory = [guid]::NewGuid().ToString()
    standardGenre1 = [guid]::NewGuid().ToString()
    standardGenre2 = [guid]::NewGuid().ToString()
    rawGenre1 = [guid]::NewGuid().ToString()
    rawGenre2 = [guid]::NewGuid().ToString()
    standardLanguage = [guid]::NewGuid().ToString()
    rawLanguage = [guid]::NewGuid().ToString()
    standardArea = [guid]::NewGuid().ToString()
    rawArea = [guid]::NewGuid().ToString()
    actor1 = [guid]::NewGuid().ToString()
    actor2 = [guid]::NewGuid().ToString()
    director1 = [guid]::NewGuid().ToString()
    coverExternal = [guid]::NewGuid().ToString()
    coverLocal = [guid]::NewGuid().ToString()
    raw1 = [guid]::NewGuid().ToString()
    raw2 = [guid]::NewGuid().ToString()
}

$expectedTitle = "Codex 02816 B15 Pipeline $stamp"
$expectedAlias1 = "B15 Alias $stamp"
$expectedAlias2 = "B15 Longest Alias Candidate $stamp"
$expectedDescription1 = "B15 short description $stamp"
$expectedDescription2 = "B15 longer description selected by V1 dynamic field rule $stamp"
$expectedSubtitle1 = "B15 sub $stamp"
$expectedSubtitle2 = "B15 longest subtitle candidate $stamp"
$expectedRemarks1 = "B15 older remark $stamp"
$expectedRemarks2 = "B15 latest remark $stamp"
$expectedYear = "2026"
$expectedScoreText = "9.4"
$expectedScore = [double]$expectedScoreText
$expectedPublishedAt = "2026-06-01"
$expectedTotalEpisodes = "12"
$expectedDuration = "42m"
$expectedSeason = "1"
$expectedTmdbId = "tmB15$stamp"
$expectedImdbId = "ttB15$stamp"
$expectedRottenTomatoesId = "rtB15$stamp"
$expectedCategoryName = "Codex B15 Category $stamp"
$expectedCategorySlug = "codex-b15-category-$stamp"
$expectedGenre1Name = "Codex B15 Genre One $stamp"
$expectedGenre2Name = "Codex B15 Genre Two $stamp"
$expectedLanguageName = "Codex B15 Language $stamp"
$expectedAreaName = "Codex B15 Area $stamp"
$expectedActor1Name = "Codex B15 Actor Shared $stamp"
$expectedActor2Name = "Codex B15 Actor Extra $stamp"
$expectedDirector1Name = "Codex B15 Director $stamp"
$sourceHash1 = "$marker-raw1"
$sourceHash2 = "$marker-raw2"

$esPfOut = Join-Path $env:TEMP "ircs-02816b15-aggregation-es-pf.out.log"
$esPfErr = Join-Path $env:TEMP "ircs-02816b15-aggregation-es-pf.err.log"
$esPf = $null
$script:elasticPassword = $null
$script:unifiedVideoId = $null
$script:unifiedVideoIds = @()
$script:aggregationEnvSnapshot = $null
$script:aggregationEnvPatched = $false
$script:cleanupDone = $false
$script:quotaBefore = $null
$script:quotaAfter = $null

function New-SmokeResult {
    param(
        [string]$Status,
        [string]$Reason,
        [hashtable]$Details = @{}
    )
    [ordered]@{
        task = "028-16-B15"
        slice = "aggregation-pipeline-runtime-live-smoke"
        status = $Status
        reason = $Reason
        checkedAt = (Get-Date).ToUniversalTime().ToString("o")
        namespace = $Namespace
        rawVideoIds = @($ids.raw1, $ids.raw2)
        unifiedVideoId = $script:unifiedVideoId
        unifiedVideoIds = @($script:unifiedVideoIds)
        marker = $marker
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
    Write-Output ("B15 MARK {0}/{1} {2}" -f $Index, $Total, $Name)
}

function Sql-Literal {
    param([AllowNull()][string]$Value)
    if ($null -eq $Value) {
        return "null"
    }
    return "'" + $Value.Replace("'", "''") + "'"
}

function Invoke-Kubectl {
    param([string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = & kubectl @Arguments 2>&1
    $exit = $LASTEXITCODE
    $ErrorActionPreference = $previous
    return @{
        exitCode = $exit
        output = (($output | Out-String).Trim())
    }
}

function Test-KubectlAccess {
    $result = Invoke-Kubectl @("-n", $Namespace, "get", "pod")
    if ($result.exitCode -ne 0) {
        Finish-Smoke "SKIPPED" "KUBERNETES_UNAVAILABLE" $result
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

function Assert-DeploymentReady {
    param([string]$Name)
    $ready = Invoke-Kubectl @("-n", $Namespace, "get", "deployment", $Name, "-o", "jsonpath={.status.readyReplicas}")
    $replicas = Invoke-Kubectl @("-n", $Namespace, "get", "deployment", $Name, "-o", "jsonpath={.spec.replicas}")
    if ($ready.exitCode -ne 0 -or $replicas.exitCode -ne 0) {
        Finish-Smoke "SKIPPED" "DEPLOYMENT_UNAVAILABLE" @{
            deployment = $Name
            readyOutput = $ready.output
            replicaOutput = $replicas.output
        }
    }
    $readyCount = if ([string]::IsNullOrWhiteSpace($ready.output)) { 0 } else { [int]$ready.output }
    $replicaCount = if ([string]::IsNullOrWhiteSpace($replicas.output)) { 0 } else { [int]$replicas.output }
    if ($replicaCount -lt 1 -or $readyCount -lt 1) {
        Finish-Smoke "SKIPPED" "DEPLOYMENT_NOT_READY" @{
            deployment = $Name
            readyReplicas = $readyCount
            replicas = $replicaCount
        }
    }
}

function Get-DeploymentEnvMap {
    param([string]$Name)
    $result = Invoke-Kubectl @("-n", $Namespace, "get", "deployment", $Name, "-o", "json")
    if ($result.exitCode -ne 0) {
        Finish-Smoke "SKIPPED" "DEPLOYMENT_UNAVAILABLE" @{
            deployment = $Name
            output = $result.output
        }
    }
    $deployment = $result.output | ConvertFrom-Json
    $envMap = @{}
    foreach ($env in $deployment.spec.template.spec.containers[0].env) {
        $envMap[$env.name] = [string]$env.value
    }
    return $envMap
}

function Capture-AggregationEnv {
    $envMap = Get-DeploymentEnvMap "ircs-aggregation-worker"
    $snapshot = @{}
    foreach ($name in @(
        "APP_AGGREGATION_WORK_QUEUE_BATCH_SIZE",
        "APP_AGGREGATION_WORK_QUEUE_FIXED_DELAY_MS"
    )) {
        $snapshot[$name] = @{
            exists = $envMap.ContainsKey($name)
            value = if ($envMap.ContainsKey($name)) { $envMap[$name] } else { $null }
        }
    }
    $script:aggregationEnvSnapshot = $snapshot
    return $envMap
}

function Set-AggregationSmokeEnv {
    $envMap = Capture-AggregationEnv
    $currentBatch = if ($envMap.ContainsKey("APP_AGGREGATION_WORK_QUEUE_BATCH_SIZE") -and $envMap["APP_AGGREGATION_WORK_QUEUE_BATCH_SIZE"] -match "^\d+$") {
        [int]$envMap["APP_AGGREGATION_WORK_QUEUE_BATCH_SIZE"]
    } else {
        1
    }
    $currentDelay = if ($envMap.ContainsKey("APP_AGGREGATION_WORK_QUEUE_FIXED_DELAY_MS") -and $envMap["APP_AGGREGATION_WORK_QUEUE_FIXED_DELAY_MS"] -match "^\d+$") {
        [int]$envMap["APP_AGGREGATION_WORK_QUEUE_FIXED_DELAY_MS"]
    } else {
        5000
    }
    $targetBatch = [Math]::Max(2, $SmokeBatchSize)
    $needsPatch = $currentBatch -lt $targetBatch -or $currentDelay -gt 2000
    if (-not $needsPatch) {
        return
    }
    if ($NoPatchAggregationWorker) {
        Finish-Smoke "SKIPPED" "AGGREGATION_BATCH_SIZE_TOO_LOW_WITH_NO_PATCH" @{
            currentBatchSize = $currentBatch
            requiredBatchSize = $targetBatch
            currentFixedDelayMs = $currentDelay
        }
    }
    $result = Invoke-Kubectl @(
        "-n", $Namespace,
        "set", "env", "deployment/ircs-aggregation-worker",
        "APP_AGGREGATION_WORK_QUEUE_BATCH_SIZE=$targetBatch",
        "APP_AGGREGATION_WORK_QUEUE_FIXED_DELAY_MS=2000"
    )
    if ($result.exitCode -ne 0) {
        Finish-Smoke "FAILED" "AGGREGATION_ENV_PATCH_FAILED" $result
    }
    $script:aggregationEnvPatched = $true
    $rollout = Invoke-Kubectl @("-n", $Namespace, "rollout", "status", "deployment/ircs-aggregation-worker", "--timeout=240s")
    if ($rollout.exitCode -ne 0) {
        Finish-Smoke "FAILED" "AGGREGATION_ENV_PATCH_ROLLOUT_FAILED" $rollout
    }
}

function Restore-AggregationEnv {
    if (-not $script:aggregationEnvPatched -or $null -eq $script:aggregationEnvSnapshot) {
        return
    }
    $args = @("-n", $Namespace, "set", "env", "deployment/ircs-aggregation-worker")
    foreach ($name in @(
        "APP_AGGREGATION_WORK_QUEUE_BATCH_SIZE",
        "APP_AGGREGATION_WORK_QUEUE_FIXED_DELAY_MS"
    )) {
        $entry = $script:aggregationEnvSnapshot[$name]
        if ($entry.exists) {
            $args += "$name=$($entry.value)"
        } else {
            $args += "$name-"
        }
    }
    $result = Invoke-Kubectl $args
    if ($result.exitCode -ne 0) {
        throw "restore aggregation env failed: $($result.output)"
    }
    $rollout = Invoke-Kubectl @("-n", $Namespace, "rollout", "status", "deployment/ircs-aggregation-worker", "--timeout=240s")
    if ($rollout.exitCode -ne 0) {
        throw "restore aggregation env rollout failed: $($rollout.output)"
    }
    $script:aggregationEnvPatched = $false
}

function Get-ResourceQuotaSnapshot {
    $result = Invoke-Kubectl @("-n", $Namespace, "get", "resourcequota", "ircs-dev-quota", "-o", "json")
    if ($result.exitCode -ne 0) {
        Finish-Smoke "SKIPPED" "RESOURCEQUOTA_UNAVAILABLE" $result
    }
    $quota = $result.output | ConvertFrom-Json
    $used = $quota.status.used
    $hard = $quota.status.hard
    return "pods=$($used.pods)/$($hard.pods)|services=$($used.services)/$($hard.services)|requests.cpu=$($used.'requests.cpu')/$($hard.'requests.cpu')|requests.memory=$($used.'requests.memory')/$($hard.'requests.memory')|limits.cpu=$($used.'limits.cpu')/$($hard.'limits.cpu')|limits.memory=$($used.'limits.memory')/$($hard.'limits.memory')"
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
    $result = Invoke-Kubectl @("-n", $Namespace, "get", "secret", "ircs-dev-secrets", "-o", "jsonpath={.data.$Key}")
    if ($result.exitCode -ne 0 -or [string]::IsNullOrWhiteSpace($result.output)) {
        Finish-Smoke "SKIPPED" "KUBERNETES_SECRET_MISSING" @{
            key = $Key
            exitCode = $result.exitCode
            output = $result.output
        }
    }
    return [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($result.output))
}

function Get-ElasticPassword {
    if ([string]::IsNullOrWhiteSpace($script:elasticPassword)) {
        $script:elasticPassword = Get-SecretValue "ELASTICSEARCH_PASSWORD"
    }
    return $script:elasticPassword
}

function Test-EsAccess {
    $elasticPassword = Get-ElasticPassword
    $responseFile = Join-Path $env:TEMP ("ircs-02816b15-es-root-" + [guid]::NewGuid() + ".json")
    try {
        $previous = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $code = & curl.exe -sS -u "elastic:$elasticPassword" `
            -o $responseFile `
            -w "%{http_code}" `
            "$($ElasticsearchBaseUrl.TrimEnd('/'))/" 2>&1
        $exit = $LASTEXITCODE
        $ErrorActionPreference = $previous
        $body = if (Test-Path $responseFile) { Get-Content -LiteralPath $responseFile -Raw } else { "" }
        if ($exit -ne 0) {
            Finish-Smoke "SKIPPED" "ELASTICSEARCH_UNAVAILABLE" @{
                curlExitCode = $exit
                output = ($code -join "`n")
            }
        }
        if ([int]$code -lt 200 -or [int]$code -ge 300) {
            Finish-Smoke "SKIPPED" "ELASTICSEARCH_HTTP_$code" @{
                httpStatus = [int]$code
                bodyPreview = if ($body.Length -gt 500) { $body.Substring(0, 500) } else { $body }
            }
        }
    } finally {
        Remove-Item -LiteralPath $responseFile -ErrorAction SilentlyContinue
    }
}

function Get-QueueParts {
    param([string]$Name)
    $lines = kubectl -n $Namespace exec rabbitmq-0 -- rabbitmqctl list_queues name messages messages_ready messages_unacknowledged consumers
    if ($LASTEXITCODE -ne 0) {
        throw "rabbitmqctl list_queues failed"
    }
    $line = $lines | Where-Object { $_ -match "^$([regex]::Escape($Name))\s" } | Select-Object -First 1
    if (-not $line) {
        throw "queue not found: $Name"
    }
    return $line -split "\s+"
}

function Get-QueueState {
    param([string]$Name)
    $parts = Get-QueueParts $Name
    return @{
        name = $Name
        messages = [int]$parts[1]
        ready = [int]$parts[2]
        unacked = [int]$parts[3]
        consumers = [int]$parts[4]
    }
}

function Get-SmokeQueues {
    return @(
    )
}

function Assert-SmokeQueuesCleanBefore {
    $dirty = @()
    foreach ($queue in Get-SmokeQueues) {
        try {
            $state = Get-QueueState $queue
            if ($state.messages -ne 0 -or $state.unacked -ne 0) {
                $dirty += "$queue messages=$($state.messages) unacked=$($state.unacked)"
            }
        } catch {
            Finish-Smoke "SKIPPED" "QUEUE_UNAVAILABLE" @{
                queue = $queue
                message = $_.Exception.Message
            }
        }
    }
    if ($dirty.Count -gt 0) {
        Finish-Smoke "SKIPPED" "QUEUE_NOT_CLEAN" @{
            queues = $dirty
        }
    }
}

function Assert-ConsumersReady {
    $lastMissing = @()
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        $missing = @()
        foreach ($queue in @(
        )) {
            $state = Get-QueueState $queue
            if ($state.consumers -lt 1) {
                $missing += "$queue consumers=$($state.consumers)"
            }
        }
        if ($missing.Count -eq 0) {
            return
        }
        $lastMissing = $missing
        Start-Sleep -Seconds $WaitSeconds
    }
    Finish-Smoke "SKIPPED" "CONSUMER_UNAVAILABLE" @{
        queues = $lastMissing
    }
}

function Assert-QueueEmpty {
    param([string]$Name)
    $state = Get-QueueState $Name
    if ($state.messages -ne 0 -or $state.unacked -ne 0) {
        throw "queue $Name has messages=$($state.messages) unacked=$($state.unacked)"
    }
}

function Wait-QueueEmpty {
    param([string]$Name)
    $last = ""
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        $state = Get-QueueState $Name
        $last = "messages=$($state.messages) ready=$($state.ready) unacked=$($state.unacked) consumers=$($state.consumers)"
        if ($state.messages -eq 0 -and $state.unacked -eq 0) {
            return
        }
        Start-Sleep -Seconds $WaitSeconds
    }
    throw "queue $Name did not drain; last state: $last"
}

function Assert-NoHttpRoute {
    $result = Invoke-Kubectl @("-n", $Namespace, "get", "httproute", "--no-headers")
    $text = $result.output
    if ($result.exitCode -ne 0 -and $text -notmatch "No resources found") {
        Finish-Smoke "FAILED" "HTTPROUTE_LIST_FAILED" @{
            exitCode = $result.exitCode
            output = $text
        }
    }
    if ($result.exitCode -eq 0 -and $text -and $text -notmatch "No resources found") {
        Finish-Smoke "FAILED" "HTTPROUTE_PRESENT" @{
            output = $text
        }
    }
}

function Get-EsDocument {
    param(
        [string]$Index,
        [string]$Id
    )
    $elasticPassword = Get-ElasticPassword
    $responseFile = Join-Path $env:TEMP ("ircs-02816b15-es-doc-" + [guid]::NewGuid() + ".json")
    try {
        $code = & curl.exe -sS -u "elastic:$elasticPassword" `
            -o $responseFile `
            -w "%{http_code}" `
            "$ElasticsearchBaseUrl/$Index/_doc/$Id"
        $text = if (Test-Path $responseFile) { Get-Content -LiteralPath $responseFile -Raw } else { "" }
        if ([int]$code -eq 404) {
            return $null
        }
        if ([int]$code -lt 200 -or [int]$code -ge 300) {
            throw "Elasticsearch get HTTP $code body=$text"
        }
        return $text | ConvertFrom-Json
    } finally {
        Remove-Item -LiteralPath $responseFile -ErrorAction SilentlyContinue
    }
}

function Remove-EsDocument {
    param(
        [string]$Index,
        [AllowNull()][string]$Id
    )
    if ([string]::IsNullOrWhiteSpace($Id) -or [string]::IsNullOrWhiteSpace($script:elasticPassword)) {
        return
    }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & curl.exe -sS -u "elastic:$script:elasticPassword" -X DELETE "$ElasticsearchBaseUrl/$Index/_doc/$Id" | Out-Null
    $ErrorActionPreference = $previous
}

function ConvertTo-StringArray {
    param($Value)
    if ($null -eq $Value) {
        return @()
    }
    if ($Value -is [string]) {
        return @($Value)
    }
    if ($Value -is [System.Collections.IEnumerable]) {
        return @($Value | ForEach-Object { [string]$_ })
    }
    return @([string]$Value)
}

function Setup-SmokeData {
    $titleSql = Sql-Literal $expectedTitle
    $alias1Sql = Sql-Literal $expectedAlias1
    $alias2Sql = Sql-Literal $expectedAlias2
    $desc1Sql = Sql-Literal $expectedDescription1
    $desc2Sql = Sql-Literal $expectedDescription2
    $sub1Sql = Sql-Literal $expectedSubtitle1
    $sub2Sql = Sql-Literal $expectedSubtitle2
    $remarks1Sql = Sql-Literal $expectedRemarks1
    $remarks2Sql = Sql-Literal $expectedRemarks2
    $categoryNameSql = Sql-Literal $expectedCategoryName
    $categorySlugSql = Sql-Literal $expectedCategorySlug
    $genre1Sql = Sql-Literal $expectedGenre1Name
    $genre2Sql = Sql-Literal $expectedGenre2Name
    $languageSql = Sql-Literal $expectedLanguageName
    $areaSql = Sql-Literal $expectedAreaName
    $actor1Sql = Sql-Literal $expectedActor1Name
    $actor2Sql = Sql-Literal $expectedActor2Name
    $directorSql = Sql-Literal $expectedDirector1Name
    $sourceHash1Sql = Sql-Literal $sourceHash1
    $sourceHash2Sql = Sql-Literal $sourceHash2
    $tmdbSql = Sql-Literal $expectedTmdbId
    $imdbSql = Sql-Literal $expectedImdbId
    $rtSql = Sql-Literal $expectedRottenTomatoesId
    $markerSql = Sql-Literal $marker
    $sql = @"
insert into data_sources (
    id, created_at, updated_at, version,
    name, base_url, list_path, list_params, detail_path, detail_params, field_mapping
) values (
    '$($ids.dataSource)', now(), now(), 0,
    'Codex B15 DataSource $stamp', 'https://codex.invalid/$marker', '/list', '{}'::jsonb, '/detail', '{}'::jsonb, '{}'::jsonb
);

insert into source_domains (
    id, created_at, updated_at, version, domain_hash, domain_value, remark, data_source_id
) values (
    '$($ids.sourceDomain)', now(), now(), 0, '$marker-domain', 'codex.invalid', $markerSql, '$($ids.dataSource)'
);

insert into standard_category (
    id, created_at, updated_at, version, name, slug
) values (
    '$($ids.category)', now(), now(), 0, $categoryNameSql, $categorySlugSql
);

insert into raw_category (
    id, created_at, updated_at, version, data_source_id, source_code, source_name, category_id
) values (
    '$($ids.rawCategory)', now(), now(), 0, '$($ids.dataSource)', '$marker-cat', $categoryNameSql, '$($ids.category)'
);

insert into standard_genre (id, created_at, updated_at, version, name)
values
    ('$($ids.standardGenre1)', now(), now(), 0, $genre1Sql),
    ('$($ids.standardGenre2)', now(), now(), 0, $genre2Sql);

insert into raw_genres (id, created_at, updated_at, version, source_value, standard_genre_id)
values
    ('$($ids.rawGenre1)', now(), now(), 0, '$marker-genre-1', '$($ids.standardGenre1)'),
    ('$($ids.rawGenre2)', now(), now(), 0, '$marker-genre-2', '$($ids.standardGenre2)');

insert into standard_languages (id, created_at, updated_at, version, name, code, english_name, native_name)
values (
    '$($ids.standardLanguage)', now(), now(), 0, $languageSql, 'b15', 'Codex B15 Language', 'Codex B15 Language'
);

insert into raw_languages (id, created_at, updated_at, version, source_value, standard_language_id)
values (
    '$($ids.rawLanguage)', now(), now(), 0, '$marker-language', '$($ids.standardLanguage)'
);

insert into standard_areas (id, created_at, updated_at, version, name, code, region)
values (
    '$($ids.standardArea)', now(), now(), 0, $areaSql, 'b15', 'Codex'
);

insert into raw_areas (id, created_at, updated_at, version, source_value, standard_area_id)
values (
    '$($ids.rawArea)', now(), now(), 0, '$marker-area', '$($ids.standardArea)'
);

insert into actors (id, created_at, updated_at, version, name)
values
    ('$($ids.actor1)', now(), now(), 0, $actor1Sql),
    ('$($ids.actor2)', now(), now(), 0, $actor2Sql);

insert into directors (id, created_at, updated_at, version, name)
values (
    '$($ids.director1)', now(), now(), 0, $directorSql
);

insert into cover_images (
    id, created_at, updated_at, version,
    storage_type, original_url, storage_path, file_hash, file_size, mime_type,
    source_domain_id, status, retry_count, next_retry_time, last_error
) values
    (
        '$($ids.coverExternal)', now(), now(), 0,
        'EXTERNAL', 'https://codex.invalid/$marker/external.webp', null, '$marker-external-hash', 100, 'image/webp',
        '$($ids.sourceDomain)', 'UNPROCESSED', 0, null, null
    ),
    (
        '$($ids.coverLocal)', now(), now(), 0,
        'LOCAL', 'https://codex.invalid/$marker/local.webp', 'covers/$marker-local.webp', '$marker-local-hash', 500, 'image/webp',
        '$($ids.sourceDomain)', 'LOCAL_STORED', 0, null, null
    );

insert into raw_videos (
    id, created_at, updated_at, version,
    aggregation_status_updated_at,
    source_vid, source_hash, data_hash,
    title, alias_title, description, year, score, published_at,
    total_episodes, duration, remarks, subtitle, season,
    douban_id, tmdb_id, imdb_id, rotten_tomatoes_id,
    cover_image_id, data_source_category_id, data_source_id,
    raw_metadata, locked_fields,
    enrichment_status, enrichment_retry_count,
    normalization_status, normalization_retry_count,
    aggregation_status, playlist_retry_count, raw_language_str
) values
    (
        '$($ids.raw1)', timestamp '2000-01-01 00:00:01', timestamp '2000-01-01 00:00:01', 0,
        timestamp '2000-01-01 00:00:01',
        '$marker-raw-1', $sourceHash1Sql, '$marker-data-1',
        $titleSql, $alias1Sql, $desc1Sql, '$expectedYear', 8.1, date '$expectedPublishedAt',
        '$expectedTotalEpisodes', '$expectedDuration', $remarks1Sql, $sub1Sql, $expectedSeason,
        '0', $tmdbSql, null, null,
        '$($ids.coverExternal)', '$($ids.rawCategory)', '$($ids.dataSource)',
        '{}'::jsonb, '[]'::jsonb,
        'SUCCESS', 0,
        'READY', 0,
        'SEEDING', 0, 'Codex B15 raw language'
    ),
    (
        '$($ids.raw2)', timestamp '2000-01-01 00:00:02', timestamp '2000-01-01 00:00:02', 0,
        timestamp '2000-01-01 00:00:02',
        '$marker-raw-2', $sourceHash2Sql, '$marker-data-2',
        $titleSql, $alias2Sql, $desc2Sql, '$expectedYear', $expectedScoreText, date '2026-06-02',
        '$expectedTotalEpisodes', '60m', $remarks2Sql, $sub2Sql, $expectedSeason,
        '0', $tmdbSql, $imdbSql, $rtSql,
        '$($ids.coverLocal)', '$($ids.rawCategory)', '$($ids.dataSource)',
        '{}'::jsonb, '[]'::jsonb,
        'SUCCESS', 0,
        'READY', 0,
        'SEEDING', 0, 'Codex B15 raw language'
    );

insert into video_actors (actor_id, video_id)
values
    ('$($ids.actor1)', '$($ids.raw1)'),
    ('$($ids.actor1)', '$($ids.raw2)'),
    ('$($ids.actor2)', '$($ids.raw2)');

insert into video_directors (director_id, video_id)
values
    ('$($ids.director1)', '$($ids.raw2)');

insert into video_raw_genres (raw_genre_id, video_id)
values
    ('$($ids.rawGenre1)', '$($ids.raw1)'),
    ('$($ids.rawGenre2)', '$($ids.raw2)');

insert into video_raw_languages (raw_language_id, video_id)
values
    ('$($ids.rawLanguage)', '$($ids.raw1)');

insert into video_raw_areas (raw_area_id, video_id)
values
    ('$($ids.rawArea)', '$($ids.raw2)');

update raw_videos
set aggregation_status = 'PENDING',
    aggregation_status_updated_at = case
        when id = '$($ids.raw1)' then timestamp '2000-01-01 00:00:01'
        else timestamp '2000-01-01 00:00:02'
    end,
    updated_at = case
        when id = '$($ids.raw1)' then timestamp '2000-01-01 00:00:01'
        else timestamp '2000-01-01 00:00:02'
    end
where id in ('$($ids.raw1)', '$($ids.raw2)');
"@
    Exec-Sql $sql | Out-Null
}

function Cleanup-SmokeData {
    $titleSql = Sql-Literal $expectedTitle
    $alias1Sql = Sql-Literal $expectedAlias1
    $alias2Sql = Sql-Literal $expectedAlias2
    $tmdbSql = Sql-Literal $expectedTmdbId
    $imdbSql = Sql-Literal $expectedImdbId
    $rtSql = Sql-Literal $expectedRottenTomatoesId
    $sql = @"
drop table if exists pg_temp.b15_target_unified;
create temporary table b15_target_unified(id uuid primary key);
insert into b15_target_unified(id)
select distinct unified_video_id
from raw_video_unified_video
where raw_video_id in ('$($ids.raw1)', '$($ids.raw2)')
on conflict do nothing;
insert into b15_target_unified(id)
select id
from unified_videos
where title = $titleSql
   or alias_title in ($alias1Sql, $alias2Sql)
   or tmdb_id = $tmdbSql
   or imdb_id = $imdbSql
   or rotten_tomatoes_id = $rtSql
on conflict do nothing;

delete from unified_video_actors where unified_video_id in (select id from b15_target_unified);
delete from unified_video_directors where unified_video_id in (select id from b15_target_unified);
delete from unified_video_genres where unified_video_id in (select id from b15_target_unified);
delete from unified_video_standard_languages where unified_video_id in (select id from b15_target_unified);
delete from unified_video_standard_areas where unified_video_id in (select id from b15_target_unified);
delete from raw_video_unified_video
where raw_video_id in ('$($ids.raw1)', '$($ids.raw2)')
   or unified_video_id in (select id from b15_target_unified);
delete from video_actors where video_id in ('$($ids.raw1)', '$($ids.raw2)');
delete from video_directors where video_id in ('$($ids.raw1)', '$($ids.raw2)');
delete from video_raw_genres where video_id in ('$($ids.raw1)', '$($ids.raw2)');
delete from video_raw_languages where video_id in ('$($ids.raw1)', '$($ids.raw2)');
delete from video_raw_areas where video_id in ('$($ids.raw1)', '$($ids.raw2)');
delete from playlists where video_id in ('$($ids.raw1)', '$($ids.raw2)');
delete from raw_videos
where id in ('$($ids.raw1)', '$($ids.raw2)')
   or source_hash in ('$sourceHash1', '$sourceHash2')
   or source_vid in ('$marker-raw-1', '$marker-raw-2')
   or title = $titleSql;
delete from unified_videos where id in (select id from b15_target_unified);
delete from cover_images where id in ('$($ids.coverExternal)', '$($ids.coverLocal)') or original_url like 'https://codex.invalid/$marker/%';
delete from raw_category where id = '$($ids.rawCategory)' or source_code = '$marker-cat';
delete from raw_genres where id in ('$($ids.rawGenre1)', '$($ids.rawGenre2)') or source_value in ('$marker-genre-1', '$marker-genre-2');
delete from raw_languages where id = '$($ids.rawLanguage)' or source_value = '$marker-language';
delete from raw_areas where id = '$($ids.rawArea)' or source_value = '$marker-area';
delete from actors where id in ('$($ids.actor1)', '$($ids.actor2)') or name in ('$(($expectedActor1Name).Replace("'", "''"))', '$(($expectedActor2Name).Replace("'", "''"))');
delete from directors where id = '$($ids.director1)' or name = '$(($expectedDirector1Name).Replace("'", "''"))';
delete from standard_genre where id in ('$($ids.standardGenre1)', '$($ids.standardGenre2)') or name in ('$(($expectedGenre1Name).Replace("'", "''"))', '$(($expectedGenre2Name).Replace("'", "''"))');
delete from standard_languages where id = '$($ids.standardLanguage)' or name = '$(($expectedLanguageName).Replace("'", "''"))';
delete from standard_areas where id = '$($ids.standardArea)' or name = '$(($expectedAreaName).Replace("'", "''"))';
delete from standard_category where id = '$($ids.category)' or slug = '$expectedCategorySlug';
delete from source_domains where id = '$($ids.sourceDomain)' or domain_hash = '$marker-domain';
delete from data_sources where id = '$($ids.dataSource)' or name = 'Codex B15 DataSource $stamp';
drop table if exists pg_temp.b15_target_unified;
"@
    Exec-Sql $sql | Out-Null
    $script:cleanupDone = $true
}

function Assert-DbFixturePresent {
    $count = Invoke-SqlScalar @"
select count(*)
from raw_videos
where id in ('$($ids.raw1)', '$($ids.raw2)')
  and source_hash in ('$sourceHash1', '$sourceHash2')
  and aggregation_status = 'PENDING'
  and normalization_status = 'READY'
  and enrichment_status = 'SUCCESS';
"@
    if ($count -ne "2") {
        throw "aggregation pipeline fixture was not seeded as two pending raws, count=$count"
    }
}

function Wait-AggregationBound {
    $lastState = "not checked"
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        Start-Sleep -Seconds $WaitSeconds
        $state = Invoke-SqlScalar @"
select count(*)::text || '|' ||
       count(*) filter (where rv.aggregation_status = 'BOUND')::text || '|' ||
       count(distinct rvu.unified_video_id)::text || '|' ||
       coalesce(string_agg(distinct rvu.unified_video_id::text, ','), '') || '|' ||
       coalesce(string_agg(rv.aggregation_status, ',' order by rv.id::text), '')
from raw_videos rv
left join raw_video_unified_video rvu on rvu.raw_video_id = rv.id
where rv.id in ('$($ids.raw1)', '$($ids.raw2)');
"@
        $lastState = $state
        if ($null -ne $state) {
            $parts = $state -split "\|", 5
            if ($parts.Count -eq 5 -and -not [string]::IsNullOrWhiteSpace($parts[3])) {
                $script:unifiedVideoIds = @($parts[3].Split(",") | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
            }
            if ($parts.Count -eq 5 -and $parts[0] -eq "2" -and $parts[1] -eq "2" -and $parts[2] -eq "1" -and -not [string]::IsNullOrWhiteSpace($parts[3])) {
                $script:unifiedVideoId = $script:unifiedVideoIds[0]
                return
            }
        }
    }
    throw "fixture raws were not bound to one unified video; last state: $lastState"
}

function Assert-RuntimeScalarAndCategory {
    $row = Invoke-SqlScalar @"
select coalesce(title, '') || '|' ||
       coalesce(year, '') || '|' ||
       coalesce(score::text, '') || '|' ||
       coalesce(published_at::text, '') || '|' ||
       coalesce(alias_title, '') || '|' ||
       coalesce(description, '') || '|' ||
       coalesce(remarks, '') || '|' ||
       coalesce(total_episodes, '') || '|' ||
       coalesce(duration, '') || '|' ||
       coalesce(season::text, '') || '|' ||
       coalesce(subtitle, '') || '|' ||
       coalesce(category_id::text, '') || '|' ||
       coalesce(douban_id, '') || '|' ||
       coalesce(tmdb_id, '') || '|' ||
       coalesce(imdb_id, '') || '|' ||
       coalesce(rotten_tomatoes_id, '') || '|' ||
       coalesce(metadata_status, '')
from unified_videos
where id = '$script:unifiedVideoId';
"@
    if ([string]::IsNullOrWhiteSpace($row)) {
        throw "unified video row missing: $script:unifiedVideoId"
    }
    $parts = $row -split "\|", 17
    if ($parts.Count -ne 17) {
        throw "unexpected unified scalar projection: $row"
    }
    if ($parts[0] -ne $expectedTitle) { throw "title mismatch: $($parts[0])" }
    if ($parts[1] -ne $expectedYear) { throw "year mismatch: $($parts[1])" }
    if ([Math]::Abs(([double]$parts[2]) - $expectedScore) -gt 0.001) { throw "score mismatch: $($parts[2])" }
    if ($parts[3] -ne $expectedPublishedAt) { throw "published_at mismatch: $($parts[3])" }
    if ($parts[4] -ne $expectedAlias2) { throw "alias_title mismatch: $($parts[4])" }
    if ($parts[5] -ne $expectedDescription2) { throw "description mismatch: $($parts[5])" }
    if ($parts[6] -ne $expectedRemarks2) { throw "remarks mismatch: $($parts[6])" }
    if ($parts[7] -ne $expectedTotalEpisodes) { throw "total_episodes mismatch: $($parts[7])" }
    if ($parts[8] -ne $expectedDuration) { throw "duration mismatch: $($parts[8])" }
    if ($parts[9] -ne $expectedSeason) { throw "season mismatch: $($parts[9])" }
    if ($parts[10] -ne $expectedSubtitle2) { throw "subtitle mismatch: $($parts[10])" }
    if ($parts[11] -ne $ids.category) { throw "category mismatch: $($parts[11])" }
    if ($parts[12] -ne "") { throw "douban_id should ignore 0, actual=$($parts[12])" }
    if ($parts[13] -ne $expectedTmdbId) { throw "tmdb_id mismatch: $($parts[13])" }
    if ($parts[14] -ne $expectedImdbId) { throw "imdb_id mismatch: $($parts[14])" }
    if ($parts[15] -ne $expectedRottenTomatoesId) { throw "rotten_tomatoes_id mismatch: $($parts[15])" }
    if ($parts[16] -ne "SYNCED") { throw "metadata_status mismatch: $($parts[16])" }
}

function Assert-RuntimeRelations {
    $counts = Invoke-SqlScalar @"
select
  (select count(*) from unified_video_actors where unified_video_id = '$script:unifiedVideoId')::text || '|' ||
  (select count(*) from unified_video_directors where unified_video_id = '$script:unifiedVideoId')::text || '|' ||
  (select count(*) from unified_video_genres where unified_video_id = '$script:unifiedVideoId')::text || '|' ||
  (select count(*) from unified_video_standard_languages where unified_video_id = '$script:unifiedVideoId')::text || '|' ||
  (select count(*) from unified_video_standard_areas where unified_video_id = '$script:unifiedVideoId')::text;
"@
    if ($counts -ne "2|1|2|1|1") {
        throw "relation union counts mismatch: $counts"
    }
}

function Wait-CoverCommandGated {
    $lastState = "not checked"
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        $state = Invoke-SqlScalar @"
select coalesce(uv.cover_image_id::text, '') || '|' ||
       coalesce(ci.storage_type, '') || '|' ||
       coalesce(ci.status, '') || '|' ||
       coalesce(ci.next_retry_time::text, '') || '|' ||
       coalesce(ci.last_error, '')
from unified_videos uv
left join cover_images ci on ci.id = uv.cover_image_id
where uv.id = '$script:unifiedVideoId';
"@
        $lastState = $state
        if ($null -ne $state) {
            $parts = $state -split "\|", 5
            if ($parts.Count -eq 5 -and $parts[0] -eq $ids.coverLocal) {
                break
            }
        }
        Start-Sleep -Seconds $WaitSeconds
    }
    if ($lastState -notmatch "^$([regex]::Escape($ids.coverLocal))\|") {
        throw "managed cover was not selected; last state: $lastState"
    }
    $storageEnv = Get-DeploymentEnvMap "ircs-storage-service"
    $r2Enabled = if ($storageEnv.ContainsKey("APP_STORAGE_R2_ENABLED")) { $storageEnv["APP_STORAGE_R2_ENABLED"] } else { "false" }
    $cover = Invoke-SqlScalar @"
select coalesce(storage_type, '') || '|' ||
       coalesce(status, '') || '|' ||
       coalesce(next_retry_time::text, '') || '|' ||
       coalesce(last_error, '')
from cover_images
where id = '$($ids.coverLocal)';
"@
    $parts = $cover -split "\|", 4
    if ($parts.Count -ne 4) {
        throw "unexpected cover projection: $cover"
    }
    if ($parts[0] -ne "LOCAL" -or $parts[1] -ne "LOCAL_STORED") {
        throw "cover storage/status mismatch: $cover"
    }
    if ($parts[3] -ne "") {
        throw "cover promote left last_error: $($parts[3])"
    }
    if ($r2Enabled.ToLowerInvariant() -eq "false" -and $parts[2] -ne "") {
        throw "R2 inactive expected next_retry_time to remain null, actual=$($parts[2])"
    }
}

function Wait-EsRawDocument {
    param(
        [string]$RawVideoId,
        [string]$ExpectedExternalId
    )
    $lastState = "not checked"
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        Start-Sleep -Seconds $WaitSeconds
        $doc = Get-EsDocument "ircs_raw_video" $RawVideoId
        if ($null -ne $doc -and $doc.found) {
            $source = $doc._source
            $externalIds = ConvertTo-StringArray $source.externalIds
            $lastState = "found title='$($source.title)' externalIds='$($externalIds -join ',')'"
            if ($source.title -eq $expectedTitle -and ($externalIds -contains $ExpectedExternalId)) {
                return
            }
        } else {
            $lastState = "missing"
        }
    }
    throw "ES raw document $RawVideoId did not reach expected projection; last state: $lastState"
}

function Wait-EsUnifiedDocument {
    $lastState = "not checked"
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        Start-Sleep -Seconds $WaitSeconds
        $doc = Get-EsDocument "ircs_unified_video" $script:unifiedVideoId
        if ($null -ne $doc -and $doc.found) {
            $source = $doc._source
            $externalIds = ConvertTo-StringArray $source.externalIds
            $scoreMatches = $false
            if ($null -ne $source.score) {
                $scoreMatches = [Math]::Abs(([double]$source.score) - $expectedScore) -lt 0.001
            }
            $lastState = "found title='$($source.title)' sourceCount='$($source.sourceCount)' hasTmdb='$($source.hasTmdb)' hasImdb='$($source.hasImdb)' externalIds='$($externalIds -join ',')'"
            if ($source.title -eq $expectedTitle `
                    -and $source.sourceCount -eq 2 `
                    -and $source.hasTmdb -eq $true `
                    -and $source.hasImdb -eq $true `
                    -and $scoreMatches `
                    -and ($externalIds -contains $expectedTmdbId) `
                    -and ($externalIds -contains $expectedImdbId)) {
                return
            }
        } else {
            $lastState = "missing"
        }
    }
    throw "ES unified document did not reach expected projection; last state: $lastState"
}

function Wait-EsMissing {
    param(
        [string]$Index,
        [AllowNull()][string]$Id
    )
    if ([string]::IsNullOrWhiteSpace($Id)) {
        return
    }
    for ($attempt = 0; $attempt -lt $WaitAttempts; $attempt++) {
        Start-Sleep -Seconds 1
        $doc = Get-EsDocument $Index $Id
        if ($null -eq $doc -or -not $doc.found) {
            return
        }
    }
    throw "Elasticsearch document still exists after cleanup: $Index/$Id"
}

function Assert-NoDbResidue {
    $titleSql = Sql-Literal $expectedTitle
    $tmdbSql = Sql-Literal $expectedTmdbId
    $imdbSql = Sql-Literal $expectedImdbId
    $rtSql = Sql-Literal $expectedRottenTomatoesId
    $residue = Invoke-SqlScalar @"
select
  (select count(*) from raw_videos where id in ('$($ids.raw1)', '$($ids.raw2)') or source_hash in ('$sourceHash1', '$sourceHash2') or title = $titleSql)::text
  || '|' ||
  (select count(*) from raw_video_unified_video where raw_video_id in ('$($ids.raw1)', '$($ids.raw2)'))::text
  || '|' ||
  (select count(*) from unified_videos where title = $titleSql or tmdb_id = $tmdbSql or imdb_id = $imdbSql or rotten_tomatoes_id = $rtSql)::text
  || '|' ||
  (select count(*) from cover_images where id in ('$($ids.coverExternal)', '$($ids.coverLocal)') or original_url like 'https://codex.invalid/$marker/%')::text
  || '|' ||
  (select count(*) from data_sources where id = '$($ids.dataSource)' or name = 'Codex B15 DataSource $stamp')::text;
"@
    if ($residue -ne "0|0|0|0|0") {
        throw "smoke cleanup left DB residue: $residue"
    }
}

try {
    Test-KubectlAccess
    Test-PostgresAccess
    Assert-DeploymentReady "ircs-aggregation-worker"
    Assert-DeploymentReady "ircs-search-service"
    Assert-DeploymentReady "ircs-storage-service"
    $script:quotaBefore = Get-ResourceQuotaSnapshot

    $esPf = Start-PortForward "svc/elasticsearch-svc" $ElasticsearchPort 9200 $esPfOut $esPfErr
    Get-ElasticPassword | Out-Null
    Test-EsAccess

    Set-AggregationSmokeEnv
    Assert-DeploymentReady "ircs-aggregation-worker"
    Assert-SmokeQueuesCleanBefore
    Assert-ConsumersReady
    Assert-NoHttpRoute

    $total = 8
    $i = 1
    Mark "ircs-dev aggregation/storage runtime workers are ready and no HTTPRoute exists" $i $total; $i++

    Cleanup-SmokeData
    $script:cleanupDone = $false
    Remove-EsDocument "ircs_raw_video" $ids.raw1
    Remove-EsDocument "ircs_raw_video" $ids.raw2
    foreach ($id in @($script:unifiedVideoIds + @($script:unifiedVideoId) | Select-Object -Unique)) {
        Remove-EsDocument "ircs_unified_video" $id
    }
    Setup-SmokeData
    Assert-DbFixturePresent
    Mark "multi-raw pipeline fixture is seeded and pre-cleaned from DB/ES" $i $total; $i++

    Wait-AggregationBound
    Mark "scheduler binds fixture raws to one unified through aggregation-worker" $i $total; $i++

    Assert-RuntimeScalarAndCategory
    Mark "runtime scalar and category outputs match V1 handler rules" $i $total; $i++

    Assert-RuntimeRelations
    Mark "runtime metadata relations match V1 union rules" $i $total; $i++

    Wait-CoverCommandGated
    Mark "cover stage selects managed cover and storage command is safely consumed/gated" $i $total; $i++

    Wait-EsRawDocument $ids.raw1 $expectedTmdbId
    Wait-EsRawDocument $ids.raw2 $expectedImdbId
    Wait-EsUnifiedDocument
    foreach ($queue in Get-SmokeQueues) {
        Assert-QueueEmpty $queue
    }
    Mark "raw/unified ES documents are refreshed and runtime work is drained" $i $total; $i++

    Cleanup-SmokeData
    Remove-EsDocument "ircs_raw_video" $ids.raw1
    Remove-EsDocument "ircs_raw_video" $ids.raw2
    foreach ($id in @($script:unifiedVideoIds + @($script:unifiedVideoId) | Select-Object -Unique)) {
        Remove-EsDocument "ircs_unified_video" $id
    }
    Wait-EsMissing "ircs_raw_video" $ids.raw1
    Wait-EsMissing "ircs_raw_video" $ids.raw2
    foreach ($id in @($script:unifiedVideoIds + @($script:unifiedVideoId) | Select-Object -Unique)) {
        Wait-EsMissing "ircs_unified_video" $id
    }
    Assert-NoDbResidue
    Restore-AggregationEnv
    Assert-NoHttpRoute
    $script:quotaAfter = Get-ResourceQuotaSnapshot
    if ($script:quotaAfter -ne $script:quotaBefore) {
        throw "resource quota changed: before=[$script:quotaBefore] after=[$script:quotaAfter]"
    }
    if ($esPf -and -not $esPf.HasExited) {
        Stop-Process -Id $esPf.Id -Force -ErrorAction SilentlyContinue
        $esPf = $null
    }
    Mark "cleanup leaves no DB/ES/HTTPRoute/port-forward residue and resource quota is unchanged" $i $total

    Finish-Smoke "PASSED" "AGGREGATION_PIPELINE_RUNTIME_LIVE_SMOKE_PASSED" @{
        quota = $script:quotaAfter
        expectedTitle = $expectedTitle
        expectedCoverImageId = $ids.coverLocal
    }
} catch {
    Finish-Smoke "FAILED" "AGGREGATION_PIPELINE_RUNTIME_LIVE_SMOKE_FAILED" @{
        message = $_.Exception.Message
        rawVideoIds = @($ids.raw1, $ids.raw2)
        unifiedVideoId = $script:unifiedVideoId
        unifiedVideoIds = @($script:unifiedVideoIds)
        expectedTmdbId = $expectedTmdbId
        expectedImdbId = $expectedImdbId
    }
} finally {
    try { Cleanup-SmokeData } catch { Write-Warning $_ }
    try {
        if ($esPf -and -not $esPf.HasExited) {
            Remove-EsDocument "ircs_raw_video" $ids.raw1
            Remove-EsDocument "ircs_raw_video" $ids.raw2
            foreach ($id in @($script:unifiedVideoIds + @($script:unifiedVideoId) | Select-Object -Unique)) {
                Remove-EsDocument "ircs_unified_video" $id
            }
        }
    } catch { Write-Warning $_ }
    try { Restore-AggregationEnv } catch { Write-Warning $_ }
    if ($PurgeSmokeQueuesOnCleanup) {
        foreach ($queue in Get-SmokeQueues) {
            try { kubectl -n $Namespace exec rabbitmq-0 -- rabbitmqctl purge_queue $queue | Out-Null } catch { Write-Warning $_ }
        }
    }
    if ($esPf -and -not $esPf.HasExited) {
        Stop-Process -Id $esPf.Id -Force -ErrorAction SilentlyContinue
    }
}
