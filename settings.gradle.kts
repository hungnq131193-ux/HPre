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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = java.net.URI("https://jitpack.io")
            content {
                includeGroupByRegex("com\\.github\\.(TeamNewPipe|hungnq131193-ux)(\\..*)?")
            }
        }
    }
}

rootProject.name = "HPre"
include(":app")
include(":baselineprofile")
