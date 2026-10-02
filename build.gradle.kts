subprojects {
    if (path == ":plugins" || path == ":plugins:advanced-staff") {
        return@subprojects
    }
    apply(plugin = "java")

    group = "mc.spearmace"
    version = "1.0-SNAPSHOT"

    configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    dependencies {
        add("testImplementation", "org.junit.jupiter:junit-jupiter:5.11.4")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher:1.11.4")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

val mikeyCoreTest = gradle.includedBuild("mikey-core").task(":test")

tasks.register("coreTest") {
    group = "verification"
    description = "Runs the standalone MikeyCore test suite through the composite build."
    dependsOn(mikeyCoreTest)
}
