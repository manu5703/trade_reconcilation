import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("recon.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    // The Boot BOM as a Gradle platform (rather than the io.spring.dependency-management plugin),
    // so it resolves alongside the JUnit BOM from the convention plugin.
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(project(":recon-core"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.micrometer.prometheus)
    implementation(libs.springdoc.openapi)
    // Compile scope: the importer uses the driver's COPY API.
    implementation(libs.postgresql)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.awaitility)
    // Integration tests generate their datasets with the real generator.
    testImplementation(project(":recon-datagen"))
}

// One tolerance file for the whole repo: bundle a copy so the jar runs anywhere.
tasks.processResources {
    from(rootProject.file("config/tolerances.properties"))
}

tasks.test {
    // Integration tests start a PostgreSQL container; give them room for a generated dataset.
    maxHeapSize = "1g"
    systemProperty("recon.rootDir", rootDir.absolutePath)
}
