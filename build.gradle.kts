import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("com.autonomousapps.dependency-analysis") version "3.5.1"
    id("com.gradleup.shadow") version "9.4.0"
    kotlin("jvm") version "2.2.0"
}

val shade by configurations.creating

configurations.named("implementation") {
    extendsFrom(shade)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(24))
}

kotlin {
    jvmToolchain(24)
}

dependencies {
    compileOnly(libs.paper.api)

    compileOnly(libs.adventure.api)
    shade(libs.adventure.key)
    shade(libs.adventure.nbt)
    shade(libs.examination.api)
    shade(libs.examination.string)
    shade(libs.kspigot)
    shade(libs.nbt.api)
    shade(libs.ktor.client.cio) {
        exclude(group = "org.slf4j", module = "slf4j-api")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-slf4j")
    }
    shade(libs.packetevents.spigot) {
        exclude(group = "net.kyori")
    }
    shade(libs.entitylib.spigot)

    implementation(libs.coroutines.core)
}

group = "de.crazj"
version = "1.0-SNAPSHOT"
description = "SprayZ"

tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf(
        "pluginName" to rootProject.name,
        "version" to rootProject.version.toString(),
        "minecraftGen" to libs.versions.minecraftGen.get()
    )
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
    filesMatching("plugin.yml") { expand(props) }
}
tasks.named("jar") { enabled = false }
tasks.named("build") {
    dependsOn("shadowJar")
}

tasks.named<ShadowJar>("shadowJar") {
    relocate("de.tr7zw.changeme.nbtapi", "de.crazj.sprayz.libs.nbt_api")
    relocate("io.github.retrooper", "de.crazj.sprayz.libs.packetevents.spigot")
    relocate("com.github.retrooper", "de.crazj.sprayz.libs.packetevents.api")
    relocate("me.tofaa.entitylib", "de.crazj.sprayz.libs.entitylib")
    relocate("net.axay.kspigot", "de.crazj.sprayz.libs.kspigot")
    relocate("io.ktor", "de.crazj.sprayz.libs.ktor")

    minimize ()

    archiveBaseName.set(rootProject.name)
    archiveClassifier.set("")
    archiveVersion.set(rootProject.version.toString())

    configurations = listOf(shade)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    entryCompression = ZipEntryCompression.STORED
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.kotlin_module")
}


tasks.register<Copy>("installToTestserver") {
    group = "build"

    val shadowJarTask = tasks.named<ShadowJar>("shadowJar")

    from(shadowJarTask.flatMap { it.archiveFile })
    into(layout.projectDirectory.dir("testserver template").dir("plugins"))

    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    val fileNameProvider = shadowJarTask.flatMap { it.archiveFileName }
    rename { fileNameProvider.get() }
}
tasks.assemble {
    dependsOn("installToTestserver")
}
