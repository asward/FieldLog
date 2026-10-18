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
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
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

    // UniFFI's generated Kotlin talks to the Rust library through JNA.
    implementation("net.java.dev.jna:jna:5.15.0@aar")
}
