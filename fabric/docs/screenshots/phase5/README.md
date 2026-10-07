# Fabric Phase 5 真客戶端截圖

2026-10-07，以 Xvfb 啟動兩版 Minecraft 真客戶端，直接擷取 1280×720 framebuffer。dedicated 使用正式 mod jar；single 是未開作弊的整合伺服器 owner。聊天 click／hover、剪貼簿、Screen 元件、BossBar／HUD 與 TTL 由同一輪 `accept-phase5.py` 驗證，沒有合成 UI 封包。

單人測試先在地獄 init，再 init 主世界，因此追加截圖只剩終界；dedicated 的主世界先 init，會顯示地獄／終界／全部三個按鈕。ignore preview 在 dedicated 使用實際已追蹤的盔甲座，確認並 commit 後另外核對 repo 不再含該 UUID。

| 畫面 | 1.21.11 專用伺服器 | 1.21.11 單人 | 26.2 專用伺服器 | 26.2 單人 |
|---|---|---|---|---|
| 初始化追加按鈕 | [檢視](dedicated-1.21.11-init-append-buttons.png) | [檢視](single-1.21.11-init-append-buttons.png) | [檢視](dedicated-26.2-init-append-buttons.png) | [檢視](single-26.2-init-append-buttons.png) |
| 聊天分支圖 | [檢視](dedicated-1.21.11-chat-graph.png) | [檢視](single-1.21.11-chat-graph.png) | [檢視](dedicated-26.2-chat-graph.png) | [檢視](single-26.2-chat-graph.png) |
| 分支圖畫面 | [檢視](dedicated-1.21.11-graph-screen.png) | [檢視](single-1.21.11-graph-screen.png) | [檢視](dedicated-26.2-graph-screen.png) | [檢視](single-26.2-graph-screen.png) |
| BossBar／HUD 執行中 | [檢視](dedicated-1.21.11-progress-running.png) | [檢視](single-1.21.11-progress-running.png) | [檢視](dedicated-26.2-progress-running.png) | [檢視](single-26.2-progress-running.png) |
| BossBar／HUD 終態 | [檢視](dedicated-1.21.11-progress-terminal.png) | [檢視](single-1.21.11-progress-terminal.png) | [檢視](dedicated-26.2-progress-terminal.png) | [檢視](single-26.2-progress-terminal.png) |
| 完成訊息 | [檢視](dedicated-1.21.11-completion-message.png) | [檢視](single-1.21.11-completion-message.png) | [檢視](dedicated-26.2-completion-message.png) | [檢視](single-26.2-completion-message.png) |
| 錯誤與複製 hover | [檢視](dedicated-1.21.11-error-copy-hover.png) | [檢視](single-1.21.11-error-copy-hover.png) | [檢視](dedicated-26.2-error-copy-hover.png) | [檢視](single-26.2-error-copy-hover.png) |
| ignore 編輯器 | [檢視](dedicated-1.21.11-ignore-editor.png) | [檢視](single-1.21.11-ignore-editor.png) | [檢視](dedicated-26.2-ignore-editor.png) | [檢視](single-26.2-ignore-editor.png) |
| ignore 即時語法錯誤 | [檢視](dedicated-1.21.11-ignore-syntax-error.png) | [檢視](single-1.21.11-ignore-syntax-error.png) | [檢視](dedicated-26.2-ignore-syntax-error.png) | [檢視](single-26.2-ignore-syntax-error.png) |
| ignore 預覽與確認 | [檢視](dedicated-1.21.11-ignore-preview.png) | [檢視](single-1.21.11-ignore-preview.png) | [檢視](dedicated-26.2-ignore-preview.png) | [檢視](single-26.2-ignore-preview.png) |

逐命令結果、通過項數與限制見 [Phase 5 最終驗收](../../../../docs/16-phase5-design.md)。完整 log／JSON 位於 `.work/fabric-phase5/`，可攜結果與雜湊索引見 [acceptance.json](acceptance.json)。
