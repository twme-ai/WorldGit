# 02 — 資料模型：世界怎麼變成 git 物件

原始筆記的疑問：「每次 commit 是要存一整個世界還是存區塊？」

**答案：兩者都是，也都不是。** 邏輯上每個 commit 都是「整個世界的完整快照」（所以切換、下載任何一個時間點都很直接），但實體上只儲存**有變動的最小單位**，沒變的部分直接引用舊的物件。這正是 git 本身的做法（內容定址 + 樹狀結構共用）。

## 1. 粒度選擇

| 粒度 | 優點 | 缺點 | 結論 |
|---|---|---|---|
| 整個世界資料夾（zip） | 最簡單 | 每次存檔點 GB 級、無法 diff/merge | ✗ |
| region 檔（.mca，32×32 chunk） | 對應檔案系統 | 放一格方塊就要存 ~數 MB；合併粒度太粗 | ✗ |
| chunk（16×384×16） | 自然單位 | 高樓/地下改一格，整根柱子重存 | △ |
| **chunk section（16×16×16）** | 大小約數百 B～數 KB；MC 本身就用 section 存調色盤 | 物件數量多（但全空氣/全石頭 section 會去重成同一個雜湊） | **✓ 採用** |
| 單一方塊 | 最細 | 物件數爆炸、雜湊開銷遠大於資料本身 | ✗（只在合併衝突時才下探到方塊級，見 [06](06-diff-merge.md)） |

## 2. 物件階層

```
commit
 ├─ parents: [..]                    # merge commit 有兩個
 ├─ meta: author(s), message, time, mcDataVersion, source(plugin/mod/cli), auto?
 └─ root tree
     ├─ dimension "minecraft:overworld"
     │   ├─ region r.0.0            # tree：最多 1024 個 chunk
     │   │   ├─ chunk c.3.7         # tree
     │   │   │   ├─ s.-4 … s.19     # blob：section（方塊 + 該 section 內的 block entity）
     │   │   │   ├─ biomes          # blob：整個 chunk 的 biome（很少變，獨立出來避免假 diff）
     │   │   │   ├─ entities        # blob：該 chunk 內被追蹤的實體（依 UUID 排序）
     │   │   │   ├─ ticks           # blob：排程中的 block/fluid tick（可選，紅石/水流才需要）
     │   │   │   └─ structures      # blob：chunk 的結構資料（村莊、要塞…），空則不存（Phase 0 補上，否則還原出的 chunk 會遺失結構資訊）
     │   │   └─ …
     │   └─ …
     ├─ dimension "minecraft:the_nether" …
     └─ world-meta                   # blob：被追蹤的 level.dat 欄位子集（出生點、gamerule…可選）
```

只改了一格方塊時，一次 commit 新增的物件：1 個 section blob + chunk tree + region tree + dimension tree + root tree + commit，總計幾 KB（Phase 0 實測約 5–6 KB、9 個物件）。全空氣且無 block entity 的 section 不存（缺檔即空氣）。

## 3. 正規化（決定 diff 準不準的關鍵）

同樣的方塊內容必須產生**完全相同的位元組**，否則雜湊不同 → 假 diff、無法去重。MC 存檔時有很多非確定性：

| 項目 | 處理 |
|---|---|
| 調色盤順序（palette 在 MC 裡是任意順序） | 依 YZX 走訪順序，以「第一次出現」重新編號調色盤，重新打包 bit 陣列 |
| block state 屬性順序 | 屬性依鍵名排序 |
| NBT compound 鍵順序 | 依鍵名排序後序列化 |
| Paper 加的欄位（chunk/section 的 `starlight.*`；實體的 `Paper.*`、`Bukkit.*`、`Spigot.*`、`WorldUUID*`） | 丟棄（Phase 0 發現） |
| 實體 `attributes` 列表順序（每次存檔可能不同） | 依 `id` 排序（Phase 0 發現） |
| 光照（BlockLight/SkyLight）、Heightmaps、`isLightOn` | **丟棄**，寫回時讓伺服器重算（Phase 0 已驗證：寫回時移除光照、`starlight.*`、Heightmaps，Paper 兩版載入後重算的光照與原本逐 nibble 相同） |
| `LastUpdate`、`InhabitedTime`、`Status`、`PostProcessing`、`xPos/yPos/zPos` | 丟棄（寫回時固定為 full / 保留目標世界原值）；`DataVersion` 記在 world-meta |
| 排程 tick（`block_ticks`/`fluid_ticks`） | 依 (y,z,x,id,p,t) 排序；`t` 易變，待定是否量化 |
| POI（村民工作站） | **不存**；寫回時**必須刪除**範圍內 chunk 的 POI，伺服器載入時會由方塊重建（Phase 0 已驗證兩版；保留舊 POI 會留下過期紀錄、新工作站不會被登記） |
| block entity 內的暫態欄位（熔爐燃燒時間、生怪磚倒數…） | 依方塊類型設定「忽略欄位」表；預設保留容器內容（箱子裡的東西是建築的一部分） |
| 實體的暫態欄位（`Motion`、`FallDistance`、`Fire`、`Air`、年齡…） | 同上，以類型白名單/黑名單處理；位置用「黏性容許距離」（§8）。Phase 0 的完整忽略清單見 `experiments/02-core-proto/REPORT.md` §1.4 |

