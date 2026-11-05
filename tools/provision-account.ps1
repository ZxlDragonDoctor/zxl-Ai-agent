[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Subject,
    [Parameter(Mandatory=$true)][string]$LoginName,
    [Parameter(Mandatory=$true)][string]$Tenant,
    [Parameter(Mandatory=$true)][ValidateSet('employee','knowledge-maintainer')][string]$Role,
    [string]$MavenRepository
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$classpathFile = Join-Path $projectRoot 'target/provision-account-classpath.txt'
Push-Location -LiteralPath $projectRoot
try {
    $buildArgs = @('-q','-DskipTests','compile','dependency:build-classpath','-DincludeScope=runtime',"-Dmdep.outputFile=$classpathFile")
    if ($MavenRepository) { $buildArgs += "-Dmaven.repo.local=$MavenRepository" }
    & mvn.cmd @buildArgs
    if ($LASTEXITCODE -ne 0) { throw 'Unable to prepare the local account command.' }
    $dependencies = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    $classes = Join-Path $projectRoot 'target/classes'
    & java '-cp' "$classes;$dependencies" 'com.zxl.zxlaiagent.security.ProvisionAccountCommand' '--subject' $Subject '--login' $LoginName '--tenant' $Tenant '--role' $Role
    if ($LASTEXITCODE -ne 0) { throw 'Account command did not confirm creation; review the safe message above.' }
} finally { Pop-Location }
