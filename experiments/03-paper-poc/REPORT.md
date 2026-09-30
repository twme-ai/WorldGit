# Phase 0 / 03-paper-poc 報告：插件端變動偵測與線上替換 section

> 測試日期 2026-09-30。全部在本機 127.0.0.1（online-mode=false，用完即關），世界為 [00-env](../00-env/REPORT.md) 的 baseline 複本。
> 程式與重跑方式見 [README.md](README.md)。本報告由子代理產出、主對話存檔（子代理無法寫報告檔）。原始結果在 `.work/paper-poc/*.jsonl` 與 `*.txt`。
> **一個 jar（`worldgit-paper-poc.jar`，Java 21 bytecode）同時跑在 Paper 1.21.11、Paper 26.2、Folia 1.21.11、Folia 26.2 四個平台**，下文以 P / F 加版本簡稱（P1.21、P26.2、F1.21、F26.2）。

## 0. 結論摘要

| 問題 | 結論 |
|---|---|
| docs/04 §2 的「unsaved 旗標 ∪ region 時間戳」能不能不漏抓？ | **方塊與 block entity 的變動成立**。22 種來源中，所有走 `LevelChunk.setBlockState / setBlockEntity / markUnsaved` 的來源都設旗標，存檔後旗標清掉、region 時間戳更新。**但有 6 個但書必須寫進設計**：①要讀 `ChunkAccess.unsaved` 原始欄位，不是 `LevelChunk.isUnsaved()`；②改一格會連帶把最多 8 個鄰居 chunk 也設旗標（光照外溢）；③實體走獨立 storage，沒有任何旗標，entities 檔時間戳對有實體的 chunk 每次存檔都更新，兩者都不能當實體變動訊號；④直接動 `LevelChunkSection` 而不呼叫 `markUnsaved()` 的寫入，旗標與時間戳都看不到（伺服器也不會存它）；⑤furnace 進度等 BE 內部 tick 狀態不穩定地設旗標；⑥時間戳只有秒解析度，同一秒內兩次存檔會撞（6 次測試中 4 次撞）。 |
| 誤報 | 有 bot 在場、閒置 10 個 chunk 觀察 120 秒，約 7–8 個會被設一次旗標，與實體種類無關，空 chunk 也會。抽樣磁碟 NBT：有的是天然世界真的在動，有的只有光照，有的**只有 InhabitedTime/LastUpdate 變**。旗標只能當候選，要靠內容雜湊過濾（docs 已是這個設計）。 |
| FAWE / WorldEdit | `EditSessionEvent` 包 Extent 可行且拿得到 actor。但 FAWE 預設會靜默丟掉非白名單的第三方 Extent（要設 `extent.allowed-plugins`）。FAWE 的 `//set`、`//replace` 看不到逐格，只看到 bulk 方法；要靠 `IBatchProcessor` 才看到 chunk 層級的實際寫入格數。純 WorldEdit（Folia 上用）逐格都看得到。 |
| 封包監聽 | PacketEvents 四平台都能用。沒玩家在附近就 0 個封包（旗標照常）；同一變動對 N 個玩家送 N 份（3 玩家實測 3 份）；NMS 直寫 chunk、biome、容器內容、實體、chunk PDC 都沒有方塊封包。只適合當即時性補充。 |
| section 線上替換 | **成功**（四平台）。bot 驗證方塊與伺服器逐格一致、伺服器端光照重算、BE 移除/重建、無錯誤 log。單 section 約 2.4–16 ms。還原後連 BE NBT 的雜湊都與原本相同。**通知玩家要明確做**，不通知 bot 看到舊畫面。 |
| 未載入 chunk 走 chunk IO | 可行（四平台皆成功），**但有競態陷阱**：chunk 系統裡已有該 chunk 的 holder 時，寫回的資料被記憶體版本蓋掉（實測重現）。建議改用「加 ticket 載入 → 記憶體替換 → 釋放 ticket」。 |
| Folia | 偵測與替換都在正確執行緒上跑，**沒有執行緒檢查錯誤**。坑：沒有 `/save-all`、`LevelChunk.isUnsaved()` 在非 region 執行緒會 NPE、`teleport` 要改 `teleportAsync`、`World.save()` 不可用、FAWE 不支援 Folia。 |
| 玩家保護 | 「事件取消」與「Resistance V 藥水」都有效。建議事件取消。 |
| 最大風險 | ①26.2 與 1.21.11 之間 NMS 有細部簽章差異（本 PoC 被執行期打到 3 處），需要 CI 做二進位相容檢查；②`isUnsaved()` 語意被 Paper/Folia 改過；③實體變動沒有廉價訊號；④Folia 26.2 仍是 BETA。 |
| 未完成 | paperweight-userdev 沒實際試；mineflayer 連 26.2 靠 hack；沒測 `ChunkUnloadEvent`/`WorldSaveEvent`；客戶端光照無法用 mineflayer 驗證；FAWE 的 `//regen`、筆刷、schematic 沒測；POI 只確認呼叫成功；替換 section 內的實體處理與殘留排程 tick 未驗證；F26.2 沒跑玩家保護。 |

## 1. 環境、版本與方法

### 1.1 版本與第三方元件
| 項目 | 版本 / 來源 |
|---|---|
| Paper 1.21.11 | build 132（Java 21），見 00-env |
| Paper 26.2 | build 129（Java 25） |
| Folia 1.21.11 | build 14 |
| Folia 26.2 | build 7（**BETA**） |
| PacketEvents | 2.14.0（spigot）`https://github.com/retrooper/packetevents/releases/download/v2.14.0/packetevents-spigot-2.14.0.jar`（sha256 前綴 060087c5）。release note 寫「新增 26.3 支援」，26.2 與 1.21.11 都正常。 |
| FastAsyncWorldEdit | 1.21.11 用 **2.15.0**（`https://cdn.modrinth.com/data/z4HZZnLr/versions/mHtmqIig/FastAsyncWorldEdit-Paper-2.15.0.jar`）；26.2 用 **2.15.4**（`.../versions/5TOYHuQr/FastAsyncWorldEdit-Paper-2.15.4.jar`）。兩版都有官方 build。**FAWE 沒有 Folia 版。** |
| WorldEdit（純） | 26.2 用 7.4.5（`https://cdn.modrinth.com/data/1u6JkXh5/versions/F5ea2ov3/worldedit-bukkit-7.4.5.jar`，有 Folia loader）。**7.4.5 的 class version 是 69（Java 25），1.21.11（Java 21）載入失敗**，1.21.11 改用 7.4.2（`.../versions/p8T2aZ8U/worldedit-bukkit-7.4.2.jar`）。 |
| ViaVersion | 5.12.0，只用來試「26.1 客戶端連 26.2」，**失敗**（見 1.3） |
| mineflayer | 4.39.0（npm latest）；minecraft-data 3.117 |
| 編譯用 API | `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT` |

