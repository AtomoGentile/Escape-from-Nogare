#Requires -Version 5.1
#Requires -RunAsAdministrator
$ErrorActionPreference = 'Stop'
$relayCaddy = Get-Command caddy.exe -CommandType Application -ErrorAction Stop
$relayCaddyPath = $relayCaddy.Source
$relayCaddyFile = Get-Item -LiteralPath $relayCaddyPath
if ($relayCaddyFile.LinkType -eq 'SymbolicLink' -and $relayCaddyFile.Target) {
    $relayTarget = @($relayCaddyFile.Target)[0]
    if (-not [System.IO.Path]::IsPathRooted($relayTarget)) {
        $relayTarget = Join-Path $relayCaddyFile.DirectoryName $relayTarget
    }
    $relayCaddyPath = (Resolve-Path -LiteralPath $relayTarget).Path
}
foreach ($relayPort in @(80, 443)) {
    $relayRuleName = 'NogarRelay-Caddy-TCP-' + $relayPort
    if (Get-NetFirewallRule -Name $relayRuleName -ErrorAction SilentlyContinue) {
        Write-Host "Regola gia presente: $relayRuleName. Nessuna modifica."
    } else {
        New-NetFirewallRule -Name $relayRuleName -DisplayName "Nogar Relay HTTPS - TCP $relayPort" -Direction Inbound -Action Allow -Protocol TCP -LocalPort $relayPort -Program $relayCaddyPath -Profile Private,Public | Out-Null
        Write-Host "Regola creata per Caddy: TCP $relayPort"
    }
}
Write-Host 'Nessuna porta Node/8080 aperta. Configura anche il port forwarding sul router.'
