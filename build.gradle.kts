import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("com.gradleup.shadow") version "9.4.0"
    kotlin("jvm") version "2.2.0"
}

version = "1.0"

repositories {
    mavenCentral()
    mavenLocal()
    exclusiveContent {
        forRepository { maven("https://repo.papermc.io/repository/maven-public/") }
        filter {
            includeGroup("io.papermc.paper")
            includeGroup("net.kyori")
            includeGroup("net.md-5")
        }
    }
    exclusiveContent {
        forRepository {
            maven("https://repo.codemc.io/repository/maven-public/")
        }
        filter {
            includeGroup("de.tr7zw")
        }
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(24))
}

kotlin {
    jvmToolchain(24)
}

dependencies {
    api(libs.kotlin.std)
    implementation(libs.kspigot)
    implementation(libs.nbt.api)
    compileOnly(libs.paper.api)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
}

group = "de.crazj_"
version = "1.0-SNAPSHOT"
description = "SprayZ"
java.sourceCompatibility = JavaVersion.VERSION_25
java.targetCompatibility = JavaVersion.VERSION_24



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

    archiveBaseName.set(rootProject.name)
    archiveClassifier.set("")
    archiveVersion.set(rootProject.version.toString())

    configurations = listOf(project.configurations.getByName("runtimeClasspath"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    entryCompression = ZipEntryCompression.STORED
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.kotlin_module")
}


tasks.register<Copy>("installToTestserver") {
    group = "build"

    val shadowJarTask = tasks.named<ShadowJar>("shadowJar")

    // WICHTIG: Nutze Provider für Dateien
    from(shadowJarTask.flatMap { it.archiveFile })
    into(layout.projectDirectory.dir("testserver template").dir("plugins"))

    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    val fileNameProvider = shadowJarTask.flatMap { it.archiveFileName }
    rename { fileNameProvider.get() }
}
tasks.assemble {
    dependsOn("installToTestserver")
}