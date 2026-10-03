# Phase 3 任務 1：core 三方合併與離線 CLI

日期：2026-10-02。本節記錄最初的 core／protocol／CLI 基礎；後續 Paper、Fabric、Hub 的 Phase 3 實作、區域切換修正與真客戶端對接分別記錄在下方。決定見 [09 #43–#49](09-roadmap-open-questions.md)。

## 完成項目

- 分支與 hash 的 snapshot group 配對、各維度的全 parent DAG merge-base。來源缺少維度時保留 ours；無共同歷史用空 tree；criss-cross 多個最佳 base 明確拒絕。
- root／region／chunk／section 短路與 4096 格比較。方塊＋完整 BE 為原子；biome 逐 sample；實體於維度內跨 chunk 以 UUID 比較；ticks／structures 整 blob；world-meta compound 遞迴逐鍵。
- 3D 衝突分群（曼哈頓距離 k=0..16，預設 1）、門／大型植物／床／活塞強制綁定、UUID 移動端點綁定。區域記錄維度、包圍盒、格數、雙方作者、紅石警示；實際切換只寫精確 atoms。
- MERGING 持久化、ours／theirs／base 原地切換與預覽、獨立 resolved 旗標、manual 取目前世界、全組 continue／commit／abort，沿用 journal／PARTIAL／verify barrier。
- `.wgignore` 有序文字三方合併、規則衝突預檢拒絕、合併規則過濾三邊、重新納入 ours 的活世界資料；規則遷移前另保存被排除的內容供 abort 還原。跨 DataVersion 依 #28 拒絕。
- merge 產生共用 snapshot UUID 的整合提交與來源 trailer；revert／cherry-pick 以反向／正向 patch 走同一套衝突流程。
- CLI `merge`／`--abort`／`--continue`、`conflicts`、`resolve`、`revert`、`cherry-pick`、MERGING status／commit，支援 JSON、dry-run、紫色 `!`、規則差異與 updateShape 提示。
- 可選衝突清單與帶 BE 的方塊預覽 protocol，保持既有 v2 訊息相容；完成報告保存供後續平台讀取。

## 給 Paper／Fabric／Hub 接手的 API 摘要

### 線上世界 coordinator

入口為 `org.worldgit.core.service.WorldOperations`。離線建構子使用 OfflineApplier；線上透過 `WorldOperations.live(...)` 與 `LiveAccess` 的 capture、validate、apply／flush barrier。完整作業由呼叫端持有平台編輯鎖／freeze；區域入口由 core 呼叫 `lockChunks`。兩者都遵守 owner 排程契約，不能在 region owner 阻塞等待自己的工作。

追加任務新增向後相容的 `LiveAccess.source(dimension, chunks)`、`entityChunks(dimension, UUIDs)`、`lockChunks(dimensionChunks)` 與 `applyRegions(plans)`；Paper／Fabric 實作直接局部來源與存檔。預設 source 仍退回完整 scan，其他平台若只實作舊介面不會自動獲得同等效能，且須繼續持有外部鎖。詳見本文件「區域切換延遲」。

| 操作 | API／契約 |
|---|---|
| 開始 | `merge(revision, MergeOptions)`，另有 `revert`／`cherryPick`。options 含 noCommit、strategy（null／OURS／THEIRS）、distance、dryRun、author、source。線上應設 **noCommit=true**，讓 shape 更新發生在最終 capture 前。 |
| 查詢 | `merging()` 回傳 null 或 MergeState；`remaining()`、`regions()` 為全維度清單。`lastMergeReports()` 讀取最近完成報告。 |
| 只切換區域 | `selectRegion(id, Choice, resolved=false, dryRun)`；id=0 表示 all。候選固定來自開始時的三邊，切換範圍外的手動修改保留。回傳該次區域 ApplyPlan。 |
| 標記解決 | `markResolved(id, manual, dryRun)`；manual=true 以目前世界 capture 為準。CLI `resolve` 用 `selectRegion(..., resolved=true, ...)` 一次切換並標記。 |
| 預覽 | `regionPreview(id, OURS／THEIRS／BASE)` 回傳 PreviewBlock(position, state, blockEntity)。BE 為完整 canonical NBT；manual 預覽由平台從活世界讀取。 |
| 完成 | `continueMerge(author, source, dryRun)` 或 `commitMerge(author, source, message, dryRun)`。尚有 unresolved／PARTIAL 時拒絕；capture 全組目前世界，再提交。 |
| 取消 | `abortMerge(dryRun)` 可恢復 MERGING 的 PARTIAL；全組還原原內容／規則，成功才清狀態。合併期間 HEAD 被外部修改時拒絕，先恢復原 HEAD。 |

`MergeResult` 包含 state、merging、每維度 reports、plans、commits、error。state 為 COMPLETE／MERGING／PARTIAL／DRY_RUN；**coordinator 的非 dry-run ApplyPlan 已經執行並驗證，呼叫端不要再次套用**。開始合併要求乾淨（含 untracked），不自動 stash；既有 switch／reset／stash／一般 commit 在 MERGING 被擋下。線上不能安全套用的 metadata／chunk 刪除仍由 LiveAccess.validate 預檢拒絕。

世界組 root 下保存 `merge-state.bin` 與選擇增量 `merge-state.bin.updates`；各 repo 保存 `MERGE_HEAD`。候選與原世界以 operation refs pin 住。候選有獨立 snapshot UUID，避免污染正式群組與舊歷史配對；成功提交才使用全組共用新 UUID。基底為 bounded canonical NBT／zstd、版本 1，基底與增量各上限 32 MiB；讀取必須重播增量。APPLYING／PARTIAL 在既有 apply journal，衝突選擇在 merge state，兩者分開。

### 中性 tree 引擎與報告（Hub）

`MergeBases.best/unique(RefStore, ours, theirs)`；`MergeEngine(ObjectStore, DimensionId, baseTree, oursTree, theirsTree, oursAuthors, theirsAuthors).merge(k)` 產生 result tree 與 MergeReport。`MergeEngine.select`、`preview`、`updateShapes` 可在無 LiveWorld 的 Hub 使用。

Hub 必須先處理 snapshot 配對、DataVersion／DataPacks、`IgnoreRuleMerge.merge` 與 `TreeFilter.filter`，再呼叫 tree 引擎；引擎本身不讀世界、不搬 refs、不建 PR。Hub 的 pending choice／權限／API 尚待其任務實作。`RefStore.createCommit(tree, List<parents>, metadata, trailers)` 新 overload 向後相容，JGitStore 已支援多 parent。

| 報告欄位 | 用途 |
|---|---|
| automaticallyMergedSections | 相對 base 任一側有變動、且沒有方塊衝突的 section 數（依路徑去重）。 |
| regions | id、dimension、bounds、blockCount、oursAuthors／theirsAuthors、redstone、choice、resolved、atoms。metadata 無空間位置時 bounds=null；作者為 tip author＋contributions 摘要，非逐格 blame。 |
| ruleDifferences | 各維度完整 base／ours／theirs／merged 規則文字；CLI 顯示有色行差異。 |
| updateShapes | 每維度世界座標 Cell 清單（交界提示，不自動處理）；切換僅重算受影響 atoms 與六鄰居，continue 完整重算。 |
| warnings | 紅石「建議測試」等警示；交界提示由 updateShapes 與 CLI 呈現（不自動處理，#46）。 |

`Region.atoms` 是精確修改集合，包含 BLOCK／BIOME／ENTITY／TICKS／STRUCTURES／METADATA／FILE；不能以 bounds 填滿覆蓋。同格 BE 與方塊一起切換；設定使用不可拆字串的 NBT key path。實體兩邊都改同 UUID 即衝突，連結果相同也保守衝突。

### updateShape 與 protocol（Paper／Fabric）

**使用者決定（2026-10-02）：套用合併結果時不重算鄰居形狀，一律維持快照中儲存的方塊狀態。** 離線與線上套用（合併、切換區域）都不得觸發 updateShape 或鄰居更新，套用後方塊 state 要與快照逐格相同。報告的 updateShapes 只是交界處的提示清單（CLI／GUI 可顯示給使用者檢查），平台不自動處理；有紅石的區域提示建議測試電路。

`org.worldgit.protocol.MergeProtocol` 是獨立可選訊息：`worldgit:conflicts`／`worldgit:conflict_preview`，envelope v1；只有支援 `merge-regions-v1` 的 peer 才發送。該 capability **尚未放入預設握手**，等平台實作後自行宣告。既有 v2 Protocol sealed messages／diff／status／clear 不變。

`regions(previewId, dimension, regions)` 編碼區域 id、bounds、格數、choice、resolved、redstone；`preview(previewId, dimension, regionId, choice, cells)` 編碼方塊 state 與整份 BE。使用 encode／decode／Assembler 組合 Part 分片，完成後回傳 Completed：每包 ≤28,000 bytes，批次 ≤8 MiB／8192 parts／100,000 entries，30 秒逾時，完整批次才發布，clear floor 防止晚到資料回來。正式 wire 格式與限制見 [protocol README](../protocol/README.md)。Fabric 只負責 overlay／UI，實際世界切換由平台調用 coordinator。

## Hub

日期：2026-10-02。範圍：唯讀合併預覽與衝突區域檢視，不寫 repo、不建 merge commit（Phase 4）。決定 #50、#51。

- **API**：`GET /api/v1/worlds/{owner}/{world}/merge-preview?ours=&theirs=`（亦接受 `/merge-preview/ours...theirs`）回傳 MergeReport：`fingerprint`、`canMerge`、`zeroIntervention`、`automaticallyMergedSections`、`regions`（id、維度、bounds、blockCount、雙方作者、redstone、boundaryHints、kinds）、`dimensions`（各維度 base／ours／theirs、狀態、bounds）、`ruleDifferences`、`problems`、`warnings`。`/view/{chunks|diff|summary}` 以 `fingerprint`、`dim`、`view=auto|ours|theirs|base|selected`、`choices=<id>:<ours|theirs|base>,…` 與視窗參數回傳依選擇的結果（沿用 chunk／diff wire；summary 含計數與 updateShapes）。fingerprint 與目前 tip 不符回 400。
- **實作**：`MergePreviewService` 重用 core `MergeBases`、`IgnoreRuleMerge`、`TreeFilter`、`MergeEngine.merge/select`；`MemoryObjects` 疊在唯讀 repo 上，候選與選擇結果只在記憶體。選擇保留 core 的精確 atoms 切換，區域包圍盒內未衝突的自動合併不被覆蓋。
- **前端**：`/{owner}/{world}/merge-preview/ours...theirs`（`pages/merge.ts`、`merge.ts`）：區域清單（維度篩選、依編號／格數／紅石排序）、點選區域鏡頭移動、五種預覽、每區域選擇、摘要、規則差異；分支頁與比較頁有「預覽合併」入口。
- **#46**：預覽與選擇結果逐格等於快照中的 state（驗收以 fence 的完整 `[east=…]` state 比對 ours／theirs，base 為 air）；`updateShapes` 只顯示座標。
- **驗收**：`MergePreviewTest`（6，零衝突／缺維度保留 ours、區域與 core 報告一致、逐格 state、多維度、DataVersion／規則／DataPacks 原因、授權 404 與非法選擇 400、快取上限與 tip 變動失效）＋`MergePreviewBudgetTest`（413、不留快取）；前端 vitest 26 通過。`hub/scripts/phase3-acceptance.sh`（port 18096，flock）以正式 CSP 的 Playwright 驗證：6 區域、區域鏡頭移動、ours／theirs／base 逐格 state、選擇後結果預覽（區域外 obsidian 保留）、URL 重載保留選擇、過期 tips 作廢、維度篩選、DataVersion 前提頁、分支頁入口、離頁釋放 viewer、匿名 404；0 CSP 違規、0 console error、0 失敗回應。截圖 `hub/docs/screenshots/phase3/`（4 張，約 645 KB）。
- **測試修正**：Hub 的 auth throttle（30 次／60 秒）會讓夾具連續 push 回 429，測試把 `worldgit.hub.auth.attempts` 調高；Hub 拒絕非快進 push，快取失效測試改用快進移動分支。
- **限制**：選擇不落地、不產生 commit；歷史未追蹤內容無法重新取回；作者為來源提交摘要；criss-cross 多 base 依 #43 拒絕。大型世界（上千區域）只有預算上限驗證，未做規模量測。

## 驗收與量測

以下測試不修改 baseline／原始 Paper 目錄；伺服器在 finally 停止，世界複本用完刪除。Gradle 與量測／伺服器腳本依規定使用 `.work/bench.lock`，單 worker、JDK 21；Paper 26.2 用 JDK 25。

### 單元與完整 baseline

- MergeEngineTest：短路、格級、BE 原子、UUID 移動／一刪一改／相同改動／新位置歸入 bbox、biome、world-meta key、k 與多格結構、紅石、跨 chunk shape、規則、ticks／structures、無共同歷史與 criss-cross。
- WorldMergeTest：持久化、切換／manual／abort／continue、PARTIAL 注入、規則 sidecar／重新納入後 abort、DataVersion／dirty 拒絕、patch、缺少維度歷史、未變動維度、只改 Nether、resolve all 的多維度驗證、候選 pin 與舊 snapshot fallback。
- CLI 與 protocol：JSON／dry-run／狀態／resolve／abort／continue；多包 BE 預覽、未知版本、重複／截斷／clear／上限。
- `Phase3LocalIntegrationTest` 用 `.work/worlds/{1.21.11,26.2}/baseline` 的完整複本（不存在則 skip）。兩版皆 PASS：不同位置 BE／UUID／跨 chunk 零衝突與兩 parent、同位置門／柵欄／紅石 4 格同區、三種選擇 verify=0、abort 回復、乾淨與衝突 patch。此次 1 個跨兩版測試耗時 **781.829 秒**，0 failure／0 skip。
- 最終 `./gradlew build :cli:acceptanceToolsJar` **BUILD SUCCESSFUL，2 分 19 秒**（78 tasks，24 executed／54 up-to-date），log：`.work/p3-build-final3.log`。完整 suite 共 **174 tests，0 failure／0 error／0 skip**：core 74、CLI 3、protocol 6、platform-api 8、Fabric logic 32、Paper common 19、Hub 29、i18n 3；本次新增 core 24／CLI 1／protocol 3。使用 `flock .work/bench.lock env JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 GRADLE_USER_HOME=.work/gradle-home ./gradlew --no-daemon --configure-on-demand --max-workers=1 build :cli:acceptanceToolsJar`。

### 真 Paper（127.0.0.1、offline、25691／25692）

可重跑 `python3 scripts/verify-phase3.py --results-dir .work/<新的結果目錄>`，腳本自行持鎖、凍結 jar、複製 baseline／Paper 全目錄、建立 441 chunk 控制地形；完整未改地形的 baseline 驗收另由上面的 integrationTest 負責。此測試不部署 Phase 3 插件。

結果檔 `.work/phase3-core-final/results.json`、各版 `*-partial.json`、伺服器 log 與 CLI log 保存證據。

| 項目 | Paper 1.21.11 | Paper 26.2 |
|---|---|---|
| 零衝突 BE／UUID／跨 chunk 合併、原始世界未改 | PASS | PASS |
| 門上下半＋柵欄＋紅石，1 區／4 格、bbox (0,224,2)..(2,225,2) | PASS | PASS |
| ours／theirs／base verify=0、abort 世界與 HEAD 回復 | PASS | PASS |
| revert／cherry-pick 乾淨與衝突各一例 | PASS | PASS |
| 合併世界啟動／save，錯誤 log | 0 | 0 |

兩版結果相同：兩個來源交界的柵欄座標 (15,80,0)、(16,80,2) 出現在 updateShapes（共 2 格）。合併後與單純載入／forceload／save 後，east／west 仍 false，verify 仍 0；將相鄰石頭 air→stone 觸發 vanilla 鄰居更新後，分別 east=true／west=true。**不能依賴載入自動修正**。兩版 clean merge 各自動整合 6 個 section，箱子 diamond count 分別 4／9，兩個 UUID 各 1 份；各維度共用新 snapshot UUID、主世界兩個 parent。所有伺服器已停止，複本已刪除。

### 耗時／記憶體

控制地形取 26.2 baseline 複本，1000 chunk；A／B 各改每 chunk 一格，位於相同 section 的不同位置。另一例在相距一 chunk 的 100 個位置兩邊同改，產生 100 區。計時採 noCommit=true 的完整 coordinator（capture／預檢／merge-base／引擎／持久化／apply／verify），不含準備分支與最終提交；單次觀察，JDK 21 `-Xmx1g`，RSS 為 `/usr/bin/time` 程序峰值。

| 場景 | 合併秒數 | 程序 wall 秒 | 最大 RSS | 結果 |
|---|---:|---:|---:|---|
| A／B 各改 1000 chunk，零衝突 | 45.619 | 45.74 | 331,648 KiB（323.9 MiB） | 0 區、1000 自動 section |
| 100 個衝突區域 | 18.399 | 18.53 | 373,648 KiB（364.9 MiB） | 100 區、0 自動 section |

第二例各分支只改 100 個 chunk（世界仍有 1000 chunk），不是與第一例等量修改；勿把兩個時間相除當作衝突額外成本。量測 log 為 `bench-0.log/.time`、`bench-100.log/.time`，本輪完整 `success=true`，世界與伺服器中間複本均已刪除；保留結果／jar／log 約 39 MiB。實機與量測的凍結 jar 早於最後的「實體移入 bbox」修正；這些場景沒有移動衝突實體，該修正另由最終單元回歸驗證。

## 失敗、修正與設計差異

- 早期候選提交以同一 UUID 重複 pin，出現 JGit LOCK_FAILURE；相同 ref 值設為冪等，並讓候選使用獨立 UUID，正式群組只 pin 最終提交。加入移除 group refs 後 legacy fallback 回歸。
- 完整 baseline 測試及第一輪 Paper 曾把驗證用 base／A 分支當作操作分支推進，造成驗證端點變動；Paper 的 clean cherry-pick 因 A 已含 B 的 UUID 成為正確的同 UUID 衝突。改保存固定 base 與 A-original，再重跑；失敗輪保留於 `.work/phase3-core-r1`，不計為通過。
- 補測發現 resolve all 涉及兩個維度時先前只驗證第一維度，已修正為驗證所有準備維度，寫入第二維度失敗的注入測試須回 PARTIAL。
- 收尾新增 L 形區域測試，重現 UUID 舊位置在外、新位置在 bbox 內但超過 k 時被拆成兩區；已改為檢查 UUID 的所有位置，再與方塊區域綁定。
- Nether-only 回歸初次建了全新 chunk；Phase 2 switch 保留它為 untracked，合併依乾淨前提拒絕。改用 baseline 既有 chunk，仍保留含 untracked 的拒絕規則。
- 中途取消過尚未完成的整合輪與 `.work/phase3-core-r2` 腳本，以修正 fixture 並改用最終凍結的 jar；不以取消結果聲稱驗收成功。取消留下的世界複本另行清除。
- criss-cross 多 base、merge commit 的 revert／cherry-pick（尚無 mainline）、不可安全刪除 world-meta 的 root revert 明確拒絕。不採 fast-forward；普通 merge 建新快照，相同 parent 去重，不偽造兩個相同 parent。
- 鄰居形狀依 #46 維持儲存的 state，只回報提示；實測確認伺服器載入時也不會改動（與決定一致）。作者為 commit 身分摘要，Mod 自訂多格結構／真正逐格 blame 尚未完成。規則重新納入只可取回活世界 ours，無法復原歷史原先沒保存的 base／theirs。

## 未完成事項與限制

- Paper／Folia 的線上合併、區域工具／GUI、Fabric UI／疊圖／單人命令及 Hub 唯讀衝突檢視已由後續平台任務完成；玩家在線、Folia 與真客戶端驗收見下方各章。Hub 寫入合併與 PR 留待 Phase 4。
- 多維度參與 repo 與實體世界目錄須先存在；不自動建立來源新增的維度。跨維度 UUID 移動協調未新增（本次維度內 UUID 全域比較沿用 Phase 1 語意）。
- 有 area ignore 時 ticks／structures 沿用 Phase 2 保守限制；未追蹤 biome sample 不能當空 biome 寫入。跨 DataVersion／DataPacks 的可靠遷移仍未完成。
- MERGING 不提供並行多工作區／remote 鎖；全組 refs CAS 與回滾沿用 Phase 2，程序在多 repo refs 更新間強制中止仍需操作員恢復原 HEAD 後 abort。不是跨 repo 資料庫交易。
- 狀態超過 32 MiB 會明確拒絕保存；大型實體預覽超過 8 MiB 要縮小批次。保留候選 refs／完成報告，尚未實作回收命令或任意 Mod 語意 registry。

完整語意見 [06](06-diff-merge.md)；儲存模型見 [02](02-data-model.md)；Phase 2 與合併的互斥見 [05](05-switch-restore.md)；平台接合見 [08](08-architecture.md) 與 [core README](../core/README.md)。

## Fabric（任務 F3，2026-10-02）

單人世界的合併、衝突清單 UI 與疊圖預覽。決定見 [09 #55～#61](09-roadmap-open-questions.md)；使用方式見 [fabric README](../fabric/README.md)。

### 完成項目

- **指令（單人整合伺服器）**：`/wg merge <分支> [--no-commit] [--strategy-option ours|theirs] [--distance k]`、`merge --abort|--continue`、`resolve <id|all> [--ours|--theirs|--base|--manual]`、`revert`、`cherry-pick`、`conflicts [--show|--teleport id]`；`status` 在 MERGING 顯示剩餘區域；`commit -m` 在 MERGING 等同 continue。全部走 `WorldOperations.live` 與 Phase 2 的 freeze／批次套用／verify barrier，**呼叫端不再套一次 plan**；乾淨合併（0 區域）在同一指令內自動 `continueMerge` 成兩 parent 的 merge commit。套用沿用 Phase 2 的 section 直接替換，不呼叫 `updateShape`／鄰居更新（#46）。
- **衝突清單畫面**：`G` 鍵或 `/wg conflicts`。分頁列出區域（`!` 未解決／`✓` 已解決／`>` 選取）、維度與座標、格數、ours／theirs 作者、狀態與目前選擇、紅石警示、交界提示數（滑過按鈕列出前 20 格，僅供檢查）、傳送（單人直接 `teleportTo`；連 Paper 送 `execute in <dim> run tp`）。按鈕分三組：Ghost（疊圖）、Set blocks（原地切換，不標解決）、Resolve（切換＋標解決；manual 取目前世界）。畫面不暫停整合伺服器。
- **疊圖**：客戶端以既有 `PreviewScene` 鬼影管線畫選取區域的 ours／theirs／base（紫色外框＋半透明模型，不改世界）；區域外框依 06 §1.1 常駐（未解決紫、已解決暗灰、全部解決後清除）。
- **連 Paper**：握手宣告 `merge-regions-v1`；`ClientConflicts` 以每維度 `Assembler` 收 `worldgit:conflicts`／`worldgit:conflict_preview`（分包完整才發布、clear floor、選擇改變後丟棄晚到的另一候選）；Fabric 伺服器端 `sendConflicts` 與 Paper 使用同一份 `MergeProtocol` 編碼。`MergeProtocol` 只做向後相容擴充（F3-5）。
- i18n：`fabric/shared/.../assets/worldgit/lang/{en_us,zh_tw}.json`（畫面、按鍵與按鍵分類）與 `i18n` 模組 `fabric.merge.*`／`fabric.help.phase3`（聊天訊息）。按鍵為原版 `KeyMapping`，可在「按鍵設定」改。

### 驗收（真正的 Minecraft 客戶端、Xvfb＋llvmpipe、單人世界）

腳本：`WG_PHASE3=1 ALSOFT_DRIVERS=null fabric/tools/run-gametest.sh <1.21.11|26.2> --record`（自行取得 bench.lock）；測試 `fabric/gametest/.../Phase3ClientGameTest.java`，離線驗證 `fabric/tools/record-phase3.py`。證據在 `.work/fabric-acceptance/phase3-<版本>-*/`（result.json、client.log、CLI 輸出、各階段世界／repo 複本、截圖）。

| 項目 | 1.21.11 | 26.2 |
|---|---|---|
| 不同位置（同 section 不同格、不同 chunk）merge：零衝突、自動完成、兩 parent、`verify` 0 | PASS | PASS |
| 同位置衝突：門（上下半）、柵欄、紅石線／中繼器 → 3 個區域（2／1／1 格），清單與 `wgit conflicts`（取 MERGING 中途世界複本）id／格數／包圍盒／紅石旗標一致 | PASS | PASS |
| 紅石警示只在紅石區域；區域含 ours／theirs 作者 | PASS | PASS |
| 疊圖 ours／theirs／base：客戶端收到的格子位置與 state 與對應分支快照逐格相同（3 區域 x 3 版本），每次預覽前後整個測試範圍（46x4x17 格）逐格不變 | PASS | PASS |
| 以 UI 路徑（客戶端命令）逐區切換 theirs→base→ours：區域原子 = 快照，區域外保持不變 | PASS | PASS |
| **柵欄連接 state 不被改動**：切到 theirs 後 (14,-60,10) 為 nether_brick_fence、(15,-60,10) 仍是 `oak_fence[west=true]`（vanilla 會改成 false） | PASS | PASS |
| UI 解決（門 theirs、柵欄 theirs、紅石 manual）後世界與預期逐格相同；`merge --continue` 兩 parent、`verify` 0、`wgit conflicts` 為空 | PASS | PASS |
| 解決一部分後 `merge --abort`：世界逐格回到合併前（含自動合併進來的格）、HEAD 不變、MERGING 清除、客戶端清單與外框清空 | PASS | PASS |

兩版各 1 次完整執行，耗時 11–17 分鐘（llvmpipe、與其他任務共用 3 核）。其中 manual 區域的測試動作 `setblock` 會讓原版更新相鄰紅石線，這是玩家自己的編輯，不是 WorldGit 造成；驗收已把該格以編輯後的實際值為準。

### 協定與相容性測試

`MergeClientTest`（fabric logic，無 Minecraft 相依）以模擬 Paper 伺服器的封包驗證：作者與交界提示欄位、>28,000 bytes 的 BE 預覽反序分包完整才發布、選擇改變後晚到的另一候選被丟棄、clear 之後各維度晚到封包不復活、舊 v1 body（無作者／提示欄位）可讀、舊伺服器不宣告能力時不送合併封包、預設 `Protocol.CAPABILITIES` 不含 `merge-regions-v1`、指令參數驗證（互斥旗標、`--distance` 範圍）。`ClientLogicTest` 改為檢查握手為預設能力加 `merge-regions-v1`。

### 限制與未完成

- 真 Paper／Folia 對接與畫面證據已追加獨立驗收流程，見下方「Fabric ↔ Paper 實機對接」。
- 本節初輪僅支援單人；追加任務已開放專用 Fabric 伺服器寫入，見下方「Fabric 專用伺服器」。Paper／Folia 已新增 `conflict-select`，支援者可在遠端 UI 使用 Set blocks；舊版依能力公告停用並顯示原因。
- 作者欄位是 commit 身分（單人世界自動／手動提交都是 `WorldGit Server`），不是逐格 blame。
- 疊圖沿用 Phase 2 的鬼影管線：固定光照、無透明面排序、不畫 block entity renderer；超大區域依既有 LOD 退成外框。未做 Sodium／Iris／硬體 GPU 驗收，200 區域的傳輸、分頁與重連納入追加驗收，但沒有量測大量區域的真 GPU 效能；清單分頁為每頁依視窗高度。
- 交界提示（updateShapes）以區域包圍盒外擴 1 格歸屬；只在選擇會改變 theirs 內容時才非空，預設 ours 時為 0。僅供檢查，不自動處理（#46）。
- 清單畫面為原版元件的簡單分頁表，沒有拖曳、搜尋或 3D 內縮圖；傳送一律落在區域最小 x／z、最高 y+2。

## Paper／Folia

日期：2026-10-02。範圍：Paper／Folia 插件的遊戲內合併流程（paper/）。決定 #52～#54。Codex 在實作中途遇到用量限制，由 Sonnet 5.5 接手完成驗收腳本修正、四平台驗收與文件。

### 完成項目

- `/wg merge <branch|rev>`、`--continue`、`--abort`、`/wg resolve <#|all> ours|theirs|base|manual`、`/wg conflicts`（GUI／主控台清單、`preview <#> <choice>`）、`/wg tool`、`/wg revert`、`/wg cherry-pick`；`/wg commit` 在 MERGING 時等同 `commitMerge`；`/wg status` 顯示 MERGING。權限節點 `worldgit.command.{merge,resolve,conflicts,conflict-preview,tool,revert,cherry-pick}`（含於 `worldgit.admin`）。
- `PaperOperations(merge=true)` 包裝 core `WorldOperations.live`：capture 走 `PaperLiveWorld.flush`，驗證用 Phase 2 的 `validateOnline`，套用沿用 owner 排程、player protection（10 秒餘韻）與實體 UUID 移除→生成 barrier。core 僅新增向後相容的 `LiveAccess.beforeComplete()` 預設方法（取消／插件關閉時在發布 HEAD 或完成 journal 前中止）。
- `MergeUi`：讀取持久化 `merge-state.bin` 後驅動 bossbar（紫色，「合併中：剩 N 個衝突」，進度＝已解決比例）、區域外框（`DisplayFallback.showRegions`，12 條邊的發光 BlockDisplay，已解決變灰，對該維度有 `worldgit.command.conflicts` 的玩家）、動作列提示、合併工具（PDC 標記的指南針）與 54 格 GUI（每頁 45 區域、翻頁、傳送用 `teleportAsync`）。插件啟動時 `refresh()` 重讀狀態，所以重啟後一切恢復；插件停用時清除所有外框／bossbar。
- `FabricLink`：握手宣告 `merge-regions-v1`（及 `revision-preview`），推送 `worldgit:conflicts`（狀態變更才重送）與 `worldgit:conflict_preview`；protocol 的 `RegionInfo` 新增可選 `oursAuthors`／`theirsAuthors`、`Completed` 新增 `updateShapes`（舊 v1 body 缺欄位讀為空；Fabric 任務同樣使用）。
- MERGING 期間：`doCommit`／`switchTo`／`resetHard`／stash 等一律拒絕（`requireNotMerging`），定時／登出自動 commit 與關閉時 commit 略過，Folia 離線關閉 commit 也略過。
- 驗收用除錯入口：`/wg debug merge-tool <玩家> cycle|resolve`（以真正的 `PlayerInteractEvent` 走完整物品／權限／core 流程）、`merge-gui`、`fill … <localX>`；`wgbot.js` 新增 `window`／`click`（真的 inventory click packet）與 `WG_BOT_DUMP`（記錄 conflicts／preview 原始 payload 供解碼）。

### 驗收（`acceptance.py <paper|folia> <1.21.11|26.2> phase3`，port 25701–25704）

四平台全部通過，各 47 個檢查，0 failure、server log 無 ERROR／Exception，最後離線 `wgit verify HEAD` 皆 COMPLETE（0 差異）、實體無重複。證據：`.work/paper-phase3/<平台>-<版本>-*/results.json`、`wire.log`、`cli.log`（console log 在 `.work/paper-delivery/logs`）。

| 項目 | paper 1.21.11 | paper 26.2 | folia 1.21.11 | folia 26.2 |
|---|---|---|---|---|
| 兩 bot（含 BE、牛、跨 chunk）不同位置建築 → `/wg merge` 零介入、兩 parent、bot 與伺服器逐格 hash 相同、離線 verify=0 | PASS | PASS | PASS | PASS |
| 同位置門＋柵欄＋紅石（repeater）衝突 → 1 區、4 格、bbox (15,64,2)..(17,65,2)，與 CLI `wgit conflicts` 的 id／bbox／格數／紅石／atoms 一致 | PASS | PASS | PASS | PASS |
| `/wg status` 顯示 MERGING；MERGING 中 `switch --force`、未解決的 commit 被擋 | PASS | PASS | PASS | PASS |
| 重啟伺服器後 MERGING 恢復（兩位授權玩家各看到 12 個外框 display） | PASS | PASS | PASS | PASS |
| 合併工具（Bukkit 右鍵事件）ours→theirs→base→ours 循環，每次切換後 bot 與伺服器的 section hash 都等於對應快照（柵欄連接 state 不變，#46） | PASS | PASS | PASS | PASS |
| 區域內生存模式玩家切換：無傷害、FALL／SUFFOCATION／DROWNING 事件被取消 | PASS | PASS | PASS | PASS |
| GUI 開啟（真 bot）、點擊 slot 傳送（teleportAsync）到區域 | PASS | PASS | PASS | PASS |
| Fabric 協作：bot 宣告 merge-regions-v1，真實收到 conflicts／preview payload，解碼得 4 格預覽 | PASS | PASS | PASS | PASS |
| `merge --abort`（含丟棄 MERGING 中手動放的方塊）→ HEAD 回復、世界逐格等於合併前、離線 verify=0 | PASS | PASS | PASS | PASS |
| 全部 resolve → commit（兩 parent）；resolve all manual（以世界現況為準）→ `--continue` | PASS | PASS | PASS | PASS |
| revert／cherry-pick 各乾淨一例（單 parent commit）、各衝突一例（進 MERGING，abort 還原） | PASS | PASS | PASS | PASS |

### 量測（單次，3 核心與另外兩個任務共用，bot 在線，`-Xmx2G`）

| 項目 | paper 1.21.11 | paper 26.2 | folia 1.21.11 | folia 26.2 |
|---|---:|---:|---:|---:|
| 兩分支各改 1,000 chunk 的線上 merge（零衝突，含 commit） | 119.5 s | 117.8 s | 91.7 s | 92.0 s |
| merge 期間 TPS 估計／p99 tick 間隔／最大間隔 | 19.98／56.0／457 ms | 19.98／52.3／406 ms | 19.97／57.5／351 ms | 19.95／56.7／412 ms |
| 一次區域切換（右鍵事件→套用完成→狀態持久化；3 次） | 26.3／23.6／23.6 s | 24.7／21.0／20.6 s | 26.8／22.6／22.6 s | 25.1／19.8／20.7 s |

切換只涉及 2 個 section，但延遲偏高：事件後約 14 s 才開始套用、套用後還要約 10 s 驗證與持久化。推測是 core 協調流程每次對整個世界組做 capture／preflight／驗證與 flush barrier（與 Phase 2 單區域 `switch` 同量級），再加上與其他任務共用 CPU；未做 profile，屬已知限制，見下。

### 設計說明與修正

- 驗收的場景修正：fixture 地表為 y=63，起初把門／柵欄放在懸空處；紅石線在 `setblock` 時會被鄰居更新重算 power（與 base 相同而不構成衝突），所以改用 repeater 的 delay 當紅石 state；`setblock` 換門前先清成空氣，避免上下半互相更新。這些只影響測試夾具，與 #46 無關（插件／core 套用路徑本身不觸發鄰居更新）。
- 玩家保護在 `applyAll` 結束後再延 10 秒；驗收在套用日誌出現後立即檢查事件被取消，而不是在後續較慢的驗證完成之後（那時已超過餘韻）。
- 合併工具用「指南針＋PDC」，不是 Folia 專屬物品；驗收以真正的 `PlayerInteractEvent` 模擬右鍵（任務允許的方式），事件仍走物品 PDC、權限、鎖與 core barrier。
- 未對 Folia 以外的 Bukkit 事件版本差異再做額外相容層；GUI／工具都在玩家的 entity scheduler 執行。

### 未完成事項與限制

- 初輪一次區域切換約 20–27 s（見上）；追加任務已新增局部 LiveAccess 並修正，後續量測見「區域切換延遲」。
- 工具對「手動」(manual) 區域沒有循環選項（右鍵只在 ours／theirs／base），manual 只能用 `/wg resolve <#> manual`；Shift+右鍵標記的是目前所見版本。
- 無逐格 blame；作者是來源 commit 身分摘要。外框最多受 `show.display-max-entities` 限制（超過的區域省略並不顯示）。
- 此處初輪只驗證 bot payload；追加的真 Fabric 客戶端與截圖見「Fabric ↔ Paper 實機對接」。
- Phase 3 初輪因 fabric／hub 由並行任務修改，僅跑 `:core:test :protocol:test :i18n:test :paper:common:test :paper:plugin:build` 綠燈。追加任務已完成完整 build，194 個單元測試全綠，見下方「區域切換延遲」。

## 區域切換延遲

追加任務（2026-10-02，接續 a12d648）：先量測再修正，沒有 commit／push，沒有修改 experiments/。所有重負載持有同一個 bench.lock；驗收腳本自行取鎖，未再套外層 flock。下列世界為副本、受控平坦地形與 frozen tick；不代表大型自然世界 TPS。

### 修改前根因量測

Paper 1.21.11 使用 Phase 3 相同 4 格、跨兩個 chunk 的門／柵欄／repeater fixture，6 次 console resolve 的 ours／theirs／base 切換，中位數 **21.497 s**、最大 **25.594 s**。證據：`.work/region-latency/paper-1.21.11-baseline-1790929812/results.json` 與該 JSON 的 console log 路徑。過去四平台的 3 次數字仍保留在本文件「Paper／Folia」章節，未以推測當成 profile 結果。

| Paper 1.21.11 階段（6 次中位數） | 修改前 | 修改後完整驗收 |
|---|---:|---:|
| lock（全組→局部） | 144.3 ms | 41.7 ms |
| 來源 flush（capture 內含） | 129.1 ms | 101.9 ms |
| capture（包含驗證內的第二次 capture） | 20,393.7 ms | 247.4 ms |
| verify（內含 capture；不能再與前項相加） | 9,616.1 ms | 128.6 ms |
| plan／updateShapes | 32.8 ms | 53.7 ms |
| apply／光照／存檔 barrier | 291.2 ms | 283.1 ms |
| owner 套用加總／光照等待加總（前項內含） | 未細分 | 4.0／1.6 ms |
| IO queue 等待（跨 flush 加總） | 未細分 | 4.4 ms |
| journal 所有小檔寫入 | 9.0 ms | 8.7 ms |
| merge-state／MERGE_HEAD→增量 | 7.9 ms | 2.4 ms |
| preview 清除通知排程 | 未細分 | 0.1 ms |

capture 的數字包含來源 flush；flush 本身只有約 0.13 s。核心原因是 `selectRegion` 在確認某維度有選中區域之前就 capture 所有維度，再由共用 execute 的 prepare 做受選維度的完整驗證。這一輪無其他重負載時仍可重現約 21–26 s，證據足以定位，因此未另取 JFR／async-profiler。狀態檔、計畫、真正套用都不是主要瓶頸。

Fabric 1.21.11 使用真正單人整合伺服器／Xvfb＋llvmpipe、同 Phase 3 的門區域，6 次 UI 指令切換中位數 **20.963 s**、最大 **23.379 s**；`.work/region-latency/fabric-1.21.11-baseline.json`、`.work/region-fabric-baseline.log` 保存逐次耗時及分段。共用 core 的完整 capture 同樣占主要時間。Fabric 26.2 以 a12d648 的隔離來源複本只加入相同計時，6 次切換中位數 **21.169 s**、最大 **21.865 s**；證據 `.work/region-latency/fabric-26.2-baseline.json`、`.work/region-fabric-26.2-baseline.log`。隔離副本已刪除，未修改正式工作樹的舊版程式。

### 修正與正確性

- 一般區域不再 world scan／workingTree，只以局部來源 flush／capture 精確 atoms 所在 chunk；沒有選擇區域的維度不 capture。UUID 區域加入歷史端點及其巢狀乘客 UUID 與目前全組實體 storage 的真正位置，Paper 在鎖內刷新 live census（不用背景舊快照），再跨維度先移除後生成。metadata／非 chunk FILE 保守沿用完整流程。
- 寫入方塊／BE 僅使用 atoms mask，包圍盒不作 replace 範圍；同 section 的其他 BE 不重建。套用後比對完整受影響 chunk 的追蹤內容，實體容許距離為 0；區域外同 chunk 的方塊／BE／其他實體也須一致。區域外其他 chunk 不在每次選擇重讀。
- Paper／Folia 在 owner 呼叫 Moonrise `NewChunkHolder.save(false)` 保存指定 terrain／entity／POI；保留 Starlight 回呼與 IO barrier。Fabric 以 vanilla ChunkMap serializer、entity store、POI flush 排入指定 chunk，等待三種 storage 的 IO queue；不直接寫使用中的 Anvil 檔。
- `merge-state.bin` 為基底，`merge-state.bin.updates` 只 append result commit、choice／resolved 與 hints 差異；長度／CRC32C／operation UUID，force 後 chunk journal 才 COMPLETE。最後一筆截斷可重播上一個完整狀態；APPLYING／PARTIAL 擋後續寫入，完整 abort 恢復。不重寫所有 region atoms 或 MERGE_HEAD；只標 resolved 也有 journal。基底與 WAL 各限 32 MiB；metadata 等完整回退路徑仍可更新基底 checkpoint，WAL 清理中斷時由 APPLYING journal 擋住後續寫入。
- updateShapes 只重算變更 atoms 與六鄰居，continue 完整重算完成報告。保持 #46 的來源 state，不觸發 updateShape／鄰居更新。
- 一般方塊區域只鎖本次 chunk 寫入；保留短暫 vanilla tick freeze、容器／指令與第三方保守協調，以維持一致快照。UUID 定位採全組鎖。全組 merge 開始、abort、continue／commit 保留完整 capture／驗證；continue 新增發布 HEAD 前再次全組 capture，比對本次提交樹。

不變 chunk 的驗證由每次選擇移到完整作業邊界；MERGING 期間其他 chunk 的 manual 編輯仍以 continue 的活世界為權威，交界提示可能暫時落後，於 continue 更新。第三方忽略鎖的寫入不受保證。IO barrier 仍等待平台整個既有 queue，其他 IO 積壓會增加耗時；UUID storage 定位需掃描實體資料，這個特殊路徑不宣稱與世界大小無關。舊 reader 只讀 merge-state.bin 會看到舊選擇，CLI／平台須一起升級並保存基底＋WAL。

### 修改後驗收與數字

四個 Paper／Folia 與兩版 Fabric 的完整 Phase 3 驗收皆已通過，以下採最後修正版本。Paper 每組至少 6 次工具切換，另在 200 區域世界中以 resolve 切換一個區域 6 次；延遲為送事件／指令→協調器完成（包含狀態持久化與解除鎖），逐格 client／server／快照比較在計時外。Fabric 記錄 1–2 格的 UI 發出指令→future 完成，包含通知。Paper 通知以排程完成為準，清單／外框與網路接收由 owner 非同步處理；客戶端與伺服器內容皆另做逐格比對。受控 Paper 修改前為 console resolve、修改後完整驗收為工具事件；同口徑的 resolve 世界大小對照另列於下方。

| 平台 | 修改前中位數／最大（s） | 修改後小區域中位數／最大（s） | 200 區域單區中位數／最大（s） |
|---|---:|---:|---:|
| Paper 1.21.11 | 21.497／25.594（本輪 6 次） | 0.802／1.910 | 0.701／0.702 |
| Paper 26.2 | 21.0／24.7（舊輪 3 次） | 0.803／1.608 | 0.703／0.811 |
| Folia 1.21.11 | 22.6／26.8（舊輪 3 次） | 0.802／1.808 | 0.601／0.701 |
| Folia 26.2 | 20.7／25.1（舊輪 3 次） | 0.804／1.605 | 0.701／0.704 |
| Fabric 1.21.11 | 20.963／23.379（本輪 6 次，2 格門） | 0.853／0.907（同一門區域 5 次） | 未要求 |
| Fabric 26.2 | 21.169／21.865（本輪 6 次，2 格門） | 0.837／0.988（同一門區域 5 次） | 未要求 |

第一次局部重現（尚未作為完整驗收結果）Paper 1.21.11 為 1.002／1.103 s（中位數／最大、6 次）；同期 inclusive capture 約 243.3 ms、verify 123.4 ms、plan／hints 28.2 ms、apply／barrier 296.9 ms、journal 6.1 ms、增量狀態 2.5 ms。原始結果在 `.work/region-latency/paper-1.21.11-optimized-1790931285/results.json`。

世界大小對照使用同一支 `profile-region.py`、同樣 4 格／2 chunk 與 6 次 resolve：主世界 484 chunk 的中位數／最大 **1.001／1.001 s**，1,296 chunk 為 **1.002／1.103 s**；兩者僅局部 capture。較小世界證據 `.work/region-latency/paper-1.21.11-optimized-small-1790933287/results.json`。此腳本以 console cmd helper 計時，含約 0.3 s 輪詢等待，與完整驗收的事件計時口徑不同；只比較同腳本的世界大小對照。核心回歸另禁止局部來源呼叫全世界 scan，避免把兩個樣本當作任意規模的常數時間保證。

完整作業的成本：本輪 1,000 chunk 的線上 merge（含自動提交）為 Paper 149.4／151.9 s、Folia 126.8／116.6 s；舊輪為 119.5／117.8／91.7／92.0 s，舊輪與其他任務共用 CPU，僅供參考。新增提交前的第二次全組 capture 在本輪分別占約 17.0／17.2／13.7／10.7 s，完成報告的全部 updateShapes 也於 continue 重算。這些全組成本保留在完整作業邊界，小區域選擇走局部流程。

### 測試、失敗與產物

四個 Paper／Folia 各通過 74 個檢查、0 failure、server problem_lines=0，最終完整 verify=0、無重複實體；每組小區域與 200 區域世界各有 6 次樣本，200 區域世界總計 2,864 個已存 chunk，這個案例每區 1 格、單次處理 1 chunk；固定 4 格／2 chunk 的世界大小對照另列於上方。兩版 Fabric 真正客戶端 success=true，門／柵欄／紅石三區分別有 5／6／5 次 UI 完成樣本，表格採同一門區域的 5 次，其餘原始樣本也保留。core 範圍回歸禁止呼叫全世界 scan；量測只證明本次世界規模與 200 區域案例，沒有宣稱任意規模的常數時間。

最後版本證據：

- Paper 1.21.11：`.work/paper-phase3/paper-1.21.11-1790948771/results.json`。
- Paper 26.2：`.work/paper-phase3/paper-26.2-1790950349/results.json`。
- Folia 1.21.11：`.work/paper-phase3/folia-1.21.11-1790947305/results.json`。
- Folia 26.2：`.work/paper-phase3/folia-26.2-1790947171/results.json`。
- Fabric 1.21.11：`.work/fabric-acceptance/phase3-1.21.11-20261002-144521/result.json`。
- Fabric 26.2：`.work/fabric-acceptance/phase3-26.2-20261002-145247/result.json`。

彙整、完整精度與 jar 雜湊：`.work/region-latency/final-summary.json`。四平台 plugin SHA-256 為 `1960c2d4fc3e30fc2896932c300981a46cfc6538f1d68070e9b72c4f16822edf`。Fabric 的 Realms 驗證 401／llvmpipe Anisotropy 提示仍在 raw log，未當作客戶端遊戲測試通過的替代證據。

最後 UUID 審查發現 Paper locator 使用背景 census，剛移入新 chunk 的實體可能被漏掉；已改鎖內 fresh census。core 加入 base 沒有 UUID、其後手動移動的實體仍完整移除的回歸；巢狀乘客 UUID 也加入 locator／journal／verify 範圍，測試先拆離成為其他 chunk 的 root、再改騎另一載具，兩次選擇都與目標快照完整相同且保留未選中載具。Paper 的 census 測試明確驗證 cached 與 refreshed 的差異。四平台已以最後 jar 重跑通過，舊數字另外保留在證據。Fabric scoped flush 也補上 terrain 卸載後仍在記憶體內的 entity／POI：三種 storage 各自保存，不能以 terrain 已載入為共同前提；兩版實際客戶端已以此修正重驗通過。格式化器曾因預設 JDK 25 與舊 formatter 不相容失敗，已用 JDK 21 重跑成功。

審查時另修正 chunk 邊界的事件鎖：互動與桶子檢查實際目標、多格放置／肥料／爆炸檢查整個 footprint，活塞檢查來源／目的格；容器／發射器保守協調。Paper 新增外側玩家操作鎖內格、活塞跨界／回縮、批量變更與外側編輯允許的回歸，四平台已以最後 jar 重跑通過；之前結果另保留。

core 新增禁止全量來源／scan、只觸及受選維度／chunk、精確 mask、基底 bytes 不變與小型增量、增量保存失敗後 PARTIAL／abort、截斷 WAL＋APPLYING 恢復、UUID 手動移到其他 chunk 後選擇恢復、乘客拆離／改騎另一載具的範圍與完整驗證。既有跨維度 verify 故障注入仍須 PARTIAL。完整 build 已通過；最後 build 為 `BUILD SUCCESSFUL in 2m 12s`，`77 actionable tasks: 25 executed, 52 up-to-date`；log `.work/region-build-passenger-fixed.log`。完整單元 suite 194 tests、0 failure／error／skip（core 80、CLI 3、protocol 7、platform-api 8、Fabric logic 36、Paper common 21、Hub 36、i18n 3）。

第一輪 Paper 1.21.11 完整驗收在 CLI conflicts JSON 比對遇到 `JSONDecodeError: Extra data`：`JAVA_TOOL_OPTIONS` 的 JVM 提示在 stderr，被驗收 helper 接到 JSON 後。已改只解析 stdout，stderr 完整保存在 cli.log；該輪列為失敗，沒有算入通過。證據 `.work/paper-phase3/paper-1.21.11-1790932323/results.json`、`.work/region-latency/paper-json-failure.log`。開發過程的缺少 import、跨版 ChunkPos 方法名稱與 fixture 需要先 scan 的問題皆修正後重跑；不以失敗輪作成功證據。

重跑命令（腳本各自持鎖，不加外層 flock）：

```sh
JAVA_TOOL_OPTIONS=-Dworldgit.profile=true python3 paper/tools/acceptance.py <paper|folia> <1.21.11|26.2> phase3
WG_PHASE3=1 JAVA_TOOL_OPTIONS=-Dworldgit.profile=true ALSOFT_DRIVERS=null fabric/tools/run-gametest.sh <1.21.11|26.2> --record
flock .work/bench.lock env GRADLE_USER_HOME=.work/gradle-home ./gradlew --no-daemon --configure-on-demand --max-workers=1 build
```

`WGPROFILE` 為 inclusive 階段，verify 內含 capture，不可將全部欄位相加。Paper apply-owner／lighting 是 owner 作業及回呼等待的加總，與跨 owner 的 wall clock 不同；總延遲採 monotonic clock。

乘客範圍回歸的第一次 build 失敗（`.work/region-build-passenger-final.log`）：fixture 僅改 Health，被正規化忽略，沒有建立衝突，取第一個 region 時得到 `NoSuchElementException`。已改用 NoAI 實體的位置差異；失敗輪不列為通過。

收尾檢查：伺服器、bot、客戶端與 Xvfb 均已關閉，25701–25714 無監聽程序，bench.lock 已釋放。自本輪開始後新建／修改的中間產物上界為 882,103,924 bytes（0.822 GiB），低於 4 GB；計算包含本次覆寫的既有檔案，不包含工作前約 15 GB 的舊 `.work` 證據，詳見 `.work/region-latency/disk-usage.json`。本次要求的驗收與 build 均完成，特殊完整回退路徑與 IO 積壓的限制如上。

## Fabric ↔ Paper 實機對接

日期：2026-10-02，接續 a12d648／cf10727；決定 #66–#70。新增 `fabric/tools/accept-paper-phase3.py <paper|folia> <1.21.11|26.2>`（自行取 bench.lock），以 Xvfb／llvmpipe 執行真正 Fabric client GameTest，連線到真正 Paper／Folia。後端 port 25701–25704，客戶端入口 25711–25714 是只轉送 TCP bytes 的本機 relay，不產生或改寫 Minecraft 封包。jar 凍結在證據目錄，例外與 SIGTERM 也走 finally，結束後關閉 client／Xvfb／relay／server，確認兩個 port 關閉並刪除 server／world 副本。

### 實作與流程

- Paper／Folia `/wg conflict-select <#|all> ours|theirs|base|manual` 與 resolve 共用權限、解析、補全與 MiniMessage。以 `RepoService.regionOperation` 呼叫 `selectRegion(...,false,false)`，沿用局部 capture／verify、精確 atoms、IO barrier 與增量 WAL（#62–#65）；manual 保留活世界。選擇已解決區域會恢復 unresolved。沒有鄰居更新（#46）。
- hello 加可選 `conflict-select-v1`，Fabric 遠端 Set blocks 同時確認 merge／select 能力；UI 與執行入口都檢查。舊 Paper 缺少能力時停用，tooltip 用 MiniMessage 顯示原因，Ghost／Resolve 保持可用。預設 v2 能力與合併 envelope v1 不變。
- Fabric networking DISCONNECT 回呼只記錄 handler，在客戶端 tick 才清理 GPU 網格與能力／清單／assembler；JOIN 先清除舊狀態。以 handler 身分忽略晚到的舊連線事件，並偵測世界退出，避免背景執行緒直接修改渲染資源或清掉新連線。
- 真客戶端握手後，伺服器建立跨 chunk 的門／柵欄／repeater／箱子衝突：1 區、5 格，bounds (15,64,2)..(18,65,2)。客戶端解碼清單的 id／bounds／格數／choice／resolved／紅石／作者與 durable MergeState 一致。
- 真滑鼠點擊三種 Ghost，received cells 的座標、完整 state 與 canonical BE bytes 等於固定候選快照；兩個完整 section 的 8192 格 client／server hashes 在預覽前後相同。
- 點擊 Set blocks theirs：client／server 完整 section hash 等於 theirs；durable 與即時清單仍 unresolved。再點擊 Resolve ours，確認選擇與 resolved、客戶端清單及完整 section 同步更新。點擊傳送到區域，再以 client 命令 continue，durable／client 清單清空。
- 另以既存 chunk 的 200 個分散 atoms 建立合併；檢查完整清單與真正多包傳輸、分頁按鈕、斷線後能力與清單 reset、重新握手後同樣的 200 區域清單。全部 manual resolve，再 continue，清單清空並完成離線 verify。

fixture 取合成平坦世界的副本，使用範圍內既存 chunk，不探索隨機地形，因此沒有 Phase 1 的 vault BE 底噪。setblock 拆門／容器可能產生原版 item，腳本在建立每個候選快照前，透過 owner 的 debug fixture 入口清除掉落物並填入容器內容；不修改正式追蹤／正規化規則。清單與 BE 的唯讀候選對照工具為 `fabric/tools/InteropEvidence.java`；客戶端按鈕由共用 GameTest 操作，畫面入口以兩版 GameTestScreens 轉接。

### 結果與證據

真客戶端對接四組全部通過，各 **17 項檢查**，client／server exit=0、server problem=0，後端與入口 port 均已關閉，最後 `verify HEAD` 為 COMPLETE（0 差異）。四組使用同一份 plugin SHA-256：`a11466a9751507e5404ce588745d5c01caa5af5379fd41ba7693ae529b737f65`。

| Fabric client ↔ server | 真客戶端對接 | 結果證據 |
|---|---|---|
| paper-1.21.11 | PASS，17 項 | `.work/fabric-acceptance/pair-paper-1.21.11-20261002-180715/result.json` |
| paper-26.2 | PASS，17 項 | `.work/fabric-acceptance/pair-paper-26.2-20261002-181343/result.json` |
| folia-1.21.11 | PASS，17 項 | `.work/fabric-acceptance/pair-folia-1.21.11-20261002-182054/result.json` |
| folia-26.2 | PASS，17 項 | `.work/fabric-acceptance/pair-folia-26.2-20261002-182939/result.json` |

初始 200 區域清單實際由 **27,846／17,485 bytes** 兩個 payload 傳輸；manual resolve 後第二包為 17,885 bytes，各包均低於 28,000 bytes。每輪保存 `commands.log`、`client.log`、伺服器 console log、control 檢查點、唯讀候選對照、`verify.json` 與凍結 jar；完整比對來自資料與世界 hashes，截圖僅作畫面存證。

共 16 張原始 854×480 截圖已保存至 [`fabric/docs/screenshots/paper-phase3/`](../fabric/docs/screenshots/paper-phase3/README.md)，每組有衝突清單、Ghost theirs、Set blocks 後仍 unresolved、200 區域分頁四張。

四平台伺服器 phase3 回歸全部通過，各 **90 項檢查**，0 failure、server problem_lines=0，最終完整 verify=0、實體無重複；同一份 plugin jar SHA-256 如上。新增檢查涵蓋非 MERGING／未知區域／非法參數拒絕、ours／theirs／base 的完整 section 比對、manual 與 unresolved 語意；既有工具／重啟／abort／patch／1,000 chunk／200 區域回歸均保留。

| 伺服器 phase3 回歸 | 結果 | 證據 |
|---|---|---|
| paper-1.21.11 | PASS，90 項 | `.work/paper-phase3/paper-1.21.11-1790959735/results.json` |
| paper-26.2 | PASS，90 項 | `.work/paper-phase3/paper-26.2-1790966201/results.json` |
| folia-1.21.11 | PASS，90 項 | `.work/paper-phase3/folia-1.21.11-1790967714/results.json` |
| folia-26.2 | PASS，90 項 | `.work/paper-phase3/folia-26.2-1790969135/results.json` |

Fabric 單人 Phase 3 兩版均 `success=true`，最終各 2 個 parent、衝突清單與 CLI 相同、離線 verify=0；包含 Ghost 不寫入、abort、精確區域選擇與 resolve、continue 清空。

| Fabric 單人回歸 | 結果 | 證據 |
|---|---|---|
| 1.21.11 | PASS | `.work/fabric-acceptance/phase3-1.21.11-20261002-195500/result.json` |
| 26.2 | PASS | `.work/fabric-acceptance/phase3-26.2-20261002-200209/result.json` |

完整 `./gradlew --no-daemon --configure-on-demand --max-workers=1 build` 通過：**BUILD SUCCESSFUL in 32s**，77 tasks（15 executed、62 up-to-date），log `.work/interop-completion-final-build.log`。單元 suite **199 tests、0 failure／error／skip**：core 80、CLI 3、protocol 7、platform-api 8、Fabric logic 40、Paper common 22、Hub 36、i18n 3。新增／延伸回歸涵蓋共用指令解析與權限、capability、manual 將已解決區域恢復 unresolved、背景斷線及晚到舊 handler。

最後建置的 plugin SHA-256 為 `e3c2c3833433ccfada07c8648dcb9fbf04ee58216903bc01d2ddd1bc056578b5`。與四組對接／四平台回歸使用的凍結 jar 逐項比對，唯一內容差異是 `org/worldgit/i18n/lang/zh_tw.yml` 的 Fabric help 補上 conflict-select；所有程式、英文訊息與其他資源完全相同，最終 i18n suite 通過。比對證據 `.work/interop-plugin-content-comparison.json`，沒有把不同 SHA 當成相同 jar。

結果彙整 `.work/interop-final-summary.json`、佇列 `.work/interop-completion-suite.json`。收尾 `.work/interop-cleanup.json`：25701–25714 無監聽、bench.lock 已釋放、無 X11 監聽 socket，Paper／Folia 執行用的 server/world 副本已移除，client／bot／Xvfb／server 已結束；Fabric GameTest 產物與失敗輪證據保留供離線比對。新建／修改中間產物以 2026-10-02 15:37 UTC 為起點計算，觀察峰值 **1,394,501,032 bytes（1.299 GiB）**，低於 4 GB；最終用量與計算範圍見 `.work/interop-disk-current.json`，不把既有舊 `.work` 證據算入本輪。未 commit／push，未修改 experiments/。

### 失敗紀錄與限制

- 接續驗收佇列在前三組真客戶端對接已完整通過後，以 exit 143 終止於 Folia 26.2 啟動階段；該輪沒有完整結果，不列為通過。確認所有測試 port 關閉、bench.lock 釋放後，只續跑未完成項目；保留 `.work/interop-completion-suite-interrupted.json` 與 `.work/fabric-acceptance/pair-folia-26.2-20261002-182750/result.json`。
- 接續時 Paper 1.21.11 背景回歸已完整通過；Paper 26.2 背景回歸停在 79 項、未記錄正常結束，不能列為通過。原始 `.work/paper-phase3/paper-26.2-1790961283/results.json` 與 `.work/interop-interruptions.json` 保留中斷證據，後續以完整重跑結果為準。
- 客戶端 tick 清理修正後，Paper 1.21.11 已跑到真正重連、200 區域全部解決與 continue 清空，但原版斷線 API 留在多人清單，GameTest 收尾要求 TitleScreen，程序因此非零退出。測試補上回標題畫面並重跑；`.work/fabric-acceptance/pair-paper-1.21.11-20261002-175904/result.json` 不列為完整通過。
- Folia 1.21.11 首輪的 vanilla `/data merge` 未執行，箱子內容相同，只得到 4 格衝突；改用 owner 的 Bukkit Container snapshot inventory 更新並等待完成。
- 重連首輪在 `level=null` 時就檢查清除狀態；改成等待 reset 後，Paper 26.2 仍在 30 秒逾時。檢查兩版 Minecraft 實作確認 `disconnect(screen,false)` 只清理畫面／listener，沒有先關閉 TCP；GameTest 改用原版離開伺服器按鈕的 `disconnectFromWorld(DEFAULT_QUIT_MESSAGE)`，由真正斷線事件清除能力、清單及預覽，並保留 reset 的逾時驗證。正常 TCP 斷線後仍發現原本直接在 networking 回呼清理 GPU／狀態不可靠；已改由客戶端 tick 處理 handler 事件，加入晚到舊事件不清除新連線的回歸。失敗輪 `.work/fabric-acceptance/pair-paper-26.2-20261002-172840/result.json` 已通過 200 區域解碼，但不列為完整通過。

- 第一輪 Paper 1.21.11 在已通過 Ghost／Set blocks 後，GameTest 以無參數 translation key 找不到帶參數的 Resolve 元件。改成與 Set blocks 相同的真滑鼠點擊；截圖另等待重建並重新選取區域，避免存到暫時清空的畫面。失敗：`.work/fabric-acceptance/pair-paper-1.21.11-20261002-155424/result.json`。
- 後續 Paper 兩版在 continue／傳送後準備 200 區域時，正確被 dirty 檢查擋下。唯讀 diff 確認只有兩個 oak_door item 被玩家撿走（方塊、biome、metadata 皆無差異），來源是 fixture 拆門時的 vanilla 鄰居更新；已在變體建立後清除掉落物。`.work/interop-dirty-diff26.json` 與 `.work/interop-dirty-live26.json` 保留定位證據。這些輪不算通過。
- 26.2 將 Minecraft 的畫面入口移到 `gui.screen()`，共用 GameTest 最初編譯失敗，已加薄轉接；i18n 新增鍵最初未登記 MessageKeys，測試失敗後已修正。Paper preview 在非 MERGING／queued tool 在合併結束後的空值也已補檢查；Paper 一般錯誤統一使用 `common.error` MiniMessage，便於使用者與驗收識別。
- Ghost 完整接收 BE，但仍不繪製特殊 BE renderer；未增加 entity／biome 模型。200 區域測的是實際分片、清單與重連正確性，不宣稱任意世界／區域數的 FPS 或 TPS。舊 Paper 的 select 能力判斷由單元測試驗證，沒有另啟一台舊版插件伺服器。Sodium／Iris／硬體 GPU 等既有限制保持。

## Fabric 專用伺服器

日期：2026-10-03。接續 cf10727／aa9c770，決定 #71–#74；不 commit／push、不修改 experiments/。

已移除 WgCommands／ServerRuntime 的單人寫入限制；專用伺服器沿用 core live coordinator、精確 atoms、局部 capture／verify、MERGING WAL 與 #46 快照 state。新增 Paper resolve 位置參數、select all／manual、console 局部 restore、有／無玩家 ApplyBudget、批量動作與 console 指令屏障、握手後 durable 清單推送、MERGING 恢復／bossbar 與完成廣播。玩家保護沿用操作全程＋10 秒，登出清理 viewer／bossbar，#60 自動 commit 跳過維持。

新增 `fabric/tools/accept-dedicated.py` 與獨立 fixture mod；真正 Fabric Loader 專用伺服器＋Xvfb／llvmpipe 客戶端，第二位能力 viewer 用實際 Minecraft TCP 連線驗證清單同步。完整 section hashes、固定候選 state／BE 與 durable MergeState 對照，包含 switch／stash／reset／console box restore、乾淨 merge／兩 parent、Ghost／Set blocks／Resolve／continue、abort、patch、MERGING 重啟、批量鎖與玩家傷害、1,000 chunk merge tick probe、區域切換及截圖。

兩版完整專用伺服器驗收均通過，各 44 項檢查；兩版 Fabric 單人 Phase 2／Phase 3，以及 Paper／Folia 四組 Phase 3 回歸也全部完成並通過。

| Fabric 專用伺服器 | 檢查／離線 verify | 1,000 chunk merge | 平均 TPS／p99／最大 tick 間隔 | 區域切換中位數／最慢 | 結果證據 |
|---|---|---|---|---|---|
| 1.21.11 | PASS，44 項；COMPLETE，0 差異 | 169.91 秒，2 parent | 20.00／54.65 ms／1,693.27 ms | 0.701／0.801 秒 | `.work/fabric-acceptance/dedicated-fabric-1.21.11-20261003-013318/result.json` |
| 26.2 | PASS，44 項；COMPLETE，0 差異 | 165.89 秒，2 parent | 20.00／54.30 ms／1,610.13 ms | 0.702／0.811 秒 | `.work/fabric-acceptance/dedicated-fabric-26.2-20261003-020439/result.json` |

兩版 client／server exit=0、server exception=0，25701／25711 與 25702／25712 均已關閉。正式模組 SHA-256：1.21.11 `7fdd5340da6deb76a3b4feacd064773f40636f170cc65273139edde41a298561`；26.2 `7d531da20ece5f91d8c27bc40860ddc02d8cc11ce5e647c58da36f3f71f74a52`。八張原始截圖另保存至 [`fabric/docs/screenshots/dedicated/`](../fabric/docs/screenshots/dedicated/README.md)。

區域延遲量測 6 次 ours／theirs／base，使用 monotonic clock，包含 console 驅動等待完成訊息及 0.3 秒輸出收集；完整 section state 均與快照相同。1,000 chunk 使用兩個分支在各 chunk 不同位置的修改，真正套用及建立 merge commit，玩家在線；tick probe 記錄伺服器主迴圈。驗收使用受控平坦世界及 WorldGit 套用期間的世界 tick freeze，不能推論大型自然世界的生物／紅石吞吐量。fixture 建立／大量載入期間的原版「Can't keep up」警告保留在 raw log，不列為合併期間的量測。

完整 build 第一次通過：`BUILD SUCCESSFUL in 18s`，77 tasks（6 executed、71 up-to-date），log `.work/fabric-dedicated-regression-build.log`。全部實機回歸後，05:03 UTC 的最後 `./gradlew --no-daemon --configure-on-demand --max-workers=1 build` 也通過：`BUILD SUCCESSFUL in 15s`，77 tasks（1 executed、76 up-to-date），log `.work/fabric-dedicated-regression-final-build.log`。兩次均持有 bench.lock，使用 `GRADLE_USER_HOME=.work/gradle-home`。單元測試共 199 tests，0 failure／error／skip；模組彙整 `.work/fabric-dedicated-unit-summary.json`。

| 既有回歸 | 結果 | 證據 |
|---|---|---|
| Fabric 單人 Phase 2，1.21.11 | PASS；preview 與 CLI 相同、檢查點離線 verify=0 | `.work/fabric-acceptance/phase2-1.21.11-20261003-024727/result.json` |
| Fabric 單人 Phase 2，26.2 | PASS；preview 與 CLI 相同、檢查點離線 verify=0 | `.work/fabric-acceptance/phase2-26.2-20261003-030209/result.json` |
| Fabric 單人 Phase 3，1.21.11 | PASS；最後 2 parent、CLI 清單一致、離線 verify=0 | `.work/fabric-acceptance/phase3-1.21.11-20261003-030944/result.json` |
| Fabric 單人 Phase 3，26.2 | PASS；最後 2 parent、CLI 清單一致、離線 verify=0 | `.work/fabric-acceptance/phase3-26.2-20261003-031726/result.json` |
| Paper Phase 3，1.21.11 | PASS，90 項；最終完整 verify=0、實體無重複 | `.work/paper-phase3/paper-1.21.11-1790997467/results.json` |
| Paper Phase 3，26.2 | PASS，90 項；最終完整 verify=0、實體無重複 | `.work/paper-phase3/paper-26.2-1790999376/results.json` |
| Folia Phase 3，1.21.11 | PASS，90 項；最終完整 verify=0、實體無重複 | `.work/paper-phase3/folia-1.21.11-1791000925/results.json` |
| Folia Phase 3，26.2 | PASS，90 項；最終完整 verify=0、實體無重複 | `.work/paper-phase3/folia-26.2-1791002486/results.json` |

四組 Paper／Folia 回歸 server problem_lines=0，使用同一份 plugin SHA-256 `206a7c52aa17ada2cac67decc10c339b0fae4647f78afed0684b4e703b643dec`。本輪量測如下，單次、玩家在線、受控平坦世界與 frozen tick；Folia probe 只代表出生點 owner。

| 回歸平台 | 1,000 chunk merge（秒） | TPS／p99／最大 tick 間隔（ms） | 小區域中位數／最慢（秒） | 200 區域單區中位數／最慢（秒） |
|---|---:|---|---|---|
| Paper 1.21.11 | 167.79 | 19.86／60.1／1,464.5 | 0.956／4.535 | 0.701／0.701 |
| Paper 26.2 | 152.62 | 19.98／54.7／453.8 | 0.803／1.707 | 0.701／0.710 |
| Folia 1.21.11 | 129.46 | 19.97／53.0／423.5 | 0.855／1.806 | 0.601／0.701 |
| Folia 26.2 | 116.00 | 19.96／54.7／414.5 | 0.801／1.605 | 0.651／0.702 |

區域切換每組各 6 次，中位數均 ≤2 秒；Paper 1.21.11 有一次 4.535 秒的樣本，沒有把中位數目標當作所有操作的延遲上限。兩版 Fabric 單人 Phase 3 的最後提交各有 2 個 parent、CLI 清單一致，各檢查點離線 verify=0。專用伺服器正式 jar 的 SHA-256 與接續時工作樹的 build 產物相同，沒有為補文件重跑已完成的實機驗收。

上一輪於 04:06 UTC 因用量限制停止主對話，回歸佇列繼續執行：Paper 26.2 於 04:15:25、Folia 1.21.11 於 04:41:25、Folia 26.2 於 05:03:41 正常完成，再完成最後 build。接續時依 result.json、逐項檢查及佇列 exit=0 確認通過。佇列紀錄 `.work/fabric-dedicated-regressions.json`，接續核對彙整 `.work/fabric-dedicated-completion-summary.json`。

首次 1.21.11 完整驗收已通過 Phase 2，但批量鎖測試遇到 Minecraft 指令錯誤後逾時；該輪列為失敗，證據 `.work/fabric-acceptance/dedicated-fabric-1.21.11-20261003-011716/result.json`。獨立重現確認 Mixin 不允許從 Minecraft 注入程式呼叫 mixin package 內的一般 helper（`IllegalClassLoadError`）；已移到公開的 `org.worldgit.fabric.EditGuard`。修正後真專用伺服器的直接寫入／鎖外寫入／活塞／爆炸／肥料／console 屏障及 tick stepping 恢復全部通過，log `.work/dedicated-guard-1.21.11-1790991082/server-013146.log`。這個快速檢查只作修正證據，完整驗收仍以後續 result.json 為準。

26.2 第一輪在啟動前因 `Overworld settings missing` 退出，沒有任何驗收檢查；證據 `.work/fabric-acceptance/dedicated-fabric-26.2-20261003-020131/result.json`。Paper 平坦 fixture 的生成設定／規則位於維度目錄，vanilla 26.2 需要世界根目錄的 shared saved-data。驗收驅動只在測試副本補入既有 Fabric baseline 的根目錄生成設定，以及平坦 fixture 的安靜規則；不修改原始 fixture 或正式套用／追蹤規則。

預先啟動、等待專用伺服器結果的回歸佇列以 exit 143 結束，尚未執行任何回歸工作；已保存 `.work/fabric-dedicated-regression-wait-interrupted.json`，並在兩版專用伺服器正常完成後重新啟動。未把等待程序退出視為回歸通過。

收尾核對：八張精選 PNG 與驗收原始 framebuffer 的 SHA-256 相同，合計 924,747 bytes；正式 Fabric jar 不含 DedicatedServerFixture 或 GameTest 類別。本輪使用的專用伺服器及 Paper／Folia Phase 3 server/world 副本均已移除，25701–25714 已關閉，bench.lock 可取得，無執行中的 server／client／bot／Xvfb。新建或修改的中間產物以 2026-10-03 00:00 UTC 起的 ctime 計算，收尾觀察上界 782,499,926 bytes（0.729 GiB），低於 4 GB；包含保留的失敗證據、build 及快取變更，不含工作前既有的其他任務資料，詳見 `.work/fabric-dedicated-disk-final.json`。`git diff --check` 通過，未 commit／push、未修改 experiments/。本次要求的驗收已完成；線上 chunk 刪除、未接上的 metadata、第三方直接寫入及 GPU 等既有限制仍見 Fabric README。
