plugins { alias(libs.plugins.fabric.loom) }

val mcVersion = "26.2"
base { archivesName = "worldgit-fabric-$mcVersion" }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
// 26.2 需要 Java 25；core 等共用模組仍是 Java 21 bytecode，可直接在 Java 25 執行。
tasks.withType<JavaCompile>().configureEach { options.release.set(25) }

sourceSets.main {
    java.srcDir("../shared/src/main/java")
    resources.srcDir("../shared/src/main/resources")
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    // 26.2 不混淆：不設 mappings、不用 modImplementation。
    implementation(libs.fabric.loader)
    implementation(libs.fabric.api.mc262)

    // 訊息：Adventure 的 Fabric 平台（MiniMessage → 原版 Text）。兩版使用不同的發行線
    // （1.21.11 → 6.8.0 / Adventure 4.25；26.2 → 7.1.1 / Adventure 5.2），共用程式碼只用兩邊相同的 API。
    implementation(libs.adventure.fabric.mc262)
    include(libs.adventure.fabric.mc262)

    implementation(project(":fabric:logic"))
    include(project(":fabric:logic"))
    include(project(":i18n"))
    include(project(":core"))
    include(project(":platform-api"))
    include(project(":protocol"))
    // jar-in-jar 不含傳遞依賴：JGit 需要 JavaEWAH 與 commons-codec（slf4j 由 Minecraft 提供）。
    for (dep in listOf(libs.jgit, libs.zstd, libs.lz4, libs.yaml, libs.javaewah, libs.commons.codec)) {
        implementation(dep)
        include(dep)
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft", mcVersion)
    inputs.property("java", 25)
    filesMatching("fabric.mod.json") { expand("version" to project.version, "minecraft" to mcVersion, "java" to 25) }
}

// Fabric client game test：在 Xvfb 下啟動真正的客戶端，建立單人世界並截圖（見 fabric/README.md「驗收」）。
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "worldgit-gametest"
        eula = true
        enableGameTests = false
        enableClientGameTests = true
    }
}

afterEvaluate {
    // gametest source set 由 configureTests 建立；測試程式在版本之間共用（fabric/gametest）。
    sourceSets.named("gametest") {
        java.srcDir("../gametest/src/main/java")
        resources.srcDir("../gametest/src/main/resources")
    }
    val tmp = rootProject.projectDir.resolve(".work/fabric-tmp").apply { mkdirs() }
    loom.runs.named("clientGameTest") {
        val suffix = if (providers.gradleProperty("wgtestPaperPort").isPresent) "-paper" else if (providers.gradleProperty("wgtestPhase2").isPresent) "-phase2" else if (providers.gradleProperty("wgtestPhase3").isPresent) "-phase3" else ""
        val workspace = projectDir.canonicalFile.parentFile.parentFile
        runDir(workspace.resolve(".work/worlds/fabric-gametest/$mcVersion$suffix").absolutePath)
        providers.gradleProperty("wgtestPaperPort").orNull?.let { port ->
            vmArgs("-Dwgtest.paperPort=$port", "-Dwgtest.paperReady=${providers.gradleProperty("wgtestPaperReady").get()}")
        }
        if (providers.gradleProperty("wgtestPaperPhase3").isPresent) vmArgs("-Dwgtest.paperPhase3=true")
        if (providers.gradleProperty("wgtestPhase2").isPresent) vmArgs("-Dwgtest.phase2=true")
        if (providers.gradleProperty("wgtestPhase3").isPresent) vmArgs("-Dwgtest.phase3=true")
        vmArgs(listOf(
            "-Xmx2G", "-XX:ActiveProcessorCount=3", "-XX:-UsePerfData",
            "-Djava.io.tmpdir=${tmp}", "-Djna.tmpdir=${tmp}", "-Dio.netty.native.workdir=${tmp}",
        ))
    }
}
