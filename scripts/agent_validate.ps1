[CmdletBinding()]
param(
    [switch]$Quick,
    [string]$MavenCommand
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$RepositoryRoot = Split-Path -Parent $PSScriptRoot

$PythonExe = $null
$PythonPrefix = @()
if (Get-Command python -ErrorAction SilentlyContinue) {
    $PythonExe = "python"
}
elseif (Get-Command py -ErrorAction SilentlyContinue) {
    $PythonExe = "py"
    $PythonPrefix = @("-3")
}
else {
    throw "Python 3 is required for agent contract validation."
}

function Invoke-Python {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    $CommandArgs = @($script:PythonPrefix) + @($Arguments)
    & $script:PythonExe @CommandArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Python command failed: $($Arguments -join ' ')"
    }
}

function Resolve-Executable {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    if (Test-Path -LiteralPath $Value -PathType Leaf) {
        return (Resolve-Path -LiteralPath $Value).Path
    }

    $command = Get-Command $Value -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $command) {
        return $command.Source
    }

    return $null
}

function Find-IntelliJBundledMaven {
    $directCandidates = New-Object System.Collections.Generic.List[string]

    foreach ($processName in @("idea64", "idea")) {
        Get-Process -Name $processName -ErrorAction SilentlyContinue | ForEach-Object {
            try {
                if ($_.Path) {
                    $ideaRoot = Split-Path -Parent (Split-Path -Parent $_.Path)
                    $directCandidates.Add((Join-Path $ideaRoot "plugins\maven\lib\maven3\bin\mvn.cmd"))
                }
            }
            catch {
                # Some process details may be inaccessible. Continue with other locations.
            }
        }
    }

    foreach ($homeVariable in @($env:MAVEN_HOME, $env:M2_HOME)) {
        if (-not [string]::IsNullOrWhiteSpace($homeVariable)) {
            $directCandidates.Add((Join-Path $homeVariable "bin\mvn.cmd"))
            $directCandidates.Add((Join-Path $homeVariable "bin\mvn"))
        }
    }

    foreach ($candidate in $directCandidates | Select-Object -Unique) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    $searchRoots = @(
        (Join-Path $env:LOCALAPPDATA "Programs"),
        (Join-Path $env:LOCALAPPDATA "JetBrains\Toolbox\apps"),
        (Join-Path $env:ProgramFiles "JetBrains")
    )

    $programFilesX86 = [Environment]::GetEnvironmentVariable("ProgramFiles(x86)")
    if (-not [string]::IsNullOrWhiteSpace($programFilesX86)) {
        $searchRoots += (Join-Path $programFilesX86 "JetBrains")
    }

    foreach ($root in $searchRoots | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Container) } | Select-Object -Unique) {
        $match = Get-ChildItem -LiteralPath $root -Filter "mvn.cmd" -File -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match '[\\/]plugins[\\/]maven[\\/]lib[\\/]maven3[\\/]bin[\\/]mvn\.cmd$' } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($null -ne $match) {
            return $match.FullName
        }
    }

    return $null
}

function Get-MavenExecutable {
    param([string]$RequestedCommand)

    if (-not [string]::IsNullOrWhiteSpace($RequestedCommand)) {
        $resolved = Resolve-Executable -Value $RequestedCommand
        if ($null -eq $resolved) {
            throw "The supplied Maven command was not found: $RequestedCommand"
        }
        return $resolved
    }

    $consumerWrapper = Join-Path $RepositoryRoot "maven-consumer-project\mvnw.cmd"
    if (Test-Path -LiteralPath $consumerWrapper -PathType Leaf) {
        return (Resolve-Path -LiteralPath $consumerWrapper).Path
    }

    foreach ($name in @("mvn.cmd", "mvn")) {
        $resolved = Resolve-Executable -Value $name
        if ($null -ne $resolved) {
            return $resolved
        }
    }

    $intellijMaven = Find-IntelliJBundledMaven
    if ($null -ne $intellijMaven) {
        return $intellijMaven
    }

    throw @"
The Maven consumer wrapper is missing, and no fallback Maven executable was found.
Expected wrapper: maven-consumer-project\mvnw.cmd
Restore the committed Maven wrapper files, then rerun validation.
"@
}

Push-Location $RepositoryRoot

try {
    Invoke-Python scripts/verify_agent_contract.py
    Invoke-Python scripts/refresh_agent_index.py --check
    Invoke-Python scripts/sync_consumer_guidance.py --check

    & .\gradlew.bat test
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    if (-not $Quick) {
        & .\gradlew.bat publishToMavenLocal
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

        $MavenExe = Get-MavenExecutable -RequestedCommand $MavenCommand
        Write-Host "Using Maven command: $MavenExe"
        & $MavenExe -f maven-consumer-project/pom.xml -U test `
            -Dpkb_runvars.pkb_browser=CHROME_HEADLESS `
            -Dpkb_runvars.pkb_tags=@all
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }

    $Mode = if ($Quick) { "quick" } else { "full" }
    Write-Host "Pickleball validation completed ($Mode mode)."
}
finally {
    Pop-Location
}
