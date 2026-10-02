plugins {
    id("io.github.goooler.shadow") version "8.1.8"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://jitpack.io")
}

dependencies {
    // compileOnly, not implementation: plugin.yml declares a hard `depend: [MikeyCore]`, so
    // Paper resolves mikey.me.core.* from the installed MikeyCore plugin. Shading it too would
    // create a second copy of every class (own jar wins in PluginClassLoader) and a second
    // CoreRegistry.root static, which silently splits the data folder.
    compileOnly("mikey.core:mikey-core:0.1.0-SNAPSHOT")
    // compileOnly does not reach the test classpath, and the tests reference CommunicationMode.
    testImplementation("mikey.core:mikey-core:0.1.0-SNAPSHOT")
    compileOnly("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("com.h2database:h2:2.3.232")
    compileOnly("me.clip:placeholderapi:2.11.7")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("com.mysql:mysql-connector-j:8.4.0")
}

tasks {
    shadowJar {
        relocate("com.zaxxer.hikari", "mikey.me.staff.libs.hikari")
        // org.slf4j is deliberately NOT relocated: Paper exports slf4j to plugins, so the
        // unrelocated 1.7 API Hikari compiles against resolves to the server's 2.x and keeps
        // working (Hikari only uses signatures stable across both). Relocating it ships no
        // StaticLoggerBinder, which silently discards every Hikari pool diagnostic.
        // com.mysql.cj and com.google.protobuf are also left alone: the driver name is a string
        // literal in DatabaseManager and is discovered via META-INF/services/java.sql.Driver.
        mergeServiceFiles()
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
    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
