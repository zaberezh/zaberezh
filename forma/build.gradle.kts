buildscript {
    val hasAndroid = System.getenv("ANDROID_HOME") != null || file("local.properties").exists()
    repositories {
        if (hasAndroid) google()
        maven("https://repo1.maven.org/maven2")
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        val kotlin = "2.2.20"
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin")
        classpath("org.jetbrains.kotlin:kotlin-serialization:$kotlin")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:$kotlin")
        if (hasAndroid) classpath("com.android.tools.build:gradle:8.13.0")
    }
}

allprojects {
    repositories {
        if (System.getenv("ANDROID_HOME") != null || rootProject.file("local.properties").exists()) google()
        maven("https://repo1.maven.org/maven2")
        mavenCentral()
    }
}
