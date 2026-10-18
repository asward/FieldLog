# FieldLog — Spec V2

Frictionless note taking and recording during personal projects.

V2 replaces session mode with an explicit **log** driven by a voice state machine, stops putting photos in git by default, and fixes speech latency and feedback. Architecture is unchanged: libgit2 (git2-rs) inside a shared Rust core, UI per platform.

## 1. Vocabulary

These terms are used in the UI, the code and the docs. "Session" is retired everywhere.

| Term | Meaning |
| --- | --- |
| **Vault** | One git repository. Multiple vaults are supported. |
| **Project** | A related body of work (e.g. a new app idea). A top-level folder in a vault. |
| **Active project** | The project currently selected, by tap. Replaces "session project". |
| **Log** | Everything between **New log** and `end log`. One markdown file, one commit. Can be re-opened and edited. |
| **Logging** | The state of having a log open. |
| **Entry** | One item inside a log: a **note** or a **snap**. Tags belong to the log, not to an entry. |
| **Save** | In app terms: commit, then push. Shown to the user as two stages: *Committed* and *Pushed*. |

Code renames: `Session*` → `Log*` (Kotlin and Rust), `source: session` → `source: log` (the reader accepts both).

## 2. Screens

1. **Projects.** Projects in the current vault. Tapping one makes it active and opens it.
2. **Project view.** A blog-style feed, newest log first, grouped under date headers. Each log card shows its time range, tags, the first lines of its notes, and up to two photo thumbnails with a "+N" count when there are more. Tapping a card opens the log. A **New log** button starts one.
3. **Log screen.** Runs the state machine (§3). From top to bottom: the mode banner, the live transcript, then the log so far in reading order (oldest entry first). It has touch buttons that mirror every command.

## 3. Log state machine

```
            "note" ┌────────┐ "end note"
          ┌───────▶│  NOTE  │────────────┐
          │        └────────┘            │
          │ "tag"  ┌────────┐ "end tags" │
┌─────────┴─┐────▶ │  TAG   │──────────▶ ├──▶ STANDBY
│  STANDBY  │      └────────┘            │
└─────────┬─┘ "snap" ┌─────────┐ "end annotation" ┌────────┐ "end markup"
          └────────▶ │ ANNOTATE│─────────────────▶│ MARKUP │──────────┘
                     └─────────┘                  └────────┘
      "end log" (from any mode) → close any open entry → COMMIT → PUSH → project view
```

**New log** enters STANDBY. From there:

| Mode | Behavior | Commands accepted |
| --- | --- | --- |
| **STANDBY** | Listens only for commands. Dictation is ignored. | `note`, `tag`, `tag <words>` (one-shot), `snap`, `undo`, `end log` |
| **NOTE** | Dictation becomes one note entry. | `end note`, `snap`, `scratch that`, `cancel`, `end log` |
| **TAG** | Each utterance becomes one tag, slugified (e.g. "deck framing" → `deck-framing`). | `end tags`, `scratch that`, `cancel`, `end log` |
| **ANNOTATE** | A photo was taken on entry. Dictation becomes the annotation for that photo. | `end annotation`, `snap`, `scratch that`, `cancel`, `end log` |
| **MARKUP** | Dictation becomes markup suggestions for that photo, stored as text for a later AI pass. The image is not edited. | `end markup`, `snap`, `scratch that`, `cancel`, `end log` |

Rules:

- **One snap entry holds one photo.** Saying `snap` in ANNOTATE or MARKUP closes the current snap entry and starts a new one. A burst is therefore "snap, snap, snap", with empty annotations allowed.
- **`snap` from NOTE nests.** The photo is inserted inline at that point in the note. After `end markup`, the machine returns to NOTE. Only one level of nesting is allowed.
- **Command meanings:**
  - `scratch that` removes the last utterance in the current mode.
  - `cancel` discards the open entry and returns to STANDBY. For a snap taken inside a note, it discards just that snap and returns to the note.
  - `undo` in STANDBY removes the last entry of the open log.
- **No auto-commit.** The v1 silence timeout is removed. After 20 minutes idle (configurable), the mic stops and a notification reads "Log open: resume or end". The log stays open.
- **The state machine lives in the Rust core.** It is pure: utterance in, events out. Android, and later iOS, only render state and play feedback.

### Command phrases

The defaults are below. Each command takes a list of phrases, editable in Settings and stored on the device. Matching ignores case and punctuation and treats singular and plural alike ("end annotation" = "end annotations").

| Command | Default phrases |
| --- | --- |
| note | "note", "start note" |
| end note | "end note" |
| tag | "tag", "start tags" |
| end tags | "end tags", "end tagging" |
| snap | "snap", "take photo" |
| end annotation | "end annotation" |
| end markup | "end markup" |
| scratch that | "scratch that", "strike that" |
| cancel | "cancel" |
| undo | "undo" |
| end log | "end log" |

**Misfire rule:**

- A command fires when it is the **whole** utterance, the same as in v1.
- `end <x>` commands may also fire when they **end** an utterance ("...rot goes two feet in, end note"). The phrase is stripped from the text.
- No other command fires mid-sentence.
- The v1 command prefix setting is removed.

