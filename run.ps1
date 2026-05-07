# PDC Grid Management - Build and Run Script
# Compiles the project then launches 1 master + N workers on localhost.
#
# Usage:  .\run.ps1
# Options: .\run.ps1 -Workers 4 -Candidates 100000 -Baseline

param(
    [int]$Workers    = 4,
    [int]$Nodes      = 500,
    [int]$Edges      = 1000,
    [int]$Candidates = 100000,
    [int]$ChunkSize  = 500,
    [int]$Port       = 9090,
    [switch]$Baseline,
    [string]$GridFile = ""
)

Set-Location $PSScriptRoot
$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "==============================================" -ForegroundColor Cyan
Write-Host "  PDC Grid Management - Build and Run" -ForegroundColor Cyan
Write-Host "==============================================" -ForegroundColor Cyan
Write-Host ""

# ── 1. Build ───────────────────────────────────────────────────────────────────
Write-Host "[BUILD] Compiling sources with javac..." -ForegroundColor Yellow
New-Item -ItemType Directory -Force -Path "target\classes" | Out-Null
$sources = Get-ChildItem -Recurse -Filter "*.java" src\main\java | Select-Object -ExpandProperty FullName
javac -d target\classes -sourcepath src\main\java $sources
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Compilation failed. Check the errors above." -ForegroundColor Red
    exit 1
}
Write-Host "[BUILD] Build successful." -ForegroundColor Green
Write-Host ""

# ── 2. Locate compiled classes ─────────────────────────────────────────────────
$CLASSES = Join-Path $PSScriptRoot 'target\classes'
if (-not (Test-Path $CLASSES)) {
    Write-Host "[ERROR] target\classes not found after build. Something went wrong." -ForegroundColor Red
    exit 1
}
# Wrap in quotes to handle spaces in the path (e.g. '6th semester')
$javaBaseArgs = @('-cp', "`"$CLASSES`"", 'com.gridmanagement.Main')

# ── 3. Prepare log files ───────────────────────────────────────────────────────
$masterLog = Join-Path $PSScriptRoot 'master.log'
$null = New-Item -Path $masterLog -ItemType File -Force

# ── 4. Launch Master ───────────────────────────────────────────────────────────
$mMsg = "[RUN] Starting Master  workers={0}  nodes={1}  edges={2}  candidates={3}  port={4}" `
        -f $Workers, $Nodes, $Edges, $Candidates, $Port
Write-Host $mMsg -ForegroundColor Yellow

$masterExtraArgs = @('master', $Workers, $Nodes, $Edges, $Candidates, $ChunkSize, $Port)
if ($Baseline)  { $masterExtraArgs += '--baseline' }
if ($GridFile)  {
    $absGrid = Resolve-Path $GridFile -ErrorAction Stop
    $masterExtraArgs += '--grid-file'
    $masterExtraArgs += "`"$absGrid`""
    Write-Host "[RUN] Using custom grid: $absGrid" -ForegroundColor Magenta
}

$masterArgs = $javaBaseArgs + $masterExtraArgs

$masterJob = Start-Process `
    -FilePath 'java' `
    -ArgumentList $masterArgs `
    -WorkingDirectory $PSScriptRoot `
    -RedirectStandardOutput $masterLog `
    -RedirectStandardError  "$PSScriptRoot\master_err.log" `
    -NoNewWindow `
    -PassThru

Start-Sleep -Milliseconds 2000   # give master time to open the server socket

# ── 5. Launch Workers ──────────────────────────────────────────────────────────
$workerProcs = @()
for ($i = 1; $i -le $Workers; $i++) {
    Write-Host "[RUN] Starting Worker $i ..." -ForegroundColor Yellow
    $workerLog = Join-Path $PSScriptRoot "worker_$i.log"
    $null = New-Item -Path $workerLog -ItemType File -Force

    $workerArgs = $javaBaseArgs + @('worker', $i, 'localhost', $Port)
    $p = Start-Process `
        -FilePath 'java' `
        -ArgumentList $workerArgs `
        -WorkingDirectory $PSScriptRoot `
        -RedirectStandardOutput $workerLog `
        -RedirectStandardError  "$PSScriptRoot\worker_${i}_err.log" `
        -NoNewWindow `
        -PassThru
    $workerProcs += $p
    Start-Sleep -Milliseconds 300
}

Write-Host ""
Write-Host "[RUN] All processes launched. Waiting for master to finish..." -ForegroundColor Cyan
Write-Host "      (Master log: master.log | Worker logs: worker_N.log)" -ForegroundColor Gray
Write-Host ""

# ── 6. Tail master log while waiting ──────────────────────────────────────────
$lastLine = 0
while (-not $masterJob.HasExited) {
    Start-Sleep -Milliseconds 500
    $lines = Get-Content $masterLog -ErrorAction SilentlyContinue
    if ($lines -and $lines.Count -gt $lastLine) {
        $lines[$lastLine..($lines.Count - 1)] | ForEach-Object { Write-Host $_ }
        $lastLine = $lines.Count
    }
}

# Flush any remaining lines
Start-Sleep -Milliseconds 200
$lines = Get-Content $masterLog -ErrorAction SilentlyContinue
if ($lines -and $lines.Count -gt $lastLine) {
    $lines[$lastLine..($lines.Count - 1)] | ForEach-Object { Write-Host $_ }
}

# ── 7. Show errors if any ──────────────────────────────────────────────────────
$errLog = "$PSScriptRoot\master_err.log"
if ((Test-Path $errLog) -and (Get-Item $errLog).Length -gt 0) {
    Write-Host ""
    Write-Host "[MASTER STDERR]" -ForegroundColor Red
    Get-Content $errLog | ForEach-Object { Write-Host $_ -ForegroundColor Red }
}

# ── 8. Cleanup workers ─────────────────────────────────────────────────────────
$workerProcs | ForEach-Object {
    $_.WaitForExit(5000) | Out-Null
    $_ | Stop-Process -Force -ErrorAction SilentlyContinue
}

# ── 9. Show results.csv ────────────────────────────────────────────────────────
Write-Host ""
$csv = Join-Path $PSScriptRoot 'results.csv'
if (Test-Path $csv) {
    Write-Host "==== results.csv ====" -ForegroundColor Green
    Get-Content $csv | ForEach-Object { Write-Host $_ -ForegroundColor White }
} else {
    Write-Host "[WARN] results.csv was not created. Check master.log for errors." -ForegroundColor Yellow
}
Write-Host ""
Write-Host "[DONE] Run complete." -ForegroundColor Green
