plugins { base }
allprojects { group = "org.worldgit"; version = "0.1.0-SNAPSHOT" }
subprojects {
    apply(plugin = "java-library")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        withSourcesJar()
    }
    tasks.withType<JavaCompile>().configureEach { options.release.set(21); options.encoding = "UTF-8" }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxParallelForks = 1
        maxHeapSize = "1g"
        systemProperty("worldgit.projectRoot", rootProject.projectDir.absolutePath)
        testLogging { events("failed", "skipped") }
    }
    dependencies {
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.launcher)
    }
}
tasks.named("build") { dependsOn(subprojects.map { it.tasks.named("build") }) }
