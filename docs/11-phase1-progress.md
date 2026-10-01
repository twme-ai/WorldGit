# Phase 1 第一個實作任務

日期：2026-10-01。此任務建立共用 JVM 基礎與離線 CLI；Paper/Folia 插件、Fabric 模組、Hub 尚未建立，因此路線圖「四端看到同一份歷史」的完整 Phase 1 驗收仍待後續任務。

## 完成範圍

| 模組 | 本次實作 |
|---|---|
| 根 monorepo | Kotlin DSL、Gradle 9.6.1 wrapper 與 SHA-256、version catalog、Java 21 toolchain、四模組 CI、來源 jar；可再加入各自設定 toolchain 的 paper/fabric/hub |
| core/anvil | 有界 canonical NBT、region header/sector 驗證、gzip/zlib/raw/LZ4、外部 mcc、離線 region 原子替換；以 DimensionId 統一兩版目錄 |
| core/model/normalize | 不可變 section/chunk/entity；版本 1 zstd blob；忽略 Paper/AI/光照/Heightmaps/POI、attributes/modifiers/passengers/ticks 排序、無 modifier 的 movement_speed、BE/實體欄位表與 HEAD 黏性距離 2 格 |
| core/store/service | ObjectStore/RefStore 的 JGit 實作、每維度 bare repo、HEAD 條件更新與 operation lock、共享 snapshot trailer、沒變動不產生 commit、各維度部分失敗明確回報 |
| core/capture/diff | 離線 index、同秒 payload 雜湊驗證、規則/HEAD 失效重建；tree 短路、section/方塊/BE/biome/UUID 實體 diff、SUMMARY/BLOCKS 與局部 chunk 視窗 |
| core/config | safe YAML、schema/未知鍵/重複鍵/型別驗證；完整 area/entity/tag/!persistent/in area/field/否定/後面優先語法；兩版 vanilla＋啟用 datapack tag resolver；world-meta field selector；兩份文件原文範本；規則修改後提示與移除已追蹤內容 |
| platform-api | LiveWorld、dirty generation 與有條件 acknowledge、OfflineWorld、SessionGuard、apply/lock/flush/notify/dataVersion 入口 |
| protocol | v2 hello/diff/status/clear、共用普通/色盲色票、section palette、≤ 28,000 bytes 分包、有界重組/亂序/重傳/timeout/clear |
| CLI | init/status/commit/log/diff、世界/維度選擇、摘要與 --blocks、JSON、ANSI/NO_COLOR/符號、snapshot 分組 log、fat jar 與 wgit 腳本 |

repo 路徑是 `<server>/.worldgit/<world>/<namespace>.<path>/`，root 下直接是 `r.X.Z`；主世界另有 `world-meta` 與 `dimensions` 清單。所有 WorldGit 設定是 YAML；`.wgignore` 保留專用規則語法，`worldgit.index` 是可丟棄 binary cache，Gradle catalog 的 TOML 是建置慣例。

## 三端接手的 API

正式套件根為 `org.worldgit`，公開介面不帶 Minecraft 或 JGit 型別。詳見 [core README](../core/README.md)、[CLI README](../cli/README.md)、[protocol README](../protocol/README.md)。

### Paper/Folia 插件與 Fabric 單人世界

1. 每個維度提供 `platform.LiveWorld`。`snapshot(ChunkPos, IgnoreRules)` 在來源擁有的 server/region 執行緒複製並正規化，回傳 `CompletionStage<Optional<ChunkSnapshot>>`；不存在或尚非 full chunk 回傳 empty。
2. `knownChunks()` 是現存集合；`dirtyChunks()` 包含新增、修改、刪除的候選；`entityChunks()` 必須另提供含實體的 chunk，因為 entity 沒有可靠 unsaved 旗標。可自訂 `scan()` 提供磁碟/線上混合來源。
3. 用 `DirtyChunkTracker.capture()` 固定 generation，先 flush，於背景的 repo executor 呼叫 `DimensionRepository.commit/status`；只有成功 commit 後 acknowledge 該 batch。status 不清除 dirty；較晚的變動不會被舊 batch 清除。不可在 region 執行緒同步等待其他 region 的 future。
4. 多維度一次操作共用 UUID，放入 `CommitMetadata.snapshot`；逐一報告 CommitResult 或錯誤。沒有跨 repo transaction，已成功的 commit 不因另一維度失敗回滾。
5. `CommitMetadata` 提供主要 author/committer、source、auto、DataVersion 與多人 Contribution（身分、player UUID、chunk 集合、cause）。主要身分進 git 欄位，其餘進 WorldGit/Co-authored-by trailers。
6. 版本 adapter 的 `EntitySemantics` 提供真正 tag/persistence 判斷；離線 tag registry 讀取兩版 vanilla 與已啟用 datapack，persistence 仍是保守近似。`normalizationFingerprint()` 在政策/registry 改變時使 index 重建；線上 adapter 重啟需完整 dirty 初始化。

