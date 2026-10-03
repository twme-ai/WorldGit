# WorldGit protocol v2

`worldgit:hello`、`worldgit:diff`、`worldgit:status`、`worldgit:clear` plugin channel；core 與 protocol 均 Java 21，沒有 MC 類別。Phase 0 v1 不相容，hello 的版本/nonce/capabilities 必須在平台 adapter 核對；未確認 peer 支援 v2，不送 diff/status。

每 payload 的第一 byte 是 envelope version 2，第二 byte 是 message kind（0 hello、1 clear、2 diff、3 status）。大端固定數值、非負有界 varint、UTF-8 長度前綴字串。`Header` 帶單調遞增 preview id、sequence、parts、totalEntries、dimension。分包每包 ≤ 28,000 bytes。

hello 帶 peerVersion、nonce、capabilities 與四種 RGB 色票。diff 每 section 先傳座標與 before/after state 共用 palette，每格含 local YZX＋ChangeKind 的 u16 與兩個 palette index。status 傳包圍盒、主要 kind 與 +/-/~/! 計數，可由 core 的 SUMMARY diff 編碼；ghost diff 需要 BLOCKS，傳摘要會明確報錯。超過 100,000 格應改用 status 包圍盒或依顯示區域分批。

`BatchAssembler` 收齊才發布，支援亂序及內容相同的重傳；拒絕不同 header、混用訊息、重複座標、數量不符。30 秒超時丟棄暫存，每批最多 8 MiB。clear 與新 preview 取消舊批次；重連必須 reset。adapter 需以連線/維度管理 preview id 並在自己的客戶端執行緒呼叫。

`DiffPalette.DEFAULT/COLORBLIND` 是 CLI、遊戲及 Hub 的共同來源；顏色外仍使用 symbol/style。Paper／Fabric adapter 已實作渲染與伺服器握手重試。

## Phase 2 revision preview（2026-10-01）

二進位 envelope 仍是 v2，沒有新增 channel 或 message kind。支援 `/wg preview <rev> [--radius r]` 的伺服器可在 hello 額外宣告 `revision-preview`（可選，舊客戶端忽略未知能力）；請求沿用原版命令封包，回應沿用 `diff`／`status`／`clear`。Paper 接手時使用相同流程即可，不要求舊伺服器支援新命令。

revision preview 的 diff 方向為**目前工作世界 → 目標 commit**。ADDED 鬼影用 after、REMOVED 用 before、MODIFIED 用 after，色彩表示「套用目標時會出現／消失／改變」。方塊與 BE 同格仍合併為一筆；實體／biome 只有既有 status 區域摘要。半徑為玩家 chunk 中心的含端點正方形，沿用伺服器 100,000 格上限與客戶端 LOD／上傳預算，超出時回 section／chunk 外框。

`preview off`／`clear`／完成套用以遞增 id 清除；客戶端必須保留跨維度 clear floor，連未曾收到資料的維度也拒絕較舊分包。本機 clear 同時取消未收齊的批次，只有斷線才 reset floor，避免排隊中的分包重新顯示已清除鬼影。

## Phase 3 可選合併訊息（2026-10-02）

既有 **Protocol v2 不變**。新增 `MergeProtocol`，獨立 channel `worldgit:conflicts`（區域清單）／`worldgit:conflict_preview`（單區域 ours／theirs／base 內容），各自 envelope version **1**。新能力 `merge-regions-v1` 不加入既有 `Protocol.CAPABILITIES`；平台實作接收／渲染後才宣告。伺服器只對宣告能力的 peer 發送；舊客戶端未註冊 channel，忽略新訊息，仍正常使用 v2 diff／status。Paper／Fabric adapter 已實作握手、清單與疊圖，實機驗收見 docs/13。

外層 `Part` 採大端：u8 version、u8 type（0 regions／1 preview）、i64 preview id、i32 sequence／parts／totalBytes、u8 dimension UTF-8 長度與字串、i32 fragment 長度與 bytes。每 fragment ≤ 27,800 bytes，每 payload ≤ 28,000 bytes；最多 8 MiB／批、8192 parts、100,000 entries。維度字串 ≤ 128 bytes，座標沿用 ±30,000,000／Y ±32768 邊界。

完整 body 為 canonical NBT compound。regions 保存 id、bounds（含端點的六個 int，metadata 區域沒有 bounds）、格數、choice、resolved、redstone。preview 保存 region id、choice（ours／theirs／base）、cells；每 cell 有三 int 世界座標、canonical block state 與可選的完整 canonical BE NBT bytes。BE 大於單包可跨 fragment，不裁掉 NBT 欄位；超過整批上限拒絕，平台應縮小預覽範圍。

`MergeProtocol.regions/preview` 產生分包；`encode/decode` 驗證版本／長度；`Assembler` 每連線／channel／維度各一個，支援亂序與相同重傳，收齊才回 Completed。拒絕混用 header／type、不同內容重傳、重複 region id／cell 座標、超量與無效 NBT；30 秒丟棄暫存。clear 的 preview floor 需與既有 v2 clear 由平台一起轉送到所有合併 assembler，避免舊批次重現；斷線才 reset。

core 的 `WorldOperations.regionPreview`／`MergeEngine.preview` 提供中性 PreviewBlock；平台轉為 `MergeProtocol.PreviewCell(position,state.canonical(),blockEntity)`。client 預覽不修改世界，原地切換仍由伺服器呼叫 selectRegion；manual 不提供候選預覽，應顯示目前世界。實體／biome 預覽圖形留平台後續，本訊息只傳方塊與 BE。

## conflict-select 能力（2026-10-02）

Paper／Folia 與 Fabric 專用伺服器在 v2 hello 額外宣告 `conflict-select-v1`，表示支援原版命令 `wg conflict-select <id|all> ours|theirs|base|manual`：只選擇／寫入區域，`resolved=false`；manual 保留世界現況。此能力是伺服器公告，客戶端不需回傳；預設 `Protocol.CAPABILITIES` 保持不變。遠端 Fabric UI 必須同時看到 `merge-regions-v1` 與 `conflict-select-v1` 才啟用 Set blocks。舊伺服器只有前者仍可 Ghost／Resolve，Set blocks 停用並顯示原因。

Ghost 請求 `wg conflict-preview <id> ours|theirs|base`；Paper／Folia／Fabric 專用伺服器 Resolve 請求 `wg resolve <id|all> ours|theirs|base|manual`。三者都用既有原版命令封包，回應清單／預覽仍用既有合併 channel 與 envelope。Fabric 單人的 resolve 旗標語法保持相容，由客戶端依連線種類產生。斷線時能力、assembler、清單與 id floor 全部 reset，重連以新的 nonce 握手後，Paper／Folia 重新送目前維度清單，Fabric 重新送 MERGING 追蹤維度的清單。

Fabric adapter 以 handler 身分記錄斷線，在客戶端 tick 清理模型與握手狀態；JOIN 先清除上一段連線，晚到的舊 handler 事件不影響新握手。這讓 GPU 釋放與分包 reset 都在客戶端執行緒完成。

Fabric 專用伺服器（2026-10-03）在成功握手後讀取 durable MERGING 清單並推送，選擇／解決後對所有有讀取權限及 merge-regions-v1 能力的連線同步更新；重啟後同樣恢復。維持既有 envelope 與 clear floor，不增加預設 Protocol.CAPABILITIES。