### 1.2 NMS 存取：paperweight-userdev vs 反射 vs「直接對 Mojang 名稱 jar 編譯」
Paper 自 1.20.5 起執行期是 Mojang 名稱，26.x 原版不混淆，所以兩版的 NMS 類別與方法名稱基本相同。本 PoC 採用第三種做法並測它的邊界：

- **做法**：`compileOnly(files(.work/servers/paper-1.21.11/versions/1.21.11/paper-1.21.11.jar))`（Paper 解出的 Mojang 名稱 patched server，含 NMS 與 Moonrise），只對 1.21.11 編譯。jar manifest 加 `paperweight-mappings-namespace: mojang`，否則 Paper 1.21.11 會把插件當 Spigot 名稱去 remap。**同一份 class 直接在 26.2 載入。**
- **優點**：零額外工具（不用 dev bundle、不用 remap），型別安全，一個 jar 兩版通用。實測 98 個 NMS/Moonrise/CraftBukkit 引用中，只有 3 處在 26.2 有差異（§9）。
- **缺點**：只有對 1.21.11 做編譯期檢查，26.2 的差異只會在執行期炸（`NoSuchMethodError` / `IllegalAccessError`），本 PoC 就被打到兩次。所以寫了 `tools/check_binary_compat.py`：把 jar 的 NMS 引用逐一對另一版 server jar 查（成員存在且 public）。**正式專案的 CI 必須有等價檢查。** 欄位被改 private（`LevelChunkSection.states`、`ChunkPos.x`）就改用 public getter；改名的方法（`getLightBlock → getLightDampening`）用 `MethodHandle` 在 class init 擇一綁定（`Nms.lightDampening`）。
- **反射**：只在「改名」與「讀 private 欄位」時用（`VarHandle` 讀 `ChunkAccess.unsaved`、`MethodHandle` 綁 light dampening）。
- **paperweight-userdev（每版一個轉接模組）**：**沒有實際試**。對正式插件仍建議 `core` 不碰 NMS，`adapter-v1_21_11`、`adapter-v26_2` 各自用 userdev 編譯，差異在編譯期就現形。PoC 的「單 jar 兩版」只適合差異小的範圍。
- **Java 目標**：Java 21 bytecode 在 Java 25（26.2）照跑，不需另編 Java 25。

### 1.3 測試用 bot
- 封包只送給附近玩家，所以用 mineflayer（離線模式）連線。1.21.11 用 `version: '1.21.11'`。
- **mineflayer 4.39.0 只支援到 26.1（協定 775），不支援 26.2（776）。** 先試 ViaVersion 5.12.0：它偵測到 `client 26.1 (775) / server 26.2 (776)`，但 protocol pipeline 只有 base protocol，沒有 26.1→26.2 的轉譯，伺服器回「Incompatible client」，放棄。
- 改成 **hack**：把 `.work/bot/node_modules` 裡的 minecraft-data、minecraft-protocol、prismarine-chunk、prismarine-physics 補上 `26.2` 條目，全指向 26.1 資料，只把協定號改成 776。bot 能連、看方塊、放置、挖掘、開箱。**bot 端方塊名稱與伺服器逐格一致**（P1 圖樣 3475 格、還原圖樣都對得上）。
- 限制：bot 讀出的「光照值」與伺服器完全對不上（1.21.11 原生也不對，所以不是 hack 造成），**客戶端光照沒有驗證**（§10）。hack 只在 `.work/` 內，不影響專案。

### 1.4 測試架構
插件暴露 `/wgpoc <子指令>`，結果以一行 JSON 印在 log 並累加 `plugins/WorldGitPoc/results.jsonl`。`tools/harness.py` 負責複製 baseline、起伺服器、送指令、起 bot、解析結果。`Watcher` 每 tick 在 global scheduler 上掃描一個矩形範圍（chunk x -3..14、z -3..5）內已載入 chunk 的旗標，記錄 false→true / true→false 與時間。測試 chunk 排成 (cx=i%10, cz=1+i/10)，各自在 y=189 鋪石頭地板，改動都在 y=190 以上。所有對世界的操作都經 `RegionScheduler`（Paper 上等於主執行緒）。

## 2. 偵測機制 1：chunk 的 unsaved 旗標

### 2.1 旗標的真正語意（重要）
`ChunkAccess` 有 `private volatile boolean unsaved`，`markUnsaved()` 設它、`tryMarkSaved()` 清它。但 **Paper/Folia 把 `LevelChunk.isUnsaved()` 覆寫成**（javap 確認，1.21.11 與 26.2 bytecode 相同）：

```java
isUnsaved() = blockTicks.moonrise$isDirty(gameTime) || fluidTicks.moonrise$isDirty(gameTime) || super.isUnsaved()
```
- 有未完成排程 tick（流動的水、紅石…）的 chunk 會**一直回傳 true**：實測 chunk(9,-9) 每次 save-all 後 10–40 ms 又變 true，磁碟 NBT 每次存檔之間 `fluid_ticks` 與 `sections` 都在變。
- chunk PDC 髒時，`isUnsaved()` 也為 true，且存檔後仍維持 true（這點是觀察，機制為推測）。
- **Folia 上 `isUnsaved()` 必須在 region 執行緒**（`getGameTime` 讀 thread-local region 資料，global 執行緒 NPE，實測）。

→ 正式插件應**直接讀原始欄位**：`MethodHandles.privateLookupIn(ChunkAccess.class, lookup()).findVarHandle(ChunkAccess.class, "unsaved", boolean.class)`，volatile，任何執行緒可讀，兩版欄位名相同。本 PoC 的 `Nms.rawUnsaved` 即此做法，`Nms.effectiveUnsaved` 是 `isUnsaved()` 供對照。

### 2.2 來源 × 機制矩陣（每格 = P1.21 / P26.2 / F1.21 / F26.2；Y=偵測到）
22 種來源各在獨立 chunk 執行一次：先 save-all 並清旗標 → 動作 → 等 60–100 tick → 讀旗標 → save-all → 讀 region 時間戳。封包欄是 P1.21、1 個 bot 在附近時 PacketEvents 看到的（`×N` = 封包數）。

