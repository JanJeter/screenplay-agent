#requires -Version 7.2
[CmdletBinding()]
param(
    [string]$EvidenceDirectory,
    [string]$Container = 'screenplay-sb12-final-five-postgres',
    [string]$Database = 'screenplay_sb12_final_five',
    [string]$Username = 'sb12',
    [switch]$GenerateHumanEvaluationV2,
    [string]$StoryboardJsonPath
)

# Exports only the already-persisted 03-B evidence. It does not contact the
# Gateway/provider, mutate Java state, or use any application credential.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\sb12-postgres-json.ps1')
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) {
    $EvidenceDirectory = Join-Path $root 'docs\storyboard-mvp\quality-runs\20261008\03-B'
}
if (-not (Test-Path -LiteralPath $EvidenceDirectory -PathType Container)) { throw "03-B evidence directory is missing: $EvidenceDirectory" }
$runId = '9b0f71b0-07bf-4bfb-850d-9f9de08cfd80'
$storyboardId = 'be29bb7c-28fc-4d0c-9232-2af921ed1784'

function Write-JsonEvidence([string]$Path, $Value) {
    $text = $Value | ConvertTo-Json -Depth 100
    if ($text -match '(?i)"(?:authorization|executionToken|api[_-]?key|password)"\s*:') {
        throw 'Refusing to write supplemental evidence containing a credential field.'
    }
    Set-Content -LiteralPath $Path -Value $text -Encoding utf8NoBOM
}
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash }
function New-HumanEvaluationMarkdown($Storyboard, [string]$Title) {
    # Keep Markdown syntax literal. In PowerShell, a backtick in a double-quoted
    # string is an escape character (for example `f and `t), so never compose
    # Markdown fences or inline code with interpolated double-quoted templates.
    $fence = '```'
    $builder = [System.Text.StringBuilder]::new()
    [void]$builder.AppendLine($Title)
    [void]$builder.AppendLine()
    [void]$builder.AppendLine('> 状态：**仅供真人评价已保存实体；原 Java run 仍为 `failed / gateway_failed`，不改写为 completed。**')
    [void]$builder.AppendLine('> 来源：已保存的只读 JSON 导出；本文件不代表新的生成或重跑。')
    [void]$builder.AppendLine()
    [void]$builder.AppendLine('## 原场景')
    [void]$builder.AppendLine()
    [void]$builder.AppendLine($fence + 'text')
    [void]$builder.AppendLine([string]$Storyboard.sourceSnapshot.sceneText)
    [void]$builder.AppendLine($fence)
    [void]$builder.AppendLine()
    foreach ($shot in @($Storyboard.shots | Sort-Object orderIndex)) {
        [void]$builder.AppendLine('## 镜 ' + ([int]$shot.orderIndex + 1))
        [void]$builder.AppendLine()
        [void]$builder.AppendLine('- sourceQuote：' + [string]$shot.sourceQuote)
        [void]$builder.AppendLine('- 画面：' + [string]$shot.visualDescription)
        [void]$builder.AppendLine('- 图像 Prompt：' + [string]$shot.imagePrompt)
        [void]$builder.AppendLine('- 视频 Prompt：' + [string]$shot.videoPrompt)
        [void]$builder.AppendLine('- 人工评价：□ 直接用  □ 小改可用  □ 需要重做')
        [void]$builder.AppendLine('- 备注：')
        [void]$builder.AppendLine()
    }
    $builder.ToString()
}
function Assert-HumanEvaluationMarkdown([string]$Markdown, $Storyboard) {
    $unexpectedControls = @($Markdown.ToCharArray() | Where-Object {
        $code = [int][char]$_
        $code -lt 32 -and $code -notin @(10, 13)
    })
    if ($unexpectedControls.Count -gt 0) {
        $codes = ($unexpectedControls | ForEach-Object { '0x{0:X2}' -f [int][char]$_ } | Select-Object -Unique) -join ', '
        throw "Human-evaluation Markdown contains unexpected control characters: $codes"
    }
    if ($Markdown -notmatch [regex]::Escape('`failed / gateway_failed`')) { throw 'Human-evaluation Markdown is missing the literal failed/gateway_failed status.' }
    $fenceLines = @($Markdown -split "\r?\n" | Where-Object { $_ -eq '```' -or $_ -eq '```text' })
    if ($fenceLines.Count -ne 2 -or $fenceLines[0] -ne '```text' -or $fenceLines[1] -ne '```') { throw 'Human-evaluation Markdown source-scene fence is not a single matched pair.' }
    $fence = '```'
    $newline = [Environment]::NewLine
    $openingPrefix = $fence + 'text' + $newline
    $closingPrefix = $newline + $fence
    $opening = $Markdown.IndexOf($openingPrefix, [StringComparison]::Ordinal)
    $closing = $Markdown.IndexOf($closingPrefix, $opening, [StringComparison]::Ordinal)
    if ($opening -lt 0 -or $closing -le $opening) { throw 'Human-evaluation Markdown cannot locate a complete source-scene code block.' }
    $sceneStart = $opening + $openingPrefix.Length
    $scene = $Markdown.Substring($sceneStart, $closing - $sceneStart).TrimEnd([char[]]@([char]13, [char]10))
    if ($scene -ne [string]$Storyboard.sourceSnapshot.sceneText) { throw 'Human-evaluation Markdown source scene does not match the saved storyboard JSON.' }
    $afterFence = $Markdown.Substring($closing + $closingPrefix.Length)
    if ($afterFence -notmatch '(?m)^## 镜 1\r?$') { throw 'Markdown preview guard: the source code block would swallow the first shot heading.' }
    $shots = @($Storyboard.shots | Sort-Object orderIndex)
    foreach ($index in 0..($shots.Count - 1)) {
        $shot = $shots[$index]
        $heading = '## 镜 ' + ($index + 1)
        if ($Markdown -notmatch ('(?m)^' + [regex]::Escape($heading) + '\r?$')) { throw "Human-evaluation Markdown is missing $heading." }
        $requiredLines = @(
            ('- sourceQuote：' + [string]$shot.sourceQuote)
            ('- 图像 Prompt：' + [string]$shot.imagePrompt)
            ('- 视频 Prompt：' + [string]$shot.videoPrompt)
        )
        foreach ($line in $requiredLines) {
            if ($Markdown.IndexOf($line, [StringComparison]::Ordinal) -lt 0) { throw "Human-evaluation Markdown is missing required shot content for $heading." }
        }
    }
    if (([regex]::Matches($Markdown, [regex]::Escape('- 人工评价：□ 直接用  □ 小改可用  □ 需要重做'))).Count -ne $shots.Count) { throw 'Human-evaluation Markdown does not have one blank human-evaluation row per shot.' }
}

