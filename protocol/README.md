# WorldGit protocol v2

`worldgit:hello`、`worldgit:diff`、`worldgit:status`、`worldgit:clear` plugin channel；core 與 protocol 均 Java 21，沒有 MC 類別。Phase 0 v1 不相容，hello 的版本/nonce/capabilities 必須在平台 adapter 核對；未確認 peer 支援 v2，不送 diff/status。

每 payload 的第一 byte 是 envelope version 2，第二 byte 是 message kind（0 hello、1 clear、2 diff、3 status）。大端固定數值、非負有界 varint、UTF-8 長度前綴字串。`Header` 帶單調遞增 preview id、sequence、parts、totalEntries、dimension。分包每包 ≤ 28,000 bytes。

hello 帶 peerVersion、nonce、capabilities 與四種 RGB 色票。diff 每 section 先傳座標與 before/after state 共用 palette，每格含 local YZX＋ChangeKind 的 u16 與兩個 palette index。status 傳包圍盒、主要 kind 與 +/-/~/! 計數，可由 core 的 SUMMARY diff 編碼；ghost diff 需要 BLOCKS，傳摘要會明確報錯。超過 100,000 格應改用 status 包圍盒或依顯示區域分批。

`BatchAssembler` 收齊才發布，支援亂序及內容相同的重傳；拒絕不同 header、混用訊息、重複座標、數量不符。30 秒超時丟棄暫存，每批最多 8 MiB。clear 與新 preview 取消舊批次；重連必須 reset。adapter 需以連線/維度管理 preview id 並在自己的客戶端執行緒呼叫。

`DiffPalette.DEFAULT/COLORBLIND` 是 CLI、遊戲及 Hub 的共同來源；顏色外仍使用 symbol/style。渲染與 server handshake 重試屬於後續 Paper/Fabric 任務。
