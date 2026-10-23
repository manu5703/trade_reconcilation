// JMH benchmarks of the core matcher. Run: ./gradlew :bench:jmh  (results in build/results/jmh/)
plugins {
    id("recon.java-conventions")
    alias(libs.plugins.jmh)
}

dependencies {
    jmh(project(":recon-core"))
    jmh(project(":recon-datagen"))
}

jmh {
    jmhVersion = libs.versions.jmh.asProvider().get()
    resultFormat = "JSON"
    resultsFile = layout.buildDirectory.file("results/jmh/results.json")
    jvmArgsAppend.add("-Drecon.rootDir=${rootDir.absolutePath}")
}

// JMH generates code with warnings we don't control; keep -Werror for our own sources only.
tasks.named<JavaCompile>("jmhCompileGeneratedClasses") {
    options.compilerArgs.remove("-Werror")
}
