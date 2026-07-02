plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("maven-publish")
    id("signing")
}

android {
    namespace = "com.scoova.navlayer.scoova"
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "nav-layer-scoova-routing"
                pom {
                    name.set("Scoova Nav Layer — Scoova Routing Adapter")
                    description.set(
                        "Adapter wiring Scoova's routing service (Valhalla-backed) into the " +
                            "Scoova Nav Layer. Profile-aware bicycle / scooter / auto / pedestrian " +
                            "routing with landmark + weather enrichment."
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
