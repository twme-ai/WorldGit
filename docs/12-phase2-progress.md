# 12 — Phase 2 進度

## Hub

Phase 2 任務 H（2026-10-01）：分支瀏覽與任意兩個 commit／分支的比較檢視完成。接手的部分 API 可用，但未驗證；已完成前端、修正分支／snapshot 語意、加回應上限，並重新跑所有驗收。

### 完成項目

- **分支 API／頁面**：同名分支跨維度合併，列出各維度 head、作者、訊息、snapshot、是否同一存檔、缺少的宣告維度；預設分支以主世界 HEAD 為準。世界首頁／commit 頁有分支下拉，歷史可依分支篩選，包含離開 main 的歷史。預設分支切換與 unborn HEAD 的退回規則共用同一實作。
- **ahead／behind**：可選任意基準分支；先合併每個維度可達的 snapshot UUID，再取世界層級差集，避免同一存檔在不同維度被重複計算。每 tip／維度最多 20,000 個 commit，超過時顯示估算；同 tip 分支共用此次請求的走訪結果。
- **compare API／頁面**：`/{owner}/{world}/compare/<a>...<b>`，接受分支、HEAD、唯一的 4–40 位 commit 前綴（包含非預設分支）。支援含 `/` 的分支深連結、交換前後、維度切換、鏡頭／模式網址分享。a→b 的方塊 +/-/~、chunk／section 逐列統計、實體與 metadata 使用現有 core DiffEngine。
- **3D**：上色疊圖、只看變動及周圍一格、前／後切換；前／後載入實際 a／b 的內容及資源，保留鏡頭；一般／色盲色票、環繞／飛行。修正整個 chunk 移除時鬼影不上傳／不卸載的問題；修改種類沿用 core kind，涵蓋 state 相同但 block entity 改變的格子。
- **限制／安全**：授權先於解析／快取，私人世界無權限 404。SecurityFilter 的 DecodeBudget 覆蓋新端點；世界最多 32 維度／500 分支，JSON 最大 4 MiB，清單跨維度最多 2000 chunk／6000 section（含截斷旗標），統計與 bounds 保持完整。3D 沿用 8×8 串流視窗、半徑／LOD 規則與 1024 chunk 請求上限，新增 16 MiB wire 上限；過大回 413。compare 不新增磁碟快取；既有 owner push 配額保留。

### 驗證

| 驗證 | 結果 |
|---|---|
| `GRADLE_USER_HOME=.work/gradle-home ./gradlew --configure-on-demand --max-workers=1 :hub:build` | **全綠，29 個後端測試**（SQLite），包含既有安全／push 配額測試 |
| `npm --prefix hub/web test` | **21 個前端單元測試全綠**；網址解析、維度選擇、分支排序／提醒、呈現模式與跨邊界一格上下文 |
| `npm --prefix hub/web run build` | TypeScript／Vite 成功，正式靜態產物納入 jar |
| `hub/scripts/phase2-acceptance.sh` | 正式 jar、127.0.0.1:18097、持有 `.work/bench.lock`；Playwright 成功，CSP 違規／console error／page error／失敗資源回應皆 **0** |

後端新增整合測試覆蓋：三維度同名分支／缺少維度、領先／落後 snapshot 去重、任意基準分支、預設分支變動、分支歷史、兩分支統計 +64／-5／~4、非 main commit 前綴、相同內容、HEAD、無效參數／不唯一物件前綴、私人匿名／他人 404、公開空世界、64-byte 解壓預算回 413、501 分支與 4 MiB JSON 回 413。兩維度合計 4010 個變動 chunk 只回 2000 筆，完整 chunkCount／bounds 正確；6001 個變動 section 只回 6000 筆且完整新增方塊數正確（此測試專用工作預算 1 億，產品維持預設 5000 萬）。wire 測試確認 16 MiB 邊界先拒絕寫入，以及 UTF 長度不會溢位。