| 來源 | 原始旗標 | 有效 isUnsaved() P1.21 / P26.2 | region ts 更新 | PacketEvents 封包 |
|---|---|---|---|---|
| Bukkit `Block#setType` | Y / Y / Y / Y | Y / Y | Y / Y / Y / Y | BLOCK_CHANGE ×1 |
| NMS `ServerLevel.setBlock(pos,state,3)` | Y / Y / Y / Y | Y / Y | Y / Y / Y / Y | BLOCK_CHANGE ×1 |
| NMS `LevelChunk.setBlockState`（直寫 chunk，不通知） | Y / Y / Y / Y | Y / Y | Y / Y / Y / Y | **無** |
| NMS `LevelChunkSection.setBlockState`（raw，沒 markUnsaved） | **- / - / - / -** | - / - | **- / - / - / -** | 無 |
| 同上 + `chunk.markUnsaved()` | Y / Y / Y / Y | Y / Y | Y / Y / Y / Y | 無 |
| console `/setblock` | Y / Y / Y / Y | Y / Y | Y / Y / Y / Y | BLOCK_CHANGE ×1 |
| console `/fill`（4×4×4，跨兩個 section） | Y / Y / Y / Y | Y / Y | Y / Y / Y / Y | MULTI_BLOCK_CHANGE ×2（共 64 格） |
| 活塞（推一格石頭） | Y / Y / Y / Y | Y / Y | Y / Y / Y / Y | BLOCK_CHANGE ×1 + MULTI ×3 |
| 水流（觸發本身的旗標清掉後仍被設） | Y / Y / Y / Y | Y / Y | Y / Y / Y / Y | BLOCK_CHANGE ×5 |
| 爆炸（`createExplosion`） | Y / Y / Y / Y | Y / Y | Y / Y / Y / Y | BLOCK_CHANGE ×1 |
| 箱子 `setItem`（BE 內容） | Y / Y / Y / Y | Y / Y | Y / Y / Y / Y | **無**（沒人開箱不送） |
| 告示牌改字 | Y / Y / Y / Y | Y / Y | Y / Y / Y / Y | BLOCK_CHANGE ×1 + BLOCK_ENTITY_DATA ×1 |
| `World#setBiome` | Y / Y / Y / Y | Y / Y | Y / Y / Y / Y | **無** |
| chunk PDC | **- / - / - / -** | **Y / Y**（存檔後仍 Y） | Y / Y / Y / Y | 無 |
| 實體：生成 / teleport / 改名 / 移除 | - / - / - / - | - / -（P26.2 有一次雜訊） | - / - / - / -（entities 檔見 §2.6） | 無 |
| 熔爐燃燒中（BE tick） | - / Y / - / - | - / Y | - / Y / - / - | 無（結果不穩定，§2.7） |
| 真玩家（bot）放置方塊 | Y（P1.21、P26.2 相同） | — | Y | BLOCK_CHANGE ×3（含對放置者本人的確認/校正，未細查） |
| 真玩家挖方塊 | Y | — | Y | BLOCK_CHANGE ×1 |
| 真玩家往箱子放物品 | Y | — | Y | BLOCK_CHANGE ×2（未細查內容） |
| 對照：什麼都不做（2 個 chunk） | - / - / - / - | - / - | - / - / - / - | 無 |

（實體與對照列是 100 tick 觀察窗內的結果，窗內仍偶有雜訊，見 §2.4。）

**重點**
- 所有走正規 API、指令、機制的方塊與 BE 改動都設原始旗標，四平台一致。存檔（Paper 用 `save-all flush`；Folia 見 §7）後原始旗標清掉（水流因持續流動又被設）。
- **盲區 A（raw section 寫入）**：旗標、時間戳、封包三者都看不到，伺服器也不會存。這正是 section 替換必須手動 `markUnsaved()` 的原因（§6）。
- **盲區 B（chunk PDC）**：原始旗標不設，但 chunk 仍被存（region ts 更新）。若 WorldGit 要追蹤 chunk PDC，不能只靠原始旗標。
- **盲區 C（實體）**：見 §2.6。

### 2.3 存檔與旗標
- `save-all flush`（Paper）：被旗標的 chunk 存檔，原始旗標清掉，region 檔頭時間戳更新（§3）。
- **自然自動存檔**：`paper-world-defaults.yml` 的 `chunks.auto-save-interval` 設 200 tick，改 3 個 chunk 後，P1.21 在 9.8 秒內三個 chunk 的旗標同時清掉、時間戳同步更新；P26.2 一次剛好撞在 0.4 秒內清掉（行為一致，相位不同）。
- **Folia 沒有 `/save-all`**（console 回 Unknown command）。`World#save()` 在 global 執行緒 NPE、async 執行緒被 AsyncCatcher 擋。唯一可行的是在**擁有該 chunk 的 region 執行緒**呼叫 `ChunkHolderManager.saveAllChunks(true, false, false, true)`（實測旗標清掉、時間戳更新）。自動存檔在 Folia 照常運作。

### 2.4 「什麼都沒改」時的誤報
1. **fp2**：10 個鋪好地板的 chunk（3 空、3 個各有 3 頭有 AI 的牛、2 個各有 3 個靜止 armor stand、1 個掉落物、1 個 3 隻羊），flush 兩次、清旗標，再觀察 120 秒，bot 在附近。P1.21 有 **7/10** 個被設一次旗標（空 2/3、牛 1/3、armor stand 2/2、掉落物 1/1、羊 1/1）；P26.2 有 **8/10**（空 2/3、牛 2/3…）。與實體種類、有無 AI 無關，時間隨機（1–83 秒）。
2. **fp**：8 個 chunk 觀察 60 秒，P1.21/P26.2 都是 4/8 被設一次。其中漏斗+箱子 150 ms 內就設（真的 BE 內容變化）；靜態岩漿、熔爐、掉落物、樹苗都沒有。
3. **磁碟 NBT 抽樣（P1.21，150 秒、10 個 chunk、6 個被設旗標）**：存檔前後比對 chunk NBT：4 個 chunk 的 `block_states` 真的變了（天然世界在動：platform 下方天然地形的流體與隨機 tick），其中 1 個還有 BE 變動；1 個只有光照（`BlockLight/SkyLight`）變；**1 個只有 `InhabitedTime` 與 `LastUpdate` 變**（純 metadata 誤報）。`InhabitedTime` 隨玩家在場增加，但它**單獨不會**設旗標（玩家在場的什麼都不做 chunk 在 100 tick 窗內多半不被設）。
4. **chunk 載入**：baseline 內已生成的 chunk(9,-9) 載入後 0.5 秒就是 unsaved，存檔後仍 true（有未完成的流體 tick，§2.1）。從未生成的 chunk(25,25) 約 2.5–8 秒生成完成時被設；存檔後清掉（P1.21 一次在 8.9 秒取樣時已是 false）。**生成/載入新 chunk 會製造大量旗標**，但那是伺服器在生成世界，WorldGit 只追蹤 full chunk 並以雜湊過濾。

