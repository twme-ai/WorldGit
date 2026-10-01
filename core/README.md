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

目前跨 DataVersion 一律清楚拒絕，不把改版本數字當 DataFixer；`.wgignore` 不一致也拒絕，規則遷移留待後續。world-meta 的還原／保留規則與限制見 [05](../docs/05-switch-restore.md)；平台批次與驗收見 [Phase 2 進度](../docs/12-phase2-progress.md)。重跑：持有 `bench.lock` 跑 `:core:integrationTest`，建置 `:cli:acceptanceToolsJar` 後執行 `python3 scripts/verify-phase2.py`。
