plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val releaseSigningVariables = listOf(
    "LUNA_KEYSTORE_FILE", "LUNA_KEYSTORE_PASSWORD", "LUNA_KEY_ALIAS", "LUNA_KEY_PASSWORD",
)
val releaseSigningValues = releaseSigningVariables.associateWith { providers.environmentVariable(it).orNull }
val hasReleaseSigning = releaseSigningValues.values.any { it != null }
require(!hasReleaseSigning || releaseSigningValues.values.all { !it.isNullOrEmpty() }) {
    "Release signing requires all four environment variables: ${releaseSigningVariables.joinToString()}."
}
val signReleaseWithDebugKey = providers.gradleProperty("signReleaseWithDebugKey").orNull?.let {
    requireNotNull(it.toBooleanStrictOrNull()) { "signReleaseWithDebugKey must be true or false." }
} ?: false
require(!hasReleaseSigning || !signReleaseWithDebugKey) {
    "Choose either release signing credentials or signReleaseWithDebugKey, not both."
}

android {
    namespace = "com.lunaexplorer.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.lunaexplorer.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.3.0-dev.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if (hasReleaseSigning) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(requireNotNull(releaseSigningValues["LUNA_KEYSTORE_FILE"]))
            storePassword = releaseSigningValues["LUNA_KEYSTORE_PASSWORD"]
            keyAlias = releaseSigningValues["LUNA_KEY_ALIAS"]
            keyPassword = releaseSigningValues["LUNA_KEY_PASSWORD"]
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = when {
                hasReleaseSigning -> signingConfigs.getByName("release")
                signReleaseWithDebugKey -> signingConfigs.getByName("debug")
                else -> null
            }
        }
    }
    lint { abortOnError = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets {
        getByName("test").kotlin.directories += "src/sharedTest/java"
        getByName("androidTest").kotlin.directories += "src/sharedTest/java"
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
    reportsDestination = layout.buildDirectory.dir("compose-reports")
    metricsDestination = layout.buildDirectory.dir("compose-reports")
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.work:work-runtime-ktx:2.10.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.hierynomus:smbj:0.14.0")
    implementation("commons-net:commons-net:3.13.0")
    implementation("com.hierynomus:sshj:0.40.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80.2")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    // smbj logs through SLF4J; LunaSlf4jProvider routes it to the debug log.
    implementation("org.slf4j:slf4j-api:2.0.9")
    // SMB share enumeration (srvsvc over DCE/RPC).
    implementation("com.rapid7.client:dcerpc:0.12.1") {
        // smbj brings the newer BouncyCastle artifact; both together produce duplicate classes.
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    }
    // B2 client. Core only: the published transport needs Apache HttpClient, which Android dropped.
    implementation("com.backblaze.b2:b2-sdk-core:6.5.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.biometric:biometric:1.1.0")
    // Biometric pulls an old Fragment that predates the Activity Result API.
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")
    implementation("dev.snipme:highlights:1.1.0")
    testImplementation("junit:junit:4.13.2")
    // Archive fixtures with non-UTF-8 filename encodings and Unicode extra fields.
    testImplementation("org.apache.commons:commons-compress:1.28.0")
    testImplementation(testFixtures(project(":core")))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("androidx.work:work-testing:2.10.4")
    testImplementation(platform("androidx.compose:compose-bom:2026.08.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
tasks.withType<Test>().configureEach {
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
}

// JVM tests cannot detect entry points removed by R8.
val checkReleaseReflection = tasks.register("checkReleaseReflection") {
    group = "verification"
    description = "Checks release DEX constructors, callbacks and class names required by reflection."
    dependsOn("minifyReleaseWithR8")
    val dexDir = layout.buildDirectory.dir("intermediates/dex/release/minifyReleaseWithR8")
    val sdk: File = androidComponents.sdkComponents.sdkDirectory.get().asFile
    val buildTools = android.buildToolsVersion
    inputs.dir(dexDir)
    doLast {
        val executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "dexdump.exe" else "dexdump"
        val dexdump = File(sdk, "build-tools/$buildTools/$executable")
        require(dexdump.isFile) { "dexdump not found at $dexdump" }
        val required = listOf(
            "Landroidx/work/impl/WorkDatabase_Impl;" to "()V",
            "Lcom/lunaexplorer/app/work/OperationWorker;" to "(Landroid/content/Context;Landroidx/work/WorkerParameters;)V",
            "Lcom/lunaexplorer/app/work/ProcedureScheduleWorker;" to "(Landroid/content/Context;Landroidx/work/WorkerParameters;)V",
            "Landroidx/work/WorkManagerInitializer;" to "()V",
            "Lcom/lunaexplorer/app/LunaApplication;" to "()V",
            "Lcom/lunaexplorer/app/MainActivity;" to "()V",
            "Lcom/lunaexplorer/app/playback/PlaybackService;" to "()V",
            // MBassador (smbj's event bus) builds handler invocations reflectively.
            "Lnet/engio/mbassy/dispatch/ReflectiveHandlerInvocation;" to "(Lnet/engio/mbassy/subscription/SubscriptionContext;)V",
            // Instantiated by SLF4J from the slf4j.provider property.
            "Lcom/lunaexplorer/app/debug/LunaSlf4jProvider;" to "()V",
            // B2Json builds the body of b2_authorize_account through this constructor.
            "Lcom/backblaze/b2/client/B2StorageClientWebifierImpl\$Empty;" to "()V",
            // Shizuku builds the helper by reflection, with a Context where its version offers one.
            "Lcom/lunaexplorer/app/storage/shizuku/FileHelperService;" to "()V",
            "Lcom/lunaexplorer/app/storage/shizuku/FileHelperService;" to "(Landroid/content/Context;)V",
            "Lrikka/shizuku/ShizukuProvider;" to "()V",
            // Bouncy Castle builds algorithm mappings and implementations by registered class name.
            "Lorg/bouncycastle/jce/provider/BouncyCastleProvider;" to "()V",
            "Lorg/bouncycastle/jcajce/provider/asymmetric/EdEC\$Mappings;" to "()V",
            "Lorg/bouncycastle/jcajce/provider/asymmetric/RSA\$Mappings;" to "()V",
            "Lorg/bouncycastle/jcajce/provider/asymmetric/DH\$Mappings;" to "()V",
            "Lorg/bouncycastle/jcajce/provider/asymmetric/edec/KeyFactorySpi\$Ed25519;" to "()V",
            "Lorg/bouncycastle/jcajce/provider/asymmetric/edec/KeyPairGeneratorSpi\$X25519;" to "()V",
            "Lorg/bouncycastle/jcajce/provider/asymmetric/edec/SignatureSpi\$Ed25519;" to "()V",
            "Lorg/bouncycastle/jcajce/provider/asymmetric/edec/KeyAgreementSpi\$X25519;" to "()V",
        )
        val dexes = dexDir.get().asFile.listFiles { f -> f.extension == "dex" }.orEmpty()
        require(dexes.isNotEmpty()) { "No DEX files under $dexDir" }
        val dump = StringBuilder()
        dexes.forEach { dex ->
            val process = ProcessBuilder(dexdump.absolutePath, dex.absolutePath).redirectErrorStream(true).start()
            dump.append(process.inputStream.bufferedReader(Charsets.ISO_8859_1).readText())
            check(process.waitFor() == 0) { "dexdump failed for $dex" }
        }
        val text = dump.toString()
        fun classBody(cls: String): String {
            val at = text.indexOf("Class descriptor  : '$cls'")
            if (at < 0) return ""
            val end = text.indexOf("Class descriptor", at + 1).takeIf { it >= 0 } ?: text.length
            return text.substring(at, end)
        }
        fun hasMethod(cls: String, name: String, signature: String): Boolean =
            Regex("""name\s+: '${Regex.escape(name)}'\s+type\s+: '${Regex.escape(signature)}'""")
                .containsMatchIn(classBody(cls))
        val missing = required.filterNot { (cls, ctor) ->
            hasMethod(cls, "<init>", ctor)
        }
        require(missing.isEmpty()) {
            "Release DEX is missing reflectively-called constructors: $missing (check the keep rules)"
        }
        // Private smbj handlers the event bus calls to evict closed sessions and trees.
        val handlers = mapOf(
            "Lcom/hierynomus/smbj/session/Session;" to ("disconnectTree" to "(Lcom/hierynomus/smbj/event/TreeDisconnected;)V"),
            "Lcom/hierynomus/smbj/connection/Connection;" to ("sessionLogoff" to "(Lcom/hierynomus/smbj/event/SessionLoggedOff;)V"),
            "Lcom/hierynomus/smbj/SMBClient;" to ("connectionClosed" to "(Lcom/hierynomus/smbj/event/ConnectionClosed;)V"),
        )
        val missingHandlers = handlers.filterNot { (cls, method) ->
            hasMethod(cls, method.first, method.second)
        }
        require(missingHandlers.isEmpty()) {
            "Release DEX is missing smbj event handlers in ${missingHandlers.keys}; closed trees would stay cached"
        }
        val fields = mapOf(
            // SevenZDictionaries fits 7z dictionaries to their content through this one.
            "Lorg/apache/commons/compress/archivers/sevenz/SevenZFile;" to "archive",
            // B2Json uses these field names as the JSON member names.
            "Lcom/backblaze/b2/client/structures/B2FileVersion;" to "fileName",
            "Lcom/backblaze/b2/client/structures/B2Bucket;" to "bucketName",
            "Lcom/backblaze/b2/client/structures/B2AccountAuthorization;" to "authorizationToken",
            "Lcom/backblaze/b2/client/structures/B2StartLargeFileRequest;" to "bucketId",
            "Lcom/backblaze/b2/client/structures/B2FinishLargeFileRequest;" to "partSha1Array",
            "Lcom/backblaze/b2/client/structures/B2UploadPartUrlResponse;" to "uploadUrl",
            "Lcom/backblaze/b2/client/structures/B2ErrorStructure;" to "code",
        )
        val missingFields = fields.filterNot { (cls, field) ->
            Regex("""name\s+: '${Regex.escape(field)}'""").containsMatchIn(classBody(cls).substringBefore("Direct methods"))
        }
        require(missingFields.isEmpty()) {
            "Release DEX is missing reflectively-read fields $missingFields (check the keep rules)"
        }
        // B2Bucket and B2StartLargeFileRequest name their constructor parameters only through
        // MethodParameters, which only the annotation dump shows.
        val annotations = StringBuilder()
        dexes.forEach { dex ->
            val process = ProcessBuilder(dexdump.absolutePath, "-a", dex.absolutePath).redirectErrorStream(true).start()
            annotations.append(process.inputStream.bufferedReader(Charsets.ISO_8859_1).readText())
            check(process.waitFor() == 0) { "dexdump -a failed for $dex" }
        }
        require(Regex("""Ldalvik/annotation/MethodParameters;.*"bucketName".*"fileLockConfiguration"""").containsMatchIn(annotations)) {
            "Release DEX has no parameter names for B2 SDK constructors; listing buckets and starting large uploads would fail"
        }
        // Library names used for readable SMB logs and SSHJ log suppression.
        val named = listOf(
            "Lcom/hierynomus/protocol/commons/concurrent/Promise;",
            "Lcom/hierynomus/smbj/transport/tcp/direct/DirectTcpTransport;",
            "Lnet/schmizz/sshj/transport/Decoder;",
            "Lcom/hierynomus/sshj/userauth/keyprovider/OpenSSHKeyV1KeyFile;",
        )
        val renamed = named.filterNot { text.contains("Class descriptor  : '$it'") }
        require(renamed.isEmpty()) {
            "Release DEX has renamed library classes $renamed; log names and suppression would fail"
        }
        require(classBody("Lorg/bouncycastle/openssl/PEMDecryptor;").isNotEmpty()) {
            "Release DEX is missing SSHJ's encrypted PEM availability check"
        }
        println("Release reflection check: ${required.size} constructors, ${handlers.size} event handlers, ${fields.size} fields and ${named.size} library class names present")
    }
}
// assembleRelease only exists once AGP has created the release variant.
androidComponents.onVariants(androidComponents.selector().withBuildType("release")) {
    project.afterEvaluate {
        tasks.named("assembleRelease") { dependsOn(checkReleaseReflection) }
    }
}
