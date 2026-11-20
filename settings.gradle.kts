pluginManagement {
    includeBuild("build-logic")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "jvm-threaddump"

include("generator")
include("servlet")

project(":generator").name = "jvm-threaddump-generator"
project(":servlet").name = "jvm-threaddump-servlet"
