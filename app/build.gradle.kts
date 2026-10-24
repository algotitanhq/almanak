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

// Version: versionCode is derived from versionName (major * 10000 + minor * 100 + patch), see DECISIONS.md.
val almanakVersion = "1.0.0"
val almanakVersionCode = run {
    val (major, minor, patch) = requireNotNull(Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(almanakVersion)) {
        "versionName $almanakVersion: must be MAJOR.MINOR.PATCH"
    }.destructured.toList().map(String::toInt)
    require(minor < 100 && patch < 100) { "versionName $almanakVersion: minor and patch must stay below 100" }
    major * 10000 + minor * 100 + patch
}

// Release signing, from the environment only (never local.properties, never the repo). All four set: the release
// APK is signed with that key. None set: the release APK stays unsigned (app-release-unsigned.apk), never signed
// with a debug key. Some set: the build fails, so a half-configured CI cannot ship an unsigned or wrong APK.
val releaseSigningEnv = listOf("ALMANAK_KEYSTORE", "ALMANAK_KEYSTORE_PASSWORD", "ALMANAK_KEY_ALIAS", "ALMANAK_KEY_PASSWORD")
    .associateWith { System.getenv(it).orEmpty() }
val releaseSigningMissing = releaseSigningEnv.filterValues { it.isEmpty() }.keys
val releaseSigned = releaseSigningMissing.isEmpty()
val releaseKeystore = releaseSigningEnv.getValue("ALMANAK_KEYSTORE").takeIf { it.isNotEmpty() }?.let(::file)
// Checked when a release variant builds, so a stray variable never blocks debug builds or IDE sync.
val releaseSigningProblem = when {
    !releaseSigned && releaseSigningMissing.size < releaseSigningEnv.size ->
        "Release signing is half configured: missing $releaseSigningMissing (set all four or none)"
    releaseSigned && releaseKeystore?.isFile != true -> "ALMANAK_KEYSTORE does not point to a file: $releaseKeystore"
    else -> null
}

android {
    namespace = "app.almanak"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "app.almanak"
        minSdk = 28
        targetSdk = 36
        versionCode = almanakVersionCode
        versionName = almanakVersion
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
        // Robolectric reads the bundled fonts, so text-fit tests lay text out as the TV does (ALG-221).
        unitTests.isIncludeAndroidResources = true
    }

    signingConfigs {
        if (releaseSigned) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseSigningEnv.getValue("ALMANAK_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningEnv.getValue("ALMANAK_KEY_ALIAS")
                keyPassword = releaseSigningEnv.getValue("ALMANAK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Explicit null: an unsigned release must never fall back to another key.
            signingConfig = if (releaseSigned) signingConfigs.getByName("release") else null
            // A published APK never carries an API key: the build-time realtime key is for debug builds only.
            buildConfigField("String", "REALTIME_API_KEY", "\"\"")
            buildConfigField("String", "REALTIME_KEY_AGENCY", "\"\"")
            buildConfigField("String", "LEGACY_REALTIME_KEY", "\"\"")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    val problem = releaseSigningProblem
    doFirst { problem?.let { throw GradleException(it) } }
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
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
