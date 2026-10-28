plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // Collects the libraries of the app and their licenses for the Licenses screen.
    alias(libs.plugins.aboutlibraries)
}

android {
    namespace = "org.evsyukov.hermes"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "org.evsyukov.hermes"
        minSdk = 31
        targetSdk = 37
        // A release takes its version from the tag: `-Phermes.version=1.2.3` gives the code 10203.
        val version = providers.gradleProperty("hermes.version").orElse("0.1.0").get()
        val (major, minor, patch) = version.substringBefore("-").split(".").map { it.toInt() }
        versionCode = major * 10_000 + minor * 100 + patch
        versionName = version
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The release key comes from the environment; without it the release stays unsigned.
    val keystore = providers.environmentVariable("HERMES_KEYSTORE").orNull
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("HERMES_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("HERMES_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("HERMES_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric sets up Android's shared memory through the JDK's internal access to file descriptors.
            it.jvmArgs("--add-exports", "java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
}

aboutLibraries {
    collect {
        configPath = file("aboutlibraries")
    }
    // The SPDX text of a license like BSD or MIT carries a placeholder where the copyright of the
    // library belongs, and the notice is what such a license asks to ship. So a library under any
    // license but Apache-2.0 fails the build until its notice is in aboutlibraries/licenses: for
    // BSD-3-Clause that is the notice of Protocol Buffers, the only library under it.
    license {
        strictMode = com.mikepenz.aboutlibraries.plugin.StrictMode.FAIL
        allowedLicenses.addAll("Apache-2.0")
        allowedLicensesMap = mapOf("BSD-3-Clause" to listOf("com.google.protobuf", "androidx.datastore"))
    }
}

// The Licenses screen reads the license of the app and, when the tailnet node is built in, the
// licenses of its Go modules, which scripts/build-tailnet.sh writes next to the node.
abstract class LegalAssetsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val license: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val tailnet: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        fs.sync {
            from(license) { into("legal") }
            from(tailnet) { into("legal") }
            into(outputDir)
        }
    }
}

val legalAssets = tasks.register<LegalAssetsTask>("legalAssets") {
    license.set(rootProject.layout.projectDirectory.file("LICENSE"))
    // A tree, so a build without the node simply has no such file.
    tailnet.from(fileTree("libs/licenses") { include("tailnet.json") })
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(legalAssets, LegalAssetsTask::outputDir)
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The Tailscale node, built by scripts/build-tailnet.sh; the app runs without it.
    implementation(fileTree("libs") { include("*.aar") })
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.webkit)
    implementation(libs.unifiedpush.connector)
    implementation(libs.aboutlibraries.core)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)

    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.markdown.m3)
    implementation(libs.markdown.coil3)
    implementation(libs.markdown.code)
    implementation(libs.telephoto.zoomable.image.coil3)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
