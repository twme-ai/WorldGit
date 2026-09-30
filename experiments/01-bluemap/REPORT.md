# 實驗 B：BlueMap core 能否以 WorldGit 的資料產生 tile

日期 2026-09-30。BlueMap v5.28（2026-09-25，main 為 commit 088123a）。

> 本報告由執行實驗的子代理撰寫，由主代理存檔（子代理的檔案寫入被系統限制）。

## 0. 結論

- **建議直接嵌入 BlueMap core（釘死版本），不 fork。** 只用 `core`，不用 `common`。tile 排程、快取鍵、lowres 合成、儲存對應由 Hub 自己寫。
- **原型成功。**
  - 沒改 BlueMap 任何程式碼，只實作它的介面：`World`、`Chunk`、`MapSettings`，儲存直接用內建 `FileMapStorage`。
  - 從記憶體中的假 chunk 產生了 hires tile（`.prbm.gz`）和 lowres tile（`.png`）。
  - 示範了「內容雜湊當 key，只重繪有變動的 tile」。
  - 1.21.11、26.2、26.3 三個版本的原版資源都跑通。
- **最大風險：**
  1. BlueMap 5.x 要求 **Java 25**（`bluemap.java.gradle.kts` 的 toolchain 設為 25，core 用了 `_` 未命名變數）。Hub 規劃是 Java 21，得升 Java 25，或把渲染做成獨立行程。
  2. **lowres 是累積式的**：`LowresLayer` 讀回既有 tile、改一小塊再存回，不是 chunk 的純函數。不能直接套「tile 鍵 = chunk tree 雜湊」，要繞過 `LowresTileManager`。
  3. core 內部介面沒有穩定性承諾（穩定的是 `bluemap-api` 這個外掛 API）。近一年 `World`/`Chunk`/`Region`/`MapStorage` 被改 5 次，且每月發版。
  4. WorldGit 丟棄光照，但 BlueMap 的著色和洞穴剔除依賴光照，要自己補近似值。
- **沒驗證到的部分：**
  - **hires tile 在 webapp 中的實際畫面沒有截圖。** webapp 已建好並用靜態伺服器掛上輸出，但無頭 Chrome 截圖（含 `--virtual-time-budget`）80 秒內都沒結束，因為 WebGL 渲染迴圈不會閒置。
  - hires 只驗到：產生了非空的 `.prbm.gz`（每張 7.8~16 KB），`settings.json` 正確。
  - lowres PNG 看過，是預期的樣子：綠色草地加上小建築的像素。
  - 樓梯方向、柵欄連接、AO 等細節的正確性沒驗證。
  - biome 用 `Biome.DEFAULT`（單一色調），沒驗證群系染色。
  - block entity 與實體的渲染 pass 沒測，原型的 `iterateEntities` 回傳空。

## 1. 模組結構與依賴

`settings.gradle.kts` 的模組：`:core`、`:common`、`implementations/{cli,fabric,forge,neoforge,paper,spigot,sponge}`，另有 git submodule `api/` 用 `includeBuild` 組合。

| 模組 | 內容 | 主要依賴 | 需要 MC 伺服器 |
|---|---|---|---|
| `api`（bluemap-api 2.8.1） | 外掛公開 API | flow-math、gson | 否 |
| `core` | 渲染引擎：world 抽象、hires/lowres 渲染、資源包解析、儲存（file/sql）、`BmMap` | bluemap-api、aircompressor、bluenbt、caffeine、commons-dbcp2、configurate、lz4-java | **否** |
| `common` | 設定、指令、`RenderManager`、`WorldRegionUpdateTask`（增量更新）、內建 HTTP、webapp 打包 | core、adventure、bluecommands | 否，但很重（建置時用 node-gradle 下載 Node 20 跑 npm） |
| `implementations/*` | 各平台接合層 | common + 平台 API | 是 |
| `common/webapp` | 檢視器 | 見 §6 | — |

- **core 可以在沒有 MC 伺服器的一般 JVM 程式中使用**，原型就是一個普通的 `main()`。
- BlueMap 已把 core 發佈到 `https://repo.bluecolored.de/releases`，座標 `de.bluecolored:bluemap-core:5.28`。
- **發佈瑕疵：** 5.28 的 POM 中 `bluemap-api` 的版本欄位是空的，Gradle 會解析失敗。要自己加上 `de.bluecolored:bluemap-api:2.8.1`，原型的 `build.gradle.kts` 已處理。
- 全域靜態狀態：`BlueMap.THREAD_POOL`、`BlueMap.SCHEDULER`、`Logger.global`、各種 `Registry`。嵌入沒問題，但一個 JVM 只能有一套設定。

