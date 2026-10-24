[CmdletBinding()]
param(
    [string]$PostgresHome = 'runtime-data/pg-lesson12',
    [string]$DataDirectory = 'runtime-data/pg-lesson12-data',
    [ValidateRange(1024, 65535)][int]$Port = 15432
)
$ErrorActionPreference = 'Stop'
$lesson12Root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $lesson12Root
try {
    $lesson12Home = (Resolve-Path -LiteralPath $PostgresHome).Path
    $lesson12Data = (Resolve-Path -LiteralPath $DataDirectory).Path
    if (-not (Test-Path -LiteralPath (Join-Path $lesson12Data 'PG_VERSION'))) { throw 'An initialized PostgreSQL data directory is required. This script never initializes, removes or replaces existing data.' }
    $lesson12Bin = Join-Path $lesson12Home 'Library/bin'
    $lesson12PgCtl = Join-Path $lesson12Bin 'pg_ctl.exe'
    if (-not (Test-Path -LiteralPath $lesson12PgCtl)) { throw 'pg_ctl.exe not found under the specified PostgreSQL installation.' }
    $lesson12OriginalPath = $env:PATH
    $env:PATH = $lesson12Bin + ';' + $env:PATH
    & $lesson12PgCtl status -D $lesson12Data
    if ($LASTEXITCODE -eq 0) { Write-Host 'The requested PostgreSQL cluster is already running; it has not been restarted.'; return }
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "Port $Port is occupied by another process; no process was stopped." }
    New-Item -ItemType Directory -Path target -Force | Out-Null
    $lesson12Log = Join-Path $lesson12Root 'target/lesson12-postgres.log'
    & $lesson12PgCtl start -D $lesson12Data -l $lesson12Log -o "-h 127.0.0.1 -p $Port" -w -t 30
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL failed to start. Inspect target/lesson12-postgres.log.' }
} finally {
    if ($null -ne $lesson12OriginalPath) { $env:PATH = $lesson12OriginalPath }
    Pop-Location
}
