plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("signing")
}

android {
    namespace = "com.scoova.navlayer.google"
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
    // Intentionally NO Google Nav SDK dep — see GoogleMapsNavLayerAdapter
    // for why: we expose a small typed builder that lets the integrator
    // map their own version of Google Nav SDK's StepInfo / Maneuver enum
    // into Scoova's events. Decouples us from API drift across SDK minors.
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "nav-layer-google-maps"
                pom {
                    name.set("Scoova Nav Layer — Google Maps Adapter")
                    description.set(
                        "Push-API helper for wiring Google Maps Navigation SDK into Scoova " +
                            "Nav Layer. Decoupled from Google SDK versioning by design."
                    )
                    url.set("https://layer.scoo-va.info")
                    inceptionYear.set("2026")
                    licenses {
                        license {
                            name.set("Apache-2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("scoova")
                            email.set("info@scoo-va.info"); name.set("Scoova"); email.set("info@scoo-va.info")
                            organization.set("Scoova"); organizationUrl.set("https://scoo-va.info")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/Scoova/scoova-nav-layer-android.git")
                        developerConnection.set("scm:git:ssh://github.com:Scoova/scoova-nav-layer-android.git")
                        url.set("https://github.com/Scoova/scoova-nav-layer-android")
                    }
                }
            }
        }
    }
}
