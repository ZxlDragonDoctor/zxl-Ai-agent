[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$lesson16Root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $lesson16Root
try {
    & mvn -q '-Dtest=SkillDefinitionTest,SkillPolicyTest,SkillRunnerTest,SkillApiTest,SkillProviderIntegrationTest,SkillChatRequestTest' test
    if($LASTEXITCODE -ne 0){throw 'Lesson 16 targeted tests failed. These tests do not replace real MCP/PG/model/UI acceptance.'}
} finally {Pop-Location}
