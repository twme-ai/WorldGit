plugins {
    java
}

// 同一個 jar 要在 Java 21（1.21.11）與 Java 25（26.2）上跑，所以目標是 Java 21 bytecode。
java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}
tasks.withType<JavaCompile> { options.release = 21; options.encoding = "UTF-8" }

val work = rootProject.layout.projectDirectory.dir("../../.work").asFile
val nmsJar = File(work, "servers/paper-1.21.11/versions/1.21.11/paper-1.21.11.jar")       // Mojang 名稱的 patched server（NMS + CraftBukkit）
val nmsLibs = fileTree(File(work, "servers/paper-1.21.11/libraries")) { include("**/*.jar") }
val jars = File(work, "jars")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation("com.github.luben:zstd-jni:1.5.6-6")
    implementation("org.lz4:lz4-java:1.8.0")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // 直接對 1.21.11 的 Mojang 名稱 NMS 編譯；執行於 26.2 時靠兩版簽章相容（見 REPORT §NMS）
    compileOnly(files(nmsJar))
    compileOnly(nmsLibs)
    compileOnly(files(File(jars, "packetevents-spigot-2.14.0.jar")))
    compileOnly(files(File(jars, "FAWE-1.21.11-2.15.0.jar")))
}

tasks.jar {
    archiveFileName = "worldgit-folia-switch.jar"
    manifest {
        attributes("paperweight-mappings-namespace" to "mojang") // 避免 Paper 1.21.11 把插件當成 Spigot 名稱去 remap
    }
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ configurations.runtimeClasspath.get().map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}
