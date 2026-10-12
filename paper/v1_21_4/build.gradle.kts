import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins { id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" }

paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION
dependencies {
    paperweight.paperDevBundle("1.21.4-R0.1-20250925.065901-228")
    compileOnly(project(":paper:common"))
}
sourceSets.main { java.srcDir("../components-shared/src/main/java") }
