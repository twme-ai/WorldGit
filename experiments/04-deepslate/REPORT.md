# Phase 0 / 04-deepslate 報告：用 deepslate 渲染測試世界的真實區塊

> 本報告由執行實驗的子代理撰寫，由主代理存檔（子代理的檔案寫入被系統限制）。
>
> 測試日期 2026-09-30。deepslate **0.27.2**（2026-09-27 發佈，npm 最新版）；Node v24.20、Vite 8、Chrome 154（無頭，SwiftShader 軟體 WebGL2）。
> 測試資料：`.work/worlds/{1.21.11,26.2}/baseline`（Paper 世界，overworld），資源為 Mojang 官方 client jar（兩版 sha1 皆已驗證）。
> 重現方式見 README.md；原始數據在 `bench-results.json`；截圖在 `screenshots/`。
> 機器為 3 核心且同時有其他代理在跑，所有耗時都是「有背景負載」下的數字，只能看量級與比例。

## 0. 一句話結論

**建議：直接把 deepslate 當依賴使用，但只用它的「資料層 / 模型層」**：NBT 與 region 讀取、BlockState、blockstate/model 的解析與展開、`SpecialRenderers` 的箱子/床/旗幟/告示牌等特殊模型、Quad 幾何。**不要用它的 `StructureRenderer` / `ChunkBuilder`**。section 網格生成、繪製、diff 著色由 WorldGit 自己寫（本實驗已有可運作的原型，約 600 行）。不需要 fork。

理由：
1. 模型層在 1.21.11 與 26.2 都不用修改就能跑，連 26.2 的 blockstate / model 新格式都吃得下。0.26.1 起專門加了 26.3 格式支援，版本追得快。
2. 它能在 Web Worker 裡使用（不依賴 DOM），打包後 worker 約 71 KB gzip。
3. 原生 `StructureRenderer` 的設計（整個 structure 一次掃描、同步、在主執行緒、沒有 alpha）不適合 4×4～16×16 chunk 的規模與 diff 需求。實測 4×4 chunk 就會讓主執行緒卡住 3.3～5.7 秒。我們只需要它「把單一方塊狀態變成 quads」這一段，而這一段寫得好、可以重用。
4. 缺口（生物群系染色、剔除、AO、greedy meshing、diff、告示牌文字、實體、uvlock…）都在 deepslate 本來就不打算涵蓋的層，fork 也不會因此更好維護。
5. 風險：單一維護者（約 90% 的 commit 來自 misode）、0.x 版本號（每個 minor 版本都可能破壞 API）。緩解：鎖定版本、用薄的 adapter 包起來（本實驗的 `resources.ts`），MIT 授權也允許必要時直接 vendor。

## 1. 做了什麼

| 項目 | 內容 |
|---|---|
| 資源管線（模擬 Hub 後端） | `scripts/prep-assets.mjs`：client jar → 圖集 PNG（原尺寸貼圖，動畫取第一格）+ 合併的 `blockstates.json` / `models.json` + `flags.json`（opaque / semi_transparent / self_culling，由模型與貼圖 alpha 推算）+ `biomes.json`（草/葉/水的顏色，由 biome JSON 與 colormap 算出）。每版約 1 秒。 |
| 資料讀取 | 直接用 deepslate 的 `NbtRegion.read()`（內含 pako）讀 `.mca`，用 `getChunk().getRoot()` 取 NBT，再自己把 palette 與緊密長整數陣列解成 `Uint16Array(4096)`。 |
| 網格生成 | `src/mesher.ts`：以 section 為單位，用 padded（18³）的鄰居陣列做面剔除。單一方塊的 quads 呼叫 deepslate 的 `BlockDefinition.getMesh()` 與 `SpecialRenderers.getBlockMesh()`，以（狀態, 剔除遮罩）為鍵快取。輸出 interleaved `Float32Array`，以 Transferable 從 worker 送回。 |
| 繪製 | `src/viewer.ts`：WebGL2、自訂 shader（含 diff 上色、鬼影、只看變動）。 |
| diff 原型 | `public/diff-edits.json`：72 格編輯（換樓梯、換玻璃片顏色、雪層、開門、移除柵欄/牆/拉桿、加上鑽石/黃金/綠寶石方塊與燈籠、挖掉平台地磚，包含跨 chunk 邊界的情況），在 JSON 層面套用到 B 世界（寫時複製，沒改到的 section 與 A 共用同一個物件）。 |
| 對照組 | `?mode=stock`：完全使用 deepslate 自己的 `Structure` 介面 + `StructureRenderer`。 |