→ 旗標只能是**候選**，誤報率不低（每分鐘每十個 chunk 數個）。但誤報內容差異是光照、Inhabited、LastUpdate 與自然變動，docs 的正規化已丟掉光照與 `InhabitedTime/LastUpdate`，內容雜湊會濾掉。

### 2.5 光照外溢：改一格會設最多 9 個 chunk 的旗標
`suiteLight`（原始旗標，P1.21 與 P26.2 一致）：
| 動作 | 被設旗標的 chunk |
|---|---|
| chunk (5,1) 中央放螢石 | 自己（約 55 ms）+ **8 個鄰居**（100 ms 內） |
| chunk (7,1) 東緣 x=15 放螢石 | 自己 + 北/南/東側鄰居共 5 個（西側光到不了，沒被設） |
| chunk (9,1) 高處放一格石頭（天光陰影） | 自己 + 8 個鄰居 |

即**任何光照資料有變動的 chunk 都會被設原始旗標**。單一方塊編輯最多 1 真 + 8 假候選，全靠雜湊濾掉。旗標在 100 ms 內就出現，比封包（下個 tick 才送）還快。

### 2.6 實體：獨立 entity storage，沒有旗標
- 實體存在 `entities/r.X.Z.mca`（兩版皆然），由 Moonrise 的 `ChunkEntitySlices` 管理。javap 確認它**沒有 dirty 旗標**（兩版類別簽章相同）。
- 實測：生成、teleport、改名、移除實體，四平台的原始旗標與有效旗標都沒設（唯一例外是 P26.2 上的雜訊）。`entities` 檔時間戳則是**有實體的 chunk 每次存檔都更新，即使實體完全沒變**（`entity_present_unchanged`：一個靜止 armor stand，四平台都更新）；沒實體的 chunk 不更新。
- 所以實體變動偵測不能靠旗標，也不能靠 entities 時間戳。可行做法（**只做分析，未實作**）：
  ①候選 = entities 檔中有實體的 chunk，每次 commit 用 `ChunkEntitySlices.getAllEntities()` 序列化後雜湊；
  ②Bukkit 實體事件做即時髒標記，以①為最終真相；
  ③離線（CLI）直接比 entities 檔內容。
- 會動的生物與掉落物每 tick 都在變，雜湊每次都不同。entity blob 需要明確決定「忽略位置/速度/AI 狀態」的正規化規則（本 PoC 不處理；見 02-core-proto 的黏性容許距離）。

### 2.7 block entity 與 PDC 的細節
- BE 的**資料被改**（箱子 setItem、告示牌）會設旗標；封包只有告示牌（BLOCK_ENTITY_DATA），箱子內容沒人開就不送。
- BE **內部 tick 狀態**（熔爐 cookTime/lit、漏斗冷卻等）不穩定：熔爐 P1.21 bot 版沒設、P1.21 nobot 版有、P26.2 兩次都有、Folia 沒有。`AbstractFurnaceBlockEntity.serverTick` 只在 lit 狀態轉換時才 `setChanged`。結論：**BE 內部進度不會可靠地讓 chunk 變髒**，commit 時以雜湊為準。

## 3. 偵測機制 2：region 時間戳
- 做法：讀 `region/r.X.Z.mca` 檔頭第二個 4096 bytes 的 big-endian int（秒），index = `(cx&31) + (cz&31)*32`。`world.getWorldFolder()` 在 1.21.11 與 26.2 都能用來找 `region/`。
- 結果與旗標完全對應：旗標=Y 的來源在 save-all 後時間戳都更新（四平台）；raw section 寫入（旗標 -）時間戳不變。**「旗標（已載入未存）∪ 時間戳（已存）」對方塊與 BE 沒有縫隙**：一個改動要嘛還在記憶體（旗標 Y），要嘛已存檔（時間戳變）。
- **秒解析度會撞**：`tsdouble`（改 → save-all → 立刻再改 → save-all，間隔 74–200 ms）共 6 次測試，4 次兩次存檔落在同一秒、時間戳沒變。如果掃描器兩次掃描之間 chunk 被存了兩次且都在同一秒，且上次記錄的時間戳就是那一秒，就會漏。對策：時間戳 == 當前秒或 == 上次掃描秒的 chunk 視為「不確定」一律進候選；或搭配 `ChunkUnloadEvent`/`WorldSaveEvent`（**未測**）。
- entities 時間戳見 §2.6（有實體就每次更新，無用）。
- 時間戳會因不重要欄位（InhabitedTime、光照）被更新而誤報，與旗標同類，由雜湊過濾。

## 4. FAWE / WorldEdit（`EditSessionEvent` 包 Extent）

做法：`WorldEdit.getInstance().getEventBus().register(listener)`，在 `Stage.BEFORE_CHANGE` 用 `event.setExtent(new Log(event.getExtent(), session))`。`Log` 繼承 `AbstractDelegateExtent`，覆寫 `setBlock`（兩種簽章）、`setBlocks(Region, …)`、`setBlocks(Set, Pattern)`、`replaceBlocks(…)`。FAWE 上另加 `commit()`、`tile()` 與一個 `IBatchProcessor`（用 `extent.addProcessor`，`processSet` 內數每個 chunk 的非 0 格）。actor 來自 `event.getActor()`。

測試：API 建立 EditSession（不指定 actor / 以 bot 名義 / `fastMode(true)` / pattern / 逐格 `setBlock`），以及 bot 下 `//pos1 //pos2 //set`、`//replace`、`//undo`、`//fast`+`//set`、`//copy`+`//paste`、48×4×48 跨 9 個 chunk 的 `//set`。

