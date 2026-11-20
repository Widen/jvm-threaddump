plugins {
    `java-library`
    `maven-publish`
}

fun jfrogCredential(name: String): String =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
        ?: throw GradleException("Set $name in the environment or in ~/.gradle/gradle.properties")

val jfrogUser = jfrogCredential("ACQUIA_JFROG_USER")
val jfrogToken = jfrogCredential("ACQUIA_JFROG_TOKEN")

fun MavenArtifactRepository.useJfrogCredentials() {
    credentials {
        username = jfrogUser
        password = jfrogToken
    }
    authentication {
        create<BasicAuthentication>("basic")
    }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(8)
    }
    withSourcesJar()
}

repositories {
    maven {
        url = uri("https://jfrog.ais.acquia.io/artifactory/widen-java-virtual/")
        useJfrogCredentials()
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

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "jfrog"
            val isSnapshot = project.version.toString().endsWith("-SNAPSHOT")
            val repository = if (isSnapshot) "libs-snapshots-local" else "libs-releases-local"
            url = uri("https://jfrog.ais.acquia.io/artifactory/$repository")
            useJfrogCredentials()
        }
    }
}