## 2. 正確性

以下都以截圖目視檢查。軟體渲染不適合看精細光影，只判斷形狀、朝向、貼圖和顏色。

| 項目 | 結果 | 截圖 / 證據 |
|---|---|---|
| 樓梯（4 個朝向 × 上/下、inner/outer、含水） | 朝向正確。另以幾何腳本驗證：`scripts/check-stairs.ts` 對 8 種 facing×half 檢查「高的那一半」是否落在正確方向，兩版都是 8/8 OK（證明 deepslate 的 blockstate y 旋轉方向正確）。**缺陷：uvlock 被忽略**（deepslate 原始碼完全沒有處理 uvlock），旋轉過的樓梯頂面木紋方向與原版不同 | `02-stairs-slabs.png` |
| 半磚（下/上/雙層/含水） | 正確 | `02-stairs-slabs.png` |
| 柵欄、柵欄門（開）、牆（含上方柱、苔石）、玻璃片、染色玻璃片、鐵欄杆 | multipart 的連接條件正確；玻璃片只顯示邊框與中線，符合原版 | `03-fences-walls-panes.png` |
| 門（木/鐵、開/關、hinge）、活板門 | 正確 | `04-doors-bed.png` |
| 床 | 由 `SpecialRenderers` 用實體貼圖 `entity/bed/red` 畫出，外形與顏色正確 | `04-doors-bed.png` |
| 紅石線、中繼器、比較器、紅石燈（亮）、觀察者、活塞、拉桿 | 正確；紅石線顏色會隨 power 變化（deepslate 內建） | `05-redstone.png` |
| 花、高草、玫瑰叢、小麥、樹苗、仙人掌、甜莓、藤蔓、雪層（3、8） | 正確 | `06-plants-snow.png` |
| 水、岩漿、流動水、含水方塊 | 水半透明、岩漿正確。流動水的高度依 `level` 查表，每格獨立計算，**沒有角落高度插值與流向**。含水方塊（樓梯/半磚）裡的水由我們自己拆到半透明層 | `07-fluids.png`、`01-overview-1.21.11.png` |
| 半透明排序 | 只依「section 中心距離」由遠到近排序，同一 section 內沒有逐 quad 排序。本場景看不出問題，但水或玻璃很密集的場景會出錯 | — |
| 生物群系染色 | **deepslate 原生不支援**：`BlockColors` 是寫死的常數。我們把它改成回傳「哨兵色」，輸出網格時依 section 內 4×4×4 的 biome 格換成預處理 `biomes.json` 中的顏色。用 `biome=swamp` 強制測試：水變灰綠、草變橄欖色，機制可用。限制：測試世界整個 16×16 chunk 範圍只有 ocean / deep_ocean（場景下方）與極少量 plains/beach，沒有陸地，無法用真實資料驗證陸地群系；也沒做 5×5 群系混色與 swamp 的噪音色 | `16-biome-swamp-override.png` |
| Block entity：箱子 | `SpecialRenderers` 畫出正確的箱子模型與貼圖 | `08-block-entities.png`、`18-stock-deepslate-blockentities.png` |
| Block entity：告示牌 | 只有牌子木板與柱子，**沒有文字**。原型只是把 NBT 中的文字用 Canvas2D 疊在畫面上，並不是貼在牌面上 | `09-sign-banner-labels.png` |
| Block entity：旗幟 | 正確，包含 NBT `patterns`（紅色 stripe_top + 黑色 creeper）。前提是深度測試要用 `LEQUAL`（多層共面的 quad）；用 `LESS` 時圖樣會被蓋掉（第一版踩到的坑，不是 bug） | `08-block-entities.png` |
| Block entity：講台 | 講台本體有，**書沒有**（原版的書是由 block entity renderer 畫的） | `08-block-entities.png` |
| Block entity：刷怪磚 | 只有籠子，沒有裡面旋轉的生物 | `08-block-entities.png` |
| Block entity：頭顱 | `SpecialRenderers` 有對應（在 Node 內驗證會產生 6 個 quad、貼圖 UV 正確），但場景中的玩家頭顱已被流動水沖掉（世界資料中該格是 `water[level=6]`），**未能目視驗證** | — |
| 實體（盔甲座、展示框、畫、display entity、村民、牛…） | **deepslate 完全不支援**（render 目錄中除了物品模型之外沒有任何實體相關程式碼，已 grep 確認）。原型用 deepslate 的 `NbtRegion` 讀 `entities/*.mca`，再用 Canvas2D 畫黃色包圍盒與標籤，證明資料讀得到，但外觀全部要自己做 | `10-entities-overlay.png` |
| 1.21.11 vs 26.2 | 同一份程式碼、同一批測試世界，兩版渲染結果一致（兩版的場景方塊本來就零差異，見 00-env 報告）；26.2 的 blockstate / model 由 0.27.2 直接吃下 | `14-overview-26.2.png` |
| 與 deepslate 原生渲染比對 | 我們的網格 + 自訂 shader 與原生 `StructureRenderer` 在同一場景下外觀一致（差別只在我們加了 biome 染色與霧、光照細節） | `17-stock-deepslate-overview.png`、`18-stock-deepslate-blockentities.png` |
| 未實測 | 與遊戲內截圖的逐像素比對（doc 10 §10）、動畫貼圖（只取第一格）、AO、頭顱的玩家皮膚、附魔光效、物品展示框的內容 | — |

