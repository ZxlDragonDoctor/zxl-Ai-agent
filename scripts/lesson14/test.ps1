[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$lesson14Root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
foreach ($lesson14Name in @('LESSON12_DB_URL', 'LESSON12_DB_USER', 'LESSON12_DB_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($lesson14Name))) {
        throw "$lesson14Name is required. Real PostgreSQL is mandatory; no in-memory fallback."
    }
}
Push-Location $lesson14Root
try {
    # Real PG and loopback model contracts; this command makes no paid model calls.
    & mvn -q '-Dtest=MemoryPgTest,MemoryApiPgTest,AnswerPolicyTest,AnswerApiTest,AnswerPgTest,HttpAnswerModelTest,KnowledgeAssistantProviderTest' test
    if ($LASTEXITCODE -ne 0) { throw 'Lesson 14 verification failed. Inspect target/surefire-reports.' }
} finally { Pop-Location }
