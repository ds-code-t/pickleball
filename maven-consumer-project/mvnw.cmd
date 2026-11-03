<# : batch portion
@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM    http://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM ----------------------------------------------------------------------------
@REM ----------------------------------------------------------------------------
@REM Apache Maven Wrapper startup batch script, version 3.3.4
@REM
@REM Optional ENV vars
@REM   MVNW_REPOURL - repo url base for downloading maven distribution
@REM   MVNW_USERNAME/MVNW_PASSWORD - user and password for downloading maven
@REM   MVNW_VERBOSE - true: enable verbose log; others: silence the output
@REM ----------------------------------------------------------------------------
@IF "%__MVNW_ARG0_NAME__%"=="" (SET __MVNW_ARG0_NAME__=%~nx0)
@SET __MVNW_CMD__=
@SET __MVNW_ERROR__=
@SET __MVNW_PSMODULEP_SAVE=%PSModulePath%
@SET PSModulePath=
@FOR /F "usebackq tokens=1* delims==" %%A IN (`powershell -noprofile "& {$scriptDir='%~dp0'; $script='%__MVNW_ARG0_NAME__%'; icm -ScriptBlock ([Scriptblock]::Create((Get-Content -Raw '%~f0'))) -NoNewScope}"`) DO @(
  IF "%%A"=="MVN_CMD" (set __MVNW_CMD__=%%B) ELSE IF "%%A"=="MAVEN_HOME" (set "MAVEN_HOME=%%B") ELSE IF "%%B"=="" (echo %%A) ELSE (echo %%A=%%B)
)
@SET PSModulePath=%__MVNW_PSMODULEP_SAVE%
@SET __MVNW_PSMODULEP_SAVE=
@SET __MVNW_ARG0_NAME__=
@SET MVNW_USERNAME=
@SET MVNW_PASSWORD=
@IF "%__MVNW_CMD__%"=="" goto pkbNoMaven
@REM Apache mvn.cmd maps every JVM status greater than 0 to exit 1.
@REM Launch the same classworlds JVM the Unix mvn script execs, and return that status.
@call :pkbMaven %*
@REM PowerShell starts this file through cmd.exe and does not use CALL.
@REM A top-level "exit /b N" is then reported as 1 for every non-zero JVM
@REM status (consumer retest of 111dea5). "exit N" ends that cmd.exe with N,
@REM which PowerShell reads as $LASTEXITCODE. The subroutine still uses
@REM "exit /b" so a nested CALL returns. Linux mvnw execs the JVM directly.
@set "PKB_RC=%ERRORLEVEL%"
@exit %PKB_RC%

:pkbNoMaven
@echo Cannot start maven from wrapper >&2
@exit /b 1

