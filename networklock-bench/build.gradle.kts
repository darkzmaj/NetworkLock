// JMH micro-benchmarks. Not part of NetworkLock.jar. The NetworkLock core under test is put on the
// classpath at run time (it/bench-jmh.sh), so the same benchmark code measures 1.0.0 and 1.0.1.
dependencies {
    compileOnly(project(":networklock-core"))
    implementation("org.openjdk.jmh:jmh-core:1.37")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    runtimeOnly("com.google.code.gson:gson:2.11.0") // BungeeGuard's handshake parser needs it
}

tasks.register("benchClasspath") {
    val out = layout.buildDirectory.file("bench-classpath.txt")
    val cp = sourceSets["main"].runtimeClasspath
    outputs.file(out)
    doLast { out.get().asFile.writeText(cp.files.joinToString(File.pathSeparator)) }
}
