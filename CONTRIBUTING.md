# Contributing to Luna Explorer

Bug reports, focused fixes and improvements are welcome. For substantial changes, open an issue first to discuss the intended behavior and scope.

## Development setup

Follow the [build instructions](README.md#build) and use the checked-in Gradle wrapper. Dependencies and toolchain versions are pinned in the Gradle files. Keep local SDK paths, credentials, signing keys and generated files out of version control.

The app targets Java 17 bytecode. The `app` module uses Android Gradle Plugin's built-in Kotlin support; the `core` module uses the Kotlin JVM plugin.

## Code changes

- Follow Kotlin's official style and the repository's `.editorconfig`. Keep formatting changes limited to the code being modified.
- Keep portable algorithms and storage contracts in `core`; Android APIs and UI belong in `app`.
- Run blocking storage and parsing work off the main thread. Propagate coroutine cancellation and close streams, handles and viewers when their work ends.
- Treat storage capabilities as provider-specific. Recheck permissions and identity before mutation, preserve existing files on failure, and report incomplete work accurately.
- Preserve file names and paths exactly, including Unicode and invisible characters. Keep validation focused on constraints imposed by the destination storage.
- Add tests for observable behavior and failure cases. Avoid tests that repeat source text, menu labels or implementation details without protecting a contract.
- Keep dependencies focused. Check Android compatibility, license notices, APK size and lifecycle requirements before adding one.

## Testing

Run checks for the affected area during development:

```sh
./gradlew :core:test --tests '*ArchiveEngineTest'
./gradlew :app:testDebugUnitTest --tests '*NavigationTest'
```

Before submitting a change, run the complete verification:

```sh
./gradlew --no-daemon --max-workers=1 :check \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
```

The root `check` task runs source hygiene, core JVM tests, Android JVM tests and Android lint. Release assembly additionally checks that R8 preserves required reflective constructors, SMB event handlers and the B2 SDK's JSON structures. `assembleDebugAndroidTest` compiles device tests; it does not execute them.

With an emulator or device connected:

```sh
./gradlew :app:connectedDebugAndroidTest
```

Storage grants, removable drives, NAS disconnections, vendor codecs, accessibility and background execution require device testing. Include the device, Android version and relevant storage provider in your test notes. A passing JVM suite does not establish those platform behaviors.

Provider implementations should run the shared contract in `core/src/testFixtures` and add tests for provider-specific behavior. UI tests should exercise actual interaction; state-only tests should avoid Activity setup where practical.

## Pull requests and bug reports

Explain the problem, the resulting behavior and how the change was verified. Include reproduction steps or a regression test where possible. Keep unrelated refactoring separate.

For a bug report, include the app version, Android version, storage type, expected result and actual result. For layout problems, include a screenshot when useful. Review diagnostic logs before sharing them: paths, server addresses and file names may contain personal information.

## Release signing

`./gradlew :app:assembleRelease` produces an unsigned optimized APK at `app/build/outputs/apk/release/app-release-unsigned.apk`.

For an installable local test build, opt into debug signing:

```sh
./gradlew :app:assembleRelease -PsignReleaseWithDebugKey=true
```

The output is `app/build/outputs/apk/release/app-release.apk`. Debug keys are for testing and can differ between development machines.

For a distribution build, supply all four environment variables through your environment or a CI secret store:

| Variable | Value |
| --- | --- |
| `LUNA_KEYSTORE_FILE` | Keystore path, absolute or relative to the repository root |
| `LUNA_KEYSTORE_PASSWORD` | Keystore password |
| `LUNA_KEY_ALIAS` | Signing-key alias |
| `LUNA_KEY_PASSWORD` | Signing-key password |

Run `./gradlew :app:assembleRelease` without the debug-signing flag. The signed APK is written to `app/build/outputs/apk/release/app-release.apk`. The build rejects incomplete signing configuration or conflicting signing modes.

Use a consistent signing identity for updates. Keep the matching `app/build/outputs/mapping/release/mapping.txt` with each distributed APK so obfuscated crash reports can be decoded. Include the dependency notices packaged in the app when redistributing it.

The CI workflow builds unsigned release APKs and requires no signing secrets. It verifies changes; it does not publish releases.

## GitHub releases

The [release workflow](.github/workflows/release.yml) runs when a `v*` tag is pushed. It runs `:check`, builds with the [release signing configuration](#release-signing), and checks the optimized APK's reflective entry points. It requires the tag to equal `v<versionName>` from the built APK's `output-metadata.json` and verifies that the APK's signing certificate SHA-256 matches the configured keystore alias.

### Set up the signing key

If a release has already been distributed, reuse its signing key so installed copies can update. Otherwise, generate a key once with the JDK's `keytool`, choosing a path outside the repository:

```sh
keytool -genkeypair -v -keystore /path/to/luna-release.jks \
  -storetype JKS -alias luna -keyalg RSA -keysize 4096 -validity 10000
```

Enter the keystore password, certificate details and key password at the prompts. Back up the keystore, alias and passwords securely; future releases need the same signing identity. See Android's [app signing guide](https://developer.android.com/studio/publish/app-signing) for key management details.

In the GitHub repository's **Settings → Secrets and variables → Actions**, add these repository secrets:

| Secret | Value |
| --- | --- |
| `LUNA_KEYSTORE_BASE64` | Base64-encoded contents of the keystore |
| `LUNA_KEYSTORE_PASSWORD` | Keystore password |
| `LUNA_KEY_ALIAS` | Signing-key alias, `luna` for the command above |
| `LUNA_KEY_PASSWORD` | Signing-key password |

With an authenticated GitHub CLI, run these commands from the repository on Linux or macOS. The password and alias commands prompt for their values:

```sh
base64 < /path/to/luna-release.jks | gh secret set LUNA_KEYSTORE_BASE64
gh secret set LUNA_KEYSTORE_PASSWORD
gh secret set LUNA_KEY_ALIAS
gh secret set LUNA_KEY_PASSWORD
```

All four secrets are required; the release workflow never falls back to debug signing. It decodes the keystore into the runner's temporary directory, supplies the signing environment only to the build step, and removes the keystore afterward. No GitHub environment is required. See GitHub's [Actions secrets guide](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets) for access and management.

### Prepare a release

1. Increase `versionCode` for every release and set `versionName` in `app/build.gradle.kts`.
2. Commit and push the reviewed changes, including the release workflow.
3. Tag that commit with `v` followed by the exact `versionName`, then push the tag. For example, for `versionName = "1.0.0"`:

   ```sh
   git tag -a v1.0.0 -m "Luna Explorer 1.0.0"
   git push origin v1.0.0
   ```

The workflow creates a **draft** GitHub Release with these assets, using the APK's version name:

- `LunaExplorer-1.0.0.apk` — signed, optimized APK.
- `LunaExplorer-1.0.0.apk.sha256` — APK checksum.
- `LunaExplorer-1.0.0-mapping.txt` — R8 mapping for crash reports.
- `LunaExplorer-1.0.0-signing-certificate.txt` — signature verification and certificate fingerprints from `apksigner verify --verbose --print-certs`.

Review the draft's notes and assets, test the APK on a device, and provide the [corresponding source instructions](#licensing) before publishing it in GitHub. The workflow does not overwrite an existing draft or published release.

If a run fails before creating the draft, rerun it after fixing the failure. If asset upload fails after draft creation, download the `signed-release-v<versionName>` workflow artifact and finish uploading its files to the draft manually. Alternatively, delete only the unfinished draft, keep its tag, and rerun the workflow. Do not replace assets on a published release.

## Licensing

Submit contributions under the project's [GPL-3.0-only license](LICENSE). Preserve existing copyright notices and the separate licenses of third-party components.

When distributing an APK, provide access to its matching corresponding source under the GPL, including the required build scripts and non-system dependency sources. A project-only source archive is not a substitute for any required dependency sources. Keep clear source-download instructions alongside the APK and retain its license and dependency notices.
