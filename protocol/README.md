# WorldGit protocol v2

`worldgit:hello`、`worldgit:diff`、`worldgit:status`、`worldgit:clear` plugin channel；core 與 protocol 均 Java 21，沒有 MC 類別。Phase 0 v1 不相容，hello 的版本/nonce/capabilities 必須在平台 adapter 核對；未確認 peer 支援 v2，不送 diff/status。

每 payload 的第一 byte 是 envelope version 2，第二 byte 是 message kind（0 hello、1 clear、2 diff、3 status）。大端固定數值、非負有界 varint、UTF-8 長度前綴字串。`Header` 帶單調遞增 preview id、sequence、parts、totalEntries、dimension。分包每包 ≤ 28,000 bytes。

hello 帶 peerVersion、nonce、capabilities 與四種 RGB 色票。diff 每 section 先傳座標與 before/after state 共用 palette，每格含 local YZX＋ChangeKind 的 u16 與兩個 palette index。status 傳包圍盒、主要 kind 與 +/-/~/! 計數，可由 core 的 SUMMARY diff 編碼；ghost diff 需要 BLOCKS，傳摘要會明確報錯。超過 100,000 格應改用 status 包圍盒或依顯示區域分批。

`BatchAssembler` 收齊才發布，支援亂序及內容相同的重傳；拒絕不同 header、混用訊息、重複座標、數量不符。30 秒超時丟棄暫存，每批最多 8 MiB。clear 與新 preview 取消舊批次；重連必須 reset。adapter 需以連線/維度管理 preview id 並在自己的客戶端執行緒呼叫。

`DiffPalette.DEFAULT/COLORBLIND` 是 CLI、遊戲及 Hub 的共同來源；顏色外仍使用 symbol/style。渲染與 server handshake 重試屬於後續 Paper/Fabric 任務。

## Phase 2 revision preview（2026-10-01）

二進位 envelope 仍是 v2，沒有新增 channel 或 message kind。支援 `/wg preview <rev> [--radius r]` 的伺服器可在 hello 額外宣告 `revision-preview`（可選，舊客戶端忽略未知能力）；請求沿用原版命令封包，回應沿用 `diff`／`status`／`clear`。Paper 接手時使用相同流程即可，不要求舊伺服器支援新命令。

revision preview 的 diff 方向為**目前工作世界 → 目標 commit**。ADDED 鬼影用 after、REMOVED 用 before、MODIFIED 用 after，色彩表示「套用目標時會出現／消失／改變」。方塊與 BE 同格仍合併為一筆；實體／biome 只有既有 status 區域摘要。半徑為玩家 chunk 中心的含端點正方形，沿用伺服器 100,000 格上限與客戶端 LOD／上傳預算，超出時回 section／chunk 外框。

`preview off`／`clear`／完成套用以遞增 id 清除；客戶端必須保留跨維度 clear floor，連未曾收到資料的維度也拒絕較舊分包。本機 clear 同時取消未收齊的批次，只有斷線才 reset floor，避免排隊中的分包重新顯示已清除鬼影。
