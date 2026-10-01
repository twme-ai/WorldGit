# Phase 0 實驗 07：大範圍網頁 3D 檢視器

> 日期：2026-10-01T01:34:29.786Z ～ 2026-10-01T01:51:58.639Z。原始結果：[bench-results.json](bench-results.json)；重跑：[README.md](README.md)。所有資料轉換與瀏覽器效能量測皆持有 `.work/bench.lock`；等待鎖的時間沒有算入網格耗時。**同一台機器（3 核心）上另有實驗 06（生存伺服器）與 08（Folia）在跑；它們與本實驗互斥取鎖，量測期間不同時重負載，但輕量背景工作與系統噪音仍在。**本實驗由 Codex 啟動，用量限制中斷後由 Sonnet 5.5 接手：24 B 主矩陣由 Codex 啟動的背景程序完成，16 B 對照與本報告由接手者完成。沒有修改 04、baseline、設計文件，沒有 commit／push。

## 1. 結論

**worker + greedy + 壓縮格式可以生成數千 chunk；顯示時仍必須有有限範圍、LOD 與串流。16×16 chunk 全部完整細節：24 B 頂點版本幾何資料 54.68 MB（>50 MB）；再壓縮到 16 B 後幾何資料（頂點＋index）38.03 MB（<50 MB），但含 atlas、framebuffer 與 heap 的整體顯示記憶體仍約 92.56 MB（>50 MB）。**

- 每頂點由 04 的 60 B 降到 **24 B**，再降到 **16 B**（第 4.1 節：網格時間不變、幾何資料再 -33%）；同一個 256 chunk 場景，greedy 相對「只剔除、不合併、同為 24 B」減少 **43.4%** 頂點資料。
- 10,000 chunk 的全量網格生成：1／2／3 worker 分別 **218.89／138.53／105.67 秒**；3 worker 對 1 worker 約 **2.07×**。全量完整網格有 **43,323,078 triangles／2079.51 MB 頂點資料**，不宜整批留在瀏覽器。
- 同一 10,000 chunk 資料集，在 **96 MiB GPU 配置預算**下實際保留 **128 完整 chunk + 9,872 LOD chunk**。GPU buffer **44.43 MB**；包含 atlas 與估算 GL framebuffer 的 GPU 顯示配置 **68.63 MB**。加入 main／worker heap、ArrayBuffer 與 overlay backing 的可追蹤項目合計約 **101.66 MB**。
- 256 chunk 全部完整細節：頂點 buffer **54.68 MB**，單此項就超過 50 MB（16 B 版 36.46 MB，見 4.1）；GPU 顯示配置 **80.45 MB**，可追蹤項目合計約 **110.27 MB**。把 worker 降為 1 仍約 **99.60 MB**，無法僅靠減少 worker 達標。
- headless 的 SwiftShader 幀時間很慢；10,000 chunk 飛越的同步繪製 P95 **448.9 ms**。主要結論來自網格與配置大小，不把它推論為真 GPU FPS。

## 2. 資料、合成方法與代表性

讀取 `.work/worlds/26.2/baseline/world/dimensions/minecraft/overworld/region` 的全部 **626 個 `minecraft:full` chunk**；跳過 1629 個 proto chunk。626 是 overworld；本次不含 nether／end。沿用 04 的 deepslate Anvil／NBT reader；沒有改 baseline。

共 **5,081 個非空 section／168 個 block entity**，方塊 state 308 種。section 正規化前後採 YZX：first-appearance 調色盤、屬性排序、LSB 緊密位元流、排序並移除位置／暫態欄位的 BE NBT。section 本體與 02 的未壓縮 v1 布局相同；外層用 `WGC7` chunk 封套（sy／長度）加每 section 64 個 biome ID。**不是直接讀 JGit 的 zstd blob**：本實驗省去 zstd／Git API，transport 用 raw binary。全量 section 原始 10.77 MB，chunk 封套與 biome 後 11.12 MB；唯一 section 4,931，去重率 **2.95%**。626 chunk 位元組精確 round-trip 通過，驗證方塊與 BE 可讀回。

基礎範圍 25×27 chunk，chunk x/z 最小為 -12/-14。把全部 626 chunk 的 payload 平移到 tile：橫向四格，偏移 `(tileX×25, tileZ×27)`；重複來源序列，最後一個 tile 截到指定數量。**只平鋪、不鏡射**，因此不需要改樓梯／門方向與 multipart state；tile 邊界按合成座標重新剔除，不沿用來源 mesh。

