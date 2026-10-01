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

## Hub（Phase 1 Hub 任務）

程式在 `hub/`（說明見 [hub/README.md](../hub/README.md)）：Spring Boot 3.5（Java 25）+ SQLite（PostgreSQL driver 已帶）、TypeScript/Vite 前端、OCI 容器。

### 完成項目

- **帳號與多租戶資料模型**：owners／users／memberships／tokens／worlds／push_events；本機帳號 + token（Bearer 與 git 用的 Basic）、啟動時建立管理員。權限僅 owner／writer／reader 基本檢查，OAuth 與細部權限留到 Phase 4。儲存層介面（`RepoStorage`，本機磁碟實作，S3 只預留介面）。
- **Git smart HTTP**：JGit `GitServlet` 掛在 `/git/{owner}/{world}/{維度目錄}.git`；push 需 WRITER，公開世界可匿名 clone；pre-receive 驗證 WorldGit trailers 與維度一致，非 WorldGit commit、non-fast-forward、刪除分支都被拒；push 後非同步 bounded repack（呼叫 core，> 95 MB 才切）。
- **世界 = 一組維度 repo**：snapshots API 依 `WorldGit-Snapshot` 把各維度 commit 合併成一列，標示 auto commit；宣告的維度 repo 尚未推送或 push 被拒時標為「部分推送」。
- **資料 API**：commit 詳情（+/-/~、變動 chunk、實體變動；初始 commit 只列 chunk 數）、WGCK 方塊串流、WGDF diff、實體 JSON、伺服器預先計算的 512×512 俯視 tile 與 128×128 高度圖（內容定址磁碟快取）、色票（protocol.DiffPalette）。資源管線以 Java 重寫（client jar → 貼圖集／blockstates／models／biomes／mapcolors；26.2 約 2.2–2.4 s），自 Mojang 下載並以 SHA-1 驗證。
- **前端**：世界首頁（維度分頁、2D tile 地圖 + 變動 chunk 疊圖、clone/push 指令）、commit 列表（auto 折疊、部分推送標示）、單一 commit 3D 檢視：worker 內 greedy 網格 + deepslate 模型層、16 B 頂點、近景細節半徑可調 + 遠景高度圖 LOD、diff 上色（新增綠、移除紅鬼影、修改黃角標、衝突紫預留；一般／色盲色票）、只看變動／淡化、環繞／飛行鏡頭、點方塊看資訊。
- **容器**：`hub/Containerfile`、`hub/compose.yaml`、`hub/deploy/` Quadlet、`hub/scripts/container-smoke.sh`；詳見 [07 §4.1](07-remote-hub.md) 的實作狀態。
- **共用模組**：core `JGitStore.readOnly(Repository)`（唯讀、共用 repository、close 不關 repo；向後相容，新增 `JGitStoreReadOnlyTest`）。沒改 platform-api／protocol。CI 只增補 `hub-web`（tsc + vite build + vitest）與 `hub-image`（`docker build`）兩個 job，沒動 Java 設定。

### 驗收結果（真實執行；截圖在 `hub/docs/screenshots/`，共約 1.4 MB）

