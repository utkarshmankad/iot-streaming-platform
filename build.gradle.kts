import com.diffplug.gradle.spotless.SpotlessExtension
import com.github.spotbugs.snom.SpotBugsExtension
import com.google.protobuf.gradle.ProtobufExtension
import info.solidsoft.gradle.pitest.PitestPluginExtension
import org.gradle.api.file.SourceDirectorySet
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    jacoco
    id("com.google.protobuf") version "0.10.0" apply false
    id("com.github.spotbugs") version "6.5.13" apply false
    id("com.diffplug.spotless") version "8.11.0" apply false
    id("org.springframework.boot") version "4.1.2" apply false
    id("info.solidsoft.pitest") version "1.19.0" apply false
}

val services = listOf(project(":backend"), project(":gateway"), project(":simulator"), project(":contracts"))

data class MutationScope(val classes: Set<String>, val tests: Set<String>)

val mutationScopes = mapOf(
    "backend" to MutationScope(
        setOf(
            "iot.platform.backend.ingest.TelemetryIngest",
            "iot.platform.backend.ingest.TelemetryStore",
            "iot.platform.backend.control.CommandStore\$Command",
        ),
        setOf(
            "iot.platform.backend.ingest.TelemetryFingerprintTest",
            "iot.platform.backend.ingest.TelemetryIngestIntegrationTest",
            "iot.platform.backend.control.CommandTimingTest",
        ),
    ),
    "contracts" to MutationScope(
        setOf("iot.platform.telemetry.TelemetryValidation", "iot.platform.control.CommandProtocol"),
        setOf("iot.platform.telemetry.TelemetryValidationTest", "iot.platform.control.CommandProtocolTest"),
    ),
    "gateway" to MutationScope(
        setOf("iot.platform.gateway.ingest.GatewaySpool", "iot.platform.gateway.ingest.GatewayDelivery"),
        setOf("iot.platform.gateway.ingest.GatewaySpoolTest", "iot.platform.gateway.ingest.GatewayDeliveryTest"),
    ),
    "simulator" to MutationScope(
        setOf("iot.platform.simulator.payload.SimulatorOutbox"),
        setOf("iot.platform.simulator.payload.SimulatorOutboxTest"),
    ),
)

