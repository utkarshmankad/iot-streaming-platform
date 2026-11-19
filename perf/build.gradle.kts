plugins {
    java
    id("com.diffplug.spotless")
    id("io.gatling.gradle") version "3.16.0"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.ADOPTIUM
    }
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<io.gatling.gradle.GatlingRunTask>().configureEach {
    nonInteractive = true
    runAllSimulations = true
    systemProperties = providers.systemPropertiesPrefixedBy("perf.").get()
}

spotless {
    java {
        target("src/gatling/java/**/*.java")
        googleJavaFormat("1.36.1")
    }
}
