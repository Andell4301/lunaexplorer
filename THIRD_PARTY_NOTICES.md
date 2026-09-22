# Third-party notices

Luna Explorer uses the libraries below. Their licenses apply independently of
Luna's own license. Full license texts and notices are included in
[third-party-notices.txt](app/src/main/assets/third-party-notices.txt), which is
packaged in the APK as `assets/third-party-notices.txt`.

This inventory was checked against `app:releaseRuntimeClasspath` on
2026-09-21. The packaged text lists the exact resolved coordinates, including
transitive dependencies and platform metadata. The table groups related modules
for readability; release shrinking may remove unused code.

| Component | Resolved version(s) | License | Upstream |
| --- | --- | --- | --- |
| AndroidX and Jetpack Compose, including Material icons | Compose 1.12.0, Material 3 1.4.0, icons 1.7.8; other AndroidX module versions listed in the packaged inventory | Apache-2.0 | [AndroidX](https://android.googlesource.com/platform/frameworks/support/) |
| AndroidX Media3 / ExoPlayer, including Media3 Session | 1.11.0 | Apache-2.0 | [Media3](https://github.com/androidx/media) |
| Kotlin standard library and JDK compatibility modules | 2.2.10; JDK 7/8 modules 1.8.22 | Apache-2.0 | [Kotlin](https://github.com/JetBrains/kotlin) |
| Kotlin coroutines | 1.10.2 | Apache-2.0 | [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| Kotlin serialization | 1.9.0 | Apache-2.0 | [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) |
| SMBJ and ASN.1 support | SMBJ 0.14.0; asn-one 0.6.0 | Apache-2.0 | [SMBJ](https://github.com/hierynomus/smbj), [asn-one](https://github.com/hierynomus/asn-one) |
| SSHJ | 0.40.0 | Apache-2.0; bundled BSD-3-Clause and MIT notices | [SSHJ](https://github.com/hierynomus/sshj/tree/v0.40.0) |
| SMBJ-RPC / DCE-RPC | 0.12.1 | BSD-3-Clause | [SMBJ-RPC](https://github.com/rapid7/smbj-rpc/tree/dcerpc-0.12.1) |
| Bouncy Castle provider, PKIX and utilities | bcprov and bcutil 1.80.2; bcpkix 1.80 | Bouncy Castle License (MIT) | [Bouncy Castle](https://www.bouncycastle.org/) |
| MBassador | 1.3.0 | MIT | [MBassador](https://github.com/bennidi/mbassador/tree/1.3.0) |
| SLF4J API | 2.0.17 | MIT | [SLF4J](https://www.slf4j.org/) |
| Backblaze B2 SDK for Java (core) | 6.5.0 | MIT | [b2-sdk-java](https://github.com/Backblaze/b2-sdk-java/tree/v6.5.0) |
| Shizuku API (api, provider, aidl, shared) | 13.1.5 | MIT | [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) |
| Zip4j | 2.11.5 | Apache-2.0 | [Zip4j](https://github.com/srikanth-lingala/zip4j) |
| XZ for Java | 1.10 | 0BSD | [XZ for Java](https://github.com/tukaani-project/xz-java/tree/v1.10) |
| Apache Commons Compress, Codec, IO, Lang and Net | 1.28.0; 1.19.0; 2.21.0; 3.18.0; 3.13.0 | Apache-2.0 | [Apache Commons](https://commons.apache.org/) |
| junrar | 8.1.1 | UnRAR License | [junrar](https://github.com/junrar/junrar/tree/v8.1.1) |
| Metadata Extractor | 2.21.0 | Apache-2.0 | [Metadata Extractor](https://github.com/drewnoakes/metadata-extractor/tree/2.21.0) |
| Adobe XMP Core | 6.1.11 | BSD-3-Clause, as declared in release metadata | [Published artifact and POM](https://repo.maven.apache.org/maven2/com/adobe/xmp/xmpcore/6.1.11/) |
| APK Parser | 2.6.10 | BSD-2-Clause | [APK Parser](https://github.com/hsiafan/apk-parser/tree/v2.6.10) |
| mp3agic | 0.9.1 | MIT | [mp3agic](https://github.com/mpatric/mp3agic/tree/mp3agic-0.9.1) |
| Highlights | 1.1.0 | Apache-2.0 | [Highlights](https://github.com/SnipMeDev/Highlights) |
| Guava and failureaccess | 33.3.1-android; 1.0.2 | Apache-2.0 | [Guava](https://github.com/google/guava) |
| Java annotations: JSR-305, Error Prone, J2ObjC, JetBrains and JSpecify | 3.0.2; 2.28.0; 3.0.0; 23.0.0; 1.0.0 | Apache-2.0 | [JSR-305](https://code.google.com/archive/p/jsr-305/), [Error Prone](https://github.com/google/error-prone), [J2ObjC](https://github.com/google/j2objc), [JetBrains](https://github.com/JetBrains/java-annotations), [JSpecify](https://github.com/jspecify/jspecify) |
| Checker Framework qualifiers | 3.43.0 | MIT | [Checker Framework](https://github.com/typetools/checker-framework) |

Commons Net supplies FTP and FTPS. SSHJ supplies SFTP and SSH authentication,
with Bouncy Castle's PKIX and utility modules for private-key parsing.

The packaged notices preserve the five Apache Commons `NOTICE.txt` files,
SSHJ's upstream `NOTICE`, component-specific MIT/BSD notices, and the Media3 Sonic attribution. Common
Apache 2.0 terms appear once. Each copied text identifies its source artifact
or release URL.

junrar is distributed under the UnRAR license: free use in software that
handles RAR archives, but the code may not be used to re-create the RAR
compression algorithm. Luna reads RAR archives only. junrar's own SLF4J
dependency is excluded in favour of the SLF4J API version listed above.

Adobe XMP Core's entry identifies its verified vendor and metadata-declared
license, followed by standard BSD-3-Clause terms. It does not claim to reproduce
a copyright notice from its 6.1.11 JAR. SMBJ-RPC's release notice is preserved
as supplied, with standard BSD-3-Clause terms provided separately.

The repository also contains the Apache-2.0 Gradle wrapper. Keep its script
headers and the license embedded in `gradle/wrapper/gradle-wrapper.jar`. Build
and test dependencies are downloaded by Gradle and are outside this release
runtime inventory.

When changing dependencies, regenerate the resolved list with
`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`, check
the new artifacts' license and notice texts, and update both notice files.
Check the resulting APK contains `assets/third-party-notices.txt`; relying only
on library `META-INF` resources can lose notices during Android packaging.