## 3. diff 原型：deepslate 是否方便做逐格上色 / 半透明

結論：**用 deepslate 的 quad 資料很容易做，但要繞過它的 renderer。**

- deepslate 的 `Vertex.color` 只有 RGB、**沒有 alpha**，內建 shader 是 `texColor * tint * lighting`，沒有任何逐格覆蓋的管道。`StructureRenderer` 的 `drawColoredStructure` 是把 blockPos 編成顏色、給滑鼠點選用的，不是給 diff 用的。
- 我們的做法：mesher 逐格分類（Same / Added / Removed / Modified），把 `kind`、`alpha` 寫進每個頂點；shader 依 `kind` 做 `mix(c, 綠/紅/黃, 0.55)`，Removed 另外走「鬼影」層（半透明、不寫深度）。「只看變動」就是在 shader 中丟掉 `kind==0`。結果：新增為綠色（包含跨 chunk 邊界的 2×2 綠寶石）、移除為紅色半透明（可透出下方泥土）、修改為黃色，見 `11-diff-overview.png`、`12-diff-only-changed.png`、`13-diff-chunk-border.png`。
- **與內容定址的呼應**：A、B 世界的 section 以物件參考共用；diff 只需要比較「參考不同」的 section。本例 72 格編輯 → 66 個 section 受影響（其中 26 個是鄰居，因為剔除結果會變）。`diffCounts` = 新增 41 / 移除 24 / 修改 7，與編輯內容核對吻合（另有 3 格原本是流動水，計入修改）。
- 邊界正確性：移除的方塊用 A 的鄰居做剔除，其他方塊用 B 的鄰居做剔除，所以洞的側壁會以鬼影畫出，且 chunk 邊界處（x=15/16）沒有縫。
- 尚未做：並排、分割滑桿、時間軸（都只是「不同資料集 + 不同繪製範圍」，不會卡在 deepslate）；實體 diff；鬼影的逐 quad 深度排序。

