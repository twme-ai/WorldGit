# 10 — 網頁前端與 3D 世界檢視器（全新撰寫）

> 已決定（2026-09-30）：網頁前端**全新撰寫，不沿用 BlockForge**。
>
> 已決定（2026-09-30，依 Phase 0 實驗結果）：**遠景地圖嵌入 BlueMap core 產生 tile；近景 / diff / 衝突用 deepslate 的模型層，加上自己寫的網格生成與繪製**。兩者都鎖定版本、以轉接層包起來。依據：`experiments/01-bluemap/REPORT.md`、`experiments/04-deepslate/REPORT.md`。

## 1. 前端要做到的事

| 功能 | 說明 |
|---|---|
| 類 GitHub 的瀏覽 | repo 列表、分支、commit 時間軸、tag/release、PR、權限設定 |
| 俯視地圖 | 整個世界的 2D 俯視圖（類 BlueMap/squaremap），可縮放；標出每個 commit 變動的 chunk |
| 3D 檢視 | 在地圖上框選一塊區域，用 3D 瀏覽該 commit 的世界（方塊 + 實體） |
| diff 檢視 | 兩個 commit / 分支的差異：上色、鬼影、左右並排、分割滑桿、沿時間軸拖曳 |
| 合併衝突 | 衝突區域清單、鏡頭飛過去、逐區切換 ours / theirs / base 並選擇 |
| 座標留言 | 在 3D 中點一格方塊留言，留言帶 x,y,z，遊戲內也看得到 |

## 2. 技術選型（建議）

| 項目 | 建議 | 理由 |
|---|---|---|
| 語言/建置 | TypeScript + Vite | |
| UI 框架 | React | 生態系最大、元件庫多；3D 以外的頁面（列表、PR、設定）都是一般網頁 |
| 3D（近景 / diff） | **deepslate**（MIT，鎖定版本）只用資料層與模型層：NBT/region 讀取、BlockState、blockstate/model 解析展開、`SpecialRenderers` 的特殊模型（箱子、床、旗幟、告示牌、頭顱…）。**不用**它的 `StructureRenderer` / `ChunkBuilder`；網格生成與繪製（WebGL2、自訂 shader）自己寫 | 實驗 A：模型層兩版不需修改即可用；原生 renderer 在 4×4 chunk 就卡住主執行緒 3.3～5.7 秒，自寫的 worker 版 0.45 秒且不阻塞 |
| 遠景地圖 | **BlueMap core**（MIT，鎖定版本）嵌入 Hub 後端產生 tile；檢視可沿用 BlueMap 的 webapp 當總覽頁 | 實驗 B：只需實作它的 `World`/`Chunk`/`MapSettings` 介面，不必修改 BlueMap |
| 背景運算 | Web Worker 池 | 解碼 section、生成網格都在 worker 做，不卡畫面 |
| 部署 | 建置成靜態檔，由 Java 後端一起提供 | 自架版維持「單一 image」；公開服務可以放 CDN |

## 3. 方塊模型與材質（資源管線）

- **Mojang 的材質與模型不能由我們重新散布**。做法：Hub 後端在需要某個 MC 版本時，從 Mojang 官方的版本清單下載該版本的客戶端 jar，取出 blockstates / models / textures，在伺服器端預先處理後快取，再提供給瀏覽器。自架版與公開服務都用同樣方式。
- 預先處理的產物（每個 MC 版本一份）：
  - 材質圖集（texture atlas）與動畫材質資訊。**必須自己打包**：deepslate 的 `TextureAtlas.fromBlobs` 會把 64×64 的實體貼圖（箱子、床、旗幟、告示牌）裁成 16×16
  - 合併後的 blockstates / models JSON（gzip 後每版約 100 KB），由瀏覽器端的 deepslate 解析展開。**不需要**在後端預先展開成四邊形表：實驗 A 顯示瀏覽器展開全部約 2300 個模型只要約 12 ms，也避免 Java 端重寫模型語意而與 deepslate 行為分歧
  - 方塊旗標（是否不透明、半透明、自我剔除），正式版改從伺服器的 data generator report 取得，不用啟發式推算
  - 生物群系顏色表（草、樹葉、水的染色；deepslate 的染色是寫死常數，要由我們替換）