Playwright 驗證：分支頁／分支篩選歷史、明確 base 對另一個分支（不是 b 的 parent）的 73 格差異、四種呈現模式、前後實際方塊內容與鏡頭一致、URL 重載、含斜線分支路由、整個 chunk 移除仍有鬼影 mesh、離開頁面釋放 viewer、私人匿名 404。headless Chromium 使用 SwiftShader；只將測試 CJK 字型與補充樣式以同源回應提供，沒有放寬 CSP 或注入 inline 樣式表。

重跑腳本：[`hub/scripts/phase2-acceptance.sh`](../hub/scripts/phase2-acceptance.sh)／[`phase2-acceptance.mjs`](../hub/web/scripts/phase2-acceptance.mjs)。最新原始證據在 `.work/hub-phase2-h/run-1790869451/acceptance.json`，最後建置紀錄 `.work/hub-phase2-h/final-build.log`。測試 Hub 與瀏覽器已關閉；本任務 `.work/hub-phase2-h/` 約 12.8 MiB，未新增 npm／瀏覽器套件下載，低於 1.5 GB 中間產物上限。

### 精選截圖

四張共 **402,184 bytes（約 393 KiB）**，沿用 Hub 截圖目錄、另置 Phase 2 子目錄：

- [分支頁](../hub/docs/screenshots/phase2/branches.jpg)
- [compare 上色疊圖](../hub/docs/screenshots/phase2/compare-color.jpg)
- [compare 只看變動](../hub/docs/screenshots/phase2/compare-changed.jpg)
- [compare 後版本](../hub/docs/screenshots/phase2/compare-after.jpg)

### 主要改動檔案

- `hub/src/main/java/org/worldgit/hub/history/{BranchService,CompareService,Refs,Dto,HistoryService}.java`：分支／比較、snapshot 合併與解析。
- `hub/src/main/java/org/worldgit/hub/web/{BranchController,BoundedJson,DataController,WorldController,SpaController}.java`：授權端點、回應上限、SPA wildcard。
- `hub/src/main/java/org/worldgit/hub/data/{DataService,ChunkWire,LimitedBuffer}.java`：明確 base、純版本實體、wire 上限。
- `hub/web/src/{api,compare,main,style}.ts/css`、`pages/{branches,compare,commits,world,commit,shared}.ts`、`viewer/{viewer,world,mesher,vertex,shaders}.ts`：分支頁／比較頁與 3D。
- `hub/src/test/java/org/worldgit/hub/{AbstractBranchTest,BranchCompareTest,BranchBudgetTest}.java`、`data/WireLimitTest.java`、`tools/BranchFixture.java`、`hub/web/test/compare.test.ts`、`hub/build.gradle.kts`：測試、固定場景與 Gradle 任務。
- `hub/scripts/phase2-acceptance.sh`、`hub/web/scripts/phase2-acceptance.mjs`、`hub/docs/screenshots/phase2/`、`hub/README.md`、Hub 安全報告補充、`docs/09`（#33–#35）、`docs/10`、本章。

### 限制與後續

- 並排、分割滑桿與時間軸未實作；Phase 2 要求的上色、只看變動、前後切換已完成。
- 明確 commit 跨維度只配對**同 snapshot UUID**的 commit；未變動的維度可能沒有該 UUID，會標示缺少配對、排除統計。要看各維度目前完整狀態可比較分支。不按時間猜測沒有記錄的跨維度 head。
- 沿用 Phase 1 的簡化階梯 LOD／實體線框；BlueMap、AO、特殊模型既有限制、真 GPU／大型真實世界效能量測仍待後續。此次大範圍只驗證 API 截斷／預算，3D 截圖是小型固定場景。
- compare 每次依 core SUMMARY 計算，沒有額外磁碟快取；超過 DecodeBudget 會回 413，需縮小視窗或由管理者調整預算。前／後切換重建 Viewer，需要重新串流。
- **core API 建議（非本任務依賴）**：並行的 core 任務已新增 `RefStore.branches()`／`headState()`；日後可再提供指定 tip 歷史／可達 snapshot 的 bounded read API，讓 Hub 對齊有界走訪與預算規則，減少 JGit 讀取包裝。本任務使用既有 core DiffEngine 與 DecodeBudget，沒有修改 core／platform-api／cli；未提交或推送主工作樹。

