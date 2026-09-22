plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pogo.companion"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pogo.companion"
        minSdk = 26
        targetSdk = 34
        versionCode = 17
        versionName = "2.2.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // CI writes ../release-keystore.p12: the permanent key when the KEYSTORE_BASE64 /
    // KEYSTORE_PASSWORD repo secrets exist (see tools/setup-signing.sh), otherwise a throwaway.
    // Only builds signed with the permanent key can be installed over each other.
    val releaseKeystore = file("../release-keystore.p12")
    signingConfigs {
        create("release") {
            if (releaseKeystore.exists()) {
                storeFile = releaseKeystore
                storeType = "pkcs12"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "pogo-release"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

// The WebView UI is the repo-root index.html (also served as the PWA); bundle the current copy
// on every build instead of committing a second one under assets/.
val syncWebAssets by tasks.registering(Copy::class) {
    from(rootProject.file("../index.html"))
    into(layout.projectDirectory.dir("src/main/assets"))
}
val writeVersionManifest by tasks.registering {
    val out = rootProject.file("../version.json")
    val code = android.defaultConfig.versionCode
    val name = android.defaultConfig.versionName
    outputs.file(out)
    doLast {
        out.writeText("{\"versionCode\": $code, \"versionName\": \"$name\", \"apk\": \"pogo-companion.apk\", \"notes\": \"\"}\n")
    }
}
tasks.named("preBuild") { dependsOn(syncWebAssets, writeVersionManifest) }

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    
    // Google ML Kit Text Recognition (On-device OCR for screen evaluation)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.0")
}
