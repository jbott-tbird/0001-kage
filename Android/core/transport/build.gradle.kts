plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:account"))
    testImplementation(libs.junit)
}
