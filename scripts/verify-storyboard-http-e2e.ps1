[CmdletBinding()]
param(
    [int]$PostgresPort = 15432,
    [int]$JavaPort = 18080,
    [int]$GatewayPort = 13001,
    [int]$WebPort = 15173
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$javaHome = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { $env:JAVA_HOME } else { 'C:\Users\tcf\.jdks\ms-21.0.9' }
if (-not (Test-Path (Join-Path $javaHome 'bin\java.exe'))) { throw 'JDK 21 is required. Set JAVA_HOME to a JDK 21 installation.' }

$runTag = "sb11-$PID"
$databaseContainer = "screenplay-$runTag-postgres"
$gatewayToken = "sb11-gateway-token-$([guid]::NewGuid().ToString('N'))"
$executionSecret = "sb11-execution-secret-$([guid]::NewGuid().ToString('N'))"
$logs = Join-Path ([System.IO.Path]::GetTempPath()) "screenplay-$runTag"
$null = New-Item -ItemType Directory -Path $logs -Force
$nodeProcess = $null
$javaProcess = $null
$viteProcess = $null

function Start-HiddenProcess([string]$WorkingDirectory, [string]$Script, [string]$LogFile) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Script))
    return Start-Process -FilePath 'powershell.exe' -WorkingDirectory $WorkingDirectory -WindowStyle Hidden -PassThru `
        -ArgumentList @('-NoProfile', '-EncodedCommand', $encoded) -RedirectStandardOutput $LogFile -RedirectStandardError "$LogFile.err"
}

function Wait-Http([string]$Url, [string]$Name) {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 2
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) { return }
        } catch { Start-Sleep -Milliseconds 500 }
    }
    throw "$Name did not become ready. See $logs"
}

try {
    $env:JAVA_HOME = $javaHome
    $env:Path = "$javaHome\bin;$env:Path"
    & (Join-Path $root 'mvnw.cmd') -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Java package failed.' }
    $jar = Get-ChildItem (Join-Path $root 'target') -Filter '*.jar' | Where-Object { $_.Name -notlike 'original-*' } | Select-Object -First 1
    if (-not $jar) { throw 'Spring Boot jar was not created.' }

    & docker run --rm --detach --name $databaseContainer -e POSTGRES_DB=screenplay_sb11 -e POSTGRES_USER=sb11 -e POSTGRES_PASSWORD=sb11 -p "${PostgresPort}:5432" postgres:16-alpine | Out-Null
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        & docker exec $databaseContainer pg_isready -U sb11 -d screenplay_sb11 | Out-Null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL did not become ready.' }

    $nodeCommand = "`$env:PORT='$GatewayPort'; `$env:AGENT_GATEWAY_TOKEN='$gatewayToken'; `$env:AGENT_MODE='mock'; `$env:JAVA_MODE='http'; `$env:JAVA_BASE_URL='http://127.0.0.1:$JavaPort'; npm run start"
    $nodeProcess = Start-HiddenProcess (Join-Path $root 'examples\pi-agent-gateway') $nodeCommand (Join-Path $logs 'gateway.log')
    Wait-Http "http://127.0.0.1:$GatewayPort/health" 'Node gateway'

    $javaCommand = "`$env:SERVER_PORT='$JavaPort'; `$env:SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:$PostgresPort/screenplay_sb11'; `$env:SPRING_DATASOURCE_USERNAME='sb11'; `$env:SPRING_DATASOURCE_PASSWORD='sb11'; `$env:AGENT_GATEWAY_URL='http://127.0.0.1:$GatewayPort'; `$env:AGENT_GATEWAY_TOKEN='$gatewayToken'; `$env:AGENT_EXECUTION_SECRET='$executionSecret'; & '$javaHome\bin\java.exe' -jar '$($jar.FullName)'"
    $javaProcess = Start-HiddenProcess $root $javaCommand (Join-Path $logs 'java.log')
    Wait-Http "http://127.0.0.1:$JavaPort/actuator/health" 'Java API'

    $viteCommand = "`$env:VITE_JAVA_API_BASE='http://127.0.0.1:$JavaPort'; npm run dev -- --host 127.0.0.1 --port $WebPort --strictPort"
    $viteProcess = Start-HiddenProcess (Join-Path $root 'apps\storyboard-web') $viteCommand (Join-Path $logs 'vite.log')
    Wait-Http "http://127.0.0.1:$WebPort" 'Vite workbench'

    $env:E2E_WEB_URL = "http://127.0.0.1:$WebPort"
    $env:E2E_JAVA_URL = "http://127.0.0.1:$JavaPort"
    $env:PLAYWRIGHT_CHROMIUM_EXECUTABLE = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
    Push-Location (Join-Path $root 'apps\storyboard-web')
    try {
        # npm/Node can print a non-fatal color warning to stderr; preserve its
        # process exit code instead of letting PowerShell convert that warning
        # into a terminating NativeCommandError.
        $previousErrorAction = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        npm run test:e2e *> (Join-Path $logs 'playwright.log')
        $playwrightExit = $LASTEXITCODE
        $ErrorActionPreference = $previousErrorAction
    } finally { Pop-Location }
    if ($playwrightExit -ne 0) { throw "Playwright workflow failed. See $logs\\playwright.log" }
    Write-Host "SB-11 HTTP E2E passed. Temporary logs: $logs"
}
finally {
    foreach ($ownedProcess in @($viteProcess, $javaProcess, $nodeProcess)) {
        if ($null -ne $ownedProcess -and -not $ownedProcess.HasExited) { & taskkill.exe /PID $ownedProcess.Id /T /F 2>$null | Out-Null }
    }
    & docker rm --force $databaseContainer 2>$null | Out-Null
}