## 4. Saving and the working tree

The working tree is always being written. Only `end log` commits.

- **On New log:** the app creates `<project>/YYYY/MM/<id>.md` in the working tree and rewrites it after every entry. Undo and nesting state goes in `.fieldlog/open-log.json`, which is device-local and ignored by git.
- **Crash or kill:** on the next launch, an uncommitted log file in the working tree is offered for **Resume**.
- **On `end log`:**
  1. Commit with the subject `<project>: <first note summary>` and the trailer `FieldLog-Log: <id>`.
  2. Fetch.
  3. Fast-forward or merge commit.
  4. Push.
  5. The UI shows *Committed*, then *Pushed*. If offline, the push is queued and retried by WorkManager.
- **Background sync pauses for a vault while a log in it is open,** so a merge never runs against a half-written file.
- **Re-entry:**
  - Opening a log from the project view and tapping **Edit** resumes the machine in STANDBY.
  - New entries are appended. Existing entries can be edited or deleted by touch.
  - `end log` commits again, with the trailer `FieldLog-Edit: <id>`.
- **Conflicts:**
  - Logs are separate files, so conflicts happen only when the same log is edited on two devices.
  - Both versions are kept: `<id>.md` and `<id>.conflict-<device>-<timestamp>.md`.
  - The log card shows a conflict badge. Conflict markers are never written.
- **Reverting a committed log:** a touch action in the log view adds a revert commit, the same as v1 undo. History is never rewritten.

## 5. Log file format

One file per log. V1 one-entry files are still read; each is treated as a log with a single note.

````markdown
---
id: 20261018T103512-a1b2
project: deck-rebuild
started: 2026-10-18T10:35:12-07:00
ended: 2026-10-18T11:02:40-07:00
tags: [rot, ledger]
source: log
photos:
- 3a7bd3e2…4f1b
---

## 10:35:12 Note

Pulled the old ledger board.

![](../../../assets/3a/3a7bd3e2…4f1b.jpg)

Rot goes about two feet in.

## 10:41:03 Snap

![](../../../assets/9c/9c04…e1.jpg)

> Joist end under the door, soft to a screwdriver.

```fieldlog-markup
circle: dark area at joist end
arrow: water path from door sill
```
````

- **Entry headings** use the form `## HH:MM:SS Note|Snap`. They are the parser's anchors; edits made outside the app must keep them.
- **`photos`** lists every image in body order, as in v1.
- **Annotation** follows its photo as a `>` quote, so a photo inside a note keeps its annotation apart from the note text.
- **Markup** goes in a `fieldlog-markup` fenced block so later tooling can find it without parsing prose.
- **Unknown front-matter keys** are preserved, as in v1.

## 6. Photo storage

`fieldlog.toml` gets a new value: `image_storage = "local" | "s3" | "git"`.

| Mode | Behavior | Default |
| --- | --- | --- |
| `local` | Photos stay on the phone in the vault's `assets/` (gitignored) and are queued in `.fieldlog/pending-uploads.txt`. Markdown links them exactly as in other modes. | **Yes, for new vaults.** |
| `s3` | As in v1. R2 is configured here because it is S3-compatible. | |
| `git` | As in v1. Intended for self-hosted remotes. Choosing it for a github.com, gitlab.com or bitbucket.org remote shows a storage warning. | |

**Stable keys** already exist: every asset is named by the SHA-256 of its normalized bytes. EXIF is stripped and the image re-encoded before hashing, as v1 already does. The R2 object key is `<prefix>/assets/ab/<sha>.<ext>`, the same path the markdown links.

- **Switching `local` → `s3`:** the pending queue drains on the next sync. No markdown is rewritten.
- **Switching `git` → `local` or `s3`:** new photos stop being committed. Photos already in history stay there; v2 does not rewrite history.
- **The pushed-image invariant is relaxed in `local` mode.** Other devices and the GitHub web view show missing images until R2 is set up.
- **Risk: photos in app-private storage are deleted on uninstall or Clear data.** Mitigations:
  - Settings shows "N photos not backed up (X MB)".
  - An **Export photos** action writes a zip to Downloads.
  - Uninstall is not blocked.

## 7. Speech

**Recognizer setting**, chosen per device:

- **On-device** (default).
- **Cloud** (the system recognizer).
- **Self-hosted** (future): a Whisper-compatible HTTP endpoint.

All three sit behind one `Transcriber` interface that emits partial and final results. The log screen always shows which engine is active. If on-device falls back because the language pack is missing, the app says so and offers the download. V1 fell back to cloud silently.

**Latency fixes** for v1's `SpeechLoop`:

