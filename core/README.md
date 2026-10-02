# WorldGit core

純 Java 21 函式庫，不依賴 MC、Bukkit、Fabric 或 protocol。正式 API 位於 `org.worldgit.core`；JGit 類別不外洩到公用介面。

## 接手入口

- `anvil.WorldLayout.discover(Path)`：世界／server root → `DimensionId → Dimension`，兩種存檔目錄；讀 DataVersion 與世界 metadata。
- `anvil.Nbt`／`RegionFile`／`RegionWriter`：有界 NBT 讀取，canonical compound 排序；gzip/zlib/raw/LZ4、外部 `.mcc` 讀寫。離線更新只動變更 chunk 的 sector，未改 chunk 的 sector／timestamp 不動；寫入使用 zlib。
- `model.Section`、`ChunkSnapshot`、`EntitySnapshot`：不可變中性快照；section 的 4096 格採 `x | z<<4 | y<<8`（YZX）。NBT 以 canonical bytes 保存，避免洩漏可修改陣列。
- `normalize.ChunkNormalizer`／`EntityNormalizer`：原生 NBT → 模型；`SnapshotCodec` → 正式有版本 zstd blob。正式 blob 與 Phase 0 不相容，不應混寫。
- `capture.SnapshotSource`：共用 capture 來源；`scan()` 提供 dirty candidates／可丟棄 index stamps，`snapshot()` 回傳 `CompletionStage<Optional<ChunkSnapshot>>`。不存在／非 full chunk 回傳 empty。來源在正確 region/server 執行緒複製資料並套用相同 IgnoreRules。
- `service.DimensionRepository`：單維度 `initialize/commit/status/diff/log/repack`，在建構時取得 repo operation lock，請使用 try-with-resources。`objects()`／`refs()` 提供 Hub 的唯讀歷史存取。
- `service.WorldRepositories`：一組維度的 init/commit/status；回傳每維度 `Outcome`，一個失敗不遮蔽其他成功。可注入 `SnapshotSource` factory，CLI 注入 platform-api 的 `OfflineWorld`。

```java
var layout = WorldLayout.discover(Path.of("/srv/minecraft/world"));
var worlds = new WorldRepositories(layout);
var identity = new CommitMetadata.Identity("Alice", "alice@example.org");
var result = worlds.init(null, "creative", WorldGitConfig.Track.ALL, identity);
// result.snapshot()；result.dimensions() 每一項明確包含 value 或 error。
```

線上端應直接呼叫 `DimensionRepository.commit(source, manifest, metadata, tolerance)`，用同一 snapshot UUID 協調各維度。主要作者／committer 使用 git commit 欄位，多人 `Contribution` 記錄身分、player UUID、chunk 集合與 cause。`CommitTrailers` 保存 co-author 與完整歸屬；source 支援 CLI/PLUGIN/MOD/HUB，auto 也在 trailer。

`platform-api.LiveWorld` 延伸 `SnapshotSource`，core 無法反向依賴 platform-api。插件需在背景的 repo executor 呼叫同步 capture API；不可在 region 執行緒等待另一個 region 的 future。先 `flush`、擷取 `DirtyChunkTracker.Batch`，commit 成功後有條件 acknowledge；status 不清除 dirty。

## tree 與設定

每個 bare repo 的根直接是 `.wgignore`、`r.X.Z/c.X.Z/{s.Y.bin,biomes.bin,entities.bin,ticks.bin,structures.bin}`；沒有 dimension 層。全空氣且無 BE 的 section 缺檔即空氣。POI、光照、Heightmaps 不存；structures 的 References 是集合，long[] 排序避免伺服器重寫的假 diff。

主世界另有 `world-meta/level.nbt`、地圖／記分板與各維度的 gamerule/worldgen/邊界 NBT，以及 `world-meta/worldgit.yml`、`dimensions` YAML 清單。其他維度的 repo 設定位於 root `worldgit.yml`，方便單獨使用。

bare repo 的可編輯 sidecar：

- `<repo>/.wgignore`：commit 時寫入 root `.wgignore` blob；修改後 status 提示，下次 commit 移除被排除內容。
- `<repo>/worldgit-repo.yml`：`track: all|modified-only`，主世界提交於 `world-meta/worldgit.yml`，其他維度提交於 root `worldgit.yml`。
- `<world repo root>/worldgit.yml`：本機 `palette: default|colorblind`、`entity-tolerance: 2`；不進版本控制。
- `<repo>/worldgit.index`：有版本的 binary cache，不是設定檔，可刪除。綁定 HEAD、規則、容許距離與 working tree id。損毀會警告並重建。

