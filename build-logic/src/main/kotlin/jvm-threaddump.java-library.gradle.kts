plugins {
    `java-library`
}

fun jfrogCredential(name: String): String =
    providers.environmentVariable(name).orNull
        ?: providers.gradleProperty(name).orNull
        ?: throw GradleException("Set $name in the environment or in ~/.gradle/gradle.properties")

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

repositories {
    maven {
        url = uri("https://jfrog.ais.acquia.io/artifactory/widen-java-virtual/")
        credentials {
            username = jfrogCredential("ACQUIA_JFROG_USER")
            password = jfrogCredential("ACQUIA_JFROG_TOKEN")
        }
        authentication {
            create<BasicAuthentication>("basic")
        }
    }
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testImplementation(libs.findLibrary("assertj-core").get())
    testRuntimeOnly(libs.findLibrary("junit-launcher").get())
}

tasks.test {
    useJUnitPlatform()
}