- **Continuous listening** (segmented session, `EXTRA_SEGMENTED_SESSION`). One long-running recognition yields segments, instead of tearing down and restarting after every utterance. This removes the gap where words are lost and the restart beep noted in v1's known limits.
- **Android 13 (API 33) is required for good speech performance.** The app still installs on older phones, but they only get the v1 restart mode, and the speech check says so.
- **Commands match on partial results.** A command fires once it is stable across two consecutive partials, so it doesn't wait about 1.5 s for the silence window.
- **Silence window depends on mode:** 700 ms in STANDBY, where only commands are expected, and 1500 ms when dictating.
- **Speech check (per phone).** This runs in Settings → Speech, and on first launch. You say a short prompt phrase into each candidate setup:
  - on-device, continuous;
  - cloud, continuous;
  - on-device, v1 restart mode;
  - cloud, v1 restart mode.

  The check records whether each setup worked, how long the first word took to appear, and whether the phrase came back correct. It then lists only the setups that worked, fastest first, and pre-selects the best one. If on-device needs a language pack, the check offers to download it and runs again. The result is stored on the device, and the check can be re-run at any time, for example after an OS update.
- **Keyword spotting in standby uses the same recognizer in v2.** A dedicated keyword engine (Porcupine or openWakeWord) can be added behind `Transcriber` later if battery or latency requires it.

## 8. Feedback

V1 had no feedback when the mic became ready. Its tones played on the notification stream, so silent mode and Do Not Disturb muted them. Its haptics used view feedback, so the system touch-haptics setting suppressed them.

V2 rules:

- **Earcons:** short bundled sounds played through `SoundPool` with usage `ASSISTANCE_SONIFICATION`, not `ToneGenerator`.
- **Haptics:** explicit `VibrationEffect` patterns through `Vibrator`.
- **Visual:** a full-width banner in the mode's color, the live partial transcript, and a mic level meter driven by `onRmsChanged`.
- **Settings:** sound on/off, sound volume, and haptics on/off.

| Event | Sound | Haptic | Banner |
| --- | --- | --- | --- |
| Mic ready (STANDBY) | soft rising | 1 short | grey "Standby" |
| Enter NOTE | rising | 1 short | blue "Note" |
| Enter TAG | rising, double | 2 short | purple "Tags" |
| Snap | shutter | 1 long | amber "Annotate" |
| Enter MARKUP | rising | 1 short | orange "Markup" |
| Mode ended | falling | 1 short | returns to previous |
| Command not allowed here | low buzz | 3 short | names allowed commands |
| End log: committed / pushed | chime / second chime | 1 long | green |
| Save failed | low buzz | 3 short | red, with reason |

## 9. Entry points and wake

- **The global wake phrase is dropped.** The "captain's log" phrase never had a code path in v1. Android doesn't let third-party apps register a system hotword, and an always-on mic service isn't worth its cost. The phrase setting is removed.
- **The widget, Quick Settings tile and per-project shortcuts** open the active project's log screen with a new log in STANDBY. The widget's camera button also snaps immediately.
- **Volume keys** keep the v1 mapping on the log screen: up = `snap`, down = mic on/off.
- **Locking the screen does not end a log.** A microphone foreground service (`FOREGROUND_SERVICE_MICROPHONE`), started while the log screen is visible, keeps listening after the screen locks. Over the lock screen and keep-screen-on behave as in v1.
- **`snap` while the log screen isn't visible** (screen off, or another app in front) is ignored. The mode does not change and there is no sound or haptic. The app posts an "Unlock to snap" notification, and tapping it opens the log screen. The app never relaunches itself to take the photo. Every other command keeps working by voice.

## 10. Unchanged from v1

- Raw audio is never kept.
- The share sheet accepts images, text and links. A share creates a one-entry log committed immediately, without the state machine.
- Search covers notes, annotations, markup and tags.

## 11. Background: why the speech check exists

Android's recognizer handles one utterance at a time. It listens, waits for 1.5 s of silence, returns the text, and shuts off. v1 then starts it again. Each restart takes a moment, and anything said during that gap is lost. On some phones the restart also beeps. This is a large part of why v1 speech feels slow and choppy.

Android 13 added continuous listening: the recognizer stays on for the whole log and hands back text in pieces as you pause. Not every phone's on-device recognizer supports it, though. Some ignore the setting, and some support it only in cloud mode. This can't be known ahead of time, so the speech check (§7) measures it on each phone and offers only the setups that work.

In v1 restart mode, commands still respond quickly because they match on partial results. Dictation keeps the small gap after each pause.

## 12. Delivery plan (PRs)

1. **Core: log format and state machine.**
   - Multi-entry log file reader and writer, with v1 compatibility.
   - Pure state machine and command matcher, with configurable phrases.
   - Working-tree drafting, commit on `end log`, edit re-entry, conflict files.
2. **Core: photo storage.** `local` mode, the hosted-remote warning for `git`, and mode migration.
3. **Android: speech.** `Transcriber` interface, recognizer setting, continuous listening, v1 restart mode as fallback, speech check, partial-result command matching, per-mode silence windows.
4. **Android: feedback.** Earcons, vibration patterns, banner, level meter, and their settings.
5. **Android: UI.**
   - Projects screen, blog-style project view and log screen.
   - The Session → Log rename and entry-point updates.
   - Microphone foreground service, idle stop, Resume.
6. **Docs.** `FORMAT.md` and README rewritten for v2.
