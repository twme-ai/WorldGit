# Phase 0 / 02-core-proto 報告：正規化、section 雜湊、JGit 映射、量測、寫回

> 測試日期 2026-09-30。Paper 1.21.11 build 132（Java 21，DataVersion 4671）與 Paper 26.2 build 129（Java 25，DataVersion 4903）。
> 原始數據：`.work/core-proto/results-{1.21.11,26.2}.json`、`.work/core-proto/<ver>/logs/*.log`、`.work/core-proto/run-<ver>.out`。
> **環境註記**：機器只有 3 核心，同時另一個代理也在跑 Minecraft 伺服器（port 2562x），所有耗時只能當量級參考（例如 init 同一份資料 1.21.11 用 4.5 s、26.2 用 8.2 s，差異主要來自當時負載）。Gradle 一律 `--max-workers=1`。
> 本報告由子代理產出、主對話存檔（子代理無法寫報告檔）。

## 1. 設計摘要

### 1.1 程式結構（Java 21，`src/main/java/wgproto/`）
| 檔案 | 內容 |
|---|---|
| `Nbt.java` | 最小 NBT 讀寫；compound 可依鍵排序輸出 |
| `Region.java` | Anvil .mca 讀寫：壓縮 1 gzip / 2 zlib / 3 無 / 4 LZ4（lz4-java，**未實測**）/ 外部 `.mcc`（僅讀）。寫回為整檔重寫，未改動的 chunk 沿用原壓縮資料 |
| `Layout.java` | 版本/目錄 adapter：`world/ + world_nether/DIM-1 + world_the_end/DIM1`（1.21.x Paper）或 `world/dimensions/<ns>/<path>/`（26.x）→ 統一成維度 id（`minecraft/overworld` 等）+ region/entities/poi 目錄 |
| `Codec.java` | 正規化 + 自訂二進位編碼 + zstd（zstd-jni level 6）|
| `Repo.java` / `Snapshot.java` | JGit bare repo；記憶體樹節點（惰性載入，只重寫有變動路徑）；`init`/`commit`；index 快取 |
| `Diff.java` / `RepoStats.java` | `diff`（section/方塊/BE/實體，實體以 UUID 為全域鍵）與 `stats` |
| `Restore.java` | `restore`（寫回 region/entities，可選刪 POI）|

### 1.2 git 樹與 blob
路徑 `<維度 id>/r.X.Z/c.X.Z/{s.Y.bin, biomes.bin, ticks.bin, structures.bin, entities.bin}`，根目錄 `world-meta`（占位：dataVersion、layout）。全空氣且無 block entity 的 section 不存（缺檔 = 空氣）。只追蹤 `Status == minecraft:full` 的 chunk（baseline 3823 個 chunk 中 698 個 full）。`structures.bin`（chunk 的 `structures`，空則不存）是額外加的：不存的話還原出新 chunk 時結構資訊會遺失。

### 1.3 section 格式（`s.Y.bin`，zstd）
```
u8 version=1 | varint 調色盤大小 | 每項: str name, varint 屬性數, (str key, str value)*（屬性依鍵排序）
u8 bits(=ceil(log2 n), n=1 時 0) | 緊密位元流 4096×bits（LSB first，不像 MC 那樣不跨 long）
varint block entity 數 | 每筆: varint 位置(x|z<<4|y<<8) + NBT（去掉 x/y/z、鍵排序）；依 y,z,x 排序
```
調色盤依 YZX 線性走訪「第一次出現」重編，並以規範字串（name+排序屬性）去重，未使用的項目丟棄。biomes：每個 section 一筆（含全空氣 section），4×4×4，同樣 first-appearance。ticks：`block_ticks/fluid_ticks` 依 (y,z,x,id,p,t) 排序（空則不存）。entities：每筆實體一份鍵排序 NBT，依 UUID 排序。

