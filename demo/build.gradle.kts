plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.scoova.ride"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.scoova.ride"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // Scoova Monitor API key — replaced at build time in CI via
        // `-PscoovaMonitorApiKey=sm_…`. Default is the registered key for
        // the "Scoova Ride" project (bundle com.scoova.ride) on
        // monitor.scoo-va.info. Like all client API keys it's safe to
        // ship in the APK; the server enforces bundle-id binding on
        // ingest so a copied key can't post events from another app.
        val monitorKey: String = (project.findProperty("scoovaMonitorApiKey") as String?)
            ?: "sm_3f172fa4e7ab47961fe306ce2a2f7b94df2dd23e28e4a25ac4d5ea44"
        buildConfigField("String", "SCOOVA_MONITOR_API_KEY", "\"$monitorKey\"")
    }

    // ── Release signing ─────────────────────────────────────────────────
    // Credentials come from ~/.gradle/gradle.properties (recommended) or
    // env vars. The keystore path is relative to the project root so the
    // .jks file stays out of git. See RELEASE.md for the one-time setup.
    val releaseKeystore: String? = (project.findProperty("RIDE_KEYSTORE_PATH") as String?)
        ?: System.getenv("RIDE_KEYSTORE_PATH")
    val releaseStorePw: String? = (project.findProperty("RIDE_KEYSTORE_PASSWORD") as String?)
        ?: System.getenv("RIDE_KEYSTORE_PASSWORD")
    val releaseKeyAlias: String? = (project.findProperty("RIDE_KEY_ALIAS") as String?)
        ?: System.getenv("RIDE_KEY_ALIAS")
    val releaseKeyPw: String? = (project.findProperty("RIDE_KEY_PASSWORD") as String?)
        ?: System.getenv("RIDE_KEY_PASSWORD")
    val hasReleaseKeystore = !releaseKeystore.isNullOrBlank() && !releaseStorePw.isNullOrBlank()

    if (hasReleaseKeystore) {
        signingConfigs {
            create("release") {
                storeFile     = rootProject.file(releaseKeystore!!)
                storePassword = releaseStorePw
                keyAlias      = releaseKeyAlias
                keyPassword   = releaseKeyPw
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false        // v1: ship un-minified, enable in v1.1 with rules
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Fall back to debug signing when no release keystore is configured,
            // so the release variant still builds for tyre-kicking. Production
            // uploads require the real keystore — see RELEASE.md.
            signingConfig = if (hasReleaseKeystore)
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // 16 KB page-size alignment for Android 15+ devices (Pixel 8+ Pro,
        // some Samsung 2025 models). AGP 8.5+ enforces 16 KB alignment on
        // .so libraries when this flag is set — the OS no longer shows
        // the "App isn't 16 KB compatible" warning.
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ui"))
    implementation(project(":adapter-scoova-routing"))
    implementation(project(":adapter-scoova-geocoding"))
    implementation(project(":adapter-scoova-weather"))
    implementation(project(":adapter-maplibre"))

    // Scoova Monitor (crash + ANR + analytics + screen views). Pulled
    // from mavenLocal until the SDK ships to Maven Central — see
    // settings.gradle.kts for the mavenLocal() repo, and publish from
    // scoova-monitor with `./gradlew :sdk-android:publishToMavenLocal`.
    implementation("com.scoova.monitor:sdk:1.4.0")

    // Compose BOM bumped to 2024.12 — pulls in androidx.graphics.path 1.0.1+
    // which ships 16-KB-aligned native libs. Required to dismiss the
    // Android 15 "App isn't 16 KB compatible" warning.
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")

    // Map renderer (open-source, no token required). 11.8.x ships 16-KB
    // page-size aligned libmaplibre.so for Android 15+ devices.
    implementation("org.maplibre.gl:android-sdk:11.8.1")

    // Location
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // For destination search via Scoova geocoding
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Encrypted persistence for ride history + active-ride snapshot.
    // Keys live in the Android Keystore (AES-256 GCM, hardware-backed
    // on supported devices). On-disk format is the same as the unencrypted
    // version so a hex-dump of rides.json shows ciphertext, not GPS
    // traces — the privacy improvement matters for shared-device
    // scenarios (family phones) and post-theft data exposure.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
