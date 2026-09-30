# Phase 0 / 00-env 報告：測試環境與 1.21.11 vs 26.2 存檔差異

> 測試日期 2026-09-30。`eula=true` 僅為本機測試（伺服器綁 127.0.0.1、online-mode=false，用完即關）。
> 原始數據：`.work/worlds/<ver>/{inspect,scene}.json`、`.work/compare.md`。

## 1. 環境與建置紀錄

| 項目 | paper-1.21.11 | paper-26.2 | folia-1.21.11 | folia-26.2 |
|---|---|---|---|---|
| build | 132（STABLE） | 129（STABLE） | 14（STABLE） | 7（**BETA**，無 stable） |
| Java（jar 內 version.json） | 21 | **25** | 21（實測可啟動） | 25（實測可啟動） |
| world_version（DataVersion） | 4671 | 4903 | — | — |
| protocol | 774 | 776 | — | — |
| 冒煙/建置啟動至 Done | 48–63 s（含首次下載/remap） | 34.5 s | 42.4 s | 29.0 s |
| 端口 | 25601 | 25602 | 25603 | 25604 |

- 世界建置（Paper）：1.21.11 共 ~200 s（啟動 63 s、場景+預生成 74 s，其餘為 save/stop）；26.2 共 ~147 s（啟動 34.5 s、場景+預生成 58 s）。3 核心機器。
- 預生成：forceload 覆蓋 chunk -10..10（441 chunks）+ 場景區；overworld 最終 2255 個 chunk 進 region 檔，其中僅 626 為 `minecraft:full`，其餘為鄰接的 `structure_starts/biomes/carvers/initialize_light` 半成品 chunk（重要：**baseline 中大量 chunk 不是 full**）。Nether/End 各生成 784 chunks（36 full）。
- 世界大小：baseline 各約 18 MB（1.21.11：18,423,391 B；26.2：18,493,705 B）；server 目錄含 cache/libraries 各約 250 MB。
- Folia 只做冒煙（啟動到 Done 再 stop），未跑場景。Folia 26.2 為 BETA build。Folia 1.21.11 佈局與 Paper 1.21.11 相同（world / world_nether / world_the_end），Folia 26.2 與 Paper 26.2 相同。
- 場景 `scene.txt`（115 行）在**兩版都沒有任何指令失敗**（`command-log.txt` 完整記錄）。1.21.11 的 SNBT 語法（item `count`、components、text component 字串）在 26.2 全部照用。第一次嘗試時 fill 因 chunk 尚未載入失敗，已改成先 forceload 並輪詢 `execute if loaded`。
- 未做：沒有玩家連線，因此 `playerdata/`（1.21.11）與 `players/data/`（26.2）皆為空，玩家存檔差異**未盤點**。

## 2. 目錄結構

### 1.21.11（Paper）：三個獨立世界資料夾
```
world/                 level.dat, level.dat_old, session.lock, uid.dat, paper-world.yml, datapacks/
  region/ entities/ poi/          （overworld）
  data/    chunks.dat raids.dat random_sequences.dat scoreboard.dat stopwatches.dat world_border.dat
  playerdata/                     （<uuid>.dat）
world_nether/          level.dat, uid.dat, paper-world.yml
  DIM-1/{region,entities,data/}   （無 poi：未生成村民/傳送門）
world_the_end/
  DIM1/{region,entities,data/}    data 含 raids_end.dat
```
（原版 vanilla 也是 `world/DIM-1`、`world/DIM1`；Paper 把 nether/end 拆成獨立資料夾，並在其內保留 `DIM-1`/`DIM1` 子資料夾。）

### 26.2（Paper）：單一世界資料夾 + 維度資料夾
```
.paper/version_history.json
world/                 level.dat, level.dat_old, session.lock, datapacks/
  players/data/                    （原 playerdata；另有 players/ 下其他子目錄的可能，未見）
  data/minecraft/      custom_boss_events.dat random_sequences.dat scoreboard.dat stopwatches.dat world_clocks.dat
  dimensions/minecraft/{overworld,the_nether,the_end}/
      region/ entities/ poi/(僅 overworld) paper-world.yml
      data/minecraft/  chunk_tickets.dat game_rules.dat raids.dat scheduled_events.dat weather.dat
                       world_border.dat world_clocks.dat world_gen_settings.dat（end 另有 ender_dragon_fight.dat）
      data/paper/      level_overrides.dat metadata.dat persistent_data_container.dat
```
- 維度路徑規則：`dimensions/<namespace>/<path>/`（所以自訂維度 `foo:bar` 會在 `dimensions/foo/bar/`）。`uid.dat` 不見了。
- 全域資料（scoreboard、random_sequences、stopwatches、boss events、world_clocks）在 `world/data/minecraft/`，**每個維度**又各有 `game_rules/weather/world_border/raids/scheduled_events/chunk_tickets/world_clocks/world_gen_settings`。
- 檔名加了命名空間目錄（`data/minecraft/…`、`data/paper/…`）；`chunks.dat` 更名為 `chunk_tickets.dat`（內容 `{DataVersion, data:{}}`）；`raids_end.dat` 併為各維度的 `raids.dat`。