core 只依賴 `capture.SnapshotSource`，LiveWorld 延伸它，避免 core/platform-api 循環依賴。`WorldRepositories` 可注入 source factory；線上端也可直接用 DimensionRepository，決定自己的作者與操作排程。

### Fabric 客戶端

`Protocol.status(previewId, summary, minY, maxY)` 直接編碼 section/chunk 描邊；`Protocol.diff(previewId, detailedDiff)` 編碼方塊的前後 state 與 kind。鬼影需要 `Detail.BLOCKS`，傳摘要會報錯。用 chunk 視窗限制展開，超過 100,000 格改用包圍盒。實體/biome 的存在可反映於 status chunk 描邊；v2 尚未傳實體模型或 biome ghost。

hello 帶版本、nonce、capabilities、色票；adapter 必須核對版本並完成握手才送 preview。每個連線/維度管理單調 preview id，分包交給 BatchAssembler，收齊才發布；重連 reset，clear 取消舊 preview。實際 channel 註冊、握手重試與渲染屬於後續任務。

### Hub

以 ObjectStore/RefStore 讀 commit/tree/blob，SnapshotCodec 解碼中性 section/entity/biome；DiffEngine 提供 SUMMARY 統計與 BLOCKS 局部檢視。相同 tree id 不向下解碼；BE 與方塊同格只計一次。實體以維度內全域 UUID 比對，再裁切顯示視窗，跨 chunk 不會被拆成新增/移除。ChangeKind 預留 conflict，但本次沒有 merge。

Hub 可以 Java 25 使用 Java 21 core；Web 3D 上色與 +/-/~/! 統計使用同一 WorldDiff，色票由 protocol.DiffPalette 提供。

## 自動測試與小型資料

`./gradlew --max-workers=1 build` 已透過真正 wrapper 成功，CI 不需要 `.work`。常規測試目前共 31 項：core 24、platform-api 3、protocol 3、CLI 1；涵蓋 codec/NBT/Anvil、正規化、ignore/YAML、diff/trailer、兩版 fixture、多維度/部分失敗、同秒碰撞、壞 index/遺失 index、線上 dirty 來源到 commit 與多人歸屬、鎖、100,000 格分包/重組、CLI 五指令與 JSON。新增 tag 測試含資料夾/ZIP、覆寫優先、遞迴/optional 參照、停用 pack、循環/schema 錯誤，以及只改 registry 時的 index 失效；metadata 測試驗證排除/加回與下次 commit 移除地圖、活世界檔案不刪除。另驗證 biome 排除標記視為未追蹤，以及無 message 的 exception 仍會回報維度失敗。

世界 fixture 由兩版 baseline 各維度擷取三個 full chunk，共 18 個，另保留極小 bukkit datapack metadata；合計 **214,677 bytes**（約 210 KiB），另有小型說明，低於 2 MB。CI 使用這些提交的資料，不複製完整世界。

完整 baseline 與大 pack 各有一項本機測試，以 `flock .work/bench.lock` 執行；缺 baseline 時 integrationTest 自動略過。packLimitTest 與啟動伺服器不列入 CI 常規 build。

## 本機驗收

### 完整 baseline

複製兩版 baseline，原檔不修改。init 後全量重讀的 diff 為空、再次 commit 不產生新 commit，正規化 blob/tree 雜湊一致。改一格只變一個 section、一格，只在主世界產生 commit；fixture 測試另驗證共用 snapshot trailer、兩範本、ignore 修改後從快照移除與 status 提示。

最終 integrationTest 通過（沒有略過），兩版 init 分別 **15.520 s / 10.666 s**；完整測試 39.377 s。packLimitTest 也通過（14.753 s）：112 MB 隨機 blob 實際分成 **84,026,179 / 28,008,602 bytes**，重新開啟逐物件校驗後再 gc，仍符合上限且可讀。連同常規 31 項，共 **33 項測試，0 failure、0 error、0 skipped**；真實 Paper 腳本另計，不併入 JUnit 數量。fat jar 另實測 always 上色、NO_COLOR 覆寫 always、YAML 色盲色票，全部通過，證據在 `.work/phase1/color-smoke.txt`。

### 真實 Paper

