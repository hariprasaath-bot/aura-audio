plugins {
    `java-library`
}

dependencies {
    // DSP & Fast Fourier Transform
    api("com.github.wendykierp:JTransforms:3.1")

    // Audio Metadata & Tagging (FLAC, MP3, WAV, ALAC, AAC, etc.)
    api("net.jthink:jaudiotagger:3.0.1")

    // FLAC decoding & streaming
    api("org.jflac:jflac-codec:1.5.2")

    // MP3 Decoding via Java Sound SPI
    api("com.googlecode.soundlibs:mp3spi:1.9.5.4")
    api("com.googlecode.soundlibs:tritonus-share:0.3.7.4")
    api("javazoom:jlayer:1.0.1")

    // Logging
    api("org.slf4j:slf4j-api:2.0.16")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

