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
            name = "JitPack"
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.cafebazaar.Poolakey")
            }
        }
    }
}

rootProject.name = "inappify_android_plugin"
include(":sdk")

// The private integration harness is not part of the public source distribution.
if (providers.gradleProperty("includePrivateSample").orNull == "true") {
    require(file("app/build.gradle.kts").isFile) {
        "The private integration harness is not available in this checkout."
    }
    include(":app")
}