## core、platform-api、CLI

Phase 2 任務 1（2026-10-01）：完成離線復原、維度組分支／stash／reset，以及供 Paper／Fabric 接手的中性 apply 與參考批次排程器。沿用中斷前可用的成果，重新檢查並驗證；未修改 Hub、Paper、Fabric 或 experiments 原始碼，未 commit／push。Hub 章節及其他任務的工作樹改動保留。

### 完成項目

- **ApplyPlan／ApplyPlanner**：目前 working tree → 目標 tree，region／chunk／leaf id 相同時短路；計畫只保存差異的壓縮 section、4096-bit mask、BE、biome、tick／structure、UUID 實體與 world-meta。支援有版本 NBT 序列化、範圍縮小、section 分批與實體移除／生成 barrier；實體來源位置隨計畫保存，縮小 box 時能正確裁切刪除操作。
- **範圍**：含端點的 chunk 正方形半徑與 block box；section 中間的邊界逐格套用方塊／BE。biome 以 4×4×4 sample 起點選取，ticks／structures 只在完整 chunk 範圍套用。範圍外的方塊／BE 與明確忽略欄位保留。
- **離線 Anvil 寫回**：RegionWriter 就地更新 sector，未改 chunk 的 payload／location／timestamp 保留；讀 gzip／zlib／raw／LZ4，寫 zlib，處理外部 `.mcc` 及刪除。變更 terrain chunk 清除光照、Heightmaps、starlight 與 POI，設 isLightOn=0。`OfflineApplier.applyAll` 在全維度移除操作 UUID（含 passengers）後才依 Pos 生成，支援跨維度、跨 chunk 移動與去重。真正 OS session lock 在操作全程持有，legacy ChunkPatch 也先鎖再讀。
- **世界組操作**：WorldOperations 全組鎖定、預檢、journal、寫回、全量驗證，成功才更新 HEAD；部分失敗回復 refs 並留下 PARTIAL，force switch／hard reset 可重新套用。branch create/delete/list 同步維度；hash switch 全組 detached；snapshot group refs 記錄未變維度的 HEAD，避免 hash 配對漏掉中途只改另一維度的快照。
- **stash／reset／untracked**：stash 是全維度的獨立 commit 組，push 包含新增及保留的 untracked chunk，pop 限原基底及完整乾淨工作區，成功才 drop。預設保留目標沒有的 chunk 並標 untracked；自動 commit 排除它，成功的 explicit commit 才重新追蹤，gate 拒絕或失敗不清標記。帶 revision 的 reset 必須 --force；無 revision 保持 HEAD。
- **CLI**：restore、switch、branch、reset --hard、stash push/pop/list/drop、verify；JSON、色彩、dry-run、維度與範圍參數，錯誤或 PARTIAL 以非零 exit 回報。新指令連 list／dry-run 也拒絕使用中的 session.lock。dry-run 不寫世界／HEAD／stash，但 capture 會增加物件與可丟棄 index。
- **相容及時間精度**：新增 LiveWorld default 讓既有平台繼續編譯，未實作 apply 的 adapter 明確失敗。WorldGit-Time trailer 保留同秒提交的 Instant 精度，秒數與 git committer 相符；舊 commit 仍讀整秒時間，避免跨維度分組 log 的同秒排序不穩定。