| 資料集 | chunks | 唯一 chunk payload | payload 重用比例 |
|---|---:|---:|---:|
| 真實 baseline | 626 | 626 | 0% |
| 平鋪 2,000 | 2,000 | 626 | 68.70% |
| 平鋪 5,000 | 5,000 | 626 | 87.48% |
| 平鋪 10,000 | 10,000 | 626 | 93.74% |

全量網格基準**沒有 mesh 雜湊去重或 instance 共用**，每個合成 chunk 都重新生成，輸出的 vertex bytes 按位置累加。原始 payload／高度圖仍共享 626 個來源，**網路量與 main 的高度圖記憶體對真實 10,000 個獨立 chunk 偏樂觀**。worker 原始與解碼快取各限 64 chunk，不把整個世界解碼常駐。

此世界主要是海洋、海床、海草／昆布與人工測試平台；不是高密度城市、森林、不同 biome 的真實大世界。平鋪造成不自然邊界、重複洞穴與相同資源／state 分布；末 tile 的截斷會增加外露面。因此可驗證工作量量級和配置控制，不能視為一般生存世界的最壞情況。06 沒有作為此實驗的輸入依賴；未額外讀取其大世界。

## 3. 網格與頂點格式

以 04 原型**複製**到本實驗；保留 `resources.ts`、`world.ts` 與 `base-mesher.ts` 的 deepslate 模型／特殊模型快取。沒有使用 deepslate 的 `StructureRenderer`／`ChunkBuilder`。

1. 每個 chunk 的 section 由 worker 解碼，帶四鄰接 chunk；section 上下與水平邊界填入 18³ padded 狀態表。未追蹤／未生成的鄰居當作外部空氣。
2. 對模型恰為六個完整單位面的 state，檢查幾何並快取面模板。依圖集 alpha 判定是否完整不透明，補上 double slab 這類不能只按 block 名稱判定的 state。完整立方體按 state、面方向、biome 合併連續矩形；不透明鄰居與同材質 self-culling 內部面剔除。
3. 非完整、含水、BE 特殊模型與多重 overlay 的完整方塊仍走 04 的模型快取；沒有為了合併而刪掉草方塊的 overlay。半透明完整立方體進半透明批次。cutout 與 opaque 共用 alpha-test shader，半透明／鬼影依 section 中心距離排序。
4. worker 輸出 section mesh，以 Transferable 回主頁；上傳 WebGL 後不保存 CPU mesh。GL buffer／VAO 在卸載時確實 `deleteBuffer`／`deleteVertexArray`。section 模型與單一來源 payload 快取不等於 GPU mesh 去重。

| 頂點欄位（24 B 版；16 B 版見 4.1） | bytes | 用途 |
|---|---:|---|
| xyz signed int16 | 6 | section 原點相對位置，1/64 格精度 |
| UV uint16×2 | 4 | 模型 atlas UV；greedy 使用 1/256 格重複座標 |
| texture limits UNORM16×4 | 8 | atlas 子貼圖界線；fragment shader 以 fract 重複貼圖，避免拉伸 |
| RGB UNORM8 | 3 | biome／模型染色 |
| light UNORM8 | 1 | 法線推導的預計算光照；沒有保存完整法線向量 |
| kind／repeat flag、alpha uint8 | 2 | diff 四類、重複 UV、半透明 |
| **合計** | **24** | quad 四頂點 96 B；另有所有 mesh 共用的 uint32 index buffer |

幾何檢查：兩個相鄰實心 section 原本 2,560 個外露單位面，合併為 **10 quads**，中間交界面為零；bottom slab 維持 fallback，double slab 通過完整立方體與遮擋檢查。全體 round-trip：20,811,776 格／626 chunk／168 BE。固定點造成的細微模型位置誤差與 fluid 形狀限制仍存在。

## 4. 全量網格生成

Playwright 驅動 **Chrome 154.0.8037.92**，Vite production build，127.0.0.1:5187。3 個 CPU，AMD EPYC 7402P 24-Core Processor，可見系統 RAM 21.0 GB。整段從 browser 啟動到後端嘗試／截圖結束皆持全域鎖；其他任務的輕量編譯仍可能造成背景負載。每個組合**單次**，沒有信賴區間。