## 4. 效能（SwiftShader 軟體渲染 + 背景負載，僅供量級參考）

### 載入與網格（worker 內、瀏覽器實測；見 `bench-results.json`）

| 範圍 | chunks / sections | region 下載（本機） | NBT 解壓 + 解析 | 網格生成（worker） | 四邊形 | GPU 緩衝 | 到可操作（全程） |
|---|---|---|---|---|---|---|---|
| 場景 7×6 | 42 / 1050 | 162 ms | 364 ms | 729 ms | 231k（含 A/B/diff 三版受影響的 section） | 55 MB | 1.8 s |
| 4×4 | 16 / 400 | 196 ms | 170 ms | 447 ms | 115k | 28 MB | 1.2 s |
| 16×16 | 256 / 6400 | 128 ms | 746 ms | 2.4 s | 1.03M | 247 MB | 3.6 s |
| 16×16（26.2） | 256 / 6400 | 117 ms | 690 ms | 2.3 s | 1.03M | 247 MB | 3.4 s |

- Node 端執行同樣的程式（沒有 GL）：4×4 的網格 0.38 s，16×16 的網格 3.2 s（訪問 8.05M 個方塊、輸出 1.84M 個），單一 section 最慢 23 ms；快取命中率 99.8%（`BlockDefinition.getMesh` 只需呼叫 1334～3139 次）。
- 資源載入：下載 blockstates/models 約 70 ms，建立 2308 個 `BlockModel` 並 flatten 約 12 ms。**在瀏覽器端展開模型很便宜**，不需要在 Java 後端預先展開成「狀態 → 四邊形」表（見 §7）。
- 第一批網格約 1 s 內就能看到（worker 分批送回），之後串流補完。
- 16×16 的 1.03M 四邊形 × 4 頂點 × 60 B = 247 MB，太大了。這是「每一面都畫、每個頂點 15 個 float」的原型格式。要支撐 16×16 的規模，需要 greedy meshing、壓縮頂點（位置用 uint8/int16、UV 由 quad 類型推導、顏色打包）、依距離分級的 LOD。deepslate 完全沒有這部分。
- 主執行緒：GPU 上傳 207 ms（16×16 全部），分批進行，不會卡死。

### 繪製（SwiftShader，1280×720）

| 場景 | 每幀 | 備註 |
|---|---|---|
| 4×4（115k 四邊形） | ~110 ms | 有背景負載 |
| 場景 7×6（231k） | 185～580 ms | 變動很大（同時有 Minecraft 伺服器在跑） |
| 16×16（1.03M） | 0.8～1.4 s | 軟體光柵，僅表示「沒有 GPU 不能看」 |

軟體渲染的數字沒有推論意義，真實 GPU 需要另外量測（**未量測**）。

### 原生 deepslate `StructureRenderer` 對照（同一份資料）

| 範圍 | 方塊數 | `new StructureRenderer()`（主執行緒同步生成網格 + 上傳） |
|---|---|---|
| 場景方塊盒 65×29×49 | 6,721 | 180 ms |
| 4×4 chunk 實際地形（y -64..69） | 506,847 | **3.3～5.7 s**（兩次實測；我們的 worker 版 0.45 s，且不阻塞） |
| 16×16 | ~8M | 未實測；線性外推約 1 分鐘以上、記憶體數 GB，視為不可行 |

