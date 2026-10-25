import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing is read from a gitignored keystore.properties at the project root.
// When it is absent (e.g. a fresh clone or CI without secrets) the release build is
// simply left unsigned instead of failing — no keys ever live in the repo.
// MImic account (4.4): Firebase project values from a gitignored firebase.properties (see
// docs/ACCOUNT_SETUP.md). Absent → sign-in shows "not configured"; nothing secret is in the repo.
val firebaseProps = Properties().apply {
    rootProject.file("firebase.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
}
fun firebaseField(key: String) = "\"" + (firebaseProps.getProperty(key) ?: "").replace("\"", "") + "\""

val keystorePropsFile = rootProject.file("keystore.properties")
val hasKeystore = keystorePropsFile.exists()
val keystoreProps = Properties()
if (hasKeystore) {
    val reader = keystorePropsFile.reader()
    keystoreProps.load(reader)
    reader.close()
}

android {
    namespace = "com.example.mimic"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.mimic"
        minSdk = 24
        targetSdk = 37
        // ── App version: change ONLY versionName for a release (see docs/RELEASING.md). versionCode
        // is derived from it (4.1.2 → 400_100_299; betas get their own lower codes), so every
        // release is higher than the last and installs over it. 3.3 was versionCode 19.
        versionName = "4.5.0-beta.1"
        versionCode = versionCodeFor(versionName!!)

        // In-app updater: the public repository whose GitHub Releases MImic trusts (docs/RELEASING.md).
        buildConfigField("String", "UPDATE_GITHUB_OWNER", "\"enoluca22-dota2\"")
        buildConfigField("String", "UPDATE_GITHUB_REPO", "\"MImic\"")
        // SHA-256 of the signing certificate every MImic release must carry (the update is
        // rejected otherwise). It is the same certificate the installed app is signed with.
        buildConfigField("String", "RELEASE_CERT_SHA256", "\"C03C51075A49A049387185C775626074368AA9F4220EB5BA0E74CC4A4F88087D\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "FIREBASE_API_KEY", firebaseField("firebase.apiKey"))
        buildConfigField("String", "FIREBASE_APP_ID", firebaseField("firebase.appId"))
        buildConfigField("String", "FIREBASE_PROJECT_ID", firebaseField("firebase.projectId"))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", firebaseField("google.webClientId"))

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

    // Editions (same application ID, so either installs over the other):
    //  full     — everything, including the media downloader merged from ENAGELYUCA. It bundles
    //             youtubedl-android (GPL-3.0) and yt-dlp; distributed from GitHub, not Google Play.
    //  standard — no downloader and no GPL/yt-dlp code; the edition to use for any store listing.
    flavorDimensions += "edition"
    productFlavors {
        create("full") {
            dimension = "edition"
            isDefault = true
            buildConfigField("Boolean", "HAS_DOWNLOADER", "true")
        }
        create("standard") {
            dimension = "edition"
            buildConfigField("Boolean", "HAS_DOWNLOADER", "false")
        }
    }

    // youtubedl-android ships python/ffmpeg binaries per ABI. One APK per ABI keeps downloads
    // small; the universal APK is the fallback. The updater picks the one matching the phone.
    splits {
        abi {
            // Off only for instrumented-test builds (one test APK per run). The standard edition has
            // no native code, so its "universal" APK is the only meaningful one.
            isEnable = gradle.startParameter.taskNames.none { it.contains("AndroidTest") }
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // youtubedl-android runs its python/ffmpeg binaries from nativeLibraryDir, so native
            // libs must be extracted at install time. 32-bit x86 is never shipped.
            useLegacyPackaging = true
            excludes += "lib/x86/**"
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
        // java.time / java.util.Base64 used by the ported BitChord code, on Android 7 (API 24-25).
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

/*
 * NewPipeExtractor ships its own org.schabi.newpipe.extractor.utils.Utils, and src/full carries
 * BitChord's patched copy at the same path. A release build merges all dex inputs and rejects the
 * duplicate, so the library's copy is stripped from the jar first (same approach as BitChord).
 */
// Full edition: protobuf-javalite 4.x (NewPipe/BitChord) already contains the well-known types
// that Firebase ships separately as protolite-well-known-types; keep one copy (the newer runtime).
configurations.matching { it.name.startsWith("full") }.configureEach {
    exclude(group = "com.google.firebase", module = "protolite-well-known-types")
}

val newPipeExtractorRaw: Configuration by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
}
dependencies {
    newPipeExtractorRaw("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
}
val newPipeExtractorStripped = tasks.register<org.gradle.api.tasks.bundling.Jar>("stripNewPipeExtractorUtils") {
    archiveFileName.set("NewPipeExtractor-v0.26.3-noutils.jar")
    destinationDirectory.set(layout.buildDirectory.dir("stripped-libs"))
    from(provider { newPipeExtractorRaw.map { zipTree(it) } }) {
        exclude("org/schabi/newpipe/extractor/utils/Utils.class")
        exclude("org/schabi/newpipe/extractor/utils/Utils\$*.class")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
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
    // Cast to Chromecast / Google TV (4.1).
    implementation(libs.androidx.media3.cast)
    // Live radio: many stations (BBC, RTL, Rai…) stream as HLS (4.2).
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.mediarouter)
    implementation(libs.androidx.appcompat) // Cast picker theme
    implementation(libs.androidx.fragment) // Cast picker dialog host (FragmentActivity)
    implementation(libs.coil.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.palette)
    implementation(libs.okhttp)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.work.runtime)
    // Media downloader (full edition only) — ported from ENAGELYUCA.
    "fullImplementation"(libs.youtubedl.library)
    "fullImplementation"(libs.youtubedl.ffmpeg)
    // yt-dlp's raw JSON is read with the Jackson that youtubedl-android already bundles.
    "fullCompileOnly"(libs.jackson.databind.ytdl)
    "fullImplementation"(libs.androidx.documentfile)
    "testFullImplementation"(libs.jackson.databind.ytdl)
    // Online music (full edition only) — stream resolution ported from BitChord (GPL-3.0).
    "fullImplementation"("com.github.MetrolistGroup.innertubex:innertubex-android:v0.7.0")
    // NewPipeExtractor v0.26.3 minus its Utils class (a patched copy lives in src/full, as in BitChord).
    "fullImplementation"(files(newPipeExtractorStripped))
    "fullImplementation"("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    "fullImplementation"("org.jsoup:jsoup:1.22.2")
    "fullImplementation"("com.google.code.findbugs:jsr305:3.0.2")
    "fullImplementation"("com.google.protobuf:protobuf-javalite:4.35.0")
    "fullImplementation"("org.mozilla:rhino:1.8.1")
    "fullImplementation"("org.mozilla:rhino-engine:1.8.1")
    "fullImplementation"("io.github.dokar3:quickjs-kt-android:1.0.14")
    "fullImplementation"("androidx.security:security-crypto:1.1.0-alpha06")
    "fullImplementation"("io.ktor:ktor-client-okhttp:3.5.2")
    "fullImplementation"("io.ktor:ktor-client-content-negotiation:3.5.2")
    "fullImplementation"("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
    "fullImplementation"("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // MImic account + sync (4.4): Sign in with Google via Credential Manager, Firebase Auth, Firestore.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
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

/**
 * versionName → versionCode; must match AppVersion.versionCodeFor in the app's updater.
 *   4.1.2          → 400_100_299   (stable: suffix 99)
 *   4.1.2-alpha.N  → 4001002_00 + N        (1..32)
 *   4.1.2-beta.N   → 4001002_00 + 33 + N   (34..65)
 *   4.1.2-rc.N     → 4001002_00 + 66 + N   (67..98)
 * so every pre-release has its own code and sorts below the final release. Up to major 20.
 */
fun versionCodeFor(name: String): Int {
    val numbers = name.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val (major, minor, patch) = List(3) { numbers.getOrElse(it) { 0 }.coerceIn(0, 999) }
    val pre = name.substringAfter('-', "").lowercase()
    val n = pre.substringAfterLast('.').toIntOrNull()?.coerceIn(1, 32) ?: 1
    val suffix = when {
        pre.isEmpty() -> 99
        pre.startsWith("alpha") -> n
        pre.startsWith("beta") -> 33 + n
        pre.startsWith("rc") -> 66 + n
        else -> n
    }
    return (major * 1_000_000 + minor * 1_000 + patch) * 100 + suffix
}
