plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:account"))
    implementation(libs.angus.mail)
    runtimeOnly(libs.angus.activation)
    testImplementation(libs.junit)
}