if ($GenerateHumanEvaluationV2) {
    if ([string]::IsNullOrWhiteSpace($StoryboardJsonPath)) { $StoryboardJsonPath = Join-Path $EvidenceDirectory '03-B.java-storyboard.readonly-recovered.json' }
    if (-not (Test-Path -LiteralPath $StoryboardJsonPath -PathType Leaf)) { throw "Saved 03-B storyboard JSON is missing: $StoryboardJsonPath" }
    $v2Packet = Join-Path $EvidenceDirectory '03-B.HUMAN_EVALUATION_SUPPLEMENT.v2.md'
    $v2Record = Join-Path $EvidenceDirectory '03-B.human-evaluation-v2-record.json'
    foreach ($path in @($v2Packet, $v2Record)) { if (Test-Path -LiteralPath $path) { throw "Refusing to overwrite existing v2 human-evaluation evidence: $path" } }
    $storyboard = Get-Content -LiteralPath $StoryboardJsonPath -Raw | ConvertFrom-Json
    $javaRunPath = Join-Path $EvidenceDirectory '03-B.java-run.json'
    $javaRun = Get-Content -LiteralPath $javaRunPath -Raw | ConvertFrom-Json
    if ($storyboard.id -ne $storyboardId -or $storyboard.sourceRunId -ne $runId -or @($storyboard.shots).Count -ne 4) { throw 'The saved storyboard JSON does not match the expected 03-B artifact, run, or shot count.' }
    if ($javaRun.id -ne $runId -or $javaRun.status -ne 'failed' -or $javaRun.errorCode -ne 'gateway_failed') { throw 'The historical 03-B Java failed status does not match the required preserved state.' }
    $markdown = New-HumanEvaluationMarkdown $storyboard '# SB-12 03-B 已保存实体：人工逐镜评价补充材料（v2）'
    Assert-HumanEvaluationMarkdown $markdown $storyboard
    Set-Content -LiteralPath $v2Packet -Value $markdown -Encoding utf8NoBOM
    $finalMarkdown = Get-Content -LiteralPath $v2Packet -Raw
    Assert-HumanEvaluationMarkdown $finalMarkdown $storyboard
    $record = [ordered]@{
        label = '03-B'; generatedAt = (Get-Date).ToUniversalTime().ToString('o'); mode = 'offline-saved-json-only'; providerRequestsSent = 0
        replacement = [ordered]@{ supersedes = '03-B.HUMAN_EVALUATION_SUPPLEMENT.md'; replacement = '03-B.HUMAN_EVALUATION_SUPPLEMENT.v2.md'; reason = 'PowerShell backtick escaping corrupted the prior Markdown status and source-scene fence.' }
        source = [ordered]@{ path = (Resolve-Path -LiteralPath $StoryboardJsonPath).Path; sha256 = Hash $StoryboardJsonPath; storyboardId = $storyboard.id; sourceRunId = $storyboard.sourceRunId }
        output = [ordered]@{ path = (Resolve-Path -LiteralPath $v2Packet).Path; sha256 = Hash $v2Packet }
        javaRunStatusPreserved = 'failed'; javaErrorCodePreserved = 'gateway_failed'; savedShotCount = @($storyboard.shots).Count
        validation = [ordered]@{ noUnexpectedControlCharacters = $true; failedStatusLiteral = $true; sourceFencePair = $true; sourceSceneMatchesJson = $true; fourShotsCompleteAndOrdered = $true; blankHumanEvaluationRows = $true; markdownPreviewFenceGuard = $true }
    }
    Write-JsonEvidence $v2Record $record
    [ordered]@{ status='passed'; mode='offline-saved-json-only'; providerRequestsSent=0; javaRunStatusPreserved='failed'; humanEvaluationPacket=$v2Packet; evidenceRecord=$v2Record } | ConvertTo-Json -Compress
    return
}

