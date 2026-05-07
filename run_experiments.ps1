# PDC Grid Management - Automated Experiment Runner
# Runs multiple (workers x candidates) combinations and accumulates results.csv
#
# Usage:  .\run_experiments.ps1
# Output: results.csv in project root

param(
    [string]$WorkersStr    = "1,2,4",
    [string]$CandidatesStr = "1000,5000,10000,100000",
    [int]$Nodes      = 500,
    [int]$Edges      = 1000,
    [int]$ChunkSize  = 100,
    [int]$Port       = 9090
)

Set-Location $PSScriptRoot
$ErrorActionPreference = "Stop"

# ── Build once ─────────────────────────────────────────────────────────────────
Write-Host "[BUILD] Compiling with javac..." -ForegroundColor Yellow
New-Item -ItemType Directory -Force -Path "target\classes" | Out-Null
$sources = Get-ChildItem -Recurse -Filter "*.java" src\main\java | Select-Object -ExpandProperty FullName
javac -d target\classes -sourcepath src\main\java $sources
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Compilation failed. Check the errors above." -ForegroundColor Red
    exit 1
}
Write-Host "[BUILD] Build successful." -ForegroundColor Green

$CLASSES = Join-Path $PSScriptRoot 'target\classes'
# Quoted path to handle spaces (e.g. '6th semester')
$Q = "`"$CLASSES`""

$WorkersList     = @($WorkersStr    -split ',' | ForEach-Object { [int]$_ })
$CandidatesList  = @($CandidatesStr -split ',' | ForEach-Object { [int]$_ })

# Backup + clear existing results
if (Test-Path results.csv) {
    $ts = Get-Date -Format "yyyyMMdd-HHmmss"
    Copy-Item results.csv "results.$ts.bak.csv"
    Write-Host "[INFO] Backed up existing results.csv -> results.$ts.bak.csv" -ForegroundColor Yellow
    Remove-Item results.csv -Force
}

$totalRuns = $WorkersList.Count * $CandidatesList.Count
$runCount  = 0

Write-Host "Starting $totalRuns experiment run(s)..." -ForegroundColor Green
Write-Host ""

foreach ($c in $CandidatesList) {
    foreach ($w in $WorkersList) {
        $runCount++
        Write-Host "──────────────────────────────────────────" -ForegroundColor DarkCyan
        Write-Host "[$runCount/$totalRuns]  workers=$w  candidates=$c" -ForegroundColor Cyan
        Write-Host "──────────────────────────────────────────" -ForegroundColor DarkCyan

        # Per-run log files
        $masterLog = Join-Path $PSScriptRoot "master.log"
        $null      = New-Item -Path $masterLog -ItemType File -Force

        # Launch master
        $masterArgs = @('-cp', $Q, 'com.gridmanagement.Main',
                        'master', $w, $Nodes, $Edges, $c, $ChunkSize, $Port)
        $masterJob = Start-Process `
            -FilePath 'java' `
            -ArgumentList $masterArgs `
            -WorkingDirectory $PSScriptRoot `
            -RedirectStandardOutput $masterLog `
            -RedirectStandardError  "$PSScriptRoot\master_err.log" `
            -NoNewWindow `
            -PassThru

        Start-Sleep -Milliseconds 2000

        # Launch workers
        $workerProcs = @()
        for ($i = 1; $i -le $w; $i++) {
            $wLog = Join-Path $PSScriptRoot "worker_$i.log"
            $null = New-Item -Path $wLog -ItemType File -Force
            $workerArgs = @('-cp', $Q, 'com.gridmanagement.Main',
                            'worker', $i, 'localhost', $Port)
            $p = Start-Process `
                -FilePath 'java' `
                -ArgumentList $workerArgs `
                -WorkingDirectory $PSScriptRoot `
                -RedirectStandardOutput $wLog `
                -RedirectStandardError  "$PSScriptRoot\worker_${i}_err.log" `
                -NoNewWindow `
                -PassThru
            $workerProcs += $p
            Start-Sleep -Milliseconds 300
        }

        # Tail master log
        $lastLine = 0
        while (-not $masterJob.HasExited) {
            Start-Sleep -Milliseconds 500
            $lines = Get-Content $masterLog -ErrorAction SilentlyContinue
            if ($lines -and $lines.Count -gt $lastLine) {
                $lines[$lastLine..($lines.Count - 1)] | ForEach-Object { Write-Host "  $_" }
                $lastLine = $lines.Count
            }
        }
        # Flush remaining
        Start-Sleep -Milliseconds 200
        $lines = Get-Content $masterLog -ErrorAction SilentlyContinue
        if ($lines -and $lines.Count -gt $lastLine) {
            $lines[$lastLine..($lines.Count - 1)] | ForEach-Object { Write-Host "  $_" }
        }

        # Wait for workers
        $workerProcs | ForEach-Object {
            $_.WaitForExit(10000) | Out-Null
            $_ | Stop-Process -Force -ErrorAction SilentlyContinue
        }

        Start-Sleep -Seconds 1
        Write-Host ""
    }
}

Write-Host ""
Write-Host "All $totalRuns runs complete." -ForegroundColor Green
Write-Host ""
if (Test-Path results.csv) {
    Write-Host "==== results.csv ====" -ForegroundColor Green
    Get-Content results.csv | ForEach-Object { Write-Host $_ }
    Write-Host ""
    Write-Host "Run: python plot_results.py  to generate speedup/efficiency charts." -ForegroundColor Cyan
} else {
    Write-Host "[WARN] results.csv was not created. Check master.log for errors." -ForegroundColor Yellow
}
