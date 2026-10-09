# WorldGit core

純 Java 21 函式庫，不依賴 Minecraft、Bukkit、Fabric 或 protocol，JGit 類別不外洩到公用介面。Phase 5 契約見 [設計](../docs/16-phase5-design.md)；Paper／Fabric／Hub 的完整操作介面由後續任務更新。

## 世界、維度與 repo

`WorldLayout.discover(path)` 接受世界根、包含 world 的伺服器目錄、Paper 分離世界、DIM-1／DIM1，以及 dimensions/ns/path。`currentDimension()` 是路徑所在維度；`repository(id)` 是該維度的 repo 位置。主世界永遠放 `<world>/.worldgit`，即使 26.2 地形在 dimensions/minecraft/overworld；其他維度放自己的地形資料夾 `.worldgit`。

每維度的 HEAD、分支、tag、stash、MERGING、PARTIAL、remote、tracking、設定與歷史完全獨立。snapshot UUID 是單次提交資訊，不能配對其他維度。批次僅提供便利，逐維度回報並保留已成功的結果。主世界保存世界級資料，非主世界只能處理自己的 dimension-meta；還原舊主世界 tree 也不套用其他維度的 metadata。

```java
var layout = WorldLayout.discover(Path.of("/srv/minecraft/world"));
var worlds = new WorldRepositories(layout);
var author = new CommitMetadata.Identity("Alice", "alice@example.org");
var initialized = worlds.init(null, "creative", WorldGitConfig.Track.ALL, author);
// null 只 init currentDimension，不是全部。
var dimensions = worlds.initializable(); // 實際資料路徑、新 repo 位置、initialized
var committed = worlds.commit(null, "完成入口", author, 2);
// null commit 對全部已 init 維度各自提交有變動的內容。
try (var ops = WorldOperations.inDimension(layout, new DimensionId("minecraft:the_nether"))) {
    ops.createBranch("cavern", null);
    ops.switchTo("cavern", false, false, false, false);
}
```

`initDimensions(ids,...)` 明確初始化集合；`initAll(...)` 是明確的便利入口。`tracked()` 新位置優先，兼容兩種舊位置。`RepositoryMigration.migrate(layout,id,dryRun)` 先取得 session 鎖，拒絕未完成舊 journal，以複製／完整 refs 與物件驗證／原子 rename 搬移，保留舊備份並可在中斷後重跑。世界運行時拒絕遷移。

## 快照與設定

bare repo tree 沒有額外 dimension 層：

```text
.wgignore
dimensions                            # 主世界的發現清單，沒有一致性約束
world-meta/level.nbt                   # 僅主世界
world-meta/worldgit.yml                # 主世界的版本化 repo 設定
worldgit.yml                          # 非主世界的版本化 repo 設定
world-meta/{地圖、記分板、資料包原檔…}
dimension-meta/{該維度 saved-data…}    # 26.2 非主世界
player-touched.yml                    # player-touched 的版本化 UUID 集合
r.X.Z/c.X.Z/{s.Y.bin,biomes.bin,entities.bin,ticks.bin,structures.bin}
```

section 的 4096 格以 `x | z<<4 | y<<8` 排序，正式 zstd blob 有版本。全空氣且無 BE 的 section 可省略。NBT canonical compound 排序；POI、光照、Heightmaps 不保存。structures References 以集合正規化，避免重寫造成假 diff。

每個 repo 的可編輯 sidecar：

| 檔案 | 用途 |
|---|---|
| `.wgignore` | 有序排除規則，下次 commit 保存 |
| `worldgit-repo.yml` | `track: all|modified-only`、`entities: all|player-touched`，版本化 |
| `worldgit.yml` | 本機色票／entity-tolerance，不版本化 |
| `remotes.yml` | 該維度展開後的 URL，不版本化 |
| `player-touched.yml` | 玩家觸及 UUID，隨歷史移動 |
| `worldgit.index` | 可丟棄 binary cache，損毀會警告重建 |
| `apply-state.yml`、`stash.yml`、`merge-state.bin`／`.updates` | 該維度套用、stash、合併恢復狀態 |
| `push-state.yml`、`fetch-state.yml`、`tag-state.yml` | 單維度 CAS 與恢復 journal |

