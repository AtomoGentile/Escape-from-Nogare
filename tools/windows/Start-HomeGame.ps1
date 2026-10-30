#Requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$PublicIp,
    [ValidateRange(1024, 65535)]
    [int]$LocalPort = 8080
)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'Start-HomeRelay.ps1') -PublicIp $PublicIp -LocalPort $LocalPort -Mode Game
