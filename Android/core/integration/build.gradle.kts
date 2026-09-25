plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    testImplementation(project(":core:testkit"))
    testImplementation(project(":core:imap"))
    testImplementation(project(":core:smtp"))
    testImplementation(project(":core:mime"))
    testImplementation(libs.greenmail)
    constraints {
        testImplementation("org.eclipse.angus:jakarta.mail") { version { strictly("2.0.4") } }
        testImplementation("org.eclipse.angus:angus-mail") { version { strictly("2.0.4") } }
    }
    testImplementation(libs.junit)
}
val certificate = layout.buildDirectory.file("test-certificates/localhost.p12")
// Legacy PKCS12 wrapping is needed by API30 test devices; the generated identity is test-only.
val createTestCertificate = tasks.register<Exec>("createTestCertificate") {
    val certificateFile = certificate.get().asFile
    outputs.file(certificate)
    doFirst { certificateFile.parentFile.mkdirs(); certificateFile.delete() }
    commandLine("${System.getProperty("java.home")}/bin/keytool", "-J-Dkeystore.pkcs12.legacy", "-genkeypair", "-alias", "localhost",
        "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12", "-keystore", certificate.get().asFile.absolutePath,
        "-storepass", "test-password", "-keypass", "test-password", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "3650")
}
tasks.test {
    dependsOn(createTestCertificate)
    systemProperty("greenmail.tls.keystore.file", certificate.get().asFile.absolutePath)
    systemProperty("greenmail.tls.keystore.password", "test-password")
    systemProperty("javax.net.ssl.trustStore", certificate.get().asFile.absolutePath)
    systemProperty("javax.net.ssl.trustStorePassword", "test-password")
}
