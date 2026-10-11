// 與 Minecraft 版本無關的插件本體：只用 Paper API（及選用的 WorldEdit/FAWE），不得引用 NMS／CraftBukkit。
// NMS 只存在於各版本的薄轉接層，由 NmsBridge 介面連接。
dependencies {
    compileOnly(project(":paper:axiom-api"))
    api(project(":core"))
    api(project(":platform-api"))
    api(project(":protocol"))
    implementation(project(":i18n"))
    implementation(libs.jackson)
    implementation(libs.jgit) // 補全以 readOnly store 讀取 refs；不開會保存設定的 DimensionRepository
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
    compileOnly("com.mojang:brigadier:1.3.10")
    compileOnly("org.apache.logging.log4j:log4j-core:2.25.2") { isTransitive = false } // 伺服器自帶；OfflineShutdownCommit 監看關閉進度的 appender
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core:2.15.0") { isTransitive = false }
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

// 版本無關程式碼不得依賴 NMS：掃描 class 常數池的字串即可，不需要額外的位元組碼函式庫。
val verifyNoNms = tasks.register("verifyNoNms") {
    description = "確認 paper:common 沒有 NMS 或不相容 API 的引用（由版本轉接層隔離）"
    val classes = sourceSets.main.get().output.classesDirs
    inputs.files(classes)
    doLast {
        val forbidden = listOf("io/papermc/paper/command/brigadier/", "io/papermc/paper/plugin/lifecycle/", "org/bukkit/damage/", "org/bukkit/util/Consumer", "org/bukkit/inventory/InventoryView", "net/minecraft/", "org/bukkit/craftbukkit", "ca/spottedleaf/", "io/papermc/paper/chunk/system")
        val bad = mutableListOf<String>()
        classes.asFileTree.matching { include("**/*.class") }.forEach { f ->
            val text = String(f.readBytes(), Charsets.ISO_8859_1)
            forbidden.filter { text.contains(it) }.forEach { bad += "${f.name}: $it" }
        }
        if (bad.isNotEmpty()) throw GradleException("paper:common 引用了 NMS 或不相容 API：\n" + bad.joinToString("\n"))
    }
}
tasks.named("check") { dependsOn(verifyNoNms) }
tasks.named("classes") { finalizedBy(verifyNoNms) }

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching(listOf("plugin.yml", "paper-plugin.yml")) { expand("version" to version) }

}
