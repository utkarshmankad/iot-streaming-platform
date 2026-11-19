import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("com.diffplug.spotless") version "8.11.0" apply false
    id("org.springframework.boot") version "4.1.2" apply false
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "org.springframework.boot")

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
            googleJavaFormat("1.36.1")
        }
    }

    val sourceSets = extensions.getByType<SourceSetContainer>()
    val mainSource = sourceSets.named("main").get()
    val integrationSource = sourceSets.create("integrationTest") {
        compileClasspath += mainSource.output
        runtimeClasspath += mainSource.output
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

    tasks.named<BootJar>("bootJar") {
        archiveFileName.set("application.jar")
    }

    dependencyLocking {
        lockAllConfigurations()
    }

    dependencies {
        add("implementation", platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
        add("implementation", "org.springframework.boot:spring-boot-starter-webmvc")
        add("implementation", "org.springframework.boot:spring-boot-starter-actuator")
        add("runtimeOnly", "io.micrometer:micrometer-registry-prometheus")
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }
}
