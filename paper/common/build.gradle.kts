// 與 Minecraft 版本無關的插件本體：只用 Paper API（及選用的 WorldEdit/FAWE），不得引用 NMS／CraftBukkit。
// NMS 只存在於 :paper:v1_21_11、:paper:v26_2 的薄轉接層，由 NmsBridge 介面連接。
dependencies {
    api(project(":core"))
    api(project(":platform-api"))
    api(project(":protocol"))
    implementation(project(":i18n"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core:2.15.0") { isTransitive = false }
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

// 版本無關程式碼不得依賴 NMS：掃描 class 常數池的字串即可，不需要額外的位元組碼函式庫。
val verifyNoNms = tasks.register("verifyNoNms") {
    description = "確認 paper:common 沒有 net/minecraft、craftbukkit、moonrise 的引用（NMS 只能出現在版本轉接層）"
    val classes = sourceSets.main.get().output.classesDirs
    inputs.files(classes)
    doLast {
        val forbidden = listOf("net/minecraft/", "org/bukkit/craftbukkit", "ca/spottedleaf/", "io/papermc/paper/chunk/system")
        val bad = mutableListOf<String>()
        classes.asFileTree.matching { include("**/*.class") }.forEach { f ->
            val text = String(f.readBytes(), Charsets.ISO_8859_1)
            forbidden.filter { text.contains(it) }.forEach { bad += "${f.name}: $it" }
        }
        if (bad.isNotEmpty()) throw GradleException("paper:common 引用了 NMS：\n" + bad.joinToString("\n"))
    }
}
tasks.named("check") { dependsOn(verifyNoNms) }
tasks.named("classes") { finalizedBy(verifyNoNms) }

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("plugin.yml") { expand("version" to version) }
}
