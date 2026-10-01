// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

plugins {
    alias(libs.plugins.ksp)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.foxred.kage"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "org.foxred.kage"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val googleClientId = (findProperty("kageGoogleAndroidClientId") as? String).orEmpty()
        require(googleClientId.matches(Regex("[A-Za-z0-9._-]*"))) {
            "kageGoogleAndroidClientId must be an Android OAuth client ID"
        }
        buildConfigField("String", "GOOGLE_ANDROID_CLIENT_ID", "\"$googleClientId\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    sourceSets.getByName("androidTest").assets.srcDir(project(":core:integration").layout.buildDirectory.dir("test-certificates").get().asFile)
    packaging { resources.merges += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md") }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    androidTestImplementation(project(":core:testkit"))
    androidTestImplementation(project(":core:demo"))
    implementation(project(":core:imap"))
    implementation(project(":core:smtp"))
    implementation(project(":core:mime"))
    implementation(libs.angus.mail)
    implementation(libs.jsoup)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.compose.icons)
    ksp(libs.androidx.room.compiler)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.coroutines.test)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(project(":core:demo"))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.5.1")
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
// Lint reads the same generated certificate assets as the instrumentation APK.
tasks.matching { it.name in setOf(
    "mergeDebugAndroidTestAssets",
    "generateDebugAndroidTestLintModel",
    "lintAnalyzeDebugAndroidTest",
) }.configureEach {
    dependsOn(":core:integration:createTestCertificate")
}

// Check the AVD identity before AGP can install, run, or clean up device tests.
val verifyTestEmulator by tasks.registering(Exec::class) {
    commandLine("python3", rootProject.file("../scripts/android_device_tests.py"), "--check")
}
tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(verifyTestEmulator)
}
