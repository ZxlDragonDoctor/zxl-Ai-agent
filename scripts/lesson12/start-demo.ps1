[CmdletBinding()]
param([ValidateRange(1024, 65535)][int]$Port = 18132)
$ErrorActionPreference = 'Stop'
$lesson12Root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $lesson12Root
try {
    foreach ($lesson12Name in @('LESSON12_DB_URL', 'LESSON12_DB_USER', 'LESSON12_DB_PASSWORD')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($lesson12Name))) {
            throw "$lesson12Name is required. Load your ignored local environment file first."
        }
    }
    if (-not $env:LESSON12_DB_URL.StartsWith('jdbc:postgresql:')) { throw 'A real PostgreSQL URL is required; no in-memory fallback is supported.' }
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
        throw "Port $Port is already in use. The existing process has not been stopped."
    }
    Get-Command java, mvn -ErrorAction Stop | Out-Null
    $env:LESSON12_PORT = [string]$Port
    if (-not $env:LESSON12_EMBEDDING_MODE) { $env:LESSON12_EMBEDDING_MODE = 'demo' }
    if ($env:LESSON12_EMBEDDING_MODE -notin @('demo', 'http')) { throw 'Embedding mode must be demo or http.' }
    if ($env:LESSON12_EMBEDDING_MODE -eq 'http') {
        foreach ($lesson12Name in @('LESSON12_EMBEDDING_ENDPOINT', 'LESSON12_EMBEDDING_API_KEY', 'LESSON12_EMBEDDING_REVISION')) {
            if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($lesson12Name))) { throw "$lesson12Name is required for real embedding mode. No demo fallback will be used." }
        }
    }
    # Compile only; this startup command is not evidence that tests passed.
    & mvn -q '-DskipTests' compile dependency:build-classpath '-Dmdep.outputFile=target/lesson12-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Compilation or dependency classpath generation failed.' }
    $lesson12Classpath = 'target/classes;' + (Get-Content -LiteralPath 'target/lesson12-classpath.txt' -Raw).Trim()
    Write-Host "Starting lesson 12 on 127.0.0.1:$Port (mode: $($env:LESSON12_EMBEDDING_MODE)). No full application/model context is scanned."
    & java -cp $lesson12Classpath com.zxl.zxlaiagent.knowledge.Lesson12Demo
    if ($LASTEXITCODE -ne 0) { throw 'Lesson 12 exited with an error. Check PostgreSQL and embedding configuration.' }
} finally { Pop-Location }
