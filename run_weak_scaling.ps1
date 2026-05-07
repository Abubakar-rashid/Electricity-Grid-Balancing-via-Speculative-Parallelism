<#
Run weak-scaling experiments where the work per worker stays constant.
Produces weak_results.csv for plot_results.py to consume.
#>

param(
    [string]$WorkersStr = "1,2,4",
    [int]$CandidatesPerWorker = 1000,
    [int]$Nodes = 500,
    [int]$Edges = 1000,
    [int]$ChunkSize = 100,
    [int]$Port = 9099
)

Set-Location $PSScriptRoot

$weakCsv = Join-Path $PSScriptRoot 'weak_results.csv'
if (Test-Path $weakCsv) {
    Remove-Item $weakCsv -Force
}

$WorkersList = @($WorkersStr -split ',' | ForEach-Object { [int]$_ })

"Workers,Candidates,T_seq(ms),T_par(ms),Speedup,Efficiency,ParallelFraction,Correctness" | Out-File -FilePath $weakCsv -Encoding ascii

foreach ($w in $WorkersList) {
    $candidates = $w * $CandidatesPerWorker
    Write-Host "Weak scaling run: workers=$w candidates=$candidates" -ForegroundColor Cyan

    if (Test-Path .\results.csv) {
        Remove-Item .\results.csv -Force
    }

    & powershell -NoProfile -ExecutionPolicy Bypass -File .\run.ps1 `
        -Workers $w -Candidates $candidates -Nodes $Nodes -Edges $Edges -ChunkSize $ChunkSize -Port $Port

    if (Test-Path .\results.csv) {
        $lastRow = Get-Content .\results.csv | Select-Object -Last 1
        Add-Content -Path $weakCsv -Value $lastRow
    }
}

Write-Host "Weak scaling results saved to weak_results.csv" -ForegroundColor Green