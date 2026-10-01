plugins { java }

repositories { mavenCentral() }

dependencies {
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.3.0.202506031305-r")
    implementation("com.github.luben:zstd-jni:1.5.6-6")
    implementation("org.lz4:lz4-java:1.8.0")
    implementation("org.slf4j:slf4j-nop:2.0.16")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
tasks.withType<JavaCompile> { options.release.set(21) }

tasks.jar {
    archiveFileName.set("survival-scale.jar")
    manifest { attributes["Main-Class"] = "wgproto.Main"
    attributes["Enable-Native-Access"] = "ALL-UNNAMED" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class", "module-info.class")
}
