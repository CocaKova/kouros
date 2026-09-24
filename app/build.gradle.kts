import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing comes from local.properties (never committed):
//   kouros.keystore=/absolute/path/to/release.keystore
//   kouros.keystore.password=…
//   kouros.key.alias=…
//   kouros.key.password=…
// Absent those, release builds fall back to the debug keystore (sideload/dev convenience).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseKeystorePath: String? = localProps.getProperty("kouros.keystore")

// Supporter overlay: when a sibling checkout named kouros-ivory sits beside this repo, an
// "ivory" flavor appears with its sources. The public repo is complete without it — foss is
// the whole app and the only flavor most builders will ever see.
val ivoryDir = rootProject.file("../kouros-ivory")

android {
    namespace = "com.cocakova.kouros"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.cocakova.kouros"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "tier"
    productFlavors {
        create("foss") {
            dimension = "tier"
            isDefault = true
        }
        if (ivoryDir.exists()) {
            create("ivory") {
                dimension = "tier"
                versionNameSuffix = "+ivory"
            }
        }
    }
    sourceSets {
        // Built-in Kotlin (no kotlin-android plugin) compiles only the kotlin source dirs;
        // java.srcDir alone would leave the flavor's .kt files unseen.
        if (ivoryDir.exists()) {
            getByName("ivory") {
                java.srcDir(ivoryDir.resolve("src/main/java"))
                kotlin.srcDir(ivoryDir.resolve("src/main/java"))
                res.srcDir(ivoryDir.resolve("src/main/res"))
            }
            getByName("testIvory") {
                java.srcDir(ivoryDir.resolve("src/test/java"))
                kotlin.srcDir(ivoryDir.resolve("src/test/java"))
            }
        }
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = localProps.getProperty("kouros.keystore.password")
                keyAlias = localProps.getProperty("kouros.key.alias")
                keyPassword = localProps.getProperty("kouros.key.password")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystorePath != null) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = false
        shaders = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.okhttp)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.coil3.compose)
    implementation(libs.coil3.network.okhttp)
    implementation(libs.coil3.gif)
    implementation(libs.coil3.video)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.tink.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
