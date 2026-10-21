# Jot — a small, free, offline notes app for Android

Android 7.0+ (API 24). No accounts, no ads, no tracking, no internet permission, no third-party libraries.

## Install & updates (Obtainium)
Jot isn't on an app store; new versions are published on this repo's
[Releases](https://github.com/aidanlenahan/jot/releases) page. [Obtainium](https://github.com/ImranR98/Obtainium)
(free, open source) watches that page and installs updates with one tap:

1. Install Obtainium on the device (download its APK from its GitHub releases page; on a Fire tablet allow
   *Settings → Security & Privacy → Apps from Unknown Sources* for your browser / Files app, then for Obtainium).
2. In Obtainium tap **Add App**, paste `https://github.com/aidanlenahan/jot` and tap **Add**.
3. If Jot is already installed, Obtainium detects it and tracks it — your notes are kept. Otherwise tap **Install**.
4. When an update appears, open Obtainium (or tap its notification) and tap **Update**.

Every release is signed with the same key, so updates install over the existing app and keep your notes.
You can also download the APK from Releases and open it directly.

## Features
**Notes** — titles, pin to top, search, folders (chips on the home screen; long-press a folder to rename/delete),
note colours, list or grid layout, sort by last edited / created / title, trash with 30-day restore,
word & character count, share in (Jot appears in Android's Share menu for text and images) and share out (text or PDF),
home-screen shortcuts (long-press the app icon: New note / Handwriting / Freewrite canvas).

**Text** — markdown with live styling: `**bold**`, `*italic*`, `<u>underline</u>`, `~~strike~~`, `==highlight==`,
`` `code` ``, `#` headings, `-` bullets, `1.` numbered, `- [ ]` checklists, `>` quotes, `---` dividers, links.
Undo/redo, reading view, Ctrl+B/I/U/Z/Y on a keyboard.

**Images** — insert from gallery/files or the camera, anywhere in a note.

**Handwriting & freewrite** — lined page or blank canvas anywhere in a typed note. Pen, highlighter, eraser
(whole-stroke or partial — tap the eraser twice to switch), lasso (move, resize with the corner dot, recolour,
duplicate, delete), 6 colours, S/M/L sizes plus optional pressure ("P"), undo/redo, two-finger pinch zoom,
smoothing (Raw/Smooth/Smooth+), hold-to-snap shapes, paper styles, add space / trim, save as SVG.
Drawings are stored as vector strokes, so they stay editable forever.

**Settings** — theme; home layout & sort; pressure-sensitive pen; palm rejection; shape snap; partial eraser;
smoothing; show/hide and reorder every button in the note toolbar and the drawing toolbar; app lock (PIN, optional
fingerprint/face, lock delay); export everything / import backup; local sync folder; trash.

**Export** — per note: .md and .txt (text only), .pdf and .docx (text, handwriting and images), .svg per drawing.
"Export everything" writes one .json with every note, folder, image and editable drawing; "Import backup" merges it
(newest edit wins) — use it to move to another device.

**Local sync** — pick a folder that Syncthing / an SD card / a NAS share syncs; Jot merges `Jot-sync.json` both ways.

## Releasing a new version
1. Bump `VERSION_CODE` (by 1) and `VERSION_NAME` in `version.txt`.
2. Merge to `main`. The **Release APK** workflow builds and signs the APK, checks it's signed with the
   existing key, and publishes release `v<VERSION_NAME>`. Obtainium picks it up from there.

The workflow needs two repository secrets (*Settings → Secrets and variables → Actions*):
- `JOT_KEYSTORE_BASE64` — the keystore file, base64-encoded (`base64 -w0 jot-release.jks`)
- `JOT_KEY_PASS` — its password

## Build from source (no Gradle needed)
Put `android.jar` (API 34), `aapt2`, `d8.jar` (any jar containing `com.android.tools.r8.D8`), `apksigner.jar`
and `zipalign` in `../tools`, then run `./build.sh`. Needs JDK 17+.

Signing: the key is not in this repo. Run with `JOT_KEYSTORE=/path/to/jot-release.jks JOT_KEY_PASS=… ./build.sh`.
Keep the key safe — updates must be signed with the same key or Android will refuse to install them over the old version.