每次新建瀏覽器 context 與 worker 池；每批 8 chunk，1／2／3 worker 並行。wall 時間包含 demand fetch、section 解碼、網格與批次派送，從 worker／資源初始化後開始計時。section 計時合計是 `performance.now()` 的逐 section elapsed 加總（包含 OS 搶占），**不是 OS CPU time**。初始化時間另在 JSON 的 `workerInit`。全量網格逐批累計後丟棄，**沒有把下表 GB 級資料全部上傳 GPU**；實際 GPU 常駐配置見下一節。

| chunks | workers | 全範圍 wall 秒 | wall ms/chunk | section elapsed 合計秒 | triangles | 完整 mesh 頂點 MB | JS heap MB | backing storage MB |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 626 | 1 | 14.30 | 22.85 | 12.97 | 2,746,582 | 131.84 | 8.54 | 6.97 |
| 626 | 2 | 8.74 | 13.97 | 14.25 | 2,746,582 | 131.84 | 13.15 | 13.34 |
| 626 | 3 | 7.03 | 11.23 | 16.71 | 2,746,582 | 131.84 | 17.69 | 19.46 |
| 2,000 | 1 | 43.69 | 21.84 | 39.68 | 8,617,934 | 413.66 | 8.63 | 7.12 |
| 2,000 | 2 | 26.10 | 13.05 | 42.69 | 8,617,934 | 413.66 | 13.38 | 14.07 |
| 2,000 | 3 | 21.49 | 10.74 | 51.04 | 8,617,934 | 413.66 | 18.07 | 20.85 |
| 5,000 | 1 | 109.28 | 21.86 | 99.69 | 21,663,962 | 1039.87 | 8.89 | 7.00 |
| 5,000 | 2 | 65.57 | 13.11 | 107.09 | 21,663,962 | 1039.87 | 13.74 | 13.98 |
| 5,000 | 3 | 52.15 | 10.43 | 125.03 | 21,663,962 | 1039.87 | 18.56 | 20.89 |
| 10,000 | 1 | 218.89 | 21.89 | 198.91 | 43,323,078 | 2079.51 | 9.25 | 7.01 |
| 10,000 | 2 | 138.53 | 13.85 | 226.35 | 43,323,078 | 2079.51 | 14.10 | 13.97 |
| 10,000 | 3 | 105.67 | 10.57 | 253.31 | 43,323,078 | 2079.51 | 19.01 | 20.98 |

JS heap 使用 CDP 對 main 與每個 worker 執行 GC 後讀 `Runtime.getHeapUsage.usedSize`，上述 backing storage 是同一 API 的 ArrayBuffer 等外部儲存。兩者分列；只看 `performance.memory` 會漏 worker 與外部 buffer。全量生成表是**保留快取、沒有常駐全量 mesh 的穩態**，不是瞬時峰值。cache／各 chunk timing 的明細保留在 JSON。

同一個真實 256 chunk、1 worker 的對照：

| 24 B 格式 | wall 秒 | triangles | 頂點 MB |
|---|---:|---:|---:|
| 只剔除、不 greedy | 7.26 | 2,011,774 | 96.57 |
| greedy | 5.93 | 1,139,246 | 54.68 |

第二次對照會重用 payload／模型快取，不能把耗時差全部歸因於 greedy；triangle／byte 差則不受快取影響。04 的 247 MB 是不同的 A/B/diff 留存策略與 60 B 格式，不能直接用其耗時／bytes 當作受控對照。

## 4.1 頂點格式再壓縮：24 B → 16 B（前後對照）

主表（第 4、5、6 節）全部是 **24 B** 版本的結果。接手者（Sonnet 5.5）針對 50 MB 目標做了一次壓縮並重量：把 4×u16 的 atlas 矩形（8 B）改為 u16 材質矩形索引（2 B），並由主執行緒以 1024×N 的 RGBA32F 資料紋理（`u_rects`，約 22 KB）在 vertex shader `texelFetch` 還原；alpha 與 light／kind／repeat 合併為 1 個 byte（light 4 bit、repeat 1 bit、kind 3 bit，水面 alpha 0.72 以 kind 5 表示）。結果頂點 **16 B**：xyz int16×3（6）+ UV u16×2（4）+ 矩形索引 u16（2）+ RGB（3）+ flags（1）。