- **流程**：`wgit` 對測試世界（26.2 baseline 複本，626 chunk）init + 一個編輯 commit（+381 −29 ~4，6 chunk）→ `git push`（smart HTTP + token）→ 首頁／世界頁／commit 列表（`home`、`world`、`commits`）→ 開 commit 的 3D 與 diff 上色（`commit-initial`、`commit-diff`、`commit-diff-colorblind`）。截圖由 Playwright + 系統 Chrome（SwiftShader 軟體 GL）擷取。3D 檢視：256 chunks／2,068 sections 載入，就緒 4.8–10.6 s（軟體渲染、機器同時有其他任務），mesh 平均 0.6–0.9 ms／section，GPU 記憶體約 51 MB。
- **部分推送**：新世界只推主世界 → snapshot `partial=true`、網頁標示「部分推送」「終界／地獄 未推送」（`commits` 截圖）；補推後恢復完整。已有整合測試。
- **2 萬 chunk 世界**（Paper 任務產出，主世界 20,449 chunks，2 個 commit，36 個 region）：push 主世界 8 s（2 個 pack 80,922,065／32,229,422 bytes，Hub 端 repack 判定無需再切、皆 < 100 MB）。API：世界首頁資料 0.06 s、snapshots 0.03 s、commit 詳情 0.08 s（單格修改）、13×13 chunk 視窗 0.30 s／519 KB（gzip）。俯視 tile：單 region 冷計算 0.66–2.9 s，36 個 region 冷計算循序共 15 s，之後命中快取 7–12 ms。瀏覽器：世界頁地圖載入完成；commit 3D 檢視就緒 9.95 s（256 chunks 近景 + 9 個 LOD region，GPU 63 MB），截圖 `scale-world`、`scale-commit`。以上皆為單次量測，軟體渲染，非效能基準。
- **容器**（Podman 4.9.3 root 模式）：`hub/scripts/container-smoke.sh` 全程通過——build（含 Gradle 與 npm）、容器啟動 8 s 就緒、uid 10001、push 三維度、API 讀回、clone 回來、資料集中在 `/data`；`podman-compose up` 後 healthcheck 轉 healthy。映像 402 MB。
- **測試**：`./gradlew :core:build :protocol:build :hub:build` 綠（合計 33 個測試通過，其中 hub 5 個：NameRules 2、端到端 3；core 新增 readOnly 測試）；`hub/web` `npm test` 8 個（wire 解碼 WGCK/WGDF、unpackBits、LOD）、`tsc`、`vite build` 通過。端到端測試涵蓋：錯誤 token 被拒、非 WorldGit commit 被拒、部分推送 → 完整、匿名不可讀私人世界、commit 詳情、chunk 二進位 magic。

### 安全修補摘要（2026-10-01）

完整報告與逐項驗證見 [hub/docs/security-review-2026-10-01.md](../hub/docs/security-review-2026-10-01.md)。已修補：H1（私人世界不存在／無權限一律 404；Git 未認證請求統一 401 challenge，公開匿名 clone 用明確的 `anonymous` 空密碼）、M1（CSP／nosniff／DENY／Referrer-Policy／Permissions-Policy，HSTS 由設定開關、預設關；Playwright 在 CSP 下載入 3D 無違規）、M2（`max-pack-bytes` 改 95 MB、owner 層級配額預設 10 GiB）、M3（core 向後相容 `DecodeBudget.open()` 聚合解壓縮／解析預算，高壓縮比 blob 實測回 413 且不 OOM）、M4（登入與 Git Basic 速率限制與暫時鎖定，XFF 只信任設定的代理）、Low（tree 名稱 4xx、PAT 到期日與 `last_used_at`、compose 與 Quadlet 改用 secret 檔案 + `*_FILE`、Containerfile 基底映像釘 digest）。依賴升級：tomcat-embed-core 10.1.60、jackson-databind 2.21.7、postgresql 42.7.13、lz4（groupId 改 `at.yawk.lz4`）1.11.4，OSV 對這些版本查無已知漏洞。CI Actions 已釘選 commit SHA。共用模組：core 新增 `DecodeBudget`（向後相容）。政策性項目（開放自助註冊前的評估、bootstrap 密碼處理）未實作，列在 docs/07 上線前必做。尚未做：CI 在 GitHub 實跑、Quadlet 實機啟動（compose 路徑已冒煙）、PostgreSQL 後端測試。

### 與設計的差異／未完成

- **遠景沒有嵌入 BlueMap core**：以伺服器預先計算的高度圖 + 俯視色做階梯 LOD（只載鏡頭附近 3×3 region）；2D 地圖也是自己的 tile 渲染（`mapcolors`）。BlueMap 嵌入留待後續。
- 沒做 AO、告示牌文字／頭顱皮膚；實體只畫線框；grass_block 不走 greedy（有 overlay）。
- 初始 commit 的統計只列 chunk 數，不逐格計數。部分推送判定只看「宣告的維度 repo 沒推送」與「push 被拒」，沒有 push option 預期清單。
- mesher（greedy + 模型）沒有單元測試（需要完整資源包），目前由 Playwright 截圖驗證；前端只有繁體中文。
- 容器：rootless Podman、Docker、arm64、Quadlet 由 systemd 實際啟動都**未驗證**；冒煙測試尚未進 CI（CI 只 `docker build`）。
- S3 儲存、PostgreSQL 實跑、OAuth、配額／速率限制未做（只有介面／driver 預留）。
- 修正的坑：ReceivePack 的 RevWalk 不保留 commit body（需 `parseBody`）；SQLite 不會建父目錄；Gradle 設定時 `--configure-on-demand` 必須（其他並行模組可能暫時壞掉）；映像建置 context 不含 paper／fabric，Containerfile 為 settings 宣告的專案建立空目錄。