| 項目 | FAWE（P1.21 2.15.0 / P26.2 2.15.4，結果相同） | 純 WorldEdit（F1.21 7.4.2、P1.21 7.4.2、F26.2 7.4.5、P26.2 7.4.5，結果相同） |
|---|---|---|
| 事件觸發、拿到 actor | 是。指令 = 玩家名；API 沒給 actor = `<none>`；API 給 actor = 該玩家名 | 同左 |
| `//set` / `//replace`（bulk） | **setBlock 呼叫數 = 0**，只看到 `setBlocks(Region,Pattern)` / `replaceBlocks(Region,Mask,Pattern)` 各 1 次（附 Region，可推涵蓋的 chunk） | **逐格 setBlock 全看得到**（144 格 = 144 次，大範圍 8464 次，依 chunk 可分） |
| `//fast`、`fastMode(true)`、pattern 版 | 同 bulk（看不到逐格） | 同逐格 |
| API 逐格 `setBlock` | 看得到（144） | 看得到 |
| `//undo`、`//paste` | **逐格看得到**（144） | 逐格 |
| chunk 層級 `IBatchProcessor` | **看得到每個 chunk 實際寫入的格數**（單 chunk 144；9 個 chunk 分別 900/960/1024…），涵蓋所有模式 | （無此機制） |
| 執行緒 | API = `Server thread`；指令 = `FAWE QueueHandler` / `AsyncNotifyKeyedQueue`（非同步） | Paper = `Server thread`；Folia = **region 執行緒** |
| 旗標 / 時間戳 | 被編輯的 chunk 旗標 Y，存檔後時間戳更新 | 同 |
| 玩家端封包 | `CHUNK_DATA` + `UPDATE_LIGHT`（整個 chunk 重送） | `MULTI_BLOCK_CHANGE`（逐格更新，每 section 一個封包） |

**FAWE 看不到逐格的模式**：只要走 bulk（`//set`、`//replace`、API 的 `setBlocks(Region, …)`，含 fast mode）就看不到逐格，看得到的是 bulk 方法呼叫（可推 Region）與 `IBatchProcessor` 的 chunk 層級資料。跨 9 個 chunk 的大範圍也只是一次 bulk 呼叫。`//regen`、筆刷、`//schem`、`//stack` **未測**。

**實務坑**
1. **FAWE 預設擋第三方 Extent**：log 出現 `Potentially unsafe extent blocked: wg.poc.FaweHook$FaweLog`，Extent 會被**靜默丟掉**（事件仍觸發但包裝無效）。必須在 FAWE `config.yml` 設 `extent.allowed-plugins: ["<你的 package>"]`（PoC 由 harness 預先寫入）。正式插件要在文件與啟動檢查中處理。
2. 純 WorldEdit 7.4 的 `AbstractDelegateExtent.commit()` 是 `final`，覆寫它會 `IncompatibleClassChangeError` 讓整個 extent 失效。FAWE 專用的覆寫必須放在只在 FAWE 下才載入的子類（本 PoC 的 `FaweHook.FaweLog`）。
3. FAWE 沒有 Folia 版，Folia 上要支援的是純 WorldEdit，兩者 Extent 行為不同（逐格 vs bulk），偵測碼需要兩條路。
4. Extent 看到的只是「想要設定」，FAWE 最終寫進 chunk 是非同步的。最穩的仍是旗標/時間戳/雜湊，Extent 用來補 **actor 歸屬**（docs 04 §4）。

## 5. 封包監聽（PacketEvents）

`Pkt extends PacketListenerAbstract`（`MONITOR`），監聽 `BLOCK_CHANGE`、`MULTI_BLOCK_CHANGE`（取 section 座標與格數）、`CHUNK_DATA`、`UPDATE_LIGHT`、`BLOCK_ENTITY_DATA`、`UNLOAD_CHUNK`；每筆記錄 (時間, 類型, 玩家, chunk, 格數)，按 chunk 彙整。

- **四平台都正常**（Folia 上 `onPacketSend` 在 netty 執行緒）。26.2 的 PacketEvents 2.14.0 沒有解析錯誤（`pktErr=0`）。
- 覆蓋範圍見 §2.2 封包欄。Bukkit API、`ServerLevel.setBlock`、指令、活塞、水、爆炸、告示牌、玩家放置/挖掘都有封包；**NMS 直寫 chunk/section、改 biome、容器內容、實體、chunk PDC 都沒有方塊封包**。FAWE 送整個 chunk。
- **沒有玩家在附近 → 0 個封包**（`flags nobot`：22 種來源全部 `pkt={}`，旗標與時間戳照常）。
- **同一變動對 N 個玩家重複**：3 個 bot 同站時每個方塊變動收到 **3 份**（`BLOCK_CHANGE` 3 個封包/3 位玩家；`/fill` 的 `MULTI_BLOCK_CHANGE` 6 個封包/192 格/3 位玩家；水流 15 個封包 = 5×3）。必須以 (world, chunk) 去重。
- 玩家放置 1 格，PE 看到 3 個 `BLOCK_CHANGE`（含對放置者本人的校正/確認），沒有逐一檢查封包內容。

結論：封包監聽不能當唯一來源，也不必要（旗標/時間戳已涵蓋）。適合 `/wg status show` 這類需要即時、只關心可見變化的功能。它看不到的（NMS 直寫、BE 內容、實體、biome、PDC）正是 §2 要靠別的機制補的。

## 6. 線上替換 section

### 6.1 做法（`Sect.replace`，必須在擁有該 chunk 的執行緒呼叫）
1. `snapshot`：`section.getStates().copy()`（便宜的容器複本）＋ section 內每個 BE 的 `chunk.getBlockEntityNbtForSaving(pos, registries)`。
2. 讀舊 section 4096 格到陣列（供光照比對）。
3. `chunk.removeBlockEntity(pos)` 移除舊 section 內所有 BE（**必須在換 section 之前**，它會處理 ticker 與註冊）。
4. 新容器：用 `section.getStates().recreate()` 取同型別空容器，逐格 `set`（真實情況由 blob 的 palette+data 解出，本 PoC 用圖樣產生）。`new LevelChunkSection(newStates, oldBiomes.copy())`（建構子會 `recalcBlockCounts`），`chunk.getSections()[i] = newSection`。
5. 重建 BE：有 NBT 的用 `BlockEntity.loadStatic(pos, state, tag, registryAccess)` + `chunk.setBlockEntity`；其餘 `state.hasBlockEntity()` 的用 `chunk.getBlockEntity(pos, EntityCreationType.IMMEDIATE)` 建空的。
6. `Heightmap.primeHeightmaps(chunk, chunk 上已有的 heightmap 種類)`。
7. 光照：對「發光量或遮光量（`getLightEmission` / `getLightDampening`）或 `useShapeForLightOcclusion` 有變」的格呼叫 `ThreadedLevelLightEngine.checkBlock(pos)`，並 `updateSectionStatus(SectionPos, hasOnlyAir)`。
8. `level.getPoiManager().checkConsistencyWithBlocks(SectionPos, section)`（呼叫成功；POI 效果未驗證）。
9. **`chunk.markUnsaved()`**（直接換 section 不會設旗標，不設伺服器不會存）。
10. 通知玩家：`BLOCKS` = 對每個改變的位置呼叫 `serverChunkCache.blockChanged(pos)`（該 tick 結束時合併成每 section 一個 `MULTI_BLOCK_CHANGE` + 每個 BE 的 `BLOCK_ENTITY_DATA`）；`RESEND` = `World#refreshChunk(cx, cz)`（整 chunk `CHUNK_DATA` + 光照）；`NONE` = 對照組。