### level.dat 差異（`Data` 複合標籤）
- 兩版 DataVersion：4671 / 4903；兩版 `Data.Version = {Name, Series, Snapshot, Id}`。兩版都有 `Data.spawn = {pos, yaw, pitch, dimension}`（取代舊的 SpawnX/Y/Z）。
- 26.2 **移出** level.dat 的欄位：`game_rules`（→ 各維度 `game_rules.dat`，鍵仍是 `minecraft:xxx` 蛇形）、`WorldGenSettings`（→ `world_gen_settings.dat`）、`DragonFight`（→ `ender_dragon_fight.dat`）、`CustomBossEvents`、`ScheduledEvents`、`DayTime`（→ `world_clocks.dat` 的 `total_ticks`）、`raining/rainTime/thundering/thunderTime/clearWeatherTime`（→ `weather.dat`，且改為 snake_case：`rain_time` 等）、`WanderingTrader*`、`hardcore/Difficulty/DifficultyLocked`（→ `Data.difficulty_settings{difficulty,hardcore,locked}`）。
- 對 WorldGit：`world-meta` blob 的來源在 26.2 需從多檔組裝；建議 WorldGit 自訂統一的 world-meta schema（gamerule/spawn/weather/clock），由各版 adapter 讀寫。

## 3. Chunk NBT（region/*.mca，壓縮皆為 zlib=2）

**結論：chunk 層級的頂層欄位、section 欄位、block_states/biomes 結構、Heightmaps 鍵，在 1.21.11 與 26.2 完全相同（鍵與 NBT 型別逐項比對，差異為零），唯一差別是 `DataVersion` 4671 → 4903。**

頂層欄位（兩版相同）：`DataVersion:int`、`xPos/zPos/yPos:int`、`Status:string`、`LastUpdate:long`、`InhabitedTime:long`、`isLightOn:byte`（僅 full chunk 有）、`sections:list<compound>`、`block_entities:list`、`block_ticks/fluid_ticks:list`、`PostProcessing:list<list>`、`structures:{References,starts}`、`Heightmaps:compound`、`carving_mask`（部分 proto chunk）、`entities`（proto chunk 才有內容）。

Section（兩版相同）：`Y:byte`、`block_states:{palette:list<compound{Name,Properties?}>, data?:long[]}`、`biomes:{palette:list<string>, data?:long[]}`、`BlockLight:byte[]`、`SkyLight:byte[]`。
Heightmaps：`MOTION_BLOCKING`、`MOTION_BLOCKING_NO_LEAVES`、`WORLD_SURFACE`、`OCEAN_FLOOR`（full chunk；proto chunk 為 `*_WG`），皆 long[]。

**Paper 自加欄位（兩版皆有，非原版）**：chunk 頂層 `starlight.light_version:int`（值 10）；section 內 `starlight.skylight_state:int`、`starlight.blocklight_state:int`（值 2 = 光照已計算）。WorldGit 必須把 `starlight.*` 視為光照相關欄位一併丟棄，否則會製造 Paper 專屬的假 diff；且**不要假設輸入一定來自 Paper**（Fabric/原版沒有這些欄位）。

### 準備丟棄／特別處理的欄位對照

