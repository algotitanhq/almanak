import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun buildSetting(env: String, prop: String): String = System.getenv(env) ?: localProps.getProperty(prop, "")
// Optional: a GTFS-realtime key baked into the build, used only for the network whose id is given (e.g. "octranspo").
val realtimeKey = buildSetting("REALTIME_API_KEY", "realtime.apiKey")
val realtimeKeyAgency = buildSetting("REALTIME_KEY_AGENCY", "realtime.keyAgency")
// The older property, still honoured: it always held an STM key.
val legacyRealtimeKey = buildSetting("STM_API_KEY", "stm.apiKey")

android {
    namespace = "app.almanak"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "app.almanak"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "REALTIME_API_KEY", "\"$realtimeKey\"")
        buildConfigField("String", "REALTIME_KEY_AGENCY", "\"$realtimeKeyAgency\"")
        buildConfigField("String", "LEGACY_REALTIME_KEY", "\"$legacyRealtimeKey\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.tv.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.gtfs.realtime.bindings)
    implementation(libs.androidx.datastore)
    implementation(libs.zxing.core)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