- 原因：`ChunkBuilder` 對 structure 裡每個方塊都呼叫 `getMesh`（沒有快取）；每次 `Mesh.merge` 都用 `concat` 複製陣列（O(n²)）；`Mesh.rebuild` 用 `flatMap` 展開；也沒有「完全被包住的方塊先跳過」的捷徑。
- 增量更新：有 `updateStructureBuffers(chunkPositions)`，但內部仍然會掃過整個 `structure.getBlocks()`。實測在 50 萬方塊的 structure 上只更新 1 個 16³ 區塊就要 **291 ms**。我們的 section 級增量：改一格 → 重建 1～3 個 section，在 worker 內 1～11 ms，含 GPU 上傳約 3～16 ms（三次編輯實測）。
- 頂點索引用 `Uint16Array`，單一 mesh 超過 65535 個頂點（16383 個 quad）就會出錯；我們用 WebGL2 + `Uint32` 索引與 section 粒度避開。

### Web Worker 與 section 增量
- **Worker：可以用。** NBT/region、`BlockDefinition`、`BlockModel`、`SpecialRenderers`、`Quad/Mesh`（CPU 部分）都能在 worker 內執行（本實驗的 worker 就是這樣做）。不能用的是 `TextureAtlas.fromBlobs`（需要 `document.createElement('canvas')`），以及 `StructureRenderer`、`ChunkBuilder`（都需要 GL context）。
- **section 增量更新：要靠我們自己的 mesher 才做得到。**

## 5. API 與維護

| 項目 | 結果 |
|---|---|
| 授權 | MIT（GitHub 與 npm 都已確認） |
| 版本與頻率 | npm 上從 0.22.0（2024-10）到 0.27.2（2026-09-27）；近 12 個月發了 0.25.0、0.25.1、0.26.0、0.26.1、0.26.2、0.27.0、0.27.1、0.27.2，約 1～2 個月一版；最近 3 週連發 5 版 |
| 對新 MC 版本的支援 | 靠作者手動更新：2026-08-19「Read 26.3 block state format」「Add support for new 26.3 block model textures format」、2026-09-04「update to 26.3」。新版本格式變動後，deepslate 約有數週延遲才跟上；本次 26.2 資源可直接使用。新的 block entity 特殊渲染要等 `SpecialRenderers` 新增 |
| API 穩定度 | 0.x，minor 版本可能破壞 API：`StructureRenderer` 已移除 `facesPerBuffer` 選項（程式內留有警告）；2026-05 的 0.26.0 有大改（Special rendering）。**我們用到的 API 面很窄**（`NbtRegion`、`BlockState`、`BlockDefinition.fromJson/getMesh`、`BlockModel.fromJson/flatten`、`SpecialRenderers.getBlockMesh`、`Mesh/Quad/Vertex`、`BlockColors`（被我們改寫）），都包在 `src/resources.ts` 裡 |
| 維護者 | 單一主要維護者（misode 314 個 commit，其次 jacobsjo 24 個）；19 個 open issue、260 star。bus factor = 1 |
| 打包大小 | 套件本身 1.57 MB（含 worldgen 等用不到的部分）；實際打包（Vite，tree-shaking）後：主 bundle 257 KB（gzip 75 KB，含我們的檢視器），worker 249 KB（gzip 71 KB）。預處理資源每版：圖集 PNG 約 1 MB、blockstates+models JSON gzip 後共約 100 KB |
| 依賴 | `gl-matrix`、`pako`、`md5`（都很小） |
| 與 doc 10 §3 資源管線的關係 | deepslate **沒有資源載入器**：圖集、blockstates/models 合併、`flags`、預設屬性、生物群系顏色表都要自己產生。`TextureAtlas.fromBlobs` 會把每張貼圖裁成 16×16，弄壞 64×64 的實體貼圖（箱子、床、旗幟、告示牌），所以圖集一定要自己打包（我們的 `prep-assets.mjs` 約 200 行，用原尺寸貼圖 + 動畫的第一格） |

## 6. deepslate 缺少、WorldGit 需要自己補的部分與估計工作量

工時以一位熟悉 WebGL 與 Minecraft 格式的開發者做到「能用」的程度來估，不含美術打磨；原型已完成的部分有標註。

