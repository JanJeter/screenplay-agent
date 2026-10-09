#requires -Version 7.2
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$launcher = Join-Path $PSScriptRoot 'run-sb12-final-five-live.ps1'
$remainingLauncher = Join-Path $PSScriptRoot 'run-sb12-remaining-live.ps1'
$oneALauncher = Join-Path $PSScriptRoot 'run-sb12-01a-live.ps1'
$baselineBatch = Join-Path $root 'docs\storyboard-mvp\quality-runs\SB12_FIXED_EIGHT_BATCH'
$initialStateFixture = Join-Path $PSScriptRoot 'fixtures\sb12-final-five-initial-state.json'
$ledger = Join-Path $root 'docs\storyboard-mvp\quality-runs\20261007\01-8-A\01-8-A.provider-ledger.json'
$javaRun = Join-Path $root 'docs\storyboard-mvp\quality-runs\20261007\01-8-A\01-8-A.java-run.json'
foreach ($path in @($launcher, $remainingLauncher, $oneALauncher, $ledger, $javaRun, (Join-Path $baselineBatch 'batch-identity.json'), $initialStateFixture)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required SB-12 validation input missing: $path" }
}

function New-TestBatchState([string]$Path) {
    $null = New-Item -ItemType Directory -Path $Path -Force
    Copy-Item -LiteralPath (Join-Path $baselineBatch 'batch-identity.json') -Destination (Join-Path $Path 'batch-identity.json')
    # Never clone the production terminal state as a "first run" fixture.
    # This separate initial fixture models the only legal pre-submission state.
    Copy-Item -LiteralPath $initialStateFixture -Destination (Join-Path $Path 'batch-execution-state.json')
}
function Assert-Rejected([scriptblock]$Action, [string]$Pattern, [string]$Message) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $_.Exception.Message -match $Pattern }
    if (-not $rejected) { throw $Message }
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("sb12-final-five-entry-" + [guid]::NewGuid().ToString('N'))
try {
    $null = New-Item -ItemType Directory -Path $testRoot
    $pendingApproval = Join-Path $testRoot 'D-review-pending-for-offline-test.md'
    Set-Content -LiteralPath $pendingApproval -Encoding utf8NoBOM -Value '后五项继续执行授权：PENDING_D_REVIEW'
    $approval = Join-Path $testRoot 'D-review-approved-for-offline-test.md'
    Set-Content -LiteralPath $approval -Encoding utf8NoBOM -Value "offline validation fixture only`n后五项继续执行授权：APPROVED"

    # The repository's actual review remains pending; this uses only a disposable
    # approval fixture and never reads a provider credential.
    Assert-Rejected { & $launcher -AcceptancePath $pendingApproval -ValidateOnly -EvidenceRootOverride (Join-Path $testRoot 'pending-output') } '后五项继续执行授权：APPROVED' 'Final-five entry did not require independent D authorization before validation/execution.'

    $firstState = Join-Path $testRoot 'first-state'; $firstHistory = Join-Path $testRoot 'first-history'
    New-TestBatchState $firstState; $null = New-Item -ItemType Directory -Path $firstHistory
    $validationDir = Join-Path $testRoot 'template'
    $output = & $launcher -AcceptancePath $approval -ValidateOnly -EvidenceRootOverride (Join-Path $testRoot 'first-output') -ValidationDirectory $validationDir -BatchStateDirectoryOverride $firstState -HistoryEvidenceRootOverride $firstHistory
    $result = ($output | Out-String | ConvertFrom-Json)
    if (-not $result.validationOnly -or $result.plan -ne 'FinalFive' -or $result.fixedBatchId -ne 'SB12-FIXED-EIGHT-20261007' -or ($result.plannedLabels -join ',') -ne '01-8-B,02-A,02-B,03-A,03-B') { throw 'First legal final-five validation did not expose the fixed plan identity and exact five-run plan.' }
    if ($result.carryover.businessRuns -ne 3 -or [decimal]$result.carryover.chargedUsd -ne [decimal]'0.00748236' -or $result.carryover.providerRequests -ne 9 -or [decimal]$result.carryover.remainingUsd -ne [decimal]'0.99251764' -or [decimal]$result.carryover.reservedUsd -ne 0) { throw 'Final-five entry did not inherit the 01-8-A terminal ledger exactly.' }
    if ($result.providerRequestsSent -ne 0 -or $result.validatedSaveCompletionRepair -ne 'present' -or $result.fixedBatchStateGuard -ne 'passed' -or $result.crossDateHistoryGuard -ne 'passed' -or -not (Test-Path -LiteralPath $result.humanEvaluationTemplate)) { throw 'Validation-only entry did not preserve fixed-state/no-provider/repair/template guarantees.' }
    $template = Get-Content -LiteralPath $result.humanEvaluationTemplate -Raw
    foreach ($label in @('01-8-B', '02-A', '02-B', '03-A', '03-B')) { if ($template -notmatch [regex]::Escape($label)) { throw "Evaluation template omits $label." } }
    if ($template -notmatch '□ 直接用  □ 小改可用  □ 需要重做') { throw 'Evaluation template lacks the required blank human-evaluation choices.' }

    # A pre-Java, pre-submission failure may retain its evidence. Resume checks
    # may reuse it only after proving there are no business-operation markers.
    $resumeState = Join-Path $testRoot 'resume-state'; $resumeHistory = Join-Path $testRoot 'resume-history'; $resumeOutputRoot = Join-Path $testRoot 'resume-output'
    New-TestBatchState $resumeState; $null = New-Item -ItemType Directory -Path $resumeHistory
    $resumeBatch = Join-Path $resumeOutputRoot 'final-five-batch'; $null = New-Item -ItemType Directory -Path (Join-Path $resumeBatch 'logs') -Force
    @{ plan='FinalFive'; stage='fresh-startup'; label=''; resume=$false } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $resumeBatch 'batch-stage.json') -Encoding utf8NoBOM
    $resumeCheck = (& $launcher -AcceptancePath $approval -ResumePreparedBatch -ResumeCheckOnly -EvidenceRootOverride $resumeOutputRoot -BatchStateDirectoryOverride $resumeState -HistoryEvidenceRootOverride $resumeHistory 6>&1 | Out-String)
    if ($resumeCheck -notmatch 'FinalFive preparation-resume checks passed') { throw 'A verified pre-submission preparation failure was not eligible for safe ResumeCheckOnly.' }

    # Exercise the production state writer (not a fixture-side JSON edit) and
    # re-read its persisted terminal value.
    $transitionState = Join-Path $testRoot 'transition-state'; $transitionHistory = Join-Path $testRoot 'transition-history'; New-TestBatchState $transitionState; $null = New-Item -ItemType Directory -Path $transitionHistory
    $transitionOutput = & $launcher -AcceptancePath $approval -ValidateOnly -OfflineStateTransitionTest -EvidenceRootOverride (Join-Path $testRoot 'transition-output') -BatchStateDirectoryOverride $transitionState -HistoryEvidenceRootOverride $transitionHistory
    $transitionResult = ($transitionOutput | Out-String | ConvertFrom-Json)
    if ($transitionResult.stateTransition.finalSubmissionStatus -ne 'terminal' -or $transitionResult.stateTransition.finalExecutionStatus -ne 'completed' -or [string]::IsNullOrWhiteSpace($transitionResult.stateTransition.updatedAt)) { throw 'Production state writer did not persist not-submitted → unknown → submitted → terminal.' }
    $transitionedState = Get-Content -LiteralPath (Join-Path $transitionState 'batch-execution-state.json') -Raw | ConvertFrom-Json
    if ($transitionedState.finalFive.'01-8-B'.submissionStatus -ne 'terminal' -or [string]::IsNullOrWhiteSpace($transitionedState.finalFive.'01-8-B'.updatedAt)) { throw 'Production state writer did not write the terminal transition to disk.' }
    Assert-Rejected { & $launcher -AcceptancePath $approval -ValidateOnly -EvidenceRootOverride (Join-Path $testRoot 'terminal-restart-output') -BatchStateDirectoryOverride $transitionState -HistoryEvidenceRootOverride $transitionHistory } "records 01-8-B as 'terminal'" 'A terminal submission was eligible for a full replay.'

    # A historical marker is authoritative even when today's output directory
    # is fresh. Test both same-date and next-date evidence roots.
    $sameDayState = Join-Path $testRoot 'same-day-state'; $sameDayHistory = Join-Path $testRoot 'same-day-history'; New-TestBatchState $sameDayState
    $null = New-Item -ItemType Directory -Path (Join-Path $sameDayHistory '20261007\01-8-B') -Force
    Set-Content -LiteralPath (Join-Path $sameDayHistory '20261007\01-8-B\01-8-B.submission-marker.json') -Value '{}' -Encoding utf8NoBOM
    Assert-Rejected { & $launcher -AcceptancePath $approval -ValidateOnly -EvidenceRootOverride (Join-Path $testRoot 'same-day-new-output') -BatchStateDirectoryOverride $sameDayState -HistoryEvidenceRootOverride $sameDayHistory } 'Historical SB-12 evidence exists for 01-8-B' 'Same-day submitted evidence was not rejected.'

    $crossDayState = Join-Path $testRoot 'cross-day-state'; $crossDayHistory = Join-Path $testRoot 'cross-day-history'; New-TestBatchState $crossDayState
    $null = New-Item -ItemType Directory -Path (Join-Path $crossDayHistory '20261008\02-A') -Force
    Set-Content -LiteralPath (Join-Path $crossDayHistory '20261008\02-A\02-A.accepted.json') -Value '{}' -Encoding utf8NoBOM
    Assert-Rejected { & $launcher -AcceptancePath $approval -ValidateOnly -EvidenceRootOverride (Join-Path $testRoot '20261009-new-output') -BatchStateDirectoryOverride $crossDayState -HistoryEvidenceRootOverride $crossDayHistory } 'Historical SB-12 evidence exists for 02-A' 'Cross-date evidence or an output-directory change bypassed duplicate protection.'

    # An unknown outcome is never eligible for automatic replay.
    $unknownState = Join-Path $testRoot 'unknown-state'; $unknownHistory = Join-Path $testRoot 'unknown-history'; New-TestBatchState $unknownState; $null = New-Item -ItemType Directory -Path $unknownHistory
    $unknown = Get-Content -LiteralPath (Join-Path $unknownState 'batch-execution-state.json') -Raw | ConvertFrom-Json
    $unknown.finalFive.'01-8-B'.submissionStatus = 'unknown'; $unknown.finalFive.'01-8-B'.executionStatus = 'submitting'
    $unknown | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath (Join-Path $unknownState 'batch-execution-state.json') -Encoding utf8NoBOM
    Assert-Rejected { & $launcher -AcceptancePath $approval -ValidateOnly -EvidenceRootOverride (Join-Path $testRoot 'unknown-output') -BatchStateDirectoryOverride $unknownState -HistoryEvidenceRootOverride $unknownHistory } "records 01-8-B as 'unknown'" 'An unknown submission result was not rejected.'

    # The two historic submission entrypoints must refuse before accepting an
    # output-root override, secret, service or budget source.
    Assert-Rejected { & $remainingLauncher -Plan RemainingSeven -ValidateOnly -EvidenceRootOverride (Join-Path $testRoot 'legacy-next-date-output') } 'RemainingSeven is retired' 'The retired RemainingSeven entry could still replay 01-B/01-8-A on a new date.'
    Assert-Rejected { & $oneALauncher -PrepareOnly 6>$null } '01-A launcher is retired' 'The retired 01-A entry could still create a new-date execution path.'

    # FileShare.None models the other executor. ValidateOnly takes the same
    # fixed lock as an actual execution, so it exercises the production guard.
    $lockState = Join-Path $testRoot 'lock-state'; $lockHistory = Join-Path $testRoot 'lock-history'; New-TestBatchState $lockState; $null = New-Item -ItemType Directory -Path $lockHistory
    $lock = [IO.File]::Open((Join-Path $lockState 'batch-execution.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try {
        Assert-Rejected { & $launcher -AcceptancePath $approval -ValidateOnly -EvidenceRootOverride (Join-Path $testRoot 'concurrent-output') -BatchStateDirectoryOverride $lockState -HistoryEvidenceRootOverride $lockHistory } 'Another executor holds the fixed SB-12 batch lock' 'A concurrent final-five executor was not rejected by the fixed batch lock.'
    } finally { $lock.Dispose() }

    [ordered]@{ status='passed'; providerRequestsSent=0; validated=@('D-authorization-gate','first-legal-entry','01-8-A-carryover','validated-save-completion-repair-present','production-state-write-and-reload','terminal-submission-rejection','same-day-duplicate-rejection','cross-date-duplicate-rejection','unknown-submission-rejection','output-directory-bypass-rejection','retired-remaining-seven-rejection','retired-01a-rejection','fixed-batch-exclusive-lock','safe-pre-submission-preparation-resume','human-evaluation-template'); temporaryRoot=$testRoot } | ConvertTo-Json -Compress
} finally {
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
