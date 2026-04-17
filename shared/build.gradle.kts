plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    kotlin("plugin.serialization")
}

kotlin {
    androidLibrary {
        namespace = "com.elishaazaria.sayboard.shared"
        compileSdk = 35
        minSdk = 24
    }

    targets.withType<org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    macosArm64()
    macosX64()
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
            implementation("io.ktor:ktor-client-core:3.1.0")
        }
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:3.1.0")
        }
        appleMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.1.0")
        }
    }
}
