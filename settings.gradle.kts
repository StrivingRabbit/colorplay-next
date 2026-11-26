pluginManagement {
    repositories {
        // Optional local mirror; CI defaults to official Google Maven.
        providers.gradleProperty("colorplay.googleMirror").orNull?.let { mirror ->
            maven(mirror) { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx.*") } }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Optional local mirror; CI defaults to official Google Maven.
        providers.gradleProperty("colorplay.googleMirror").orNull?.let { mirror ->
            maven(mirror) { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx.*") } }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "colorplay-next"
include(":app")
