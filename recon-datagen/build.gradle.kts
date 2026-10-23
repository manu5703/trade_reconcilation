plugins {
    id("recon.java-conventions")
    application
}

dependencies {
    implementation(project(":recon-core"))
    implementation(libs.picocli)
    implementation(libs.commons.csv)
    implementation(libs.duckdb.jdbc)
}

application {
    mainClass = "com.recon.datagen.DatagenCli"
}

// Resolve --out and --tolerances against the repo root, not recon-datagen/.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

tasks.test {
    systemProperty("recon.rootDir", rootDir.absolutePath)
    // The full-size generator tests hold ~20k cases in memory.
    maxHeapSize = "1g"
}
