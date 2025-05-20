plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" // Compose compiler plugin
}

android {
    namespace = "com.wzoun.blenfc"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wzoun.blenfc"
        minSdk = 23
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
        compose = true // Enable compose
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "2.0.0" // Make sure this matches your Kotlin version
    }
}

dependencies {

    // Lifecycle components - KTX (ViewModel, LiveData, Fragment, Lifecycle-Runtime, Activity)
    // Using the latest versions you had listed and removing duplicates
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0") // Kept as its specific version
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // Core Android KTX, AppCompat, Material, ConstraintLayout
    // Using the latest versions you had listed, removing duplicates, and fixing a typo
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4") // Fixed: removed trailing quote '

    // Coroutines for background tasks and Flows
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Compose dependencies
    // 建议：检查并使用与 Kotlin 2.0.0 / Compose Compiler 2.0.0 匹配的最新 Compose BOM
    // 例如：implementation(platform("androidx.compose:compose-bom:2024.06.00")) // 请查阅官方文档确认
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation(libs.androidx.navigation.fragment.ktx)

    // Debug implementations for Compose
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Test dependencies
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
