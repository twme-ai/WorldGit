# Fabric Phase 2 實機畫面

Xvfb／llvmpipe 下的真正 Minecraft framebuffer（854×480）。兩版各選三張：目前 B 預覽 A、switch A 清除鬼影、半徑 restore A 並保留範圍外 B。六張共 **389,148 bytes（約 380 KiB）**，低於 1.5 MB。精選以 JPEG 保存；未裁切或合成，原始 PNG 與四個世界／repo 檢查點保留於 `.work/fabric-acceptance/phase2-*/`。

| 場景 | 1.21.11 | 26.2 |
|---|---|---|
| 目前 B → A，8 格增／刪／改鬼影 | [預覽](1.21.11-01-preview-A.jpg) | [預覽](26.2-01-preview-A.jpg) |
| switch A，實際方塊復原、無殘留鬼影 | [切換](1.21.11-02-switch-A.jpg) | [切換](26.2-02-switch-A.jpg) |
| chunk 半徑 0 restore，HEAD 留在 B | [範圍復原](1.21.11-03-restore-radius.jpg) | [範圍復原](26.2-03-restore-radius.jpg) |

畫面輔以 GameTest 的 client／server 逐格比對及離線 CLI verify；範圍外 chunk 的 gold_block 另外以程式斷言確認。完整結果、限制與原始證據路徑見 [Phase 2 進度](../../../../docs/12-phase2-progress.md)。
