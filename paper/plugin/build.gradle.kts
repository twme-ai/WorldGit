import java.util.zip.ZipFile

// 發佈用的單一插件 jar：版本無關本體（paper:common）＋ core/platform-api/protocol ＋ 每個 Minecraft 版本一個 NMS 轉接層。
// 執行時依 Bukkit.getMinecraftVersion() 只載入符合的轉接層（見 NmsBridges）。
val adapters = listOf(":paper:v1_20_6", ":paper:v1_21_1", ":paper:v1_21_4", ":paper:v1_21_8", ":paper:v1_21_11", ":paper:v26_1_2", ":paper:v26_2", ":paper:v26_3")
val legacyAdapters = listOf(":paper:v1_20_1", ":paper:v1_20_4")
(adapters + legacyAdapters).forEach { evaluationDependsOn(it) }

dependencies {
    implementation(project(":paper:common"))
}

tasks.jar {
    archiveBaseName.set("worldgit-paper")
    dependsOn(configurations.runtimeClasspath)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        // 轉接層以 Mojang 名稱編譯；Paper 1.20.5+ 看到這個屬性就不會把插件當成 Spigot 名稱去 remap。
        attributes("paperweight-mappings-namespace" to "mojang")
        attributes("Implementation-Version" to project.version)
    }
    from(sourceSets.main.get().output)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    adapters.forEach { path ->
        val compile = project(path).tasks.named<JavaCompile>("compileJava")
        dependsOn(compile)
        from(compile.map { it.destinationDirectory })
    }
    legacyAdapters.forEach { path ->
        val reobf = project(path).tasks.named("reobfJar")
        dependsOn(reobf)
        from(reobf.map { task -> task.outputs.files.files.map { zipTree(it) } })
    }
    // 伺服器已提供 slf4j／snakeyaml 相容版本（plugin classloader 以伺服器優先），不重複打包 slf4j。
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/**/module-info.class", "org/slf4j/**")
}
tasks.named("assemble") { dependsOn(tasks.jar) }

val verifyOptionalApi = tasks.register("verifyOptionalApi") {
    dependsOn(tasks.jar)
    val artifact = tasks.jar.flatMap { it.archiveFile }
    inputs.file(artifact)
    doLast {
        ZipFile(artifact.get().asFile).use { zip ->
            check(zip.entries().asSequence().none { it.name.startsWith("com/moulberry/axiom/") }) {
                "Axiom compileOnly API must not be bundled in WorldGit"
            }
        }
    }
}
tasks.named("check") { dependsOn(verifyOptionalApi) }

// 每個精確 bundle 產生完全一致 bytecode 的來源才可在發佈 jar 合併。
val verifyComponentsSources = tasks.register("verifyComponentsSources") {
    val variants = listOf(":paper:v1_20_6", ":paper:v1_21_1", ":paper:v1_21_4").map {
        project(it).tasks.named<JavaCompile>("compileJava")
    }
    dependsOn(variants)
    variants.forEach { inputs.dir(it.flatMap { task -> task.destinationDirectory }) }
    doLast {
        val roots = variants.map { it.get().destinationDirectory.get().asFile }
        val files = roots.first().walkTopDown().filter {
            it.isFile && it.extension == "class" && it.name.startsWith("Components")
        }.map { it.relativeTo(roots.first()).path }.toList()
        check(files.isNotEmpty()) { "components shared sources were not compiled" }
        files.forEach { path ->
            check(roots.drop(1).all { root -> root.resolve(path).readBytes().contentEquals(roots.first().resolve(path).readBytes()) }) {
                "components shared bytecode differs: $path; isolate this source in its version adapter"
            }
        }
    }
}
tasks.jar { dependsOn(verifyComponentsSources) }
tasks.named("check") { dependsOn(verifyComponentsSources) }

val verifyModernCommands = tasks.register("verifyModernCommands") {
    val variants = listOf(":paper:v1_21_8", ":paper:v1_21_11").map {
        project(it).tasks.named<JavaCompile>("compileJava")
    }
    dependsOn(variants)
    variants.forEach { inputs.dir(it.flatMap { task -> task.destinationDirectory }) }
    doLast {
        val roots = variants.map { it.get().destinationDirectory.get().asFile }
        val files = roots.first().walkTopDown().filter {
            it.isFile && it.extension == "class" && it.name.startsWith("ModernCommandRegistration")
        }.map { it.relativeTo(roots.first()).path }.toList()
        check(files.isNotEmpty()) { "modern command shared sources were not compiled" }
        files.forEach { path ->
            // Both adapters use Java 21; require identical complete class files.
            val expected = roots.first().resolve(path).readBytes().toList()
            check(roots.drop(1).all { root -> root.resolve(path).readBytes().toList() == expected }) {
                "modern command bytecode differs: $path; isolate this source in its version adapter"
            }
        }
    }
}
tasks.jar { dependsOn(verifyModernCommands) }
tasks.named("check") { dependsOn(verifyModernCommands) }

// 三個未混淆的 Java 25 bundle 只合併完整 class 完全一致的 modern 來源。
val verifyModern26Sources = tasks.register("verifyModern26Sources") {
    val variants = listOf(":paper:v26_1_2", ":paper:v26_2", ":paper:v26_3").map {
        project(it).tasks.named<JavaCompile>("compileJava")
    }
    dependsOn(variants)
    variants.forEach { inputs.dir(it.flatMap { task -> task.destinationDirectory }) }
    doLast {
        val roots = variants.map { it.get().destinationDirectory.get().asFile }
        val files = roots.first().walkTopDown().filter {
            it.isFile && it.extension == "class" &&
                (it.name.startsWith("ChunkTickGuard") || it.name.startsWith("ModernCommandRegistration"))
        }.map { it.relativeTo(roots.first()).path }.toList()
        check(files.isNotEmpty()) { "26.x modern shared sources were not compiled" }
        files.forEach { path ->
            val expected = roots.first().resolve(path).readBytes()
            check(roots.drop(1).all { root -> root.resolve(path).readBytes().contentEquals(expected) }) {
                "26.x shared bytecode differs: $path; isolate this source in its version adapter"
            }
        }
    }
}
tasks.jar { dependsOn(verifyModern26Sources) }
tasks.named("check") { dependsOn(verifyModern26Sources) }
