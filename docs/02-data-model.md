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
commit                               # 屬於某一個維度的 repo（§2.1）
 ├─ parents: [..]                    # merge commit 有兩個
 ├─ meta: author(s), message, time, mcDataVersion, dimension, source(plugin/mod/cli), auto?
 └─ root tree
     ├─ .wgignore                    # 該維度的排除規則（§5）
     ├─ region r.0.0                 # tree：最多 1024 個 chunk
     │   ├─ chunk c.3.7              # tree
     │   │   ├─ s.-4 … s.19          # blob：section（方塊 + 該 section 內的 block entity）
     │   │   ├─ biomes               # blob：整個 chunk 的 biome（很少變，獨立出來避免假 diff）
     │   │   ├─ entities             # blob：該 chunk 內被追蹤的實體（依 UUID 排序）
     │   │   ├─ ticks                # blob：排程中的 block/fluid tick（可選，紅石/水流才需要）
     │   │   └─ structures           # blob：chunk 的結構資料（村莊、要塞…），空則不存（Phase 0 補上，否則還原出的 chunk 會遺失結構資訊）
     │   └─ …
     ├─ …
     ├─ world-meta                   # 只在主世界 repo：被追蹤的 level.dat 欄位子集（出生點、gamerule…）、地圖、記分板
     └─ dimensions                   # 只在主世界 repo：其他維度 repo 的清單（維度 id → repo 位址）
```

只改了一格方塊時，一次 commit 新增的物件：1 個 section blob + chunk tree + region tree + root tree + commit，總計幾 KB（Phase 0 實測約 5–6 KB、9 個物件；當時還有一層 dimension tree）。

### 2.1 每個維度一個 repo（已決定，2026-10-01）

主世界、地獄、終界，以及資料包加入的自訂維度，**各自是一個獨立的 git repo**（Phase 0 原型是一個 repo 內再分 dimension tree，正式版改掉）。

- **理由**：單一 repo 的大小與 pack 數量變小，比較容易放進一般 git 託管（決定 #17）；只關心主世界的人不必下載地獄與終界；各維度的 `.wgignore`、自動 commit 頻率可以不同。
- **主世界 repo 是入口**：放 `world-meta` 與 `dimensions` 清單；`clone` 主世界 repo 時，依清單一併 clone 其他維度（可用 `--dimension` 只拿部分維度）。沒有被 clone 的維度在世界裡就是未生成，打開時會正常生成。
- **跨維度的一致性**：四端預設把所有維度當成一組操作——`commit`、`switch`、`branch`、`push`/`pull` 會對每個維度 repo 做同名的動作；各維度的 commit 在 trailer 記下同一個 `WorldGit-Snapshot` id，`log` 可依此把同一次存檔點顯示成一列。沒有變動的維度不產生 commit。
- **不保證跨 repo 原子性**：例如 push 主世界成功、地獄失敗。客戶端要能重試，且 Hub 依 snapshot id 顯示「部分推送」。
- 也可以只對單一維度操作（`--dimension minecraft:the_nether`），此時其他維度不動。全空氣且無 block entity 的 section 不存（缺檔即空氣）。

## 3. 正規化（決定 diff 準不準的關鍵）

同樣的方塊內容必須產生**完全相同的位元組**，否則雜湊不同 → 假 diff、無法去重。MC 存檔時有很多非確定性：

| 項目 | 處理 |
|---|---|
| 調色盤順序（palette 在 MC 裡是任意順序） | 依 YZX 走訪順序，以「第一次出現」重新編號調色盤，重新打包 bit 陣列 |
| block state 屬性順序 | 屬性依鍵名排序 |
| NBT compound 鍵順序 | 依鍵名排序後序列化 |
| Paper 加的欄位（chunk/section 的 `starlight.*`；實體的 `Paper.*`、`Bukkit.*`、`Spigot.*`、`WorldUUID*`） | 丟棄（Phase 0 發現） |
| 實體 `attributes` 列表順序（每次存檔可能不同） | 依 `id` 排序；沒有 modifier 的 `movement_speed` 由伺服器惰性補上，比較時略過（Phase 0 發現） |
| 光照（BlockLight/SkyLight）、Heightmaps、`isLightOn` | **丟棄**，寫回時讓伺服器重算（Phase 0 已驗證：寫回時移除光照、`starlight.*`、Heightmaps，Paper 兩版載入後重算的光照與原本逐 nibble 相同） |
| `LastUpdate`、`InhabitedTime`、`Status`、`PostProcessing`、`xPos/yPos/zPos` | 丟棄（寫回時固定為 full / 保留目標世界原值）；`DataVersion` 記在 world-meta |
| structures 的 `References` | chunk 座標集合的 long[] 依數值排序；MC 的 LongSet 重寫可能重排，同一集合不得造成假 diff（Phase 1 真正 Paper 地獄維度發現） |
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
| **所有已完全生成的 chunk**（`Status: full`，包含沒被玩家動過的自然地形） | 追蹤（可設定成只存玩家改過的 chunk，預設關閉，見 §7.1） | 從 repo 下載時要能拿到完整、可以直接開的世界，見 §7。生成到一半的邊緣 chunk（玩家看不到）不追蹤：Phase 0 測試世界中 2255 個 chunk 只有 626 個是 full |
| 玩家資料（背包、位置、進度） | **不追蹤**（寫死，不能用 `.wgignore` 反向開啟） | 不是世界的一部分；切換分支時沒收玩家的東西會很奇怪 |
| 地圖（map_*.dat）、記分板、世界邊界、gamerule | 追蹤 | 屬於 `world-meta`，同樣可排除 |

## 5. `.wgignore`：類似 `.gitignore` 的排除規則（已決定，2026-09-30）

`.wgignore` 是 repo 根目錄下的一個**被版本控制的檔案**，跟 `.gitignore` 一樣會隨 commit 一起存、隨 push/pull 分享，所以整個團隊用同一套規則；不同分支也可以有不同規則。

git 的 `.gitignore` 只能比對路徑，但世界需要依「座標範圍」「實體類型」「NBT 欄位」排除，所以語法是以關鍵字開頭的一行一規則（草案）：

```gitignore
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
field worldgit:map *                 # 地圖的 world-meta 不追蹤

