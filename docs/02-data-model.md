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
     │   │   │   └─ ticks           # blob：排程中的 block/fluid tick（可選，紅石/水流才需要）
     │   │   └─ …
     │   └─ …
     ├─ dimension "minecraft:the_nether" …
     └─ world-meta                   # blob：被追蹤的 level.dat 欄位子集（出生點、gamerule…可選）
```

只改了一格方塊時，一次 commit 新增的物件：1 個 section blob + chunk tree + region tree + dimension tree + root tree + commit，總計幾 KB。

## 3. 正規化（決定 diff 準不準的關鍵）

同樣的方塊內容必須產生**完全相同的位元組**，否則雜湊不同 → 假 diff、無法去重。MC 存檔時有很多非確定性：

| 項目 | 處理 |
|---|---|
| 調色盤順序（palette 在 MC 裡是任意順序） | 依 YZX 走訪順序，以「第一次出現」重新編號調色盤，重新打包 bit 陣列 |
| block state 屬性順序 | 屬性依鍵名排序 |
| NBT compound 鍵順序 | 依鍵名排序後序列化 |
| 光照（BlockLight/SkyLight）、Heightmaps、`isLightOn` | **丟棄**，寫回時讓伺服器重算 |
| `LastUpdate`、`InhabitedTime`、`Status` | 丟棄（寫回時固定為 full / 保留目標世界原值） |
| POI（村民工作站） | 預設丟棄，由方塊重新推導（待驗證：各版本 POI 重建行為，列入 [09](09-roadmap-open-questions.md) 驗證項目） |
| block entity 內的暫態欄位（熔爐燃燒時間、生怪磚倒數…） | 依方塊類型設定「忽略欄位」表；預設保留容器內容（箱子裡的東西是建築的一部分） |
| 實體的暫態欄位（`Motion`、`FallDistance`、`Fire`、`Air`、年齡…） | 同上，以類型白名單/黑名單處理；座標量化 |

正規化後的 section 格式建議：自訂的精簡二進位（版本號 + 調色盤字串表 + 打包索引 + 排序過的 block entity NBT），再以 zstd 壓縮。**不直接存 MC 原生 NBT**，好處是 MC 改存檔格式時只影響轉換層。

## 4. 追蹤範圍（已決定，2026-09-30）

| 資料 | 預設 | 理由 |
|---|---|---|
| 方塊、block entity（箱子內容、告示牌文字、旗幟…） | 追蹤 | 建築本體 |
| 「靜態」實體：盔甲座、物品展示框、畫、display entity、船/礦車 | 追蹤 | 建築師大量使用 |
| **所有生物**（牛、村民、怪物、無 AI 的裝飾生物…） | **追蹤** | 有人用生物當建築素材（凍結的生物雕像、村民交易所、動物園）。生物會移動所帶來的問題見 §7 |
| 掉落物、經驗球、投射物、掉落中的方塊、點燃的 TNT | 預設不追蹤（可開啟） | 暫態，不是「生物」；但可在設定中開啟 |
| 玩家資料（背包、位置、進度） | **不追蹤** | 不是世界的一部分；而且切換分支時沒收玩家的東西會很奇怪 |
| 地圖（map_*.dat）、記分板、世界邊界 | 可選 | |
| biome | 追蹤（獨立 blob） | 有些建築師會改 biome |

`worldgit.toml` 範例（草案）：

```toml
[track]
dimensions = ["minecraft:overworld"]
# 只追蹤某個範圍；省略 = 全世界已生成的 chunk
bounds = { min = [-2000, -64, -2000], max = [2000, 320, 2000] }
entities = "all-but-players" # 預設；none | static | all-but-players | 自訂白名單
transient_entities = false    # 掉落物、經驗球、投射物…
block_ticks = false

[ignore]
areas = [ { name = "刷怪塔", min = [500, -64, 500], max = [600, 320, 600] } ]
block_entity_fields = { "minecraft:furnace" = ["BurnTime", "CookTime"] }
```

## 5. 雜湊

- 若採用 JGit 後端（見 [03](03-storage-backend.md)）：沿用 git 的 SHA-1（或 git 的 SHA-256 模式），換取與 git 生態相容。
- 若自製物件庫：BLAKE3，更快。

## 6. 「還沒生成的 chunk」

base 裡不存在、分支裡被生成的 chunk（玩家跑去探索）→ 算新增。合併時如果兩邊都生成了同一個 chunk 且都沒被玩家改過，理論上內容一樣（同種子）→ 雜湊相同 → 自動無衝突。**但前提是兩邊 MC 版本與世界生成設定相同**，否則會出現大量無意義衝突。建議：可選擇「只追蹤玩家改動過的 chunk」模式，自然生成的地形不進版本控制（需要時由種子重新生成）。

## 7. 追蹤生物帶來的問題與對策

決定追蹤所有生物後，最大的問題是**生物會自己動**：牛走兩步、村民換工作、雞生蛋，都會讓世界「永遠不乾淨」，而且合併時會出現大量無意義的衝突。對策：

| 問題 | 對策 |
|---|---|
| 位置/視角每 tick 都在變 | 正規化時忽略 `Motion`、`Rotation`、`FallDistance`、`OnGround` 等欄位；位置量化到方塊格，並設**容許距離**（預設例如 ≤ 2 格內的移動不算變動，可設定）。`NoAI` 生物則精確比對 |
| AI 內部狀態（`Brain` 記憶、`InLove`、`Age` 計時、仇恨目標…） | 以實體類型維護「忽略欄位」表；只比對「玩家在意的」欄位：類型、名稱、裝備、`NoAI`、`Silent`、`Invulnerable`、`PersistenceRequired`、變種（顏色/花紋）、村民職業與交易、拴繩、坐騎 |
| 生物跨 chunk 移動 | 儲存上仍以 chunk 分組，但 diff/merge **以 UUID 為全域鍵**：A chunk 消失、B chunk 出現同一 UUID → 視為「移動」而不是「刪除 + 新增」 |
| 切換/還原時的重複生物 | 套用前以 UUID 在整個世界（已載入 + 目標 chunk）查找並移除舊的那隻，再放入目標版本，避免同一隻出現兩次 |
| 自然生成/自然死亡的怪物（夜晚刷怪） | 非 `PersistenceRequired` 的敵對生物預設視為暫態（可設定改為追蹤）；被命名牌命名、或 `PersistenceRequired=true` 的一律追蹤 |
| `status` 雜訊 | `status` 分開顯示「方塊變動」與「生物變動」；自動 commit 可設為「只有生物變動時不觸發」 |
| 生物繁殖/死亡造成的合併衝突 | 同一 UUID 兩邊都改 → 衝突；只有一邊生出新 UUID → 直接加入。衝突區域以生物所在位置併入方塊衝突分群 |

這一段的正規化規則會是 Phase 0 的重點驗證項目之一。
