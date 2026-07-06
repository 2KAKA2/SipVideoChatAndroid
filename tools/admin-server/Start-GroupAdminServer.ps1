param(
    [string]$Host = "0.0.0.0",
    [int]$Port = 8090,
    [string]$StoreFile = ".\GroupAdminStore.txt"
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

$existing = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "Group admin server already listening on port $Port"
    exit 0
}

$javaHome = "C:\Users\wxd\AppData\Roaming\.minecraft\runtime\java-runtime-delta"
$javac = Join-Path $javaHome "bin\javac.exe"
$java = Join-Path $javaHome "bin\java.exe"

if (-not (Test-Path $javac)) {
    throw "javac not found: $javac"
}

& $javac ".\GroupAdminServer.java"
if ($LASTEXITCODE -ne 0) {
    throw "javac failed"
}

& $java "-cp" "." "GroupAdminServer" $Host $Port $StoreFile
