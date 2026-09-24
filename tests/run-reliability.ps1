param()

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Cache = Join-Path ([System.IO.Path]::GetTempPath()) "ai-ssistant-test-deps"
$Json = Join-Path $Cache "json-20240303.jar"
# Keep test bytecode out of Gradle/IDE output folders. Java language tooling can hold those
# classes open on Windows while this standalone check recompiles them.
$Classes = Join-Path ([System.IO.Path]::GetTempPath()) ("ai-ssistant-reliability-" + $PID)

if (-not (Test-Path $Json)) {
  New-Item -ItemType Directory -Force -Path $Cache | Out-Null
  & curl.exe -k -L --fail --retry 2 -o $Json "https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar"
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
New-Item -ItemType Directory -Force -Path $Classes | Out-Null

$sources = @(
  (Join-Path $Root "app-src\src\com\aissistant\app\AppLock.java"),
  (Join-Path $Root "app-src\src\com\aissistant\app\AgentPrompt.java"),
  (Join-Path $Root "app-src\src\com\aissistant\app\AgentSkills.java"),
  (Join-Path $Root "app-src\src\com\aissistant\app\AgentTools.java"),
  (Join-Path $Root "app-src\src\com\aissistant\app\ToolPolicy.java"),
  (Join-Path $Root "app-src\src\com\aissistant\app\AgentWindowFilter.java"),
  (Join-Path $Root "app-src\src\com\aissistant\app\OverlayHub.java"),
  (Join-Path $Root "app-src\src\com\aissistant\app\ChatBranch.java"),
  (Join-Path $Root "app-src\src\com\aissistant\app\AgentMemory.java"),
  (Join-Path $Root "app-src\src\com\aissistant\app\ReferenceReader.java"),
  (Join-Path $Root "app-src\src\com\aissistant\app\RunGuard.java"),
  (Join-Path $Root "app-src\src\com\aissistant\app\AiClient.java"),
  (Join-Path $Root "app-src\src\com\aissistant\app\RootShell.java"),
  (Join-Path $Root "tests\java\android\util\Log.java"),
  (Join-Path $Root "tests\java\com\aissistant\app\AgentReliabilityTest.java")
)

& javac --release 11 -cp $Json -d $Classes @sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp "$Classes;$Json" com.aissistant.app.AgentReliabilityTest
exit $LASTEXITCODE