正規化後的 section 格式建議：自訂的精簡二進位（版本號 + 調色盤字串表 + 打包索引 + 排序過的 block entity NBT），再以 zstd 壓縮。**不直接存 MC 原生 NBT**，好處是 MC 改存檔格式時只影響轉換層。

**Phase 0 驗證結果（2026-09-30，`experiments/02-core-proto/`）**：這套正規化在 1.21.11 與 26.2 都成立。伺服器把 697 個 chunk 全部重寫後，方塊、block entity、biome、ticks 的假 diff 都是 0；把 init 快照寫回乾淨世界再 commit，diff 也是 0。section blob 中位數 485 B，698 個 chunk 的 repo 經 gc 後約 3.2 MB。section 之間幾乎沒有去重（3.4%），主要節省來自不存全空氣 section；biome 去重率約 90%。

## 4. 追蹤範圍（已決定，2026-09-30）

原則：**除了玩家以外，世界裡的一切預設都追蹤**；不想追蹤的東西用 `.wgignore`（§5）排除。

| 資料 | 預設 | 備註 |
|---|---|---|
| 方塊、block entity（箱子內容、告示牌文字、旗幟…） | 追蹤 | 建築本體 |
| 盔甲座、物品展示框、畫、display entity、船/礦車 | 追蹤 | 建築師大量使用 |
| **所有生物**，包含自然刷出的怪物 | 追蹤 | 有人用生物做建築（凍結的生物雕像、村民交易所、動物園）。移動帶來的問題見 §8 |
| 掉落物、經驗球、投射物、掉落中的方塊、點燃的 TNT | 追蹤 | 可用 `.wgignore` 排除（範本裡有現成的註解行） |
| biome | 追蹤（獨立 blob） | 有些建築師會改 biome |
| **所有已完全生成的 chunk**（`Status: full`，包含沒被玩家動過的自然地形） | 追蹤 | 從 repo 下載時要能拿到完整、可以直接開的世界，見 §7。生成到一半的邊緣 chunk（玩家看不到）不追蹤：Phase 0 測試世界中 2255 個 chunk 只有 626 個是 full |
| 玩家資料（背包、位置、進度） | **不追蹤**（寫死，不能用 `.wgignore` 反向開啟） | 不是世界的一部分；切換分支時沒收玩家的東西會很奇怪 |
| 地圖（map_*.dat）、記分板、世界邊界、gamerule | 追蹤 | 屬於 `world-meta`，同樣可排除 |

## 5. `.wgignore`：類似 `.gitignore` 的排除規則（已決定，2026-09-30）

`.wgignore` 是 repo 根目錄下的一個**被版本控制的檔案**，跟 `.gitignore` 一樣會隨 commit 一起存、隨 push/pull 分享，所以整個團隊用同一套規則；不同分支也可以有不同規則。

git 的 `.gitignore` 只能比對路徑，但世界需要依「座標範圍」「實體類型」「NBT 欄位」排除，所以語法是以關鍵字開頭的一行一規則（草案）：

```gitignore
# ── 維度 ─────────────────────────────
dimension minecraft:the_end

# ── 座標範圍（x1 y1 z1 x2 y2 z2）─────
area 500 -64 500 600 320 600        # 刷怪塔，方塊和實體都不追蹤

# ── 實體 ─────────────────────────────
entity minecraft:item               # 掉落物
entity minecraft:experience_orb
entity #minecraft:arrows            # 可用實體 tag
entity minecraft:zombie !persistent # 只排除「會自然消失」的殭屍（被命名的仍追蹤）
entity * !persistent in area 0 -64 0 1000 320 1000   # 某範圍內所有自然刷出的生物

# ── NBT 欄位（只忽略欄位，實體/方塊本身仍追蹤）──
field minecraft:furnace BurnTime CookTime
field minecraft:villager Gossips

# ── 否定：把前面排除的東西加回來（跟 .gitignore 的 ! 一樣，後面的規則優先）──
!entity minecraft:item in area 100 60 100 120 80 120   # 展示用的掉落物
```

