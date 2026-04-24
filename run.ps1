# PDC Grid Management — Quick-Start Run Script
# Builds the fat-JAR then launches 1 master + 4 workers on localhost
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
Write-Host "╔══════════════════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║  PDC Grid Management  —  Build & Run                        ║" -ForegroundColor Cyan
Write-Host "╚══════════════════════════════════════════════════════════════╝" -ForegroundColor Cyan
Write-Host ""

# ── 1. Build ──────────────────────────────────────────────────────────────
$JAR = "$PSScriptRoot\target\gridmanagement-1.0-SNAPSHOT.jar"
$CLASSES = "$PSScriptRoot\target\classes"

if (-not (Test-Path $JAR)) {
    Write-Host "[BUILD] mvn clean package -q ..." -ForegroundColor Yellow
    mvn clean package -q
    if ($LASTEXITCODE -eq 0 -and (Test-Path $JAR)) {
        Write-Host "[BUILD] Success." -ForegroundColor Green
    } else {
        Write-Host "[WARN] Maven packaging unavailable; falling back to target\classes." -ForegroundColor Yellow
    }
    Write-Host ""
}

if (Test-Path $JAR) {
    $JavaBaseArgs = @("-jar", $JAR)
} elseif (Test-Path $CLASSES) {
    $JavaBaseArgs = @("-cp", $CLASSES, "com.gridmanagement.Main")
} else {
    Write-Host "[ERROR] Neither the JAR nor target\classes exists. Build the project first." -ForegroundColor Red
    exit 1
}

# ── 2. Launch Master ──────────────────────────────────────────────────────
$mMsg = "[RUN] Starting Master (workers={0}, nodes={1}, edges={2}, candidates={3})..." -f $Workers, $Nodes, $Edges, $Candidates
Write-Host $mMsg -ForegroundColor Yellow
$masterArgs = @($JavaBaseArgs + @("master", "$Workers", "$Nodes", "$Edges", "$Candidates", "$ChunkSize", "$Port"))
$masterJob = Start-Process java -ArgumentList $masterArgs -PassThru

Start-Sleep -Milliseconds 1500   # give master time to open socket

# ── 3. Launch Workers ─────────────────────────────────────────────────────
$workerProcs = @()
for ($i = 1; $i -le $Workers; $i++) {
    Write-Host "[RUN] Starting Worker $i ..." -ForegroundColor Yellow
    $workerArgs = @($JavaBaseArgs + @("worker", "$i", "localhost", "$Port"))
    $p = Start-Process java -ArgumentList $workerArgs -PassThru
    $workerProcs += $p
    Start-Sleep -Milliseconds 200
}

Write-Host ""
Write-Host "All processes launched.  Watch the Master window for results." -ForegroundColor Cyan
Write-Host "Press ENTER here to kill all processes when done." -ForegroundColor Gray
Read-Host

# ── Cleanup ───────────────────────────────────────────────────────────────
$masterJob | Stop-Process -Force -ErrorAction SilentlyContinue
$workerProcs | ForEach-Object { $_ | Stop-Process -Force -ErrorAction SilentlyContinue }
Write-Host "Cleaned up." -ForegroundColor Green