`modified-only` **目前只讀寫、版本控制與提示，篩選尚未生效**。離線 persistence 是版本無關的保守近似；`EntitySemantics` 可由 adapter 提供真正 persistence 與 tag registry。離線 EntityTagRegistry 內建兩版 vanilla tags，依 `DataPacks.Enabled` 載入資料夾/ZIP，支援 replace、遞迴/optional 參照與後面優先；未知 tag 明確報錯。缺少已啟用 pack 提示可能不完整，未知模組內建 pack 要由 adapter 提供 registry。

world-meta 可用 `field worldgit:map *` 排除地圖，以及 `worldgit:level/scoreboard/boss_events/gamerules/border/worldgen` 對應 NBT 根欄位；`!field` 同樣後面優先。排除不會刪除活世界資料。`SnapshotSource.normalizationFingerprint()` 綁定正規化政策、DataVersion、tag registry；adapter 的政策/registry 改變時要更新 fingerprint，使 index 全量重建。

`.wgignore` 的 `area` 是包含端點的 block/entity 範圍；biome 使用 4×4×4 sample 的起點比對，排除 sample 使用空字串。`!field` 可以加回內建忽略的普通欄位；實體 id/UUID/Pos 是模型必要身分欄位。玩家永遠排除。structures 是不可拆的 chunk blob，不做結構 bounding box 裁切。

## diff 與效能

`DiffEngine.compare(dimension, beforeTree, afterTree, tolerance, Detail)` 先短路相同 tree id，然後解碼不同的 leaf。`WorldDiff` 包含 section、實體、biome 與 metadata 差異，`ChangeKind` 為 added/removed/modified/conflict。BE 差異併入所在格，避免統計重複計數。

- `Detail.SUMMARY`：每 section 有 `Counts`，blocks 空列表；biome sampleIndex = -1 與 count 表示 section 統計。capture／CLI 預設使用此模式，init 不展開幾千萬筆方塊。
- `Detail.BLOCKS`：逐格明細、BE 前後 canonical NBT Base64，以及逐 biome sample，供 CLI `--blocks`、protocol 鬼影、Hub 的局部檢視使用。可用 `compare(..., Detail.BLOCKS, Set<ChunkPos>)` 只解碼指定視窗的方塊/biome，實體保持 UUID 全域比對後裁切。大範圍不可一次展開全世界。
- 實體全域 UUID 比對與 HEAD 黏性錨點，2 格內且其餘欄位相同則沿用 HEAD；跨 chunk 不算新增＋刪除。NoAI 與靜態實體精確比對。

offline index 對檔案 mtime（奈秒）、size、fileKey 與 sector location 做便宜篩選；region timestamp 在本次／上次掃描秒的不確定窗內，或檔案屬性改變時，重讀 compressed payload SHA-256，再決定是否解析 NBT。同秒重寫不會僅因 timestamp 相同而漏掉。`status --full` 兜底。蓄意回填所有檔案屬性及舊時間戳屬於 `--full` 才能處理的情境。

## pack

init 設 `pack.packSizeLimit = 95000000`，關閉 JGit 自動 GC；JGit 7.3 本身不會依此設定分 pack。`DimensionRepository.repack()`／`JGitStore.gc()` 使用 JGit PackWriter，依 zlib 最壞上界分組、禁用 delta/reuse，每個 pack 驗證 ≤ 95,000,000 bytes，先安裝全部 pack/index 才刪除舊 pack 與已打包 loose objects。GC 保守保留不可達的 loose objects，尚無到期 prune。

請透過上述 API 維護 pack。外部 JGit GC 不遵守此保證；native git 可使用 repo 設定。其他程序也應遵守 WorldGit operation lock。大 blob 限 32 MiB；不可把整個世界或一張大地圖合成單一超大 blob。

## 驗證

`./gradlew :core:test` 用提交的真實 fixture；`integrationTest` 用完整 baseline（不存在時略過）；`packLimitTest` 真正產生 > 95 MB 隨機內容，應持有 `.work/bench.lock`。詳細數字見 [進度](../docs/11-phase1-progress.md)。

## Phase 2 套用與復原

