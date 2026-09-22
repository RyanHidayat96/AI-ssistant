param()

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Cache = Join-Path ([System.IO.Path]::GetTempPath()) "ai-ssistants-test-deps"
$Json = Join-Path $Cache "json-20240303.jar"
$Classes = Join-Path $Root "build\reliability-classes"

if (-not (Test-Path $Json)) {
  New-Item -ItemType Directory -Force -Path $Cache | Out-Null
  & curl.exe -k -L --fail --retry 2 -o $Json "https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar"
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
New-Item -ItemType Directory -Force -Path $Classes | Out-Null

$sources = @(
  (Join-Path $Root "app-src\src\com\aissistants\app\AgentPrompt.java"),
  (Join-Path $Root "app-src\src\com\aissistants\app\AgentSkills.java"),
  (Join-Path $Root "app-src\src\com\aissistants\app\AgentTools.java"),
  (Join-Path $Root "app-src\src\com\aissistants\app\AgentMemory.java"),
  (Join-Path $Root "app-src\src\com\aissistants\app\ReferenceReader.java"),
  (Join-Path $Root "app-src\src\com\aissistants\app\RunGuard.java"),
  (Join-Path $Root "app-src\src\com\aissistants\app\AiClient.java"),
  (Join-Path $Root "app-src\src\com\aissistants\app\RootShell.java"),
  (Join-Path $Root "tests\java\android\util\Log.java"),
  (Join-Path $Root "tests\java\com\aissistants\app\AgentReliabilityTest.java")
)

& javac --release 11 -cp $Json -d $Classes @sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp "$Classes;$Json" com.aissistants.app.AgentReliabilityTest
exit $LASTEXITCODE