- BlueMap 的遠景渲染使用它自己的資源管線（同樣從 Mojang 下載 client jar），與上面的產物分開。
- 依 commit 的 `DataVersion` 載入對應版本的資源（首發：1.21.11 與 26.2）。

## 4. 混合架構：伺服器預先渲染 + 瀏覽器即時生成

參考 BlueMap 的做法（§9），把「看整張地圖」和「近看 / 看差異」分開處理：

| | 遠景、總覽 | 近景、diff、衝突 |
|---|---|---|
| 誰來算 | **Hub 後端預先渲染成 tile**（2D 俯視圖 + 低細節 3D） | **瀏覽器在 Web Worker 即時生成網格** |
| 原因 | 大範圍資料量大，每個訪客都自己算太浪費 | 需要動態上色、切換 ours/theirs/base，預先渲染做不到 |

**與 git 內容定址的結合**：tile 的快取鍵是「它涵蓋的那些 chunk 的 tree 雜湊」，而不是 commit。所以兩個 commit 之間沒變的區域共用同一批 tile，每次 push 只需要重新渲染真正有變動的 tile。這比一般地圖外掛「定時全圖重繪」有效率得多，也讓「切換到任何一個歷史 commit 看地圖」成本很低。

## 5. 瀏覽器端網格生成

以 **section（16×16×16）為單位**生成網格，與資料模型的粒度一致，所以 diff 時只要重建變動的 section：

1. 從 API 取得正規化後的 section 二進位資料（與 core 相同的格式），在 worker 中解碼
2. 面剔除：相鄰是不透明完整方塊的面不畫（需要鄰近 section 的邊界資料）
3. 單一方塊的四邊形由 deepslate 的 `BlockDefinition.getMesh()` / `SpecialRenderers` 產生，以（方塊狀態, 剔除遮罩）為鍵快取（實驗 A 的快取命中率 99.8%）
4. **完整方塊要自己做貪婪合併（greedy meshing）並壓縮頂點格式**：deepslate 沒有這部分。實驗 A 的原型不合併時，16×16 chunk 需要 247 MB 顯示記憶體，目標是降到 50 MB 以下
5. 分成不透明、裁切（樹葉、玻璃片）、半透明（水、染色玻璃）三個繪製批次；半透明依距離排序
6. 生物群系染色、簡單的環境光遮蔽（AO）；deepslate 不處理的 uvlock、流體角落高度與流向也由我們補上

規模控制：
- 3D 只載入**框選範圍**與視野內的 section；視錐剔除、距離剔除
- 遠處用低細節（只畫頂面顏色的高度圖），近處才用完整模型
- 全世界的總覽用 2D 地圖 tile，不用 3D

## 6. 實體

Minecraft 的生物模型寫在遊戲程式碼裡而不是 JSON，完整重現成本很高。分階段：
1. **第一版**：以包圍盒 + 生物圖示/名稱標籤呈現；盔甲座、物品展示框、畫、display entity 優先做真實外觀（建築最常用）
2. **之後**：常見生物的簡化模型

diff 中實體以 UUID 對應：新增（綠）、移除（紅）、移動（箭頭連線）、屬性改變（黃）。

## 7. diff 的呈現模式

| 模式 | 說明 |
|---|---|
| 上色 | 單一畫面，新增綠、移除紅（半透明鬼影）、修改黃、衝突紫（色票與色盲規則見 [06](06-diff-merge.md) §1.1） |
| 並排 | 左右兩個畫面，鏡頭同步 |
| 分割滑桿 | 同一畫面，拖曳分割線左右分別顯示兩個版本 |
| 時間軸 | 沿 commit 列表拖曳，逐個 commit 播放變化 |
| 只看變動 | 隱藏沒變的方塊，只顯示變動及其周圍 1 格 |

## 8. 操作

