plugins {
    alias(libs.plugins.android.application)
}

// Optional local-only artwork. Original third-party SVGs are not part of this repository.
val privateIconAssetsDir = providers.gradleProperty("privateIconAssetsDir").orNull

// Public repository: only the user-configured edition; never read personal credentials.
android {
    namespace = "com.example.weatherapp"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.example.weatherapp"
        minSdk = 24
        targetSdk = 37
        versionCode = 8
        versionName = "1.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "USER_CONFIGURABLE", "true")
        buildConfigField("boolean", "HAS_PRIVATE_ICON_ASSETS", (privateIconAssetsDir != null).toString())
        listOf("WEATHER_GATEWAY_BASE_URL", "CAIYUN_EMBEDDED_TOKEN", "LEGACY_QWEATHER_HOST",
            "LEGACY_QWEATHER_KID", "LEGACY_QWEATHER_SUB", "LEGACY_QWEATHER_PRIVATE_KEY_PEM").forEach {
            buildConfigField("String", it, "\"\"")
        }
    }
    flavorDimensions += "credentials"
    productFlavors {
        create("userApi") {
            dimension = "credentials"
            applicationIdSuffix = ".userapi"
            versionNameSuffix = "-userapi"
        }
    }
    buildFeatures { buildConfig = true }
    if (privateIconAssetsDir != null) {
        val privateAssets = rootProject.file(privateIconAssetsDir)
        require(privateAssets.isDirectory) { "privateIconAssetsDir must point to an existing assets directory" }
        sourceSets.getByName("main").assets.srcDir(privateAssets)
    }
    buildTypes {
        release {
            // Intentionally unsigned. Keep signing material outside Git and sign locally.
            optimization { enable = false }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Public Maven artifact, compile-only; ColorOS supplies its runtime implementation.
    compileOnly("com.oplus.animation:viewseamless:1.0.0@aar")
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.androidsvg)
    implementation(libs.androidx.viewpager2)
    implementation(libs.swiperefreshlayout)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.eddsa)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