## Paper/Folia（Phase 1 插件任務，2026-10-01）

正式插件在 `paper/`，使用方式見 [paper README](../paper/README.md)。同一 jar 內含 Java 21 的 common／1.21.11 adapter 與 Java 25 的 26.2 adapter；執行時依版本載入，Paper/Folia 共用 Region／Global／Entity／Async scheduler。

### 已完成的插件功能

- `/wg init/status/commit/log/diff/clear/reload`、權限、每維度 bare repo、與 CLI 相同的 blob／index／trailer 格式。status 可以全量掃描；diff 只展開玩家附近的 chunk。
- 線上 chunk 在擁有執行緒複製 palette、BE 與實體，背景完成編碼／正規化／寫 repo；滑動視窗限制記憶體。原始 unsaved、Bukkit 事件、WorldEdit/FAWE 與有實體的 chunk 是候選來源；實體移動的來源與目的 chunk 都重新擷取，同次掃描以 UUID 去重。
- 多位作者、原因與 chunk 集合寫進 Contribution／Co-authored-by；失敗或未達 gate 門檻會還原歸屬，新事件不會被舊 dirty batch 清掉。定時／登出自動 commit；Paper 關閉前 commit；純實體變動預設不觸發定時 commit，即使達 max-wait 也維持此設定。
- protocol v2 的 nonce／版本／能力握手、status／diff／clear、每包 ≤ 28,000 bytes、每 tick 最多兩包；新預覽或 clear 會停止舊分包的傳送。未裝模組時顯示聊天提示與可點擊座標。
- `i18n.MessageCatalog` + MiniMessage，完整 en_us／zh_tw 的 `paper.*` 鍵、玩家客戶端語言、`plugins/WorldGit/lang/` 覆寫及 reload。diff 自訂標籤讀 protocol 的一般／色盲色票，外部參數不解析成標籤。
- CI 同時安裝 JDK 21／25，檢查 adapter 與內部類別的 NMS 描述子、公開存取權限、反射 unsaved 欄位；1.21.11 adapter 另對 26.2 檢查。Paperclip 使用版本與 SHA-256 正確的 patched jar，避免兩版同目錄誤用 cache。

### 驗收方法與底噪發現

真實伺服器、mineflayer bot、CLI 與負載全程持有 `.work/bench.lock`，綁 127.0.0.1、offline mode、port 25651–25654，finally 停止伺服器與 bot。acceptance／smoke／benchmark 內部取得鎖，外層只使用 `timeout`；其他重負載才使用 `timeout ... flock`。

原 baseline 的生物 AI、kelp／水、掉落物與燃燒熔爐是**真實世界演化**，不能當成雜湊錯誤，也不能以排除欄位偽造 no-op。1.21.11 曾看到 `(11,150,24)` 的 furnace `lit=true → false`；26.2 曾在 no-op 看到 item 移動，關機後看到 drowned 移動。舊 camelCase gamerule 在兩版會被拒絕，需使用 `random_tick_speed`／`spawn_mobs`／`advance_weather`，腳本保留實際回應。kill 生物還會產生掉落物，因此單次 kill 不保證靜止。

「沒有人編輯」並不等於「世界沒有變動」；生存世界預設仍保留這些內容，應以 auto threshold 或管理者的 ignore 政策控制頻率。最終無變動／單格測試使用 `ScaleFixture.java` 生成的**合成平坦副本**：主世界沒有生物、BE、流體與隨機刻，保留世界設定與另兩維度 baseline。原 baseline 不改動；init 後暖機基準與兩次 no-op 分開記錄。

mod 場景最初握手成功但封包為 0，原因是 bot3／bot4 沒有 op；補權限後四個 channel 已實際收到。客戶端使用 en_us 時，未裝模組提示也為英文；驗收需接受兩個語言。定時與登出測試使用分開的定時間隔，避免 timer 搶先提交而誤判登出觸發失敗。

原關機後 `world-meta/level.nbt` 差異來自受測 gamerule 尚未保存到 level.dat，並非 Time／LastPlayed。插件現在在全域排程器複製活 gamerule，1.21.11 寫 level 的 game_rules，26.2 寫各維度 saved data；沒有把受追蹤設定排除。

### 測試與平台結果

