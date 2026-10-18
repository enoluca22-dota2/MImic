import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing is read from a gitignored keystore.properties at the project root.
// When it is absent (e.g. a fresh clone or CI without secrets) the release build is
// simply left unsigned instead of failing — no keys ever live in the repo.
val keystorePropsFile = rootProject.file("keystore.properties")
val hasKeystore = keystorePropsFile.exists()
val keystoreProps = Properties()
if (hasKeystore) {
    val reader = keystorePropsFile.reader()
    keystoreProps.load(reader)
    reader.close()
}

// Cloud sign-in configuration (MImic 3.1) from a gitignored cloud.properties. These are PUBLIC
// client identifiers, not secrets (no client secret exists for a mobile app) — they are kept out
// of git only because they belong to the user's own app registrations. See docs/CLOUD_SETUP.md.
val cloudPropsFile = rootProject.file("cloud.properties")
val cloudProps = Properties()
if (cloudPropsFile.exists()) cloudPropsFile.reader().use { cloudProps.load(it) }
val msalClientId: String = cloudProps.getProperty("msal.clientId", "").trim()
// Base64 SHA-1 of the APK signing certificate; the default is this machine's debug keystore,
// which signs every MImic APK installed so far (no release keystore exists).
val msalSignatureHash: String = cloudProps.getProperty("msal.signatureHash", "eA5L9wOTGbvwmdX3ojvGnABaX/k=").trim()

android {
    namespace = "com.example.mimic"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.mimic"
        minSdk = 24
        targetSdk = 37
        versionCode = 18
        versionName = "3.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "MSAL_CLIENT_ID", "\"$msalClientId\"")
        buildConfigField("String", "MSAL_SIGNATURE_HASH", "\"$msalSignatureHash\"")
        manifestPlaceholders["msalSignatureHash"] = msalSignatureHash
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.palette)
    implementation(libs.okhttp)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.work.runtime)
    implementation(libs.msal)
    implementation(libs.play.services.auth)
    implementation(libs.kyant.backdrop)
    implementation(libs.kyant.shapes)
    implementation(libs.material.color.utilities)
    implementation(libs.colorpicker.compose)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json.test) // real org.json for JVM tests (android.jar only has stubs)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}