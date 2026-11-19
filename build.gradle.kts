import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec

plugins {
    id("org.springframework.boot") version "4.1.2" apply false
}

subprojects {
    apply(plugin = "java")
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

    dependencyLocking {
        lockAllConfigurations()
    }

    dependencies {
        add("implementation", platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
        add("implementation", "org.springframework.boot:spring-boot-starter")
    }
}
