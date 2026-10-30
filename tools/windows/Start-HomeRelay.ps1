#Requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$PublicIp,
    [ValidateRange(1024, 65535)]
    [int]$LocalPort = 8080,
    [ValidateSet("Relay", "Game")]
    [string]$Mode = "Relay"
)

$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Questo script richiede Windows 11/Windows PowerShell 5.1 o successivo.' }

$relayAddress = $null
if (-not [System.Net.IPAddress]::TryParse($PublicIp, [ref]$relayAddress) -or
    $relayAddress.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) {
    throw 'Inserisci l IPv4 WAN mostrato dal router, senza http, porte o spazi.'
}
$relayBytes = $relayAddress.GetAddressBytes()
if ($relayBytes[0] -in @(0, 10, 127) -or $relayBytes[0] -ge 224 -or
    ($relayBytes[0] -eq 192 -and $relayBytes[1] -eq 168) -or
    ($relayBytes[0] -eq 172 -and $relayBytes[1] -ge 16 -and $relayBytes[1] -le 31) -or
    ($relayBytes[0] -eq 100 -and $relayBytes[1] -ge 64 -and $relayBytes[1] -le 127) -or
    ($relayBytes[0] -eq 169 -and $relayBytes[1] -eq 254)) {
    throw 'L indirizzo inserito e privato o non utilizzabile. Serve l IP WAN pubblico del router.'
}
$relayNode = Get-Command node.exe -CommandType Application -ErrorAction SilentlyContinue
$relayCaddy = Get-Command caddy.exe -CommandType Application -ErrorAction SilentlyContinue
if (-not $relayNode) { throw 'Installa Node: winget install --id OpenJS.NodeJS.LTS --exact --source winget; poi riapri PowerShell.' }
if (-not $relayCaddy) { throw 'Installa Caddy: winget install --id CaddyServer.Caddy --exact --source winget; poi riapri PowerShell.' }
$relayNodeVersion = & $relayNode.Source --version
if ([int](($relayNodeVersion -replace '^v', '').Split('.')[0]) -lt 22) { throw 'Serve Node.js 22 o successivo.' }

$relayRepo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$relayFile = if ($Mode -eq 'Game') { 'server\server.mjs' } else { 'server\relay.mjs' }
$relayService = if ($Mode -eq 'Game') { 'nogar-pc' } else { 'nogar-relay' }
$relayProtocol = if ($Mode -eq 'Game') { 3 } else { 2 }
$relayEntry = Join-Path $relayRepo $relayFile
if (-not (Test-Path $relayEntry)) { throw "Manca $relayFile. Estrai tutto il repository prima di avviare lo script." }
foreach ($relayPort in @(80, 443, $LocalPort)) {
    if (Get-NetTCPConnection -LocalPort $relayPort -State Listen -ErrorAction SilentlyContinue) {
        throw "Porta $relayPort gia occupata. Chiudi il servizio che la usa oppure scegli un altro PC; lo script non termina processi esistenti."
    }
}

$relayDomain = $relayAddress.ToString().Replace('.', '-') + '.sslip.io'
$relayUrl = 'https://' + $relayDomain
$relayStateName = if ($Mode -eq 'Game') { 'NogarGameServer' } else { 'NogarRelay' }
$relayState = Join-Path $env:LOCALAPPDATA $relayStateName
New-Item -ItemType Directory -Path $relayState -Force | Out-Null
$relayConfigPath = Join-Path $relayState 'Caddyfile.generated'
if (Test-Path -LiteralPath $relayConfigPath) {
    $relayBackupPath = $relayConfigPath + '.' + [DateTime]::UtcNow.Ticks + '.bak'
    Copy-Item -LiteralPath $relayConfigPath -Destination $relayBackupPath
}
$relayConfig = @"
{
    admin off
}
$relayDomain {
    reverse_proxy 127.0.0.1:$LocalPort
}
"@
[System.IO.File]::WriteAllText($relayConfigPath, $relayConfig, (New-Object System.Text.UTF8Encoding($false)))
& $relayCaddy.Source fmt --overwrite $relayConfigPath
if ($LASTEXITCODE -ne 0) { throw 'Impossibile formattare la configurazione Caddy.' }
& $relayCaddy.Source adapt --config $relayConfigPath --adapter caddyfile 1> $null
if ($LASTEXITCODE -ne 0) { throw 'Configurazione Caddy non valida.' }

$relayNodeProcess = $null
$relayCaddyProcess = $null
$relayPreviousHostValue = $env:HOST
$relayPreviousPortValue = $env:PORT
try {
    $env:HOST = '127.0.0.1'
    $env:PORT = [string]$LocalPort
    $relayNodeProcess = Start-Process -FilePath $relayNode.Source -ArgumentList @(('"' + $relayEntry + '"')) -WorkingDirectory $relayRepo -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $relayState 'node.log') -RedirectStandardError (Join-Path $relayState 'node-error.log')
    $relayReady = $false
    for ($relayAttempt = 0; $relayAttempt -lt 30; $relayAttempt++) {
        if ($relayNodeProcess.HasExited) { throw "Node si e fermato. Leggi $relayState\node-error.log" }
        try {
            $relayHealth = Invoke-RestMethod -Uri "http://127.0.0.1:$LocalPort/health" -TimeoutSec 2
            if ($relayHealth.ok -and $relayHealth.service -eq $relayService -and $relayHealth.protocol -eq $relayProtocol) { $relayReady = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 300
    }
    if (-not $relayReady) { throw 'Il relay locale non ha superato il controllo /health.' }

    $relayCaddyProcess = Start-Process -FilePath $relayCaddy.Source -ArgumentList @('run', '--config', ('"' + $relayConfigPath + '"'), '--adapter', 'caddyfile') -WorkingDirectory $relayRepo -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $relayState 'caddy.log') -RedirectStandardError (Join-Path $relayState 'caddy-error.log')
    Write-Host ''
    Write-Host "$relayService locale pronto. Caddy sta attivando HTTPS."
    Write-Host "URL da verificare con i DATI MOBILI: $relayUrl/health"
    Write-Host "URL da inserire nell app dopo la verifica: $relayUrl"
    Write-Host 'Apri sul router TCP 80 e 443 verso questo PC e abilita le regole firewall indicate nella guida.'
    Write-Host "Log locali: $relayState"
    Write-Host 'Tieni questa finestra aperta e il PC acceso. Premi CTRL+C per fermare i processi avviati qui.'
    Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway } | ForEach-Object {
        foreach ($relayLanAddress in $_.IPv4Address) { Write-Host ('Possibile IP LAN del PC: ' + $relayLanAddress.IPAddress + ' (' + $_.InterfaceAlias + ')') }
    }
    while ($true) {
        if ($relayNodeProcess.HasExited) { throw "Relay terminato. Leggi $relayState\node-error.log" }
        if ($relayCaddyProcess.HasExited) { throw "HTTPS terminato. Leggi $relayState\caddy-error.log" }
        Start-Sleep -Seconds 1
    }
} finally {
    foreach ($relayOwnedProcess in @($relayCaddyProcess, $relayNodeProcess)) {
        if ($null -ne $relayOwnedProcess) {
            try { if (-not $relayOwnedProcess.HasExited) { $relayOwnedProcess.Kill() } } catch { }
        }
    }
    $env:HOST = $relayPreviousHostValue
    $env:PORT = $relayPreviousPortValue
}
