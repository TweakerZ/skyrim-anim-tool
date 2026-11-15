buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.5.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.10")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

tasks.register("clean") {
    delete(rootProject.buildDir)
}