$docker = (Get-Command docker -ErrorAction Stop).Source
$outputs = @(
    '03-B.java-storyboard.readonly-recovered.json',
    '03-B.agent-events.readonly-recovered.json',
    '03-B.save-events.readonly-recovered.json',
    '03-B.final-model-receipt.readonly-recovered.txt',
    '03-B.readonly-export-record.json',
    '03-B.HUMAN_EVALUATION_SUPPLEMENT.md'
) | ForEach-Object { Join-Path $EvidenceDirectory $_ }
foreach ($path in $outputs) { if (Test-Path -LiteralPath $path) { throw "Refusing to overwrite existing 03-B supplemental evidence: $path" } }

$common = @{ DockerPath=$docker; Container=$Container; Database=$Database; Username=$Username }
$storyboard = Invoke-Sb12PostgresJson @common -SelectSql @"
SELECT json_build_object(
  'id', d.id::text, 'projectId', d.project_id::text, 'createdBy', d.created_by::text,
  'sourceRunId', d.source_run_id::text,
  'sourceSnapshot', json_build_object('scriptId', d.source_script_id::text, 'scriptRevision', d.source_script_revision,
    'sourceSceneId', d.source_scene_id::text, 'sceneNo', d.source_scene_no, 'heading', d.source_heading,
    'sceneText', d.source_scene_text, 'sceneHash', d.source_scene_hash),
  'revision', d.revision, 'createdAt', d.created_at, 'updatedAt', d.updated_at,
  'shots', COALESCE((SELECT json_agg(json_build_object('id', s.id::text, 'orderIndex', s.order_index,
    'shotSize', s.shot_size, 'cameraMovement', s.camera_movement, 'visualDescription', s.visual_description,
    'dialogue', s.dialogue, 'sound', s.sound, 'durationSeconds', s.duration_seconds,
    'imagePrompt', s.image_prompt, 'videoPrompt', s.video_prompt, 'sourceQuote', s.source_quote) ORDER BY s.order_index)
    FROM storyboard_shots s WHERE s.storyboard_id = d.id), '[]'::json),
  'proposalSummaries', '[]'::json)
