import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins { id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" }

// Minecraft 26.2（未混淆）的 NMS 轉接層；伺服器 class 為 Java 25，所以用 Java 25 toolchain 編譯（輸出為 Java 25 位元碼）。
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
// 26.2 的 paper-api 與 server 都是 Java 25 位元碼；此轉接層因此是 Java 25 class，只會在 Java 25 的 26.2 伺服器載入。
tasks.withType<JavaCompile>().configureEach { options.release.set(25) }
paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION

dependencies {
    paperweight.paperDevBundle("26.2.build.129-stable")
    compileOnly(project(":paper:common"))
}

sourceSets.main { java.srcDir("../nms-shared/src/main/java") }
