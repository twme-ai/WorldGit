import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins { id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" }

paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.REOBF_PRODUCTION
dependencies {
    paperweight.paperDevBundle("1.20.1-R0.1-20230921.165944-178")
    compileOnly(project(":paper:common"))
}
