# R8 rules for reflective entry points and optional dependencies.

-keep class com.lunaexplorer.app.work.ProcedureScheduleWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Room (via WorkManager)
# Room 2.6.1's consumer rule omits constructors required by reflective database initialization.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# apk-parser
# JSR-305 annotations are optional and not required at runtime.
-dontwarn javax.annotation.**
-dontwarn javax.annotation.meta.**

# commons-compress
# SevenZDictionaries reads the parsed 7z header through this private field by name.
-keep class org.apache.commons.compress.archivers.sevenz.SevenZFile { private final *** archive; }
# Ignore unavailable optional codecs and desktop Java APIs.
-dontwarn org.apache.commons.compress.**
-dontwarn org.brotli.**
-dontwarn com.github.luben.zstd.**
-dontwarn java.beans.**

# mp3agic
-keep class com.mpatric.mp3agic.** { *; }
-dontwarn com.mpatric.mp3agic.**

# zip4j
-dontwarn net.lingala.zip4j.**

# junrar
# Logs through SLF4J; optional integrations are absent on Android.
-dontwarn com.github.junrar.**

# metadata-extractor
# Directory implementations are discovered by type when a file is read.
-keep class com.drew.metadata.** { *; }
-dontwarn com.drew.**
-dontwarn com.adobe.internal.xmp.**

# smbj
# Luna uses NTLM. Kerberos, SPNEGO, and optional dependency hooks are unused on Android.
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn javax.el.**
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**

# dcerpc
# Android lacks this exception type; share enumeration handles the resulting linkage error.
-dontwarn java.rmi.UnmarshalException

# SMBJ event bus
# MBassador finds @Handler methods by reflection. Without them, close events never evict cached
# trees, sessions and connections, and closed handles get reused.
-keep class com.hierynomus.smbj.event.** { *; }
-keepclasseswithmembers class com.hierynomus.** { @net.engio.mbassy.listener.Handler <methods>; }
-keep class net.engio.mbassy.** { *; }

# Backblaze B2 SDK
# B2Json uses field names as the JSON member names and builds objects through annotated
# constructors found by reflection. B2Bucket and B2StartLargeFileRequest name their constructor
# parameters only through the MethodParameters attribute.
-keepattributes MethodParameters
-keep class com.backblaze.b2.client.structures.** { *; }
-keep class com.backblaze.b2.json.B2Json$* { *; }
-keepclassmembers enum com.backblaze.b2.** { *; }
# The body of b2_authorize_account, the one B2Json class outside structures. Without it no
# account can be authorized.
-keep class com.backblaze.b2.client.B2StorageClientWebifierImpl$Empty { *; }

# Shizuku
# Shizuku starts the helper in a process of its own, finding the class by name and building it by
# reflection: with a Context where its version offers one, otherwise with nothing.
-keep class com.lunaexplorer.app.storage.shizuku.FileHelperService {
    public <init>();
    public <init>(android.content.Context);
}

# Debug log
# SLF4J reflectively calls the no-argument constructor of the provider set in slf4j.provider.
-keep class com.lunaexplorer.app.debug.LunaSlf4jProvider { public <init>(); }
# Keep SMB class names readable in log tags; unused classes can still be removed.
-keepnames class com.hierynomus.** { }

# SSHJ loads Bouncy Castle algorithm mappings and implementations by their registered names.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
# The SLF4J bridge suppresses SSHJ's raw packet and key parser logs by package name.
-keepnames class net.schmizz.** { }
# SSHJ checks for this class before reading encrypted legacy PEM keys.
-keep interface org.bouncycastle.openssl.PEMDecryptor { *; }
