plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.scoova.navlayer.geocoding"
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "nav-layer-geocoding"
                pom {
                    name.set("Scoova Nav Layer — Geocoding Adapter")
                    description.set(
                        "Pelias-backed geocoding client for Scoova Nav Layer — typed " +
                            "autocomplete + reverse-geocode against geocoding.scoo-va.info."
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
