pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // VLC repository for the VLC Android SDK
        maven { url = java.net.URI("https://artifacts.videolan.org/android/") }
    }
}

rootProject.name = "frigate-android"
include(":app")
