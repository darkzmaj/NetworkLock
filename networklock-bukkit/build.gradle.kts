dependencies {
    implementation(project(":networklock-core"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

// Compiled for Java 8 (1.8-1.16 servers), but against the newest Paper API, which declares Java 21.
// Newer API methods are only called after checking they exist at runtime.
configurations.named("compileClasspath") {
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}
