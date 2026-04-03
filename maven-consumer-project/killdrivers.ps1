$names = 'chromedriver','msedgedriver'
$procs = Get-Process $names -ErrorAction SilentlyContinue

if ($procs) {
    $killed = $procs.ProcessName | Sort-Object -Unique
    $procs | Stop-Process -Force
    Write-Host ("Killed: " + ($killed -join ', '))
} else {
    Write-Host "No matching driver processes found."
}