plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("maven-publish")
}

android {
    namespace = "com.scoova.navlayer.maplibre"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    buildFeatures { compose = true }

    publishing { singleVariant("release") { withSourcesJar() } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core"))
    // MapLibre — the only renderer this adapter targets. Pinned to the
    // same version the demo uses so a host app can't accidentally pull
    // in a mismatched MapLibre via transitive deps.
    api("org.maplibre.gl:android-sdk:11.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Used by ScoovaStylePatcher.loadAndPatch() for the one-shot style
    // fetch. Host apps that already have an HTTP client can call
    // rewriteFonts() / rewriteTextLanguage() directly and skip the
    // network helper.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Compose — for the ScoovaMapView Composable wrapper.
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "nav-layer-maplibre"
                pom {
                    name.set("Scoova Nav Layer — MapLibre Adapter")
                    description.set(
                        "MapLibre Android map components: animated user puck (pulsing " +
                            "accuracy ring + heading cone), route line painter, destination " +
                            "pin. Drop-in on top of MapLibre Android SDK 11.x."
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
