plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

allprojects {
    group = "dev.korinetwork.networklock"
    // Lockfiles pin every resolved version (reproducible builds) and feed the OSV vulnerability scan in CI.
    dependencyLocking { lockAllConfigurations() }
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

subprojects {
    apply(plugin = "java")
    java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        // Each part runs only on its own platform: core and the Bukkit side load on Java 8 servers (1.8 to 1.16),
        // the proxies need Java 17+, Sponge 1.21+ needs 21. Tests always run on the build JDK.
        options.release.set(when {
            name.contains("Test") -> 21
            project.name == "networklock-core" || project.name == "networklock-bukkit" -> 8
            project.name == "networklock-sponge" -> 21
            else -> 17
        })
        options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial,-options,-classfile", "-Werror"))
    }
    tasks.withType<ProcessResources> {
        val v = project.version.toString()
        inputs.property("version", v)
        filesMatching(listOf("plugin.yml", "bungee.yml", "velocity-plugin.json", "META-INF/sponge_plugins.json")) {
            expand("version" to v)
        }
    }
    tasks.withType<Test> {
        useJUnitPlatform()
    }
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))

dependencies {
    implementation(project(":networklock-core"))
    implementation(project(":networklock-bukkit"))
    implementation(project(":networklock-velocity"))
    implementation(project(":networklock-bungee"))
    implementation(project(":networklock-sponge"))
}

tasks.shadowJar {
    archiveFileName.set("NetworkLock.jar")
    from(files("LICENSE", "THIRD-PARTY-NOTICES.txt")) { into("META-INF") }
    // Our own copy of SnakeYAML: platform-provided versions differ (1.x vs 2.x) and are not safe-load by default.
    relocate("org.yaml.snakeyaml", "dev.korinetwork.networklock.lib.snakeyaml")
    exclude("META-INF/maven/**", "META-INF/versions/**", "module-info.class", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    // Classes reference only their own platform; tell Paper not to run the Spigot->Mojang remapper over them.
    manifest.attributes("paperweight-mappings-namespace" to "mojang")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.jar { enabled = false }
tasks.assemble { dependsOn(tasks.shadowJar) }
