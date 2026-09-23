# Localization audit

## Current state

The app does **not** yet follow the Android system language for its own UI.

| Area | Finding | Effect |
| --- | --- | --- |
| Resources | No `res/values/strings.xml` or locale-qualified text resources | Android has no translated UI text to select. |
| Main UI | 185 display-text call sites are hard-coded in `MainActivity.java` | Chats, settings, lock, models, dialogs, toasts, permission UI, and voice errors are mixed Indonesian and English. |
| Overlay | 9 hard-coded display-text call sites in `OverlayView.java` | Overlay language can differ from the main app. |
| Notifications | 19 notification/status text sites in `AgentService.java` | Foreground notification can use a different language from the screen. |
| Formatting | `timeOf()`, `dayOf()`, token count, and test duration explicitly use English locales | Dates, abbreviated numbers, and units do not match device language. |
| Agent output | `AgentPrompt` already directs the model to use the user's language | Keep this behavior: the user's language is a better signal than the device UI language. |
| Speech | `RecognizerIntent.EXTRA_LANGUAGE` already uses `Locale.getDefault()` | Speech recognition already follows the app/device locale. |

## Proposed supported locales

Ship two complete UI resource sets first:

- `res/values/strings.xml`: English default and complete fallback.
- `res/values-in/strings.xml`: Indonesian.

English is the correct fallback for system languages that are not yet translated. Adding a language to Android's app-language list must happen only after its resource file is complete.

## Implementation approach

1. Move every visible static string into `R.string.*`. This includes labels, hints, buttons, dialogs, menu items, toasts, accessibility descriptions, overlay text, and notification text. Keep text IDs semantic, for example `app_lock_unlock`, not `button_12`.
2. Use formatted string resources for dynamic UI. For example, `Working · step %1$d`, `Load %1$d earlier messages`, and connection duration. Use `<plurals>` for message/command counts.
3. Keep non-UI data literal and untranslatable: user chat history, model IDs, provider URLs, shell commands, package names, tool output, evidence, and code. Mark the product name `AI-ssistant` as non-translatable.
4. Replace presentation formatting that explicitly uses `Locale.ENGLISH` or `Locale.US` with the app configuration locale. Keep normalization/parsing on `Locale.ROOT`, because it is program logic rather than UI formatting.
5. Add `res/xml/locale_config.xml` listing `en` and `id`, then set `android:localeConfig="@xml/locale_config"` on `<application>`. This project uses a direct `aapt2` build rather than Gradle, so manual locale configuration is the compatible approach.
6. Do not add a custom language picker initially. With no app override, Android resources automatically follow the system language on every supported Android version. Android 13+ also exposes **Settings → Apps → AI-ssistant → Language** for the two declared locales.
7. Keep the agent response rule as “use the user's language.” UI language and conversation language are intentionally independent: a user with an English device can still write Indonesian prompts and receive Indonesian answers.

## Lifecycle and test plan

- `MainActivity` does not declare `locale` in `configChanges`, so Android recreates it when the UI language changes and resolves new resources automatically.
- Test English and Indonesian system locales on Android 12 and lower.
- On Android 13+, test both system language and the per-app Language setting, including resetting the app language to “System default.”
- Verify long Indonesian labels on Models, Settings, App Lock, overlay, permission dialogs, and notifications; then test large font and landscape.
- Add a source-level regression check: no user-facing literal is introduced outside an approved allowlist (product name, protocol/error literals, code, and external data).

## Scope boundary

This audit does not translate existing conversation records or tool output. They are historical/user-provided data and must remain byte-for-byte intact.
