# Luna Explorer

An Android file manager for local storage, document providers, SMB shares, FTP/SFTP servers and Backblaze B2 buckets, built with Kotlin and Jetpack Compose.

Supports Android 8.0 (API 26) and later. Luna is in active development.


## Why Luna?

I've enjoyed Android file managers since the ES File Explorer days back in the 2010s, before switching to Solid Explorer and eventually MiXplorer. As privacy and open source software are important to me, however, I wanted an open source option with similar power and breadth of features as some of these more established options. I also wanted to be able to invest more into the features that I use most often and otherwise shape it around my workflows. Luna grew out of that need, and I hope others find it useful too.

Thanks to the developers of MiXplorer, Solid Explorer, Cx Explorer, the Fossify team, and developers of a huge number of other excellent file explorers for Android for their work and inspiration.

## AI Disclosure

I'm primarily a Python and Rust developer. I'm very new to Kotlin. I'm learning, but in the interim, Codex and Claude Code have contributed significantly to making this project possible.

## Features

- **Storage:** internal storage, removable drives, folders granted through Android's file picker, SMB 2/3 shares, FTP/FTPS and SFTP servers, and Backblaze B2 buckets.
- **Navigation:** tabs, bookmarks, breadcrumbs, search by name, size, date and the text inside files, list and grid layouts, and folder-specific sorting.
- **File operations:** queued copy and move, batch rename, drag and drop, conflict resolution, a recycle bin, and operation reports.
- **Stored procedures:** saved sequences of file operations, multiple sources, filename patterns, schedules, run history, and optional result notifications.
- **Archives:** browse and extract archives; create ZIP, TAR, GZIP and XZ archives, including encrypted ZIP files.
- **Viewers and editors:** images, PDF, EPUB, DOCX/PPTX previews, text and code editing with find and syntax highlighting, and a searchable hex viewer. Viewers can be offered to other apps through "Open with Luna".
- **Media:** audio and video playback, configurable video exit behavior, picture in picture, background play, track selection, external subtitles, playback speed, and subtitle appearance controls.
- **Tools:** storage analysis, duplicate detection, installed-app inspection, APK extraction, package installation, checksums and file metadata.
- **Appearance:** light and dark themes, accent colors, colorful icons, adjustable sizing, and a tablet sidebar.

## Build

Requirements:

- JDK 21 or 25.
- Android SDK Platform 37.0 and Build Tools 36.0.0.
- Android Studio compatible with Android Gradle Plugin 9.4.0, or the Android command-line tools.

The Gradle wrapper is included. Set the SDK location through Android Studio, `ANDROID_HOME`, or an untracked `local.properties` file:

```properties
sdk.dir=/path/to/Android/Sdk
```

Build a debug APK:

