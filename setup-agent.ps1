[CmdletBinding()]
param(
    [switch]$Quick,
    [switch]$SkipTests,
    [switch]$StageGit,
    [string]$MavenCommand
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($Quick -and $SkipTests) {
    throw "Use either -Quick or -SkipTests, not both."
}

$RepositoryRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ExpectedBuildFile = Join-Path $RepositoryRoot "build.gradle"
$ExpectedConsumerProject = Join-Path $RepositoryRoot "maven-consumer-project"

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Get-PythonCommand {
    if (Get-Command python -ErrorAction SilentlyContinue) {
        return [PSCustomObject]@{
            Executable = "python"
            Prefix = @()
        }
    }

    if (Get-Command py -ErrorAction SilentlyContinue) {
        return [PSCustomObject]@{
            Executable = "py"
            Prefix = @("-3")
        }
    }

    throw "Python 3 is required. Install Python 3, then run this script again."
}

function Invoke-Python {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $commandArguments = @($script:PythonCommand.Prefix) + @($Arguments)
    & $script:PythonCommand.Executable @commandArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Python command failed: $($Arguments -join ' ')"
    }
}

function Add-GitIgnoreEntry {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Entry
    )

    $gitIgnorePath = Join-Path $RepositoryRoot ".gitignore"
    $utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)

    if (Test-Path -LiteralPath $gitIgnorePath) {
        $existingText = [System.IO.File]::ReadAllText($gitIgnorePath)
        $existingLines = $existingText -split "`r?`n"
        if ($existingLines -contains $Entry) {
            Write-Host ".gitignore already contains $Entry"
            return
        }

        $separator = if ($existingText.Length -eq 0 -or $existingText.EndsWith("`n")) { "" } else { [Environment]::NewLine }
        $updatedText = $existingText + $separator + $Entry + [Environment]::NewLine
        [System.IO.File]::WriteAllText($gitIgnorePath, $updatedText, $utf8WithoutBom)
        Write-Host "Added $Entry to .gitignore"
        return
    }

    [System.IO.File]::WriteAllText(
        $gitIgnorePath,
        $Entry + [Environment]::NewLine,
        $utf8WithoutBom
    )
    Write-Host "Created .gitignore with $Entry"
}

function Stage-AgentFiles {
    if (-not (Test-Path -LiteralPath (Join-Path $RepositoryRoot ".git"))) {
        throw "-StageGit was requested, but this directory is not a Git working tree."
    }

    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        throw "-StageGit was requested, but Git is not available on PATH."
    }

    $paths = @(
        "AGENTS.md",
        "CLAUDE.md",
        "GEMINI.md",
        "REVIEW.md",
        "setup-agent.ps1",
        ".agents",
        ".aiassistant",
        ".amazonq",
        ".claude",
        ".clinerules",
        ".continue",
        ".cursor",
        ".github",
        ".junie",
        ".windsurf",
        "docs/agent",
        "scripts",
        "maven-consumer-project/mvnw",
        "maven-consumer-project/mvnw.cmd",
        "maven-consumer-project/.mvn/wrapper/maven-wrapper.properties",
        ".gitignore"
    ) | Where-Object { Test-Path -LiteralPath (Join-Path $RepositoryRoot $_) }

    & git -C $RepositoryRoot add -- @paths
    if ($LASTEXITCODE -ne 0) {
        throw "git add failed."
    }

    Write-Host "Agent files are staged. No commit or push was performed."
}

if (-not (Test-Path -LiteralPath $ExpectedBuildFile) -or
    -not (Test-Path -LiteralPath $ExpectedConsumerProject)) {
    throw "Run setup-agent.ps1 from the Pickleball repository root. Expected build.gradle and maven-consumer-project beside this script."
}

$script:PythonCommand = Get-PythonCommand

Push-Location $RepositoryRoot
try {
    Write-Step "Preparing repository files"
    Add-GitIgnoreEntry -Entry ".agent-bootstrap-backup-*/"

    $exampleGitIgnore = Join-Path $RepositoryRoot ".gitignore.agent-bootstrap-example"
    if (Test-Path -LiteralPath $exampleGitIgnore) {
        Remove-Item -LiteralPath $exampleGitIgnore -Force
        Write-Host "Removed .gitignore.agent-bootstrap-example after merging its entry."
    }

    Write-Step "Generating the agent repository index"
    Invoke-Python -Arguments @("scripts/refresh_agent_index.py")

    Write-Step "Verifying the installed agent contract"
    Invoke-Python -Arguments @("scripts/verify_agent_contract.py")

    if ($SkipTests) {
        Write-Step "Skipping project tests as requested"
    }
    else {
        $validationArguments = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "scripts/agent_validate.ps1")
        if (-not [string]::IsNullOrWhiteSpace($MavenCommand)) {
            $validationArguments += @("-MavenCommand", $MavenCommand)
        }

        if ($Quick) {
            $validationArguments += "-Quick"
            Write-Step "Running quick project validation"
        }
        else {
            Write-Step "Running full framework and Maven consumer validation"
        }

        & powershell @validationArguments
        if ($LASTEXITCODE -ne 0) {
            throw "Project validation failed with exit code $LASTEXITCODE."
        }
    }

    if ($StageGit) {
        Write-Step "Staging agent files in Git"
        Stage-AgentFiles
    }

    Write-Step "Setup complete"
    Write-Host "The repository-native agent configuration is ready."
    Write-Host "The Maven consumer is configured to use maven-consumer-project\mvnw.cmd; no separate Maven installation is required."
    Write-Host "For JetBrains AI Assistant, set .aiassistant/rules/pickleball.md to Always in Settings > Tools > AI Assistant > Rules."

    if (-not $StageGit) {
        Write-Host "Review the files with 'git status', then commit them to share the configuration with other agents and developers."
    }
}
finally {
    Pop-Location
}