## 2. 世界資料的讀取抽象

**有乾淨、可替換的介面，NBT/anvil 完全在介面之外。**

- `core/.../world/World.java`（interface）：渲染實際只用到 `getChunk`、`getChunkAtBlock`、`getDimensionType`。
  - `getRegion`、`listRegions`、`preloadRegionChunks`、`invalidateChunkCache`、`createRegionWatchService` 是給 `common` 的更新掃描用的，直接呼叫 `renderTile` 時不會用到。
  - `iterateEntities` 只在實體渲染 pass 用。
- `core/.../world/Chunk.java`（interface，**所有方法都有 default**，預設回傳空氣或 0）。我們要覆寫的：
  - `getBlockState(x,y,z)`：回傳 `BlockState(Key, Map<String,String>)`。
  - `getMinY`、`getMaxY`：決定每一柱的掃描範圍。
  - `getLightData`：見下表。
  - `getBiome`：有 `Biome.DEFAULT` 可用。
  - 選用：`getBlockEntity`、`iterateBlockEntities`、`isGenerated`。
- `world/mca/*`（約 2300 行）才是讀 region/chunk 的實作（`MCAWorld`、`MCARegion`、`MCAChunkLoader`，依 DataVersion 分成 `Chunk_1_13/1_15/1_16/1_18`）。**我們完全不需要它**，所以 MC 存檔格式的變動（如 26.1）不會影響我們。
- `WorldLoader` / `WorldLoaderType.REGISTRY` 是「從路徑載入 world」的外掛點，只有 `ANVIL` 一種。我們不需要它，直接 new 自己的 `World` 交給 `BmMap` 即可。

| 項目 | 結論 |
|---|---|
| 原版 NBT | 介面層沒有用到。 |
| 光照 | 介面有，而且渲染會用。`ResourceModelRenderer`（方塊與實體）、`LiquidModelRenderer` 把天光/方塊光寫進頂點屬性，lowres 也存 blockLight。WorldGit 丟棄光照，所以要近似。原型做法：柱頂以上 sky=15，以下 0；block=0。`RenderSettings.isIgnoreMissingLightData()` 讓 `common` 的更新任務不會因為缺光照就跳過 tile。 |
| heightmap | **不需要。** `getOceanFloorY` 只用在洞穴偵測（`ExtendedBlock.isRemoveIfCave`），並有 `hasOceanFloorHeights()` 守衛。回傳 false 時退化成 `y < removeCavesBelowY`（預設 55）。 |
| inhabitedTime | 只有 `common` 的 `minInhabitedTime` 過濾會用，直接渲染時不用。 |

**最小實作集合：**
- `World`：`getId`、`getDimensionType`、`getChunkGrid`、`getRegionGrid`、`getChunk`、`getChunkAtBlock`。其餘丟例外或空實作，見 `FakeWorld.java`。
- `Chunk`：`getBlockState`、`getMinY`、`getMaxY`、`getLightData`、`getBiome`。
- `MapSettings`：約 25 個 getter，見 `Settings.java`，對應 `MapConfig` 的預設值。
- `World` 的實作必須**執行緒安全**，因為渲染 pass 會在 thread-local 下多執行緒呼叫。

## 3. 渲染流程與輸出格式

入口是 `BmMap.renderTile(Vector2i tile)`，內部呼叫 `HiresModelManager.render(tile, tileMetaConsumer, save)`。

**hires tile**
- 座標切分：`new Grid(32, offset 2)`，`settings.json` 顯示 `hires.translate: [2,2]`。tile 以 32 格為單位、偏移 2 格，**不與 chunk（16 格）對齊**，所以一張 tile 最多會碰到 3×3 個 chunk。
- 每個 (x,z) 柱由上往下掃到 `getMinY`。`BlockStateModelRenderer` 依原版 blockstate/model 展開成三角形，並剔除被遮住的面。
- 最後 `model.sort()`，寫成 **PRBM**（BlueMap 自訂的二進位網格格式，`PRBMWriter`），gzip 後存成 `tiles/0/x<X>/z<Z>.prbm.gz`。
- 面剔除、水、樹葉、AO 都要讀鄰居方塊，所以 tile 的內容取決於**向外 1 格**的方塊。