`--configure-on-demand --max-workers=1 :paper:plugin:jar :paper:common:test :core:test :i18n:test` 通過：Paper common **13**、core **33**、i18n **3** 項，全部 0 failure／error／skipped。common 包含多人 drain／restore 與並行寫入、entity 跨 chunk 與 generation、1,000 份快照背壓／失敗／取消／shutdown inline、YAML 型別與門檻、兩語言／兩色票／四種 diff、注入防護、語言覆寫 reload、26.2 世界根目錄。core 的 gate／window 新重載有回歸測試。

### 四個伺服器的驗收結果

在合成平坦副本（`ScaleFixture.java`，周圍 10 chunk 的緩衝邊界（benchmark JSON 內 paper 的 fixture 欄位誤標為 4-chunk-halo，實際皆為 10），主世界無生物／BE／流體）上，paper-1.21.11、paper-26.2、folia-1.21.11、folia-26.2 **全部場景通過、0 失敗**：init、連續兩次 commit 無變動、bot 放一格（status 恰好 1 個 section、commit 後 CLI log／diff 看到同一格 +1）、兩位作者歸屬（各自 chunk）、mod 握手與 status／diff／clear 封包（Paper 與 Folia 皆測，未裝模組玩家有聊天提示）、WorldEdit／FAWE 大範圍 //set 偵測與歸屬（Paper 用 FAWE 2.15.0；Folia 用純 WorldEdit）、定時自動 commit、無變動不產生 commit、登出自動 commit、離線 CLI status 無變動（Paper）。Folia 的關閉前 commit 是已記錄的限制，驗收改為斷言「不建立 commit、未提交的一格仍被離線 CLI 恰好看到」，兩版 Folia 通過該斷言（不是「關閉前 commit 通過」）。

過程中發現並修正：合成副本若緩衝邊界只有 4 chunk，伺服器載入範圍會生成邊界外的 chunk，關機後離線 CLI 看到邊緣 chunk 的大量差異，是副本邊界的人為影響，不是插件 bug；邊界加大到 10 後消失。`check_binary_compat.py` 先前對帶 `throws` 的方法簽名解析錯誤（對 26.2 誤報 `NbtIo.write` 缺失），已修正。

**26.2 二進位相容檢查**（Java 25 javap；`paper-26.2.jar` 由 Paperclip patch 出）：22 個直接引用，`Bridge_26_2` 對 26.2、`Bridge_1_21_11` 對 26.2、`Bridge_1_21_11` 對 1.21.11 皆 **0 問題**。

### 1 千／1 萬 chunk 量測

負載是合成平坦 chunk（每輪把指定數量的 chunk 全部改成不同方塊，兩輪：gold／diamond），首次 commit 前先載入、init、暖機。「copy」是擁有執行緒上複製快照的阻塞；probe 是出生點所屬 region（Folia）／主執行緒（Paper）的 tick 間隔（含 GC 與其他工作）。所有伺服器 TPS 約 20.0。實際載入／掃描 chunk 為 1,272（1 千組）與 10,816（1 萬組）。

| 平台 | 變動 chunk | commit 總時間 | 複製總計 | 單 chunk 平均 | 單 chunk 最大 | tick 間隔 p99／最大 | >100 ms 的 tick |
|---|---|---|---|---|---|---|---|
| Paper 1.21.11 | 1,000 | 17.1／17.0 s | 38.4／32.5 ms | 25–30 µs | 0.45／2.6 ms | 51.1／57.3 ms | 0 |
| Paper 1.21.11 | 10,000 | 145.0／145.7 s | 292.5／336.6 ms | 27–31 µs | 6.2／**45.8 ms** | 53.9／**167.3 ms** | 第二輪 1 次 |
| Paper 26.2 | 1,000 | 17.3／17.0 s | 40.1／31.9 ms | 25–31 µs | 0.42／0.34 ms | 53.2／61.5 ms | 0 |
| Paper 26.2 | 10,000 | 144.9／147.3 s | 225.5／240.8 ms | 20–22 µs | 1.8／12.3 ms | 53.9／91.2 ms | 0 |
| Folia 1.21.11 | 1,000 | 11.7／11.1 s | 39.2／29.5 ms | 23–30 µs | 0.66／0.32 ms | 50.9／51.8 ms | 0 |
| Folia 1.21.11 | 10,000 | 93.4／90.8 s | 256.4／225.4 ms | 20–23 µs | 13.3／0.95 ms | 54.9／83.4 ms | 0 |
| Folia 26.2 | 1,000 | 9.9／9.4 s | 39.2／29.9 ms | 23–30 µs | 2.2／0.22 ms | 54.6／55.2 ms | 0 |
| Folia 26.2 | 10,000 | 81.9／83.4 s | 279.9／245.5 ms | 22–25 µs | 24.1／9.1 ms | 56.3／62.2 ms | 0 |

