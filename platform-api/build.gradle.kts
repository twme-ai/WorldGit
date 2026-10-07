dependencies {
    api(project(":core"))
    api(project(":i18n"))
    // 與 Hub 的安全修補版本一致；避免 Paper/Fabric 共用 client 回退舊 parser。
    implementation(enforcedPlatform("com.fasterxml.jackson:jackson-bom:2.21.7"))
    implementation(libs.jackson)
}