**lowres tile**
- `BlockRenderPass` 每算完一柱就呼叫 `TileMetaConsumer.set(x,z,color,height,blockLight)`，由 `LowresTileManager` 寫進 lowres 圖層。
- 格式：`Grid(500)`，每格 1 像素，PNG，路徑 `tiles/1/x<X>/z<Z>.png`。
- `lodCount=3`、`lodFactor=5`，較高層的 LOD 由下一層縮小合成（`tiles/2`、`tiles/3`）。
- **累積式：** `LowresLayer` 會從 storage 讀回既有 tile、改像素再存回（有 `pendingChanges`、`save()`）。

**增量更新**（在 `common`，不在 core）
- `WorldRegionUpdateTask` 用每個 chunk 的 `lastModified` 當作 hash，與 `MapChunkState` 存的上次值比較。tile 涵蓋的任何一個 chunk 不同就重繪（`checkChunksHaveChanges`）。
- `TileActionResolver` / `TileState` 決定 RENDER / NONE / DELETE。
- 它**不看鄰居 chunk**，所以 chunk 邊界偶爾會留下舊的邊緣。

**能否以「一組 chunk」為單位只重繪對應的 tile：可以，而且很直接。**
- `BmMap.renderTile(tile)` 是公開方法，不依賴 `WorldRegionUpdateTask`。
- 自己用 `HiresModelManager.getTileGrid()` 算出哪些 tile 涵蓋這些 chunk，逐張呼叫即可。
- 原型的第二輪就是這樣做：改一個 chunk，9 張 tile 只重繪 1 張。

## 4. 原型

**內容**

| 檔案 | 作用 |
|---|---|
| `build.gradle.kts` | Gradle，Java 25 toolchain，依賴 `bluemap-core:5.28` 與 `bluemap-api:2.8.1` |
| `src/main/java/wg/FakeWorld.java` | 記憶體中的 `World` 與 `Chunk`（96 格高、方塊陣列、天光近似、內容雜湊） |
| `src/main/java/wg/Settings.java` | `MapSettings` 實作 |
| `src/main/java/wg/Proto.java` | 載入資源、建場景、建 `BmMap`、依雜湊渲染兩輪 |

**場景：** 6×6 chunk（-1..4）的地面（基岩/石頭/泥土/草方塊），在 chunk (1,1)、(2,1) 上放：
- 石磚平台
- 橡木樓梯（4 個方向、上下顛倒、外角）
- 一排會相連的橡木柵欄
- 原木加樹葉
- 玻璃與染色玻璃
- 水池
- 羊毛

**執行**
```
cd experiments/01-bluemap
gradle run --max-workers=1 --no-daemon                 # 預設 MC 1.21.11
gradle run -PmcVersion=26.2 --max-workers=1 --no-daemon
```
第一次執行會從 Mojang 下載 client jar 到 `.work/bluemap-proto/data/`（以 SHA-1 驗證，見 `MinecraftVersion.java`）。

**實測**（3 核心，同時有其他代理在跑）
```
[resources] mc=1.21.11 ~3.1 s (26.2: 3.2 s, 26.3: 3.7 s)   (jar 已下載後的解析時間)
[pass 1 cold] rendered=9 skipped=0  ~1.8–2.0 s   (~200 ms/張，JIT 冷，場景多為地面)
[pass 2 chunk(1,1) changed] rendered=1 skipped=8  ~0.22–0.28 s
```
記憶體用量沒有量測。

**hires 網格的實際外觀（樓梯、柵欄、AO）沒驗證**，原因見 §0。建議用有 WebGL 的瀏覽器人工檢查：
- 已建好的 webapp 在 `.work/bluemap-proto/web/`，`maps/wg-demo` 是符號連結。
- 用 `python3 -m http.server` 提供服務，`settings.json` 設 `clientDecompression: true`。

## 5. 介接成本與 tile 快取鍵

**快取鍵**不能只是 chunk tree 雜湊，至少要包含：

`key = H( 渲染器版本（BlueMap 版本 + adapter 版本 + MapSettings） ‖ MC 資源版本 ‖ 涵蓋範圍（向外 1 格）內每個 chunk tree 的 oid )`

- 一張 hires tile 因為偏移 2 格，最多跨 3×3 個 chunk；向外擴 1 格後仍在 3×3~4×4 之間。
- region tree 太粗（512×512 格）。要用 region tree 內各 chunk entry 的 oid；JGit 讀一棵 region tree 就能拿到 1024 個 entry 的 oid，成本低。
- BlueMap 自己的 chunk hash 不含鄰居，我們的 key 必須把外擴 1 格的鄰居算進去，否則邊界會出現過期的面。