| # | 缺口 | 現況 | 估計 |
|---|---|---|---|
| 1 | 資源管線（圖集、合併 blockstates/models、flags、biome 顏色表、多版本、快取） | 原型已完成（JS，約 200 行）。正式版在 Java 後端需要：PNG 讀寫與打包、動畫貼圖 mcmeta、flags 推算（或改從 server data generator 的 report 取得）、跨版本測試 | 1～1.5 週（Java 版） |
| 2 | section 網格生成器：padded 鄰居、剔除、worker 池、增量更新、Transferable | 原型已完成（`mesher.ts` + `worker.ts`） | 正式化與測試 1 週 |
| 3 | greedy meshing、壓縮頂點格式、視錐/距離剔除、LOD（遠景高度圖）、記憶體預算 | 未做；16×16 的 247 MB 須降到約 50 MB 以下 | 2～3 週 |
| 4 | 繪製器：半透明排序（逐 quad 或 OIT）、AO、霧、動畫貼圖、點選（ray pick）、環繞/飛行鏡頭 | 原型有基本版（含 diff shader） | 2 週 |
| 5 | diff 呈現：並排、分割滑桿、時間軸、只看變動 + 周圍 1 格、實體 diff | 上色、鬼影、只看變動已有 | 1.5～2 週 |
| 6 | 生物群系染色：5×5 混色、swamp 噪音、dry foliage、維護有色方塊清單 | 基本版已有（依 biome 格查表） | 3～5 天 |
| 7 | uvlock、流體角落高度與流向 | 未做 | 1 週 |
| 8 | 告示牌文字（前後兩面、顏色、發光）、講台的書、刷怪磚內的生物、頭顱的玩家皮膚 | 未做 | 1～1.5 週 |
| 9 | 實體外觀：優先做盔甲座、物品展示框、畫、display entity；之後做村民等生物的簡化模型 | 只有包圍盒與標籤 | 優先的那批 2～3 週；常見生物模型每種約 2～3 天 |
| 10 | 追蹤新 MC 版本：新方塊（flags、染色表）、新 block entity 特殊渲染、deepslate 升版回歸測試（截圖比對） | — | 每個 MC 版本約 2～4 天 + CI |
| 11 | 正確性驗證：固定場景的遊戲內截圖與網頁截圖比對（doc 10 §10） | 截圖流程已建立 | 1 週 |

合計：完整的近景 / diff 檢視器約 **3～4 個人月**，其中大部分是「沒有 deepslate 也得做」的。deepslate 實際替我們省下的是：blockstate/model 的解析與展開（variants、multipart、parent 繼承、旋轉、`AND/OR`）、NBT/region 讀取，以及床、箱子、旗幟、告示牌、頭顱、潛影盒、鐘、陶罐等特殊模型的手刻幾何，粗估 **3～5 週**，並且能持續免費跟上新版本。

## 7. 對設計文件的回饋（供主對話決定，本實驗沒有修改文件）

1. **doc 10 §3「伺服器端把 blockstate 展開成最終四邊形表」可以不做**：在瀏覽器端用 deepslate 展開全部約 2300 個模型只要 12 ms 左右，單一方塊的 quads 有快取，首次生成網格的瓶頸不在這裡。Hub 後端只需產生：圖集 + 座標表、合併後的 blockstates/models（gzip 後每版約 100 KB）、flags、biome 顏色表。後端（Java）不必重新實作 Minecraft 的模型語意，也避免與 deepslate 的行為分歧。
2. **doc 10 §5 的 greedy meshing 要另外做**，deepslate 沒有。原型的 1.03M 四邊形（16×16 chunk 的海底地形）顯示不做的話資料量太大。
3. BlueMap 與 deepslate 的分工（遠景 tile 由後端產生，近景/diff 在瀏覽器生成）成立；本實驗沒有驗證 BlueMap 那一半。
4. 資料版本：兩版的 chunk NBT 結構零差異（00-env 已證實），同一個 section 解碼器兩版通用；但資源兩版不同（1.21.11：1168 個 blockstate；26.2：1198 個 blockstate、2569 個 model），必須依 `DataVersion` 選擇資源包。

