// :core — everything about ComfyUI that is not Android.
//
// commonMain compiles against the common stdlib, coroutines, serialization and ktor-client-core
// only, so the compiler keeps android.* and java.* out: the protocol, the workflow graph, the
// compiler and the form engine are plain Kotlin that an iOS or desktop client could reuse.
//
// Targets: jvm (what the Android app consumes, and where the golden corpus runs) plus the iOS
// pair, which only build on a macOS host — on Linux they are declared and never asked for.
plugins {
    // No version: AGP 9 already owns the Kotlin plugin classpath.
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kotlin.serialization)
}

tasks.withType<Test>().configureEach {
    // Live end-to-end test: KOUROS_LIVE_SERVER=http://host:8188 KOUROS_LIVE_WORKFLOW=path/to/workflow.json
    listOf("KOUROS_LIVE_SERVER", "KOUROS_LIVE_WORKFLOW", "KOUROS_LIVE_PROMPT", "KOUROS_SOCKET_PROBE", "KOUROS_LIVE_ASSIST", "KOUROS_LIVE_ASSIST_KEY", "KOUROS_LIVE_ASSIST_ASK").forEach { k -> System.getenv(k)?.let { environment(k, it) } }
}

kotlin {
    jvmToolchain(17)
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmTest.dependencies {
            // The opt-in live test talks to a real server.
            implementation(libs.ktor.client.okhttp)
        }
    }
}
