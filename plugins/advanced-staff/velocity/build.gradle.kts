plugins {
    id("io.github.goooler.shadow") version "8.1.8"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation("mikey.core:mikey-core:0.1.0-SNAPSHOT")
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("com.mysql:mysql-connector-j:8.4.0")
}

tasks {
    shadowJar {
        relocate("com.zaxxer.hikari", "mc.spearmace.staff.velocity.libs.hikari")
        mergeServiceFiles()
        archiveClassifier.set("")
    }
    build {
        dependsOn(shadowJar)
    }
}
