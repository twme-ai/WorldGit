dependencies {
    implementation(libs.jgit)
    implementation(libs.zstd)
    implementation(libs.lz4)
    implementation(libs.yaml)
    testImplementation(libs.jgit)
    testImplementation(libs.lz4)
}
tasks.test { exclude("**/*LocalIntegrationTest*", "**/*PackLimitTest*") }
val integrationTest = tasks.register<Test>("integrationTest") {
    description = "本機 baseline 驗證；缺少 .work 世界時略過"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/*LocalIntegrationTest*")
    shouldRunAfter(tasks.test)
}
val packLimitTest = tasks.register<Test>("packLimitTest") {
    description = "95 MB pack 分割的重負載驗證（請使用 flock）"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/*PackLimitTest*")
    maxHeapSize = "1500m"
}

tasks.register<JavaExec>("extractFixtures") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.worldgit.core.FixtureExtractor")
    args(rootProject.projectDir.absolutePath)
    dependsOn(tasks.testClasses)
}

tasks.register("writeTestClasspath") {
    dependsOn(tasks.testClasses)
    doLast {
        val output = rootProject.layout.projectDirectory.file(".work/phase1/test-classpath.txt").asFile
        output.parentFile.mkdirs()
        output.writeText(sourceSets.test.get().runtimeClasspath.asPath)
    }
}
