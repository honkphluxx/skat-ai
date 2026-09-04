// Standalone build for the Skat AI training ground.
//
// This file is used only when skat-ai is built on its own -- `cd skat-ai &&
// ./gradlew test`. When the directory sits inside the SkatKlar repository, the
// outer settings.gradle.kts includes :skat-ai:engine and friends directly and
// this file is ignored, which is deliberate: the app and the server compile
// against the same sources the arena measures, not a copy of them.
//
// The module build files never say `rootProject`. They ask for `project.parent`
// instead, which is this directory in both arrangements.

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "skat-ai"

include(":engine")
include(":jskat-ai")
include(":arena")
