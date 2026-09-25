// Optional comparison APK; never included in the shipping app or ordinary builds.
plugins { alias(libs.plugins.android.application) }
android {
    namespace = "org.foxred.kage.spike"
    compileSdk = 37
    defaultConfig {
        applicationId = "org.foxred.kage.spike"
        minSdk = 30
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("androidTest").assets.directories.add(project(":core:integration").layout.buildDirectory.dir("test-certificates").get().asFile.absolutePath)
    packaging { resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/versions/**") }
}
dependencies {
    implementation(fileTree(providers.gradleProperty("thunderbirdSpikeJars").get()) {
        include("*.jar")
        exclude("*-annotation-jvm-*.jar")
    })
    androidTestImplementation(project(":core:testkit"))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test:runner:1.5.2")
}
tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach {
    dependsOn(":core:integration:createTestCertificate")
}
