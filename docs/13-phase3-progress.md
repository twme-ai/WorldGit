# Phase 3 任務 1：core 三方合併與離線 CLI

日期：2026-10-02。此任務提供 core／protocol／CLI 基礎，Paper、Fabric、Hub 尚未實作 Phase 3 的命令或 UI；既有平台繼續使用相容的 Phase 2 API。決定見 [09 #43–#49](09-roadmap-open-questions.md)。

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

入口為 `org.worldgit.core.service.WorldOperations`。離線建構子使用 OfflineApplier；線上透過既有 `WorldOperations.live(...)` 與 Phase 2 `LiveAccess` 的 capture、validate、apply／flush barrier。呼叫端仍須持有平台編輯鎖、freeze 與 owner 排程契約，不能在 region owner 阻塞等待自己的工作。

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

世界組 root 下保存 `merge-state.bin`；各 repo 保存 `MERGE_HEAD`。候選與原世界以 `refs/worldgit/merges/<operation>/...` pin 住。候選有獨立 snapshot UUID，避免污染正式群組與舊歷史配對；成功提交才使用全組共用新 UUID。狀態檔為 bounded canonical NBT／zstd，版本 1，上限 32 MiB。APPLYING／PARTIAL 在既有 apply journal，衝突選擇在 merge state，兩者分開。

### 中性 tree 引擎與報告（Hub）

`MergeBases.best/unique(RefStore, ours, theirs)`；`MergeEngine(ObjectStore, DimensionId, baseTree, oursTree, theirsTree, oursAuthors, theirsAuthors).merge(k)` 產生 result tree 與 MergeReport。`MergeEngine.select`、`preview`、`updateShapes` 可在無 LiveWorld 的 Hub 使用。

Hub 必須先處理 snapshot 配對、DataVersion／DataPacks、`IgnoreRuleMerge.merge` 與 `TreeFilter.filter`，再呼叫 tree 引擎；引擎本身不讀世界、不搬 refs、不建 PR。Hub 的 pending choice／權限／API 尚待其任務實作。`RefStore.createCommit(tree, List<parents>, metadata, trailers)` 新 overload 向後相容，JGitStore 已支援多 parent。

| 報告欄位 | 用途 |
|---|---|
| automaticallyMergedSections | 相對 base 任一側有變動、且沒有方塊衝突的 section 數（依路徑去重）。 |
| regions | id、dimension、bounds、blockCount、oursAuthors／theirsAuthors、redstone、choice、resolved、atoms。metadata 無空間位置時 bounds=null；作者為 tip author＋contributions 摘要，非逐格 blame。 |
| ruleDifferences | 各維度完整 base／ours／theirs／merged 規則文字；CLI 顯示有色行差異。 |
| updateShapes | 每維度世界座標 Cell 清單（交界提示，不自動處理）；切換區域後重新計算清單。 |
| warnings | 紅石「建議測試」等警示；交界提示由 updateShapes 與 CLI 呈現（不自動處理，#46）。 |

`Region.atoms` 是精確修改集合，包含 BLOCK／BIOME／ENTITY／TICKS／STRUCTURES／METADATA／FILE；不能以 bounds 填滿覆蓋。同格 BE 與方塊一起切換；設定使用不可拆字串的 NBT key path。實體兩邊都改同 UUID 即衝突，連結果相同也保守衝突。

### updateShape 與 protocol（Paper／Fabric）

**使用者決定（2026-10-02）：套用合併結果時不重算鄰居形狀，一律維持快照中儲存的方塊狀態。** 離線與線上套用（合併、切換區域）都不得觸發 updateShape 或鄰居更新，套用後方塊 state 要與快照逐格相同。報告的 updateShapes 只是交界處的提示清單（CLI／GUI 可顯示給使用者檢查），平台不自動處理；有紅石的區域提示建議測試電路。

`org.worldgit.protocol.MergeProtocol` 是獨立可選訊息：`worldgit:conflicts`／`worldgit:conflict_preview`，envelope v1；只有支援 `merge-regions-v1` 的 peer 才發送。該 capability **尚未放入預設握手**，等平台實作後自行宣告。既有 v2 Protocol sealed messages／diff／status／clear 不變。

`regions(previewId, dimension, regions)` 編碼區域 id、bounds、格數、choice、resolved、redstone；`preview(previewId, dimension, regionId, choice, cells)` 編碼方塊 state 與整份 BE。使用 encode／decode／Assembler 組合 Part 分片，完成後回傳 Completed：每包 ≤28,000 bytes，批次 ≤8 MiB／8192 parts／100,000 entries，30 秒逾時，完整批次才發布，clear floor 防止晚到資料回來。正式 wire 格式與限制見 [protocol README](../protocol/README.md)。Fabric 只負責 overlay／UI，實際世界切換由平台調用 coordinator。

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

- Paper／Folia 的線上 merge／region 切換工具／GUI、Fabric UI／疊圖／單人命令、Hub 合併／PR／衝突檢視待各平台任務；此次沒有實作這些功能，也沒有玩家在線／TPS／Folia 驗收。
- 多維度參與 repo 與實體世界目錄須先存在；不自動建立來源新增的維度。跨維度 UUID 移動協調未新增（本次維度內 UUID 全域比較沿用 Phase 1 語意）。
- 有 area ignore 時 ticks／structures 沿用 Phase 2 保守限制；未追蹤 biome sample 不能當空 biome 寫入。跨 DataVersion／DataPacks 的可靠遷移仍未完成。
- MERGING 不提供並行多工作區／remote 鎖；全組 refs CAS 與回滾沿用 Phase 2，程序在多 repo refs 更新間強制中止仍需操作員恢復原 HEAD 後 abort。不是跨 repo 資料庫交易。
- 狀態超過 32 MiB 會明確拒絕保存；大型實體預覽超過 8 MiB 要縮小批次。保留候選 refs／完成報告，尚未實作回收命令或任意 Mod 語意 registry。

完整語意見 [06](06-diff-merge.md)；儲存模型見 [02](02-data-model.md)；Phase 2 與合併的互斥見 [05](05-switch-restore.md)；平台接合見 [08](08-architecture.md) 與 [core README](../core/README.md)。
