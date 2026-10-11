import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins { id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" }

paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION
dependencies {
    paperweight.paperDevBundle("1.20.6-R0.1-20241030.191541-126")
    compileOnly(project(":paper:common"))
}

sourceSets.main { java.srcDir("../components-shared/src/main/java") }
