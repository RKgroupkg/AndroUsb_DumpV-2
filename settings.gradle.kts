pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AndroUsb_DumpV-2"

// Only enable TYPESAFE_PROJECT_ACCESSORS, remove VERSION_CATALOGS
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
