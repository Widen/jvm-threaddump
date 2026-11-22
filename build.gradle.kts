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
            languageVersion = JavaLanguageVersion.of(17)
        }
        withSourcesJar()
    }

    // Compile on 17 for Java 8, so the JARs load on Java 8 and testOnJava8 can run the tests there.
    tasks.withType<JavaCompile>().configureEach {
        options.release = 8
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
        "testImplementation"(catalog.mockito.core)
        "testRuntimeOnly"(catalog.junit.launcher)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Tests assert formatted output, so pin the locale that formats it.
        systemProperty("user.language", "en")
        systemProperty("user.country", "US")
    }

    val testSourceSet = the<SourceSetContainer>()["test"]
    val java8Launcher = the<JavaToolchainService>().launcherFor {
        languageVersion = JavaLanguageVersion.of(8)
    }
    val testOnJava8 = tasks.register<Test>("testOnJava8") {
        description = "Runs the tests on a Java 8 JVM."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        javaLauncher = java8Launcher
    }
    tasks.named("check") {
        dependsOn(testOnJava8)
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
