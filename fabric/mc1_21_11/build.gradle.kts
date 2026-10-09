plugins { alias(libs.plugins.fabric.loom.remap) }

val mcVersion = "1.21.11"
base { archivesName = "worldgit-fabric-$mcVersion" }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

// 共用原始碼（fabric/shared）編譯進每個版本；版本差異只放在本目錄的 adapter。
sourceSets.main {
    java.srcDir("../shared/src/main/java")
    resources.srcDir("../shared/src/main/resources")
}

dependencies {
    // 選用的真客戶端互通性驗收；不 include、不進入正式產物。
    providers.environmentVariable("WG_TEST_AXIOM_JAR").orNull?.let {
        modLocalRuntime(files(it))
        providers.environmentVariable("WG_TEST_AXIOM_DEPS").orNull?.let { directory ->
            modLocalRuntime(fileTree(directory) { include("*.jar") })
        }
    }

    minecraft("com.mojang:minecraft:$mcVersion")
    mappings(loom.officialMojangMappings())
    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api.mc1211)

    // 訊息：Adventure 的 Fabric 平台（MiniMessage → 原版 Text）。兩版使用不同的發行線
    // （1.21.11 → 6.8.0 / Adventure 4.25；26.2 → 7.1.1 / Adventure 5.2），共用程式碼只用兩邊相同的 API。
    modImplementation(libs.adventure.fabric.mc1211)
    include(libs.adventure.fabric.mc1211)

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
    inputs.property("java", 21)
    filesMatching("fabric.mod.json") { expand("version" to project.version, "minecraft" to mcVersion, "java" to 21) }
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
    tasks.register<net.fabricmc.loom.task.RemapJarTask>("remapDedicatedFixtureJar") {
        dependsOn(dedicatedFixture)
        inputFile.set(dedicatedFixture.flatMap { it.archiveFile })
        archiveClassifier.set("dedicated-fixture-remapped")
    }
    providers.gradleProperty("wgtestPerformanceArtifact").orNull?.let { artifact ->
        dependencies.add("productionRuntimeMods", "net.fabricmc.fabric-api:fabric-api:0.141.6+1.21.11")
        dependencies.add("productionRuntimeMods", fabricApi.module("fabric-client-gametest-api-v1", "0.141.6+1.21.11"))
        dependencies.add("productionRuntimeMods", fabricApi.module("fabric-gametest-api-v1", "0.141.6+1.21.11"))
        tasks.register<net.fabricmc.loom.task.prod.ClientProductionRunTask>("runPerformanceClient") {
            dependsOn("remapDedicatedFixtureJar")
            mods.setFrom(files(artifact), configurations.named("productionRuntimeMods"), tasks.named("remapDedicatedFixtureJar"))
            runDir.set(rootProject.layout.projectDirectory.dir(".work/worlds/fabric-gametest/$mcVersion-phase5"))
            jvmArgs.addAll("-Dfabric.client.gametest", "-Dfabric.client.gametest.disableNetworkSynchronizer=true",
                "-Dworldgit.acceptance=true", "-Dwgtest.performance=true", "-Dwgtest.phase5=true", "-Dwgtest.phase5Dir=${providers.gradleProperty("wgtestPhase5Dir").get()}",
                "-Dwgtest.phase5Single=true", "-Dwgtest.phase5Language=en_us", "-Xmx2G", "-XX:ActiveProcessorCount=3", "-XX:-UsePerfData")
            programArgs.addAll("--username", "PerfPlayer", "--width", "1280", "--height", "720")
        }
    }
    // 正式 jar 客戶端驗收：不使用 development classpath，避免漏包被 Gradle 掩蓋。
    if (providers.gradleProperty("wgtestPhase4").isPresent) {
        dependencies.add("productionRuntimeMods", "net.fabricmc.fabric-api:fabric-api:0.141.6+1.21.11")
        dependencies.add("productionRuntimeMods", fabricApi.module("fabric-client-gametest-api-v1", "0.141.6+1.21.11"))
        dependencies.add("productionRuntimeMods", fabricApi.module("fabric-gametest-api-v1", "0.141.6+1.21.11"))
        tasks.register<net.fabricmc.loom.task.prod.ClientProductionRunTask>("runPhase4ProductionClient") {
            dependsOn("remapDedicatedFixtureJar")
            mods.from(tasks.named("remapDedicatedFixtureJar"))
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
        val suffix = if (providers.gradleProperty("wgtestPhase5").isPresent) "-phase5" else if (providers.gradleProperty("wgtestPhase4").isPresent) "-phase4" else if (providers.gradleProperty("wgtestPaperPort").isPresent) "-paper" else if (providers.gradleProperty("wgtestPhase2").isPresent) "-phase2" else if (providers.gradleProperty("wgtestPhase3").isPresent) "-phase3" else ""
        val workspace = projectDir.canonicalFile.parentFile.parentFile
        runDir(workspace.resolve(".work/worlds/fabric-gametest/$mcVersion$suffix").absolutePath)
        providers.gradleProperty("wgtestPaperPort").orNull?.let { port ->
            vmArgs("-Dwgtest.paperPort=$port", "-Dwgtest.paperReady=${providers.gradleProperty("wgtestPaperReady").get()}")
        }
        if (providers.gradleProperty("wgtestPhase5").isPresent) programArgs("--width", "1280", "--height", "720")
        if (providers.gradleProperty("wgtestPhase5").isPresent) vmArgs("-Dwgtest.phase5=true", "-Dwgtest.phase5Dir=${providers.gradleProperty("wgtestPhase5Dir").get()}",
            "-Dwgtest.phase5Single=${providers.gradleProperty("wgtestPhase5Single").getOrElse("false")}", "-Dwgtest.phase5Language=${providers.gradleProperty("wgtestPhase5Language").getOrElse("en_us")}")
        if (providers.gradleProperty("wgtestPhase4").isPresent) vmArgs("-Dwgtest.phase4=true", "-Dwgtest.phase4Dir=${providers.gradleProperty("wgtestPhase4Dir").get()}", "-Dwgtest.phase4Single=${providers.gradleProperty("wgtestPhase4Single").getOrElse("false")}")
        if (providers.gradleProperty("wgtestPaperPhase3").isPresent) vmArgs("-Dwgtest.paperPhase3=true")
        if (providers.gradleProperty("wgtestDedicated").isPresent) vmArgs("-Dwgtest.dedicated=true")
        if (providers.gradleProperty("wgtestPhase2").isPresent) vmArgs("-Dwgtest.phase2=true")
        if (providers.gradleProperty("wgtestPhase3").isPresent) vmArgs("-Dwgtest.phase3=true")
        vmArgs(listOf(
            // 1.21.11 的 gametest API（4.3.5）在整合伺服器載入世界時卡住，關閉 NetworkSynchronizer 才能進世界（26.2 不需要）。
            "-Dfabric.client.gametest.disableNetworkSynchronizer=true", "-Xmx2G", "-XX:ActiveProcessorCount=3", "-XX:-UsePerfData",
            "-Djava.io.tmpdir=${tmp}", "-Djna.tmpdir=${tmp}", "-Dio.netty.native.workdir=${tmp}",
        ))
    }
}

// 正式產物的 nested dependency 檢查；只讀 jar，沒有遊戲／網路負載。
val verifyRemoteRuntime = tasks.register<Exec>("verifyRemoteRuntime") {
    dependsOn("remapJar")
    commandLine("python3", rootProject.file("fabric/tools/check-phase4-jars.py"),
        layout.buildDirectory.file("libs/worldgit-fabric-$mcVersion-${project.version}.jar").get().asFile)
}
tasks.named("check") { dependsOn(verifyRemoteRuntime) }