每輪都是兩個數字（gold／diamond 輪）。結論：commit 時主執行緒只做複製，1 萬 chunk 的複製總計約 0.23–0.34 s，分散在多個 tick；整次 commit 約 80–147 s 在背景完成。Paper 1.21.11 的 1 萬組第二輪出現一次 167 ms 間隔與單 chunk 45.8 ms（疑為 GC／單次延遲，各單次量測，沒有重複多輪取統計）。這是單次、合成平坦資料、單機三核心與其他任務並行下的數字，不代表生存世界；生物與 BE 較多的世界複製成本較高。結果 JSON 在 `.work/paper-delivery/results/benchmark-*.json`。

`./gradlew --configure-on-demand --max-workers=1 build`（含 paper／core／i18n 測試）通過，BUILD SUCCESSFUL，log 在 `.work/paper-delivery/logs/gradle-build-final.log`。


### 與設計的差異與未完成

- 登出 commit 是當前維度／世界的全部待提交變動，**沒有 per-player staging**；作者是 chunk 級歸屬，沒有逐格 blame。
- **Folia shutdown commit 略過**：disable 時 region scheduler 已不可用，沒有單一 owner thread。定時與登出可用，但不能把 Folia 關閉前未提交內容宣稱已建立存檔點。
- 未載入部分沿用 core 磁碟掃描，尚無伺服器 chunk IO／flush barrier；卸載存檔仍排隊時可能到下一次 scan 才反映。跨 chunk 快照不是同 tick transaction，UUID 去重保留第一份。
- 世界級 metadata 除 gamerule 外仍以已落盤內容為準；正在修改但尚未保存的地圖／記分板等沒有完整的活資料擷取。
- display entity fallback、真正客戶端渲染／截圖與四端共同 push 的端到端流程不由本次 bot 封包測試證明；Fabric／Hub 的實際畫面驗收見各自章節。Phase 2 apply／switch／restore／保護尚未實作。
- FAWE 的 `//regen`、筆刷、schematic、biome 修改模式未逐一驗證；沒有 PacketEvents 補充監聽，內容雜湊仍是最終真相。
- CI 用 `https://fill.papermc.io/v3/projects/paper/versions/<version>/builds/latest` 的 `downloads["server:default"].url`，**未對線上 API 驗證**。本機 Fill v3／舊 API 曾回 429／503／504；v2 不支援以 latest 當 build id。CI 的實際網路下載仍需線上跑確認。

本端的共用改動是向後相容的 `DimensionRepository.status(detail, window)`／`commit(gate)` 與測試、`i18n` 的 Paper 鍵，以及 settings／CI 的最小增補；沒有修改 `hub/`、`fabric/` 或 experiments，沒有 git commit／push。

## Fabric 模組（Phase 1 Fabric 任務，2026-10-01）

完整說明見 [fabric/README.md](../fabric/README.md)，截圖見 `fabric/docs/screenshots/`（14 張，3.9 MB，真正 Xvfb／llvmpipe framebuffer）。

### 完成項目

- `fabric/` 分為純 Java 21 的 `logic`（含 JUnit）、共用 Minecraft 原始碼 `shared` 與 `mc1_21_11`／`mc26_2` 薄轉接層，產出 Java 21 與 Java 25 兩個 jar。沿用 Phase 0 的 Loom 1.17.21、Loader 0.19.5、Fabric API 0.141.6／0.161.0，未升級。
- 伺服端：實作 `LiveWorld`，`/wg init|status|commit|log|diff|clear|info|reload`、自動 commit（定時、登出、關閉）、chunk 級作者歸屬、YAML 設定、每維度一個 repo，格式與 CLI／Paper 相容。
- 客戶端：v2 握手、section 分塊裁切、視錐／距離裁切、遠處包圍盒、新增實線／移除鬼影／修改虛線／衝突閃爍、色盲色票、`/wgc`。客戶端與伺服端文字走同一套 i18n（MiniMessage＋Adventure Fabric 6.8.0／7.1.1，兩版實機查證）。
- 單人世界偵測 setblock 的 bug：單人世界中 `chunk.isUnsaved()` 不可靠地反映 setblock（gametest 實測 `unsaved=0`，專用伺服器則稍後才偵測得到；確切原因未完全查明，疑與原版存檔時機有關），只靠它會漏。修法是對 `LevelChunk.markUnsaved` 加 mixin，把變動記入 WorldGit 自己的 generation tracker，直到成功 commit 才 acknowledge；runtime 在世界載入前建立。測試：gametest 斷言 `status sections=1`，並強制存檔後再斷言一次（`saved-status sections=1`）。
- 先前修掉的 bug：關閉流程中 `server.execute` 會在呼叫者執行緒立刻執行造成 chunk map 損毀，改為自有 server task 佇列。

