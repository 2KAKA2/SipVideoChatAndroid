param(
    [string]$StorageRoot = "D:\SipVideoChatMediaRelay",
    [int]$Port = 6061,
    [switch]$Foreground
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$javaFile = Join-Path $scriptDir "PcMediaRelayServer.java"
$classFile = Join-Path $scriptDir "PcMediaRelayServer.class"
$stdoutLog = Join-Path $scriptDir "PcMediaRelayServer.out.log"
$stderrLog = Join-Path $scriptDir "PcMediaRelayServer.err.log"

if (!(Test-Path $StorageRoot)) {
    New-Item -Path $StorageRoot -ItemType Directory -Force | Out-Null
}

if (!(Test-Path $classFile) -or (Get-Item $classFile).LastWriteTimeUtc -lt (Get-Item $javaFile).LastWriteTimeUtc) {
    & javac $javaFile
    if ($LASTEXITCODE -ne 0) {
        throw "javac compile failed"
    }
}

$javaExe = (Get-Command java).Source
$arguments = @("-cp", $scriptDir, "PcMediaRelayServer", $Port.ToString(), $StorageRoot)

if ($Foreground) {
    & $javaExe @arguments
    exit $LASTEXITCODE
}

Start-Process -FilePath $javaExe `
    -ArgumentList $arguments `
    -WorkingDirectory $scriptDir `
    -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog `
    -WindowStyle Hidden | Out-Null

Write-Output "PC media relay started on port $Port"
Write-Output "Storage root: $StorageRoot"