:pkbMaven
@setlocal
@if not "%JAVA_HOME%"=="" goto pkbJavaHome
@for %%i in (java.exe) do @set "JAVACMD=%%~$PATH:i"
@goto pkbCheckJava
:pkbJavaHome
@set "JAVACMD=%JAVA_HOME%\bin\java.exe"
:pkbCheckJava
@if exist "%JAVACMD%" goto pkbJavaOk
@echo The JAVA_HOME environment variable is not defined correctly. >&2
@endlocal & exit /b 1
:pkbJavaOk
@if not "%MAVEN_HOME%"=="" goto pkbHomeOk
@set "PKB_MVN_CMD=%__MVNW_CMD__:/=\%"
@for %%I in ("%PKB_MVN_CMD%") do @set "PKB_MVN_DIR=%%~dpI"
@for %%I in ("%PKB_MVN_DIR%..") do @set "MAVEN_HOME=%%~fI"
:pkbHomeOk
@set "MAVEN_HOME=%MAVEN_HOME:/=\%"
@set "CLASSWORLDS_JAR="
@for %%i in ("%MAVEN_HOME%"\boot\plexus-classworlds-*) do @set "CLASSWORLDS_JAR=%%~i"
@if exist "%CLASSWORLDS_JAR%" goto pkbCpOk
@echo Cannot start maven from wrapper >&2
@endlocal & exit /b 1
:pkbCpOk
@set "MAVEN_PROJECTBASEDIR=%CD%"
@if not "%MAVEN_BASEDIR%"=="" set "MAVEN_PROJECTBASEDIR=%MAVEN_BASEDIR%"
@if not exist "%MAVEN_PROJECTBASEDIR%\.mvn" call :pkbFindBase
@if "_%MAVEN_PROJECTBASEDIR:~-1%"=="_\" set "MAVEN_PROJECTBASEDIR=%MAVEN_PROJECTBASEDIR%\"
@set "JVM_CONFIG_MAVEN_PROPS="
@if not exist "%MAVEN_PROJECTBASEDIR%\.mvn\jvm.config" goto pkbNoJvmConfig
@setlocal EnableExtensions EnableDelayedExpansion
@for /F "usebackq delims=" %%a in ("%MAVEN_PROJECTBASEDIR%\.mvn\jvm.config") do @set JVM_CONFIG_MAVEN_PROPS=!JVM_CONFIG_MAVEN_PROPS! %%a
@endlocal & set "JVM_CONFIG_MAVEN_PROPS=%JVM_CONFIG_MAVEN_PROPS%"
:pkbNoJvmConfig
@set "MAVEN_CMD_LINE_ARGS=%*"
@if not "%MAVEN_SKIP_RC%"=="" goto pkbSkipRcPre
@if exist "%USERPROFILE%\mavenrc_pre.bat" call "%USERPROFILE%\mavenrc_pre.bat" %*
@if exist "%USERPROFILE%\mavenrc_pre.cmd" call "%USERPROFILE%\mavenrc_pre.cmd" %*
:pkbSkipRcPre
@set "INTERNAL_MAVEN_OPTS="
@"%JAVACMD%" --enable-native-access=ALL-UNNAMED -version >nul 2>&1
@if ERRORLEVEL 1 goto pkbSkipNative
@set "INTERNAL_MAVEN_OPTS=--enable-native-access=ALL-UNNAMED"
:pkbSkipNative
@"%JAVACMD%" %JVM_CONFIG_MAVEN_PROPS% %INTERNAL_MAVEN_OPTS% %MAVEN_OPTS% %MAVEN_DEBUG_OPTS% -classpath "%CLASSWORLDS_JAR%" "-Dclassworlds.conf=%MAVEN_HOME%\bin\m2.conf" "-Dmaven.home=%MAVEN_HOME%" "-Dlibrary.jansi.path=%MAVEN_HOME%\lib\jansi-native" "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" org.codehaus.plexus.classworlds.launcher.Launcher %MAVEN_ARGS% %*
@set "PKB_EXIT=%ERRORLEVEL%"
@if not "%MAVEN_SKIP_RC%"=="" goto pkbSkipRcPost
@if exist "%USERPROFILE%\mavenrc_post.bat" call "%USERPROFILE%\mavenrc_post.bat"
@if exist "%USERPROFILE%\mavenrc_post.cmd" call "%USERPROFILE%\mavenrc_post.cmd"
:pkbSkipRcPost
@endlocal & exit /b %PKB_EXIT%

:pkbFindBase
@set "WDIR=%CD%"
:pkbBaseLoop
@if exist "%WDIR%\.mvn" goto pkbBaseFound
@for %%I in ("%WDIR%\..") do @set "NEXT=%%~fI"
@if "%NEXT%"=="%WDIR%" goto pkbBaseMiss
@set "WDIR=%NEXT%"
@goto pkbBaseLoop
:pkbBaseFound
@set "MAVEN_PROJECTBASEDIR=%WDIR%"
:pkbBaseMiss
@exit /b 0

