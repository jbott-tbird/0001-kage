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
    }

    // Instrumentation may clear or uninstall its target. Never target the user's app.
    testBuildType = "instrumented"
    buildTypes {
        create("instrumented") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".instrumented"
            matchingFallbacks += "debug"
            // Live Google OAuth is registered for the normal app identity only.
            buildConfigField("String", "GOOGLE_ANDROID_CLIENT_ID", "\"\"")
        }
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
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.5.1")
    androidTestImplementation(libs.androidx.junit)
    "instrumentedImplementation"(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
tasks.matching { it.name == "mergeInstrumentedAndroidTestAssets" }.configureEach {
    dependsOn(":core:integration:createTestCertificate")
}
