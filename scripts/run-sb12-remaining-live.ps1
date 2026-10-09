[CmdletBinding()]
param(
    [string]$AcceptancePath,
    [string]$LedgerPath,
    [string]$JavaRunPath,
    [int]$PostgresPort = 15434,
    [int]$JavaPort = 18082,
    [int]$GatewayPort = 13003,
    [switch]$SkipBuild,
    [switch]$ResumePreparedBatch,
    [switch]$ResumeCheckOnly,
    [ValidateSet('RemainingSeven', 'FinalFive')]
    [string]$Plan = 'RemainingSeven',
    [switch]$ValidateOnly,
    [string]$EvidenceRootOverride,
    [string]$ValidationDirectory,
    # Test-only injection points. Actual FinalFive execution always uses the
    # repository's fixed batch-state directory and complete evidence history.
    [string]$BatchStateDirectoryOverride,
    [string]$HistoryEvidenceRootOverride,
    [switch]$OfflineStateTransitionTest
)

# This script deliberately refuses to start a fresh batch at zero. It imports
# only presence-safe values from a finalized ledger, then performs the selected
# remaining plan serially in a new, isolated Java/DB process.
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7.2+ is required for the UTF-8/Base64 evidence exporter. Run this script with pwsh.exe, not Windows PowerShell.' }
if ($Plan -eq 'RemainingSeven') { throw 'SB-12 RemainingSeven is retired after 01-B and 01-8-A were submitted. It must not import the old 01-A budget or replay historical runs; use only the reviewed FinalFive entry.' }
. (Join-Path $PSScriptRoot 'lib\sb12-postgres-json.ps1')
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$gatewayDir = Join-Path $root 'examples\pi-agent-gateway'
$runDate = Get-Date -Format 'yyyyMMdd'
$evidenceRoot = if ([string]::IsNullOrWhiteSpace($EvidenceRootOverride)) { Join-Path $root "docs\storyboard-mvp\quality-runs\$runDate" } else { $EvidenceRootOverride }
$fixedBatchId = 'SB12-FIXED-EIGHT-20261007'
$fixedBatchDirectory = if ([string]::IsNullOrWhiteSpace($BatchStateDirectoryOverride)) { Join-Path $root 'docs\storyboard-mvp\quality-runs\SB12_FIXED_EIGHT_BATCH' } else { $BatchStateDirectoryOverride }
$historyEvidenceRoot = if ([string]::IsNullOrWhiteSpace($HistoryEvidenceRootOverride)) { Join-Path $root 'docs\storyboard-mvp\quality-runs' } else { $HistoryEvidenceRootOverride }
$batchIdentityPath = Join-Path $fixedBatchDirectory 'batch-identity.json'
$batchStatePath = Join-Path $fixedBatchDirectory 'batch-execution-state.json'
$batchLockPath = Join-Path $fixedBatchDirectory 'batch-execution.lock'

$definitions = if ($Plan -eq 'FinalFive') {
    @(
        [pscustomobject]@{ Label='01-8-B'; Sample='01-unopened-letter-dialogue.md'; SampleName='样本 01'; Shots=8; Instructions='压低色温，强调林舟犹豫、信封未拆封、动作顺序和克制的夜雨氛围。' },
        [pscustomobject]@{ Label='02-A'; Sample='02-blackout-stairwell-action.md'; SampleName='样本 02'; Shots=6; Instructions='保持停电、手电、蓝色文件夹、钥匙与门锁状态连续，不增加原文外动作。' },
        [pscustomobject]@{ Label='02-B'; Sample='02-blackout-stairwell-action.md'; SampleName='样本 02'; Shots=6; Instructions='保持停电、手电、蓝色文件夹、钥匙与门锁状态连续，不增加原文外动作。' },
        [pscustomobject]@{ Label='03-A'; Sample='03-dawn-platform-atmosphere.md'; SampleName='样本 03'; Shots=4; Instructions='全场无对白；突出雾、蓝光、湿车票、清洁工与无列车的清晨氛围。' },
        [pscustomobject]@{ Label='03-B'; Sample='03-dawn-platform-atmosphere.md'; SampleName='样本 03'; Shots=4; Instructions='全场无对白；突出雾、蓝光、湿车票、清洁工与无列车的清晨氛围。' }
    )
} else {
    @(
        [pscustomobject]@{ Label='01-B'; Sample='01-unopened-letter-dialogue.md'; SampleName='样本 01'; Shots=6; Instructions='压低色温，强调林舟犹豫、信封未拆封、动作顺序和克制的夜雨氛围。' },
        [pscustomobject]@{ Label='01-8-A'; Sample='01-unopened-letter-dialogue.md'; SampleName='样本 01'; Shots=8; Instructions='压低色温，强调林舟犹豫、信封未拆封、动作顺序和克制的夜雨氛围。' },
        [pscustomobject]@{ Label='01-8-B'; Sample='01-unopened-letter-dialogue.md'; SampleName='样本 01'; Shots=8; Instructions='压低色温，强调林舟犹豫、信封未拆封、动作顺序和克制的夜雨氛围。' },
        [pscustomobject]@{ Label='02-A'; Sample='02-blackout-stairwell-action.md'; SampleName='样本 02'; Shots=6; Instructions='保持停电、手电、蓝色文件夹、钥匙与门锁状态连续，不增加原文外动作。' },
        [pscustomobject]@{ Label='02-B'; Sample='02-blackout-stairwell-action.md'; SampleName='样本 02'; Shots=6; Instructions='保持停电、手电、蓝色文件夹、钥匙与门锁状态连续，不增加原文外动作。' },
        [pscustomobject]@{ Label='03-A'; Sample='03-dawn-platform-atmosphere.md'; SampleName='样本 03'; Shots=4; Instructions='全场无对白；突出雾、蓝光、湿车票、清洁工与无列车的清晨氛围。' },
        [pscustomobject]@{ Label='03-B'; Sample='03-dawn-platform-atmosphere.md'; SampleName='样本 03'; Shots=4; Instructions='全场无对白；突出雾、蓝光、湿车票、清洁工与无列车的清晨氛围。' }
    )
}
$sourceLabel = if ($Plan -eq 'FinalFive') { '01-8-A' } else { '01-A' }
$batchName = if ($Plan -eq 'FinalFive') { 'final-five-batch' } else { 'remaining-batch' }
$databaseContainer = if ($Plan -eq 'FinalFive') { 'screenplay-sb12-final-five-postgres' } else { 'screenplay-sb12-remaining-postgres' }
$databaseName = if ($Plan -eq 'FinalFive') { 'screenplay_sb12_final_five' } else { 'screenplay_sb12_remaining' }
$approvalPattern = if ($Plan -eq 'FinalFive') { '(?im)^\s*后五项继续执行授权\s*[:：]\s*APPROVED\s*$' } else { '(?im)^\s*继续执行授权\s*[:：]\s*APPROVED\s*$' }
if ($Plan -eq 'FinalFive' -and $PostgresPort -eq 15434 -and $JavaPort -eq 18082 -and $GatewayPort -eq 13003) {
    # Do not collide with the retained seven-run environment when callers use defaults.
    $PostgresPort = 15435; $JavaPort = 18083; $GatewayPort = 13004
}
if ([string]::IsNullOrWhiteSpace($AcceptancePath)) {
    $AcceptancePath = if ($Plan -eq 'FinalFive') { Join-Path $root 'docs\storyboard-mvp\SB12_FINAL_FIVE_REVIEW.md' } else { Join-Path $root 'docs\storyboard-mvp\SB12_01A_ACCEPTANCE.md' }
}
if (-not $LedgerPath) { $LedgerPath = Join-Path $root "docs\storyboard-mvp\quality-runs\20261007\$sourceLabel\$sourceLabel.provider-ledger.json" }
if (-not $JavaRunPath) { $JavaRunPath = Join-Path $root "docs\storyboard-mvp\quality-runs\20261007\$sourceLabel\$sourceLabel.java-run.json" }
$batchEvidence = Join-Path $evidenceRoot $batchName
$logs = Join-Path $batchEvidence 'logs'

