fun jfrogCredential(name: String): String? =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }

val jfrogUser = jfrogCredential("ACQUIA_JFROG_USER")
val jfrogToken = jfrogCredential("ACQUIA_JFROG_TOKEN")
val missingJfrogCredentials = listOfNotNull(
    "ACQUIA_JFROG_USER".takeIf { jfrogUser == null },
    "ACQUIA_JFROG_TOKEN".takeIf { jfrogToken == null },
)
val hasJfrogCredentials = missingJfrogCredentials.isEmpty()

fun MavenArtifactRepository.useJfrogCredentials() {
    credentials {
        username = jfrogUser
        password = jfrogToken
    }
    authentication {
        create<BasicAuthentication>("basic")
    }
}

// Inside subprojects {}, the libs accessor would look up the subproject's catalog extension.
val catalog = libs

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(8)
        }
        withSourcesJar()
    }

    // With JFrog credentials, resolve through Acquia's proxy; without them, from Maven Central.
    repositories {
        if (hasJfrogCredentials) {
            maven {
                url = uri("https://jfrog.ais.acquia.io/artifactory/widen-java-virtual/")
                useJfrogCredentials()
            }
        } else {
            mavenCentral()
        }
    }

    dependencies {
        "testImplementation"(platform(catalog.junit.bom))
        "testImplementation"(catalog.junit.jupiter)
        "testImplementation"(catalog.assertj.core)
        "testRuntimeOnly"(catalog.junit.launcher)
    }

    tasks.named<Test>("test") {
        useJUnitPlatform()
    }

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("mavenJava") {
                from(components["java"])
            }
        }
        repositories {
            maven {
                name = "jfrog"
                val isSnapshot = version.toString().endsWith("-SNAPSHOT")
                val repository = if (isSnapshot) "libs-snapshots-local" else "libs-releases-local"
                url = uri("https://jfrog.ais.acquia.io/artifactory/$repository")
                // Gradle rejects half-set credentials before any task action runs, so set
                // them only when both exist and let the check below name what is missing.
                if (hasJfrogCredentials) {
                    useJfrogCredentials()
                }
            }
        }
    }

    tasks.withType<PublishToMavenRepository>().configureEach {
        val missing = missingJfrogCredentials
        doFirst {
            if (repository.name == "jfrog" && missing.isNotEmpty()) {
                throw GradleException(
                    "Set ${missing.joinToString(" and ")} in the environment or in ~/.gradle/gradle.properties"
                )
            }
        }
    }
}