### 1.4 忽略/丟棄清單
- **chunk 層級**：`BlockLight`/`SkyLight`、`starlight.*`、`isLightOn`、`Heightmaps`、`LastUpdate`、`InhabitedTime`、`PostProcessing`、`Status`（僅用來篩 full）、`DataVersion`（只記在 world-meta）、`xPos/yPos/zPos`。
- **block entity**（依 id）：furnace/blast_furnace/smoker `lit_time_remaining, cooking_time_spent`；mob_spawner `Delay`；jukebox `ticks_since_song_started`；command_block `LastExecution, SuccessCount`；brewing_stand `BrewTime`；campfire `CookingTimes`；hopper `TransferCooldown`；所有 `Paper.*/Bukkit.*/Spigot.*`。容器內容保留。
- **實體**：`Paper.* / Bukkit.* / Spigot.* / WorldUUID*`；`Motion, FallDistance, fall_distance, OnGround, Air, Fire, PortalCooldown, HasTicked, TicksFrozen, HurtTime, HurtByTimestamp, DeathTime, FallFlying, current_impulse_context_reset_grace_time, Brain, Age, InLove, LoveCause, InWaterTime, DrownedConversionTime, PickupDelay, Time, LastRestock, RestocksToday, LastGossipDecay, Gossips, FoodLevel, sleeping_pos, life, inGround, shake, Fuse, start_interpolation, AngryAt, AngerTime, LeashDelay, WasOnFire`。`Rotation` 只對非靜態實體丟棄（盔甲座、展示框、畫、display、interaction、marker、NoAI 保留）。`attributes` 依 `id` 排序；`Passengers` 遞迴正規化並去掉內層 `Pos`。
- **未忽略、實測會製造實體變動**：`equipment.*.components.minecraft:damage`（殭屍頭盔日曬磨損）、`Health`、`Item`（掉落物合併堆疊）。建議列入可設定忽略表。

### 1.5 黏性位置
commit 時：對「時間戳改變的 entities chunk」取新紀錄；從 HEAD 讀這些 chunk 及 8 鄰居的 `entities.bin`，建 `UUID → 紀錄` 候選表；同 UUID 且「正規化後除 `Pos` 外位元組相同、位置差 ≤ tol（歐氏，預設 2）」就沿用 HEAD 整筆紀錄（含舊位置與舊所在 chunk），否則寫新的。Pos 精確比對的實體：`NoAI=1` 與靜態類型。移動超過 tol 後錨點才更新，不會累積漂移。`--tol 0` 關閉。限制：候選表只看 3×3 鄰居。

### 1.6 index 與增量 commit
index（`<repo>.index`）記錄每個 chunk 的 region 時間戳 / entities 時間戳 / 是否 full，綁定 HEAD commit id。只重算時間戳改變的 chunk，未變 chunk 重用 HEAD 的 tree。掃描 3823 個 region 標頭約 0.4–0.8 s。沒有任何 blob 差異則不產生 commit（`--allow-empty` 可強制）。

### 1.7 restore
`restore <serverRoot> <repo> <rev> <dim> cx1 cz1 cx2 cz2 [--poi keep|delete]`：範圍內每個 chunk 以 blob 重建 `sections`（僅方塊與 biome，無光照）、`block_entities`、ticks、`structures`；活世界已有該 chunk 則在其上覆蓋（保留其他頂層欄位），並移除 `Heightmaps`、`starlight.light_version`，設 `isLightOn=0`。範圍內 chunk 的 entities 整個取代，並以 UUID 掃描整個維度 entities 檔移除範圍外同 UUID 舊實體。`--poi delete` 刪範圍內 chunk 的 POI。

## 2. 結果

### T1 穩定性
流程：baseline 複本 → init → 無玩家伺服器 → `save-all flush` → stop → commit；同時維護 repo A（tol 0）與 B（tol 2）。

伺服器只在 chunk 載入且變髒時才寫檔：restart0（無載入）**0 個 chunk 被重寫，commit 為空**（兩版）。所以其餘重啟以 forceload 載入 overworld 441 個 chunk + nether/end 各 32×32 格，跑 60 秒。

**T1a 預設 gamerule**：伺服器重寫 697 個 chunk；方塊變動 2126（1.21.11）/ 2231（26.2）、BE 1，**全為真實演化**：昆布成長（約 800 格）、岩漿流動（worldgen 遺留 pending fluid tick，約 340–385 格 air→lava 等）、紫水晶芽成長、場景熔爐開始燒煉。

