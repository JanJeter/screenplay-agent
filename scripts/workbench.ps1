[CmdletBinding()]
param(
    [ValidateSet('Start', 'Stop', 'Status')][string]$Action = 'Status',
    [ValidateSet('mock', 'live')][string]$Mode = 'mock',
    [string]$JdkHome = 'C:\Users\tcf\.jdks\ms-21.0.9',
    [string]$LiveConfigPath,
    [switch]$SkipBuild,
    [switch]$Json
)

# This launcher owns only its recorded processes and labelled Docker resources.
# Database data, credentials and live budget ledgers survive Stop and Start.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dataRoot = Join-Path $root '.local\workbench-dev'
$statePath = Join-Path $dataRoot 'state.json'
$secretsPath = Join-Path $dataRoot 'secrets.json'
$ports = @{ Postgres = 15436; Java = 18084; Gateway = 13005; Web = 5174 }
$state = $null
$docker = $null
$mutex = $null
$locked = $false

function Write-JsonFile([string]$Path, $Value) {
    $temporary = "$Path.tmp"
    [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}
function Save-State { Write-JsonFile $statePath $script:state }
function Read-DeepSeekEnvFile([string]$Path) {
    # Read one credential only. Never execute dotenv text or import unrelated
    # settings that could change the authorized budget, model or Node runtime.
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    $definitions = @([IO.File]::ReadAllLines($Path) | Where-Object { $_ -match '^\s*(?:export\s+)?DEEPSEEK_API_KEY\s*=' })
    if ($definitions.Count -ne 1) { throw 'Backend .env must define DEEPSEEK_API_KEY exactly once.' }
    $value = ($definitions[0] -replace '^\s*(?:export\s+)?DEEPSEEK_API_KEY\s*=\s*', '').Trim()
    if ($value.StartsWith('"') -or $value.StartsWith("'")) {
        $closing = $value.IndexOf($value.Substring(0, 1), 1, [StringComparison]::Ordinal)
        if ($closing -lt 1) { throw 'Backend .env credential must use a closed single-line quoted value.' }
        $tail = $value.Substring($closing + 1).Trim()
        if ($tail -and -not $tail.StartsWith('#')) { throw 'Unexpected text after the backend .env credential.' }
        $value = $value.Substring(1, $closing - 1)
    } else {
        $value = ($value -split '#', 2)[0].Trim()
    }
    if ([string]::IsNullOrWhiteSpace($value) -or $value -match '\s|[\x00-\x1f\x7f]') {
        throw 'Backend .env DEEPSEEK_API_KEY must be a non-empty single-line value without whitespace.'
    }
    return $value
}
function Get-OwnedProcess($Record) {
    if ($null -eq $Record) { return $null }
    $candidate = Get-Process -Id $Record.pid -ErrorAction SilentlyContinue
    # PowerShell 7 may deserialize ISO timestamps as DateTime; PowerShell 5 keeps strings.
    $recordedStart = ([datetime]$Record.startedAt).ToUniversalTime()
    if ($candidate -and $candidate.StartTime.ToUniversalTime().Ticks -eq $recordedStart.Ticks -and $candidate.Path -eq $Record.executable) {
        return $candidate
    }
    return $null
}
function Get-Container {
    if (-not $script:state.containerId) { return $null }
    $raw = & $script:docker container ls -a --no-trunc --filter "id=$($script:state.containerId)" --format '{{.ID}}'
    if ($LASTEXITCODE -ne 0) { throw 'Docker is unavailable. Start Docker Desktop and retry.' }
    if (-not $raw) { throw 'The recorded development database container is missing; restore it from its retained volume before starting.' }
    $container = @((& $script:docker inspect $script:state.containerId | ConvertFrom-Json))[0]
    if ($LASTEXITCODE -ne 0 -or $container.Config.Labels.'screenplay.workbench.owner' -ne $script:state.owner) {
        throw 'Database ownership does not match; no container will be changed.'
    }
    return $container
}
function Test-Http([string]$Url) {
    try { return (Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 2).StatusCode -eq 200 }
    catch { return $false }
}
function Wait-Http([string]$Url, [string]$Name) {
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        if (Test-Http $Url) { return }
        if (-not (Get-OwnedProcess $script:state.processes.$Name)) { throw "$Name exited. See $($script:state.logDirectory)." }
        Start-Sleep -Milliseconds 500
    }
    throw "$Name did not become healthy. See $($script:state.logDirectory)."
}
function Assert-PortFree([int]$Port) {
    if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
        throw "Port $Port is occupied. Its process was not stopped; free this development port before retrying."
    }
}
function Start-Owned([string]$Name, [string]$Executable, [string[]]$Arguments, [string]$WorkingDirectory, [hashtable]$Environment) {
    $previous = @{}
    try {
        foreach ($key in $Environment.Keys) {
            $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
            [Environment]::SetEnvironmentVariable($key, $Environment[$key], 'Process')
        }
        # Arguments contain only launcher-controlled flags and local paths, never credentials.
        $quoted = @($Arguments | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' })
        $log = Join-Path $script:state.logDirectory "$Name.log"
        $process = Start-Process -FilePath $Executable -ArgumentList $quoted -WorkingDirectory $WorkingDirectory -WindowStyle Hidden -PassThru -RedirectStandardOutput $log -RedirectStandardError "$log.err"
        $script:state.processes.$Name = [pscustomobject]@{
            pid = $process.Id; startedAt = $process.StartTime.ToUniversalTime().ToString('o'); executable = $Executable
        }
        Save-State
    } finally {
        foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
    }
}
function Stop-OwnedTree($Record) {
    $process = Get-OwnedProcess $Record
    if (-not $process) { return }
    # Include runtime helpers (for example esbuild), but verify each PID again before stopping it.
    $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($process.Id)" -ErrorAction SilentlyContinue)
    foreach ($child in $children) {
        $childProcess = Get-Process -Id $child.ProcessId -ErrorAction SilentlyContinue
        if ($childProcess -and $childProcess.StartTime -ge $process.StartTime) {
            Stop-OwnedTree ([pscustomobject]@{ pid = $childProcess.Id; startedAt = $childProcess.StartTime.ToUniversalTime().ToString('o'); executable = $childProcess.Path })
        }
    }
    if (Get-OwnedProcess $Record) { Stop-Process -Id $Record.pid -Force -ErrorAction Stop }
}
function Stop-Services {
    foreach ($name in @('web', 'gateway', 'java')) {
        Stop-OwnedTree $script:state.processes.$name
        $script:state.processes.$name = $null
        Save-State
    }
    $container = Get-Container
    if ($container -and $container.State.Running) {
        & $script:docker stop --time 30 $script:state.containerId | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL did not stop cleanly. Development data was retained.' }
    }
}
function Show-Status {
    if (-not $script:state) {
        if ($Json) { [pscustomobject]@{ state = 'not-initialized'; webUrl = 'http://127.0.0.1:5174' } | ConvertTo-Json }
        else { Write-Host 'Workbench has not been started. Run .\scripts\workbench.ps1 -Action Start.' }
        return
    }
    $container = Get-Container
    $services = foreach ($entry in @(
        @{ name = 'gateway'; url = "http://127.0.0.1:$($ports.Gateway)/health" },
        @{ name = 'java'; url = "http://127.0.0.1:$($ports.Java)/actuator/health" },
        @{ name = 'web'; url = "http://127.0.0.1:$($ports.Web)/" }
    )) {
        $process = Get-OwnedProcess $script:state.processes.($entry.name)
        [pscustomobject]@{ service = $entry.name; owned = [bool]$process; pid = if ($process) { $process.Id } else { $null }; healthy = [bool]($process -and (Test-Http $entry.url)); url = $entry.url }
    }
    $result = [pscustomobject]@{
        mode = $script:state.mode; services = @($services); postgresRunning = [bool]($container -and $container.State.Running)
        webUrl = "http://127.0.0.1:$($ports.Web)/"; javaUrl = "http://127.0.0.1:$($ports.Java)"
        dataDirectory = $dataRoot; databaseVolume = $script:state.volumeName; logs = $script:state.logDirectory
        activityId = $script:state.activityId; gatewaySourceSha256 = $script:state.gatewaySourceSha256
    }
    if ($Json) { $result | ConvertTo-Json -Depth 8 }
    else {
        $result.services | Format-Table -AutoSize | Out-Host
        Write-Host "Mode: $($result.mode); PostgreSQL running: $($result.postgresRunning)"
        Write-Host "Workbench: $($result.webUrl)"
        Write-Host "Data: $dataRoot ; Docker volume: $($result.databaseVolume)"
        Write-Host "Logs: $($result.logs)"
        if ($result.activityId) { Write-Host "Activity: $($result.activityId)" }
    }
}

try {
    # Serialize launch/stop attempts without leaving stale lock files after a crash.
    $rootHash = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($root)).Replace('\', '_').Replace('/', '_').Replace('=', '')
    $mutex = [Threading.Mutex]::new($false, "Local\ScreenplayWorkbench-$rootHash")
    try { $locked = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true }
    if (-not $locked) { throw 'Another workbench command is running. Retry after it finishes.' }
    if (Test-Path -LiteralPath $statePath) { $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json }
    if ($Action -eq 'Status' -and -not $state) { Show-Status; return }
    if ($Action -eq 'Stop' -and -not $state) { Write-Host 'Workbench is already stopped; no resources were changed.'; return }
    $docker = (Get-Command docker.exe -ErrorAction Stop).Source
    & $docker info --format '{{.ServerVersion}}' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop before managing the workbench.' }
    if ($Action -eq 'Status') { Show-Status; return }
    if ($Action -eq 'Stop') { Stop-Services; Write-Host 'Workbench stopped. Database, files and budget ledger are retained.'; return }
    if ($state) {
        $active = @('web', 'java', 'gateway') | Where-Object { Get-OwnedProcess $state.processes.$_ }
        if ($active.Count -gt 0) {
            if ($active.Count -ne 3 -or $state.mode -ne $Mode) { throw 'The workbench is partly running or uses a different mode. Stop it before starting again.' }
            foreach ($entry in @(@('web', $ports.Web, '/'), @('gateway', $ports.Gateway, '/health'), @('java', $ports.Java, '/actuator/health'))) {
                if (-not (Test-Http "http://127.0.0.1:$($entry[1])$($entry[2])")) { throw "Existing $($entry[0]) is unhealthy. Inspect its logs, then Stop and Start." }
            }
            Write-Host 'Workbench is already running; no process or data was replaced.'
            Show-Status
            return
        }
    }
    $java = Join-Path $JdkHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $java)) { throw 'JDK 21 is required. Supply -JdkHome with its installation directory.' }
    $javaVersion = (& $java --version | Select-Object -First 1)
    if ($javaVersion -notmatch '^(openjdk|java) 21[\s\.]') { throw 'The selected Java is not JDK 21.' }
    $node = (Get-Command node.exe -ErrorAction Stop).Source
    $nodeVersion = [version]((& $node --version).TrimStart('v'))
    if ($nodeVersion -lt [version]'22.19.0') { throw 'Node.js 22.19 or newer is required.' }
    $tsxLoader = Join-Path $root 'pi-agent\node_modules\tsx\dist\loader.mjs'
    $vite = Join-Path $root 'apps\storyboard-web\node_modules\vite\bin\vite.js'
    foreach ($dependency in @($tsxLoader, $vite)) {
        if (-not (Test-Path -LiteralPath $dependency)) { throw 'Dependencies are missing. Run npm ci in pi-agent and apps/storyboard-web.' }
    }
    foreach ($port in @($ports.Java, $ports.Gateway, $ports.Web)) { Assert-PortFree $port }
    $container = if ($state) { Get-Container } else { $null }
    if (-not ($container -and $container.State.Running)) { Assert-PortFree $ports.Postgres }
    $liveEnvironment = @{}
    if ($Mode -eq 'live') {
        if (-not $LiveConfigPath) { throw 'Live mode requires -LiveConfigPath with the explicitly agreed activity budget and pricing.' }
        $liveConfig = Get-Content -LiteralPath $LiveConfigPath -Raw | ConvertFrom-Json
        $allowed = @('AGENT_ACTIVITY_ID', 'AGENT_BUSINESS_RUN_LIMIT', 'AGENT_BUDGET_USD', 'AGENT_INPUT_TOKEN_RESERVE', 'AGENT_PROVIDER_INPUT_OVERHEAD_TOKENS', 'PI_PROVIDER', 'PI_MODEL', 'PI_INPUT_USD_PER_MTOK', 'PI_OUTPUT_USD_PER_MTOK', 'PI_CACHE_READ_USD_PER_MTOK', 'PI_CACHE_WRITE_USD_PER_MTOK', 'PI_CACHE_WRITE_1H_USD_PER_MTOK', 'PI_PRICING_SOURCE')
        foreach ($property in $liveConfig.PSObject.Properties) {
            if ($property.Name -notin $allowed) { throw "Unsupported live setting: $($property.Name). Credentials belong in the process environment or the backend .env, not the budget configuration." }
            $liveEnvironment[$property.Name] = [string]$property.Value
        }
        foreach ($required in @('AGENT_ACTIVITY_ID', 'AGENT_BUSINESS_RUN_LIMIT', 'AGENT_BUDGET_USD', 'AGENT_INPUT_TOKEN_RESERVE', 'AGENT_PROVIDER_INPUT_OVERHEAD_TOKENS', 'PI_PROVIDER', 'PI_MODEL', 'PI_INPUT_USD_PER_MTOK', 'PI_OUTPUT_USD_PER_MTOK', 'PI_CACHE_READ_USD_PER_MTOK', 'PI_CACHE_WRITE_USD_PER_MTOK', 'PI_CACHE_WRITE_1H_USD_PER_MTOK', 'PI_PRICING_SOURCE')) {
            if (-not $liveEnvironment[$required]) { throw "Missing live setting: $required. No budget defaults are assumed." }
        }
        if ($liveEnvironment.AGENT_ACTIVITY_ID -notmatch '^[a-zA-Z0-9][a-zA-Z0-9_-]{2,79}$' -or $liveEnvironment.AGENT_ACTIVITY_ID -match '^sb[-_]?12') { throw 'Use a new workbench activity ID; historical SB12 activities are not allowed.' }
        if ($liveEnvironment.PI_PROVIDER -ne 'deepseek' -or $liveEnvironment.PI_MODEL -ne 'deepseek-flash') { throw 'This workbench uses the existing DeepSeek official deepseek-flash provider.' }
        $credential = $env:DEEPSEEK_API_KEY
        if ([string]::IsNullOrWhiteSpace($credential)) { $credential = Read-DeepSeekEnvFile (Join-Path $root 'examples\pi-agent-gateway\.env') }
        if ([string]::IsNullOrWhiteSpace($credential)) { throw 'Set DEEPSEEK_API_KEY in the current process or examples/pi-agent-gateway/.env before starting live mode.' }
        $liveEnvironment.DEEPSEEK_API_KEY = $credential
        $credential = $null
        $liveEnvironment.AGENT_BUDGET_PROFILE = 'workbench'
        $liveEnvironment.AGENT_BUDGET_LEDGER_PATH = Join-Path $dataRoot "budget\$($liveEnvironment.AGENT_ACTIVITY_ID).json"
        # Ask the Gateway's own validator before starting services; it never calls a provider.
        $preflightEnvironment = $liveEnvironment.Clone()
        $preflightEnvironment.AGENT_MODE = 'live'
        $preflightEnvironment.JAVA_MODE = 'http'
        $preflightEnvironment.JAVA_BASE_URL = "http://127.0.0.1:$($ports.Java)"
        $preflightEnvironment.AGENT_GATEWAY_TOKEN = 'configuration-preflight-only-token'
        $preflightEnvironment.TSX_TSCONFIG_PATH = Join-Path $root 'examples\pi-agent-gateway\tsconfig.json'
        $previous = @{}
        try {
            foreach ($key in $preflightEnvironment.Keys) {
                $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
                [Environment]::SetEnvironmentVariable($key, $preflightEnvironment[$key], 'Process')
            }
            $preflightText = & $node --import ([uri]$tsxLoader).AbsoluteUri (Join-Path $root 'examples\pi-agent-gateway\scripts\preflight.ts')
            if ($LASTEXITCODE -ne 0) { throw 'Gateway live configuration preflight failed; no services were started.' }
            $preflight = ($preflightText -join "`n") | ConvertFrom-Json
            if (-not ($preflight.readyForLiveRun -or $preflight.readyForSb12LiveRun)) {
                $invalidNames = @($preflight.requiredConfiguration | ForEach-Object { $_.variable }) -join ', '
                throw "Gateway live configuration rejected: $invalidNames. No services were started."
            }
        } finally {
            foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
        }
    }
    $jar = Join-Path $root 'target\saas-backend-starter-0.0.1-SNAPSHOT.jar'
    if (-not $SkipBuild) {
        $oldJavaHome = $env:JAVA_HOME
        try {
            $env:JAVA_HOME = $JdkHome
            Push-Location $root
            try { & (Join-Path $root 'mvnw.cmd') -q '-DskipTests' package } finally { Pop-Location }
            if ($LASTEXITCODE -ne 0) { throw 'Java package failed.' }
        } finally { $env:JAVA_HOME = $oldJavaHome }
    }
    if (-not (Test-Path -LiteralPath $jar)) { throw 'The Java application jar is missing. Retry without -SkipBuild.' }
    $null = New-Item -ItemType Directory -Path $dataRoot -Force
    if (-not $state) {
        $owner = [guid]::NewGuid().ToString('N')
        $state = [pscustomobject]@{
            owner = $owner; containerId = $null; containerName = "screenplay-workbench-dev-$($owner.Substring(0, 8))"
            volumeName = "screenplay-workbench-dev-$($owner.Substring(0, 8))-postgres"; mode = $Mode
            processes = [pscustomobject]@{ gateway = $null; java = $null; web = $null }
            logDirectory = $null; activityId = $null; gatewaySourceSha256 = $null
        }
        Save-State
    }
    if (-not (Test-Path -LiteralPath $secretsPath)) {
        if ($state.containerId) { throw 'Development credentials are missing. Restore secrets.json; existing database credentials will not be overwritten.' }
        Write-JsonFile $secretsPath ([ordered]@{ databasePassword = [guid]::NewGuid().ToString('N'); gatewayToken = [guid]::NewGuid().ToString('N'); executionSecret = [guid]::NewGuid().ToString('N'); jwtSecret = ([guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')) })
    }
    $secrets = Get-Content -LiteralPath $secretsPath -Raw | ConvertFrom-Json
    $state.mode = $Mode
    $state.activityId = if ($Mode -eq 'live') { $liveEnvironment.AGENT_ACTIVITY_ID } else { $null }
    $state.logDirectory = Join-Path $dataRoot ("logs\" + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
    $null = New-Item -ItemType Directory -Path $state.logDirectory -Force
    $hashes = [ordered]@{}
    foreach ($file in @('server.ts', 'runtime.ts', 'profiles.ts', 'storyboard-schema.ts', 'storyboard-tools.ts', 'budget.ts', 'budget-ledger.ts', 'live-provider.ts')) { $hashes[$file] = (Get-FileHash -LiteralPath (Join-Path $root "examples\pi-agent-gateway\src\$file") -Algorithm SHA256).Hash }
    $state.gatewaySourceSha256 = $hashes
    Save-State
    try {
        if (-not $state.containerId) {
            & $docker volume create --label "screenplay.workbench.owner=$($state.owner)" $state.volumeName | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Development database volume creation failed.' }
            $oldPgPassword = $env:POSTGRES_PASSWORD
            try {
                $env:POSTGRES_PASSWORD = $secrets.databasePassword
                $id = & $docker create --name $state.containerName --label "screenplay.workbench.owner=$($state.owner)" --mount "type=volume,src=$($state.volumeName),dst=/var/lib/postgresql/data" -e POSTGRES_DB=screenplay_workbench -e POSTGRES_USER=workbench -e POSTGRES_PASSWORD -p "127.0.0.1:$($ports.Postgres):5432" postgres:16-alpine
                if ($LASTEXITCODE -ne 0) { throw 'Development PostgreSQL container creation failed.' }
                $state.containerId = "$id".Trim()
                Save-State
            } finally { $env:POSTGRES_PASSWORD = $oldPgPassword }
        }
        $container = Get-Container
        if (-not $container.State.Running) {
            & $docker start $state.containerId | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Development PostgreSQL start failed.' }
        }
        $ready = $false
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            & $docker exec $state.containerId pg_isready -U workbench -d screenplay_workbench *> $null
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
            Start-Sleep -Milliseconds 500
        }
        if (-not $ready) { throw 'Development PostgreSQL did not become healthy.' }
        $gatewayEnvironment = @{ PORT = "$($ports.Gateway)"; AGENT_GATEWAY_TOKEN = $secrets.gatewayToken; AGENT_MODE = $Mode; JAVA_MODE = 'http'; JAVA_BASE_URL = "http://127.0.0.1:$($ports.Java)"; TSX_TSCONFIG_PATH = (Join-Path $root 'examples\pi-agent-gateway\tsconfig.json') }
        foreach ($key in $liveEnvironment.Keys) { $gatewayEnvironment[$key] = $liveEnvironment[$key] }
        Start-Owned 'gateway' $node @('--import', ([uri]$tsxLoader).AbsoluteUri, 'src/server.ts') (Join-Path $root 'examples\pi-agent-gateway') $gatewayEnvironment
        Wait-Http "http://127.0.0.1:$($ports.Gateway)/health" 'gateway'
        Start-Owned 'java' $java @('-jar', $jar) $root @{
            SERVER_ADDRESS = '127.0.0.1'; SERVER_PORT = "$($ports.Java)"
            SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:$($ports.Postgres)/screenplay_workbench"; SPRING_DATASOURCE_USERNAME = 'workbench'; SPRING_DATASOURCE_PASSWORD = $secrets.databasePassword
            AGENT_GATEWAY_URL = "http://127.0.0.1:$($ports.Gateway)"; AGENT_GATEWAY_TOKEN = $secrets.gatewayToken; AGENT_EXECUTION_SECRET = $secrets.executionSecret; JWT_SECRET = $secrets.jwtSecret; SPRING_JPA_SHOW_SQL = 'false'
            AUTH_MAIL_MODE = 'local'; AUTH_LOCAL_MAIL_DIR = (Join-Path $dataRoot 'mail'); AUTH_FRONTEND_BASE_URL = "http://127.0.0.1:$($ports.Web)"
            AUTH_REGISTRATION_ORGANIZATION_SLUG = 'demo-org'; AUTH_DEMO_SEED_ENABLED = 'true'
        }
        Wait-Http "http://127.0.0.1:$($ports.Java)/actuator/health" 'java'
        Start-Owned 'web' $node @($vite, '--host', '127.0.0.1', '--port', "$($ports.Web)", '--strictPort') (Join-Path $root 'apps\storyboard-web') @{
            VITE_STORYBOARD_DATA_SOURCE = 'java'; VITE_JAVA_API_BASE = "http://127.0.0.1:$($ports.Java)"; VITE_AGENT_MODE = $Mode
        }
        Wait-Http "http://127.0.0.1:$($ports.Web)/" 'web'
    } catch {
        $failure = $_
        try { Stop-Services } catch { Write-Warning 'Cleanup was incomplete; run Status and inspect development logs.' }
        throw $failure
    }
    Show-Status
} finally {
    if ($locked) { $mutex.ReleaseMutex() }
    if ($mutex) { $mutex.Dispose() }
}
