// End-of-day batch: the recon-core matcher on Spark (local mode by default).
// Run: ./gradlew :recon-spark:run --args="--data build/data-10m --out build/spark-out"
plugins {
    id("recon.java-conventions")
    application
}

dependencies {
    implementation(project(":recon-core"))
    implementation(libs.spark.sql)
    implementation(libs.picocli)
    testImplementation(project(":recon-datagen"))
}

// Spark reaches into JDK internals; these are the flags its own launcher (spark-submit) adds on
// Java 17+. Needed because we start Spark from a plain JVM.
val sparkJvmArgs = listOf(
    "-XX:+IgnoreUnrecognizedVMOptions",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
    "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
    "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
    "-Djdk.reflect.useDirectMethodHandle=false",
    "-Dio.netty.tryReflectionSetAccessible=true",
)

application {
    mainClass = "com.recon.spark.ReconSparkJob"
    applicationDefaultJvmArgs = sparkJvmArgs
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
    maxHeapSize = "8g"
}

tasks.test {
    jvmArgs(sparkJvmArgs)
    maxHeapSize = "2g"
    systemProperty("recon.rootDir", rootDir.absolutePath)
}
