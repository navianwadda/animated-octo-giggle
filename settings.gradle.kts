pluginManagement {
    plugins {
        id("com.android.application") version "8.4.0" apply false
        id("org.jetbrains.kotlin.android") version "1.9.23" apply false
    }
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
        maven("https://chaquo.com/maven")
    }
}

rootProject.name = "YdlV1"
include(":app")