# ── 否定：把前面排除的東西加回來（跟 .gitignore 的 ! 一樣，後面的規則優先）──
!entity minecraft:item in area 100 60 100 120 80 120   # 展示用的掉落物
```

行為規則：
- 被忽略的內容在 `commit` 時不存、`status`/`diff` 不顯示，`switch`/`restore` 時**保持活世界裡的現狀不動**（對應 git 的「untracked 檔案不會被 checkout 覆蓋」）。
- 修改 `.wgignore` 本身也是一個變動，要 commit 才生效。之前已追蹤、之後才被忽略的東西，會在下一次 commit 從快照中移除（等同 `git rm --cached`），並在 `status` 裡提示。
- 每個維度是獨立 repo（§2.1），所以 `.wgignore` 也是每個維度一份；不想追蹤整個維度，就不要為它建 repo（`init --dimension` 只選要的維度），不再用 `dimension` 規則。
- Phase 0 實測（`experiments/06-survival-scale/`）：生存模擬中 `entity * !persistent` 讓 commit 大小少 15%、實體增減少 98%；只排除掉落物只少 0.5%。容許距離 0／2／4 格時被判為修改的實體 295／192／174，維持預設 2 格。依決定 #2 預設仍全部追蹤，`entity * !persistent` 放在範本裡當作**建議取消註解的第一行**，並在 `status` 實體雜訊多時提示。
- `/wg init` 會詢問要用哪份範本（§5.1）；沒有指定時用創造模式範本，只有註解掉的範例行，實際上什麼都不排除，符合「預設全部追蹤」。
- 另外還有一份**不進版本控制**的本機設定 `worldgit.yml`，只放個別伺服器的設定（自動 commit 頻率、Hub 位址、權限），不放追蹤規則。所有設定檔都用 YAML（決定 #21）。

### 5.1 創造模式與生存模式範本（已決定，2026-10-01）

WorldGit 主要給建築（創造模式）玩家用，但生存伺服器也會用，兩者的雜訊來源差很多，所以 `init` 提供兩份範本（`/wg init --template creative|survival`，CLI 與模組同名參數）。範本只是起點，產生後就是一般的 `.wgignore`，可以自由修改。

**創造模式（`creative`，預設）**：建築世界通常關掉生怪、生物多半是刻意擺放的，維持全部追蹤。

```gitignore
# WorldGit 創造模式範本：預設追蹤一切（玩家除外）。
# 有需要時取消註解：
# entity minecraft:item              # 掉落物
# entity minecraft:experience_orb    # 經驗球
# entity #minecraft:arrows           # 箭
# entity * !persistent               # 自然刷出、會自然消失的生物
```

**生存模式（`survival`）**：依 Phase 0 量測（`experiments/06-survival-scale/`），自然刷怪佔實體增減的 98%，排除會消失的東西。被命名、馴服、拴住、`PersistenceRequired` 的生物，以及盔甲座、展示框、畫、display entity、船、礦車仍然追蹤。

```gitignore
# WorldGit 生存模式範本：排除會自然消失、反覆出現的實體。
entity * !persistent                 # 自然刷出、會自然消失的生物
entity minecraft:item                # 掉落物
entity minecraft:experience_orb      # 經驗球
entity #minecraft:arrows             # 箭（含光靈箭）
entity minecraft:falling_block       # 掉落中的方塊
entity minecraft:tnt                 # 點燃的 TNT
# 熔爐等的燃燒進度本來就忽略；要更安靜可再加：
# field minecraft:villager Gossips
```

`!persistent` 的確切判斷（哪些實體算會自然消失）由各版本的 adapter 決定，不只看 NBT 旗標；Phase 0 原型只讀 `PersistenceRequired`。

## 6. 雜湊

- 若採用 JGit 後端（見 [03](03-storage-backend.md)）：沿用 git 的 SHA-1（或 git 的 SHA-256 模式），換取與 git 生態相容。
- 若自製物件庫：BLAKE3，更快。

## 7. 已生成的地形也要追蹤（已決定，2026-09-30）

**決定：所有已生成的 chunk 都進版本控制。** 從 Hub 下載或 `clone` 時拿到的是完整世界，打開就跟原本一模一樣，不需要靠種子重新生成、也不怕 MC 版本改變地形生成。

背景說明（之前的疑問）：玩家跑去探索時，遊戲會生成新的 chunk，雖然沒人在上面蓋東西，它仍然是「世界多出來的內容」。之前的選項是「只追蹤玩家改過的 chunk，自然地形靠種子重建」，這樣 repo 比較小，但下載下來的世界可能不完整或跟原本不同，所以不當預設，改成可選設定（§7.1）。

代價與對策：
- **第一次 `init` 比較大**：要把整個已生成的世界存一次。只算一次，之後都是增量；全空氣、全石頭這類相同的 section 會去重成同一個物件。
- **「兩邊都探索了同一塊新區域」的合併**：同種子、同 MC 版本下生成出來的 chunk 內容相同，雜湊相同，直接無衝突。如果版本不同導致生成結果不同，而且兩邊都沒有玩家改動紀錄（[04](04-commit-and-status.md) §4 的作者歸屬可以判斷），就自動採用目前分支的版本，並在合併報告中列出，不當成需要人處理的衝突。

### 7.1 選項：只存玩家改過的 chunk（已決定，2026-10-01，預設關閉）

上面的「全部追蹤」是**預設值**。使用者可以在 repo 設定中設定 `track: modified-only`，此時沒被玩家改過的自然地形不存進 repo。

- **設定放在 repo 裡**（主世界 repo 的 `world-meta`，跟世界一起被版本控制），不放本機的 `worldgit.yml`：clone 的人必須知道哪些 chunk 是故意不存的。
- **什麼算「改過」**：WorldGit 觀察到的任何一次方塊、block entity、biome 或被追蹤實體的變動（[04](04-commit-and-status.md) 的偵測機制）。開啟前就存在的 chunk，以 `init` 時與種子重新生成結果比對判斷；比對太貴時可選擇保守地全部視為改過。
- **clone 與切換**：沒存的 chunk 由伺服器依種子重新生成。需要相同的種子、MC 版本、世界生成設定與資料包，這些記錄在 `world-meta`，不一致時 `clone`/`switch` 要警告，因為重新生成的地形可能跟原本不同。
- **switch/restore**：沒存的 chunk 視同 untracked，不會被覆蓋。
- 好處是 repo 小很多（大型生存伺服器大多數 chunk 只是探索過）；代價就是上面這些相容性限制，所以預設關閉。

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

## Phase 1 實作細節（2026-10-01）

- 正式 blob 使用 `WG + kind + version=1` envelope 與 zstd；與 Phase 0 blob 不相容。chunk section 可解回不可變中性模型，NBT IO 另有深度/大小限制。
- bare repo 沒有一般 working tree。可編輯 `.wgignore` 位於 `<repo>/.wgignore` sidecar，commit 寫進 root blob；repo 設定的 sidecar 為 `worldgit-repo.yml`，主世界提交於 `world-meta/worldgit.yml`，其他維度提交於 root `worldgit.yml`，使單獨使用維度 repo 也能保留設定。本機 `worldgit.yml` 位於一組 repo 的上層，不進版本控制。
- `track: modified-only` 本次只完成讀寫、init 寫入、版本控制與明確提示，**自然 chunk 篩選尚未生效**。
- `area` 含端點；biome 以 4×4×4 sample 起點判斷，排除 sample 用空字串表示，整個 section 的 sample 都排除則不存。structures 維持 chunk 原子 blob，不裁切結構的 bounding box。
- `field` 支援 `*` wildcard 與 `!field` 加回普通內建忽略欄位；id/UUID/Pos 是中性實體模型必要欄位，不能移除。玩家永遠不追蹤。
- `EntitySemantics` 讓版本 adapter 提供 tag 與 persistence；離線 `EntityTagRegistry` 帶兩版 vanilla tag 資料，依 level.dat 的 `DataPacks.Enabled` 順序載入資料夾/ZIP 的 entity_type tags，支援 replace、tag 參照與 optional entries。參照在覆寫完後才展開，停用的 pack 不載入；缺少 pack 會提示，未知 tag、必要參照遺失、循環、schema/大小錯誤會明確報錯。模組注入的未知內建 pack 仍需線上 adapter。persistence 的明確旗標與常見不會消失的實體類型是保守近似，不能精確替代伺服器的 despawn 判斷。
- world-meta 的 field selector 使用 `worldgit:level`、`worldgit:map`、`worldgit:scoreboard`、`worldgit:boss_events`、`worldgit:gamerules`、`worldgit:border`、`worldgit:worldgen`；比對 canonical NBT 根 compound 欄位。`field worldgit:map *` 排除所有地圖，`!field` 同樣後面優先。空 compound 不存，活世界資料不刪除；repo 設定不受這些規則影響。
- 世界 metadata 擷取 1.21.11 的 `game_rules`、spawn、worldgen、邊界，以及 26.2 各維度 `data/minecraft/{game_rules,world_border,world_gen_settings}.dat`，地圖/記分板另存 canonical NBT。完整跨版本 world-meta 的還原/DataFixer 流程仍是 Phase 2。
