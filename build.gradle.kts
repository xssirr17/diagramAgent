import java.net.URI

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    java
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${libs.versions.springAi.get()}")
    }
}

val aiProvider = providers.gradleProperty("aiProvider").getOrElse("gemini-api")

dependencies {
    implementation(platform(libs.spring.ai.bom))

    when (aiProvider) {
        "vertex" -> {
            implementation(libs.spring.ai.vertex.ai)
        }
        "both" -> {
            implementation(libs.spring.ai.google.genai)
            implementation(libs.spring.ai.vertex.ai)
        }
        else -> {
            implementation(libs.spring.ai.google.genai)
        }
    }

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.javaparser.core)
    implementation(libs.javaparser.symbol.solver)
    implementation(libs.caffeine)
    implementation(libs.jgit)

    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    System.getenv("ALL_PROXY")?.let { proxy ->
        if (proxy.startsWith("socks5://") || proxy.startsWith("socks://")) {
            val uri = URI.create(proxy)
            systemProperty("socksProxyHost", uri.host)
            systemProperty("socksProxyPort", uri.port.toString())
        }
    }
}

tasks.register<Test>("integrationTestLive") {
    description = "Runs live integration tests against Google Gemini API when GOOGLE_API_KEY is present."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("live")
    }
    systemProperty("spring.profiles.active", "gemini-api")
    val apiKey = System.getenv("GOOGLE_API_KEY") ?: System.getenv("GEMINI_API_KEY")
    if (apiKey != null && apiKey.isNotBlank()) {
        environment("GOOGLE_API_KEY", apiKey)
    }
    System.getenv("DIAGRAM_MODEL")?.let { environment("DIAGRAM_MODEL", it) }
    System.getenv("DIAGRAM_ROOT")?.let { environment("DIAGRAM_ROOT", it) }
    onlyIf {
        apiKey != null && apiKey.isNotBlank()
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("live")
    }
}
