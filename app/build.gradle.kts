import org.jetbrains.kotlin.gradle.dsl.JvmTarget
// Imported, not written as java.util.Properties: inside the android { } block the Kotlin DSL
// resolves the bare name `java` to Gradle's own `java` extension, not to the Java package.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mura.mesh"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mura.mesh"
        minSdk = 28          // Android 9
        targetSdk = 36       // Android 16
        versionCode = 21
        versionName = "2.7.9"
    }

    // Release signing for Google Play: create keystore.properties next to settings.gradle.kts (never commit it):
    //   storeFile=/full/path/mura-upload.jks
    //   storePassword=…
    //   keyAlias=mura
    //   keyPassword=…
    val keystoreProps = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystoreProps.exists()) {
            val p = Properties().apply { keystoreProps.inputStream().use { load(it) } }
            create("upload") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with your upload key when keystore.properties exists; otherwise with the debug key (for testing only).
            signingConfig = if (keystoreProps.exists()) signingConfigs.getByName("upload") else signingConfigs.getByName("debug")
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
    }

    bundle {
        // Play delivers only the CPU-specific Tor library each phone needs.
        abi { enableSplit = true }
        language { enableSplit = false }   // all translations stay in the app (switching language needs no download)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // X25519 for Android 9+ (pure Java, no Google Play Services needed)
    implementation("com.google.crypto.tink:tink-android:1.17.0")

    // Video compression (H.264 re-encode) — runs entirely on the phone
    implementation("androidx.media3:media3-transformer:1.9.4")
    implementation("androidx.media3:media3-effect:1.9.4")
    implementation("androidx.media3:media3-common:1.9.4")

    // Scheduled encrypted backups (to a folder the user picks — no Google, no cloud account) and keep-alive jobs
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    // App lock (fingerprint / face / device PIN)
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // QR code show + scan for in-person key verification
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")

    // Internet without a server: Tor runs inside the app; every phone is its own onion service.
    implementation("info.guardianproject:tor-android:0.4.9.12")
    implementation("info.guardianproject:jtorctl:0.4.5.7")
    // Tor bridges / pluggable transports (Snowflake, obfs4 via Lyrebird, webtunnel, meek) — runs inside the app.
    implementation("com.netzarchitekten:IPtProxy:5.5.1")

    testImplementation("junit:junit:4.13.2")
}