### 6.2 驗證
chunk (5,1)，section Y=11（y 176..191）。圖樣：S0 = 原有內容含 3 個 BE（箱子+鑽石×3、告示牌、熔爐）；P1 = 下半全石頭+玻璃棋盤+海晶燈+箱子/木桶；P2 = 泥土+水。

| 步驟 | 結果（四平台相同） |
|---|---|
| S0 初始：bot 4096 格名稱雜湊 vs 伺服器 | 一致 |
| 套 P1，通知 `BLOCKS`（改 3475 格、移除 3 個 BE、建立 2 個 BE、光照檢查 3350 次） | bot 與伺服器**逐格一致**。伺服器端光照重算：海晶燈旁 (2,191,0) 方塊光 13、正上方跨 section 的 (4,192,4) 方塊光 14、玻璃格 12，被石頭覆蓋的 (5,180,5) 天光變 0（原 9）。 |
| 存檔後從磁碟（chunk IO）讀回 | section palette = stone/glass/air/chest/barrel/sea_lantern，BE 2 個 |
| 還原 S0，通知 `RESEND` | bot 一致；**伺服器端整個 section（state 字串 + 每個 BE 完整 NBT）雜湊與替換前相同**（P1.21 `fc2cacf2…`、P26.2 `04c55357…`）；光照回到原值 |
| 套 P2，**不通知** | bot 仍看到舊畫面（2823 格不一致：泥土→空氣 2048、水→空氣 512…）。對照組，證明通知必要。 |
| 還原 S0，通知 `BLOCKS` | bot 一致，雜湊與原本相同 |
| 替換前後旗標 | 替換前清成 false，替換後 true（我們自己 `markUnsaved()`） |
| Log | 無 ERROR/WARN/Exception（四平台） |

耗時（`Sect.replace` 內部，含 3000+ 次光照檢查與 3475 次 `blockChanged`；依序 P1/S0-RESEND/P2/S0-BLOCKS）：
- P1.21：15.8 / 3.4 / 2.7 / 3.6 ms
- P26.2：15.4 / 5.8 / 2.7 / 4.7 ms
- F1.21：13.8 / 8.5 / 2.6 / 3.5 ms
- F26.2：11.1 / 5.1 / 2.4 / 4.4 ms

第一次含 JIT。一個 section 遠低於 1 tick，docs 05 的「每 tick 限額」可用 section 數計。光照是非同步重算，約 1–2 秒內完成。

**過程中被打到的版本差異**：第一次在 26.2 套 P1 直接 `NoSuchMethodError: BlockState.getLightBlock()`；修掉後又遇到 `IllegalAccessError: ChunkPos.x`（見 §9）。

### 6.3 通知方式比較
| 方式 | 封包 | 適用 |
|---|---|---|
| `BLOCKS`（`blockChanged` ×N） | 每 section 1 個 `MULTI_BLOCK_CHANGE` + BE 的 `BLOCK_ENTITY_DATA` + 光照更新 | 變動格數少到中等 |
| `RESEND`（`refreshChunk`） | 整 chunk `CHUNK_DATA` + 光照 | 變動多（整個 section）；實作最簡單；**API 標 deprecated 但 1.21.11/26.2 都可用** |
| 什麼都不做 | 無 | 不可行（客戶端看舊畫面） |

建議：整 section 替換直接 `RESEND`（或自組 `ClientboundLevelChunkWithLightPacket` 只送有變的 chunk），零星 restore 用 `BLOCKS`。

### 6.4 未載入 chunk：走伺服器 chunk IO
做法：`MoonriseRegionFileIO.loadData(level, cx, cz, RegionFileType.CHUNK_DATA, Priority.NORMAL)`（async 執行緒）→ 改 NBT：
- `sections[Y].block_states` 換成單一 palette
- 移除舊 section 內的 `block_entities`
- 移除該 section 的 `BlockLight/SkyLight`
- 移除 `isLightOn` 與 `Heightmaps`，讓載入時重算

→ `scheduleSave(…)` → `MoonriseRegionFileIO.flush(level)` → 再 `loadData` 確認 → 以 ticket 載入 chunk 驗證方塊。

- **結果：P1.21 / P26.2 / F1.21 / F26.2 四平台皆成功**（讀 1–40 ms；載入後整個 section 4096 格都是新方塊）。NBT API 是 1.21.5 之後 Optional 版（`getList(key)` → `Optional<ListTag>`），兩版相同。
- **競態陷阱（P1.21 實測重現）**：當 `ServerLevel.moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(cx,cz) != null`（chunk 系統記憶體裡有該 chunk，即使只是非 full 的 proto 狀態、`getChunkNow`=null、`isChunkLoaded`=false）時，寫回的 NBT 被記憶體版本蓋掉。驗證時 section 是舊的（4096 格中 0 格是新方塊），磁碟上讀回卻是新的。同 chunk 在沒有 holder 時（乾淨啟動）成功。
- 建議：先檢查 `hasChunkHolder`。沒有 → 可走 IO；有 → 或一律改走「`getChunkAtAsync` + plugin ticket → 在 region 執行緒用 §6.1 替換 → `markUnsaved()` → 移除 ticket」，與已載入 chunk 共用同一條路徑，免掉光照/heightmap/BE 的 NBT 層手工處理。IO 路徑還要自己管 `DataVersion`、palette/data 位元壓縮、BE/entity/ticks 的 NBT 修改，複雜度遠高於 §6.1。

## 7. Folia

