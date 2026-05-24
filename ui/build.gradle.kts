plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("maven-publish")
}

android {
    namespace = "com.scoova.navlayer.ui"
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
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "nav-layer-ui"
                pom {
                    name.set("Scoova Nav Layer — UI")
                    description.set(
                        "Drop-in Jetpack Compose components for Scoova Nav Layer — maneuver " +
                            "banner, route preview card, heading puck, weather banner. Themeable."
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