configure(services) {
    apply(plugin = "java")
    apply(plugin = "jacoco")
    apply(plugin = "com.github.spotbugs")
    apply(plugin = "com.diffplug.spotless")
    val contractLibrary = name == "contracts"
    if (contractLibrary) {
        apply(plugin = "java-library")
    } else {
        apply(plugin = "org.springframework.boot")
    }

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
            vendor = JvmVendorSpec.ADOPTIUM
        }
    }

    extensions.configure<SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat("1.36.1")
        }
    }

    extensions.configure<JacocoPluginExtension> {
        toolVersion = "0.8.16"
    }
    extensions.configure<SpotBugsExtension> {
        toolVersion.set("4.10.5")
    }

    val mutationScope = mutationScopes.getValue(name)
    apply(plugin = "info.solidsoft.pitest")
    val mutationJava = extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
    extensions.configure<PitestPluginExtension> {
        pitestVersion.set("1.30.1")
        junit5PluginVersion.set("1.2.3")
        targetClasses.set(mutationScope.classes)
        targetTests.set(mutationScope.tests)
        jvmPath.set(mutationJava.map { it.executablePath })
        threads.set(2)
        failWhenNoMutations.set(true)
        outputFormats.set(setOf("HTML", "XML"))
        timestampedReports.set(false)
    }

    val sourceSets = extensions.getByType<SourceSetContainer>()
    val mainSource = sourceSets.named("main").get()
    if (contractLibrary) {
        apply(plugin = "com.google.protobuf")
        extensions.configure<ProtobufExtension> {
            protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
        }
        (mainSource.extensions.getByName("proto") as SourceDirectorySet)
            .srcDir(rootProject.file("contracts/proto"))
        extensions.configure<SpotBugsExtension> {
            excludeFilter.set(rootProject.file("config/spotbugs/registry-generated.xml"))
        }
    }

    val integrationSource = sourceSets.create("integrationTest") {
        compileClasspath += mainSource.output
        runtimeClasspath += mainSource.output
    }
    if (name == "backend") {
        extensions.configure<PitestPluginExtension> {
            testSourceSets.set(listOf(sourceSets.named("test").get(), integrationSource))
        }
    }
    configurations.named(integrationSource.implementationConfigurationName) {
        extendsFrom(configurations.getByName("testImplementation"))
    }
    configurations.named(integrationSource.runtimeOnlyConfigurationName) {
        extendsFrom(configurations.getByName("testRuntimeOnly"))
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
    tasks.register<Test>("integrationTest") {
        group = "verification"
        description = "Runs Spring Boot integration tests."
        testClassesDirs = integrationSource.output.classesDirs
        classpath = integrationSource.runtimeClasspath
        shouldRunAfter(tasks.named("test"))
    }

    if (!contractLibrary) {
        tasks.named<BootJar>("bootJar") {
            archiveFileName.set("application.jar")
        }
    }

    dependencyLocking {
        lockAllConfigurations()
    }

    dependencies {
        add("implementation", platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
        if (contractLibrary) {
            add("api", "com.google.protobuf:protobuf-java:4.36.2")
        } else {
            add("implementation", project(":contracts"))
            add("implementation", "org.springframework.boot:spring-boot-starter-webmvc")
            add("implementation", "org.springframework.boot:spring-boot-starter-actuator")
            add("runtimeOnly", "io.micrometer:micrometer-registry-prometheus")
        }
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
        add("spotbugsPlugins", "com.h3xstream.findsecbugs:findsecbugs-plugin:1.14.0")
        if (name == "simulator" || name == "gateway") {
            add("implementation", "org.xerial:sqlite-jdbc:3.54.1.0")
            add("implementation", "org.eclipse.paho:org.eclipse.paho.mqttv5.client:1.2.5")
            add("integrationTestImplementation", "org.testcontainers:testcontainers")
        }
        if (name == "backend") {
            add("implementation", "org.springframework.boot:spring-boot-starter-security")
            add("implementation", "org.springframework.boot:spring-boot-starter-jdbc")
            add("implementation", "org.springframework.boot:spring-boot-starter-flyway")
            add("runtimeOnly", "org.flywaydb:flyway-database-postgresql")
            add("runtimeOnly", "org.postgresql:postgresql")
            add("integrationTestImplementation", "org.testcontainers:testcontainers-postgresql")
            add("testImplementation", "com.tngtech.archunit:archunit-junit5:1.5.1")
        }
    }
}

repositories {
    mavenCentral()
}

tasks.register("mutationChecks") {
    group = "verification"
    description = "Runs targeted PIT mutation checks and writes per-module HTML and XML reports."
    dependsOn(mutationScopes.keys.map { ":$it:pitest" })
}

jacoco {
    toolVersion = "0.8.16"
}

val coverageClasses = files(services.map { service ->
    service.extensions.getByType<SourceSetContainer>().getByName("main").output.classesDirs
        .asFileTree.matching {
            // These three main methods only delegate startup to Spring Boot.
            exclude(
                "iot/platform/registry/proto/**",
                "iot/platform/telemetry/proto/**",
                "iot/platform/control/proto/**",
                "iot/platform/backend/BackendApplication.class",
                "iot/platform/gateway/GatewayApplication.class",
                "iot/platform/simulator/SimulatorApplication.class",
            )
        }
})
val coverageData = files(services.flatMap { service ->
    listOf("test", "integrationTest").map { suite ->
        service.layout.buildDirectory.file("jacoco/$suite.exec")
    }
})
val coverageSources = files(services.map { it.file("src/main/java") })
val coverageTests = services.flatMap { service ->
    listOf("test", "integrationTest").map { suite -> service.tasks.named(suite) }
}

val coverageInputs = tasks.register("verifyCoverageInputs") {
    dependsOn(coverageTests)
    doLast {
        check(coverageClasses.isEmpty || coverageData.files.any { it.isFile }) {
            "Production classes require coverage data from the Java test suites."
        }
        if (coverageClasses.isEmpty) {
            logger.lifecycle("Coverage threshold deferred: only Spring Boot entry points exist.")
        }
    }
}

tasks.register<JacocoReport>("jacocoMergedReport") {
    dependsOn(coverageInputs)
    classDirectories.setFrom(coverageClasses)
    sourceDirectories.setFrom(coverageSources)
    executionData.setFrom(coverageData)
    onlyIf("there are measurable production classes") { !coverageClasses.isEmpty }
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.register<JacocoCoverageVerification>("jacocoMergedCoverageVerification") {
    dependsOn(coverageInputs)
    classDirectories.setFrom(coverageClasses)
    sourceDirectories.setFrom(coverageSources)
    executionData.setFrom(coverageData)
    onlyIf("there are measurable production classes") { !coverageClasses.isEmpty }
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.85".toBigDecimal()
            }
        }
    }
}
