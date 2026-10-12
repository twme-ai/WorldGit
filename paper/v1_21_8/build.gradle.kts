import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins { id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" }

paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION
dependencies {
    paperweight.paperDevBundle("1.21.8-R0.1-20250906.215025-55")
    compileOnly(project(":paper:common"))
}
sourceSets.main { java.srcDir("../commands-modern/src/main/java") }
