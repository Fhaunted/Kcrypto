import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    id("com.gradleup.shadow") version "8.3.0"
}

group = "com.kcrp"
version = "1.0.0"
description = "KKopia – Folia crypto-economy plugin"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    // Paper / Folia API
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper / Folia API (provided at runtime by the server)
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")

    // HikariCP connection pool – shaded into the jar
    implementation("com.zaxxer:HikariCP:5.1.0")

    // MySQL Connector/J – shaded into the jar
    implementation("com.mysql:mysql-connector-j:8.3.0")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.withType<ShadowJar> {
    archiveClassifier.set("")
    archiveBaseName.set("KKopia")
    archiveVersion.set(project.version.toString())

    // isZip64 handles large JARs (HikariCP + MySQL bundled)
    isZip64 = true

    // Relocate shaded libs so they don't clash with other plugins
    relocate("com.zaxxer.hikari",  "com.kcrp.kkopia.libs.hikari")
    relocate("com.mysql",          "com.kcrp.kkopia.libs.mysql")

    mergeServiceFiles()
}

// Make the default `build` task produce the shadow jar
tasks.build {
    dependsOn(tasks.shadowJar)
}

// Copy the final jar to a local test-server folder (optional, edit path as needed)
// val testServerPlugins = file("C:/servers/folia/plugins")
// tasks.register<Copy>("deployJar") {
//     dependsOn(tasks.shadowJar)
//     from(tasks.shadowJar.get().archiveFile)
//     into(testServerPlugins)
// }