| 欄位 | 1.21.11 | 26.2 | 建議 |
|---|---|---|---|
| `BlockLight`/`SkyLight`（section） | 有（僅 full chunk 部分 section） | 有 | 丟棄；還原時設 `isLightOn=0` 讓伺服器重算 |
| `starlight.*`（Paper） | 有 | 有 | 丟棄 |
| `isLightOn` | byte，僅 full chunk | 同 | 丟棄/寫 0 |
| `Heightmaps` | 4 個（full） | 同 | 丟棄，載入時重算（或還原時保留缺省） |
| `Status` | `minecraft:full` 等 | 同 | 只追蹤 full chunk；非 full 要另行決策（見 §7） |
| `LastUpdate` / `InhabitedTime` | long | long | 丟棄（每次存檔都變）；InhabitedTime 影響區域難度，可選擇保留 |
| `block_ticks`/`fluid_ticks` | list<compound>（含 `t`,`i`,`x,y,z`,`p`） | 同 | 依 docs 的 `ticks` blob 可選保留；比較時注意水流會製造 tick |
| `PostProcessing` | list<list> | 同 | 丟棄（非 full 才有意義；full chunk 為空 list of 24 個空 list） |
| `structures` | `{References,starts}` | 同 | 保留（結構資料影響 `/locate`），但體積可觀 |
| `DataVersion` | 4671 | 4903 | 每個 chunk 都帶；commit meta 記 `mcDataVersion`，blob 內部統一以升級後版本或原版本+標記 |

## 4. 實體與 entities/ 檔

- `entities/r.X.Z.mca` 兩版結構相同：每個 chunk 為 `{DataVersion:int, Position:int[2], Entities:list<compound>}`，`Position` = [chunkX, chunkZ]（絕對 chunk 座標）。chunk 內 `entities` 欄位在 region 檔只存於 proto chunk。
- 1.21.11 中實體 DataVersion 也獨立為 4671（entities 檔有自己的 DataVersion，須各自升級）。
- `poi/`：`{DataVersion, Sections:{"<y>":{Valid, Records}}}`，兩版相同；僅 overworld 有（場景中有講台、床等 POI 才寫入）。
- Paper 自加的實體欄位（兩版皆有）：`Paper.SpawnReason`、`Paper.Origin`、`Paper.OriginWorld`、`WorldUUIDMost/Least`、`Bukkit.updateLevel`、`Bukkit.Aware`、`Spigot.ticksLived`。WorldGit 應在正規化時剝除 `Paper.*`、`Bukkit.*`、`Spigot.*`、`WorldUUID*`（`WorldUUID*`/`Paper.OriginWorld` 會綁定世界 UUID，跨世界還原後會不一致）。

### 實體欄位差異（場景固定實體，逐欄比對，共 13 種 + 天然生成實體）
| 實體 | 僅 1.21.11 | 僅 26.2 |
|---|---|---|
| armor_stand、zombie、villager、cow、pig、drowned、zombie_nautilus（所有 LivingEntity） | `HurtByTimestamp:int` | `current_impulse_context_reset_grace_time:int` |
| cow、pig | — | `sound_variant:string`（`minecraft:classic`） |
| villager | — | `VillagerDataFinalized:byte` |
| zombie_nautilus | `Age`、`AgeLocked`、`ForcedAge` | — |
| block_display、item_display、text_display、item_frame、painting、minecart、chest_minecart、item、experience_orb、falling_block | 無差異 | 無差異 |

其他：`attributes` 列表的**順序**在兩版不同（同一個實體，1.21.11 是 armor, movement_speed, armor_toughness；26.2 是 movement_speed, armor_toughness, armor）——**列表順序不穩定，正規化時必須依 `id` 排序**。UUID 為 int[4]，兩版相同。Item 格式（`id`,`count`,`components`）、Offers、display 的 `transformation`、`equipment`（1.21.5+ 的 equipment 複合）兩版完全一致，SNBT 與 NBT 型別（包含 byte/short 後綴）相同。

## 5. Block entity 差異

12 種（1.21.11）/ 11 種（26.2）：banner、barrel、bed、brushable_block、chest、command_block、comparator、furnace、jukebox、lectern、mob_spawner、sign。
- **唯一結構差異：`minecraft:bed` block entity 在 26.2 消失**（1.21.11 場景中的床在 `block_entities` 有兩筆，內容只有 `id,x,y,z,keepPacked,components`，無實質資料；26.2 沒有）。方塊 palette 中 `minecraft:red_bed[facing,part,occupied]` 兩版相同 → 轉換層對 1.21.11 → 26.2 應丟棄 bed BE，26.2 → 1.21.11 應補一個空的 bed BE（否則 1.21.11 可能載入時補回；未實測）。
- 其餘 BE（chest/sign/banner/lectern/spawner/furnace/barrel/comparator/command_block/jukebox/brushable_block）的欄位鍵與型別完全一致。furnace 的 `lit_time_remaining/cooking_time_spent` 是隨時間變化的值（`107s` vs `15s` 為時間差，不是格式差異）。
- 兩版 BE 皆有 `keepPacked:byte` 與 `components:compound`（空）；`brushable_block`（場景中天然出現的可疑沙/砂礫）兩版皆有。
- sign：`front_text/back_text{messages:list<string>, color, has_glowing_text}`、`is_waxed`；lectern 書本內 `written_book_content.pages` 元素是 `{raw:"..."}` 形式，`title` 為 `{raw:...}` —— 兩版一致。

