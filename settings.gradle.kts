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
    }
}

rootProject.name = "RME PDF Scanner"
include(":app")
if (providers.gradleProperty("rmeTranslationPlayDelivery").orNull == "true") {
    include(":translation_ro", ":translation_multi", ":translation_ga")
}
