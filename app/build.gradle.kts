plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    compileSdk = 36

    defaultConfig {
        applicationId = "com.barakplasma.privateaitranslate"
        minSdk = 33
        targetSdk = 35
        versionCode = 58
        versionName = "19.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    flavorDimensions += "internet"
    productFlavors {
        create("full") {
            dimension = "internet"
            buildConfigField("Boolean", "ON_DEVICE_ONLY", "false")
            buildConfigField("Boolean", "INCLUDE_GOOGLE_SERVICES", "true")
        }
        create("noInternet") {
            dimension = "internet"
            buildConfigField("Boolean", "ON_DEVICE_ONLY", "true")
            buildConfigField("Boolean", "INCLUDE_GOOGLE_SERVICES", "true")
            versionNameSuffix = "-offline"
        }
        create("pureOffline") {
            dimension = "internet"
            buildConfigField("Boolean", "ON_DEVICE_ONLY", "true")
            buildConfigField("Boolean", "INCLUDE_GOOGLE_SERVICES", "false")
            versionNameSuffix = "-pureoffline"
            applicationIdSuffix = ".pureoffline"
        }
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    namespace = "com.barakplasma.privateaitranslate"

}

// Force the Android-flavored Guava to fix GHSA-5mg8-w23w-74h3 and GHSA-7g45-4rm6-3mm3
// (transitive deps pull in the vulnerable guava:31.0.1-jre)
configurations.all {
    resolutionStrategy {
        force("com.google.guava:guava:33.4.8-android")
    }
}

dependencies {
    val composeVersion: String by rootProject.extra
    // Android Core
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    // Compose
    implementation("androidx.compose.ui:ui:$composeVersion")
    implementation("androidx.compose.ui:ui-tooling-preview:$composeVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:$composeVersion")
    debugImplementation("androidx.compose.ui:ui-tooling:$composeVersion")
    debugImplementation("androidx.compose.ui:ui-test-manifest:$composeVersion")
    implementation("androidx.navigation:navigation-compose:2.9.7")
    implementation("com.materialkolor:material-kolor:3.0.1")

    // Retrofit & API
    implementation(project(":translation-engines"))
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    // retrofit:3.0.0 based on okhttp3:4.12.0

    // Room database
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // Tesseract OCR
    implementation("cz.adaptech.tesseract4android:tesseract4android-openmp:4.9.0")

    // Dynamic color scheme
    implementation("com.google.android.material:material:1.14.0")

    // Gemini Nano and ML Kit Translation — excluded from pureOffline to prevent any Google
    // Play Services telemetry; pureOffline uses stub engine classes instead.
    "fullImplementation"("com.google.mlkit:genai-prompt:1.0.0-beta2")
    "noInternetImplementation"("com.google.mlkit:genai-prompt:1.0.0-beta2")
    "fullImplementation"("com.google.mlkit:translate:17.0.3")
    "noInternetImplementation"("com.google.mlkit:translate:17.0.3")
    "fullImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    "noInternetImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // ML Kit Text Recognition (OCR) + Language Identification — excluded from pureOffline (no Google Play)
    "fullImplementation"("com.google.mlkit:language-id:17.0.6")
    "noInternetImplementation"("com.google.mlkit:language-id:17.0.6")
    "fullImplementation"("com.google.mlkit:text-recognition:16.0.1")
    "noInternetImplementation"("com.google.mlkit:text-recognition:16.0.1")
    "fullImplementation"("com.google.mlkit:text-recognition-chinese:16.0.1")
    "noInternetImplementation"("com.google.mlkit:text-recognition-chinese:16.0.1")
    "fullImplementation"("com.google.mlkit:text-recognition-japanese:16.0.1")
    "noInternetImplementation"("com.google.mlkit:text-recognition-japanese:16.0.1")
    "fullImplementation"("com.google.mlkit:text-recognition-korean:16.0.1")
    "noInternetImplementation"("com.google.mlkit:text-recognition-korean:16.0.1")
    "fullImplementation"("com.google.mlkit:text-recognition-devanagari:16.0.1")
    "noInternetImplementation"("com.google.mlkit:text-recognition-devanagari:16.0.1")

    // LiteRT-LM on-device LLM inference — no telemetry, included in all flavors
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")

    // Sentry for crash reporting (sentry-android includes NDK, ANR, and proper Android integrations)
    implementation("io.sentry:sentry-android:8.43.1")
}
