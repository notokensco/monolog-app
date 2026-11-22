import java.security.KeyStore
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val monologTestingKeystore = providers.gradleProperty("monologTestingKeystore")
    .map { rootProject.file(it) }.orNull

android {
    namespace = "com.notokens.monolog"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.notokens.monolog"
        minSdk = 35
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.0-prototype.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val webEndpoint = providers.gradleProperty("monologWebEndpoint").orElse("").get()
        require(webEndpoint.isEmpty() || webEndpoint.matches(Regex("https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?(?:/[A-Za-z0-9._/-]*)?"))) {
            "monologWebEndpoint must be an HTTPS endpoint without credentials, query parameters or fragments"
        }
        buildConfigField("String", "MONOLOG_WEB_ENDPOINT", "\"$webEndpoint\"")
        providers.gradleProperty("monologAbi").orNull?.let { abi ->
            ndk.abiFilters.add(abi)
        }
    }

    val testingSigning = monologTestingKeystore?.let { keystore ->
        val androidDebug = signingConfigs.getByName("debug")
        signingConfigs.create("monologTesting") {
            storeFile = keystore
            storePassword = androidDebug.storePassword
            keyAlias = androidDebug.keyAlias
            keyPassword = androidDebug.keyPassword
        }
    }

    buildTypes {
        debug {
            if (testingSigning != null) signingConfig = testingSigning
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("optimized") {
            initWith(getByName("release"))
            signingConfig = testingSigning ?: signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

monologTestingKeystore?.let { keystore ->
    val signing = android.signingConfigs.getByName("monologTesting")
    val alias = requireNotNull(signing.keyAlias)
    val password = requireNotNull(signing.storePassword)
    val verifyCertificate = tasks.register("verifyMonologTestingCertificate") {
        inputs.file(keystore).withPathSensitivity(PathSensitivity.NONE)
        doLast {
            val certificate = KeyStore.getInstance(keystore, password.toCharArray()).getCertificate(alias)
            checkNotNull(certificate) { "The Monolog testing keystore is missing the $alias certificate." }
            val sha256 = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString("") { "%02x".format(it) }
            check(sha256 == "aa5cfb038cdff724d43e2de3f158e3761d80c840f9d4e5948ea02f322acf6f69") {
                "The Monolog testing certificate has SHA-256 $sha256. Use the original keystore to build an APK that can update the installed app."
            }
        }
    }
    tasks.matching {
        it.name in setOf("validateSigningDebug", "validateSigningOptimized", "validateSigningDebugAndroidTest")
    }.configureEach {
        dependsOn(verifyCertificate)
    }
}

dependencies {
    implementation("com.google.mediapipe:tasks-text:1.2.3")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.google.android.gms:play-services-auth:21.5.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    testImplementation("org.json:json:20240303")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.commonmark)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.litert.lm)
    implementation(libs.mlkit.image.labeling)
    implementation(libs.mlkit.objectDetection)
    implementation(libs.mlkit.translation)
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.mlkit.genai.rewriting)
    implementation(libs.mlkit.genai.image.description)
    implementation(libs.kotlinx.coroutines.tasks)
    implementation(libs.kotlinx.coroutines.guava)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
