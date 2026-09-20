plugins {
    id("com.android.application") version "9.4.0" apply false
    kotlin("jvm") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
}

val checkSourceHygiene = tasks.register("checkSourceHygiene") {
    group = "verification"
    description = "Rejects raw NUL bytes that make Kotlin files appear binary to editors."
    val sources = files(fileTree("app/src") { include("**/*.kt") }, fileTree("core/src") { include("**/*.kt") })
    inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)
    doLast {
        val offenders = sources.files.mapNotNull { file ->
            val bytes = file.readBytes()
            val at = bytes.indexOf(0)
            if (at < 0) null else {
                val line = bytes.take(at).count { it == '\n'.code.toByte() } + 1
                "${file.relativeTo(rootDir)}:$line"
            }
        }
        check(offenders.isEmpty()) { "Write NUL as \\u0000 in Kotlin source: ${offenders.joinToString()}" }
    }
}

tasks.register("check") {
    group = "verification"
    description = "Runs source hygiene, core and Android JVM tests, and Android lint."
    dependsOn(checkSourceHygiene, ":core:check", ":app:testDebugUnitTest", ":app:lintDebug")
}
