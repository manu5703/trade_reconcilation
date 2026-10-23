plugins {
    // Lets Gradle download a matching JDK when the machine doesn't have Java 21 installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "trade-recon"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

// recon-ui is an npm project (see recon-ui/package.json), not part of the Gradle build.
include("recon-core", "recon-datagen", "recon-service", "recon-spark", "bench")
