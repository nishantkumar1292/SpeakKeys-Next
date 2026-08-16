import com.android.build.api.variant.ApplicationVariant

val speakKeysVersionCode = providers.gradleProperty("speakkeysVersionCode").orNull?.let { value ->
    val parsed = value.toIntOrNull()
        ?: error("speakkeysVersionCode must be an integer, got: $value")
    require(parsed in 1..2_100_000_000) {
        "speakkeysVersionCode must be between 1 and 2100000000, got: $parsed"
    }
    parsed
} ?: 102

val speakKeysVersionNameSuffix = providers.gradleProperty("speakkeysVersionNameSuffix").orNull
    ?.let { value ->
        require(value.matches(Regex("[0-9A-Za-z][0-9A-Za-z._-]{0,63}"))) {
            "speakkeysVersionNameSuffix must be 1-64 filename-safe characters, got: $value"
        }
        value
    }
val speakKeysVersionName = "v0.1.2" + speakKeysVersionNameSuffix?.let { "+$it" }.orEmpty()

val speakKeysUnsignedRelease = providers.gradleProperty("speakkeysUnsignedRelease").orNull?.let { value ->
    value.toBooleanStrictOrNull()
        ?: error("speakkeysUnsignedRelease must be true or false, got: $value")
} ?: false

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization") version "2.2.21"
    kotlin("plugin.compose") version "2.2.21"
    id("com.google.gms.google-services")
}

android {
    compileSdk = 36

    defaultConfig {
        applicationId = "com.speakkeys.keyboard"
        minSdk = 24
        targetSdk = 36
        versionCode = speakKeysVersionCode
        versionName = speakKeysVersionName
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }

    signingConfigs {
        create("release") {
            val storePath = if (speakKeysUnsignedRelease) {
                null
            } else {
                (project.findProperty("SPEAKKEYS_STORE_FILE") as String?)
                    ?: System.getenv("SPEAKKEYS_STORE_FILE")
            }
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = (project.findProperty("SPEAKKEYS_STORE_PASSWORD") as String?)
                    ?: System.getenv("SPEAKKEYS_STORE_PASSWORD")
                keyAlias = (project.findProperty("SPEAKKEYS_KEY_ALIAS") as String?)
                    ?: System.getenv("SPEAKKEYS_KEY_ALIAS")
                keyPassword = (project.findProperty("SPEAKKEYS_KEY_PASSWORD") as String?)
                    ?: System.getenv("SPEAKKEYS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        base.archivesName.set("SpeakKeys_$speakKeysVersionName")
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
    implementation("androidx.core:core-ktx:1.16.0")
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
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:runner:1.6.2")
    testImplementation("androidx.test:core:1.6.1")
}