**lowres 的處理**
- 不要用 `BmMap.renderTile`，它綁死了 `LowresTileManager`。
- 改呼叫 `bmMap.getHiresModelManager().render(tile, myMetaConsumer, true)`。`myMetaConsumer` 收下每張 hires tile 的 32×32 個 `(color,height,blockLight)`，依 hires key 快取，再由 Hub 自己合成 lowres 各層。
- 合成可複用 `LowresLayer` / `LowresTile` 的縮放邏輯，或自己寫，約 200~400 行。

**工作量估計（未實作，是子代理的估計）**

| 項目 | 行數 |
|---|---|
| section blob 解碼成 `Chunk`（調色盤、YZX 索引、min/maxY、LRU 快取、執行緒安全） | 300~500 |
| 天光/方塊光近似，加上 biome 對應（用 `DataPack`） | 200~300 |
| `GridStorage`/`MapStorage` 對應到 Hub 物件庫（或先寫暫存目錄再搬） | 250~400 |
| tile 排程（哪些 chunk 變動 → 哪些 tile 要重繪、push 後的工作佇列），不用 `common` 的 `RenderManager` | 300~500 |
| lowres 合成 | 200~400 |
| MC 版本管理（DataVersion → 版本 id → client jar 快取 → `TextureGallery` 持久化） | 200~300 |
| HTTP 路徑對應 webapp，加上產生 `settings.json` | 200~300 |

合計約 **1500~2500 行 Java，2~3 人週**，不含檢視器客製與金標準 tile 比對測試。

## 6. 資源管線、版本、webapp、授權

**資源管線**
- `MinecraftVersion.load(id, dataRoot, allowDownload)` 從 Mojang 版本清單取得 client jar 的網址與 SHA-1，下載後校驗。
- 資源包（blockstates/models/textures）和資料包（biome、dimension_type）都直接從 jar 讀取（`ResourcePack.loadResources`、`DataPack.loadResources`），不需要伺服器。
- 另外附有 `resourceExtensions.zip`（`core/src/main/resourceExtensions/{mc1_15,mc1_17,mc1_20_3,mc1_21_9,mc26_1,...}`），補上方塊屬性、顏色、床、告示牌模型。
- 這與 docs/10 §3「Hub 後端下載 client jar」的方針一致，不重新散布 Mojang 的資源。

**MC 版本**
- 資源包最早支援 1.13，資料包需要 ≥ 1.19.4。
- **1.21.11**：實測可載入並渲染（使用 extension `mc1_21_9`）。
- **26.2**：實測可載入並渲染（使用 extension `mc26_1`；git log 有「Add initial support for 26.1 world and resource changes」，2026-03-08）。26.3 也可載入。
- Mojang 目前的 latest release 是 26.3，snapshot 是 26.4。
- 「載入並渲染不出錯」不等於畫面正確，新方塊/模型需要人工抽查。

**webapp**（`common/webapp`）
- 技術棧：Vue 3、Three.js 0.186、Vite 6、hammerjs、vue-i18n。
- `npm ci && npm run build` 約 5 秒就建成純靜態的 `dist/`，不需要 Java 端或 Gradle。
- 它讀 `settings.json`，再讀 `maps/<id>/settings.json`、`textures.json(.gz)`、`tiles/...`。
- 設定 `clientDecompression: true` 時，會請求 `.prbm.gz` 並在瀏覽器解壓，所以**純靜態伺服器或 CDN 就能提供服務**。
- 可單獨顯示我們產生的 tile（透視、平面、自由飛行三種視角），也支援注入自訂的 `scripts`/`styles`。
- 它是 BlueMap 專用的檢視器，沒有 commit、diff、衝突、座標留言的概念。
- 建議定位：當作「總覽地圖」頁（docs/10 §4 的遠景欄）；近景/diff/衝突走瀏覽器端自寫的網格。

**授權**
- 根目錄 `LICENSE` 與 `api/LICENSE` 皆為 MIT（© Blue、contributors），webapp 在同一個 repo，授權相同，只需保留授權聲明。
- 傳遞依賴**沒有逐一查證**，正式採用前要做一次授權盤點。

