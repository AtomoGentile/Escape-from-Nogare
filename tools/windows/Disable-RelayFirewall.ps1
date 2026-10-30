#Requires -Version 5.1
#Requires -RunAsAdministrator
$ErrorActionPreference = 'Stop'
foreach ($relayPort in @(80, 443)) {
    $relayRuleName = 'NogarRelay-Caddy-TCP-' + $relayPort
    if (Get-NetFirewallRule -Name $relayRuleName -ErrorAction SilentlyContinue) {
        Remove-NetFirewallRule -Name $relayRuleName
        Write-Host "Regola rimossa: $relayRuleName"
    }
}
Write-Host 'Rimosse solo le regole NogarRelay. Il firewall Windows rimane attivo.'
