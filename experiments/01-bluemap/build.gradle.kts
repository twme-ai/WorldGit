plugins {
    application
}

// BlueMap 5.x 自己的 build 要求 Java 25 toolchain（見 REPORT.md），class 版本為 69，所以本原型也必須用 Java 25 執行。
java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
    maven("https://repo.bluecolored.de/releases") // bluenbt、bluemap-api、bluemap-core 官方發佈處
}

dependencies {
    // 官方發佈的 bluemap-core（其 POM 已帶入 bluenbt、caffeine、bluemap-api 等傳遞依賴）
    implementation("de.bluecolored:bluemap-core:5.28")
    // 注意：core 5.28 的 POM 中 bluemap-api 版本欄位是空的（composite build 的發佈瑕疵），必須自己指定
    implementation("de.bluecolored:bluemap-api:2.8.1")
    // 以下僅為保險，版本與 BlueMap 的 libs.versions.toml 一致
    implementation("io.airlift:aircompressor:2.0.3")
    implementation("de.bluecolored:bluenbt:3.5.1")
    implementation("com.github.ben-manes.caffeine:caffeine:3.3.0")
    implementation("com.flowpowered:flow-math:1.0.3")
    implementation("com.google.code.gson:gson:2.8.9")
    implementation("at.yawk.lz4:lz4-java:1.10.1")
    implementation("org.spongepowered:configurate-hocon:4.2.0")
    implementation("org.spongepowered:configurate-gson:4.2.0")
    implementation("org.apache.commons:commons-dbcp2:2.14.0")
    compileOnly("org.jetbrains:annotations:26.1.0")
}

application {
    mainClass = "wg.Proto"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.layout.projectDirectory.dir("../../.work/bluemap-proto").asFile.also { it.mkdirs() }
    // 參數：MC 版本 id（預設 1.21.11）
    args = (project.findProperty("mcVersion") as String?)?.let { listOf(it) } ?: listOf("1.21.11")
}