**穩定性與發版**
- 穩定的公開 API 是 `bluemap-api` 2.8.1；core 雖然有發佈到 Maven，但沒有穩定性承諾。
- 發版頻繁：2025-12 的 v5.15 到 2026-09-25 的 v5.28，約 9.5 個月 14 個 tag（全歷史 127 個 tag、1731 個 commit）。
- 近一年 `World`/`Chunk`/`Region`/`MapStorage` 被改了 5 次：
  - 2025-11 移除 World 的 spawn 概念。
  - 2026-03 支援 26.1。
  - 2026-06 追蹤 region 更新。
  - 2026-09 `Region.fingerprint()` 的預設值改為 0，另加 region-file-check-interval。
- 多數是新增或 default method，但每次升級都要重新編譯並做回歸測試。
- 專案活躍（最新 commit 2026-09-28），但以單一維護者為主，有 bus factor 風險。

## 7. 風險與對策

| 風險 | 對策 |
|---|---|
| 要求 Java 25，Hub 若固定 Java 21 就無法載入 | Hub 改用 Java 25（LTS），或把渲染做成獨立的 worker 行程或容器 |
| lowres 是累積式 | 自己收集 meta、自己合成（§5） |
| 缺光照導致著色和洞穴判斷不準 | 用天光近似加上發光方塊的 block light，實作保留在 `Chunk` 內，日後可替換 |
| 內部 API 不穩定 | 釘死版本；BlueMap 的型別藏在 Hub 內部的 `TileRenderer` 介面後面；升級時用金標準 tile 比對做回歸 |
| 全域靜態狀態、只能單一實例 | 一個 JVM 一套設定；每個 MC 版本一個 `ResourcePack` 加 `TextureGallery` |
| `TextureGallery`（`textures.json`）的紋理編號必須與 prbm 一致，重排會讓跨 commit 共用的 tile 全部失效 | 每個 MC 版本（含資源包版本）持久化一份只增不改的紋理表；資源版本變動時整批失效 |
| 單一維護者，MC 大改版時可能落後 | 我們的 section 格式與 BlueMap 的 anvil 讀取無關，只受資源包影響。必要時 fork，只改 `resourceExtensions` |
| 真實地形的效能和記憶體未測 | 原型只有 64×64 格。Phase 1 用 `.work/worlds/` 的 1.21.11 測試世界量測每張 tile 的時間與 heap |

## 8. 建議：三選一

**選「直接嵌入 BlueMap core（釘死版本）」。**
1. 原型證明它確實只是介面。`World`、`Chunk`、`MapSettings`、`MapStorage` 都能換成我們的實作，不需要改 BlueMap，所以目前沒有 fork 的必要。fork 還會背上跟進每月發版和 MC 新版資源的維護成本。
2. 「只參考設計自己寫」要重做的東西：blockstate/model 解析（multipart、variants、uvlock、模型繼承）、水/樹葉/玻璃的特殊渲染、材質圖集、PRBM 格式、lowres 分層、檢視器。這正是 docs/10 §3 預估最貴的部分，而原型顯示約 200 行膠水程式就能產生 tile，差距太大。
3. 風險可控：內部 API 不穩定，靠單一適配層、釘死版本加上金標準測試處理；lowres 靠自己合成；Java 25 是一個決策，不是技術阻礙。
4. 有退路：MIT 授權，我們只碰少數介面，日後需要時可以 fork 或 vendor `core`。

何時改選 fork：需要改動 `BlockRenderPass` 或 lowres 的核心行為時，例如想在 tile 中直接烘進 diff 上色，或想把 lowres 改成純函數式。

## 9. 重現步驟

```
# 1. 執行原型（只需 Java 25 與網路；會下載 bluemap-core 5.28 與 Mojang client jar）
cd experiments/01-bluemap && gradle run --max-workers=1 --no-daemon
# 2.（選用）建置 webapp 並掛上輸出
cd .work/bluemap-src/common/webapp && npm ci && npm run build
#   把 dist/ 複製到 .work/bluemap-proto/web/，settings.json = {"maps":["wg-demo"],"clientDecompression":true}
#   maps/wg-demo -> ../../out-1.21.11/map，用 python3 -m http.server 提供服務，用有 WebGL 的瀏覽器開啟
```

子代理也完整建置過 BlueMap 原始碼（`./gradlew :core:jar --max-workers=1`，約 8 分鐘，主要花在下載 ForgeGradle、Loom 等各平台外掛的依賴）。結論是不需要自己建置，直接用官方發佈的 `bluemap-core` 即可。
