[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$lesson15Root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
foreach($lesson15Name in @('LESSON12_DB_URL','LESSON12_DB_USER','LESSON12_DB_PASSWORD')) {
    if([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($lesson15Name))){throw "$lesson15Name is required; PG tests are not replaced by in-memory tests."}
}
Push-Location $lesson15Root
try {
    & mvn -q '-Dtest=McpAdapterBoundaryTest,McpResultContractTest,KnowledgeMcpContractTest,KnowledgeMcpTransportTest,KnowledgeMcpSessionTest,KnowledgeMcpPgTest,ResearchRunnerTest,ResearchDispatchBoundaryTest,McpEvidenceVerifierTest,ResearchControllerTest,ResearchAgentIntegrationTest' test
    if($LASTEXITCODE -ne 0){throw 'Lesson 15 tests failed. See target/surefire-reports.'}
} finally {Pop-Location}
