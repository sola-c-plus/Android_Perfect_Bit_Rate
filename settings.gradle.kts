pluginManagement {
    repositories {
        maven { url = uri("https://maven.mozilla.org/maven2/") }
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
        maven { url = uri("https://maven.mozilla.org/maven2/") }
        google()
        mavenCentral()
        // JitPack 繝ｪ繝昴ず繝医Μ
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Perfect_Bit_Rate"
include(":app")