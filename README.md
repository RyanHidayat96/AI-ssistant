# AI-ssistants

An Android **AI assistant that owns the device**. You point it at any OpenAI-compatible
endpoint, and it works through a **root shell**: it inspects, patches, builds, installs,
reads app data and pokes the kernel — verifying its own work step by step — instead of
just answering questions.

```
AI-ssistants/
  build.ps1 / build.cmd        aapt2 + javac + d8 + zipalign + apksigner (no Gradle)
  app-src/
    manifest/AndroidManifest.xml
    res/{values,drawable}      dark theme, vector launcher icon
    src/com/aissistants/app/
      MainActivity.java        chat UI, quick actions, agent loop
      AiClient.java            OpenAI-compatible client (HttpURLConnection, tool calls)
      RootShell.java           `su -c` executor, timeout + fail-fast when root is denied
      Store.java               endpoint config + history (private SharedPreferences)
  ai-ssistants.keystore        signing key (alias `aissistants`)
  release/AI-ssistants-v<ver>.apk
```

## Build

```powershell
.\build.ps1                 # -> release\AI-ssistants-v1.0.0.apk
.\build.ps1 -Deploy         # build + adb install -r
.\build.ps1 -Sdk D:\sdk     # explicit Android SDK (defaults to the sibling Causentry tools/sdk)
```

Needs build-tools 35.0.0 + platform android-35 and a JDK 17+ (`JAVA_HOME` or Program Files).

## Use

1. Install the APK, open it.
2. Tap the status pill (**NO ROOT**) → approve the KernelSU/Magisk prompt once, or enable
   AI-ssistants in the manager's Superuser list. The pill turns **ROOT ✓**.
3. Tap the gear and save **Base URL** (`https://api.openai.com/v1`, `http://192.168.1.9:11434/v1`, …),
   **API key** (empty for local servers) and **Model**.
4. Ask for anything. Commands the model emits run as **uid 0** and their output is fed straight
   back so it can continue and verify.

`$ <command>` in the input runs a root command yourself, without the model — useful offline.

## How the agent loop works

```
user prompt
  ↓  system prompt: root shell tool (run_shell), "do the work yourself, never refuse"
model reply ──┬─ native tool_calls (run_shell)   → executed
              └─ ```sh fenced block / RUN: line  → executed (models without tool calling)
  ↓  stdout+stderr merged, 48 KB cap, per-command timeout (default 180 s)
tool output is appended to the conversation and the loop repeats (≤ Max steps, default 12)
```

Quick actions (System / Apps / Processes / Logcat / Storage / Network) just prefill a strong
prompt for that inventory, so the assistant starts from facts instead of guesses.

Automation hooks (deterministic, no taps needed):

```bash
adb shell am start -n com.aissistants.app/.MainActivity --es run 'id; uname -r'
adb shell am start -n com.aissistants.app/.MainActivity --es prompt 'list root-capable apps'
```

## Notes

- Nothing about the app is privileged by itself: `su` is the single door, and the app can only do
  what the user's root manager allows. KernelSU `su` that never answers is detected and surfaced
  as the *not granted* hint instead of hanging a request.
- Output is capped (48 KB per command) and timed out, so a runaway command cannot wedge the chat.
- `AutoRun` off turns every proposed command into a queue with a **Run N** button — the model
  still plans, but nothing executes until you press it.
- History (last turns) and the endpoint config live in the app's private prefs; only the endpoint
  the user configured ever receives conversation data.
