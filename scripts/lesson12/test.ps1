[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$lesson12Root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
foreach ($lesson12Name in @('LESSON12_DB_URL', 'LESSON12_DB_USER', 'LESSON12_DB_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($lesson12Name))) { throw "$lesson12Name is required. These tests require a real PostgreSQL database and never fall back to H2." }
}
Push-Location $lesson12Root
try {
    # All embedding requests in this suite use controlled fixtures, including HTTP contract stubs.
    & mvn -q '-Dtest=KnowledgeTextTest,HttpKnowledgeEmbeddingTest,KnowledgeProfileTest,KnowledgeFailureApiTest,KnowledgePgTest,KnowledgeApiPgTest' test
    if ($LASTEXITCODE -ne 0) { throw 'Lesson 12 verification failed. Inspect target/surefire-reports; no failed check is converted into a pass.' }
} finally { Pop-Location }
