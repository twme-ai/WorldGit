import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("org.springframework.boot") version "3.5.16"
}

// Hub 使用 Java 25（決定 #14，BlueMap 5.x 需要）；根專案預設的 Java 21 在這裡覆寫。
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile>().configureEach { options.release.set(25) }

val jgitVersion = libs.versions.jgit.get()

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(project(":core"))
    implementation(project(":protocol"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.apache.httpcomponents.client5:httpclient5")
    implementation(libs.jgit)
    implementation("org.eclipse.jgit:org.eclipse.jgit.http.server:$jgitVersion")
    implementation(libs.zstd)
    implementation("org.yaml:snakeyaml")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    // 安全修補：用修復版 BOM 對齊整個 Tomcat／Jackson 家族，避免 Boot BOM 回退。
    implementation(enforcedPlatform("com.fasterxml.jackson:jackson-bom:2.21.7"))
    implementation(enforcedPlatform("org.apache.logging.log4j:log4j-bom:2.25.5"))
    constraints {
        implementation("org.apache.httpcomponents.client5:httpclient5") { version { strictly("5.6.3") } }
        implementation("org.apache.httpcomponents.core5:httpcore5") { version { strictly("5.4.3") } }
        implementation("org.apache.httpcomponents.core5:httpcore5-h2") { version { strictly("5.4.3") } }
        implementation("org.apache.tomcat.embed:tomcat-embed-core") { version { strictly("10.1.60") } }
        implementation("org.apache.tomcat.embed:tomcat-embed-el") { version { strictly("10.1.60") } }
        implementation("org.apache.tomcat.embed:tomcat-embed-websocket") { version { strictly("10.1.60") } }
        runtimeOnly("org.postgresql:postgresql") { version { strictly("42.7.13") } }
    }
    runtimeOnly("org.postgresql:postgresql:42.7.13")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.jgit)
}

// 前端（hub/web，Vite）建置結果放進 jar 的 static/；沒有建置前端時 jar 仍可啟動（API 可用）。
val webDist = layout.projectDirectory.dir("web/dist")
tasks.processResources {
    from(webDist) { into("static") }
}
tasks.register<Exec>("webBuild") {
    description = "npm ci && npm run build（需要 Node 22+）"
    workingDir = layout.projectDirectory.dir("web").asFile
    commandLine("sh", "-c", "npm ci && npm run build")
}

tasks.bootJar { archiveFileName.set("worldgit-hub.jar") }
tasks.jar { enabled = false }

// 驗收工具：對關閉中的世界複本離線改方塊（見 ApplyEdits）。
tasks.register<JavaExec>("applyEdits") {
    description = "離線修改世界方塊：-Pworld=<dir> -Pedits=<json>"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.worldgit.hub.tools.ApplyEdits")
    args(providers.gradleProperty("world").orElse("").get(), providers.gradleProperty("edits").orElse("").get())
    dependsOn(tasks.testClasses)
}

// Phase 2 分支／compare 截圖的可重跑固定場景（數十 KiB）。
tasks.register<JavaExec>("branchFixture") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.worldgit.hub.tools.BranchFixture")
    args(providers.gradleProperty("target").getOrElse(".work/branch-fixture"))
    dependsOn(tasks.testClasses)
}

// Phase 3 唯讀合併的三維度固定場景。
tasks.register<JavaExec>("mergeFixture") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.worldgit.hub.tools.MergeFixture")
    args(providers.gradleProperty("target").getOrElse(".work/merge-fixture"))
    dependsOn(tasks.testClasses)
}

// SQLite／PostgreSQL 切換必須重新測試；外部 PostgreSQL 狀態不可沿用 task cache。
val testDatabaseKind = providers.environmentVariable("WORLDGIT_TEST_POSTGRES_URL")
    .map { if (it.isBlank()) "sqlite" else "postgres" }.orElse("sqlite")
tasks.test {
    inputs.property("testDatabaseKind", testDatabaseKind)
    outputs.upToDateWhen { testDatabaseKind.get() == "sqlite" }
    outputs.doNotCacheIf("PostgreSQL 測試資料庫是外部狀態") { testDatabaseKind.get() == "postgres" }
}