代價與限制：光照只剩 16 階（原 256 階），畫面略偏暗（見 `screenshots-16b/`）；diff ghost 水面不保留 0.72 alpha；「不在 atlas 表內的部分矩形」（推測為動畫貼圖首格、裁切過 UV 的植物／模型；未逐一確認來源）以「完全包含它的最小 atlas 矩形」代替，NEAREST 取樣下取樣結果相同，最多 255 種，未能解析者 0 種。第一次 16 B 嘗試曾把這些部分矩形當成未命中而渲染成無貼圖，量測後才發現（約佔頂點 0.7%），修正後重跑，**下表只用修正後的重跑結果**。16 B 這輪**只重跑**部分組合（626／2,000 chunk 網格 1／3 worker、window256 與 10,000 chunk 顯示），不是完整矩陣，每組一次，同樣持全域鎖；結果檔 [bench-results-16b.json](bench-results-16b.json)，截圖 [screenshots-16b/](screenshots-16b/)。

| 項目 | 24 B | 16 B | 變化 |
|---|---:|---:|---:|
| 626 chunk 完整頂點資料 MB | 131.84 | 87.89 | -33.3% |
| 2,000 chunk 完整頂點資料 MB | 413.66 | 275.77 | -33.3% |
| 626 chunk、1 worker 全量 wall 秒 | 14.30 | 14.29 | 約持平 |
| 626 chunk、3 worker 全量 wall 秒 | 7.03 | 7.54 | +7%，單次且同機有他任務，不判定為變慢 |
| 2,000 chunk、1 worker 全量 wall 秒 | 43.69 | 44.12 | 約持平 |
| 2,000 chunk、3 worker 全量 wall 秒 | 21.49 | 21.61 | 約持平 |
| window256 頂點 buffer MB | 54.68 | 36.46 | -33.3% |
| window256 頂點＋index MB | 56.26 | 38.03 | **兩者皆 <50 MB** |
| window256 GPU 顯示估算 MB（含 atlas 16.78、framebuffer 估算 7.42） | 80.45 | 62.26 | -18.20 MB |
| window256 可追蹤合計 MB | 110.27 | 92.56 | -17.71 MB |
| 10,000 chunk（128 完整 + 9,872 LOD）頂點 buffer MB | 42.86 | 28.57 | |
| 10,000 chunk GPU 顯示估算 MB | 68.63 | 54.37 | |
| 10,000 chunk 可追蹤合計 MB | 101.66 | 87.88 | |
| window256 飛越 draw+sync mean／P95 ms（SwiftShader） | 650 / 1435 | 615 / 1445 | 噪音範圍內 |
| 10,000 chunk 飛越 draw+sync mean／P95 ms | 268 / 449 | 260 / 448 | 噪音範圍內 |

判讀：16×16 chunk 全部完整細節的**幾何資料（頂點＋index）已降到 38.03 MB，低於 50 MB**；但「整個顯示記憶體」仍是 GPU 顯示估算 62.26 MB（atlas 約 17 MB 與 framebuffer 估算佔 24 MB）、含 JS heap 與 worker ArrayBuffer 的可追蹤合計 92.56 MB，**仍超過 50 MB**。是否算達標取決於 doc 10 的「顯示記憶體」是否只計幾何：只計幾何則達成（且仍有 greedy、LOD 可進一步縮），計整個瀏覽器工作集則否。進一步要靠 atlas 分頁／縮小（只載入場景用到的材質）、worker 完成後釋放解碼快取、只保留可見 section，以及植物與水面改用更省的表示；頂點格式本身再壓縮的邊際效益很小（再降到 12 B 需要 quad instancing）。網格生成時間因 shader 之外的邏輯幾乎不變，只多一次矩形查表（以上一個矩形快取避開每頂點字串查詢）。

## 5. 顯示、LOD、串流與 50 MB 目標

近處以 chunk 為串流單位、section 為 GPU mesh 單位；相機水平距離最近 128 chunk 保留全部非空 section。遠景每 chunk 為 **4×4 高度圖／16 quads／32 triangles／1,536 B 頂點**，高度是 4×4 方格平均，色彩取頂部代表方塊的貼圖平均色。LOD 不保留洞穴、室內、懸挑、多層建築與水下植被；不能代替近景模型。