### 驗收結果（真實執行）

| 項目 | 1.21.11 | 26.2 |
|---|---|---|
| 單人世界 client gametest（建世界、init、三格變動、status、強制存檔後 status、diff、色盲、commit、LOD、clear、zh_tw） | 通過（最後一次 11:19–11:20，exit 0） | 通過（10:59–11:04） |
| status／diff | 1 section，+1 -1 ~1，強制存檔後仍 1 section | 同左 |
| 離線 CLI `wgit log`／`diff` 讀到同一對 commit | 通過，作者 Player0 | 通過 |
| 專用伺服器（最終 jar）：init→setblock×3→status→commit→log→status | 1 chunk／1 section，`+3 -0 ~1`；commit 後 clean；CLI 讀到同樣 log/diff | 1 section，`+3 -0 ~0`；commit 後 clean；CLI 相同 |
| 專用伺服器載入檢查（`.work/fabric-acceptance/check-dedicated.py`，jar 與最終建置相同 SHA-256） | 通過 | 通過 |
| 連 Paper（真客戶端，port 25663／25664）：握手、三格 diff、status 描邊、截圖、clear | 通過 | 通過 |

- 專用伺服器 1.21.11 的 `~1` 是 flat 世界該位置原本有方塊（被 stone 取代）；26.2 的對應位置為空氣。
- Paper 驗收使用固定的插件 jar 副本（來源時間戳 `2026-10-01T10:52:18.091997Z`、12,546,211 bytes、SHA-256 `f8d6c585...10a51e`），不受並行重建影響。第一次連 Paper 失敗：驗收腳本把 console ANSI 色碼讀進玩家名稱導致 Paper 崩潰，已修正解析，失敗紀錄保留在 `.work/fabric-acceptance/`。
- 26.2 gametest 第一次失敗：草地隨機刻腐化造成多一格變動；驗收世界關閉隨機刻與生物生成後通過，失敗紀錄保留。
- 3,072 格、6 sections 的合成 preview：遠處 0 個明細 section 只畫包圍盒，靠近後 6 個明細 section。這是軟體渲染下的功能驗證，**不是** 100,000 格的效能量測。

### 與設計的差異與未完成

- 1.21.11 的 client gametest 需停用 Fabric 測試框架的 NetworkSynchronizer 才能載入整合世界（只在測試 run 設定），26.2 不需要。
- 合成的四種類型（含衝突）只驗證渲染；衝突沒有 merge 功能。
- 沒有：準星前後 state UI、實體／biome 模型、流體與特殊 block entity renderer、Mod Menu 畫面、資源包重載後模型快取重建、Sodium／Iris、硬體 GPU、100,000 格效能量測；Phase 2 apply／restore／switch／merge 與編輯鎖（`lockEdits` 空實作）。
- 多人登出提交共同工作世界，沒有 per-player staging。

### 共用模組改動

- core：`EntityTagRegistry.PackResolver` 與 `load(world, dv, resolver)`，`OfflineSnapshotSource` 增加 resolver 建構子，fabric* pack 無 resolver 時警告而非失敗；`RegionFile` 將 0 byte `.mca` 視為空、末尾未補滿 4096 的 chunk 可讀。測試：`EntityTagRegistryTest`、`CodecNormalizationTest`，`:core:test` 通過。
- 根專案：`settings.gradle.kts`（Fabric repo、include、`repositoriesMode = PREFER_PROJECT`）、`gradle/libs.versions.toml`、`ci.yml`（上傳 fabric jar）。沒有修改 `hub/`、`paper/`、`experiments/`，沒有 git commit／push。
