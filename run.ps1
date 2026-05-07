# PDC Grid Management - Quick Start Run Script
# Builds the fat JAR, then launches 1 master plus N workers on localhost.
# Usage:  .\run.ps1
# Optional: .\run.ps1 -Workers 4 -Candidates 100000

param(
    [int]$Workers    = 4,
    [int]$Nodes      = 500,
    [int]$Edges      = 1000,
    [int]$Candidates = 100000,
    [int]$ChunkSize  = 500,
    [int]$Port       = 9090
)

Set-Location $PSScriptRoot

Write-Host ""
Write-Host "==============================================" -ForegroundColor Cyan
Write-Host "  PDC Grid Management - Build and Run" -ForegroundColor Cyan
Write-Host "==============================================" -ForegroundColor Cyan
Write-Host ""

# ── 1. Build / select output ──────────────────────────────────────────────
$JAR = Join-Path $PSScriptRoot 'target\gridmanagement-1.0-SNAPSHOT.jar'
$CLASSES = Join-Path $PSScriptRoot 'target\classes'

if (Test-Path $CLASSES) {
    Write-Host "[BUILD] Using compiled classes from target\classes." -ForegroundColor Green
    $javaBaseArgs = @('-cp', $CLASSES, 'com.gridmanagement.Main')
} elseif (Test-Path $JAR) {
    Write-Host "[BUILD] Using JAR from target\gridmanagement-1.0-SNAPSHOT.jar." -ForegroundColor Yellow
    $javaBaseArgs = @('-jar', $JAR)
} else {
    Write-Host "[ERROR] Neither target\classes nor the JAR exists. Compile the project first." -ForegroundColor Red
    exit 1
}

# 2. Launch master
$mMsg = "[RUN] Starting Master (workers={0}, nodes={1}, edges={2}, candidates={3})..." -f $Workers, $Nodes, $Edges, $Candidates
Write-Host $mMsg -ForegroundColor Yellow
$masterArgs = $javaBaseArgs + @('master', $Workers, $Nodes, $Edges, $Candidates, $ChunkSize, $Port)
$masterJob = Start-Process -FilePath 'java' -ArgumentList $masterArgs -WorkingDirectory $PSScriptRoot -PassThru

Start-Sleep -Milliseconds 1500   # give master time to open socket

# 3. Launch workers
$workerProcs = @()
for ($i = 1; $i -le $Workers; $i++) {
    Write-Host "[RUN] Starting Worker $i ..." -ForegroundColor Yellow
    $workerArgs = $javaBaseArgs + @('worker', $i, 'localhost', $Port)
    $p = Start-Process -FilePath 'java' -ArgumentList $workerArgs -WorkingDirectory $PSScriptRoot -PassThru
    $workerProcs += $p
    Start-Sleep -Milliseconds 200
}

Write-Host ""
Write-Host "All processes launched. Watch the Master window for results." -ForegroundColor Cyan

Write-Host "Waiting for the master to finish..." -ForegroundColor Gray
Wait-Process -Id $masterJob.Id

# Cleanup
$masterJob | Stop-Process -Force -ErrorAction SilentlyContinue
$workerProcs | ForEach-Object { $_ | Stop-Process -Force -ErrorAction SilentlyContinue }
Write-Host "Cleaned up." -ForegroundColor Green
