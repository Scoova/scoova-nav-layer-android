plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.scoova.navlayer.mapbox"
    compileSdk = 35
    defaultConfig { minSdk = 24 }

    publishing { singleVariant("release") { withSourcesJar() } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core"))
    // Mapbox Navigation SDK is a `compileOnly` dep — the host app brings
    // its own version. We only require its API shapes at compile time.
    compileOnly("com.mapbox.navigation:android:3.4.0")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "nav-layer-mapbox"
            }
        }
    }
}
