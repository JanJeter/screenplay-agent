#requires -Version 7.2
[CmdletBinding()]
param(
    [string]$Container = 'screenplay-sb12-01a-postgres',
    [string]$Database = 'screenplay_sb12_01a'
)

# NO PROVIDER REQUESTS. Uses only read-only SELECTs in an existing container.
# Does not start services, invoke either launcher, or write evidence/artifacts.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\sb12-postgres-json.ps1')
$docker = (Get-Command docker.exe -ErrorAction Stop).Source
$connection = @{ DockerPath = $docker; Container = $Container; Database = $Database }

function Assert-Rejected([scriptblock]$Action, [string]$ExpectedMessage, [string]$Label) {
    try { $null = & $Action } catch {
        if ($_.Exception.Message -notlike $ExpectedMessage) { throw "$Label failed with an unexpected error." }
        return
    }
    throw "$Label was not rejected."
}

# ASCII SQL builds Unicode inside PostgreSQL independently of the PS fixture.
# The long payload needs many of PostgreSQL encode()'s 76-column output lines.
$sql = @'
SELECT json_build_object(
    'instructions', chr(20013) || chr(25991) || ' "quotes" C:' || chr(92) || 'scene' || chr(10) || 'second line',
    'sourceSnapshot', json_build_object(
        'sceneText', repeat(chr(20013) || chr(25991), 120) || chr(10) || '"end"' || chr(92)),
    'readOnly', current_setting('transaction_read_only'))
'@
$expectedInstructions = '中文 "quotes" C:\scene' + "`nsecond line"
$expectedScene = ('中文' * 120) + "`n" + '"end"\'
$savedConsoleEncoding = [Console]::OutputEncoding
$savedOutputEncoding = $OutputEncoding
try {
    foreach ($codePage in @(65001, 936)) {
        [Console]::OutputEncoding = [Text.Encoding]::GetEncoding($codePage)
        $OutputEncoding = [Console]::OutputEncoding
        $value = Invoke-Sb12PostgresJson @connection -SelectSql $sql
        if (-not [string]::Equals($value.instructions, $expectedInstructions, [StringComparison]::Ordinal)) {
            throw "Chinese, quotes, backslash, or LF changed under code page $codePage."
        }
        if (-not [string]::Equals($value.sourceSnapshot.sceneText, $expectedScene, [StringComparison]::Ordinal)) {
            throw "Wrapped base64 payload changed or was truncated under code page $codePage."
        }
        if ($value.readOnly -ne 'on') { throw 'PostgreSQL export session is not read-only.' }
        Write-Host "PASS: complete Unicode JSON under code page $codePage; session is read-only."
    }

    Assert-Rejected { Invoke-Sb12PostgresJson @connection -SelectSql "SELECT '{}'::jsonb WHERE false" } `
        '*no row or a SQL NULL payload*' 'Missing row'
    Assert-Rejected { Invoke-Sb12PostgresJson @connection -SelectSql 'SELECT NULL::jsonb' } `
        '*no row or a SQL NULL payload*' 'SQL NULL'
    Assert-Rejected { Invoke-Sb12PostgresJson @connection -SelectSql "SELECT 'null'::jsonb" } `
        '*returned JSON null*' 'JSON null'
    Assert-Rejected { Invoke-Sb12PostgresJson @connection -SelectSql 'SELECT to_jsonb(n) FROM generate_series(1, 2) AS n' } `
        '*failed (exit code *' 'Multiple rows'
    Assert-Rejected { Invoke-Sb12PostgresJson @connection -SelectSql 'SELECT to_jsonb(1 / 0)' } `
        '*failed (exit code *' 'SQL error'
    Assert-Rejected { Invoke-Sb12PostgresJson @connection -SelectSql "SELECT 'not-json'::text" } `
        '*returned invalid JSON*' 'Invalid JSON'
    Assert-Rejected { Get-Sb12FrozenContext @connection -RunId '00000000-0000-0000-0000-000000000000' } `
        '*no row or a SQL NULL payload*' 'Missing frozen context'
    Assert-Rejected { Get-Sb12FrozenContext -DockerPath 'must-not-be-invoked' -Container $Container -Database $Database -RunId "bad-id'" } `
        '*run ID must be a GUID*' 'Invalid run GUID before Docker invocation'
    Write-Host 'PASS: missing/null/multiple rows, SQL/JSON errors, and invalid run IDs are rejected.'
} finally {
    [Console]::OutputEncoding = $savedConsoleEncoding
    $OutputEncoding = $savedOutputEncoding
}
Write-Host 'SB-12 PostgreSQL JSON regression passed. No provider requests or artifact writes.'
