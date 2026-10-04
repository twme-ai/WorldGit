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
    for (dep in listOf(libs.jgit, libs.zstd, libs.lz4, libs.yaml, libs.javaewah, libs.commons.codec,
        "com.fasterxml.jackson.core:jackson-core:2.21.7",
        "com.fasterxml.jackson.core:jackson-annotations:2.21",
        "com.fasterxml.jackson.core:jackson-databind:2.21.7")) {
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
    val dedicatedFixture = tasks.register<Jar>("dedicatedFixtureJar") {
        dependsOn("gametestClasses")
        from(sourceSets.named("gametest").get().output)
        archiveClassifier.set("dedicated-fixture")
    }
    // 正式 jar 客戶端驗收：不使用 development classpath，避免漏包被 Gradle 掩蓋。
    if (providers.gradleProperty("wgtestPhase4").isPresent) {
        dependencies.add("productionRuntimeMods", "net.fabricmc.fabric-api:fabric-api:0.161.0+26.2")
        dependencies.add("productionRuntimeMods", fabricApi.module("fabric-client-gametest-api-v1", "0.161.0+26.2"))
        dependencies.add("productionRuntimeMods", fabricApi.module("fabric-gametest-api-v1", "0.161.0+26.2"))
        tasks.register<net.fabricmc.loom.task.prod.ClientProductionRunTask>("runPhase4ProductionClient") {
            dependsOn("dedicatedFixtureJar")
            mods.from(tasks.named("dedicatedFixtureJar"))
            runDir.set(rootProject.layout.projectDirectory.dir(".work/worlds/fabric-gametest/$mcVersion-phase4"))
            jvmArgs.addAll("-Dfabric.client.gametest", "-Dfabric.client.gametest.disableNetworkSynchronizer=true",
                "-Dwgtest.phase4=true", "-Dwgtest.phase4Dir=${providers.gradleProperty("wgtestPhase4Dir").get()}",
                "-Dwgtest.phase4Single=${providers.gradleProperty("wgtestPhase4Single").getOrElse("false")}",
                "-Xmx2G", "-XX:ActiveProcessorCount=3", "-XX:-UsePerfData")
            providers.gradleProperty("wgtestPaperPort").orNull?.let { port ->
                jvmArgs.add("-Dwgtest.paperPort=$port")
            }
            programArgs.addAll("--username", "Phase4Player", "--width", "1280", "--height", "720")
        }
    }
    val tmp = rootProject.projectDir.resolve(".work/fabric-tmp").apply { mkdirs() }
    loom.runs.named("clientGameTest") {
        val suffix = if (providers.gradleProperty("wgtestPhase4").isPresent) "-phase4" else if (providers.gradleProperty("wgtestPaperPort").isPresent) "-paper" else if (providers.gradleProperty("wgtestPhase2").isPresent) "-phase2" else if (providers.gradleProperty("wgtestPhase3").isPresent) "-phase3" else ""
        val workspace = projectDir.canonicalFile.parentFile.parentFile
        runDir(workspace.resolve(".work/worlds/fabric-gametest/$mcVersion$suffix").absolutePath)
        providers.gradleProperty("wgtestPaperPort").orNull?.let { port ->
            vmArgs("-Dwgtest.paperPort=$port", "-Dwgtest.paperReady=${providers.gradleProperty("wgtestPaperReady").get()}")
        }
        if (providers.gradleProperty("wgtestPhase4").isPresent) vmArgs("-Dwgtest.phase4=true", "-Dwgtest.phase4Dir=${providers.gradleProperty("wgtestPhase4Dir").get()}", "-Dwgtest.phase4Single=${providers.gradleProperty("wgtestPhase4Single").getOrElse("false")}")
        if (providers.gradleProperty("wgtestPaperPhase3").isPresent) vmArgs("-Dwgtest.paperPhase3=true")
        if (providers.gradleProperty("wgtestDedicated").isPresent) vmArgs("-Dwgtest.dedicated=true")
        if (providers.gradleProperty("wgtestPhase2").isPresent) vmArgs("-Dwgtest.phase2=true")
        if (providers.gradleProperty("wgtestPhase3").isPresent) vmArgs("-Dwgtest.phase3=true")
        vmArgs(listOf(
            "-Xmx2G", "-XX:ActiveProcessorCount=3", "-XX:-UsePerfData",
            "-Djava.io.tmpdir=${tmp}", "-Djna.tmpdir=${tmp}", "-Dio.netty.native.workdir=${tmp}",
        ))
    }
}

// 正式產物的 nested dependency 檢查；只讀 jar，沒有遊戲／網路負載。
val verifyRemoteRuntime = tasks.register<Exec>("verifyRemoteRuntime") {
    dependsOn("jar")
    commandLine("python3", rootProject.file("fabric/tools/check-phase4-jars.py"),
        layout.buildDirectory.file("libs/worldgit-fabric-$mcVersion-${project.version}.jar").get().asFile)
}
tasks.named("check") { dependsOn(verifyRemoteRuntime) }
