plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("maven-publish")
    id("signing")
}

allprojects {
    // Maven Central verified namespace for the Scoova org. Every sibling
    // SDK (scoova-routing-android, scoova-maps-android, ...) publishes
    // under this same group.
    group = "info.scoo-va"
    version = "1.0.2"
}

// Cross-module publishing repos + signing. Each module keeps its own
// `create<MavenPublication>("release")` with the module-specific artifactId
// + POM; we just add the LocalStaging + GitHubPackages destinations plus
// PGP signing here so every module inherits them.
//
// Publish targets:
//   • `./gradlew publishToMavenLocal`                        → ~/.m2
//   • `./gradlew publishReleasePublicationToLocalStagingRepository`
//                                                            → build/staging-deploy
//     (feeds scripts/publish-to-central-portal.sh for Maven Central)
//   • `./gradlew publishReleasePublicationToGitHubPackagesRepository`
//                                                            needs GITHUB_ACTOR + GITHUB_TOKEN
subprojects {
    plugins.withId("maven-publish") {
        afterEvaluate {
            configure<PublishingExtension> {
                repositories {
                    // Local staging dir at the ROOT build/ so the Central Portal
                    // script picks up every module's info/scoo-va/... tree
                    // from a single zip bundle.
                    maven {
                        name = "LocalStaging"
                        url = uri(rootProject.layout.buildDirectory.dir("staging-deploy"))
                    }
                    maven {
                        name = "GitHubPackages"
                        url = uri("https://maven.pkg.github.com/Scoova/scoova-nav-layer-android")
                        credentials {
                            username = System.getenv("GITHUB_ACTOR")
                                ?: (findProperty("gpr.user") as String?) ?: ""
                            password = System.getenv("GITHUB_TOKEN")
                                ?: (findProperty("gpr.token") as String?) ?: ""
                        }
                    }
                }
            }

            // Sign the release publication with in-memory PGP when the
            // key is available on the environment (CI). Skipped locally.
            plugins.withId("signing") {
                configure<SigningExtension> {
                    val signingKey      = System.getenv("SIGNING_KEY")
                    val signingPassword = System.getenv("SIGNING_PASSWORD") ?: ""
                    if (!signingKey.isNullOrBlank()) {
                        useInMemoryPgpKeys(signingKey, signingPassword)
                        val pubs = (extensions["publishing"] as PublishingExtension)
                            .publications
                        sign(pubs)
                    }
                }
            }
        }
    }
}
