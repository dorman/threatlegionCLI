plugins {
    application
    java
}

group = "com.clinecli"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.anthropic:anthropic-java:2.15.0")
    implementation("org.jline:jline:3.26.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

application {
    mainClass.set("com.clinecli.Main")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "com.clinecli.Main"
    }
    // Bundle all dependencies into a fat jar
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