- 插件 `folia-supported: true`，全部排程走 `RegionScheduler` / `GlobalRegionScheduler` / `EntityScheduler` / `AsyncScheduler`。F1.21 與 F26.2（BETA）的 flags 套件（22 來源）、section 替換（含 bot 驗證與 IO）、WorldEdit 都跑完；玩家保護只在 F1.21 跑。**console 沒有任何執行緒檢查錯誤**（log 中的 Exception 都是 PoC 自己程式的 bug，已修）。
- 實測踩到的坑：
  1. `LevelChunk.isUnsaved()`（有效旗標）在 global 執行緒 NPE（`getCurrentWorldData()` 為 null）→ 用 §2.1 的原始欄位 `VarHandle`。
  2. **沒有 `/save-all`**；`World#save()` 在 global/async 執行緒 NPE/AsyncCatcher；唯一可行：region 執行緒呼叫 `chunkHolderManager.saveAllChunks(true,false,false,true)`（只存該 region 擁有的 chunk）。WorldGit 的「commit 前強制存檔」在 Folia 上要按 region 分組各自呼叫。
  3. `Entity#teleport` 在 region 執行緒丟 `UnsupportedOperationException: Must use teleportAsync`。
  4. console 指令（`/setblock`、`/fill`）在 Folia 上正常（Folia 自行導到對應 region），結果與 Paper 一致。
  5. FAWE 不支援 Folia；純 WorldEdit 在 region 執行緒回呼 Extent（不是 global 執行緒）。
- Folia 26.2 是 BETA build，本次行為與 Folia 1.21.11 完全相同。

## 8. 玩家保護（docs/05 §1 的決定）
測試：bot 設為生存，傳送到 y=235（落下約 45 格）→ 落地後在頭部位置放石頭（窒息）。三種模式：`none`（基準）、`event`（EntityDamageEvent 取消 FALL/SUFFOCATION/DROWNING，期限 15 秒）、`potion`（Resistance V，300 tick，無粒子）。

| 模式 | FALL 事件 final damage | SUFFOCATION final damage | 玩家血量 |
|---|---|---|---|
| none | 本次基準 bot 落地判定延遲，未產生 FALL 事件（y=190.0002、onGround=false） | 1.0（未取消） | 20（自然回血，抽樣時機） |
| event | **40.0（致命）→ 被取消** | 1.0 → 被取消 | 20.0 |
| potion | 事件仍觸發但 final damage **0.0** | 0.0 | 20.0 |

P1.21、P26.2、F1.21 結果相同（F26.2 未跑）。Folia 上事件在實體所在 region 的執行緒觸發，`ConcurrentHashMap` 記錄到期時間即可。基準組的 FALL 缺失是 bot 落地旗標問題；FALL 40.0 與 SUFFOCATION 1.0 的數值來自 event/potion 組的 `EntityDamageEvent`。

取捨：**事件取消**只擋指定 cause、不留狀態、毫秒精度到期、沒有圖示與粒子、斷線重連不殘留；缺點是要維護一個 map 與監聽。**藥水**實作最簡單，但擋全部傷害（含怪物攻擊，範圍太大）、圖示需隱藏、重連/死亡行為要另外處理。建議事件取消（預設 10 秒、cause=FALL/SUFFOCATION/DROWNING）。創造/旁觀本來就免傷害。

## 9. NMS 用到的類別與方法；1.21.11 ↔ 26.2 差異

**兩版皆有（用 `check_binary_compat.py` 對 26.2 jar 逐一檢查，96–98 個引用）：**
- `org.bukkit.craftbukkit.CraftWorld#getHandle → ServerLevel`；`CraftBlockData#getState`
- `ServerLevel#getChunkSource → ServerChunkCache`：`getChunkNow(cx,cz)`（不載入）、`blockChanged(BlockPos)`、`getLightEngine() → ThreadedLevelLightEngine`（`checkBlock`、`updateSectionStatus`）
- `ServerLevel#getPoiManager().checkConsistencyWithBlocks(SectionPos, LevelChunkSection)`
- `ServerLevel#moonrise$getChunkTaskScheduler().chunkHolderManager`：`getChunkHolder(int,int)`、`saveAllChunks(boolean,boolean,boolean,boolean)`
- `ChunkAccess`：`unsaved`（private volatile，VarHandle）、`markUnsaved()`、`tryMarkSaved()`、`getSections()`、`getSection(i)`、`getSectionIndexFromSectionY`、`getSectionIndex`、`getHeightmaps()`、`getBlockEntitiesPos()`、`getBlockEntityNbtForSaving(pos, registries)`、`getPersistedStatus()`
- `LevelChunk`：`setBlockState(BlockPos, BlockState, int)`、`setBlockEntity`、`removeBlockEntity`、`getBlockEntity(pos, EntityCreationType)`、`isUnsaved()`（被覆寫）
- `LevelChunkSection`：`getStates()`、`getBiomes()`、`getBlockState(x,y,z)`、`setBlockState(x,y,z,state,bool)`、`hasOnlyAir()`、建構子 `(PalettedContainer<BlockState>, PalettedContainer<Holder<Biome>>)`
- `PalettedContainer`：`copy()`、`recreate()`、`set(x,y,z,v)`
- `BlockEntity.loadStatic(pos,state,tag,registries)`；`Heightmap.primeHeightmaps(chunk, Set<Types>)`
- Moonrise：`MoonriseRegionFileIO.loadData / scheduleSave / flush`、`RegionFileType.CHUNK_DATA`、`ca.spottedleaf.concurrentutil.util.Priority`
- NBT（1.21.5+，Optional 回傳）：`CompoundTag#getList/getCompound/getByte/getString/getInt/put/putByte/putString/remove/keySet`、`ListTag`

**兩版差異：**
| 項目 | 1.21.11 | 26.2 | 處理 |
|---|---|---|---|
| `BlockState#getLightBlock()` | 有 | **改名為 `getLightDampening()`** | `MethodHandle` 擇一綁定（否則 `NoSuchMethodError`，實際被打到） |
| `ChunkPos.x / z` 欄位 | public | **private**（`IllegalAccessError`，實際被打到） | 改用 `getMinBlockX()>>4` |
| `LevelChunkSection.states` 欄位 | public | private（`getStates()` 兩版都 public） | 只用 `getStates()` |
| `ChunkAccess#setBiome` / `LevelChunkSection#setBiome` | `setBiome` | `setNoiseBiome` | 本 PoC 不用 |
| `ChunkAccess#markPosForPostprocessing` | …Postprocessing | …PostProcessing | 不用 |
| `ServerChunkCache#fullChunks` 型別 | `ConcurrentLong2ReferenceChainedHashTable` | `ConcurrentChainedLong2ReferenceHashTable` | 不碰此欄位，用 `getChunkNow` |
| `ServerChunkCache#dataStorage` | `DimensionDataStorage` | `SavedDataStorage` | 不用 |
| `LevelChunk.level/loaded` 欄位 | public | private | 不用 |
| `ChunkEntitySlices`、`NewChunkHolder`、`MoonriseRegionFileIO`、`ChunkHolderManager` | — | 簽章相同 | — |

