#requires -Version 7.2

function Invoke-Sb12PostgresJson {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$DockerPath,
        [Parameter(Mandatory)][string]$Container,
        [Parameter(Mandatory)][string]$Database,
        [string]$Username = 'sb12',
        [Parameter(Mandatory)][string]$SelectSql
    )

    # Keep Docker stdout ASCII even when PowerShell decodes native output as
    # GBK. The scalar subquery rejects multiple rows; zero rows become NULL.
    # PostgreSQL wraps base64 at 76 columns, so every output line is required.
    $sql = @"
SELECT encode(convert_to(
    (SELECT sb12_json.payload::text FROM ($SelectSql) AS sb12_json(payload)),
    'UTF8'), 'base64')
"@
    $PSNativeCommandUseErrorActionPreference = $false
    $lines = @(& $DockerPath exec --env 'PGOPTIONS=-c default_transaction_read_only=on' --env 'PGCLIENTENCODING=UTF8' `
        $Container psql -X -q -A -t -v ON_ERROR_STOP=1 -U $Username -d $Database -c $sql 2>&1)
    if ($LASTEXITCODE -ne 0) {
        # Do not echo SQL, stderr, or row contents into an evidence log.
        throw "Read-only PostgreSQL JSON export failed (exit code $LASTEXITCODE)."
    }
    $wire = ($lines | ForEach-Object { [string]$_ }) -join ''
    if ([string]::IsNullOrWhiteSpace($wire)) {
        throw 'Read-only PostgreSQL JSON export returned no row or a SQL NULL payload.'
    }
    try {
        $utf8 = [Text.UTF8Encoding]::new($false, $true)
        $json = $utf8.GetString([Convert]::FromBase64String($wire))
    } catch {
        throw 'Read-only PostgreSQL JSON export returned invalid base64 or UTF-8.'
    }
    try {
        $value = ConvertFrom-Json -InputObject $json -Depth 100 -NoEnumerate -ErrorAction Stop
    } catch {
        throw 'Read-only PostgreSQL JSON export returned invalid JSON.'
    }
    if ($null -eq $value) { throw 'Read-only PostgreSQL JSON export returned JSON null.' }
    return ,$value
}

function Get-Sb12FrozenContext {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$DockerPath,
        [Parameter(Mandatory)][string]$Container,
        [Parameter(Mandatory)][string]$Database,
        [string]$Username = 'sb12',
        [Parameter(Mandatory)][string]$RunId
    )

    $runGuid = [guid]::Empty
    if (-not [guid]::TryParse($RunId, [ref]$runGuid)) { throw 'Frozen-context run ID must be a GUID.' }
    $sql = @"
SELECT json_build_object(
    'mode', mode, 'targetShotCount', target_shot_count, 'instructions', instructions,
    'sourceSnapshot', json_build_object(
        'scriptId', source_script_id::text, 'scriptRevision', source_script_revision,
        'sourceSceneId', source_scene_id, 'sceneNo', source_scene_no, 'heading', source_heading,
        'sceneText', source_scene_text, 'sceneHash', source_scene_hash))
FROM storyboard_run_contexts WHERE run_id = '$($runGuid.ToString('D'))'
"@
    Invoke-Sb12PostgresJson -DockerPath $DockerPath -Container $Container -Database $Database -Username $Username -SelectSql $sql
}
