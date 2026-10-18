plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val rustOut = layout.buildDirectory.dir("rust")

android {
    namespace = "io.github.asward.fieldlog"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.asward.fieldlog"
        minSdk = 28
        targetSdk = 35
        // Each CI build counts as newer, so it installs over the last one.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = run ?: 1
        versionName = if (run != null) "0.2.0-dev.$run" else "0.2.0-local"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // A fixed debug key (committed; dev only, not a secret) so every build,
    // from CI or locally, signs the same way and installs as an update
    // instead of forcing an uninstall that wipes vaults and keys.
    signingConfigs {
        getByName("debug") {
            storeFile = file("dev-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    sourceSets["main"].apply {
        jniLibs.srcDir(rustOut.map { it.dir("jniLibs") })
        java.srcDir(rustOut.map { it.dir("kotlin") })
    }

    packaging { jniLibs { useLegacyPackaging = false } }
}

// Cross-compiles core/ for each ABI with cargo-ndk and generates the UniFFI
// Kotlin bindings into build/rust. See scripts/build-core-android.sh.
val buildRustCore by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the FieldLog Rust core for Android and generate Kotlin bindings"
    val script = rootProject.file("../scripts/build-core-android.sh")
    inputs.dir(rootProject.file("../core/src"))
    inputs.file(rootProject.file("../core/Cargo.toml"))
    inputs.file(script)
    outputs.dir(rustOut)
    commandLine("bash", script.absolutePath, rustOut.get().asFile.absolutePath)
    onlyIf { (project.findProperty("fieldlog.buildRust") ?: "true").toString().toBoolean() }
}

tasks.named("preBuild") { dependsOn(buildRustCore) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // UniFFI's generated Kotlin talks to the Rust library through JNA.
    implementation("net.java.dev.jna:jna:5.15.0@aar")
}
