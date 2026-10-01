// 不依賴 Minecraft 的 Fabric 模組邏輯：設定、訊息、握手、預覽規劃、客戶端分塊 LOD。
dependencies {
    api(project(":core"))
    api(project(":platform-api"))
    api(project(":protocol"))
    api(project(":i18n"))
    implementation(libs.yaml)
}
