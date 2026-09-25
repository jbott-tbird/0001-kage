plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:account"))
    implementation(project(":core:mime"))
    implementation(project(":core:transport"))
    implementation(libs.angus.mail)
    testImplementation(libs.junit)
}