每個 frame 用 VP 六面平面對 section AABB 做視錐裁切；半透明依距離排序。相機移動時載入新的完整 chunk，卸載舊 GPU buffer，再補回 LOD。原型原始／解碼快取為 bounded FIFO（各 worker 64 chunk），正式版應改 LRU 與移動遲滯。輸出本次上限 128 完整 chunk 是**可控的策略上限，不是硬體最大容量**；byte 預算不足時會繼續退回 LOD。

GPU 預算 96 MiB = 100.66 MB，包含頂點、共用 index（1.57 MB）、2048² RGBA atlas（16.78 MB）與估算 GL framebuffer（7.42 MB）。下表可追蹤合計再加 CDP heap、backing storage，以及 Canvas2D overlay RGBA backing（3.30 MB）。GL driver shadow copy、Chrome compositor／原生 font cache／allocator 等**未完整量測**；合計不是整個 Chromium RSS，也不是真硬體 VRAM 讀值。這個缺口不改變 50 MB 判定：256 chunk 的頂點 buffer 本身已超標。

畫布 1280×644；最初相機看向測試平台，以下顯示均 2 workers。首次可見與首次完整 mesh 可見是 navigation 時鐘、第一次同步繪製有可見 mesh 的時間；ready 是既定近景完整 chunk 全部上傳完畢。首次畫面可以只有 LOD。

| dataset | 完整 / LOD | 首次可見 / 完整可見 ms | 全部 ready 秒 | GPU buffers MB | GPU 顯示估算 MB | JS heap MB | backing MB | 可追蹤合計 MB |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| window256 | 256 / 0 | 1329 / 1348 | 23.69 | 56.26 | 80.45 | 13.78 | 12.74 | 110.27 |
| baseline | 128 / 498 | 814 / 1415 | 4.54 | 30.03 | 54.23 | 13.49 | 12.34 | 83.36 |
| 2000 | 128 / 1872 | 908 / 1578 | 4.61 | 32.14 | 56.34 | 14.06 | 12.36 | 86.06 |
| 5000 | 128 / 4872 | 955 / 1479 | 4.81 | 36.75 | 60.95 | 15.31 | 12.35 | 91.90 |
| 10000 | 128 / 9872 | 1156 / 1661 | 4.94 | 44.43 | 68.63 | 17.37 | 12.36 | 101.66 |

**16×16 全完整細節（24 B 版）未達 <50 MB；16 B 版幾何達標、整體未達標（見 4.1）**。1 worker 顯示同樣 geometry，heap 9.27 MB／backing 6.57 MB，合計約 99.60 MB。要保留完整垂直範圍並達到總顯示記憶體 50 MB，尚須 quad instancing／材質索引、細分可見 section、縮小或分頁圖集、降低植物幾何，以及更小的 decode cache；不能只把頂點由 float 改成整數。

**10,000 chunk 的實測答案：128 完整 + 9,872 LOD，約 101.66 MB 可追蹤配置**。飛越結束仍為 128 完整 + 9,872 LOD，卸載 512 個完整 chunk；GPU 顯示配置 71.67 MB。這支持正式版以約 128–160 MiB 的瀏覽器工作集起步，並按 bytes 動態降低完整 chunk 數；不是對任意密集建築的容量保證。真實獨立大世界的高度圖應由伺服器預先降採樣並按 tile 串流，避免本實驗共享 626 份高度圖的樂觀因素。

## 6. 相機飛越與 GL 後端

每個資料集 60 幀，從資料集 x 最小到最大、z 中央、y=125，看向前方與水面；每 10 幀嘗試背景串流，完整 detail 上傳與卸載可並行。每幀使用 1 pixel `readPixels` 強制等待 GL 工作完成，量測 `draw + sync`；不能用未同步的提交時間當 GPU 幀時間。另記 RAF interval，包含訊息／上傳／主頁其他工作；兩者不可混為一談。最終等待串流穩定後再讀 memory。

