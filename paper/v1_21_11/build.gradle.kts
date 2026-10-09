import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins { id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" }

// Minecraft 1.21.11 的 NMS 轉接層。Paper 自 1.20.5 起執行期為 Mojang 名稱，所以直接以 Mojang 名稱產出，不 reobf。
paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
    compileOnly(project(":paper:common"))
}

sourceSets.main { java.srcDir("../nms-shared/src/main/java") }
