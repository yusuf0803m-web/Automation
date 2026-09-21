# Pelita Auto Continue

An Android app that sends **"Lanjut"** to the ChatGPT Android app — but only
once ChatGPT has actually finished its previous response.

It is built for one workflow: generating a series of images in ChatGPT, where
every image needs a "Lanjut" to keep going, and sending it too early wastes a
turn.

## The idea: MODE B — wait for response

This is **not** a "tap Send every 3 minutes" macro. A fixed timer has no idea
whether ChatGPT is still streaming. The app is event-driven instead:

```
User opens ChatGPT
  -> Accessibility Service observes ChatGPT's UI
  -> generation starts        (a "stop generating" button appears)
  -> app waits
  -> generation ends          (stop button gone, send button back, composer ready)
  -> the end must hold steady for 1.5s   (debounce)
  -> configurable delay, default 10s, with a visible countdown you can cancel
  -> type "Lanjut" into the composer
  -> click Send
  -> wait for the next generation
  -> repeat
```

Timers exist only as a post-response delay, as timeouts, and as a slow fallback
poll. None of them is ever the trigger.

### The rule that matters most

Generation state is one of `GENERATING`, `FINISHED` or `UNKNOWN`. If the app
cannot read ChatGPT's UI confidently, the answer is `UNKNOWN`, and on `UNKNOWN`
**nothing is sent — the app just waits.** Waiting too long is cheap; sending
into a half-finished response is not.

## Features

- Event-driven detection built on `AccessibilityService`, using accessibility
  nodes only — **no coordinate tapping anywhere**.
- An explicit 11-state machine with `PAUSE`, `STOP` and an emergency stop from
  the notification.
- Debounce so streaming flicker (`GENERATING → FINISHED → GENERATING`) cannot
  trigger a send.
- Duplicate prevention: exactly one "Lanjut" per response cycle, enforced by a
  response-cycle id rather than a boolean that can get out of step.
- Configurable post-response delay (1–60s) with a visible, cancellable
  countdown.
- Configurable message and target package.
- Activity log, bounded in size, containing automation events only.
- An Accessibility Debug screen for when ChatGPT's UI changes.
- Foreground-service notification carrying PAUSE and STOP.

## Project layout

The project is split so the interesting logic can be tested without a device:

| Module  | What it is | Needs Android SDK? |
|---------|-----------|--------------------|
| `:core` | The whole automation brain: state machine, generation detector, debounce, duplicate prevention, node-matching heuristics, activity log. Pure Kotlin/JVM. | No |
| `:app`  | Android app: Compose UI, `PelitaAccessibilityService`, `ChatGptUiDetector`, foreground service. A thin adapter over `:core`. | Yes |

`settings.gradle.kts` includes `:app` only when an Android SDK is present, so
`./gradlew :core:test` works on a plain JDK.

Key types:

- `AutomationEngine` — the state machine. Owns no threads and no Android types;
  it takes `AutomationInput`s and returns `AutomationEffect`s.
- `GenerationStateDetector` — turns a `UiSnapshot` into
  `GENERATING` / `FINISHED` / `UNKNOWN`.
- `ChatGptNodeHeuristics` — **the file to edit when ChatGPT's UI changes.**
  It decides what each node is (send button, stop button, composer, spinner).
- `ChatGptUiDetector` (in `:app`) — walks the real accessibility tree and
  performs actions. All ChatGPT UI knowledge is confined to these two files.

## Building

Requirements: JDK 17+, Android SDK with API 35, and network access to
Google's Maven repository (`dl.google.com` / `maven.google.com`).

Run the tests — this needs no Android SDK at all:

```bash
./gradlew :core:test
```

Build the debug APK:

```bash
# Point Gradle at your SDK, either way works:
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
# or: export ANDROID_HOME=$HOME/Android/Sdk

./gradlew :app:assembleDebug
```

The APK lands at:

```
app/build/outputs/apk/debug/app-debug.apk
```

Install it:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`adb` is only used to install. The app has **no runtime dependency on adb** and
does not require root.

## Using it

