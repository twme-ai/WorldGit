plugins { alias(libs.plugins.fabric.loom.remap) }
loom { accessWidenerPath.set(file("src/main/resources/worldgit.accesswidener")) }
val mcVersion = "1.20.4"
base { archivesName = "worldgit-fabric-$mcVersion" }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
val generateShared = tasks.register<Exec>("generateShared") {
    inputs.dir("../shared/src/main/java")
    inputs.dir("../legacy-shared/src/main/java")
    inputs.dir("src/main/java")
    inputs.file("../tools/shared-sources.py")
    outputs.dir(layout.buildDirectory.dir("generated/shared"))
    commandLine("python3", "../tools/shared-sources.py", "mc1_20_4")
}
sourceSets.main {
    java.srcDir(generateShared.map { layout.buildDirectory.dir("generated/shared") })
    resources.srcDir("../shared/src/main/resources")
}
tasks.named("compileJava") { dependsOn(generateShared) }
dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    mappings(loom.officialMojangMappings())
    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api.mc1204)
    // Only plain Adventure and Gson serializers; no modern Fabric platform jar in legacy Minecraft.
    for (name in listOf("adventure-api", "adventure-key", "adventure-text-minimessage", "adventure-text-serializer-gson", "adventure-text-serializer-json", "adventure-text-serializer-commons")) {
        val dependency = "net.kyori:$name:4.25.0"
        implementation(dependency); include(dependency)
    }
    implementation(project(":fabric:logic")); include(project(":fabric:logic"))
    for (name in listOf("i18n", "core", "platform-api", "protocol")) include(project(":$name"))
    for (dep in listOf(libs.jgit, libs.zstd, libs.lz4, libs.yaml, libs.javaewah, libs.commons.codec,
        "net.kyori:option:1.1.0", "net.kyori:examination-api:1.3.0", "net.kyori:examination-string:1.3.0",
        "com.fasterxml.jackson.core:jackson-core:2.21.7", "com.fasterxml.jackson.core:jackson-annotations:2.21", "com.fasterxml.jackson.core:jackson-databind:2.21.7")) {
        implementation(dep); include(dep)
    }
}
tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    inputs.property("version", project.version); inputs.property("minecraft", mcVersion); inputs.property("java", 21)
    filesMatching("fabric.mod.json") { expand("version" to project.version, "minecraft" to mcVersion, "java" to 21) }
}
val verifyRemoteRuntime = tasks.register<Exec>("verifyRemoteRuntime") {
    dependsOn("remapJar")
    commandLine("python3", rootProject.file("fabric/tools/check-phase4-jars.py"), layout.buildDirectory.file("libs/worldgit-fabric-$mcVersion-${project.version}.jar").get().asFile)
}
tasks.named("check") { dependsOn(verifyRemoteRuntime) }

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.addAll(listOf("-Xmaxerrs", "500")) }

val gametest = sourceSets.create("gametest") {
    java.srcDir("../gametest-legacy/src/main/java")
    java.srcDir("src/gametest/java")
    resources.srcDir("../gametest-legacy/src/main/resources")
    compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().runtimeClasspath + output
}
val dedicatedFixture = tasks.register<Jar>("dedicatedFixtureJar") {
    dependsOn("gametestClasses");from(gametest.output);archiveClassifier.set("dedicated-fixture")
}
val remappedFixture = tasks.register<net.fabricmc.loom.task.RemapJarTask>("remapDedicatedFixtureJar") {
    dependsOn(dedicatedFixture);inputFile.set(dedicatedFixture.flatMap { it.archiveFile });archiveClassifier.set("dedicated-fixture-remapped")
}
if (providers.gradleProperty("wgtestPhase5").isPresent) {
    dependencies.add("productionRuntimeMods", "net.fabricmc.fabric-api:fabric-api:0.97.3+1.20.4")
    tasks.register<net.fabricmc.loom.task.prod.ClientProductionRunTask>("runLegacyTestClient") {
        dependsOn("remapJar", remappedFixture)
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
        useXVFB.set(false)
        mods.setFrom(tasks.named("remapJar"), remappedFixture, configurations.named("productionRuntimeMods"))
        runDir.set(rootProject.layout.projectDirectory.dir(".work/worlds/fabric-gametest/$mcVersion-phase5"))
        jvmArgs.addAll("-Dworldgit.acceptance=true", "-Dwgtest.phase5=true", "-Dwgtest.phase5Dir=${providers.gradleProperty("wgtestPhase5Dir").get()}",
            "-Dwgtest.phase5Single=${providers.gradleProperty("wgtestPhase5Single").getOrElse("true")}", "-Dwgtest.phase5Language=${providers.gradleProperty("wgtestPhase5Language").getOrElse("en_us")}",
            "-Xmx2G", "-XX:ActiveProcessorCount=3", "-XX:-UsePerfData")
        programArgs.addAll("--username", "Phase5Player", "--width", "1280", "--height", "720")
    }
}

tasks.named<Jar>("sourcesJar") { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