- 兩種鏡頭：環繞（像模型檢視器）與飛行（WASD + 滑鼠，像遊戲內旁觀模式）
- 點方塊顯示：座標、方塊狀態、block entity 內容（例如箱子物品）、最後變動的 commit 與作者
- 網址帶座標與視角，可以直接分享「這個角度的這個 commit」

## 9. 參考專案

以下專案都是開源的 Minecraft 地圖或渲染專案，資料為 2026-09-30 查詢時的狀態：

| 專案 | 授權 | 語言 | 形式 | 值得參考的地方 |
|---|---|---|---|---|
| [BlueMap](https://github.com/BlueMap-Minecraft/BlueMap) | MIT | Java（網頁端 Three.js） | 伺服器端把世界渲染成 3D tile，網頁載入檢視 | **整體架構最接近**：伺服器預先渲染高/低細節 tile、從遊戲 jar 載入資源、網頁檢視器的飛行/環繞/平面鏡頭。核心渲染器是 Java，理論上可以放進 Hub 後端直接產生 tile，需要評估介接成本 |
| [deepslate](https://github.com/misode/deepslate) | MIT | TypeScript | 瀏覽器端函式庫，用原版資源渲染結構/方塊 | **近景渲染**：方塊模型解析（blockstate、multipart、模型繼承）與 WebGL 網格，可能可以直接當作依賴使用 |
| [prismarine-viewer](https://github.com/PrismarineJS/prismarine-viewer) | MIT | JavaScript（Three.js） | 瀏覽器端即時顯示伺服器/機器人看到的世界 | Web Worker 網格生成、以 section 為單位增量更新 |
| [Dynmap](https://github.com/webbukkit/dynmap) | Apache-2.0 | Java | 老牌的即時網頁地圖 | 方塊變動後如何讓 tile 失效與重繪 |
| [squaremap](https://github.com/jpenilla/squaremap) | 需確認（GitHub 未辨識） | Java | 輕量 2D 俯視地圖（原版地圖風格） | 2D 總覽圖的風格與效能 |
| [Pl3xMap](https://github.com/granny/Pl3xMap) | MIT | Java | 同上 | 同上 |

使用方式（已決定，2026-09-30）：**直接使用 deepslate 與 BlueMap core 當依賴**（見文件開頭）。

### 採用方式與注意事項

| | deepslate（近景 / diff） | BlueMap core（遠景 tile） |
|---|---|---|
| 使用範圍 | 資料層與模型層；網格生成、繪製、diff 上色自己寫 | `core` 模組；不用 `common`，tile 排程、快取鍵、lowres 合成、儲存對應由 Hub 自己寫 |
| 版本管理 | 鎖定版本（0.x，minor 版本可能破壞 API），包在一層薄的轉接層後面 | 鎖定版本（內部介面沒有穩定承諾），BlueMap 的型別藏在 Hub 內部的 `TileRenderer` 介面後面 |
| 已知要補的部分 | 圖集打包、生物群系染色、greedy meshing、uvlock、流體細節、告示牌文字、講台的書、刷怪磚內的生物、所有實體的外觀 | 光照近似（WorldGit 丟棄光照）；lowres 是累積式的，要由 Hub 自己合成；tile 快取鍵要包含向外 1 格的鄰居 chunk |
| 特殊需求 | — | Java 25（Hub 已改用 Java 25） |
| 升版方式 | 固定場景的截圖比對做回歸測試 | 金標準 tile 比對做回歸測試 |
| 風險 | 單一維護者；MIT 授權允許必要時 vendor | 單一維護者；必要時 fork 或 vendor `core` |

估計工作量（實驗報告的估計）：近景 / diff 檢視器約 3～4 個人月（deepslate 省下其中約 3～5 週）；BlueMap 介接約 1500～2500 行 Java、2～3 人週。

## 10. 驗證方式

- 渲染正確性：以固定場景（各種方塊類型的測試世界）在遊戲內截圖，與網頁渲染做比對
- 效能目標（Phase 0 量測後確定）：例如框選 16×16 chunk 範圍時，首次載入到可操作的時間、幀率、記憶體用量
