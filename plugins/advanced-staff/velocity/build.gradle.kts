plugins {
    id("io.github.goooler.shadow") version "8.1.8"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Velocity has no `depend` mechanism for Paper plugins, so this one genuinely must bundle
    // MikeyCore. The Bukkit-only entry point and plugin.yml are excluded below.
    implementation("mikey.core:mikey-core:0.1.0-SNAPSHOT")
    // The shadowed copy is compileOnly-equivalent at runtime; tests need it explicitly.
    testImplementation("mikey.core:mikey-core:0.1.0-SNAPSHOT")
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("com.mysql:mysql-connector-j:8.4.0")
}

tasks {
    shadowJar {
        relocate("com.zaxxer.hikari", "mikey.me.staff.velocity.libs.hikari")
        // org.slf4j is deliberately NOT relocated: Velocity exports slf4j to plugins, so the
        // unrelocated 1.7 API resolves to the proxy's 2.x and keeps working. Relocating it
        // ships no StaticLoggerBinder, discarding every Hikari pool diagnostic.
        // com.mysql.cj and com.google.protobuf are also left alone: the driver name is a string
        // literal in VelocityDatabaseManager, found via META-INF/services/java.sql.Driver.
        mergeServiceFiles()
        // MikeyCorePlugin extends org.bukkit.plugin.java.JavaPlugin. This jar ships into a
        // Velocity proxy that has no Bukkit API at all, so the class could never link. It is
        // also never referenced from this module.
        exclude("mikey/me/core/MikeyCorePlugin.class")
        // Likewise MikeyCore's own plugin.yml: meaningless in a proxy jar and it would
        // otherwise land in the jar root.
        exclude("plugin.yml")
        exclude("META-INF/maven/**")
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        exclude("LICENSE", "README", "INFO_BIN", "INFO_SRC")
        archiveClassifier.set("")
    }
    // The thin jar would otherwise overwrite the shaded one at the same path.
    jar {
        archiveClassifier.set("plain")
    }
    build {
        dependsOn(shadowJar)
    }
}
