pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Mapbox Navigation SDK lives behind their authenticated maven. Including
// the adapter module is only useful if the integrator has a Mapbox download
// token (set in `~/.gradle/gradle.properties` as MAPBOX_DOWNLOADS_TOKEN).
// We auto-detect; if absent, the Mapbox adapter is skipped from the build.
val mapboxToken: String? =
    (extra.properties["MAPBOX_DOWNLOADS_TOKEN"] as String?)
        ?: System.getenv("MAPBOX_DOWNLOADS_TOKEN")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Scoova Monitor SDK lives in mavenLocal until it ships to
        // Maven Central. Publish from the scoova-monitor repo with
        // `./gradlew :sdk-android:publishToMavenLocal`.
        mavenLocal()
        if (mapboxToken != null) {
            maven {
                url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
                authentication { create<BasicAuthentication>("basic") }
                credentials {
                    username = "mapbox"
                    password = mapboxToken
                }
            }
        }
    }
}

rootProject.name = "scoova-nav-layer-android"
include(":core")
include(":ui")
include(":adapter-scoova-routing")
include(":adapter-scoova-geocoding")
include(":adapter-scoova-weather")
include(":adapter-google-maps")
include(":adapter-maplibre")
include(":demo")
if (mapboxToken != null) {
    include(":adapter-mapbox")
} else {
    logger.lifecycle("[scoova-nav-layer] MAPBOX_DOWNLOADS_TOKEN not set — skipping :adapter-mapbox. Set it in ~/.gradle/gradle.properties to build.")
}