world-meta 套用已追蹤的出生點、gamerules、難度、邊界、worldgen、地圖／記分板等設定；玩家、Time／DayTime、天氣及未追蹤欄位保留，局部 restore 不動 metadata。DataPacks 清單必須一致；完整規則見 [05 §7](05-switch-restore.md)。新決定列於 [09 #25–#32](09-roadmap-open-questions.md)。

### Paper／Fabric 接手 API

| 步驟／介面 | 契約 |
|---|---|
| 世界組 repo executor | 鎖定維度組並持久化操作狀態；flush 後 capture 全組 working tree，用 ApplyPlanner 建計畫。預檢 DataVersion、維度、規則與設定，避免寫到一半才發現不相容。 |
| `ApplyPlan.batches(maxSections)` | 每批嚴格限制 section 數；chunk 級資料在最後一批，entity removal 在任何 spawn 之前，metadata 最後。全維度移動也須共用 removal barrier。 |
| `ApplyScheduler.start(world, plan, budget, executor, observer)` | 保守的單維度、單批參考 coordinator；executor 是 repo／coordinator 執行緒，須存活到 result 完成。observer 持久化 APPLYING／PARTIAL，`applyProgress` 供 UI 通知。多維度線上呼叫端須協調全組 removal，再允許 spawn，不可逐維度完整套用。 |
| `ApplyBudget.DEFAULT`／`WITH_PLAYERS` | 預設每 region 每 tick 8 section／5 ms／24 chunk；有玩家模式 4／5 ms／16 chunk。真正 owner／region 由平台決定；各 lane 共享 region／tick 計數，時間為不可搶占 section 的軟預算，全域 ticket 有上限。 |
| `LiveWorld.apply(batch, budget)`／`nextApplyTick` | 在真正 chunk owner 的執行緒套用；nextApplyTick 延至該 owner 的下一 tick。future 完成或失敗之前，必須釋放該批 ticket。不直接寫線上未載入 chunk 的 `.mca`。 |
| `lockEdits(chunks, reason)` | 包含容器、玩家、活塞、流體、紅石、生物與第三方寫入協調；可 tick freeze，close 恢復原狀。不得在 owner 執行緒等待其他 region。 |
| `Handle.cancel()`／`Handle.result()` | 取消停止派發，等待在途 future 清理，已嘗試寫入即回報 PARTIAL；未開始寫入則 CANCELLED／FAILED。完成批次、section、phase、取消旗標在 ApplyProgress 中。取消不做反向復原。 |
| `finishApply`／`flush` | 成功、取消及失敗都嘗試完成已派出工作的 heightmap／光照／POI／dirty／玩家 chunk 封包及存檔；observer 或光照失敗也繼續嘗試後續清理。flush future 表示 terrain／entity／POI IO 已持久化。 |
| `PlayerProtection` | operation UUID、active、範圍 chunk、FALL／SUFFOCATION／DROWNING。active=true 維持整個操作；active=false 後再延續 10 秒，涵蓋中途進入範圍的玩家。由 EntityScheduler／玩家 owner 取消傷害，不傳送或套 Resistance。 |
| `applyProgress`／`notifyPlayers` | 結構化進度可轉 bossbar／MiniMessage；玩家通知排到各自 owner。只有 COMPLETE，且呼叫端全組驗證成功後，才移動所有維度 HEAD。 |

離線 CLI 用 WorldOperations；低階離線 adapter 用 `OfflineWorld.apply`，多維度直接寫回用 `OfflineApplier.applyAll(plans, sessionLock)`。這些入口不替代平台的 NMS／Fabric owner 排程。API 細節見 [core README](../core/README.md)、[08](08-architecture.md) 與 [LiveWorld](../platform-api/src/main/java/org/worldgit/platform/LiveWorld.java)。

### 驗收與量測

| 驗證 | 結果 |
|---|---|
| core 單元測試 | 49 個通過，涵蓋計畫、序列化／範圍、BE、sector／mcc／LZ4、POI、全維度 UUID／passengers／移動、分支／detached／stash／reset、故障注入 PARTIAL、hash 配對與 untracked。 |
| platform-api 單元測試 | 8 個通過，含嚴格批次、remove→spawn、全操作玩家保護、等待在途取消、寫入失敗、observer 失敗後清理及真正離線 apply／session lock。 |
| CLI 單元測試 | 2 個通過，涵蓋原有流程與新指令 JSON／dry-run／stash／reset／非法範圍及組操作參數。 |
| `build :core:integrationTest :cli:acceptanceToolsJar` | **全綠**；含 baseline 整合的完整重跑 5 分 35 秒，tag 排除回歸後最終 build 1 分 26 秒。兩個 baseline 整合測試 0 failure／0 skip；Phase 2 往返／裁切測試 203.727 秒。 |
| 完整 baseline 複本整合 | 1.21.11／26.2：A → 方塊／BE／實體／跨 chunk 改動 → B，switch A、B 與目標差異都為 0；單格 box 檢查範圍外所有方塊／BE 不變，半徑 0 檢查鄰接 chunk canonical 內容不變。baseline 原件未寫入。 |
| 真 Paper 複本重開 | 兩版各做 A、B 共 4 次：無 ERROR／Exception／Watchdog、光照資料重算、POI 改回正確 lectern 位置、受控 UUID 僅一個，save-all flush 後 verify 差異全為 0。修正全維度 barrier 後再次通過。 |
| 使用中世界拒絕 | 兩版真伺服器持有 session.lock 時，switch／restore／branch list／stash list／reset／verify 均拒絕。 |
| 1,000 chunk 離線 switch | 最後回歸量測 20.633 秒、wall 20.76 秒，max RSS 338,372 KiB（約 330.44 MiB），verify 差異 0。首次量測 23.824 秒／332.45 MiB。 |

整合測試在 `.work/worlds/<version>/baseline` 不存在時略過；本機兩版都有，實際執行而未略過。完整 baseline 往返由 [Phase2LocalIntegrationTest](../core/src/test/java/org/worldgit/core/Phase2LocalIntegrationTest.java) 負責；真伺服器驗收使用 baseline 設定的複本，terrain 換成 441 個受控平坦 chunk，以 frozen tick、固定 gamerules 隔離自然演化。B 先經伺服器存檔補齊 BE 預設欄位，再 commit；不是拿不完整手工 NBT 當穩定目標。這是無玩家的離線還原／重開驗收，未測線上 TPS 或玩家活動。

重開前受影響 chunk 的 BlockLight／SkyLight／starlight 都移除，POI 為空；重開存檔後樣本 chunk 有 1 個 BlockLight section、2 個 SkyLight section、starlight version=10，UUID 無重複。兩版 Paper 的 isLightOn 仍為 0，因此以實際光照陣列與 starlight 資料確認重算，沒有把 isLightOn=1 當唯一標準。A 的 librarian POI 為 `[3,65,3]`，B 為 `[4,65,4]`；舊位置不存在，verify 全組為 0。baseline 所有檔案 SHA-256 前後一致。

1,000 chunk 量測為 26.2 合成平坦世界，每 chunk 改一個 section，共 1,000 section；JDK 21、`-Xmx1g`，持有 bench.lock。switch 包含完整 capture、計畫、就地寫回、驗證及 HEAD 更新；max RSS 含 JVM 原生記憶體。只有單次正式量測與一次修正後回歸重跑，不能當成大型真實世界的速度保證，也不能與 Phase 0 線上 TPS 數字直接比較。

重跑方式：

```sh
flock .work/bench.lock env GRADLE_USER_HOME=.work/gradle-home \
  ./gradlew --no-daemon --configure-on-demand --max-workers=1 \
  build :core:integrationTest :cli:acceptanceToolsJar
python3 scripts/verify-phase2.py --results-dir .work/phase2-core-next
```

驗收腳本自取 bench.lock，Paper 用 JDK 21／25、`127.0.0.1`、offline、25661／25662，伺服器與 worlds 都複製，不修改原件。finally 停服並刪除暫存世界／server 複本；既有證據目錄拒絕覆寫。首次結果在 `.work/phase2-core/results.json`，最後回歸在 `.work/phase2-core-final/results.json`，包含逐版本／目標的 apply、光照／POI／UUID 檢查、verify、baseline 雜湊結果與 time／RSS；相鄰 log 留原始紀錄。完整 baseline 整合建置紀錄為 `.work/p2-core-build-verified.log`；補上刪除 chunk 時的 tag 排除回歸後，最終建置紀錄為 `.work/p2-core-build-release.log`，測試 XML 位於各模組 `build/test-results/`。

### 接手中斷與失敗紀錄

已讀上一輪原始 session 紀錄：15:30 的 heredoc 已寫入 ApplyPlanTest，exit 1 出自隨後 PlatformTest 的 snapshot ref 衝突；15:32 的 Python 已完成 `refs/worldgit/snapshots/<UUID>/<commit>` 與 Time 的 Number 讀取修正，exit 1 出自隨後 Gradle daemon 消失。兩處檔案不是半寫狀態，這些修正保留並重新編譯／測試。

本輪遇到並修正／重跑：排程器取消測試在派發前等待 progress 的競爭（改等實際 apply 開始）；新測試 SortedMap fixture 型別與 nether chunk 假設錯誤；共享 Gradle daemon 消失（重跑改 --no-daemon）；完整建置遇到 Fabric 同秒 log 排序不穩定（由 core WorldGit-Time 精度修正）。最後覆核也補上刪除 chunk 時使用正式 EntityTagRegistry 的修正及回歸測試，避免版本／datapack tag 排除規則在寫回階段失效。故障注入的多維度寫回與 observer 失敗是預期的 PARTIAL 測試，確認沒有移動 HEAD，並可全量恢復。最終結果以 XML 測試報告與獨立最後建置 log 為準，不使用曾被兩次指令共用覆寫的早期 log 判定成功。

### 差異、限制與後續

- 跨 DataVersion（較新或較舊）一律明確拒絕，沒有可靠 DataFixer 升級；規則不同、DataPacks 清單不同也預檢拒絕。完整 metadata 還原規則已寫入 docs/05，沒有還原玩家資料或世界時鐘。
- Phase 2 在線 Paper／Folia／Fabric adapter、命令與 UI 尚由後續平台任務實作；本任務的 reference coordinator 是單維度、單批，真實 region 的時間／ticket 限額、全維度線上 barrier、玩家保護與通知都需平台落實。本輪沒有玩家在線、TPS、自然生物或逐格光照驗收。
- 離線就地更新不是跨檔案或斷電原子交易。APPLYING／PARTIAL journal 與 refs pin 支援全量重套恢復，不自動逐 section 續傳；從 journal 恢復與從 refs 回復都是保守流程。
- biome sample 不可方塊級拆分；tick／structure 仍 chunk 級。box 的完整高度判定採 -64..319，自訂高度維度建議用 chunk 範圍。使用者明確忽略的資料只合併頂層欄位。
- verify 比對正規化、可套用的追蹤內容，保留的 untracked 另列數量；不是 raw `.mca` 位元組相等。stash pop 不三方合併，要求原基底且包含 untracked 的工作區乾淨；legacy ChunkPatch 的非空 expectedChunkTree 無法可靠驗證而拒絕，請使用全量 capture 的 ApplyPlan。
- snapshot／operation refs 保守 pin 物件，目前沒有到期清理策略；stash drop 清掉 stash／snapshot pin，既有 GC 仍不立即 prune。revert、worktree、preview 仍在原定後續階段。

兩次驗收證據及凍結的 jar 共約 75.7 MiB；core／platform-api／CLI build 約 62.3 MiB，合計約 138 MiB（不含共用既有 Gradle 快取）。暫存世界與伺服器副本已刪除，25661／25662 已關閉，低於 3 GB 中間產物上限。core／platform-api／CLI 原始碼、測試、README、scripts/verify-phase2.py 與 docs/02／05／08／09／本章構成本任務產出。
