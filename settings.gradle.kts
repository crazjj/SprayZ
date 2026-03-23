rootProject.name = "SprayZ"


pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }
    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()

        exclusiveContent {
            forRepository { maven("https://repo.papermc.io/repository/maven-public/") }
            filter {
                includeGroup("io.papermc.paper")
                includeGroup("net.kyori")
                includeGroup("net.md-5")
            }
        }
        exclusiveContent {
            forRepository { maven("https://repo.codemc.io/repository/maven-public/") }
            filter { includeGroup("de.tr7zw") }
        }

        exclusiveContent {
            forRepository { maven("https://maven.pvphub.me/tofaa") }
            filter { includeGroup("io.github.tofaa2") }
        }

//    exclusiveContent {
//        forRepository { maven("https://jitpack.io") }
//        filter { includeGroup("io.github.tofaa2") }
//    }

        exclusiveContent {
            forRepository { maven("https://repo.codemc.io/repository/maven-releases/") }
            filter { includeGroup("com.github.retrooper") }
        }

        mavenLocal()
    }
}