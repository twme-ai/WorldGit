plugins { application }
dependencies {
    implementation(project(":core")); implementation(project(":platform-api")); implementation(project(":protocol")); implementation(project(":i18n"))
    implementation(libs.picocli); implementation(libs.jackson); runtimeOnly(libs.slf4j.nop)
}
application { mainClass.set("org.worldgit.cli.Wgit") }
val fatJar = tasks.register<Jar>("fatJar") {
    archiveFileName.set("wgit.jar")
    dependsOn(configurations.runtimeClasspath)
    manifest { attributes["Main-Class"] = application.mainClass.get(); attributes["Enable-Native-Access"] = "ALL-UNNAMED" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class", "META-INF/versions/**/module-info.class")
}
tasks.assemble { dependsOn(fatJar) }

// 本機驗收工具是獨立 artifact，驗證程序不讀取正在重建的 classes/fat jar。
tasks.register<Jar>("acceptanceToolsJar") {
    dependsOn(fatJar, project(":core").tasks.named("testClasses"))
    archiveFileName.set("acceptance-tools.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "org.worldgit.core.AcceptanceTool" }
    from({ zipTree(fatJar.get().archiveFile.get()) }) { exclude("META-INF/MANIFEST.MF") }
    from(project(":core").layout.buildDirectory.dir("classes/java/test")) {
        include("org/worldgit/core/AcceptanceTool*.class", "org/worldgit/core/Phase2AcceptanceTool*.class", "org/worldgit/core/TestWorlds*.class", "org/worldgit/core/Phase3AcceptanceTool*.class", "org/worldgit/core/Phase4AcceptanceTool*.class")
    }
}