FROM storyboard_drafts d
WHERE d.id = '$storyboardId' AND d.source_run_id = '$runId'
"@
if ($storyboard.id -ne $storyboardId -or $storyboard.sourceRunId -ne $runId -or @($storyboard.shots).Count -ne 4) {
    throw 'Read-only export did not find the expected 03-B storyboard ownership or four saved shots.'
}
$events = Invoke-Sb12PostgresJson @common -SelectSql @"
SELECT COALESCE(json_agg(json_build_object('sequence', sequence, 'type', type, 'payload', payload::json) ORDER BY sequence), '[]'::json)
FROM agent_events WHERE run_id = '$runId'
"@
$saveEvents = @($events | Where-Object { $_.sequence -in @(17, 19, 35, 36, 68, 70) })
$successfulSave = @($saveEvents | Where-Object { $_.sequence -eq 70 -and $_.type -eq 'tool.completed' -and $_.payload.data.isError -eq $false })
if ($successfulSave.Count -ne 1) { throw 'Read-only export did not find exactly one successful final 03-B save event.' }
$receipt = Invoke-Sb12PostgresJson @common -SelectSql @"
SELECT json_build_object('runId', '$runId', 'fromSequence', 73, 'toSequence', 176,
  'text', COALESCE(string_agg(payload::json #>> '{data,delta}', '' ORDER BY sequence), ''))
FROM agent_events
WHERE run_id = '$runId' AND type = 'text.delta' AND sequence BETWEEN 73 AND 176
"@
if ([string]::IsNullOrWhiteSpace($receipt.text)) { throw 'Read-only export could not reconstruct the final 03-B model receipt.' }

Write-JsonEvidence $outputs[0] $storyboard
Write-JsonEvidence $outputs[1] $events
Write-JsonEvidence $outputs[2] $saveEvents
Set-Content -LiteralPath $outputs[3] -Value $receipt.text -Encoding utf8NoBOM
$packet = New-HumanEvaluationMarkdown $storyboard '# SB-12 03-B 已保存实体：人工逐镜评价补充材料'
Assert-HumanEvaluationMarkdown $packet $storyboard
Set-Content -LiteralPath $outputs[5] -Value $packet -Encoding utf8NoBOM
$record = [ordered]@{
    label = '03-B'; exportedAt = (Get-Date).ToUniversalTime().ToString('o'); mode = 'postgresql-read-only'
    javaRunId = $runId; storyboardId = $storyboardId; javaRunStatusPreserved = 'failed'; javaErrorCodePreserved = 'gateway_failed'
    savedShotCount = @($storyboard.shots).Count; successfulSaveEventSequence = 70; finalReceiptSequences = '73-176'
    files = [ordered]@{
        storyboard = [IO.Path]::GetFileName($outputs[0]); events = [IO.Path]::GetFileName($outputs[1]); saveEvents = [IO.Path]::GetFileName($outputs[2]);
        finalReceipt = [IO.Path]::GetFileName($outputs[3]); humanEvaluation = [IO.Path]::GetFileName($outputs[5])
    }
    sha256 = [ordered]@{ storyboard = Hash $outputs[0]; events = Hash $outputs[1]; saveEvents = Hash $outputs[2]; finalReceipt = Hash $outputs[3]; humanEvaluation = Hash $outputs[5] }
    providerRequestsSent = 0
}
Write-JsonEvidence $outputs[4] $record
[ordered]@{ status='passed'; mode='postgresql-read-only'; providerRequestsSent=0; javaRunStatusPreserved='failed'; storyboardId=$storyboardId; savedShotCount=@($storyboard.shots).Count; evidenceRecord=$outputs[4] } | ConvertTo-Json -Compress
