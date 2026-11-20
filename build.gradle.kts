plugins {
    `java-library`
}

group = "cn.ykn"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/") {
        content { includeGroup("com.github.retrooper") }
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-api:2.13.0") {
        isTransitive = false
    }

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("dialog-prompts")
}