function Require-Command([string]$Name) { $command = Get-Command $Name -ErrorAction SilentlyContinue; if (-not $command) { throw "Required command '$Name' was not found." }; $command.Source }
function Write-JsonFile([string]$Path, $Value) { $Value | ConvertTo-Json -Depth 32 | Set-Content -LiteralPath $Path -Encoding utf8NoBOM }
function Write-PersistentJson([string]$Path, $Value) {
    $temporaryPath = "$Path.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        Write-JsonFile $temporaryPath $Value
        Move-Item -LiteralPath $temporaryPath -Destination $Path -Force
    } finally {
        if (Test-Path -LiteralPath $temporaryPath) { Remove-Item -LiteralPath $temporaryPath -Force }
    }
}
function Get-FixedBatchState([switch]$AllowRecordedSubmissions) {
    if (-not (Test-Path -LiteralPath $batchIdentityPath -PathType Leaf) -or -not (Test-Path -LiteralPath $batchStatePath -PathType Leaf)) {
        throw "Fixed SB-12 batch identity/state is missing: $fixedBatchDirectory"
    }
    $identity = Get-Content -LiteralPath $batchIdentityPath -Raw | ConvertFrom-Json
    $state = Get-Content -LiteralPath $batchStatePath -Raw | ConvertFrom-Json
    $expectedLabels = @($definitions | ForEach-Object { $_.Label })
    if ($identity.batchId -ne $fixedBatchId -or $state.batchId -ne $fixedBatchId -or (@($identity.finalFiveLabels) -join ',') -ne ($expectedLabels -join ',')) {
        throw 'Fixed SB-12 batch identity does not match the authorized eight-run plan.'
    }
    foreach ($label in $expectedLabels) {
        $entry = $state.finalFive.$label
        if ($null -eq $entry -or [string]::IsNullOrWhiteSpace($entry.submissionStatus)) { throw "Fixed batch state is incomplete for $label." }
        if (-not $AllowRecordedSubmissions -and $entry.submissionStatus -ne 'not-submitted') {
            throw "Fixed batch state records $label as '$($entry.submissionStatus)'. Full FinalFive replay is prohibited; reconcile preserved evidence instead."
        }
    }
    [pscustomobject]@{ Identity=$identity; State=$state }
}
function Assert-NoHistoricalFinalFiveSubmission {
    if (-not (Test-Path -LiteralPath $historyEvidenceRoot -PathType Container)) { throw "SB-12 evidence history is unavailable: $historyEvidenceRoot" }
    foreach ($label in @($definitions | ForEach-Object { $_.Label })) {
        $directories = @(Get-ChildItem -LiteralPath $historyEvidenceRoot -Directory -Recurse -Force -ErrorAction Stop | Where-Object { $_.Name -eq $label })
        $markers = @(Get-ChildItem -LiteralPath $historyEvidenceRoot -File -Recurse -Force -ErrorAction Stop | Where-Object { $_.Name -in @("$label.submission-marker.json", "$label.accepted.json", "$label.java-run.json", "$label.provider-ledger.json") })
        if ($directories.Count -gt 0 -or $markers.Count -gt 0) {
            throw "Historical SB-12 evidence exists for $label. Submitted, terminal, or unknown results must be reconciled; full FinalFive replay is prohibited."
        }
    }
}
function Set-FixedBatchRunState([string]$Label, [string]$SubmissionStatus, [string]$ExecutionStatus, [hashtable]$Extra = @{}) {
    $persistent = Get-FixedBatchState -AllowRecordedSubmissions
    $entry = $persistent.State.finalFive.$Label
    if ($null -eq $entry) { throw "Cannot persist an unknown fixed-batch label: $Label" }
    $entry | Add-Member -NotePropertyName submissionStatus -NotePropertyValue $SubmissionStatus -Force
    $entry | Add-Member -NotePropertyName executionStatus -NotePropertyValue $ExecutionStatus -Force
    $entry | Add-Member -NotePropertyName updatedAt -NotePropertyValue ((Get-Date).ToUniversalTime().ToString('o')) -Force
    foreach ($key in $Extra.Keys) { $entry | Add-Member -NotePropertyName $key -NotePropertyValue $Extra[$key] -Force }
    $persistent.State | Add-Member -NotePropertyName updatedAt -NotePropertyValue ((Get-Date).ToUniversalTime().ToString('o')) -Force
    Write-PersistentJson $batchStatePath $persistent.State
}
function Set-BatchStage([string]$Stage, [string]$Label = '') {
    $script:currentStage = $Stage
    $script:currentLabel = $Label
    Write-Host "SB-12 ${Plan}: $Stage $Label"
    Write-JsonFile (Join-Path $batchEvidence 'batch-stage.json') ([ordered]@{ plan=$Plan; stage=$Stage; label=$Label; resume=[bool]$ResumePreparedBatch; updatedAt=(Get-Date).ToUniversalTime().ToString('o') })
}
function Assert-PreparedBatch {
    if ($Plan -eq 'FinalFive') { throw 'FinalFive does not support prepared-batch resume. Reconcile any terminal evidence before a new reviewed continuation.' }
    if ($PostgresPort -ne 15434 -or $JavaPort -ne 18082 -or $GatewayPort -ne 13003) { throw 'Prepared-batch resume requires the dedicated existing ports 15434 / 18082 / 13003.' }
    foreach ($name in @('carryover.json', "$sourceLabel.provider-ledger-copy.json", "$sourceLabel.java-run-copy.json", 'preflight-live.json', 'gateway-typecheck.txt', 'provider-contract.txt', 'budget-contract.txt')) {
        if (-not (Test-Path -LiteralPath (Join-Path $batchEvidence $name) -PathType Leaf)) { throw "Prepared batch evidence is incomplete: $name." }
    }
    foreach ($label in @($definitions | ForEach-Object { $_.Label })) {
        if (Test-Path -LiteralPath (Join-Path $evidenceRoot $label)) { throw "Run evidence already exists for $label; prepared-batch resume is refused." }
    }
    foreach ($name in @('batch-progress.json', 'HUMAN_EVALUATION_PACKET_REMAINING.md')) {
        if (Test-Path -LiteralPath (Join-Path $batchEvidence $name)) { throw "Batch execution marker already exists: $name; reconcile before resuming." }
    }
    $stagePath = Join-Path $batchEvidence 'batch-stage.json'
    if (Test-Path -LiteralPath $stagePath) {
        $previousStage = Get-Content -LiteralPath $stagePath -Raw | ConvertFrom-Json
        if ($previousStage.stage -notin @('fresh-startup', 'resume-validated', 'human-evaluation-packet') -or -not [string]::IsNullOrWhiteSpace($previousStage.label)) {
            throw 'Batch may have reached a business operation; prepared-batch resume is refused.'
        }
    }
    $carryover = Get-Content -LiteralPath (Join-Path $batchEvidence 'carryover.json') -Raw | ConvertFrom-Json
    $ledgerHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $LedgerPath).Hash
    $javaHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $JavaRunPath).Hash
    if ($carryover.acceptanceSha256 -ne (Get-FileHash -Algorithm SHA256 -LiteralPath $AcceptancePath).Hash -or
        $carryover.ledgerSha256 -ne $ledgerHash -or
        (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $batchEvidence "$sourceLabel.provider-ledger-copy.json")).Hash -ne $ledgerHash -or
        (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $batchEvidence "$sourceLabel.java-run-copy.json")).Hash -ne $javaHash) { throw 'Prepared-batch acceptance or source evidence hashes differ.' }
    if ($carryover.sourceRunId -ne $priorRun.id -or $carryover.sourceTraceId -ne $priorRun.traceId -or
        $carryover.initialBusinessRuns -ne $initialRuns -or [decimal]$carryover.initialChargedUsd -ne $initialCharged -or
        $carryover.initialProviderRequests -ne $initialProviderRequests) { throw 'Prepared-batch carryover values differ from finalized source evidence.' }
    $preflight = Get-Content -LiteralPath (Join-Path $batchEvidence 'preflight-live.json') -Raw | ConvertFrom-Json
    if (-not $preflight.readyForSb12LiveRun -or $preflight.mode -ne 'live' -or $preflight.provider -ne 'deepseek' -or $preflight.credentialPresence.DEEPSEEK_API_KEY -ne 'present') { throw 'Prepared-batch preflight evidence is not ready.' }
    $counts = Invoke-Sb12PostgresJson -DockerPath $docker -Container $databaseContainer -Database $databaseName -SelectSql "select json_build_object('runs', (select count(*) from agent_runs), 'projects', (select count(*) from screenplay_projects), 'contexts', (select count(*) from storyboard_run_contexts))"
    foreach ($field in @('runs', 'projects', 'contexts')) {
        if ($null -eq $counts.$field -or [long]$counts.$field -ne 0) { throw 'Prepared-batch database is not empty; no continuation was submitted.' }
    }
    try {
        $gatewayHealth = Invoke-RestMethod -Uri "http://127.0.0.1:$GatewayPort/health" -TimeoutSec 5
        $javaHealth = Invoke-RestMethod -Uri "http://127.0.0.1:$JavaPort/actuator/health" -TimeoutSec 5
    } catch { throw 'Prepared-batch Java or Gateway health check failed.' }
    if ($gatewayHealth.ok -ne $true -or $javaHealth.status -ne 'UP') { throw 'Prepared-batch services are not healthy.' }
    if ([string]::IsNullOrWhiteSpace($env:AGENT_GATEWAY_TOKEN)) { throw 'Resume requires the existing Gateway token in AGENT_GATEWAY_TOKEN; no secret is regenerated.' }
    try {
        $probe = Invoke-WebRequest -Uri "http://127.0.0.1:$GatewayPort/agent/runs/$([guid]::NewGuid().ToString('D'))/ledger" -Method Get -Headers @{ Authorization="Bearer $env:AGENT_GATEWAY_TOKEN"; 'X-Agent-User'='1' } -TimeoutSec 5 -SkipHttpErrorCheck
    } catch { throw 'Prepared-batch Gateway authentication probe could not complete.' }
    if ([int]$probe.StatusCode -ne 404) { throw 'Existing Gateway token could not be verified by the read-only ledger probe.' }
}
function Assert-UnsubmittedFinalFivePreparation {
    if ($Plan -ne 'FinalFive') { throw 'This preparation-resume guard is only valid for FinalFive.' }
    $stagePath = Join-Path $batchEvidence 'batch-stage.json'
    if (-not (Test-Path -LiteralPath $stagePath -PathType Leaf)) { throw 'FinalFive preparation state is missing batch-stage.json.' }
    $stage = Get-Content -LiteralPath $stagePath -Raw | ConvertFrom-Json
    if ($stage.plan -ne 'FinalFive' -or $stage.stage -notin @('fresh-startup', 'resume-preflight') -or -not [string]::IsNullOrWhiteSpace($stage.label)) {
        throw 'FinalFive preparation may have reached a business operation; resume is refused.'
    }
    $forbiddenNames = @('submission-marker.json', 'accepted.json', 'java-run.json', 'provider-ledger.json')
    $forbiddenEvidence = @(Get-ChildItem -LiteralPath $batchEvidence -File -Recurse -Force | Where-Object { $forbiddenNames -contains $_.Name })
    if ($forbiddenEvidence.Count -gt 0) { throw 'FinalFive preparation contains submission or terminal evidence; resume is refused.' }
    foreach ($definition in $definitions) {
        if (Test-Path -LiteralPath (Join-Path $evidenceRoot $definition.Label)) { throw "FinalFive run evidence exists for $($definition.Label); preparation resume is refused." }
    }
}
function Wait-Http([string]$Url, [string]$Name) {
    for ($attempt = 0; $attempt -lt 90; $attempt++) { try { if ((Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 2).StatusCode -lt 500) { return } } catch { Start-Sleep -Milliseconds 500 } }
    throw "$Name did not become ready. Inspect $logs."
}
function Invoke-Json([string]$Method, [string]$Url, $Body, [hashtable]$Headers = @{}) {
    $request = @{ Method = $Method; Uri = $Url; Headers = $Headers; ContentType = 'application/json'; TimeoutSec = 30 }
    if ($null -ne $Body) { $request.Body = $Body | ConvertTo-Json -Depth 20 -Compress }
    try { Invoke-RestMethod @request } catch { $detail = if ($_.ErrorDetails.Message) { $_.ErrorDetails.Message } else { $_.Exception.Message }; throw "HTTP $Method $Url failed: $detail" }
}
function Start-Background([string]$FilePath, [string[]]$Arguments, [string]$WorkingDirectory, [string]$Name) {
    Start-Process -FilePath $FilePath -ArgumentList $Arguments -WorkingDirectory $WorkingDirectory -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logs "$Name.log") -RedirectStandardError (Join-Path $logs "$Name.err.log")
}
function Get-FencedScene([string]$SamplePath) {
    $document = Get-Content -LiteralPath $SamplePath -Raw
    $match = [regex]::Match($document, '(?s)```text\r?\n(.*?)\r?\n```')
    if (-not $match.Success) { throw "No fenced text scene in $SamplePath" }
    $match.Groups[1].Value -replace "\r\n?", "`n"
}
function Add-HumanEvaluation([string]$Path, $Definition, $Storyboard) {
    $lines = @("`n## Run $($Definition.Label) — $($Definition.SampleName) / $($Definition.Shots) 镜", '', '原场景：', '```text', $Storyboard.sourceSnapshot.sceneText, '```', '')
    foreach ($shot in @($Storyboard.shots)) {
        $lines += @("### 镜 $($shot.orderIndex + 1)", "- sourceQuote：$($shot.sourceQuote)", "- 画面：$($shot.visualDescription)", "- 图像 Prompt：$($shot.imagePrompt)", "- 视频 Prompt：$($shot.videoPrompt)", '- 人工评价：□ 直接用  □ 小改可用  □ 需要重做', '- 备注：', '')
    }
    Add-Content -LiteralPath $Path -Value ($lines -join "`n") -Encoding utf8NoBOM
}
function Initialize-HumanEvaluationPacket([string]$Path) {
    $lines = @('# SB-12 后续批次人工逐镜评价', '', '这是人工评价材料，不包含模型自评或人工结论。每个计划镜头先留空；Java 保存分镜后，启动器会追加原场景与完整逐镜结果。', '')
    foreach ($definition in $definitions) {
        $lines += @("## $($definition.Label) — $($definition.SampleName) / $($definition.Shots) 镜", '- 状态：待 Java 保存产物。')
        for ($index = 1; $index -le $definition.Shots; $index++) {
            $lines += @("### 镜 $index — 待填入已保存结果", '- sourceQuote：', '- 图像 Prompt：', '- 视频 Prompt：', '- 人工评价：□ 直接用  □ 小改可用  □ 需要重做', '- 备注：', '')
        }
    }
    Set-Content -LiteralPath $Path -Encoding utf8NoBOM -Value ($lines -join "`n")
}
function Get-AutomatedAcceptance($Definition, $Storyboard) {
    if (-not $Storyboard) { return [ordered]@{ completed = $false; reason = 'Java storyboard artifact unavailable' } }
    $combined = @($Storyboard.shots | ForEach-Object { "$($_.visualDescription) $($_.dialogue) $($_.sound) $($_.imagePrompt) $($_.videoPrompt)" }) -join "`n"
    $patterns = switch ($Definition.SampleName) {
        '样本 01' { @('信.{0,10}(打开|拆开|读)', '(和解|离开)') }
        '样本 02' { @('门.{0,10}(打开|解锁)', '(?<!没有)陈默.{0,16}碰.{0,6}门') }
        '样本 03' { @('(?<!没有)列车.{0,12}(进站|到站)') }
    }
    $matches = @($patterns | Where-Object { [regex]::IsMatch($combined, $_) })
    [ordered]@{
        sourceQuotesLocated = @($Storyboard.shots | Where-Object { -not $Storyboard.sourceSnapshot.sceneText.Contains($_.sourceQuote) }).Count -eq 0
        forbiddenPatternMatches = $matches
        dialogueEmptyForAtmosphereSample = if ($Definition.SampleName -eq '样本 03') { @($Storyboard.shots | Where-Object { -not [string]::IsNullOrWhiteSpace($_.dialogue) }).Count -eq 0 } else { $null }
        semanticContinuity = '无法由关键词可靠判定；必须由真人检查动作、道具、关系与 Prompt 的逐镜连续性。'
    }
}

