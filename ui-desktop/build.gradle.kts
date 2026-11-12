plugins {
    application
}

val osName = System.getProperty("os.name").lowercase()
val platform = when {
    osName.contains("win") -> "win"
    osName.contains("mac") -> "mac"
    else -> "linux"
}
val javafxVersion = "21.0.5"

application {
    mainClass.set("com.aura.audio.desktop.AuraDesktopApp")
}

dependencies {
    implementation(project(":core-dsp"))

    // JavaFX 21 Native UI & Hardware-accelerated graphics
    implementation("org.openjfx:javafx-base:$javafxVersion:$platform")
    implementation("org.openjfx:javafx-graphics:$javafxVersion:$platform")
    implementation("org.openjfx:javafx-controls:$javafxVersion:$platform")
    implementation("org.openjfx:javafx-swing:$javafxVersion:$platform")

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.16")
}

