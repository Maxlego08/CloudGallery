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
        // MSAL -> common -> display-mask (Surface Duo), published only on the Duo SDK feed.
        // Exclusive content: this repository serves only that group and is never queried for anything else.
        exclusiveContent {
            forRepository {
                maven("https://pkgs.dev.azure.com/MicrosoftDeviceSDK/DuoSDK-Public/_packaging/Duo-SDK-Feed/maven/v1") {
                    name = "DuoSdkFeed"
                }
            }
            filter { includeGroup("com.microsoft.device.display") }
        }
    }
}

rootProject.name = "CloudGallery"
include(":app")