行為規則：
- 被忽略的內容在 `commit` 時不存、`status`/`diff` 不顯示，`switch`/`restore` 時**保持活世界裡的現狀不動**（對應 git 的「untracked 檔案不會被 checkout 覆蓋」）。
- 修改 `.wgignore` 本身也是一個變動，要 commit 才生效。之前已追蹤、之後才被忽略的東西，會在下一次 commit 從快照中移除（等同 `git rm --cached`），並在 `status` 裡提示。
- `/wg init` 產生的預設 `.wgignore` 只有註解掉的範例行（例如 `# entity minecraft:item`），實際上什麼都不排除，符合「預設全部追蹤」。
- 另外還有一份**不進版本控制**的本機設定 `worldgit.toml`，只放個別伺服器的設定（自動 commit 頻率、Hub 位址、權限），不放追蹤規則。

## 6. 雜湊

- 若採用 JGit 後端（見 [03](03-storage-backend.md)）：沿用 git 的 SHA-1（或 git 的 SHA-256 模式），換取與 git 生態相容。
- 若自製物件庫：BLAKE3，更快。

## 7. 已生成的地形也要追蹤（已決定，2026-09-30）

**決定：所有已生成的 chunk 都進版本控制。** 從 Hub 下載或 `clone` 時拿到的是完整世界，打開就跟原本一模一樣，不需要靠種子重新生成、也不怕 MC 版本改變地形生成。

背景說明（之前的疑問）：玩家跑去探索時，遊戲會生成新的 chunk，雖然沒人在上面蓋東西，它仍然是「世界多出來的內容」。之前的選項是「只追蹤玩家改過的 chunk，自然地形靠種子重建」，這樣 repo 比較小，但下載下來的世界可能不完整或跟原本不同，所以不採用。

代價與對策：
- **第一次 `init` 比較大**：要把整個已生成的世界存一次。只算一次，之後都是增量；全空氣、全石頭這類相同的 section 會去重成同一個物件。
- **「兩邊都探索了同一塊新區域」的合併**：同種子、同 MC 版本下生成出來的 chunk 內容相同，雜湊相同，直接無衝突。如果版本不同導致生成結果不同，而且兩邊都沒有玩家改動紀錄（[04](04-commit-and-status.md) §4 的作者歸屬可以判斷），就自動採用目前分支的版本，並在合併報告中列出，不當成需要人處理的衝突。

## 8. 追蹤生物帶來的問題與對策

追蹤所有生物後，最大的問題是**生物會自己動**：牛走兩步、村民換工作、雞生蛋、夜晚刷怪，都會讓世界「永遠不乾淨」，而且合併時會出現大量無意義的衝突。對策：

| 問題 | 對策 |
|---|---|
| 位置/視角每 tick 都在變 | 正規化時忽略 `Motion`、`FallDistance`、`OnGround` 等欄位；`Rotation` 只對會動的實體忽略（盔甲座、展示框、畫、display、NoAI 生物要保留朝向）。位置用**黏性容許距離**：commit 時與 HEAD 中同 UUID 的紀錄比較，其他欄位相同且移動 ≤ 2 格（可設定）就沿用 HEAD 的紀錄。不用「量化到方塊格」，因為生物跨越格線時仍會變。`NoAI` 與靜態實體精確比對。Phase 0 實測：黏性把只有位置的變動減少 60–75%，剩下的都是真的走超過 2 格 |
| 會自己磨損/變動的欄位 | Phase 0 發現：日曬下殭屍頭盔的耐久（`equipment…damage`）、`Health`、掉落物合併後的 `Item` 數量會一直變，需列入內建忽略表 |
| AI 內部狀態（`Brain` 記憶、`InLove`、`Age` 計時、仇恨目標…） | 以實體類型維護內建的「忽略欄位」表；只比對「玩家在意的」欄位：類型、名稱、裝備、`NoAI`、`Silent`、`Invulnerable`、`PersistenceRequired`、變種（顏色/花紋）、村民職業與交易、拴繩、坐騎。使用者可用 `.wgignore` 的 `field` 規則增減 |
| 生物跨 chunk 移動 | 儲存上仍以 chunk 分組，但 diff/merge **以 UUID 為全域鍵**：A chunk 消失、B chunk 出現同一 UUID → 視為「移動」而不是「刪除 + 新增」 |
| 切換/還原時的重複生物 | 套用前以 UUID 在整個世界（已載入 + 目標 chunk）查找並移除舊的那隻，再放入目標版本，避免同一隻出現兩次 |
| 自然刷出又自然消失的怪物、會被撿走的掉落物 | 預設都追蹤。雜訊太多的伺服器可以在 `.wgignore` 加上 `entity * !persistent` 一行排除（範本中已附註解） |
| `status` 雜訊 | `status` 分開顯示「方塊變動」與「實體變動」；自動 commit 可設為「只有實體變動時不觸發」 |
| 生物繁殖/死亡造成的合併衝突 | 同一 UUID 兩邊都改 → 衝突；只有一邊生出新 UUID → 直接加入；只有一邊死亡 → 直接移除。衝突區域以生物所在位置併入方塊衝突分群 |

這一段的正規化規則會是 Phase 0 的重點驗證項目之一。