## 6. 方塊狀態／ID

場景 y=150..151 的 302 個非空氣方塊（`dump_scene.py`）與整個 overworld palette：**兩版的方塊名稱與屬性鍵完全相同，沒有改名、沒有新增屬性，逐格比對零差異**（包含樓梯 shape/waterlogged、門 hinge/half、紅石線 power/side、床 part/occupied、雪 layers、水 level 等）。因此 1.21.11 → 26.2 的方塊層轉換在本場景涵蓋範圍內是恆等映射；但這只涵蓋場景中的方塊，全量 DataFixer 對照表（例如 26.x 新方塊）仍需另行以 26.2 新增方塊清單驗證，且只驗證了兩個相鄰版本（4671 → 4903）。

## 7. 對 WorldGit 的影響與建議

1. **Chunk/section 層可用同一套解析器**：4671 與 4903 的 chunk 結構零差異；轉換層（若有）應以 `DataVersion` 分派，而不是版本字串。每個 chunk/實體檔各自帶 DataVersion，須各自檢查。
2. **檔案佈局必須抽象成 `WorldLayout` adapter**：以 `level.dat` 的 `Data.DataVersion` 或有無 `world/dimensions/` 判斷。1.21.11（含 Paper 的 `world_nether/DIM-1`）與 26.2（`world/dimensions/minecraft/<dim>`）的維度路徑、`playerdata`↔`players/data`、`data/*.dat` 的位置與檔名都不同；WorldGit 儲存後端應以維度 id（`minecraft:overworld`）為鍵，不記錄實體路徑。
3. **level.dat / data/*.dat 的 world-meta 需 adapter**：26.2 把 gamerule、天氣、時鐘、龍戰、難度、worldgen 設定拆進多個檔案。建議 world-meta 用 WorldGit 自己的 schema。
4. **正規化清單新增**：`starlight.*`、`Paper.*`、`Bukkit.*`、`Spigot.*`、`WorldUUIDMost/Least`、`HurtByTimestamp`、`current_impulse_context_reset_grace_time`（跨版本假 diff 來源）；`attributes` 依 `id` 排序；`LastUpdate/InhabitedTime`、光照、Heightmaps、`isLightOn`、`PostProcessing` 依 docs 丟棄。
5. **bed block entity**：跨版本切換時（26.2 無、1.21.11 有）需特別處理，否則 diff 會在版本升降時出現整批「床 BE 新增/刪除」。
6. **非 full chunk**：預生成後 overworld 只有 28% chunk 為 full，其餘 `Status` 為 `structure_starts/biomes/carvers/initialize_light`。WorldGit 應決定：僅追蹤 `full` chunk，或整包保存 proto chunk（建議只追蹤 full，proto chunk 由世界生成器重生，但注意種子相同也可能因版本不同生成不同地形）。
7. **Folia 與 Paper 存檔格式相同**（Folia 26.2 為 BETA），冒煙啟動兩版正常；Folia 的 region 執行緒模型不影響檔案格式，但 WorldGit 在 Folia 上讀寫 chunk 需走 region scheduler（另行實驗）。
8. Java 需求：1.21.11 → Java 21，26.2 → Java 25；WorldGit 插件/模組的 Java 目標版本需分別建置（或 Java 21 bytecode 在 25 執行仍可）。

## 8. 限制與誠實紀錄
- 只比較了 Paper 所產生的世界（含 Paper/starlight 自加欄位）；原版/Fabric 產生的存檔沒有這些欄位，未測。
- 沒有玩家資料（無連線）。
- 天然生成的實體（drowned、pig、掉落物、falling_block 等）是自然噪音；兩版數量不同不代表格式差異。
- 兩版地形不同（同種子跨版本不保證一致），所以整個 chunk 的方塊內容不能直接比較，只比較了場景座標。
- `compare_worlds.py` 比對的是欄位鍵集合與型別，並以 `dump_scene.py` 補值比對；未逐位元組比對 section 資料，因為地形不同。
- 第一次建置嘗試中 `fill` 因 chunk 未載入而失敗（已修正）；build_world.py 的錯誤偵測靠關鍵字，最終兩版 `failed_commands` 皆為空，並以 dump 檢查場景實際存在（302 方塊、13 實體）。
