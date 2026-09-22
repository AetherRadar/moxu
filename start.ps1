# MoXu Windows launcher. Requires Windows PowerShell 5.1+ and no Python.
# Reads exported environment variables; does not automatically load .env files.
$ErrorActionPreference = 'Stop'
$rootDir = $PSScriptRoot
$backendProcess = $null
$frontendProcess = $null
$exitCode = 0
$savedEnvironment = @{}
$frontendVariables = @('VITE_DEV_PORT', 'VITE_BACKEND_PORT', 'VITE_BACKEND_URL', 'VITE_BACKEND_WS_URL')

function Resolve-Setting([string[]] $Names, [string] $Fallback) {
    foreach ($name in $Names) {
        $value = [Environment]::GetEnvironmentVariable($name, 'Process')
        if (-not [string]::IsNullOrWhiteSpace($value)) { return $value }
    }
    return $Fallback
}

function Assert-FreePorts([int[]] $Ports) {
    if ($Ports[0] -eq $Ports[1]) { throw 'Backend and frontend ports must be distinct.' }
    foreach ($port in $Ports) {
        if ($port -lt 1 -or $port -gt 65535) { throw 'Ports must be between 1 and 65535.' }
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $port)
        $listener.Server.ExclusiveAddressUse = $true
        try { $listener.Start() }
        catch { throw "Port $port is unavailable. Existing services were left running." }
        finally { $listener.Stop() }
    }
}

function Invoke-LoggedNative([string] $Command, [string[]] $Arguments, [string] $LogPath) {
    # Windows PowerShell 5.1 can turn native stderr warnings into error records.
    # Judge the build by its exit code, while keeping its output in the private log.
    $ErrorActionPreference = 'Continue'
    & $Command @Arguments *> $LogPath
    if ($LASTEXITCODE -ne 0) { throw "Command failed: $Command. Inspect $LogPath" }
}

function Wait-Ready($Process, [string] $Url, [string] $Kind) {
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) { throw "$Kind exited. Inspect logs in $runDir" }
        try {
            $request = [System.Net.WebRequest]::Create($Url)
            $request.Proxy = $null
            $request.Timeout = 2000
            $response = $request.GetResponse()
            try {
                if ($Kind -eq 'backend') {
                    $reader = [System.IO.StreamReader]::new($response.GetResponseStream())
                    try { $health = $reader.ReadToEnd() | ConvertFrom-Json }
                    finally { $reader.Dispose() }
                    $ready = $health.status -eq 'ok' -and $health.application -eq 'moxu-backend'
                } else { $ready = [int]$response.StatusCode -eq 200 }
            } finally { $response.Dispose() }
            if ($ready -and -not $Process.HasExited) { return }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw "$Kind readiness check timed out. Inspect logs in $runDir"
}

try {
    foreach ($tool in @('java', 'mvn.cmd', 'node', 'npm.cmd')) {
        if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
            throw "Required tool missing: $tool (JDK 21+, Maven 3.9+, Node.js 18+)."
        }
    }
    $javaBin = (Get-Command java).Source
    if ($env:JAVA_HOME) { $javaBin = Join-Path $env:JAVA_HOME 'bin/java.exe' }
    if (-not (Test-Path $javaBin)) { throw 'JAVA_HOME does not contain bin/java.exe.' }
    $backendPort = [int](Resolve-Setting @('MOXU_BACKEND_PORT', 'WENSHAPE_BACKEND_PORT') '8080')
    $frontendPort = [int](Resolve-Setting @('MOXU_FRONTEND_PORT', 'WENSHAPE_FRONTEND_PORT') '3000')
    $dataDir = Resolve-Setting @('MOXU_DATA_DIR', 'WENSHAPE_DATA_DIR') (Join-Path $rootDir 'data')
    if (-not [System.IO.Path]::IsPathRooted($dataDir)) { $dataDir = Join-Path $rootDir $dataDir }
    $dataDir = [System.IO.Path]::GetFullPath($dataDir)
    Assert-FreePorts @($backendPort, $frontendPort)
    $runDir = Join-Path $rootDir ('.moxu-runtime/run.' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $runDir -Force | Out-Null
    Write-Host "[MoXu] Build and service logs: $runDir"
    Write-Host '[MoXu] Building Java backend...'
    Invoke-LoggedNative 'mvn.cmd' @('-B', '-f', (Join-Path $rootDir 'backend-java/pom.xml'), "-Dmoxu.build.directory=$runDir/backend", '-DskipTests', 'package') (Join-Path $runDir 'build.log')
    $frontendDir = Join-Path $rootDir 'web'
    $vitePath = Join-Path $frontendDir 'node_modules/vite/bin/vite.js'
    if (-not (Test-Path $vitePath)) {
        Push-Location $frontendDir
        try {
            Invoke-LoggedNative 'npm.cmd' @('ci') (Join-Path $runDir 'npm.log')
        } finally { Pop-Location }
    }
    Assert-FreePorts @($backendPort, $frontendPort)
    New-Item -ItemType Directory -Path $dataDir -Force | Out-Null
    $jarPath = Join-Path $runDir 'backend/moxu-backend.jar'
    $backendArgs = @('-jar', ('"' + $jarPath + '"'), '--server.address=127.0.0.1', "--server.port=$backendPort", ('"--wenshape.data-dir=' + $dataDir + '"'))
    $backendProcess = Start-Process -FilePath $javaBin -ArgumentList $backendArgs -WorkingDirectory $rootDir -NoNewWindow -PassThru -RedirectStandardOutput (Join-Path $runDir 'backend.log') -RedirectStandardError (Join-Path $runDir 'backend-error.log')
    Wait-Ready $backendProcess "http://127.0.0.1:$backendPort/health" 'backend'
    foreach ($name in $frontendVariables) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    $env:VITE_DEV_PORT = "$frontendPort"
    $env:VITE_BACKEND_PORT = "$backendPort"
    $env:VITE_BACKEND_URL = "http://127.0.0.1:$backendPort"
    $env:VITE_BACKEND_WS_URL = "ws://127.0.0.1:$backendPort"
    $frontendArgs = @(('"' + $vitePath + '"'), '--host', '127.0.0.1', '--port', "$frontendPort", '--strictPort')
    $frontendProcess = Start-Process -FilePath (Get-Command node).Source -ArgumentList $frontendArgs -WorkingDirectory $frontendDir -NoNewWindow -PassThru -RedirectStandardOutput (Join-Path $runDir 'frontend.log') -RedirectStandardError (Join-Path $runDir 'frontend-error.log')
    Wait-Ready $frontendProcess "http://127.0.0.1:$frontendPort/" 'frontend'
    Write-Host "[MoXu] Ready: http://127.0.0.1:$frontendPort"
    Write-Host "[MoXu] Health: http://127.0.0.1:$backendPort/health"
    Write-Host "[MoXu] Data: $dataDir"
    Write-Host '[MoXu] Press Ctrl+C to stop only the services started by this launch.'
    while (-not $backendProcess.HasExited -and -not $frontendProcess.HasExited) { Start-Sleep -Seconds 1 }
    throw "A service exited. Inspect logs in $runDir"
} catch {
    Write-Host "[MoXu] $($_.Exception.Message)" -ForegroundColor Red
    $exitCode = 1
} finally {
    foreach ($process in @($frontendProcess, $backendProcess)) {
        if ($null -ne $process -and -not $process.HasExited) {
            # Only the captured child process and its descendants, never a port/name lookup.
            try { & taskkill.exe /PID $process.Id /T /F *> $null } catch { }
        }
    }
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
}
exit $exitCode
