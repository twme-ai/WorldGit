// 發佈用的單一插件 jar：版本無關本體（paper:common）＋ core/platform-api/protocol ＋ 每個 Minecraft 版本一個 NMS 轉接層。
// 執行時依 Bukkit.getMinecraftVersion() 只載入符合的轉接層（見 NmsBridges）。
val adapters = listOf(":paper:v1_21_11", ":paper:v26_2")
adapters.forEach { evaluationDependsOn(it) }

dependencies {
    implementation(project(":paper:common"))
}

tasks.jar {
    archiveBaseName.set("worldgit-paper")
    dependsOn(configurations.runtimeClasspath)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        // 轉接層以 Mojang 名稱編譯；Paper 1.21.x 看到這個屬性就不會把插件當成 Spigot 名稱去 remap。
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
    // 伺服器已提供 slf4j／snakeyaml 相容版本（plugin classloader 以伺服器優先），不重複打包 slf4j。
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/**/module-info.class", "org/slf4j/**")
}
tasks.named("assemble") { dependsOn(tasks.jar) }