| dataset | draw+sync mean / P50 / P95 ms | RAF interval mean / P50 / P95 ms | 完整 chunk 卸載累計 | 結束完整 / LOD |
|---|---:|---:|---:|---:|
| window256 | 649.6 / 627.5 / 1435.4 | 647.4 / 629.0 / 1448.7 | 0 | 256 / 0 |
| baseline | 320.7 / 345.3 / 523.4 | 359.3 / 384.8 / 628.8 | 339 | 128 / 498 |
| 2000 | 118.3 / 122.8 / 230.3 | 153.3 / 155.2 / 313.2 | 503 | 128 / 1872 |
| 5000 | 190.7 / 191.5 / 339.4 | 240.1 / 249.3 / 423.0 | 512 | 128 / 4872 |
| 10000 | 267.8 / 271.5 / 448.9 | 340.8 / 348.3 / 620.7 | 512 | 128 / 9872 |

主測 renderer：`ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero) (0x0000C0DE)), SwiftShader driver)`。沒有 Xvfb、沒有硬體 GPU；SwiftShader 與 worker 都使用同一組 CPU，所以 frame 數字只能看量級。額外 GL 後端嘗試（也持鎖）：

- `egl`：ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero) (0x0000C0DE)), SwiftShader driver)
- `angle-gl`：ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero) (0x0000C0DE)), SwiftShader driver)
- `angle-vulkan`：ANGLE (Mesa, Vulkan 1.4.318 (llvmpipe (LLVM 20.1.2 256 bits) (0x00000000)), llvmpipe)

不能據此聲稱真 GPU 達到某個 FPS；未取得硬體 renderer 的數據。

## 7. diff 與真實截圖

四類使用 docs/06 §1.1 的精確色票：新增 `#3FB950`、移除 `#F85149`、修改 `#D29922`、衝突 `#A371F7`。新增實線外框、移除紅色半透明鬼影、修改虛線與 fragment 角標效果、衝突紫色緩慢閃爍；不只靠紅綠色辨識。

**本次是合成 diff fixture，不是兩個真實 commit 的 diff**。近景放 36 個四色標記 mesh，遠處每隔 8 chunk 有區域標記。密集模式以每 chunk 4,096 格的種類統計直接畫區域包圍盒，依衝突 > 修改 > 移除 > 新增決定色彩；10,000 chunk 共 **40,960,000 格／10,000 個 bbox**，沒有生成四千萬格 geometry。近景 diff 新增頂點量只有 **0 B（包圍盒模式）**；逐格示範 mesh 的詳細配置見 JSON `diff.small`。移除舊非完整模型、真實 A/B/merge-base 來源與 diff 算法不在此實驗完成範圍內。

下列 PNG 全部由 Playwright `page.screenshot()` 直接擷取同一個 10,000 chunk 頁面；不是圖片生成。附繁體中文字型避免 headless 缺字，字型存於 `.work/viewer-scale/fonts`。

| 截圖 | 內容 |
|---|---|
| [近景](screenshots/01-near.png) | 完整模型、測試平台、海草與水 |
| [遠景 LOD](screenshots/02-far-lod.png) | 大範圍低細節高度圖；重複地形一目了然 |
| [diff 疊加](screenshots/03-diff-overlay.png) | 四色、鬼影與不同線型 |
| [區域包圍盒](screenshots/04-region-boxes.png) | 數千區域，依優先種類上色 |

## 8. 正式前端建議

