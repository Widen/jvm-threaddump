plugins {
    `java-library`
    `maven-publish`
}

fun jfrogCredential(name: String): String =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
        ?: throw GradleException("Set $name in the environment or in ~/.gradle/gradle.properties")

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

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = project.name
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "jfrog"
            val repository = if (project.version.toString().endsWith("-SNAPSHOT")) "libs-snapshots-local" else "libs-releases-local"
            url = uri("https://jfrog.ais.acquia.io/artifactory/$repository")
            credentials {
                username = jfrogCredential("ACQUIA_JFROG_USER")
                password = jfrogCredential("ACQUIA_JFROG_TOKEN")
            }
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
}