```sh
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. On Windows, use `gradlew.bat`. No API keys, server setup or application account is required to build or use local storage.

For an optimized APK signed with your machine's debug key:

```sh
./gradlew :app:assembleRelease -PsignReleaseWithDebugKey=true
```

The test APK is written to `app/build/outputs/apk/release/app-release.apk`. Release builds are unsigned by default; see [release signing](CONTRIBUTING.md#release-signing) for distribution builds.

For signed APKs attached to draft GitHub Releases, follow the [GitHub release setup](CONTRIBUTING.md#github-releases).

## Storage access and compatibility

Luna can request all-files access for shared storage or use individual folders granted through Android's file picker. It does not require root access. Android's restrictions on other apps' private data and protected folders still apply.

Android 11 and later close `Android/data` and `Android/obb` to file managers. With [Shizuku](https://shizuku.rikka.app/) installed and running, Luna can optionally browse and edit them through a small helper that Shizuku runs as the adb shell user (Settings, Storage access). The helper is part of Luna, runs only while Luna does, and touches nothing outside those folders.

Network passwords, SSH private keys and passphrases, and B2 application keys are stored encrypted using Android Keystore. Optional biometric or device-credential authentication controls access to the credential vault. Network thumbnails use a download budget set per provider, with a default of 25 MB per thumbnail. B2 folder listings are saved on the device and read again only on refresh; they can be cleared under Settings, Network, Backblaze B2.

FTP supports anonymous or password login, with plain FTP or explicit/implicit TLS. SFTP supports passwords and imported SSH private keys, including encrypted keys; approve the server’s SHA-256 host-key fingerprint when adding a connection under Settings → Network. Both providers support uploads, downloads, folder creation, rename, move, delete and existing-file replacement. Replacements keep the previous file under a backup name until the verified copy is published. FTP/FTPS asks for confirmation before overwriting or merging: FTP checks for name conflicts before uploading or renaming, but cannot prevent concurrent server changes between that check and the command.

File-operation capabilities depend on the storage provider. Copies are verified before completion; interrupted operations and cleanup failures are reported. Atomicity and recovery guarantees depend on the provider and filesystem.

Settings → Stored procedures saves ordered copy, move, permanent delete, rename, create-folder, create-archive and extract-archive actions. Sources can be specific files or folders, or folder contents matched by `*` and `?`, optionally including subfolders. Each step resolves its locations when it runs, so later steps can use folders created by earlier ones. A conflict set to Stop or a failed step stops the procedure; completed changes remain. Archive passwords are not stored in procedures.

Procedures run manually or on once, interval, daily or weekly schedules. Scheduling uses WorkManager without alarm permissions: due times are approximate, checked every 15 minutes, and Android can delay background work further. Runs never overlap for the same procedure. An unsuccessful or interrupted run pauses automatic execution until a manual run succeeds. History, cancellation and reports are available in the procedures page and operation queue. Success and failure notifications can be enabled separately. Procedures and their notification and schedule settings are included in settings exports; imported schedules start disabled. Scheduled runs use the same storage providers as the browser, including Shizuku when enabled and connected; network access still needs available credentials.

Media playback uses Android's codecs, so supported formats vary by device. Office documents are reading previews and may differ from their original layout. PDF text selection requires Android 15 or later. Legacy DOC/PPT files, protected Office documents and DRM-protected EPUBs require another app. The interface is currently available in English.

Leaving a video with Home or app switching uses picture in picture by default. Settings → Video player also offers Background play and Off; the player provides both actions directly and gesture switches under More → Gestures. Back/Close stops playback. Audio background play defaults on and can be changed in the player or Settings → Audio player.

## Development

| Location | Responsibility |
| --- | --- |
| `core/` | Storage contracts, file operations, archive handling and portable algorithms |
| `app/` | Android storage adapters, persistence, background work and Compose UI |
| `core/src/testFixtures/` | Shared storage-provider contract and test providers |
| `.github/workflows/` | Automated tests, lint and build verification |

`AppGraph` wires the application's dependencies. Storage adapters share provider contracts in `core`; file mutations pass through a persistent queue and an engine that stages, verifies and publishes their results. `BrowserViewModel` owns browser state and delegates file access, navigation and operations to focused collaborators.

Run the automated checks:

```sh
./gradlew :check :app:assembleRelease -PsignReleaseWithDebugKey=true
```

This runs core tests, Android JVM tests, source checks, Android lint and an optimized build with checks for reflective entry points after shrinking. Device tests run separately with `./gradlew :app:connectedDebugAndroidTest`.

See [CONTRIBUTING.md](CONTRIBUTING.md) for development conventions, testing and release signing. Report reproducible bugs and feature requests through [GitHub Issues](https://github.com/Andell4301/lunaexplorer/issues).

## License

Copyright (C) 2026 Luna Explorer contributors.

Luna Explorer is free software licensed under the **GNU General Public License, version 3 only** (`GPL-3.0-only`). You may redistribute and modify it under those terms. It is provided without warranty; see [LICENSE](LICENSE) for the full terms.

Third-party components retain their own licenses and notices, listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
