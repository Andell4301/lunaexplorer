# AGENTS.md

Instructions for AI coding agents working on Luna Explorer, an Android file manager in Kotlin and Jetpack Compose (local storage, SAF folders, SMB shares, Backblaze B2 buckets, archives, built-in viewers). Human contributors should start with [README.md](README.md) and [CONTRIBUTING.md](CONTRIBUTING.md).

## Ground rules

- **Never commit, push, stash, or switch branches.** Leave changes in the working tree; the maintainer reviews and commits.
- Do everything that was asked. If you think part of a request is a mistake, say so, but do not silently skip it.
- **User-visible text is terse and deliberate.** The app is for power users: no explainer subtitles, no helper prose. Do not reword existing labels, messages or error text while working on something else. UI tests find nodes by text, content description and test tag.
- **Paths and names are used exactly as typed.** No trimming, no normalising, no warnings about unusual characters.
- **Comments are rare and plain.** Write one only where a reader would otherwise be misled: a platform quirk, an ordering or threading constraint, a format detail, why the obvious approach is wrong. One line, literal, no storytelling, no history, no section banners, no KDoc that restates a name.
- Do not leave scratch files, probe tests or notes in the repository, even temporarily.
- A new dependency needs a reason. When adding or upgrading one, update [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and `app/src/main/assets/third-party-notices.txt`.

## Build and verify

Needs JDK 21 or 25, Android SDK Platform 37 and Build Tools 36.0.0. Use the Gradle wrapper.

```sh
./gradlew :core:test --tests '*OperationEngineTest'          # one core suite
./gradlew :app:testDebugUnitTest --tests '*NavigationTest'   # one app suite
./gradlew :check                                             # hygiene + all JVM tests + lint
./gradlew :app:assembleRelease -PsignReleaseWithDebugKey=true
```

- Iterate with `--tests` filters; the full app suite takes several minutes.
- **Before calling work finished, run `:check` and the release assemble above.** The release build runs R8 and then `checkReleaseReflection`, so a change can pass every test and still break only there. Lint errors fail `:check`.
- Run one Gradle invocation at a time in this checkout.
- Anything reached only by reflection (WorkManager workers, the SLF4J provider, smbj's event-bus handlers, the B2 SDK's JSON structures, the Shizuku file helper) needs a keep rule in `app/proguard-rules.pro` **and** an entry in `checkReleaseReflection` in `app/build.gradle.kts`.
- `:checkSourceHygiene` rejects a raw NUL byte in Kotlin source. Write it as `\u0000`; some editing tools turn the escape into the byte.
- There is usually no device attached. JVM tests do not establish device behaviour (storage grants, codecs, real SMB servers, background limits); say so rather than claiming it works.

## Layout

| Path | What lives there |
| --- | --- |
| `core/` | Pure Kotlin/JVM, no Android. Storage contracts (`Storage.kt`), the copy/move/delete engine (`Operations.kt`), archives (`Archives.kt`, `ArchiveProvider.kt`, `ArchiveReaders.kt`, `ArchiveRewrite.kt`), batch rename, search, sizing, digests, metadata, `ReadBudget`, the hex viewer's rows, blocks and search (`Hex.kt`). |
| `core/src/testFixtures/` | `MemoryStorageProvider` and `StorageProviderContract`, shared with the app's tests. |
| `app/.../AppGraph.kt` | All dependency wiring, by hand. `LunaApplication` owns the one graph. |
| `app/.../storage/` | Android adapters: `LocalStorageProvider`, `SafStorageProvider`, `smb/`, `b2/`, `shizuku/` (the helper that reaches Android/data), thumbnails, media index, APK install, stream and documents providers, credential vault. |
| `app/.../data/` | SQLite (`LunaDatabase`: session, operation queue and journal, trash, open-with defaults), `SessionCodec`, settings export/import (`SettingsRegistry`, `SettingsTransfer`). |
| `app/.../model/` | State types with no Compose dependency: `BrowserState`, `Preferences`, navigation, overlays. |
| `app/.../work/` | `OperationQueue` and `OperationWorker` (WorkManager, foreground). |
| `app/.../playback/` | Background play: `BackgroundPlayback` (the published session), `PlaybackSession` (the viewer's half) and `PlaybackService` (`MediaSessionService`, foreground). |
| `app/.../ui/` | Everything Compose, in one package so screens share `internal` pieces. `BrowserViewModel` owns navigation, listings, search and the exposed `StateFlow`; its collaborators (`BrowserFiles`, `BrowserOperations`, `Bookmarks`, `StorageTools`, `SettingsTransfer`, …) each own one job and share its scope. |

`core` must not depend on `app` or on Android. Its tests run with `--limit-modules`, so `java.awt`, `javax.imageio` and other desktop-only APIs fail there on purpose.

## Invariants

**Storage providers**
- A `NodeRef` is opaque. Never derive a parent or a filesystem path from one; ask the provider (`parentOf`) or `PathAddressable`.
- `PathAddressable.pathOf` is a path the caller may open itself, and is null for a file only the provider can open (Android/data through the helper). Use `shownPathOf` for anything that only displays or compares a path. Opening a path from `shownPathOf` is a bug.
- The registry's `local` provider is `AssistedLocalProvider`: it sends Android/data and Android/obb to the Shizuku helper while one is connected, under the local provider's own references. `AppGraph.local` is the plain provider, for mapping paths only; open files through the registry.
- Behaviour is driven by what an entry or provider declares (`Capability`, `Feature`), not by provider type. Keep abstractions provider-neutral: more cloud providers are planned.
- A new provider runs `StorageProviderContract` and adds tests only for what is specific to it.
- A provider that implements `CachedListings` answers `list` from its saved copy and never refetches on its own. Only the user's refresh (`BrowserViewModel.refresh`) and the provider's own mutations forget a listing; `list(complete = true)` always asks the server, so the engine never acts on a saved one.
- On `Feature.VERSIONED` storage an overwrite keeps what it covers as an older version. Removing a name (a delete, a move's or rename's source) removes every version unless the request carries `keepVersions`, which hides instead. The engine applies that only to what the user asked removed, never to its own staging data. The recycle bin does not apply there.
- Search walks listings folder by folder unless the provider implements `DeepListing`, which hands over a whole tree at once. B2 does: a request there lists a thousand objects at any depth but only one folder, so it reads saved listings where it has them and sweeps the rest, saving each folder it completes. Text inside files is searched on this device only, never on `Feature.NETWORK` storage.
- On `Feature.PREFIX_FOLDERS` storage a folder is a name prefix that goes with its last object. The engine writes folders in place there instead of staging them, and a failed `relocate` is reported rather than retried as a copy, because it may be half done.

**Mutations**
- Copy, move, delete, rename, archive create/extract and bin operations go through `OperationQueue` to `OperationEngine`. Do not mutate storage directly from UI code. In-place file edits go through `BrowserFiles`, which also invalidates the listing cache.
- The engine's protocol is the safety guarantee; do not shortcut it: stage under a journalled name, reread and SHA-256 verify, publish without replacing anything by default, and for a move delete the source only after the copy is verified. Overwrite needs `ATOMIC_REPLACE` or `REPLACE` on the destination and is file-onto-file only. Folders merge; they are never replaced.
- Never recover by deleting, never automatically replay an interrupted mutation, never retry a mutation whose outcome is unknown. Report what was left behind instead.
- The recycle bin is a move into `.LunaTrash` on the item's own storage. Trash records are staged before the move and reconciled against its outcomes; each records the item's own parent folder, not the folder on screen.

**Coroutines**
- Blocking storage and parsing work runs off the main thread. Streams, players, renderers and staged files have an explicit owner that closes them.
- Never read a proxy file descriptor served by the same process: a killed reader can remain stuck in the kernel waiting for its dead callback. Internal playback and previews read providers directly; self-reads of exported URIs use pipes.
- **Never swallow `CancellationException`.** `runCatching { someSuspendCall() }` is a bug: a cancelled load gets treated as a failure, and anything that caches failures then poisons itself. Catch `Exception`, call `ensureActive()` first, then handle the real error.
- Late results must not overwrite newer state. The ViewModel guards listings and searches with generation checks; keep them when touching navigation.
- A search or category view belongs to its tab and keeps collecting while another tab is in front; only the front tab's session publishes into the state.
- Network reads made for previews carry a `ReadBudget`. Do not add an unbudgeted read path for thumbnails.

**State and settings**
- **Every setting can be exported and imported.** Give anything the user can configure a unit in `SettingsRegistry` (or an entry in its `excluded` map, with the reason). `SettingsRegistryCoverageTest` enforces this for fields of `Preferences`, `SubtitleAppearance` and the session document; a setting stored anywhere else (a new table, a file) escapes that test, so add its unit by hand.
- **Every setting can be changed from the Settings screen.** A control elsewhere in the UI (a folder menu, the player, a pinch) is a shortcut to a setting, never the only place it can be changed. Nothing enforces this; check it when adding one.
- What belongs to one kind of network storage (its accounts, its thumbnail limit, anything only it has) lives on that provider's own page under Settings → Network; add a `NetworkKind` for a new provider. The credential vault is shared and stays on the Network page itself.
- `SessionCodec` decides what is persisted.
- A locked vault's key answers only for a short while after the user authenticates, even while the vault is open. Anything that writes a secret goes through `withVault`, which asks again when `VaultAccess.usable()` says the key has stopped answering.
- `FileHelperService` runs in a process Shizuku starts as the shell user, from Luna's APK but with no `LunaApplication`, graph, database or log. Keep it to `LocalStorageProvider`, `java.nio` and `android.system.Os`. Raise `Wire.PROTOCOL` with any change to `IFileHelper` or to what its strings carry, and only ever append transaction codes. All of it is for Android 11 and later, where the setting's switch is; nothing may turn the setting on below that. A screen that explains a closed folder goes by `View.Folder.closedToApps`, which the provider decides, not by the look of the path.
- SMB passwords and B2 application keys live only in `CredentialVault`. They are never serialised with accounts and are exported only after authentication. No token, upload URL or key goes into a log line or an exception message.
- The player belongs to the media viewer's composition. `PlaybackService` only shows the session `BackgroundPlayback` holds; it never builds a player and stops itself without one. Closing the viewer withdraws and releases the session, then releases the player. The service runs only while Luna is off screen, which `serviceWanted` alone decides.
- Only the media viewer arms picture in picture, through `LocalPictureInPicture`, and it disarms when it leaves. `MainActivity.startActivityForResult` disarms auto-enter for anything Luna launches itself.

## Compose conventions

- The settings screen, the viewers and several tools are `Dialog`s, each with its own window. Back, `BackHandler` and the system bars belong to that window, not the Activity's.
- `showMessage` draws in the browser's window, or in the settings window while that is open. A `Dialog` on top of either (the account editors) hides it, so such a dialog shows its own failures inline. A failure nobody can see reads as a button that does nothing.
- Build settings editors inline or as a sub-page. A text field in an `AlertDialog` nested inside the settings `Dialog` never reaches idle, and cannot be tested.
- UI zoom works by scaling `LocalDensity`. Any pixel size derived from density changes on every zoom step, and `pointerInput` restarts when density changes, which is why the pinch handler sits above the scaled theme.
- Large text is never put in a single text field (that caused an ANR). Use the row-based `TextDocument` in `LazyText.kt` for anything big.
- Text the editor derives from a file (a decoded manifest, compiled XML) is `EditorContent.derived`: always read-only rows, and never saved over the file it came from.
- In picture in picture the media viewer composes the picture only. Its chrome's dialogs are windows of their own and would draw inside the small window.

## Tests

| Layer | Where | Use it for |
| --- | --- | --- |
| Portable logic | `core/src/test` | Engine, archives, naming, search, budgets |
| Provider contract | `core/src/testFixtures` | The same contract against memory, local, SAF, SMB and B2 providers |
| Android services | `app/src/test/.../storage`, `data`, `work`, `debug` | Adapters, persistence and logging under Robolectric |
| Browser state | `app/src/test/.../ui` with `BrowserViewModelHarness` | Navigation, selection, caches, search, settings; no Activity |
| Screens | `app/src/test/.../ui`, subclasses of `RobolectricBrowserUiTest` | Real taps, dialogs, gestures and Back |
| Shared smoke | `app/src/sharedTest` | UI to queue to filesystem, run on the JVM and on a device |

- Put a regression at the lowest layer that reproduces it. Write the failing test first when fixing a bug.
- Test observable behaviour. Do not test constants, enum order, defaults, data-class copies, library behaviour, exact prose, or something another layer already covers.
- Names: backticked sentences in JVM-only tests; camelCase in anything built on the Activity fixture, `sharedTest` or `androidTest`, which must also compile for a device.
- **Under `BrowserViewModelHarness`, queued operations do not run.** `OperationWorker` uses the application's graph, while the harness builds its own on the same database, so a harness test may assert that an operation was queued (title, type) and nothing about its outcome. To test what an operation actually did, subclass `RobolectricBrowserUiTest`, where the ViewModel and the worker share `fixture.graph`, and wait with `awaitCondition`.
- Tests that poll with `harness.awaitUntil` can fail under heavy machine load. Re-run the test alone before treating a failure as a regression, especially when its subject is unrelated to your change.
- Bitmap tests need `@GraphicsMode(GraphicsMode.Mode.NATIVE)`.
