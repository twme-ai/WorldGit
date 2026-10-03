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

### 5.1 Phase 0 大範圍實測（2026-10-01，`experiments/07-viewer-scale/`）

以 baseline 626 chunk 加上平鋪合成的 2,000／5,000／10,000 chunk 量測（headless Chromium，SwiftShader 軟體渲染；幀時間只看量級）：
- **網格生成**：greedy 合併讓頂點資料少 43%。每頂點從實驗 A 的 60 B 壓到 24 B，再到 16 B（圖集矩形改成索引查表，光照剩 16 階）。10,000 chunk 全量網格 1／2／3 個 worker 分別 219／139／106 秒，約每 chunk 10.6 ms（3 worker）。
- **不能把整個世界的完整網格放進瀏覽器**：10,000 chunk 的完整網格約 4,300 萬個三角形、2 GB 頂點資料。必須只對相機附近做完整細節，其餘用 LOD，並隨相機串流載入與卸載。
- **50 MB 目標**：16×16 chunk 全完整細節時，16 B 版的幾何資料（頂點 + index）38 MB，**幾何部分達標**；但加上材質圖集（約 17 MB）、framebuffer、JS heap 後約 93 MB。修正目標：**幾何 < 50 MB，整體瀏覽器工作集預算 128–160 MiB**。若要更低，需做圖集分頁與 quad instancing。
- **10,000 chunk 在 96 MiB GPU 預算下**：128 個完整 chunk + 9,872 個 LOD chunk，首次可見約 1.1 秒。
- **建議架構**：
  - 平常用 2 個 worker；每幀限制上傳時間；相機移走時取消落後的工作。
  - LOD 至少兩級：近處完整、中距離 4×4 或 8×8 高度色塊、最遠用 BlueMap lowres。
  - **Hub 伺服器端預先產生每個 tile 的高度圖、平均顏色與 diff 區域統計**（依內容雜湊與 dirty section 快取），不必預先產生近景網格。
  - 大範圍 diff 先由伺服器統計畫包圍盒，使用者靠近或點選時才載入逐格資料。
- 尚未驗證：真 GPU 幀率、真實的大型獨立世界（合成資料共享 payload，結果偏樂觀）、兩個真實 commit 的 diff。

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
| 前／後切換 | 同一鏡頭切換 a 與 b 的完整內容（不帶 diff 上色） |


### 7.1 Phase 2 Hub 實作（2026-10-01）

- 世界首頁與 commit 列表提供分支下拉；`/{owner}/{world}/branches` 依名稱合併各維度 head，顯示每個 head 的作者、最新 commit 訊息、缺少分支的維度、head 是否同一 snapshot。預設分支以主世界 HEAD 為準；領先／落後以世界層級可達 snapshot 集合相減，支援切換比較基準。不同 snapshot 可能只是該維度沒有變動，不等於推送失敗。
- `/{owner}/{world}/compare/<a>...<b>` 比較 a→b；兩端可填分支、HEAD 或唯一的 4–40 位 commit 前綴。分支可含 `/`，網址保留斜線，交給 SPA wildcard 路由；不使用容器可能拒絕的 `%2F`。分支優先於同名十六進位前綴。
- 分支代表各維度當下 head；commit 只帶入各維度同 snapshot 的 commit。沒有完整跨維度狀態清單時不依時間補猜 head：缺少配對的維度顯示提醒、排除統計，仍可看可用的一端。
- 統計由 core `DiffEngine.Detail.SUMMARY` 計算，包括方塊 +/-/~、chunk、section、實體與 metadata；回傳 chunk／section 各自的 +/-/~。API chunk／section 清單全維度合計最多 2000／6000，包含截斷旗標；畫面最多呈現各 300 列，可點 chunk 移動鏡頭。統計與 bounds 不受清單截斷影響。
- **已提供**：上色疊圖、只看變動與周圍一格（含跨 chunk／section 邊界）、前／後切換、一般／色盲色票、環繞／飛行鏡頭。前／後讀取實際 commit 的完整內容，包括 block entity、biome、實體及 LOD；切換時保留鏡頭。修改種類直接使用 core diff 的 kind，包含方塊 state 未變而 block entity 改變的格子；全 chunk 被移除時仍能畫鬼影。
- 延續相機附近的 8×8 chunk 串流視窗、最多三個視窗同時載入、兩個 Worker、細節半徑選項 3–10 chunk、鏡頭附近 3×3 region 的階梯 LOD。JSON 上限 4 MiB、WGCK／WGDF 上限 16 MiB，在寫出更多資料前檢查。新端點共用授權與 DecodeBudget；私人世界無權限 404，超過聚合預算 413。compare 不新增磁碟快取；既有 push 配額保持適用。
- **待後續**：並排、分割滑桿與時間軸；BlueMap、真 GPU／大世界效能的既有限制仍見 [11](11-phase1-progress.md) 與 [12](12-phase2-progress.md) Hub。

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

## 11. Phase 4 協作頁（2026-10-03）

`/{owner}/{world}/pulls` 列表可按 open/merged/closed 篩選與分頁，建立同世界來源→目標 PR；詳情 `/pulls/{id}` 顯示來源 commits（最多 200，另標截斷）、作者／描述／審核數／可合併狀態。3D 與衝突區域沿用 Phase 3 merge-preview，ours/theirs/base/manual 選擇保存到 PR；manual 僅表示未選完，不能在 Hub 上直接編輯方塊。tip 改變清除選擇與審核並提示重新檢查；選擇改變清除審核。核准／要求修改與合併再送 fingerprint，lease 不符回 409，全部符合才呼叫 core 產生整合提交。

留言支援一般文字、回覆、作者／管理者編輯刪除、維度與整數座標／範圍；可點 3D 方塊填座標，也可手動輸入。3D 標記及留言按鈕會聚焦指定 bounds，跨維度切換；`textContent`／DOM text 輸出，惡意 HTML 只顯示文字。座標留言與世界釘選列表 API 可供遊戲端使用。

`/releases` 建立 tag release、`/releases/{id}` 顯示說明與受權限檢查的串流 ZIP 下載；`/settings` 管理世界授權、可見性、分支保護與 webhook／投遞紀錄；全站 `/settings` 是 PAT／OAuth 連結（組織／團隊管理走 REST），`/notifications` 顯示事件並標已讀。表單依後端角色與 scope 授權，前端按鈕不作為安全邊界。fork／squash／rebase、一般 compare 的並排與時間軸仍未提供。

瀏覽器本機與 OAuth 登入使用 HttpOnly、SameSite=Lax 的 JSESSIONID，每次登入換 session id；前端不保存 bearer/PAT 到 localStorage，寫入送 X-XSRF-TOKEN。Playwright 可重跑流程在 `hub/web/scripts/phase4-acceptance.mjs`，完整 Hub／CLI／Paper 編排在 `hub/scripts/phase4-acceptance.sh`；涵蓋登入、PR 建立／跨帳號審核、衝突選擇重載、座標聚焦、XSS 文字、合併、release 下載與 CSP。

最後結果：1.21.11／26.2 共 10 次瀏覽器流程全部通過，CSP violation 與 JS／console error 皆 0；lint、26 項前端測試及 build 通過。原始 18 張截圖在 `.work/phase4-hub-e2e-complete/screenshots/`，9 張精選在 `hub/docs/screenshots/phase4/`；結果見 [e2e-results.json](../hub/docs/phase4-security/e2e-results.json) 與 [14 Hub](14-phase4-progress.md#hub)。繁中字型只用於驗收主機，未打包進 Hub。容器映像建置與冒煙已由主對話以 Podman 驗證通過。
