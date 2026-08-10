plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization)
    id("com.google.protobuf")
}

android {
    namespace = "com.simonproyt.legacysignal"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.simonproyt.legacysignal"
        minSdk = 18
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }




    

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    
    // Protobuf
    implementation("com.google.protobuf:protobuf-javalite:3.21.12")

    // Signal
    implementation(files("libs/libsignal-android-0.86.5-compat.aar"))
    implementation(files("libs/libsignal-client-0.86.5.jar"))
    implementation(libs.conscrypt.android)
    implementation("org.bouncycastle:bcprov-jdk15to18:1.77")

    // Legacy networking
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)
    implementation(libs.retrofit)
    implementation("com.squareup.retrofit2:converter-gson:2.6.4")
    implementation("com.google.code.gson:gson:2.8.9")

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // GeckoView for backported modern WebView
    implementation("org.mozilla.geckoview:geckoview:73.0.20200217142647") {
        exclude(group = "com.android.support")
    }

    // Tests
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.tracing:tracing:1.0.0")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.21.12"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}