新 creative 預設 `player-touched`，方塊／BE／biome／地形仍完整追蹤。survival 及沒有 entities 鍵的舊 repo 維持 all；自然生物的排除規則仍沿用 survival 範本。CLI 沒有事件來源，creative init 集合為空並提示。`PlayerTouchedEntities.touch` 記錄根與乘客閉包，上限 100000 UUID／8 MiB；完整成功 commit 清除已消失的 UUID，status 不改集合。capture、merge、clone 只處理集合內實體，apply 保留集合外的實體與位置。

Paper／Fabric 的相容入口在觸及事件接線前明確使用 `Entities.ALL`，遊戲內 creative init 暫時保持全部實體追蹤；任務 3／4 接完事件後才改用新預設。

`modified-only` 使用 `SnapshotSource.modifiedChunks()`／`ModifiedChunks` 完整曾編輯集合，缺集合時保守全存並警告。不能把當次 dirty batch 當完整集合。

`IgnoreEditor.Document` 保留原始行、註解、空行、順序與尾端換行；add／remove／move／enabled，語法錯誤含行號。`preview` 回報移除追蹤的計數與有界樣本，`testBlock/testEntity/testField` 回報最後命中規則。256 KiB／4096 行，MERGING 禁止修改，持 repo 鎖原子寫回。規則是純文字，不解析 UI 標籤。

world-meta 支援 field worldgit:level/map/scoreboard/boss_events/gamerules/border/worldgen/saved_data；area 包含端點，biome 依 4×4×4 sample 起點裁切。玩家永遠排除，實體 id／UUID／Pos 保持模型必要欄位。`EntityTagRegistry` 載入兩版 vanilla 與已啟用資料包 tag，未知 tag 明確拒絕；平臺可提供真 persistence／模組 pack resolver。

## capture、diff 與套用

`DimensionRepository` 建構時取得 repo operation 鎖，請使用 try-with-resources。`SnapshotSource.scan` 提供候選／index stamps，`snapshot` 非 full／不存在時為 empty。平臺在 owner 執行緒複製資料，背景 repo executor 正規化及寫 git；不得在 owner 等另一個 owner 的 future。

offline index 比較 region 標頭、mtime／size／fileKey／Unix ctime 與 sector location，同秒不確定窗或沒有 ctime 時重讀 compressed payload SHA-256；外部 `.mcc` 同樣核對。索引 body 附 SHA-256，舊版／損毀回退全量。CLI 全域 `--full` 及明確 `verify HEAD` 強制完整擷取。touched 集合變更也使相關正規化政策失效。

working capture 共用 status 索引；apply 後強制重擷取寫入 chunk，再 scan 全維度，其他 chunk 經來源內容證明才沿用。離線驗證仍比較整棵 working tree；線上 `LiveAccess.guardApply` 只保護受影響 chunk 與邊界，單 tick 套用使用當 tick 的驗證副本。未受影響 chunk 的自然變動保留為 dirty。HEAD 只在寫入部分零差異後移動並重新綁定索引；詳細保證與 benchmark 見 [18](../docs/18-performance.md)。

`DiffEngine.compare` 相同 tree 短路，SUMMARY 提供 section 計數，BLOCKS 才展開逐格方塊／BE／biome。window 限制方塊解碼，實體按該維度 UUID 比對；BE 併入所在格，避免重複統計。動態實體在容許距離內沿用 HEAD 黏性錨點，NoAI 與靜態實體精確比對。

`ApplyPlanner.plan` 產生中性計畫：section mask、BE、biome、UUID、ticks／structures、metadata；局部範圍不動世界級資料。目標沒有的 chunk 預設保留 untracked，明確 commit 可重新追蹤；刪除需要選項且排除規則允許。

`WorldOperations` 只持單維度 repo 鎖與離線 session 鎖。restore 保持 HEAD；switch 分支／detached、reset、stash、merge／revert／cherry-pick、verify 共用預檢→journal→apply→verify→HEAD。失敗留下 PARTIAL，refs 保留原狀，世界內容以 force switch／reset 全範圍恢復。stash pop 要求原基底且乾淨，成功才 drop。