- **保留 deepslate 模型層與自寫 renderer 的採用方向。** 加上基於完整 occlusion shape／材質的 greedy；多層 face overlay／植物可先 fallback，後續用 quad／植物 instancing。模型快取與 GPU mesh 快取分開；GPU 快取鍵包含 section 內容、六鄰居邊界、DataVersion、圖集布局和 mesher 版本。
- **常態 2 workers、離線批次可用 3 workers。** 此機 3 worker 可加速全量生成，但會與畫面競爭 CPU；正式版把 decode／mesh 派送與 frame upload 分開，主頁每 RAF 限制 upload 時間，取消落後相機的工作，重建粒度維持 section。
- **起始瀏覽器工作集約 128–160 MiB，而不是全世界完整 mesh。** 以 96 MiB GPU 配置加 worker／CPU buffer 預算為起點、近景最多約 128 chunk，依據密度退回 LOD；完整／LOD cache 必須按 bytes 受控，保留小型邊界 halo，採 LRU、距離遲滯與 unload。若產品硬性要求 50 MB，需降低近景完整範圍並優先實作圖集分頁／quad instancing；本原型沒有證明 50 MB 可行。
- **LOD 改為少量粗 tile draw call，分兩級以上。** 近距離完整、較遠 4×4 或 8×8 高度色塊、最遠 BlueMap lowres；洞穴／多層／衝突點強制提升細節。高度圖 min/max bounds 可改善目前保守的整 chunk AABB 裁切。
- **伺服器應預產生高度圖／平均顏色與 diff 區域統計。** 使用 dirty section 與含 halo 的內容 hash 重建；按 tile 提供已降採樣的高度／顏色，避免每個訪客下載全世界方塊或全 256 格頂面資料。這不是重寫 deepslate 模型解析。
- **近景 server mesh 不是初版必要前提。** 10k 完整 mesh 的首次全量生成很昂貴，但串流只計算一小圈；可選擇為熱門 section 快取 immutable mesh，不能以每個 commit 重建全世界為預設，也不能只用 section hash 忽略鄰居。diff 上色／鬼影與 ours/theirs/base 仍留瀏覽器動態處理。
- **BlueMap lowres 負責世界總覽與最遠景，瀏覽器高度圖負責銜接到近景的過渡。** 延續 doc 10 的混合方案：Hub 嵌 BlueMap core 算 tile／lowres，WorldGit 自己依內容 hash 合成與快取；近景與 diff 使用正規化 section。大範圍 bbox 由伺服器 diff 統計直接生成，只有使用者靠近或點選的區域再載入逐格資料。

## 9. 未完成與限制

- **<50 MB 只在「純幾何」口徑達成（16 B：38.03 MB）**；整體顯示記憶體仍約 62.26 MB（GPU 估算）／92.56 MB（可追蹤合計）。16 B 只重跑部分組合（626／2,000 網格 1／3 worker、window256 與 10,000 顯示），5,000 chunk 與 2 worker 沒有 16 B 數據。未完成 quad instancing（12 B 以下）、atlas paging、只保留可見 section、worker buffer pool 與真實 native／driver 峰值 RSS 的量測。
- 只有一份海洋 baseline 與平鋪資料；未讀真實數千獨立 chunk 世界、森林／城市／極密集建築。高度圖共享與材質分布偏樂觀。
- 每組一次、60 幀；沒有多次重複的信賴區間、真 GPU 或行動裝置測試。首次 ready 與幀時間受軟體渲染和主頁分批繪製影響，不能只歸因於 mesher。
- transport 是 raw core section v1 封套；未測 zstd 解碼、JGit/API、遠端延遲／gzip、權限或 production Hub 整合。
- diff 是合成標記／region 統計；未接兩個 commit、真實衝突區域分群、舊模型鬼影與四端共用 protocol 色票檔。色票依文件精確設定，但在此獨立原型內仍是本地常數。
- 繼承 04 的 uvlock、流體角落／流向、AO、動畫貼圖、告示牌文字、實體外觀等缺口；半透明只按 section 中心排序。full-cube/alpha 判定仍是原型啟發式，正式版需 server data generator／完整 occlusion shape。
- 截圖中 header 的 select／button 文字有「□」缺字（表單控制項沒有套用內嵌 CJK 字型），畫布內容與 HUD 不受影響；屬截圖頁面瑕疵，未修。
- 本次沒有因後端無法初始化而改報主測結果；失敗的 GL 嘗試保留於 `backends`，不宣稱成功。早期煙霧測試另保留 `.work/viewer-scale/smoke-results.json`，其舊格式／未裝字型截圖不是正式數據。

## 10. 最終磁碟與清理

| 路徑 | apparent bytes / MB | 實際配置 MB (`du`) |
|---|---:|---:|
| `.work/viewer-scale` | 135,564,098 / 135.56 | 143.35 |
| `experiments/07-viewer-scale` | 2,161,221 / 2.16 | 2.27 |

合計 apparent **137.73 MB**，實際配置 **145.63 MB**，遠低於 6 GB；不重複計入原本共用的 `.work/assets`／baseline。Chrome profiles 由 Playwright close 清除，Vite preview／Chromium 已結束，沒有 Xvfb 或 bot。程式、報告、截圖與結果只寫入本實驗與 `.work/viewer-scale`；依賴複本、pack、資料與 build 皆留在私有工作目錄，便於重跑。
