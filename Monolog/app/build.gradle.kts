import java.security.KeyStore
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Moonshine ships a reduced ONNX Runtime. Share the full runtime used by the journal encoder.
val moonshineSdk by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}
val moonshineRuntimeAar = tasks.register<Zip>("prepareMoonshineRuntime") {
    archiveFileName.set("moonshine-voice.aar")
    destinationDirectory.set(layout.buildDirectory.dir("generated/moonshine"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(moonshineSdk.elements.map { zipTree(it.single().asFile) }) {
        exclude("jni/**/libonnxruntime.so")
    }
}

val llamaSource = providers.gradleProperty("monologLlamaSource").map { rootProject.file(it) }.orNull
val rustTypingEnabled = providers.gradleProperty("monologRustTyping").map(String::toBoolean).orElse(true)
val rustTypingProject = layout.projectDirectory.dir("src/main/rust")
val rustTypingOutput = layout.buildDirectory.dir("generated/monologTyping/jniLibs")
val rustTypingTargets = mapOf(
    "arm64-v8a" to ("aarch64-linux-android" to "aarch64-linux-android"),
    "armeabi-v7a" to ("armv7-linux-androideabi" to "armv7a-linux-androideabi"),
    "x86" to ("i686-linux-android" to "i686-linux-android"),
    "x86_64" to ("x86_64-linux-android" to "x86_64-linux-android")
)
val rustTypingAbi = providers.gradleProperty("monologAbi")
val rustTypingNdk = providers.gradleProperty("monologRustNdk").orElse("25.1.8937393")
val rustToolchain = providers.gradleProperty("monologRustToolchain").orElse("stable")
val androidSdkDir = androidComponents.sdkComponents.sdkDirectory
val buildRustTyping = tasks.register("buildRustTyping") {
    onlyIf { rustTypingEnabled.get() }
    inputs.dir(rustTypingProject.dir("src"))
    inputs.files(rustTypingProject.file("Cargo.toml"), rustTypingProject.file("Cargo.lock"))
    inputs.property("abi", rustTypingAbi.orElse("all"))
    inputs.property("ndk", rustTypingNdk)
    inputs.property("rustToolchain", rustToolchain)
    outputs.dir(rustTypingOutput)
    doLast {
        val ndk = androidSdkDir.get().asFile.resolve("ndk/${rustTypingNdk.get()}")
        val toolchainRoot = ndk.resolve("toolchains/llvm/prebuilt")
        val toolchain = checkNotNull(toolchainRoot.listFiles()?.singleOrNull { it.isDirectory }) {
            "Install Android NDK ${rustTypingNdk.get()} or set -PmonologRustNdk to an installed version."
        }
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val linker = toolchain.resolve(if (windows) "bin/clang.exe" else "bin/clang")
        val cargoName = if (windows) "cargo.exe" else "cargo"
        val cargoHome = System.getenv("CARGO_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".cargo")
        val cargo = cargoHome.resolve("bin/$cargoName").takeIf { it.isFile }?.absolutePath ?: cargoName
        val cargoTarget = layout.buildDirectory.dir("intermediates/monologTyping/cargo").get().asFile
        val output = rustTypingOutput.get().asFile
        val targets = rustTypingAbi.orNull?.let { mapOf(it to rustTypingTargets.getValue(it)) } ?: rustTypingTargets
        for ((abi, triples) in targets) {
            val (rustTarget, clangTarget) = triples
            val flags = listOf(
                "-C", "linker=${linker.absolutePath}",
                "-C", "link-arg=--target=${clangTarget}28",
                "-C", "link-arg=-Wl,-z,max-page-size=16384"
            )
            providers.exec {
                workingDir(rustTypingProject.asFile)
                commandLine(cargo, "+${rustToolchain.get()}", "build", "--locked", "--release", "--target", rustTarget)
                environment("CARGO_TARGET_DIR", cargoTarget.absolutePath)
                environment("CARGO_ENCODED_RUSTFLAGS", flags.joinToString("\u001f"))
            }.result.get().assertNormalExitValue()
            val built = cargoTarget.resolve("$rustTarget/release/libmonolog_typing.so")
            built.copyTo(output.resolve("$abi/libmonolog_typing.so"), overwrite = true)
        }
    }
}

if (rustTypingEnabled.get()) {
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
        .configureEach { dependsOn(buildRustTyping) }
}

val ggufOutput = layout.buildDirectory.dir("generated/monologGguf/jniLibs")
if (llamaSource != null) {
    require(llamaSource.resolve("include/llama.h").isFile) { "Set monologLlamaSource to the pinned llama.cpp checkout." }
    val buildGguf = tasks.register("buildGgufRuntime") {
        val bridge = layout.projectDirectory.dir("src/main/gguf")
        inputs.dir(bridge)
        inputs.files(llamaSource.resolve("include/llama.h"), llamaSource.resolve("CMakeLists.txt"),
            llamaSource.resolve("src"), llamaSource.resolve("ggml"))
        inputs.property("abi", rustTypingAbi.orElse("64-bit"))
        inputs.property("ndk", rustTypingNdk)
        outputs.dir(ggufOutput)
        doLast {
            val sdk = androidSdkDir.get().asFile
            val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
            val suffix = if (windows) ".exe" else ""
            val cmakeBin = sdk.resolve("cmake/3.22.1/bin")
            val cmake = cmakeBin.resolve("cmake$suffix")
            val ninja = cmakeBin.resolve("ninja$suffix")
            val ndk = sdk.resolve("ndk/${rustTypingNdk.get()}")
            check(cmake.isFile && ninja.isFile) { "Install Android SDK CMake 3.22.1 for the GGUF runtime." }
            val abis = rustTypingAbi.orNull?.let { listOf(it) } ?: listOf("arm64-v8a", "x86_64")
            for (abi in abis.filter { it == "arm64-v8a" || it == "x86_64" }) {
                val build = layout.buildDirectory.dir("intermediates/monologGguf/$abi").get().asFile
                providers.exec {
                    commandLine(cmake.absolutePath, "-S", bridge.asFile.absolutePath, "-B", build.absolutePath,
                        "-G", "Ninja", "-DCMAKE_MAKE_PROGRAM=${ninja.absolutePath}",
                        "-DCMAKE_TOOLCHAIN_FILE=${ndk.resolve("build/cmake/android.toolchain.cmake").absolutePath}",
                        "-DANDROID_ABI=$abi", "-DANDROID_PLATFORM=android-28", "-DANDROID_STL=c++_static",
                        "-DCMAKE_BUILD_TYPE=Release", "-DMONOLOG_LLAMA_SOURCE=${llamaSource.absolutePath}")
                }.result.get().assertNormalExitValue()
                providers.exec {
                    commandLine(cmake.absolutePath, "--build", build.absolutePath, "--target", "monolog_gguf",
                        "--parallel", "4")
                }.result.get().assertNormalExitValue()
                build.resolve("libmonolog_gguf.so").copyTo(
                    ggufOutput.get().asFile.resolve("$abi/libmonolog_gguf.so"), overwrite = true)
            }
        }
    }
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
        .configureEach { dependsOn(buildGguf) }
}

val monologTestingKeystore = providers.gradleProperty("monologTestingKeystore")
    .map { rootProject.file(it) }.orNull

android {
    ndkVersion = rustTypingNdk.get()
    if (rustTypingEnabled.get()) sourceSets.getByName("main").jniLibs.srcDir(rustTypingOutput)
    if (llamaSource != null) sourceSets.getByName("main").jniLibs.srcDir(ggufOutput)
    namespace = "com.notokens.monolog"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.notokens.monolog"
        minSdk = 35
        targetSdk = 36
        versionCode = 16
        versionName = "0.1.0-alpha.8"
        buildConfigField("boolean", "MONOLOG_HAS_GGUF", (llamaSource != null).toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val webEndpoint = providers.gradleProperty("monologWebEndpoint").orElse("").get()
        require(webEndpoint.isEmpty() || webEndpoint.matches(Regex("https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?(?:/[A-Za-z0-9._/-]*)?"))) {
            "monologWebEndpoint must be an HTTPS endpoint without credentials, query parameters or fragments"
        }
        buildConfigField("String", "MONOLOG_WEB_ENDPOINT", "\"$webEndpoint\"")
        val serviceAddress = Regex("https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?(?:/[A-Za-z0-9._/-]*)?")
        val searchEndpoint = providers.gradleProperty("monologDuckDuckGoEndpoint").orElse("").get()
        val braveEndpoint = providers.gradleProperty("monologBraveSearchEndpoint").orElse("").get()
        require(braveEndpoint.isBlank() || braveEndpoint.matches(serviceAddress)) { "Use an HTTPS Brave Search endpoint." }
        buildConfigField("String", "MONOLOG_BRAVE_SEARCH_ENDPOINT", "\"$braveEndpoint\"")
        val klipyEndpoint = providers.gradleProperty("monologKlipyEndpoint").orElse("").get()
        require(searchEndpoint.isBlank() || searchEndpoint.matches(serviceAddress)) { "Use an HTTPS DuckDuckGo search endpoint." }
        require(klipyEndpoint.isBlank() || klipyEndpoint.matches(serviceAddress)) { "Use an HTTPS KLIPY endpoint." }
        buildConfigField("String", "MONOLOG_DUCKDUCKGO_ENDPOINT", "\"$searchEndpoint\"")
        buildConfigField("String", "MONOLOG_KLIPY_ENDPOINT", "\"$klipyEndpoint\"")
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

    val releaseSigning = providers.environmentVariable("MONOLOG_UPLOAD_KEYSTORE").orNull?.let { path ->
        signingConfigs.create("monologUpload") {
            storeFile = rootProject.file(path)
            storePassword = providers.environmentVariable("MONOLOG_UPLOAD_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("MONOLOG_UPLOAD_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("MONOLOG_UPLOAD_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        debug {
            if (testingSigning != null) signingConfig = testingSigning
        }
        release {
            if (releaseSigning != null) signingConfig = releaseSigning
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
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2") // Matches the Moonshine JNI symbol version.
    moonshineSdk("ai.moonshine:moonshine-voice:0.1.5@aar")
    implementation(files(moonshineRuntimeAar))
    // A local AAR has no Maven metadata. WorkManager is already declared below.
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.autofill:autofill:1.3.0")
    implementation("com.google.android.material:material:1.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
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