腳本 `scripts/verify-paper.py` 取得 bench.lock，使用 Java 21/25 的 Paper 1.21.11/26.2，127.0.0.1、online-mode=false、port 25641/25642，finally 停止伺服器。每版固定原 baseline 的 **698 個 full chunk**，不將首次載入時生成的邊緣 chunk 持續加進下一輪。

原 baseline 首次真正載入會產生邊緣地形、流體/作物與活生物變動，不能把它當作純格式重寫。前面的暖機輪保留在 `.work/phase1/paper-fixed/`；最終驗證從該 baseline 複本建立全新正式 repo，再實際載入/重寫兩次。規則仍預設全部追蹤，生物 AI 不會被冒稱為靜止。

最終兩版結果都通過：

| 檢查 | 1.21.11 | 26.2 |
|---|---|---|
| 原始 full chunk 確實重寫（header timestamp 改變） | 698/698 | 698/698 |
| 重寫兩次的方塊/BE、biome、ticks、structures 差異 | 全部 0 | 全部 0 |
| 最後一輪主世界實體活動 | 19 修改、19 移除 | 14 修改、29 移除 |
| 上述修改實體的位置差異 | 全部 > 2 格，最小 2.176 格 | 全部 > 2 格，最小 3.166 格 |
| 最後一輪其他維度實體活動 | 地獄 1 修改，移動 7.353 格；終界 0 | 地獄 1 修改，移動 6.965 格；終界 0 |
| setblock 後 wgit diff --blocks | 恰好 1 section、1 格；座標 (0,235,0)、diamond_block | 同左 |
| 運行中的 session.lock | 警告並拒絕 status | 同左 |

首次載入產生的鄰接 full chunk 使驗證複本最後各有 1,051 個 full chunk。實體的真實移動、新增/消失仍保留在 diff；不將活世界描述為所有 diff 都是零。最終六次伺服器啟動都已停止，最終 log 無 ERROR/Watchdog。完整證據在 `.work/phase1/paper-delivery/results.json` 與各世界的 verify/one-block log；report 直接列出 entity kind 及移動距離核對。tag/world-meta 補齊前的驗證另保留於 paper-final。

### 20,521 full chunk 世界

原 Phase 0 `.work/survival-scale/large/run` raw 世界已清理；保留的 `original.git` init commit `4a6e36c20bac9ca03d9690d78ba869467394fc10` 尚在。首次量測只讀該 repo、用原型 restore 重建在新的驗證目錄；**不是原始 raw 存檔**，也沒有修改 experiments。世界包含主世界 20,449 與另兩維度各 36 個 full chunk。

正式驗證腳本複製這個重建世界，在全新正式 repo 測量 init、clean status、無變動 commit、一格變更 commit、bounded repack；Java -Xmx1g、3 核心機器、bench.lock、GNU time 最大 RSS。最後用 native git fsck 檢查全部維度。

最終量測使用 `.work/phase1/scale/run`（上述重建世界加前次單格測試）的世界複本，排除舊 .worldgit，重新 init；數字不是沿用 Phase 0 repo 的建置時間。

| 操作 | wall time | 最大 RSS |
|---|---:|---:|
| init | **314.31 s** | **546.89 MiB** |
| clean status | **8.39 s** | **147.02 MiB** |
| 無變動 commit | **8.34 s** | **144.72 MiB** |
| 一格變更 commit | **13.01 s** | **345.90 MiB** |
| bounded repack | **31.13 s** | **400.50 MiB** |

clean status 三維度均為 `candidates=0 payloads-read=0`，無變動 commit 三者均跳過，一格變更只在主世界產生 commit。repack 主世界兩個 pack 為 **80,922,065 / 32,229,422 bytes**；地獄 **131,450**、終界 **25,045 bytes**。全部低於 95,000,000 bytes，更低於 100 MB；全部維度的 `git fsck --no-dangling` 通過。量測證據在 `.work/phase1/scale-delivery/results.json`、各操作 stdout/stderr/time.json。

正式 init 比 Phase 0 的 112.5 s 原型慢；本次只完成一輪局部最佳化，尚未深入 profile。上述是單次量級量測，包含 JVM 啟動及 native/JIT 記憶體，不是延遲上界或 SLA。

## 實作中發現與修正

