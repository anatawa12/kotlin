plugins {
    id("org.jetbrains.kotlin.multiplatform").version("<pluginMarkerVersion>")
    id("maven-publish")
}

group = "org.sample"
version = "2.0"

repositories {
    mavenLocal()
    mavenCentral()
}

kotlin {
    <SingleNativeTarget>("native")
}

publishing {
    repositories {
        maven {
            url = file("<LocalRepo>").toURI()
        }
    }
}
