pluginManagement {
    buildscript {
        repositories {
            maven {
                url = uri("https://storage.googleapis.com/r8-releases/raw")
                content { includeModule("com.android.tools", "r8") }
            }
        }
        dependencies {
            // LiteRT-LM ships Kotlin 2.4 metadata. AGP 8.13's bundled R8 supports up to Kotlin 2.3.
            classpath("com.android.tools:r8:9.1.29")
        }
    }
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Monolog"
include(":app")