- **JGit 不實作 pack.packSizeLimit 分割**：只寫設定不能達到 #17。正式 bounded repack 依 zlib 最壞上界分組，禁用 delta/reuse、用 JGit PackWriter 產生獨立 pack，實際驗證每個 ≤ 95,000,000 bytes；全部新 pack/idx 安裝後才清理舊 pack/已打包 loose object。gc 共用相同實作，自動 JGit GC 關閉。
- **首次 init 展開所有方塊會 OOM**：最初完整 baseline 測試失敗，已改為 SUMMARY capture，BLOCKS 只在明細/指定視窗才展開，重跑 baseline 成功。
- **structures.References 是集合**：真實 Paper 重寫曾只剩 nether_fossil 的 References long[] 順序變動。確認內容相同、順序不同，已排序並補回歸測試，保留 structures 的其他內容。
- **驗證產物被重建破壞**：早期長時間伺服器腳本讀取同時重建的 fat jar/classes，曾 NoClassDefFoundError。改為獨立 acceptance-tools.jar，腳本啟動時複製兩個固定 jar；保留失敗紀錄，不算驗收成功。
- **forceload 範圍膨脹**：最初每輪重列新增 full chunk，會持續載入更多邊緣。改為固定原始 698 個 chunk，並比對全部 header timestamp 確認真的重寫。
- **效能**：初次大型 init 327.33 s、clean status 9.84 s；發現逐格 hasAreas 的 stream 配置與逐 chunk mcc stat，改成快取規則旗標與一次列出 mcc 後重測。最終數字另列，不混用前後版本。
- **不同輪次的量測**：tag/world-meta 補齊前的 scale-final 為 init 279.44 s、status 8.25 s、最大 RSS 524.76 MiB；補齊後的 scale-delivery 採上述 314.31 s 等數字。兩輪不是受控 A/B，JVM、磁碟快取及同機活動會影響時間，不據此推論單一功能造成多少效能差異。
- **遺失 index**：全量 capture 必須從空 tree 重建，否則會保留磁碟上已刪除的 chunk；已修正並測試。
- **離線排除規則的缺口**：最後檢查將只支援 arrows 的 resolver 擴充為兩版 vanilla＋啟用 datapack，補上 world-meta 的 field selector。tag 改變也會更新 normalization fingerprint，避免 mca 沒變時沿用錯誤篩選。新增 tag 測試首輪因 fixture 的 `(0,0)` 沒有 entity container 而失敗，測試建立該容器後重跑通過。

## 與設計文件的補充及尚未完成

- `track: modified-only` 已可讀寫、init 寫入、進版本控制與明確提示；**自然地形篩選尚未生效**，目前仍儲存全部 full chunk，符合本次允許的實作範圍。
- 離線 tag resolver 已完成兩版 vanilla（46/48 個 tags）與已啟用資料夾/ZIP datapack，支援覆寫與遞迴/optional 參照；缺少 pack 提示、未知 tag 明確失敗。未知模組內建 pack 仍需線上版本 adapter 提供 registry。離線 persistence 是保守近似，需 adapter 做真正 despawn 判斷。
- bare repo 的可編輯 sidecar 為 `.wgignore`、`worldgit-repo.yml`；提交後仍符合 root `.wgignore` 與主世界 world-meta 的設計。其他維度 root worldgit.yml 保留單維度獨立使用的追蹤設定。
- 自訂維度 path 的 `/` 與 namespace/path 的 `.` 做百分比編碼，避免 repo 名碰撞；預設三維度名稱維持文件原式。
- 正式 blob v1、protocol v2 與 Phase 0 不相容，沒有原型 repo 遷移工具。
- bounded gc 目前保守保留不可達 loose objects，尚無到期 prune，未做 delta 壓縮最佳化。外部 JGit GC 不享有正式 API 的 pack 上限保證。
- 離線大型 init 仍需效能調校；目前基線已量測，沒有宣稱與原型相同速度。world-meta 排除已用 field selector 完成；已啟用 datapack 清單記在 metadata，datapack 檔案本身尚未納入快照，也不會在 Phase 1 自動還原/下載。
- Phase 2 apply/restore/switch/merge、DataFixer、POI/光照重算沒有實作；LiveWorld 預留方法，OfflineWorld.apply 明確拒絕，不能以此 API 宣稱已可還原世界。
- 本次只有 protocol 的編碼/重組測試；正式 Paper/Fabric handshake、遊戲渲染、Hub 3D 與四端共同歷史驗收仍由後續任務完成。

修改文件：[02 資料模型](02-data-model.md)、[03 儲存](03-storage-backend.md)、[04 commit/status](04-commit-and-status.md)、[06 diff](06-diff-merge.md)、[08 架構](08-architecture.md)、[09 決策/路線圖](09-roadmap-open-questions.md)，以及本報告、根/core/CLI/protocol README。未執行 git commit/push，未修改 experiments。
