# PDC Grid Management - Baseline (Fixed Granularity) Experiment Runner
# Produces results_baseline.csv using the --baseline flag.
#
# Usage:  .\run_baseline_experiments.ps1

param(
    [string]$WorkersStr    = "1,2,4",
    [string]$CandidatesStr = "1000,5000,10000",
    [int]$Nodes     = 500,
    [int]$Edges     = 1000,
    [int]$ChunkSize = 100,
    [int]$Port      = 9090
)

Set-Location $PSScriptRoot
$ErrorActionPreference = "Stop"

# ── Build once ─────────────────────────────────────────────────────────────────
Write-Host "[BUILD] Compiling sources with javac..." -ForegroundColor Yellow
New-Item -ItemType Directory -Force -Path "target\classes" | Out-Null
$sources = Get-ChildItem -Recurse -Filter "*.java" src\main\java | Select-Object -ExpandProperty FullName
javac -d target\classes -sourcepath src\main\java $sources
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Compilation failed." -ForegroundColor Red; exit 1
}
Write-Host "[BUILD] Build successful." -ForegroundColor Green

$CLASSES = Join-Path $PSScriptRoot 'target\classes'
$Q = "`"$CLASSES`""

$WorkersList    = @($WorkersStr    -split ',' | ForEach-Object { [int]$_ })
$CandidatesList = @($CandidatesStr -split ',' | ForEach-Object { [int]$_ })

if (Test-Path results_baseline.csv) { Remove-Item results_baseline.csv -Force }

Write-Host "Starting BASELINE (fixed chunk) experiments..." -ForegroundColor Green

$totalRuns = $WorkersList.Count * $CandidatesList.Count
$runCount  = 0

foreach ($c in $CandidatesList) {
    foreach ($w in $WorkersList) {
        $runCount++
        Write-Host "──────────────────────────────────────────" -ForegroundColor DarkCyan
        Write-Host "[$runCount/$totalRuns] BASELINE  workers=$w  candidates=$c" -ForegroundColor Cyan
        Write-Host "──────────────────────────────────────────" -ForegroundColor DarkCyan

        # Clear per-run results
        if (Test-Path results.csv) { Remove-Item results.csv -Force }

        $masterLog = Join-Path $PSScriptRoot "master.log"
        $null      = New-Item -Path $masterLog -ItemType File -Force

        $masterArgs = @('-cp', $Q, 'com.gridmanagement.Main',
                        'master', $w, $Nodes, $Edges, $c, $ChunkSize, $Port, '--baseline')
        $masterJob = Start-Process `
            -FilePath 'java' `
            -ArgumentList $masterArgs `
            -WorkingDirectory $PSScriptRoot `
            -RedirectStandardOutput $masterLog `
            -RedirectStandardError  "$PSScriptRoot\master_err.log" `
            -NoNewWindow `
            -PassThru

        Start-Sleep -Milliseconds 2000

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

        # Tail master log live
        $lastLine = 0
        while (-not $masterJob.HasExited) {
            Start-Sleep -Milliseconds 500
            $lines = Get-Content $masterLog -ErrorAction SilentlyContinue
            if ($lines -and $lines.Count -gt $lastLine) {
                $lines[$lastLine..($lines.Count - 1)] | ForEach-Object { Write-Host "  $_" }
                $lastLine = $lines.Count
            }
        }
        Start-Sleep -Milliseconds 200
        $lines = Get-Content $masterLog -ErrorAction SilentlyContinue
        if ($lines -and $lines.Count -gt $lastLine) {
            $lines[$lastLine..($lines.Count - 1)] | ForEach-Object { Write-Host "  $_" }
        }

        $workerProcs | ForEach-Object {
            $_.WaitForExit(10000) | Out-Null
            $_ | Stop-Process -Force -ErrorAction SilentlyContinue
        }

        # Accumulate into results_baseline.csv
        if (Test-Path results.csv) {
            if (-not (Test-Path results_baseline.csv)) {
                Copy-Item results.csv results_baseline.csv
            } else {
                Get-Content results.csv | Select-Object -Skip 1 | Add-Content results_baseline.csv
            }
            Remove-Item results.csv -Force
        }

        Start-Sleep -Seconds 1
        Write-Host ""
    }
}

Write-Host ""
Write-Host "BASELINE experiments complete." -ForegroundColor Green
if (Test-Path results_baseline.csv) {
    Write-Host "==== results_baseline.csv ====" -ForegroundColor Green
    Get-Content results_baseline.csv | ForEach-Object { Write-Host $_ }
}