@GOTO :EOF
: end batch / begin powershell #>
$ErrorActionPreference = "Stop"
if ($env:MVNW_VERBOSE -eq "true") {
  $VerbosePreference = "Continue"
}
# calculate distributionUrl, requires .mvn/wrapper/maven-wrapper.properties
$distributionUrl = (Get-Content -Raw "$scriptDir/.mvn/wrapper/maven-wrapper.properties" | ConvertFrom-StringData).distributionUrl
if (!$distributionUrl) {
  Write-Error "cannot read distributionUrl property in $scriptDir/.mvn/wrapper/maven-wrapper.properties"
}
switch -wildcard -casesensitive ( $($distributionUrl -replace '^.*/','') ) {
  "maven-mvnd-*" {
    $USE_MVND = $true
    $distributionUrl = $distributionUrl -replace '-bin\.[^.]*$',"-windows-amd64.zip"
    $MVN_CMD = "mvnd.cmd"
    break
  }
  default {
    $USE_MVND = $false
    $MVN_CMD = $script -replace '^mvnw','mvn'
    break
  }
}
# apply MVNW_REPOURL and calculate MAVEN_HOME
# maven home pattern: ~/.m2/wrapper/dists/{apache-maven-<version>,maven-mvnd-<version>-<platform>}/<hash>
if ($env:MVNW_REPOURL) {
  $MVNW_REPO_PATTERN = if ($USE_MVND -eq $False) { "/org/apache/maven/" } else { "/maven/mvnd/" }
  $distributionUrl = "$env:MVNW_REPOURL$MVNW_REPO_PATTERN$($distributionUrl -replace "^.*$MVNW_REPO_PATTERN",'')"
}
$distributionUrlName = $distributionUrl -replace '^.*/',''
$distributionUrlNameMain = $distributionUrlName -replace '\.[^.]*$','' -replace '-bin$',''
$MAVEN_M2_PATH = "$HOME/.m2"
if ($env:MAVEN_USER_HOME) {
  $MAVEN_M2_PATH = "$env:MAVEN_USER_HOME"
}

if (-not (Test-Path -Path $MAVEN_M2_PATH)) {
    New-Item -Path $MAVEN_M2_PATH -ItemType Directory | Out-Null
}

$MAVEN_WRAPPER_DISTS = $null
if ((Get-Item $MAVEN_M2_PATH).Target[0] -eq $null) {
  $MAVEN_WRAPPER_DISTS = "$MAVEN_M2_PATH/wrapper/dists"
} else {
  $MAVEN_WRAPPER_DISTS = (Get-Item $MAVEN_M2_PATH).Target[0] + "/wrapper/dists"
}
$MAVEN_HOME_PARENT = "$MAVEN_WRAPPER_DISTS/$distributionUrlNameMain"
$MAVEN_HOME_NAME = ([System.Security.Cryptography.SHA256]::Create().ComputeHash([byte[]][char[]]$distributionUrl) | ForEach-Object {$_.ToString("x2")}) -join ''
$MAVEN_HOME = "$MAVEN_HOME_PARENT/$MAVEN_HOME_NAME"

if (Test-Path -Path "$MAVEN_HOME" -PathType Container) {
  Write-Verbose "found existing MAVEN_HOME at $MAVEN_HOME"
  Write-Output "MVN_CMD=$MAVEN_HOME/bin/$MVN_CMD"
  Write-Output "MAVEN_HOME=$MAVEN_HOME"
  exit $?
}
if (! $distributionUrlNameMain -or ($distributionUrlName -eq $distributionUrlNameMain)) {
  Write-Error "distributionUrl is not valid, must end with *-bin.zip, but found $distributionUrl"
}
# prepare tmp dir
$TMP_DOWNLOAD_DIR_HOLDER = New-TemporaryFile
$TMP_DOWNLOAD_DIR = New-Item -Itemtype Directory -Path "$TMP_DOWNLOAD_DIR_HOLDER.dir"
$TMP_DOWNLOAD_DIR_HOLDER.Delete() | Out-Null
trap {
  if ($TMP_DOWNLOAD_DIR.Exists) {
    try { Remove-Item $TMP_DOWNLOAD_DIR -Recurse -Force | Out-Null }
    catch { Write-Warning "Cannot remove $TMP_DOWNLOAD_DIR" }
  }
}