`ApplyPlanner.plan(objects, dimension, currentTree, targetTree, scope, options)` 分層短路，只建立有差異的 section／BE、biome、UUID 實體、tick、structure 與 metadata 操作。`DimensionRepository.workingTree` 全量掃描目前世界，不移動 HEAD。`ApplyPlan` 保存壓縮 section blob、4096-bit mask、來源實體位置，支援 `toBytes/fromBytes`、`only(Scope)` 與 `batches(maxSections)`。entity 分成移除／生成兩階段；平台需等全部移除完成再生成。

`Scope.chunkRadius` 為含端點的正方形（0–256），`Scope.box` 為含端點的方塊盒；BE 隨方塊逐格裁切。biome 仍是原版 4×4×4 sample，依起點裁切；tick／structure 只在完整 chunk 範圍套用，有 area 排除時保留。`OfflineApplier` 或正式 `OfflineWorld.apply` 持有 OS session lock，清除變更 chunk 的光照／Heightmaps／POI，UUID 在全維度（含 passengers）移除後依 Pos 寫回。多維度用 `OfflineApplier.applyAll(plans, lock)` 共用移除／生成 barrier；WorldOperations 已使用此入口。低階 writer 不是整個世界的原子交易；中途中斷必須由操作紀錄恢復。

`WorldOperations` 是離線世界組入口，建構時持有世界組／各 repo／session 鎖，提供 restore、switch、branch、reset、stash、verify。先預檢所有維度、寫 `apply-state.yml`、套用並全量驗證，成功才移動 HEAD；失敗回復 refs 並持久化 PARTIAL，世界內容可用 `switch --force`／`reset --hard` 全量重套。PARTIAL 禁止 commit／普通 switch／branch／stash。restore 保持 HEAD；switch hash 為 detached HEAD。

stash 使用各維度 `refs/worldgit/stash/<UUID>` 與世界組 `stash.yml`。pop 要求乾淨且原基底相同，不做跨分支合併。`WorldRepositories` 對不變維度也保存 `refs/worldgit/groups/<snapshot>`，hash 可配對該次完整維度組；舊 Phase 1 歷史以 first-parent snapshot 回溯，無法配對就拒絕。

線上平台可用向後相容的 `WorldOperations.live(layout, LiveAccess)` 共用相同預檢、journal、驗證與 HEAD／stash 流程。遊戲持有 session.lock；呼叫端須先鎖定編輯與 flush，直到 close 後才解鎖，且只在 repo executor 呼叫。`LiveAccess.source` 提供 owner 上的快照、`validate` 全組寫入前預檢、`applyAll` 負責全維度 UUID 移除→生成 barrier 與完整存檔。`packs()` 可提供模組 pack resolver。關閉此入口只釋放 repo 鎖，不釋放遊戲 session；既有離線入口仍取得並檢查 OS session.lock。

目前跨 DataVersion 一律清楚拒絕，不把改版本數字當 DataFixer；`.wgignore` 不一致也拒絕，規則遷移留待後續。world-meta 的還原／保留規則與限制見 [05](../docs/05-switch-restore.md)；平台批次與驗收見 [Phase 2 進度](../docs/12-phase2-progress.md)。重跑：持有 `bench.lock` 跑 `:core:integrationTest`，建置 `:cli:acceptanceToolsJar` 後執行 `python3 scripts/verify-phase2.py`。

## Phase 3 三方合併

`merge.MergeBases.best/unique` 找每維度最佳共同祖先（沒有則空 tree，criss-cross 多個 base 拒絕）；`MergeEngine.merge(k)` 產生候選 tree＋MergeReport。tree 分層短路、4096 格／BE 原子、biome sample、全域 UUID entity、ticks／structures 原子及 world-meta NBT 逐鍵。`MergeEngine.select` 替換區域精確 atoms，`preview` 回傳方塊與 BE；Hub 可只操作 trees，不需要 working world。

`WorldOperations` 的世界組 API：

- `merge/revert/cherryPick(revision, MergeOptions)`：要求乾淨（含 untracked），開始套用＋MERGING；dryRun 只建立 objects／計畫，不寫 refs／世界／合併狀態。noCommit=true 也保留乾淨合併供平台更新形狀。
- `merging()`：持久化 MergeState，含原 HEAD／分支、各候選樹、region 選擇與 resolved；`remaining()` 為剩餘區域。
- `selectRegion(id, Choice, resolved, dryRun)`：ours／theirs／base 原地切換，0 為 all；傳 false 可只切換預覽。
- `markResolved(id, manual, dryRun)`：保留目前選擇或 manual；manual 權威資料是活世界，不接受假造的解決快照。
- `regionPreview(id, Choice)`：讀取候選方塊／完整 BE，不寫回。
- `continueMerge(author, source, dryRun)`／`commitMerge(author, source, message, dryRun)`：全部解決後全組 capture，建立同 snapshot 的 merge commit（不同 tip 兩 parent；revert／cherry-pick 單 parent）。
- `abortMerge(dryRun)`：恢復原世界、規則，HEAD 不動；支援合併寫回失敗留下的 PARTIAL。`lastMergeReports()` 讀取完成報告。

