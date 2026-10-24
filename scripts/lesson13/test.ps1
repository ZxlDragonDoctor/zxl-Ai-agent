[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$lesson13Root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
foreach ($lesson13Name in @('LESSON12_DB_URL', 'LESSON12_DB_USER', 'LESSON12_DB_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($lesson13Name))) {
        throw "$lesson13Name is required. This suite needs real PostgreSQL and does not fall back to memory."
    }
}
Push-Location $lesson13Root
try {
    # Real PG, controlled model fixtures and loopback HTTP contracts. No paid model calls.
    & mvn -q '-Dtest=BudgetedConnectionTest,HttpKnowledgeEmbeddingTest,HttpRerankerTest,RetrievalAlgorithmsTest,RetrievalApiTest,RetrievalPgTest,RetrievalRerankBoundaryPgTest,EvaluationMetricsTest,EvaluationServiceTest,EvaluationPgTest' test
    if ($LASTEXITCODE -ne 0) { throw 'Lesson 13 verification failed. Inspect target/surefire-reports.' }
} finally { Pop-Location }