if ($ResumeCheckOnly -and -not $ResumePreparedBatch) { throw '-ResumeCheckOnly requires -ResumePreparedBatch.' }
if ($ValidateOnly -and $ResumePreparedBatch) { throw '-ValidateOnly cannot be combined with -ResumePreparedBatch.' }
if ($OfflineStateTransitionTest -and (-not $ValidateOnly -or [string]::IsNullOrWhiteSpace($BatchStateDirectoryOverride) -or [string]::IsNullOrWhiteSpace($HistoryEvidenceRootOverride))) {
    throw '-OfflineStateTransitionTest requires -ValidateOnly plus disposable BatchStateDirectoryOverride and HistoryEvidenceRootOverride paths.'
}
if ($Plan -eq 'FinalFive' -and -not $ValidateOnly -and -not $ResumeCheckOnly -and (-not [string]::IsNullOrWhiteSpace($BatchStateDirectoryOverride) -or -not [string]::IsNullOrWhiteSpace($HistoryEvidenceRootOverride))) {
    throw 'FinalFive persistent state and evidence-history locations cannot be overridden for an actual execution.'
}
if (-not $ValidateOnly -and -not $ResumePreparedBatch -and [string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) { throw 'DEEPSEEK_API_KEY is absent in this PowerShell process. Use the existing secret-configured terminal; do not paste a key into this script.' }
if (-not (Test-Path $AcceptancePath)) { throw "$sourceLabel continuation review record is required before continuation: $AcceptancePath" }
$acceptanceText = Get-Content -LiteralPath $AcceptancePath -Raw
if ($acceptanceText -notmatch $approvalPattern) {
    $requiredApproval = if ($Plan -eq 'FinalFive') { '后五项继续执行授权：APPROVED' } else { '继续执行授权：APPROVED' }
    throw "$sourceLabel has no explicit D continuation authorization (required line: $requiredApproval). No live run was submitted."
}
if (-not $LedgerPath -or -not (Test-Path $LedgerPath) -or -not $JavaRunPath -or -not (Test-Path $JavaRunPath)) { throw "$sourceLabel ledger and Java run JSON are both required; no live run was submitted." }
if ($ResumePreparedBatch) {
    if (-not (Test-Path -LiteralPath $batchEvidence -PathType Container)) { throw 'No prepared batch exists to resume.' }
} elseif (Test-Path $batchEvidence) { throw "Batch evidence already exists: $batchEvidence. Use -ResumePreparedBatch only for a validated, unsubmitted prepared batch." }

$priorLedger = Get-Content -LiteralPath $LedgerPath -Raw | ConvertFrom-Json
$priorRun = Get-Content -LiteralPath $JavaRunPath -Raw | ConvertFrom-Json
if ($null -eq $priorLedger.budget -or $null -eq $priorLedger.providerRequests -or [string]::IsNullOrWhiteSpace($priorRun.id)) { throw "$sourceLabel evidence lacks required ledger/run fields." }
$sourceMustBeFailed = $Plan -eq 'FinalFive'
$expectedSourceStatus = if ($sourceMustBeFailed) { 'failed' } else { 'completed' }
if ($priorRun.status -ne $expectedSourceStatus -or $priorLedger.status -ne $expectedSourceStatus -or $null -eq $priorLedger.budget.businessRuns -or $null -eq $priorLedger.budget.chargedUsd -or [decimal]$priorLedger.budget.reservedUsd -ne 0 -or @($priorLedger.providerRequests | Where-Object { $_.status -ne 'completed' }).Count -gt 0) { throw "$sourceLabel run and provider ledger must be finalized with expected status '$expectedSourceStatus'." }
$initialRuns = [int]$priorLedger.budget.businessRuns
$initialCharged = [decimal]$priorLedger.budget.chargedUsd
$initialProviderRequests = @($priorLedger.providerRequests).Count
if ($initialRuns -lt 1 -or $initialRuns -gt 8 -or $initialCharged -lt 0 -or $initialCharged -gt 1) { throw "$sourceLabel carryover totals are invalid; stop for reconciliation." }
if ($initialRuns + @($definitions).Count -gt 8) { throw "$sourceLabel ledger already records $initialRuns business runs; this plan's $(@($definitions).Count) items would exceed the cap." }
if ($priorLedger.budget.businessRunCap -ne 8 -or [decimal]$priorLedger.budget.capUsd -ne 1) { throw "$sourceLabel ledger caps do not match the authorized eight-run / one-dollar batch." }
if ($Plan -eq 'FinalFive' -and ($initialRuns -ne 3 -or [decimal]::Abs($initialCharged - [decimal]'0.00748236') -gt [decimal]'0.000000001' -or $initialProviderRequests -ne 3 -or [decimal]::Abs(([decimal]$priorLedger.budget.remainingUsd) - [decimal]'0.99251764') -gt [decimal]'0.000000001')) {
    throw '01-8-A ledger does not match the approved final-five carryover (3 runs / 9 requests / USD 0.007482360 / USD 0.992517640 remaining).'
}
if ($Plan -eq 'FinalFive') {
    # A terminal run ledger contains that run's three requests; the preserved
    # batch summary supplies the cross-process cumulative nine-request total.
    $summaryPath = Join-Path $root 'docs\storyboard-mvp\quality-runs\20261007\remaining-batch\batch-summary.json'
    if (-not (Test-Path -LiteralPath $summaryPath -PathType Leaf)) { throw 'The preserved cumulative batch summary is required to account for the prior nine provider requests.' }
    $summary = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
    if ($summary.businessRuns -ne 3 -or $summary.providerRequests -ne 9 -or $summary.retries -ne 0 -or [decimal]::Abs(([decimal]$summary.chargedUsd) - $initialCharged) -gt [decimal]'0.000000001' -or [decimal]$summary.reservedUsd -ne 0) { throw 'The preserved cumulative batch summary does not reconcile to the 01-8-A terminal ledger.' }
    $initialProviderRequests = 9
}
$batchLock = $null
$currentStage = 'guard'
$currentLabel = ''
try {
if ($Plan -eq 'FinalFive') {
    try { $batchLock = [IO.File]::Open($batchLockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
    catch { throw "Another executor holds the fixed SB-12 batch lock: $batchLockPath" }
    $fixedBatch = Get-FixedBatchState
    Assert-NoHistoricalFinalFiveSubmission
}
foreach ($definition in $definitions) {
    if (Test-Path -LiteralPath (Join-Path $evidenceRoot $definition.Label)) { throw "Planned run evidence already exists: $($definition.Label). Reconcile it; this launcher will not overwrite or re-run a submitted sample." }
}
if ($ValidateOnly) {
    $receiptSchema = Get-Content -LiteralPath (Join-Path $gatewayDir 'src\storyboard-schema.ts') -Raw
    $runtimeSource = Get-Content -LiteralPath (Join-Path $gatewayDir 'src\runtime.ts') -Raw
    if ($receiptSchema -notmatch 'parseSavedStoryboardAcknowledgement' -or $receiptSchema -notmatch 'Markdown JSON fence' -or $runtimeSource -notmatch 'hasVerifiedSavedStoryboardResult' -or $runtimeSource -notmatch 'validateSavedStoryboardAcknowledgement') { throw 'The validated-save completion repair is not present in the Gateway source.' }
    if ([string]::IsNullOrWhiteSpace($ValidationDirectory)) { $ValidationDirectory = Join-Path ([IO.Path]::GetTempPath()) ("sb12-final-five-validation-" + [guid]::NewGuid().ToString('N')) }
    $null = New-Item -ItemType Directory -Path $ValidationDirectory -Force
    $templatePath = Join-Path $ValidationDirectory 'HUMAN_EVALUATION_PACKET_FINAL_FIVE_PREVIEW.md'
    Initialize-HumanEvaluationPacket $templatePath
    $stateTransition = $null
    if ($OfflineStateTransitionTest) {
        Set-FixedBatchRunState '01-8-B' 'unknown' 'submitting'
        Set-FixedBatchRunState '01-8-B' 'submitted' 'accepted'
        Set-FixedBatchRunState '01-8-B' 'terminal' 'completed'
        $transitioned = Get-FixedBatchState -AllowRecordedSubmissions
        $entry = $transitioned.State.finalFive.'01-8-B'
        if ($entry.submissionStatus -ne 'terminal' -or $entry.executionStatus -ne 'completed' -or [string]::IsNullOrWhiteSpace($entry.updatedAt)) { throw 'Production fixed-batch state transition did not persist unknown → submitted → terminal.' }
        $stateTransition = [ordered]@{ label='01-8-B'; finalSubmissionStatus=$entry.submissionStatus; finalExecutionStatus=$entry.executionStatus; updatedAt=$entry.updatedAt }
    }
    [ordered]@{ validationOnly=$true; plan=$Plan; fixedBatchId=$fixedBatchId; fixedBatchStatePath=$batchStatePath; sourceLabel=$sourceLabel; sourceRunId=$priorRun.id; sourceLedgerRunId=$priorLedger.runId; carryover=[ordered]@{ businessRuns=$initialRuns; providerRequests=$initialProviderRequests; finalLedgerProviderRequests=@($priorLedger.providerRequests).Count; chargedUsd=$initialCharged; reservedUsd=[decimal]$priorLedger.budget.reservedUsd; remainingUsd=[decimal]$priorLedger.budget.remainingUsd }; plannedLabels=@($definitions | ForEach-Object { $_.Label }); fixedBatchStateGuard='passed'; crossDateHistoryGuard='passed'; duplicateEvidenceGuard='passed'; validatedSaveCompletionRepair='present'; stateTransition=$stateTransition; humanEvaluationTemplate=$templatePath; providerRequestsSent=0 } | ConvertTo-Json -Depth 8
    return
}

if (-not (Test-Path -LiteralPath $logs -PathType Container)) { $null = New-Item -ItemType Directory -Path $logs -Force }
if ($Plan -ne 'FinalFive') {
    try { $batchLock = [IO.File]::Open((Join-Path $batchEvidence 'batch-execution.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
    catch { throw 'Another process holds the batch execution lock, or the lock cannot be opened.' }
}
$docker = Require-Command 'docker.exe'
if ($ResumePreparedBatch -and $Plan -eq 'FinalFive') {
    Assert-UnsubmittedFinalFivePreparation
    if ($ResumeCheckOnly) { Write-Host 'FinalFive preparation-resume checks passed. No POST or provider request was sent.'; return }
    Set-BatchStage 'resume-preflight'
} elseif ($ResumePreparedBatch) {
    Assert-PreparedBatch
    if ($ResumeCheckOnly) { Write-Host 'Prepared-batch resume checks passed. No POST or provider request was sent.'; return }
    Set-BatchStage 'resume-validated'
}
if (-not $ResumePreparedBatch -or $Plan -eq 'FinalFive') {
if (-not $ResumePreparedBatch) { Set-BatchStage 'fresh-startup' }
$node = Require-Command 'node.exe'; $npm = Require-Command 'npm.cmd'
$javaHome = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { $env:JAVA_HOME } else { 'C:\Users\tcf\.jdks\ms-21.0.9' }
$java = Join-Path $javaHome 'bin\java.exe'; if (-not (Test-Path $java)) { throw 'JDK 21 is required. Set JAVA_HOME to a JDK 21 installation.' }
$javaVersion = (& $java -version 2>&1 | Out-String)
if ($javaVersion -notmatch '(?m)version "21\.') { throw "JDK 21 is required; resolved JAVA_HOME '$javaHome' is not JDK 21." }
$env:JAVA_HOME = $javaHome
if (($env:Path -split ';') -notcontains (Join-Path $javaHome 'bin')) { $env:Path = "$(Join-Path $javaHome 'bin');$env:Path" }
$tsx = Join-Path $root 'pi-agent\node_modules\tsx\dist\cli.mjs'; if (-not (Test-Path $tsx)) { throw "Pinned tsx runner is absent: $tsx" }

$env:PORT = "$GatewayPort"; $env:AGENT_MODE = 'live'; $env:JAVA_MODE = 'http'; $env:JAVA_BASE_URL = "http://127.0.0.1:$JavaPort"
$env:PI_PROVIDER = 'deepseek'; $env:PI_MODEL = 'deepseek-flash'; $env:SB12_BUDGET_USD = '1.00'
$env:SB12_INITIAL_BUSINESS_RUNS = "$initialRuns"; $env:SB12_INITIAL_CHARGED_USD = "$initialCharged"
$env:SB12_INPUT_TOKEN_RESERVE = '50000'; $env:SB12_PROVIDER_INPUT_OVERHEAD_TOKENS = '2000'
$env:PI_INPUT_USD_PER_MTOK = '0.30'; $env:PI_OUTPUT_USD_PER_MTOK = '1.20'; $env:PI_CACHE_READ_USD_PER_MTOK = '0.006'; $env:PI_CACHE_WRITE_USD_PER_MTOK = '0'; $env:PI_CACHE_WRITE_1H_USD_PER_MTOK = '0'; $env:PI_PRICING_SOURCE = 'https://api-docs.deepseek.com/quick_start/pricing/'
$env:AGENT_GATEWAY_TOKEN = "sb12-gateway-$([guid]::NewGuid().ToString('N'))"; $env:AGENT_EXECUTION_SECRET = "sb12-execution-$([guid]::NewGuid().ToString('N'))"; $env:JWT_SECRET = "sb12-jwt-$([guid]::NewGuid().ToString('N'))"

Copy-Item -LiteralPath $LedgerPath -Destination (Join-Path $batchEvidence "$sourceLabel.provider-ledger-copy.json")
Copy-Item -LiteralPath $JavaRunPath -Destination (Join-Path $batchEvidence "$sourceLabel.java-run-copy.json")
Write-JsonFile (Join-Path $batchEvidence 'carryover.json') ([ordered]@{ sourceRunId = $priorRun.id; sourceTraceId = $priorRun.traceId; initialBusinessRuns = $initialRuns; initialChargedUsd = $initialCharged; initialProviderRequests = $initialProviderRequests; finalLedgerProviderRequests = @($priorLedger.providerRequests).Count; importedAt = (Get-Date).ToUniversalTime().ToString('o'); acceptanceSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $AcceptancePath).Hash; ledgerSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $LedgerPath).Hash })

Push-Location $gatewayDir
try {
    & $node $tsx --tsconfig tsconfig.json scripts/preflight.ts | Set-Content -LiteralPath (Join-Path $batchEvidence 'preflight-live.json') -Encoding utf8NoBOM
    if ($LASTEXITCODE -ne 0) { throw 'SB-12 preflight failed.' }
    $preflight = Get-Content -LiteralPath (Join-Path $batchEvidence 'preflight-live.json') -Raw | ConvertFrom-Json
    if (-not $preflight.readyForSb12LiveRun -or $preflight.credentialPresence.DEEPSEEK_API_KEY -ne 'present') { throw 'SB-12 preflight is not ready.' }
    & $npm run check | Set-Content -LiteralPath (Join-Path $batchEvidence 'gateway-typecheck.txt') -Encoding utf8NoBOM; if ($LASTEXITCODE -ne 0) { throw 'Gateway type check failed.' }
    & $npm run provider:check | Set-Content -LiteralPath (Join-Path $batchEvidence 'provider-contract.txt') -Encoding utf8NoBOM; if ($LASTEXITCODE -ne 0) { throw 'Offline provider contract failed.' }
    & $npm run budget:check | Set-Content -LiteralPath (Join-Path $batchEvidence 'budget-contract.txt') -Encoding utf8NoBOM; if ($LASTEXITCODE -ne 0) { throw 'Offline budget contract failed.' }
} finally { Pop-Location }

# Existing SB-12 processes can keep the normal executable jar open for recovery. Build fresh classes
# without repackaging that locked jar, then start an isolated Java process from
# the compiled classes and an explicitly regenerated runtime classpath.
$runtimeClasspathFile = Join-Path $root ("target\sb12-{0}-runtime.classpath" -f $batchName)
if (-not $SkipBuild) {
    Push-Location $root
    try {
    & (Join-Path $root 'mvnw.cmd') -q -DskipTests '-Dspring-boot.repackage.skip=true' package | Set-Content -LiteralPath (Join-Path $logs 'java-package.log') -Encoding utf8NoBOM
    if ($LASTEXITCODE -ne 0) { throw 'Java root build failed.' }
    & (Join-Path $root 'mvnw.cmd') -q dependency:build-classpath "-Dmdep.outputFile=$runtimeClasspathFile" | Set-Content -LiteralPath (Join-Path $logs 'java-runtime-classpath.log') -Encoding utf8NoBOM
    if ($LASTEXITCODE -ne 0) { throw 'Java runtime classpath generation failed.' }
    } finally { Pop-Location }
}
$classes = Join-Path $root 'target\classes'
if (-not (Test-Path (Join-Path $classes 'com\urke\saasbackendstarter\SaasBackendStarterApplication.class')) -or -not (Test-Path $runtimeClasspathFile)) { throw 'Compiled Java classes or SB-12 runtime classpath are absent.' }
$javaClasspath = "$classes;$((Get-Content -LiteralPath $runtimeClasspathFile -Raw).Trim())"
$existingContainer = @(& $docker ps -a --format '{{.Names}}'); if ($existingContainer -contains $databaseContainer) { throw "Container '$databaseContainer' already exists." }
if ($LASTEXITCODE -ne 0) { throw 'Docker container inspection failed.' }
& $docker run --rm --detach --name $databaseContainer -e "POSTGRES_DB=$databaseName" -e POSTGRES_USER=sb12 -e POSTGRES_PASSWORD=sb12 -p "${PostgresPort}:5432" postgres:16-alpine | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL container creation failed.' }
for ($attempt = 0; $attempt -lt 60; $attempt++) { & $docker exec $databaseContainer pg_isready -U sb12 -d $databaseName | Out-Null; if ($LASTEXITCODE -eq 0) { break }; Start-Sleep -Milliseconds 500 }
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL did not become ready.' }
$nodeProcess = Start-Background $npm @('run','start') $gatewayDir 'gateway'; Wait-Http "http://127.0.0.1:$GatewayPort/health" 'Gateway'
$env:SERVER_PORT = "$JavaPort"; $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:$PostgresPort/$databaseName"; $env:SPRING_DATASOURCE_USERNAME = 'sb12'; $env:SPRING_DATASOURCE_PASSWORD = 'sb12'; $env:AGENT_GATEWAY_URL = "http://127.0.0.1:$GatewayPort"
$javaProcess = Start-Background $java @('-cp', $javaClasspath, 'com.urke.saasbackendstarter.SaasBackendStarterApplication') $root 'java'; Wait-Http "http://127.0.0.1:$JavaPort/actuator/health" 'Java API'
}

$implementationHashes = [ordered]@{}
foreach ($relative in @('src\runtime.ts','src\profiles.ts','src\storyboard-tools.ts','src\storyboard-schema.ts','src\budget.ts')) { $implementationHashes[$relative] = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $gatewayDir $relative)).Hash }

Set-BatchStage 'human-evaluation-packet'
$humanPacket = Join-Path $batchEvidence ("HUMAN_EVALUATION_PACKET_{0}.md" -f $batchName.ToUpperInvariant().Replace('-', '_'))
Initialize-HumanEvaluationPacket $humanPacket
Set-BatchStage 'login'
$base = "http://127.0.0.1:$JavaPort/api/v1"; $login = Invoke-Json 'POST' "$base/auth/login" @{ email='admin@demo.com'; password='admin12345' }; $auth = @{ Authorization = "Bearer $($login.accessToken)" }
$batchProgress = @()

foreach ($definition in $definitions) {
    Set-BatchStage 'prepare-run-evidence' $definition.Label
    $runEvidence = Join-Path $evidenceRoot $definition.Label; if (Test-Path $runEvidence) { throw "Evidence directory already exists: $runEvidence" }; $null = New-Item -ItemType Directory -Path $runEvidence
    $samplePath = Join-Path $root ("docs\storyboard-mvp\samples\" + $definition.Sample); $rawText = Get-FencedScene $samplePath; Copy-Item $samplePath (Join-Path $runEvidence "$($definition.Label).source.md")
    $requestId = "sb12-$($definition.Label.ToLower())-$([guid]::NewGuid().ToString('N'))"; $started = (Get-Date).ToUniversalTime()
    Write-JsonFile (Join-Path $runEvidence "$($definition.Label).configuration.json") ([ordered]@{ label=$definition.Label; provider='deepseek'; model='deepseek-flash'; targetShotCount=$definition.Shots; instructions=$definition.Instructions; implementationSha256=$implementationHashes; inputTokenReserve=50000; providerInputOverheadTokens=2000; maxOutputTokens=8192; maxRetries=0; carryover=(Get-Content (Join-Path $batchEvidence 'carryover.json') -Raw | ConvertFrom-Json); startedAt=$started.ToString('o') })
    Set-BatchStage 'create-project' $definition.Label
    $project = Invoke-Json 'POST' "$base/screenplay/projects" @{ name="SB-12 $($definition.Label)"; description='Real-model validation; isolated run.'; genre='drama' } $auth
    Set-BatchStage 'create-script' $definition.Label
    $script = Invoke-Json 'POST' "$base/screenplay/projects/$($project.id)/scripts" @{ versionName="sb12-$($definition.Label)"; originalFilename=$definition.Sample; rawText=$rawText } $auth
    Set-BatchStage 'analyze-script' $definition.Label
    $null = Invoke-Json 'POST' "$base/screenplay/scripts/$($script.id)/analyze" $null $auth; $scenes = @(Invoke-Json 'GET' "$base/screenplay/scripts/$($script.id)/scenes" $null $auth); if ($scenes.Count -ne 1) { throw "$($definition.Label) did not parse to one scene; no provider request was sent for this item." }
    $generationRequest = [ordered]@{ scriptId="$($script.id)"; sceneId="$($scenes[0].id)"; targetShotCount=$definition.Shots; instructions=$definition.Instructions; clientRequestId=$requestId }; Write-JsonFile (Join-Path $runEvidence "$($definition.Label).generation-request.json") $generationRequest
    Set-BatchStage 'submit-generation' $definition.Label
    Write-JsonFile (Join-Path $runEvidence "$($definition.Label).submission-marker.json") ([ordered]@{ label=$definition.Label; clientRequestId=$requestId; projectId=$project.id; submittedAt=(Get-Date).ToUniversalTime().ToString('o'); outcome='unknown-until-accepted-evidence'; automaticResubmissionAllowed=$false })
    if ($Plan -eq 'FinalFive') { Set-FixedBatchRunState $definition.Label 'unknown' 'submitting' @{ clientRequestId=$requestId; outputEvidencePath=$runEvidence } }
    $accepted = Invoke-Json 'POST' "$base/screenplay/projects/$($project.id)/storyboards/generations" $generationRequest $auth
    Write-JsonFile (Join-Path $runEvidence "$($definition.Label).accepted.json") $accepted
    if ($Plan -eq 'FinalFive') { Set-FixedBatchRunState $definition.Label 'submitted' 'accepted' @{ javaRunId=$accepted.runId } }
    Set-BatchStage 'wait-terminal-state' $definition.Label
    $javaRun = $null; for ($attempt=0; $attempt -lt 75; $attempt++) { $javaRun=Invoke-Json 'GET' "$base/screenplay/agent/runs/$($accepted.runId)" $null $auth; if ($javaRun.status -in @('completed','failed','cancelled','interrupted')) { break }; Start-Sleep -Seconds 2 }
    $ended=(Get-Date).ToUniversalTime(); if ($null -eq $javaRun -or $javaRun.status -notin @('completed','failed','cancelled','interrupted')) { throw "$($definition.Label) did not reach terminal state; no later run was submitted." }; Write-JsonFile (Join-Path $runEvidence "$($definition.Label).java-run.json") $javaRun
    if ($Plan -eq 'FinalFive') { Set-FixedBatchRunState $definition.Label 'terminal' $javaRun.status @{ traceId=$javaRun.traceId; resultRef=$javaRun.resultRef } }
    # Preserve the in-memory ledger before any frozen-context export failure.
    Set-BatchStage 'export-provider-ledger' $definition.Label
    $exportRunId = ([guid]$accepted.runId).ToString('D')
    $sql = "select json_build_object('gatewayRunId', r.gateway_run_id, 'userId', s.user_id) from agent_runs r join agent_sessions s on s.id=r.session_id where r.id='$exportRunId'"
    $binding = Invoke-Sb12PostgresJson -DockerPath $docker -Container $databaseContainer -Database $databaseName -SelectSql $sql
    if ([string]::IsNullOrWhiteSpace($binding.gatewayRunId) -or [string]::IsNullOrWhiteSpace($binding.userId)) { throw "$($definition.Label) Gateway binding is unavailable." }
    $gatewayRunId, $gatewayUserId = [string]$binding.gatewayRunId, [string]$binding.userId
    $ledger = Invoke-Json 'GET' "http://127.0.0.1:$GatewayPort/agent/runs/$gatewayRunId/ledger" $null @{ Authorization="Bearer $env:AGENT_GATEWAY_TOKEN"; 'X-Agent-User'=$gatewayUserId }
    Write-JsonFile (Join-Path $runEvidence "$($definition.Label).provider-ledger.json") $ledger
    Set-BatchStage 'export-frozen-context-and-storyboard' $definition.Label
    $frozenContext = Get-Sb12FrozenContext -DockerPath $docker -Container $databaseContainer -Database $databaseName -RunId $exportRunId
    Write-JsonFile (Join-Path $runEvidence "$($definition.Label).java-frozen-context.json") $frozenContext
    $storyboard=$null; if ($javaRun.status -eq 'completed' -and $javaRun.resultRef -and $javaRun.resultRef.storyboardId) { $storyboard=Invoke-Json 'GET' "$base/screenplay/storyboards/$($javaRun.resultRef.storyboardId)" $null $auth; Write-JsonFile (Join-Path $runEvidence "$($definition.Label).java-storyboard.json") $storyboard; Add-HumanEvaluation $humanPacket $definition $storyboard }
    Set-BatchStage 'record-acceptance-and-progress' $definition.Label
    $criticalChecks = Get-AutomatedAcceptance $definition $storyboard
    Write-JsonFile (Join-Path $runEvidence "$($definition.Label).automatic-precheck.json") ([ordered]@{ label='自动预检查'; javaRunId=$accepted.runId; gatewayRunId=$gatewayRunId; traceId=$javaRun.traceId; status=$javaRun.status; startedAt=$started.ToString('o'); endedAt=$ended.ToString('o'); durationMs=[int][Math]::Round(($ended-$started).TotalMilliseconds); expectedShotCount=$definition.Shots; actualShotCount=if($storyboard){@($storyboard.shots).Count}else{$null}; allSourceQuotesInJavaFrozenScene=if($storyboard){@($storyboard.shots|Where-Object{-not $storyboard.sourceSnapshot.sceneText.Contains($_.sourceQuote)}).Count -eq 0}else{$false}; allImagePromptsPresent=if($storyboard){@($storyboard.shots|Where-Object{[string]::IsNullOrWhiteSpace($_.imagePrompt)}).Count -eq 0}else{$false}; allVideoPromptsPresent=if($storyboard){@($storyboard.shots|Where-Object{[string]::IsNullOrWhiteSpace($_.videoPrompt)}).Count -eq 0}else{$false}; criticalFactAutomatedChecks=$criticalChecks; humanEvaluation='待人工评价：直接用 / 小改可用 / 需要重做。' })
    $batchProgress += [ordered]@{ label=$definition.Label; javaRunId=$accepted.runId; gatewayRunId=$gatewayRunId; traceId=$javaRun.traceId; status=$javaRun.status; resultRef=$javaRun.resultRef; providerRequests=@($ledger.providerRequests).Count; retries=(@($ledger.providerRequests | ForEach-Object { $_.retryPolicy.retries } | Measure-Object -Sum).Sum); chargedUsd=$ledger.budget.chargedUsd; remainingUsd=$ledger.budget.remainingUsd }
    Write-JsonFile (Join-Path $batchEvidence 'batch-progress.json') ([ordered]@{ carryover=(Get-Content (Join-Path $batchEvidence 'carryover.json') -Raw | ConvertFrom-Json); completed=$batchProgress; updatedAt=(Get-Date).ToUniversalTime().ToString('o') })
    if ($javaRun.status -ne 'completed') { throw "$($definition.Label) failed; evidence retained and no later run was submitted." }
}
Set-BatchStage 'completed'
Write-Host "SB-12 remaining runs completed. Evidence: $batchEvidence"
Write-Host "Processes/container retained for review. DB=$databaseContainer"
} catch {
    if ($batchLock -and -not $ResumeCheckOnly -and (Test-Path -LiteralPath $batchEvidence -PathType Container)) {
        # Never serialize exception bodies, HTTP headers, tokens, or process environment.
        $failurePath = Join-Path $batchEvidence ("batch-failure-{0}.json" -f [guid]::NewGuid().ToString('N'))
        Write-JsonFile $failurePath ([ordered]@{ stage=$currentStage; label=$currentLabel; resume=[bool]$ResumePreparedBatch; failedAt=(Get-Date).ToUniversalTime().ToString('o'); automaticResubmissionAllowed=$false; reason='Batch stopped; inspect preserved stage and run evidence before any continuation.' })
    }
    throw
} finally {
    if ($batchLock) { $batchLock.Dispose() }
}
