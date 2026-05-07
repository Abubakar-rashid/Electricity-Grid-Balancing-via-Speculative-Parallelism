<#
Run baseline (fixed granularity) experiments.
Generates results_baseline.csv with --baseline flag.
#>

param(
    [string]$WorkersStr = "1,2,4",
    [string]$CandidatesStr = "1000,5000,10000",
    [int]$Nodes = 500,
    [int]$Edges = 1000,
    [int]$ChunkSize = 100,
    [int]$Port = 9099
)

Set-Location $PSScriptRoot

$WorkersList = @($WorkersStr -split ',' | ForEach-Object { [int]$_ })
$CandidatesList = @($CandidatesStr -split ',' | ForEach-Object { [int]$_ })

if (Test-Path results_baseline.csv) {
    Remove-Item results_baseline.csv -Force
}

Write-Host "Starting BASELINE experiments (fixed chunk size)..." -ForegroundColor Green

$totalRuns = $WorkersList.Count * $CandidatesList.Count
$runCount = 0

$CLASSES = Join-Path $PSScriptRoot 'target\classes'

foreach ($c in $CandidatesList) {
    foreach ($w in $WorkersList) {
        $runCount++
        Write-Host "[$runCount/$totalRuns] BASELINE: workers=$w candidates=$c" -ForegroundColor Cyan
        
        # Clean up old results.csv before run
        if (Test-Path results.csv) {
            Remove-Item results.csv -Force
        }
        
        # Launch master with --baseline flag
        $masterArgs = @('-cp', $CLASSES, 'com.gridmanagement.Main', 'master', $w, $Nodes, $Edges, $c, $ChunkSize, $Port, '--baseline')
        $masterJob = Start-Process -FilePath 'java' -ArgumentList $masterArgs -WorkingDirectory $PSScriptRoot -PassThru

        Start-Sleep -Milliseconds 1500

        # Launch workers
        $workerProcs = @()
        for ($i = 1; $i -le $w; $i++) {
            $workerArgs = @('-cp', $CLASSES, 'com.gridmanagement.Main', 'worker', $i, 'localhost', $Port)
            $p = Start-Process -FilePath 'java' -ArgumentList $workerArgs -WorkingDirectory $PSScriptRoot -PassThru
            $workerProcs += $p
            Start-Sleep -Milliseconds 200
        }

        # Wait for master
        Wait-Process -Id $masterJob.Id
        $masterJob | Stop-Process -Force -ErrorAction SilentlyContinue
        $workerProcs | ForEach-Object { $_ | Stop-Process -Force -ErrorAction SilentlyContinue }
        
        # Append results to baseline file
        if (Test-Path results.csv) {
            if ((Test-Path results_baseline.csv) -eq $false) {
                Copy-Item results.csv results_baseline.csv
            } else {
                Get-Content results.csv | Select-Object -Skip 1 | Add-Content results_baseline.csv
            }
            Remove-Item results.csv -Force
        }
        
        Start-Sleep -Seconds 1
    }
}

Write-Host ""
Write-Host "BASELINE experiments completed. Results in results_baseline.csv" -ForegroundColor Green