1. **Enable the Accessibility Service.** Open the app and tap
   `AKTIFKAN ACCESSIBILITY`, then find *Pelita Auto Continue* under
   Settings → Accessibility → Installed apps and turn it on. The app will never
   enable this for you — Android does not allow it, and it should not.
2. **Check the settings.** Delay (default 10s), message (default `Lanjut`),
   and target package (default `com.openai.chatgpt`).
3. **Tap `MULAI`**, then switch to ChatGPT and leave it in the foreground.
4. The status line tracks what is happening: `WAITING FOR CHATGPT`,
   `GENERATING`, `WAITING 10s`, `SENDING`.
5. **`PAUSE`** stops all interaction — no typing, no tapping, no new countdown.
   Resume re-checks ChatGPT's state before continuing.
6. **`STOP`** ends the run, cancels any pending countdown and sends nothing
   further. Both are also on the notification.

### Verifying the target package

`com.openai.chatgpt` is the default, not a guarantee. To confirm it on your
device: open ChatGPT, switch to Pelita, open the **Debug** tab and read the
`Package` row. If it shows something else, type that value into
`Target package` on the same screen — no rebuild needed.

## Troubleshooting

Open the **Debug** tab; it shows exactly what the detector can and cannot see.

| Symptom | What the Debug tab shows | Fix |
|---|---|---|
| Nothing happens at all | `ChatGPT foreground: NO` | Wrong target package, or ChatGPT is not actually in front. |
| Stuck on `WAITING FOR CHATGPT` | `Send: NOT FOUND` | ChatGPT's send button changed. Add a hint to `SEND_HINTS` in `ChatGptNodeHeuristics`. |
| Stuck, never sends | `Generating: UNKNOWN` | Working as designed — the app refuses to act on an unreadable UI. The rows above tell you which control it lost. |
| Sends while still generating | `Stop generating: NOT FOUND` during streaming | The stop button is not being recognised; add its label/id to `STOP_HINTS`. |
| Countdown never reaches zero | Generating flips to `UNKNOWN` | The countdown deliberately freezes while the UI is unreadable. |
| `ERROR` state | — | The app waits out a cooldown and returns to `WAITING FOR CHATGPT` by itself. |

## Limitations

**Android.** This is not a background robot and does not pretend to be. It is
designed to work while the screen is on, ChatGPT is in the foreground and the
Accessibility Service is enabled. With the screen locked, the device asleep, or
ChatGPT killed by the system, automation does not run — and the app does not
try to work around Android's background restrictions, use root, or use adb at
runtime.

**ChatGPT's UI.** Detection relies on what the ChatGPT app exposes through
accessibility: a "stop generating" control while streaming, a send control and
a composer when idle. ChatGPT is not a stable, documented API. If OpenAI
relabels or restructures those controls, detection degrades to `UNKNOWN` — the
app stops acting rather than acting wrongly — and
`ChatGptNodeHeuristics`/`ChatGptUiDetector` need updating. The Debug screen
exists to make that a five-minute fix.

## Privacy

The Accessibility Service can, by nature of the API, see the content of the
window it observes. This app is written so that it does not keep any of it:

- No conversation text is stored, logged or displayed. The activity log
  contains automation events only ("Generation finished", "Message sent").
- To tell one response from the next, the app hashes the newest message into a
  short digest (`length:hashCode`) and keeps only that. The text is discarded
  immediately, and the digest cannot be read back into the message. This is
  covered by a test.
- The app requests no internet permission, so nothing can leave the device.
- Permissions are limited to `POST_NOTIFICATIONS` and the foreground-service
  permissions. No storage, overlay, contacts, location, camera or microphone.
- The Debug screen shows only found/not-found/unknown for each control, never
  message contents.

## Tests

```bash
./gradlew :core:test
```

68 unit tests covering the state machine and a fake accessibility node tree,
including the cases this app most needs to get right:

- `UNKNOWN` never triggers a send, however long it lasts.
- Never two "Lanjut" for one response.
- A paused or stopped engine never types or taps.
- A countdown freezes when the UI becomes unreadable, rather than firing blind.
- ChatGPT leaving the foreground returns the machine to `WAITING_FOR_CHATGPT`.
- Losing the Accessibility Service cancels everything in flight.
- The message digest never embeds the message.