New-Item -Itemtype Directory -Path "$MAVEN_HOME_PARENT" -Force | Out-Null
# Download and Install Apache Maven
Write-Verbose "Couldn't find MAVEN_HOME, downloading and installing it ..."
Write-Verbose "Downloading from: $distributionUrl"
Write-Verbose "Downloading to: $TMP_DOWNLOAD_DIR/$distributionUrlName"
$webclient = New-Object System.Net.WebClient
if ($env:MVNW_USERNAME -and $env:MVNW_PASSWORD) {
  $webclient.Credentials = New-Object System.Net.NetworkCredential($env:MVNW_USERNAME, $env:MVNW_PASSWORD)
}
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$webclient.DownloadFile($distributionUrl, "$TMP_DOWNLOAD_DIR/$distributionUrlName") | Out-Null
# If specified, validate the SHA-256 sum of the Maven distribution zip file
$distributionSha256Sum = (Get-Content -Raw "$scriptDir/.mvn/wrapper/maven-wrapper.properties" | ConvertFrom-StringData).distributionSha256Sum
if ($distributionSha256Sum) {
  if ($USE_MVND) {
    Write-Error "Checksum validation is not supported for maven-mvnd. `nPlease disable validation by removing 'distributionSha256Sum' from your maven-wrapper.properties."
  }
  Import-Module $PSHOME\Modules\Microsoft.PowerShell.Utility -Function Get-FileHash
  if ((Get-FileHash "$TMP_DOWNLOAD_DIR/$distributionUrlName" -Algorithm SHA256).Hash.ToLower() -ne $distributionSha256Sum) {
    Write-Error "Error: Failed to validate Maven distribution SHA-256, your Maven distribution might be compromised. If you updated your Maven version, you need to update the specified distributionSha256Sum property."
  }
}
# unzip and move
Expand-Archive "$TMP_DOWNLOAD_DIR/$distributionUrlName" -DestinationPath "$TMP_DOWNLOAD_DIR" | Out-Null

# Find the actual extracted directory name (handles snapshots where filename != directory name)
$actualDistributionDir = ""
# First try the expected directory name (for regular distributions)
$expectedPath = Join-Path "$TMP_DOWNLOAD_DIR" "$distributionUrlNameMain"
$expectedMvnPath = Join-Path "$expectedPath" "bin/$MVN_CMD"
if ((Test-Path -Path $expectedPath -PathType Container) -and (Test-Path -Path $expectedMvnPath -PathType Leaf)) {
  $actualDistributionDir = $distributionUrlNameMain
}
# If not found, search for any directory with the Maven executable (for snapshots)
if (!$actualDistributionDir) {
  Get-ChildItem -Path "$TMP_DOWNLOAD_DIR" -Directory | ForEach-Object {
    $testPath = Join-Path $_.FullName "bin/$MVN_CMD"
    if (Test-Path -Path $testPath -PathType Leaf) {
      $actualDistributionDir = $_.Name
    }
  }
}

if (!$actualDistributionDir) {
  Write-Error "Could not find Maven distribution directory in extracted archive"
}
Write-Verbose "Found extracted Maven distribution directory: $actualDistributionDir"
Rename-Item -Path "$TMP_DOWNLOAD_DIR/$actualDistributionDir" -NewName $MAVEN_HOME_NAME | Out-Null
try {
  Move-Item -Path "$TMP_DOWNLOAD_DIR/$MAVEN_HOME_NAME" -Destination $MAVEN_HOME_PARENT | Out-Null
} catch {
  if (! (Test-Path -Path "$MAVEN_HOME" -PathType Container)) {
    Write-Error "fail to move MAVEN_HOME"
  }
} finally {
  try { Remove-Item $TMP_DOWNLOAD_DIR -Recurse -Force | Out-Null }
  catch { Write-Warning "Cannot remove $TMP_DOWNLOAD_DIR" }
}
Write-Output "MVN_CMD=$MAVEN_HOME/bin/$MVN_CMD"
Write-Output "MAVEN_HOME=$MAVEN_HOME"
