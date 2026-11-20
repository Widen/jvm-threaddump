plugins {
    `java-library`
}

dependencies {
    api(project(":jvm-threaddump-generator"))
    compileOnly(libs.servlet.api)
}
