pluginManagement { repositories { gradlePluginPortal(); mavenCentral(); maven("https://maven.fabricmc.net/"); maven("https://repo.papermc.io/repository/maven-public/") } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
        maven("https://maven.enginehub.org/repo/") { name = "enginehub" }
        maven("https://libraries.minecraft.net/") { name = "mojang" }
        maven("https://maven.fabricmc.net/") { name = "fabricmc" }
    }
}
rootProject.name = "worldgit"
include("core", "platform-api", "protocol", "cli", "i18n")
include("paper:common", "paper:v1_21_11", "paper:v26_2", "paper:plugin")
include("fabric:logic", "fabric:mc1_21_11", "fabric:mc26_2")
include("hub")
