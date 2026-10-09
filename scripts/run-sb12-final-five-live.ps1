[CmdletBinding()]
param(
    [string]$AcceptancePath = (Join-Path $PSScriptRoot '..\docs\storyboard-mvp\SB12_FINAL_FIVE_REVIEW.md'),
    [int]$PostgresPort = 15435,
    [int]$JavaPort = 18083,
    [int]$GatewayPort = 13004,
    [switch]$SkipBuild,
    [switch]$ValidateOnly,
    [switch]$ResumePreparedBatch,
    [switch]$ResumeCheckOnly,
    [string]$EvidenceRootOverride,
    [string]$ValidationDirectory,
    [string]$BatchStateDirectoryOverride,
    [string]$HistoryEvidenceRootOverride,
    [switch]$OfflineStateTransitionTest
)

# Deliberately narrow entrypoint: it cannot include 01-A, 01-B, or 01-8-A.
# The delegated launcher validates the final failed 01-8-A ledger before it can
# create a service or submit a generation.
$arguments = @{
    Plan = 'FinalFive'
    AcceptancePath = $AcceptancePath
    PostgresPort = $PostgresPort
    JavaPort = $JavaPort
    GatewayPort = $GatewayPort
}
if ($SkipBuild) { $arguments.SkipBuild = $true }
if ($ValidateOnly) { $arguments.ValidateOnly = $true }
if ($ResumePreparedBatch) { $arguments.ResumePreparedBatch = $true }
if ($ResumeCheckOnly) { $arguments.ResumeCheckOnly = $true }
if ($EvidenceRootOverride) { $arguments.EvidenceRootOverride = $EvidenceRootOverride }
if ($ValidationDirectory) { $arguments.ValidationDirectory = $ValidationDirectory }
if ($BatchStateDirectoryOverride) { $arguments.BatchStateDirectoryOverride = $BatchStateDirectoryOverride }
if ($HistoryEvidenceRootOverride) { $arguments.HistoryEvidenceRootOverride = $HistoryEvidenceRootOverride }
if ($OfflineStateTransitionTest) { $arguments.OfflineStateTransitionTest = $true }

& (Join-Path $PSScriptRoot 'run-sb12-remaining-live.ps1') @arguments
