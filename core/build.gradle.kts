plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-test-fixtures`
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

tasks.withType<Test>().configureEach {
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    // Hide desktop-only modules so JVM tests reject APIs unavailable on Android.
    jvmArgs("--limit-modules", "java.base,java.logging,java.xml,jdk.unsupported")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testFixturesImplementation("junit:junit:4.13.2")
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    implementation("org.tukaani:xz:1.10")
    implementation("com.drewnoakes:metadata-extractor:2.21.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    // Both modules use the same SLF4J API version.
    implementation("com.github.junrar:junrar:8.1.1") { exclude(group = "org.slf4j", module = "slf4j-api") }
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("net.dongliu:apk-parser:2.6.10")
    implementation("com.mpatric:mp3agic:0.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
