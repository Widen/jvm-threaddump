plugins {
    `java-library`
    `maven-publish`
}

fun jfrogCredential(name: String): String? =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }

val jfrogUser = jfrogCredential("ACQUIA_JFROG_USER")
val jfrogToken = jfrogCredential("ACQUIA_JFROG_TOKEN")
val missingJfrogCredentials = listOfNotNull(
    "ACQUIA_JFROG_USER".takeIf { jfrogUser == null },
    "ACQUIA_JFROG_TOKEN".takeIf { jfrogToken == null },
)

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(8)
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
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
            // Gradle rejects half-set credentials before any task action runs, so set
            // them only when both exist and let the check below name what is missing.
            if (missingJfrogCredentials.isEmpty()) {
                credentials {
                    username = jfrogUser
                    password = jfrogToken
                }
                authentication {
                    create<BasicAuthentication>("basic")
                }
            }
        }
    }
}

// Only publishing needs the credentials, so builds without them still configure.
tasks.withType<PublishToMavenRepository>().configureEach {
    val missing = missingJfrogCredentials
    doFirst {
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Set ${missing.joinToString(" and ")} in the environment or in ~/.gradle/gradle.properties"
            )
        }
    }
}