線上用 `WorldOperations.live(layout,access,id)`：呼叫端管理 lockEdits、flush、owner apply、IO／光照屏障；close 不釋放遊戲的 session.lock。UUID 移除與生成只限選定維度，不刪除其他維度中的實體。兩版原生 region 支援 gzip／zlib／raw／LZ4、外部 mcc，就地更新只動變更 sector。跨 DataVersion 與不一致 DataPacks 拒絕；switch／restore 的 .wgignore 不一致仍預檢拒絕。

## merge、graph 與操作結果

`MergeBases` 找最佳共同祖先，沒有則空 tree，多個最佳 base 明確拒絕。`MergeEngine` 方塊逐格／BE 原子／biome／實體 UUID／NBT 欄位三方合併；規則有序文字三方合併，重新納入的歷史資料不猜測。`selectRegion` 只套精確 atoms，manual 以活世界為準，continue 再 capture 驗證並發布兩 parent commit。MERGING／WAL／refs pin 都在自己的 repo，abort 還原該維度原狀。

所有套用維持來源快照的方塊 state，不呼叫 updateShape；交界與紅石只提供檢查提示。region 快速路徑只 capture 受影響 chunk，metadata 回退完整路徑，UUID 定位可能需要掃 entity storage。

`CommitGraph.read(refs,limit,all)` 提供共同拓樸／時間排序、parents、snapshot、作者、labels、lane／before／after／edges／truncated；上限 10000 列、遍歷 20000 commit。`GraphText` 是基於相同 lane 的 ASCII 呈現，其他端可用 SVG／聊天圖。

`OperationProgress` 提供同一 operationId 的階段、完成／總量、單位、速率與 ETA，未知總量為 null。計算端只更新記憶體，dispatcher ≥100 ms 節流回呼，不在世界鎖內做 IO。取消在安全點檢查，已寫世界內容依 PARTIAL 恢復。`result(...)` 產生同 id 的 `OperationResult`，SUCCESS／NO_OP／PARTIAL／FAILED／CANCELLED 對應 CLI exit 0／0／2／1／130。

`OperationResult.ErrorReport` 提供代碼、操作／id、維度、版本、UTC、純文字全文，遮罩 userinfo／PAT／Authorization／敏感鍵及傳入已知秘密，長度上限 8192。平臺／CLI／Hub 負責呈現，共用摘要與下一步欄位。

## remote 與組裝

`WorldRemotes` 不取得世界 session、不讀寫活世界；每維度自己的設定、分支、tag、tracking、CAS 與 journal。多維度入口逐一執行，不要求同分支／snapshot／publication。`RepositoryGroup` 只是開啟集合的轉接器，不能當全組交易。`WorldOperations.pull` 的 targets／expectedHeads 只能包含自己維度。

`BareWorldMerge(repository,dimension)` 是單維度裸合併；Hub 舊 map 入口暫供任務 2 過渡。`WorldClone.cloneWorld(...,Map<DimensionId,String>,...)` 各維度預設分支／明確 branch，需主世界 metadata，組裝與驗證完成後原子發布目的地。`WorldAssembler` 接受 commit map；release ZIP 排除所有 `.worldgit`／玩家／session.lock，使用者自己壓縮世界資料夾則攜帶 repo。

1.21.11 組裝保留空的 `DIM-1/`、`DIM1/`，即使未選取該維度也保留目錄，ZIP 亦包含目錄項目；不建立未選取維度的地形或 repo。Paper 因此能搬移並沿用主世界 seed／DragonFight，避免首次開服生成新的終界設定。

JGit 自動 GC 關閉，WorldGit 分包使用 ≤95000000 bytes，單 blob ≤32 MiB；transfers 合成 refs 支援空 repo 分批下載。force-with-lease、tag CAS、單維度中斷恢復保留。外部 native git／託管端 GC 不受此保證。

## 驗證

```sh
flock .work/bench.lock env GRADLE_USER_HOME=.work/gradle-home ./gradlew :core:test :cli:test --no-daemon --configure-on-demand --max-workers=1
python3 cli/tools/phase5_acceptance.py  # 自持鎖；真 Hub、SQLite、兩版 Paper、ZIP、遷移
```

既有 fixture 測試需要舊實體政策時，明確 initAll(...,Entities.ALL)，保留原本內容與隔離斷言。完整 build／平臺回歸與整合結果見 [驗收紀錄](../docs/16-phase5-design.md#本次實作與後續缺口)。
