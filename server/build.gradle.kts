plugins {
    application
}

application {
    mainClass.set("com.aura.audio.server.AuraServer")
}

dependencies {
    implementation(project(":core-dsp"))

    // Lightweight embedded Web & WebSocket server (Javalin 6.x)
    implementation("io.javalin:javalin:6.4.0")

    // JSON serialization
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")

    // SLF4J logger binding
    implementation("org.slf4j:slf4j-simple:2.0.16")
}

