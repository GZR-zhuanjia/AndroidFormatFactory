plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val nativeAbis = providers.gradleProperty("nativeAbis").orElse("arm64-v8a,x86_64")
    .get().split(',').map(String::trim).filter(String::isNotEmpty)
require(nativeAbis.isNotEmpty() && nativeAbis.all { it in setOf("arm64-v8a", "x86_64") }) {
    "nativeAbis must contain arm64-v8a and/or x86_64"
}

val checkNativeDependencies by tasks.registering {
    group = "verification"
    description = "Require reproducibly built FFmpeg/LAME libraries for every selected ABI."
    doLast {
        val required = listOf("include/libavformat/avformat.h", "include/libavcodec/avcodec.h",
            "include/libswresample/swresample.h", "lib/libavformat.so", "lib/libavcodec.so",
            "lib/libavutil.so", "lib/libswresample.so", "lib/libmp3lame.so")
        val missing = nativeAbis.flatMap { abi -> required.map { rootProject.file("native-deps/$abi/$it") } }
            .filterNot { it.isFile }
        check(missing.isEmpty()) {
            "Native dependencies missing. Run ./scripts/build-native.sh --abis ${nativeAbis.joinToString(",")} " +
                "with ANDROID_NDK_HOME pointing to NDK 28.2.13676358. Missing: " + missing.joinToString { it.path }
        }
    }
}

tasks.configureEach {
    if (name == "preBuild" || name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(checkNativeDependencies)
    }
}

android {
    namespace = "com.zhuanformat.app"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.zhuanformat.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += nativeAbis }
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_shared" }
        }
    }

    buildFeatures { compose = true }
    sourceSets.getByName("main").assets.srcDir(rootProject.file("docs/licenses"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    lint { abortOnError = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.media3:media3-transformer:1.6.1")
    implementation("androidx.media3:media3-effect:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