**T1b（`random_tick_speed=0`）**：
| 步驟 | 重寫 chunk | 變動 section | 方塊變動 | BE | 說明 |
|---|---|---|---|---|---|
| restart0 無載入 | 0 | 0 | 0 | 0 | |
| restart1（1.21.11 / 26.2）| 697 | 21 / 23 | 464 / 535 | 1 | 剩餘岩漿流動 + 熔爐燒煉 |
| restart2（加 churn）| 697 | 3 / 5 | 5 / 16 | 0 | churn = 場景區空氣/石磚/泥土換成綠寶石塊再換回。剩下皆為真實後果：岩漿、紅石燈熄滅、小麥被遮光掉落 |
| **restart3** | 697 | **0** | **0** | **0** | biome/ticks/structures 也為 0（兩版）|

結論：**方塊與 BE 假 diff = 0**。另做全量 round-trip（init commit 的 overworld 626 個 full chunk restore 回乾淨 baseline 複本 → 重新 commit）對 init diff 全為 0。

**實體變動（restart3，同狀態 tol 0 vs tol 2）**：1.21.11：26 → 7；26.2：36 → 10。restart2（1.21.11）：30 → 10 modified。T1a：30/38 → 20/15。
剩下的是：(1) 自然生物（溺屍、zombie_nautilus、牛）60 秒走超過 2 格（Pos 差 3–16）——真實移動；(2) 水中掉落物漂動與合併；(3) NoAI 殭屍頭盔 `damage` 15→106（日曬磨損，每次重啟都變，須加忽略）；(4) 自然刷出/消失的掉落物。黏性 tol 2 把 Pos-only 變動減少約 60–75%，沒有位置抖動造成的假 diff。取捨：被黏住的紀錄所在 chunk 可能與實際位置不同，還原時要以 Pos 重新分配。

### T2 精準度（兩版全 PASS）
| 操作 | 預期 | 實際 |
|---|---|---|
| setblock 1 格 | 1 section | sections 1、blocks 1 |
| 同一指令再下一次 | 無變動 | 沒有 commit |
| `fill 0 155 0 20 175 20 sea_lantern` | 8 section、9261 格 | 8、9261 |
| 放箱子 | 1 section、1 方塊、1 BE | 吻合 |
| 只改箱子內容 | 1 section、0 方塊、1 BE | 吻合 |

### T3 量測（698 個 full chunk；baseline 18.4 MB，含 3125 個不追蹤的非 full chunk，非同口徑）
| 項目 | 1.21.11 | 26.2 |
|---|---|---|
| init 耗時 | 4.5 s | 8.2 s（負載）|
| init 物件數 | 6237 loose（5523 section、698 biomes、84 structures、54–60 ticks、30–32 entities、約 715 trees、1 commit、1 world-meta）| 6245 |
| loose 位元組 | 3,057,741 B（`du` 25,984 KB）| 3,059,547 B |
| gc 後 pack | 3,185,912 B（1 個 pack，gc 3.4 s）| 3,187,968 B（4.4 s）|
| section blob（zstd，唯一）| min 34、p50 485、p90 816、p99 1207、max 2291 B；合計 2.71 MB（正規化原始 11.2 MB → 4.1×）| 相同量級 |
| section 去重 | 5523 → 5337 unique = 3.4% | 相同 |
| biomes / structures 去重 | 89.7% / 67.9% | 相同 |
| 增量 commit 新增 | setblock 5.7 KB/9 物件；fill 8 section 8.9 KB/17；箱子 6.9 KB/9；近空 5.3 KB/7 | 4.9 / 9.4 / 6.9 / 5.3 KB |
| 增量 commit 耗時 | 0.8–2.3 s | 0.7–1.2 s |

- 兩版地形相同（同 seed），section 大小與雜湊幾乎一致。
- gc 後 pack 僅比 loose 大 4%（zstd 後 blob 不可再壓縮）。是否改存未壓縮正規化資料讓 git 的 zlib+delta 處理，尚未比較。
- 每個 commit 至少約 5 KB（樹層級與 commit 物件的固定成本）。

### T4 寫回（兩版皆通過）
情境：T2 修改 + 拆掉 (7,150,24) 的講台、新增 (12,150,28) 的講台後，把 init commit 的 overworld chunk -2..4 × -2..3（42 chunk）restore；伺服器重啟（`tick freeze`、forceload）→ console 檢查 → `save-all flush` → 讀 region。

