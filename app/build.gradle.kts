import com.android.build.api.variant.ApplicationVariant

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization") version "2.2.21"
    kotlin("plugin.compose") version "2.2.21"
    id("com.google.gms.google-services")
}

android {
    compileSdk = 35

    defaultConfig {
        applicationId = "com.speakkeys.keyboard"
        minSdk = 24
        targetSdk = 35
        versionCode = 100
        versionName = "v0.1.0"
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
        }
        create("nouserlib") { // same as release, but does not allow the user to provide a library
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
        }
        debug {
            isMinifyEnabled = false
            isJniDebuggable = false
        }
        create("runTests") { // build variant for running tests on CI that skips tests known to fail
            isMinifyEnabled = false
            isJniDebuggable = false
        }
        create("debugNoMinify") { // for faster builds in IDE
            isDebuggable = true
            isMinifyEnabled = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
        }
        base.archivesName.set("SpeakKeys_" + defaultConfig.versionName)
        // got a little too big for GitHub after some dependency upgrades, so we remove the largest dictionary
        androidComponents.onVariants { variant: ApplicationVariant ->
            if (variant.buildType == "debug") {
                variant.androidResources.ignoreAssetsPatterns = listOf("main_ro.dict")
                variant.proguardFiles = emptyList()
                //noinspection ProguardAndroidTxtUsage we intentionally use the "normal" file here
                variant.proguardFiles.add(project.layout.buildDirectory.file(getDefaultProguardFile("proguard-android.txt").absolutePath))
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/proguard-rules.pro"))
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        ndkBuild {
            path = File("src/main/jni/Android.mk")
        }
    }
    ndkVersion = "28.0.13004108"

    packaging {
        jniLibs {
            // shrinks APK by 3 MB, zipped size unchanged
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // see https://github.com/HeliBorg/HeliBoard/issues/477
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    namespace = "helium314.keyboard.latin"
    lint {
        abortOnError = true
    }
}

dependencies {
    // androidx
    implementation("androidx.core:core-ktx:1.16.0") // 1.17 requires SDK 36
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.autofill:autofill:1.3.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // compose
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime-livedata")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.9.6")
    implementation("sh.calvin.reorderable:reorderable:2.4.3")
    implementation("com.github.skydoves:colorpicker-compose:1.1.3")

    // shared KMP module (voice recognition pipeline)
    implementation(project(":shared"))

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:34.9.0"))
    implementation("com.google.firebase:firebase-auth")

    // Credential Manager (for Google Sign-In)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // JetPref DataStore (for voice-specific preferences)
    implementation("dev.patrickgold.jetpref:jetpref-datastore-model:0.1.0-beta14")
    implementation("dev.patrickgold.jetpref:jetpref-datastore-ui:0.1.0-beta14")
    implementation("dev.patrickgold.jetpref:jetpref-material-ui:0.1.0-beta14")

    // LiveData (for voice UI state)
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // test
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.17.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:runner:1.6.2")
    testImplementation("androidx.test:core:1.6.1")
}
