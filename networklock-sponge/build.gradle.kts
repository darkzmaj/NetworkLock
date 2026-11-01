repositories {
    maven("https://repo.spongepowered.org/repository/maven-public/")
}

dependencies {
    implementation(project(":networklock-core"))
    compileOnly("org.spongepowered:spongeapi:17.0.0")
    compileOnly("org.jetbrains:annotations:26.0.2") // referenced by the Sponge API class files
}