| 檢查 | poi=keep | poi=delete |
|---|---|---|
| 方塊還原（金塊、海燈籠區、箱子、講台 has_book、熔爐、木桶、岩漿）| 全 ✓ | 全 ✓ |
| 伺服器 log ERROR | 0 | 0 |
| restore 範圍內對 init diff | 僅 1 個 section、0 方塊變動（熔爐 BE：載入後恢復燃燒耗 1 燃料）| 同 |
| 光照：restore 後檔案無 SkyLight/BlockLight/starlight | ✓ | ✓ |
| 伺服器載入+存檔後：chunk (1,1) 5 section 有 SkyLight、6 有 BlockLight，`starlight.light_version=10`；岩漿格 BlockLight=15、上空 SkyLight=15；與 baseline 光照 **45056/45056 nibble 相同** | ✓ | ✓ |
| POI | **過期**：仍是 `librarian (12,150,28)`，原 `(7,150,24)` **未重建** | **正確重建**：`librarian (7,150,24)`、fisherman、home，無 (12,150,28) |

結論：(1) 移除 `BlockLight/SkyLight/starlight.*/Heightmaps` 足以讓 Paper 載入時重算光照，結果與原本相同；Paper 存檔的 `isLightOn` 一律為 0（baseline 也是 0），它靠 `starlight.*`。(2) POI `keep` 是錯的，`delete` 由方塊重建（兩版驗證）。(3) 缺少 `Heightmaps` 的 chunk 可正常載入；biome `data` 位元數用 `ceil(log2 n)` 伺服器接受。

## 3. 發現的問題
1. 無載入的重啟不寫任何檔；穩定性測試必須強迫載入 chunk。
2. 世界本身不是靜態的（昆布、紫水晶、岩漿、熔爐）：`status` 會看到大量真實方塊變動，自動 commit 需過濾。
3. ticks 的 `t` 易變；建議只在紅石區保留或量化 `t`。
4. 1.21.11 gamerule 已是 snake_case（`random_tick_speed`）。
5. `tick freeze` 沒有完全凍結熔爐。
6. 26.2 restart3 log 有 `PaperVersionFetcher ... Read timed out`（更新檢查逾時，無關）。
7. 非 Paper 來源未測；`structures.bin` 與 DataVersion（僅 world-meta 一份，各 chunk 可能不同）需定案。

## 4. 對正式 core 的建議
- 正規化策略成立（first-appearance 調色盤 + 緊密位元流）。
- 實體：黏性比較優於量化；忽略表要加 `equipment…damage`、`Health`、掉落物 `Item` 合併；靜態類型與 NoAI 精確比對並保留 Rotation；預設 `.wgignore` 範本提示 `entity * !persistent`；UUID 候選表大世界需索引。
- POI 不存、restore 時刪範圍內 POI；光照/Heightmaps 丟棄，restore 清掉 `starlight.*`。
- index 用 region 時間戳 + entities 時間戳 + HEAD id 足夠；掃 3823 個標頭 0.4 s。
- 儲存：section 幾乎無去重（3.4%），空氣不存才是主要節省；pack 後 3.2 MB（698 chunk）。
- restore：整檔重寫 region 對大 region 昂貴，應就地更新 sector；需補 `.mcc` 寫入、LZ4、大範圍實體去重。
- bed block entity（26.2 無、1.21.11 有）的跨版本處理未實作。

## 5. 未完成 / 限制
- 未測 LZ4、`.mcc` 大 chunk、Folia、非 Paper 世界。
- T4 只測 overworld 附近 42 個 chunk；POI 只驗證 lectern/barrel/bed，未用村民驗證尋路。
- 光照重算是否僅因 `isLightOn=0` 還是缺少 starlight 欄位未單因子對照。
- 實體還原只看檔案與 diff，沒在遊戲內逐一檢視；每階段僅跑 60 秒，無長時間漂移測試。
- 耗時受同機另一代理影響。

## 6. 如何重跑
- 建置：`gradle --max-workers=1 --no-daemon jar`
- 全部：`python3 scripts/run_tests.py 1.21.11 26.2`（每版約 15 分鐘，詳見 `README.md`）
- restore 範圍比對：`python3 scripts/range_diff.py <ver> keep|delete`
