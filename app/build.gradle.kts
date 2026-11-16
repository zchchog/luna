import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.imdtv.iptvtv"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.imdtv.iptvtv"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.0"
    }

    // Release key, kept outside the repo: ~/.config/iptv-tv/keystore.properties (storeFile, storePassword, keyAlias,
    // keyPassword), or the file named by the IPTV_KEYSTORE_PROPERTIES environment variable. Without it, release builds
    // are signed with the debug key so anyone can build; those can't update an install signed with the release key.
    val keystoreProps = File(System.getenv("IPTV_KEYSTORE_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.config/iptv-tv/keystore.properties")
    val releaseKey = if (keystoreProps.isFile) Properties().apply { keystoreProps.inputStream().use(::load) } else null
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = file(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    sourceSets {
        // Robolectric only sees merged app assets, not test assets; debug-only so release ships without them.
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.datastore.preferences)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.dash) // DASH (.mpd) streams: iptv-org's BBC channels and some providers
    implementation(libs.media3.ui)
    implementation(libs.media3.ffmpeg)
    implementation(libs.work.runtime)
    implementation(libs.coil.compose)
    implementation(libs.smbj) // recordings on a network share

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.room.testing)
}
