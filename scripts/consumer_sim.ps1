# Windows entry for the consumer-simulation harness.
# Uses PowerShell and mvnw.cmd (the Python harness selects mvnw.cmd on Windows).
$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)
$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) { $python = Get-Command py -ErrorAction SilentlyContinue }
if (-not $python) { Write-Error "Python is required to run scripts/consumer_sim.py"; exit 2 }
& $python.Source "scripts/consumer_sim.py" @args
exit $LASTEXITCODE