MergeResult 含 state、merging、各維度 reports／plans、完成 commits、error。MergeReport 含自動 section 數、Region 列表、完整規則差異、updateShapes 清單與紅石提示。範圍只有 bounds 用於顯示，切換精確 atoms；不能把整個包圍盒當 replace 範圍。`MergeState.read` 可唯讀讀取 merge-state.bin／last-merge-report.bin；有版本、解碼上限 32 MiB。各 repo MERGE_HEAD 與 refs pin 保留來源／備份，操作仍使用 Phase 2 journal。

離線與線上都維持來源方塊 state，不重算鄰居形狀（docs/09 #46）；updateShapes 是交界處的提示清單，平台不自動處理。線上沿用 WorldOperations.live：全程本次操作的 lockEdits／flush／owner apply／驗證 barrier，套用時不得觸發鄰居更新。等待衝突選擇期間可解鎖，不需要一直 freeze。

merge 的 `.wgignore` 改用有序三方合併（衝突先拒絕），新規則過濾三邊；重新納入時 ours 可從活世界取回資料，其他歷史不猜測未保存內容。Phase 2 switch／restore 的規則限制不變。DataVersion／DataPacks 仍清楚拒絕不一致。規則不同時保存原來被排除的內容，abort 可以回復；保留原始 MC 暫態／衍生欄位的界線沿用 Phase 2。

驗收／量測與給 Paper／Fabric／Hub 的完整摘要見 [13](../docs/13-phase3-progress.md)，重跑 `scripts/verify-phase3.py`（自行拿 bench.lock），以及 `:core:integrationTest --tests org.worldgit.core.Phase3LocalIntegrationTest`。

## 區域切換延遲（2026-10-02）

`selectRegion` 的一般 chunk atoms 路徑使用 `LiveAccess.source(dimension, chunks)`、`lockChunks` 與 `applyRegions`：不呼叫世界 scan／workingTree，不擷取沒有選擇區域的維度。離線來源也能直接依座標讀取 chunk，不需先全量 scan。方塊／BE 的 mask 僅含選擇 atoms；套用後以 0 格實體容許距離驗證完整受影響 chunk，因此同 chunk 區域外的追蹤資料也要相同。UUID 切換加入歷史與實際位置，包含巢狀乘客拆離／改騎其他載具後的位置及其他維度的 removal；定位仍需掃描 entity storage，不保證此特殊路徑與世界大小無關。舊 LiveAccess 預設方法保守準備全量來源；要取得局部效能，adapter 須覆寫局部 source。

`merge-state.bin` 為基底，`merge-state.bin.updates` 保存 result commit、choice／resolved 及提示差異。`MergeState.read` 會重播 WAL，CLI／平台不可只讀基底；舊 binary readers 無法看到增量，需一併升級。WAL 有長度、CRC32C、operation UUID 及 32 MiB 上限；最後一筆截斷沿用上一筆完整狀態。metadata 等完整回退路徑可更新基底 checkpoint 並清除 WAL，清理窗口仍由 APPLYING journal 保護。小型 chunk journal 在第一次套用前寫 APPLYING，在驗證與 WAL force 完成後才 COMPLETE；保存失敗也是 PARTIAL。恢復仍用完整 abort，不提供未驗證的逐批續傳。

merge 開始、abort、continue／commit 的完整世界屏障保留；continue 在發布 HEAD 前再次 capture 全組並比對，完成報告重算全部交界提示。區域外其他 chunk 的玩家 manual 編輯在 continue 取活世界資料，切換時不重讀；提示可能暫時落後於此類編輯。metadata／非 chunk FILE 選擇沿用完整路徑。所有平台都不觸發鄰居更新（#46）。

以 `-Dworldgit.profile=true` 開啟 `WGPROFILE` 分段計時，階段是 inclusive（例如 verify 內含 capture），不可直接相加。Paper lighting 的數值為 owner 回呼等待時間加總；總耗時使用 monotonic wall clock。根因、六端驗收數字與限制見 [區域切換延遲報告](../docs/13-phase3-progress.md#區域切換延遲)。
