[CmdletBinding()]
param([ValidateRange(1024,65535)][int]$Port=18135)
$ErrorActionPreference='Stop'
$lesson15Root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $lesson15Root
try {
    foreach($lesson15Name in @('LESSON12_DB_URL','LESSON12_DB_USER','LESSON12_DB_PASSWORD')) {
        if([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($lesson15Name))){throw "$lesson15Name is required. Load ignored local environment first."}
    }
    if(-not $env:LESSON12_DB_URL.StartsWith('jdbc:postgresql:')){throw 'Real PostgreSQL is required; no memory fallback.'}
    if(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue){throw "Port $Port is occupied; no existing process was stopped."}
    if($env:LESSON12_EMBEDDING_MODE -ne 'http'){throw 'Set LESSON12_EMBEDDING_MODE=http and the same embedding profile used for ingestion. Demo embedding is reserved for explicit automated tests.'}
    foreach($lesson15Name in @('LESSON12_EMBEDDING_ENDPOINT','LESSON12_EMBEDDING_API_KEY','LESSON12_EMBEDDING_REVISION')) {
        if([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($lesson15Name))){throw "$lesson15Name is required for the real embedding profile."}
    }
    $env:LESSON15_MCP_PORT=[string]$Port
    & mvn -q '-DskipTests' compile dependency:build-classpath '-Dmdep.outputFile=target/lesson15-classpath.txt'
    if($LASTEXITCODE -ne 0){throw 'Compilation or classpath generation failed; this startup is not a test pass.'}
    $lesson15Classpath='target/classes;'+(Get-Content target/lesson15-classpath.txt -Raw).Trim()
    Write-Host "Starting fixed-scope read-only MCP on 127.0.0.1:$Port (SDK 0.7.0 legacy SSE)."
    & java -cp $lesson15Classpath com.zxl.zxlaiagent.knowledge.mcp.KnowledgeMcpServer
    if($LASTEXITCODE -ne 0){throw 'MCP server exited with an error. Check PG and embedding profile.'}
} finally {Pop-Location}
