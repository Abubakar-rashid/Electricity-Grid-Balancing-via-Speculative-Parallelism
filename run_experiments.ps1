<#
Automate experiments by running `run.ps1` for multiple worker counts
and candidate sizes. Each run appends to `results.csv`.

Usage: .\run_experiments.ps1
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

# Parse comma-separated worker and candidate lists
$WorkersList = @($WorkersStr -split ',' | ForEach-Object { [int]$_ })
$CandidatesList = @($CandidatesStr -split ',' | ForEach-Object { [int]$_ })

# Backup existing results.csv if present
if (Test-Path results.csv) {
    $ts = Get-Date -Format "yyyyMMdd-HHmmss"
    Copy-Item results.csv "results.$ts.bak.csv"
    Write-Host "Backed up existing results.csv to results.$ts.bak.csv" -ForegroundColor Yellow
    Remove-Item results.csv -Force
}

# Write header
"Workers,Candidates,T_seq(ms),T_par(ms),Speedup,Efficiency,ParallelFraction,Correctness" | Out-File -FilePath results.csv -Encoding ascii
Write-Host "Starting experiments ($($WorkersList.Count) worker counts x $($CandidatesList.Count) sizes)..." -ForegroundColor Green

$totalRuns = $WorkersList.Count * $CandidatesList.Count
$runCount = 0

foreach ($c in $CandidatesList) {
    foreach ($w in $WorkersList) {
        $runCount++
        Write-Host "[$runCount/$totalRuns] Running: workers=$w candidates=$c" -ForegroundColor Cyan
        
        # Run the launcher; it appends to results.csv and waits for completion
        & powershell -NoProfile -ExecutionPolicy Bypass -File .\run.ps1 `
            -Workers $w -Candidates $c -Nodes $Nodes -Edges $Edges -ChunkSize $ChunkSize -Port $Port
        
        if ($LASTEXITCODE -ne 0) {
            Write-Host "Warning: run.ps1 exited with code $LASTEXITCODE" -ForegroundColor Yellow
        }
        
        Start-Sleep -Seconds 1
    }
}

Write-Host ""
Write-Host "All experiments completed. Results saved in results.csv" -ForegroundColor Green
Write-Host "Next: run 'python plot_results.py' to generate speedup/efficiency plots." -ForegroundColor Cyan