## 10. 問題、限制與未驗證
- 已在文中：`isUnsaved()` 被覆寫與 Folia NPE；FAWE 擋第三方 Extent；WE 7.4.5 需 Java 25；`commit()` final；mineflayer 不支援 26.2；ViaVersion 無法轉譯 26.1→26.2；Folia 沒有 save-all；IO 路徑競態；NMS 兩版細微差異。
- **客戶端光照沒驗證**：mineflayer 讀出的 `block.light`/`skyLight` 與伺服器完全對不上（連替換前的初始狀態都不同，1.21.11 原生也是）。判斷是 mineflayer/prismarine-chunk 對光照陣列的對應問題。伺服器端光照（`Block#getLightFromBlocks/FromSky`）的重算有驗證，`UPDATE_LIGHT` 封包有送出。
- **沒做**：
  - paperweight-userdev
  - `ChunkUnloadEvent` / `WorldSaveEvent` 作為「存檔瞬間」補抓
  - Nether/End、多世界
  - FAWE 的 `//regen`、筆刷、schematic、biome 編輯
  - 替換 section 內的實體處理（kill/搬移，見 docs 05）
  - 殘留的 `block_ticks`/`fluid_ticks`（預期無害，因為 tick 會檢查方塊類型，**未驗證**）
  - POI 效果（呼叫成功，但測試 section 無 POI 方塊變化）
  - Folia 多 region 同時替換的壓力測試
  - F26.2 玩家保護
  - 26.2 以外的 26.x
- 資料品質：誤報統計樣本小（每種 1–3 個 chunk、60–150 秒），只能說「存在且不可忽略」，不能當速率；bot 只有 1–3 個；機器 3 核心且另有別的代理在跑伺服器，ms 量測僅供參考。
- bot hack 只影響 26.2 的 bot 驗證（放置、挖掘、看方塊），結果與 1.21.11 原生 bot 相同，未發現差異。
- `flags` 套件的第一版沒有分開讀「原始旗標」與「有效旗標」，發現 `isUnsaved()` 的覆寫後重跑了全部四平台。`extra=2`（3 玩家）那次是舊版資料，只用於封包重複數，其旗標欄不引用。

## 11. 對正式插件的建議（docs/04 §2 偵測組合是否成立）

**成立，但請改成下面這個具體版本：**

```
已載入 chunk（即時、每 1–2 秒掃一次，任何執行緒）:
    原始 unsaved 旗標（VarHandle 讀 ChunkAccess.unsaved，不要用 isUnsaved()）          → 候選
    ∪ EditSessionEvent（WE 逐格 / FAWE bulk Region + IBatchProcessor）                 → 候選 + actor
    ∪ Bukkit 事件（放置/破壞/爆炸/活塞…）                                               → 候選 + actor
    ∪ PacketEvents（僅可見變化，給即時 UI；以 chunk 去重）                               → 候選（選用）
已存回磁碟的 chunk:
    region 檔頭時間戳 ≠ index 記錄值，或 == 當前秒/上次掃描秒（同秒碰撞）              → 候選
    ∪ ChunkUnloadEvent / WorldSaveEvent（選用，未測）
實體（獨立 entity storage，無旗標）:
    候選 = 有實體的 chunk（entities 檔時間戳對它們每次存檔都變，只當「有實體」的索引）
    + Bukkit 實體事件做即時髒標記；commit 時序列化實體 → 正規化（丟位置/速度/AI）→ 雜湊
                    ↓
    候選 chunk → 內容雜湊（正規化：丟光照、starlight.*、Heightmaps、InhabitedTime、LastUpdate、PostProcessing）→ 真正的變動
    定期 / `status --full`：對已載入 chunk 做全量 section 雜湊，兜底「盲區」
```

1. **讀原始欄位**，不要用 `LevelChunk.isUnsaved()`（含排程 tick 的 dirty、含 PDC、Folia 上會 NPE）。
2. **預期 1 真 + 8 假**：單方塊編輯讓最多 9 個 chunk 被設旗標（光照外溢），閒置 chunk 也偶發被設。雜湊前可先做便宜篩選（只比 section palette 與 `block_states` 位元組雜湊，丟掉光照/metadata 欄位）。旗標機制仍值得保留，它把「全世界」縮成「幾十個 chunk」。
3. **盲區要明講**：raw section 寫入（含我們自己的 switch/restore，務必 `markUnsaved()`）、BE 內部 tick 進度、實體、chunk PDC。前兩者由「存檔不會被持久化」自然限制影響，實體與 PDC 需要另行設計（§2.6）。
4. **時間戳同秒碰撞**要處理。掃描頻率越高，越不容易遇到「一次掃描間隔內存了兩次」。
5. **FAWE**：要求使用者設 `extent.allowed-plugins` 並偵測被擋（log 關鍵字，或測試 Extent 是否收到呼叫）；用 bulk 呼叫的 Region 與 `IBatchProcessor` 的 chunk 資料推出受影響的 chunk。Folia 只有純 WorldEdit（逐格可見），兩條路徑分開寫。
6. **封包**只當選用的即時 UI 訊號，不能拿來「保證不漏」。
7. **section 替換**：已載入 → §6.1 流程（記得 `markUnsaved()`、`BLOCKS`/`RESEND` 通知、光照 `checkBlock`）；未載入 → 預設「ticket 載入 → 記憶體替換」，chunk IO 只在確認沒有 `ChunkHolder` 時作為省記憶體的選項。
8. **Folia**：全部經 `RegionScheduler`/`EntityScheduler`；強制存檔按 region 分組；`save-all` 不存在；旗標讀取用 VarHandle。
9. **多版本**：正式專案用 paperweight-userdev 各版一個 adapter；CI 加「對另一版 server jar 的二進位相容檢查」（PoC 的 `check_binary_compat.py`），`BlockState.getLightBlock` 這類改名寫 adapter 介面（或 MethodHandle）。
10. **玩家保護**用事件取消（§8）。
