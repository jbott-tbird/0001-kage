pluginManagement {
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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Kage"
if (!providers.gradleProperty("mailCoreOnly").isPresent) include(":app")

include(":core:account", ":core:mime", ":core:transport", ":core:imap", ":core:smtp")
include(":core:integration")

include(":core:testkit")

// Explicit local input keeps the comparison out of shipping builds and CI.
if (providers.gradleProperty("thunderbirdSpikeJars").isPresent) include(":spikes:thunderbird")
include(":core:demo")
