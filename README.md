# AI-ssistant

A chat-first Android assistant that works the device instead of describing it. You point it at any
OpenAI-compatible endpoint; the commands it proposes run through a root shell and their output is fed
back to the model, so it can continue, verify and recover on its own.

## Requirements

- Android 8.0+ device (minSdk 26) with root (KernelSU or Magisk); developed and tested on Android 16
  (targetSdk 35).
- JDK 17 or newer.
- Android SDK: build-tools 35.0.0 and platform android-35.
- Gradle Wrapper 8.9 with Android Gradle Plugin 8.7.3. The existing `build.ps1` direct pipeline remains available.

## Gradle

```powershell
.\gradlew.bat :app:assembleDebug      # app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat :app:assembleRelease    # app\build\outputs\apk\release\app-release.apk
```

Set the Android SDK path in `local.properties` (`sdk.dir=...`); Android Studio creates this file
for you. Copy `keystore.properties.example` to `keystore.properties` for a release signed with your
own key. Both local files are ignored by Git. Gradle reads the app source from `app-src/`, so no
source tree has been duplicated.
## Build

```powershell
.\build.ps1                                  # -> release\AI-ssistant-<version>.apk
.\build.ps1 -Deploy                          # build, then adb install -r
.\build.ps1 -Deploy -Serial <serial>         # pick a device explicitly
.\build.ps1 -Sdk "C:\Android\Sdk"            # explicit SDK path
```

The SDK defaults to `..\Causentry\tools\sdk`, then to `%LOCALAPPDATA%\Android\Sdk`, then to
`AI_SSISTANT_SDK`. `build.cmd` is a thin wrapper for cmd.exe.

## Signing

The keystore is intentionally not in this repository (see `.gitignore`). Put `ai-ssistant.keystore`
next to the checkout before building a release APK; the alias is `aissistant`. For a fork, generate
your own key and adjust the alias and paths at the top of `build.ps1`.

## Project layout

```
app-src/
  manifest/AndroidManifest.xml
  res/values                       colours, styles (dark theme)
  res/drawable                     launcher and composer vector icons
  src/com/aissistant/app/
    MainActivity.java              chat UI, composer, transcript, run loop, approval gates
    AgentPrompt.java               system instructions for the model
    AiClient.java                  OpenAI-compatible client: streaming, tools, usage, retries
    RootShell.java                 persistent `su` session, timeouts, output cap
    HybridRouter.java              local executor: parser -> adapter plan -> verify
    LocalParser.java               rule-based intent parser, no model round trip
    Adapters.java / AppAdapter.java   per-app capability plans
    FastTasks.java                 single-shell shortcuts for common requests
    OverlayView.java / OverlayHub.java   floating panel while another app is driven
    Hygiene.java                   clears hooking leftovers, restores device settings
    SessionLog.java                append-only transcript journal per session
    Store.java                     endpoint/model configuration, settings, history
    AgentService.java              foreground service that keeps a run alive in the background
build.ps1 / build.cmd
```

## How a request is handled

```
user prompt
  |
  +-- FastTasks      single shell chain, no model call
  +-- LocalParser    -> HybridRouter adapter plan (launch, tap, type, verify)
  +-- model          native tool calls, or a ```sh block / `RUN:` line as a fallback
  |
  v                       output is capped, timed out, and returned to the conversation
verify -> recover -> reply    the run stops when the task is done, the user stops it, or a
                              concrete blocker is proven
```

`$ <command>` in the input runs a root command directly, without the model.

A run keeps going while the agent drives other apps: a foreground service stops the system from
freezing it, and a small overlay panel keeps the transcript, a STOP button and prompt input on screen.
The panel never takes the input focus away from the app being driven.

## Automation hooks

```bash
adb shell am start -n com.aissistant.app/.MainActivity --es run 'id; uname -r'
adb shell am start -n com.aissistant.app/.MainActivity --es prompt 'list root-capable apps'
```

## Security and privacy

- The app's privileges come from `su` alone; approvals are the root manager's decision.
- Commands run as uid 0 with a per-command timeout and a 48 KB output cap.
- Risky categories (install, destructive, system, egress, messaging) stop at an in-app approval gate
  unless auto-approval is enabled.
- Endpoint configuration, API keys and conversation history stay in the app's private storage and are
  never committed here.
- The model only ever receives what is sent to the endpoint you configure.
