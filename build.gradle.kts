plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("maven-publish")
}

allprojects {
    group = "com.scoova"
    version = "1.0.0"
}

// SDK publishing — applies to every module with the `maven-publish` plugin.
// • `./gradlew publishToMavenLocal`           → ~/.m2 (works out of the box)
// • `./gradlew publishAllPublicationsToGitHubPackagesRepository`
//                                              needs GITHUB_USER + GITHUB_TOKEN
// • `./gradlew publishAllPublicationsToSonatypeRepository`
//                                              needs OSSRH_USERNAME + OSSRH_PASSWORD
subprojects {
    plugins.withId("maven-publish") {
        configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/Scoova/scoova-nav-layer-android")
                    credentials {
                        username = (findProperty("gpr.user") as String?)
                            ?: System.getenv("GITHUB_USER") ?: ""
                        password = (findProperty("gpr.token") as String?)
                            ?: System.getenv("GITHUB_TOKEN") ?: ""
                    }
                }
                maven {
                    name = "Sonatype"
                    url = uri(
                        if (version.toString().endsWith("SNAPSHOT"))
                            "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                        else
                            "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
                    )
                    credentials {
                        username = (findProperty("ossrhUsername") as String?)
                            ?: System.getenv("OSSRH_USERNAME") ?: ""
                        password = (findProperty("ossrhPassword") as String?)
                            ?: System.getenv("OSSRH_PASSWORD") ?: ""
                    }
                }
            }
        }
    }
}