## 8. 失敗、限制與未驗證

- 截圖用的是軟體 WebGL。`gl.finish()` 在 SwiftShader 下不會真的阻塞，第一版基準因此量到 1 ms 的假數字，後來改用 `readPixels` 強制同步，才得到上表的數字。原生 deepslate 對照組的「每幀耗時」沒有用同樣方法重量，表中只列建置時間。
- 沒有 GPU，沒有量測真實硬體上的幀率與 GPU 記憶體。
- 測試世界沒有陸地（整個 16×16 chunk 範圍都是海洋，場景是漂浮平台），所以「真實地形」截圖只有海床；樹葉與陸地群系染色沒有真實資料驗證，只用 `biome=` 強制覆蓋測試。
- 玩家頭顱已被流動水沖掉，未能目視驗證；裝飾陶罐、盾牌等由 NBT 決定外觀的方塊未測。
- 頂點、流體、AO 的視覺細節只做了目視檢查，沒有與遊戲內截圖逐像素比對。
- `flags.json` 的 opaque 判斷是啟發式的（模型為單一整格、六面皆有 `cullface`、貼圖完全不透明），可能與原版在少數方塊上不一致；正式版建議改用 server data generator 的 report。
- 動畫貼圖（水、岩漿、海草）只顯示第一格。
- 改寫 `BlockColors` 依賴「`BlockColors` 是可寫的匯出物件」這個實作細節，升版時要做回歸測試。
- 實體與告示牌的標籤是用 Canvas2D 疊在畫面上，只用來證明資料讀得到。
- `.work/assets` 與 `.work/worlds` 的內容不進 repo；`experiments/04-deepslate/` 內沒有任何 Mojang 資產原檔。截圖是渲染結果，如果 repo 將來要公開、對此有疑慮，可以在公開前移除。

## 9. 截圖清單（`screenshots/`，每張都 < 500 KB）

| 檔名 | 內容 |
|---|---|
| `01-overview-1.21.11.png` | 場景總覽（B 版），含水域與下方海床 |
| `02-stairs-slabs.png` | 樓梯各朝向、半磚（A 版，隱藏半透明層） |
| `03-fences-walls-panes.png` | 柵欄、牆、玻璃片、鐵欄杆 |
| `04-doors-bed.png` | 門、活板門、床 |
| `05-redstone.png` | 紅石線、中繼器、比較器、紅石燈、觀察者、活塞 |
| `06-plants-snow.png` | 花、草、作物、雪層 |
| `07-fluids.png` | 水、岩漿、流動水 |
| `08-block-entities.png` | 箱子、告示牌、旗幟、講台、刷怪磚、熔爐、木桶、唱片機、指令方塊 |
| `09-sign-banner-labels.png` | 告示牌（疊圖文字）、旗幟、箱子近拍 |
| `10-entities-overlay.png` | 實體包圍盒與標籤（deepslate 不支援實體，這是我們的疊圖） |
| `11-diff-overview.png` | diff：新增綠、移除紅色鬼影、修改黃 |
| `12-diff-only-changed.png` | diff：只看變動 |
| `13-diff-chunk-border.png` | diff：跨 chunk 邊界的新增與移除 |
| `14-overview-26.2.png` | 26.2 世界與資源 |
| `15-terrain-16x16.png` | 16×16 chunk 規模的海床地形 |
| `16-biome-swamp-override.png` | 強制 swamp 群系，測試染色機制 |
| `17-stock-deepslate-overview.png` | 對照組：原生 `StructureRenderer` |
| `18-stock-deepslate-blockentities.png` | 對照組：原生 `StructureRenderer` 的 block entity |
