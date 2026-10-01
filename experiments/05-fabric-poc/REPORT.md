# Phase 0 實驗 05：Fabric 鬼影 diff 與插件握手

實作／查證跨 2026-09-30 至 2026-10-01（UTC）。所有伺服器都使用 00-env baseline 的實驗複本；沒有修改設計文件或 baseline，沒有 commit／push。

**兩版都成功在真正 Fabric 客戶端渲染**：移除鬼影使用原版模型，新增／修改／衝突使用三種不同外框；共保存 14 張原始截圖。相同 Java 21 插件完成兩版握手，未裝模組 bot 皆在逾時後被判定為 NO_MOD；clear、1 萬／10 萬格分包與固定 buffer 也實際驗證。兩版客戶端與伺服器都正常退出（code 0），測試 port 已關閉。

10 萬格使用約 98.9 MB vertex buffer；llvmpipe 的平均整體幀時間約 553 ms（1.21.11）／476 ms（26.2），因此本 PoC 證明功能與版本轉接可行，正式版仍需分區裁切與 LOD。

## 1. 實際使用版本

| 項目 | 1.21.11 | 26.2 |
|---|---|---|
| Paper | 132（STABLE，既有測試 jar） | 129（STABLE，既有測試 jar） |
| Minecraft 協定／DataVersion | 774／4671 | 776／4903 |
| 客戶端 JDK | Ubuntu OpenJDK 21.0.12.1 | Ubuntu OpenJDK 25.0.4.1 |
| 模組 class major | 65（Java 21） | 69（Java 25） |
| Loom | **1.17.21** | **1.17.21** |
| Gradle plugin ID | `net.fabricmc.fabric-loom-remap` | `net.fabricmc.fabric-loom` |
| mappings | `officialMojangMappings()`，產出 jar remap 至 intermediary | 原版不混淆；不設 mappings、不做 remap |
| Fabric Loader | **0.19.5** | **0.19.5** |
| Fabric API | **0.141.6+1.21.11** | **0.161.0+26.2** |
| fabric-rendering-v1 | 16.2.10+0290ad933e | 25.3.3+515ac5339e |
| fabric-networking-api-v1 | 5.1.6+6b6d71a53e | 6.3.4+2989c6a09e |
| 依賴設定 | `modImplementation` | `implementation`（新 Loom 不提供舊 configuration） |

共用 protocol 和插件都是 Java 21（major 65）；兩個模組都嵌入同一份 protocol jar。插件僅依賴 `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`（compileOnly）與 protocol，不用 NMS、PacketEvents、WorldEdit 或 ViaVersion。**同一個插件 jar**由兩版實際伺服器載入。

Gradle 9.6.1 本身以 Java 25 啟動，toolchain 分別選 Java 21／25，所有建置 `--max-workers=1`。版本從 [Fabric Meta](https://meta.fabricmc.net/v2/versions/loader/26.2)、[Loom Maven metadata](https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml) 與 [Fabric API Maven metadata](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml) 實際下載核對；不混淆 Loom 的使用方式也對照 [Fabric Loom 文件](https://docs.fabricmc.net/develop/loom/)。

## 2. 協定與大小上限

`protocol/` 不依賴任何 Minecraft 類別，公開 Java 21 record 與 enum；四端預設色票只有這一份：

| 類型 | protocol RGB | 客戶端視覺記號 |
|---|---|---|
| added | `#3FB950` | 綠色連續實心外框（線的 alpha=1） |
| removed | `#F85149` | 紅色原版模型鬼影（alpha=108/255） |
| modified | `#D29922` | 黃色角標外框，每條邊只畫兩端 23%；另準備舊模型（alpha=58/255） |
| conflict | `#A371F7` | 紫色外框，2 秒週期 alpha 0.25..1 的平滑閃爍 |

不同呈現方式可在顏色之外區分類型。PoC 未提供自訂／色盲替代色票設定介面。

### 2.1 握手

`worldgit:hello`：`version:VarInt`、`nonce:int64`、`capabilityCount:VarInt`、UTF-8 字串清單。字串以 byte 長度 VarInt 開頭，最大 512 bytes；能力最多 16 個。

插件加入事件建立 nonce，20 ticks 與 60 ticks 嘗試送 hello（plugin 宣告能力清單為空）；客戶端回覆相同 nonce、協定版本 1 和 `ghost-render`、`outline`、`section-palette-v1`。請求是 **10 bytes**，目前回覆是 **50 bytes**。伺服器驗證 nonce、版本與所需能力；未成功者於 120 ticks（20 TPS 時約 6 秒）寫 `NO_MOD ... fallback=display-entity-outline (decision only)`，示範指令不送 diff。

首輪實測有一次第 20 tick 的玩家 channel 清單仍是空的；第 60 tick 已註冊，第二次 hello 成功。正式版應保留重試與期限，不能只在 PlayerJoinEvent 當下送一次。此能力回報不是權限驗證；PoC 指令仍由 Bukkit 權限／op 控制。

### 2.2 diff、分包與清除

`worldgit:diff` 每個封包：

```text
version:VarInt, previewId:int64
sequence:VarInt, partCount:VarInt, totalEntries:VarInt
dimension:UTF-8, sectionCount:VarInt
for each section fragment:
  sectionX:int32, sectionY:int32, sectionZ:int32    # signed，big endian
  paletteCount:VarInt, palette[]:UTF-8 block-state
  entryCount:VarInt
  for each entry:
    packed:uint16 = localX | localZ<<4 | localY<<8 | type<<12
    otherStateIndex:VarInt                        # 0=沒有；其餘 palette index+1
```

type 為 added=0、removed=1、modified=2、conflict=3；uint16 的最高 2 bits 保留且必須為 0。每個 section 最多 4096 格；removed／modified 不可省略另一版本 state。座標可為負數；section 由算術右移 4 bits 算出。本次另一版本包含樓梯 facing／half／shape、半磚 type、柵欄與玻璃片四向連接、waterlogged 等完整屬性。

先把每個 section 分成最多 128 格的小段（若 palette 很大，還會依 bytes 再切），再按**實際編碼大小**合併封包，每包最多 **28,000 bytes**。一般小 palette 每格僅 uint16+一個 VarInt，約 **3 bytes**。跨封包的同一 section fragment 會重送該段需要的 palette；不假設整個 section 的 palette 能放進一包。

客戶端最多收 100,000 格、8192 包，核對 previewId／dimension／parts／總數，完整批次才原子發布；拒絕重複序號、重複位置、不一致 metadata、截斷、多餘 bytes、過大的字串／長度／座標、缺少 removed／modified state。組裝期限 30 秒（在後續 accept 時檢查）；clear／離線會清除；已建立場景會在換維度時清除（跨維度切換尚未做完整競態驗證）。TCP 通常不亂序，但重組也通過了反向序號測試。

`worldgit:clear`：version+previewId，共 **9 bytes**。客戶端關閉全部 buffer，取消尚未完成的批次，用 previewId 排除較舊封包。

### 2.3 兩版實際限制

| 層 | 1.21.11 | 26.2 | 查證 |
|---|---:|---:|---|
| Minecraft clientbound custom payload fallback | 1,048,576 B | 1,048,576 B | 兩版原始碼 `ClientboundCustomPayloadPacket.MAX_PAYLOAD_SIZE`／`DiscardedPayload.codec` |
| Minecraft serverbound custom payload fallback | 32,767 B | 32,767 B | 兩版 `ServerboundCustomPayloadPacket` |
| Paper plugin Messenger | 1,048,576 B | 1,048,576 B | Paper `Messenger.MAX_MESSAGE_SIZE` 的 jar 常數／Javadoc |
| WorldGit 自訂 codec | 28,000 B | 28,000 B | 應用層主動限制，兩個方向都低於較小上限 |

Fabric 註冊型別後走自訂 codec，而非 vanilla 的 discarded fallback；因此模組自己的 decoder 也明確檢查上限，沒有把 vanilla fallback 當成自動保護。28 KB 是保守的共同封包上限，S2C 本來可到 1 MiB；小分包也避免一次巨大的解碼／上傳尖峰。Paper 常數可對照 [1.21.11 Messenger](https://jd.papermc.io/paper/1.21.11/org/bukkit/plugin/messaging/Messenger.html) 和 [26.2 Messenger](https://jd.papermc.io/paper/26.2/org/bukkit/plugin/messaging/Messenger.html)。原版原始碼由 Loom genSources 取得，查證摘錄留在 `.work/fabric-poc/research/`。

實際 fixture 的應用層 payload 大小兩版一致（不含 Minecraft packet／channel framing）：

| 總格數 | 封包數 | 總 payload bytes | 最大單包 bytes | 已讀回的移除 AIR 格數 |
|---:|---:|---:|---:|---:|
| 64 | 1 | 974 | 974 | 16 |
| 10,000 | 2 | 38,407 | 27,766 | 2,500 |
| 100,000 | 12 | 320,242 | 27,923 | 25,000 |

插件的 `fixtureMs` 是分 tick 寫入的整段 wall time，10 萬格約 5 秒，不代表單一 tick 佔用 5 秒。`encodeMs` 另在背景執行緒量測；原始數字保留在 results JSON。

## 3. 渲染與兩版轉接層

### 3.1 共用實作

`client-common/GhostScene.java` 將每種舊 block state 的原版 baked model 擷取成中性 `xyzuv` quad 清單。同一個 state 一批只擷取一次，本次 5 個 state 的 quads 數兩版一致：石磚 6、樓梯 11、半磚 6、兩側連接柵欄 26、玻璃片 14。

鬼影保留模型頂點、UV 與原版方塊 atlas 紋理，使用紅色頂點色及半透明 blending；未簡化為整格方塊。修改格也準備黃色舊模型，但它落在目前實心方塊內時會被深度遮擋，只能看見伸出的部分。PoC shader 無 AO／世界光照，採固定染色；block entity、流體、動態特殊模型不在本次驗證範圍。

外框是整格 12 條邊，略向外擴 0.008 格以避免與目前方塊表面 z-fighting。修改使用角標，衝突由 DynamicTransforms 的 alpha 閃爍。**不穿透世界方塊**：保持深度測試，所有 overlay 都不寫 depth，讓鬼影位置與遮擋保持可理解；穿牆顯示容易把近／遠景疊在一起。正式版可再提供明確的透視選項。

幾何與顏色只在完整新批次到達時建置；models／positions／palette 不在逐幀迴圈遍歷。上傳後只有 4 個 persistent GPU vertex buffers（鬼影、新增、修改、衝突），共用原版的 sequential index buffer。每幀只繪製 buffer、更新相機平移矩陣與衝突 alpha。以最小座標作局部原點減少大座標的 float 精度損失。clear／disconnect／CLIENT_STOPPING 均呼叫 close。

### 3.2 實際 API 差異

| 項目 | 1.21.11 | 26.2 | 轉接方法 |
|---|---|---|---|
| extraction 事件 | `world.WorldRenderEvents.END_EXTRACTION` | `level.LevelExtractionEvents.END_EXTRACTION` | 各版註冊 `WorldGitClient.extract()` |
| drawing 事件 | `WorldRenderEvents.END_MAIN` | `LevelRenderEvents.END_MAIN` | buffer 繪製在 main pass 尾端 |
| render state | `worldState()`、`matrices()` | `levelState()`、`poseStack()` | 只取 cameraRenderState.pos；view rotation 取 RenderSystem，避免用 identity PoseStack |
| 模型入口 | `getBlockRenderer().getBlockModel(state)` | `getModelManager().getBlockStateModelSet().get(state)` | `Adapter.model` 輸出共用 Quad |
| model part | `renderer.block.model.BlockModelPart` | `renderer.block.dispatch.BlockStateModelPart` | 兩版都 collectParts、各方向+unculled quads |
| BakedQuad | `renderer.block.model.BakedQuad` | `resources.model.geometry.BakedQuad`，材質資訊放 materialInfo | 兩版都有 position(v)／packedUV(v)，以 UVPair 解開 |
| vertex format／mode | `withVertexFormat(format, VertexFormat.Mode)` | `withVertexBinding(0, format)`＋`PrimitiveTopology` | 共用 bytes stride：外框 16 B、鬼影 24 B |
| uniforms／sampler | `withUniform`／`withSampler` | `withBindGroupLayout` | 同一份 Projection／DynamicTransforms GLSL |
| depth | `LEQUAL_DEPTH_TEST`，傳統 Z | **reversed Z：`GREATER_THAN_OR_EQUAL`** | 不能沿用舊比較方向；兩版都不寫 depth |
| blending | `withBlend(TRANSLUCENT)` | `withColorTargetState(ColorTargetState(...))` | alpha blending |
| 細線 | `Mode.DEBUG_LINES` | `PrimitiveTopology.DEBUG_LINES` | `LINES` 兩版都指粗線 quad 展開，不能用於 2 頂點線 |
| vertex／index buffer | GpuBuffer、VertexFormat.IndexType | GpuBufferSlice、獨立 IndexType | drawing adapter 處理 |
| drawIndexed | `(baseVertex, firstIndex, count, instances)` | `(count, instances, firstIndex, baseVertex, firstInstance)` | 4 vs 5 參數且順序改變 |
| framebuffer | `Minecraft.getMainRenderTarget()` | `gameRenderer.mainRenderTarget()` | 同時用於真實截圖 |
| view matrix | `getModelViewMatrix()` | `getModelViewMatrixCopy()` | 輸出共用 Matrix4f |
| payload registry | `playS2C()`／`playC2S()` | `clientboundPlay()`／`serverboundPlay()` | 共用收送邏輯，Adapter 提供 registry |
| 自動化 GUI | `Minecraft.screen/getOverlay`、`options.hideGui` | `Minecraft.gui.screen()/overlay()`、`gui.hud.toggle()` | 差異不散布到共用程式 |

26.2 的反向深度不是只看文件推測：第一次實際截圖中移除鬼影消失，原版 `DepthStencilState.DEFAULT` 直接使用 `GREATER_THAN_OR_EQUAL`；修正後重跑截圖檢查。同時查 `GlConst` 確認 `LINES→GL_TRIANGLES`、`DEBUG_LINES→GL_LINES`，修掉初版的綠／紫三角面。**編譯成功與 Loader 載入成功都不足以證明畫面正確。**

轉接層為兩個同名 `Adapter.java`，各自和共用 sources 編譯；協定、幾何、快取、shader、量測與自動化流程共用，沒有 runtime 版本字串分派或反射。Fabric 現行指南也要求區分 extraction 和 drawing，見 [Rendering in the World](https://docs.fabricmc.net/develop/rendering/world)；本次在 extraction 取模型，在 drawing 只使用 GPU 資料與 render state。

## 4. 實際客戶端驗證

| 驗證項目 | 1.21.11 | 26.2 |
|---|---|---|
| protocol 檢查、插件／模組 build | 通過 | 通過 |
| Fabric Loader 真正載入模組 | 通過，Java 21 | 通過，Java 25 |
| Xvfb＋Mesa 實際渲染／原版模型鬼影 | 通過 | 通過（修正反向深度與線 topology 後） |
| 插件 ↔ 模組 version／nonce／capabilities 握手 | HANDSHAKE_OK | HANDSHAKE_OK |
| 未裝模組 mineflayer bot | NO_MOD，120 ticks | NO_MOD，120 ticks |
| 64／10,000／100,000 格完整接收與 buffer | 三組皆成功 | 三組皆成功 |
| 移除格實際為 AIR | 三組數量符合 | 三組數量符合 |
| clear／fixture 還原 | restored=64／10000／100000 | restored=64／10000／100000 |
| 真實截圖 | 7 張 | 7 張 |
| 客戶端／伺服器退出；port 關閉 | 0／0；25631 已關閉 | 0／0；25632 已關閉 |

可核對 [1.21.11 精簡證據](results/1.21.11.json) 與 [26.2 精簡證據](results/26.2.json)。本次交付的 jar／截圖 SHA-256、class major、PNG 尺寸與 baseline 核對記錄見 [artifacts.json](results/artifacts.json)；baseline 雜湊與最後兩輪前的 00:20 UTC 觀測一致，檔案最後修改時間仍為 2026-09-30。

驅動是 `tools/run.py`：Xvfb → Loom runClient → 離線帳號 WgFabric → 本機 Paper，透過控制檔讓**真正 Minecraft 客戶端**發送指令、由伺服器傳送／轉視角，再用 Minecraft `Screenshot.takeScreenshot` 擷取 framebuffer。未使用 mineflayer 或網頁渲染器製作畫面。

預設 64 格四種類型各 16 格，原點 (14,192,14)，x 到 44、z 到 23，跨 x／z section；10 萬格也跨 y section。視角為正面 (29,198,-3)、側面 (49,197,20)、鬼影近照 (28,195,10)；大量預覽 (46,230,-12)。座標為 console tp 參數，整數 x／z 會被原版移至格子中心（+0.5）。

removedAir 是插件實際讀回為 AIR 的數量：64／10,000／100,000 格分別為 16／2,500／25,000，證明移除鬼影所處格子在伺服器已不存在舊方塊。clear 關閉客戶端 buffer 並還原 fixture；另一張 clear 截圖可見疊圖消失。握手未安裝組使用 mineflayer 4.39.0，1.21.11 原生，26.2 使用 03-paper-poc 已有的 26.1 資料＋protocol 776 hack，只驗證連線／不回覆握手。

### 截圖清單

| 場景 | 1.21.11 | 26.2 |
|---|---|---|
| 64 格正面／四種記號 | [64-front](screenshots/1.21.11-64-front.png) | [64-front](screenshots/26.2-64-front.png) |
| 64 格側面／遮擋 | [64-side](screenshots/1.21.11-64-side.png) | [64-side](screenshots/26.2-64-side.png) |
| 舊模型近照／非完整方塊 | [64-ghost-close](screenshots/1.21.11-64-ghost-close.png) | [64-ghost-close](screenshots/26.2-64-ghost-close.png) |
| 稍後同視角／衝突透明度 | [64-pulse-later](screenshots/1.21.11-64-pulse-later.png) | [64-pulse-later](screenshots/26.2-64-pulse-later.png) |
| clear 後疊圖消失 | [clear](screenshots/1.21.11-clear.png) | [clear](screenshots/26.2-clear.png) |
| 10,000 格總覽 | [10000-overview](screenshots/1.21.11-10000-overview.png) | [10000-overview](screenshots/26.2-10000-overview.png) |
| 100,000 格總覽 | [100000-overview](screenshots/1.21.11-100000-overview.png) | [100000-overview](screenshots/26.2-100000-overview.png) |

截圖都是未加工的客戶端 PNG；正面、側面、近照與稍後的同視角畫面可檢查不同表示法、非完整方塊與紫色透明度變化。失敗版的 26.2 三角面截圖另存 `.work/fabric-poc/attempts/262-wrong-depth-and-lines/`，不混入最終清單。

## 5. 效能（軟體渲染量級參考）

3 核心 AMD EPYC 7402P VM、OpenGL llvmpipe（LLVM 20.1.2，256 bits）、Mesa 25.2.8-0ubuntu0.24.04.2、1280×720、render distance=6、vsync 關閉、fps cap=260。Paper view distance=6、simulation distance=3；最終客戶端 simulation distance=5。每版依序測 64、10,000、100,000，無其它測試伺服器／客戶端同時運行。

10,000／100,000 格把相機移至 overview，截圖後再等約 8 秒，收 240 個 frame interval；64 格則在多角度截圖序列中收樣本，含視角切換，僅作小場景參考。`frameMean/P50/P95` 是兩次 render hook 間的**完整遊戲幀間隔**，包含 vanilla 世界、驅動、排程及限幀；`submitMean` 是 overlay draw 呼叫的 CPU wall time，也可能含 llvmpipe／driver 等待，**不是 GPU timestamp 或純增量成本**。沒有做關閉 overlay 的相同場景 baseline，不能把總幀時間全部歸因於 WorldGit。

`cpuBuild` 含模型快取、幾何與 byte buffer 編碼；`upload` 是 GPU buffer 建立／初始資料上傳，兩者不含網路解碼／批次重組。64 格含首次模型讀取與 JIT，後面的數字是暖過的程式；小樣本、同一台機器，不能拿來做硬體 FPS 承諾或比較版本優劣。

| 版本 | 格數 | cpuBuild ms | upload ms | totalBuild ms | frameMean ms | P50 ms | P95 ms | submitMean ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1.21.11 | 64 | 7.89 | 0.86 | 8.75 | 36.82 | 34.47 | 56.84 | 0.47 |
| 1.21.11 | 10,000 | 77.59 | 2.12 | 79.70 | 109.95 | 109.18 | 124.36 | 52.72 |
| 1.21.11 | 100,000 | 237.41 | 85.31 | 322.72 | 552.97 | 548.15 | 604.72 | 408.88 |
| 26.2 | 64 | 11.17 | 0.89 | 12.06 | 37.37 | 35.09 | 52.97 | 0.45 |
| 26.2 | 10,000 | 49.79 | 1.73 | 51.52 | 93.35 | 92.20 | 110.37 | 39.48 |
| 26.2 | 100,000 | 214.38 | 33.88 | 248.26 | 475.79 | 471.12 | 517.24 | 371.24 |

10,000 格 buffer 為 **9,888,000 B**（492,000 vertices），100,000 格為 **98,880,000 B**（4,920,000 vertices）；不含原版共用 index buffer 與 driver 配置。整批只有 5 種模型快取、4 個 draw meshes，但所有幾何都送繪製，沒有 section/frustum culling、LOD 或內部面消除。

主要瓶頸在每幀提交／繪製大量模型及透明 overdraw。固定 buffer 解決重建成本，仍不足以讓 llvmpipe 流暢畫 10 萬格；正式版需要 docs/06 §1.1 已規劃的區域包圍盒與分區裁切。

## 6. 實作過程的失敗與環境問題

- 最初嘗試 Loom 1.18.2；其 Gradle variant 要求 9.7.0，環境為 9.6.1，所以改成相容的 1.17.21（要求至少 9.5.0）。
- 26.2 的不混淆 Loom 沒有 `modImplementation`；改用 `implementation`。payload registry、GUI、render target 等差異是在兩版編譯時查出並隔離。
- 零相依測試是可執行 main，Gradle 9 的預設 Test discovery 沒找到 JUnit 測試會失敗；改由 `checkProtocol` 執行並接到 `check`，停用不適用的 Test task。
- 第一個真正客戶端啟動因 `withLocation("worldgit:...")` 的 String overload 自動補 `minecraft:` 而失敗；管線與 shader 改傳 `Identifier.parse(...)`。失敗 log 留在 `.work/fabric-poc/attempts/121-identifier-failure.log`。
- 首次 1.21.11 長程外層程序收到 SIGTERM，停在 1 萬格截圖之後。未把這次當完整成功，保留了 partial log。兩版串接的補跑在第二版啟動時再次收到外部 SIGTERM；最後改為各版獨立跑完。驅動另補 SIGTERM→例外→finally 清理。
- 首次 26.2 的渲染錯誤由實際截圖發現：粗線 topology 畫成三角面，傳統深度比較讓空氣中的鬼影消失。停止該輪，修正後重新驗證。
- 初版 options.txt 將 FOV 寫成 70.0（磁碟格式應為 normalized 的 0.0），simulation distance=3 又低於客戶端範圍。原版回到預設 FOV／simulation；最終驅動已寫成 0.0／5 並重跑。
- Loom 離線帳號會留下 Realms／profile key 的 401 訊息，Xvfb 有 standard cursor 警告、ALSA 無音效裝置而關閉音效。這些訊息照實保留；不影響本機 offline-mode 連線與 framebuffer 截圖，沒有宣稱 log 完全無 ERROR。

## 7. 正式 fabric/ 與 protocol/ 的建議、未完成事項

1. 保留純 Java 21 protocol、共用色票與 section palette；將 MC DataVersion、能力協商、預覽來源／維度、revision 與正式 schema 一併定版。PluginMessaging 的兩方向上限不同，不能拿 S2C 的 1 MiB 用來回傳大量 C2S 資料。
2. 採編譯期版本 adapter；26.2 要明確處理不混淆 Loom、反向深度、primitive topology、vertex binding／drawIndexed 參數。CI 除 build／Loader 檢查外，應保留真正客戶端的影像回歸。
3. 正式唯讀 preview 只送 diff、建立客戶端 buffer；本次 `diffdemo` 是**測試素材寫入器**，不能當正式 preview。PoC 的 clear 只還原 block state，沒有保存 block entity／實體／排程 tick。
4. 將 GPU buffer 分到 section／chunk 並做距離與 frustum culling；數萬格以上改畫區域包圍盒（衝突＞修改＞移除＞新增）。背景建幾何、限制 render-thread 的 upload 配額，避免首幀停頓。
5. 補 translucent 排序／鄰接內部面消除、方塊形狀外框、AO／光照、流體／特殊 block entity renderer；目前鬼影是固定 quad 順序、整格外框。資源包 reload 後也需要重建模型與 UV 快取，PoC 未接這個生命週期。
6. 正式協定需完成重組期限的週期清理、傳送背壓、取消／更換預覽，以及未知／不合法 block state 的可恢復處理；目前 state 無法解析會使渲染建置失敗。伺服器掉線或插件停用時，fixture 的世界還原也未做通用保證（只在 throwaway 複本使用）。
7. 未實作 plugin-only 的 display entity fallback（本任務只要求判斷）、準星舊→新 UI、衝突選 ours/theirs/base、色盲偏好設定、多維度／資源包／Sodium／Iris／其它渲染 backend 的相容性測試；未驗證 Folia 或封裝 jar 的 production launcher（本次為真正 Loom runClient）。
8. 缺少硬體 GPU 測試、純 overlay GPU timer、無 diff 的同場景 baseline、可視範圍／變動密度的多組樣本。此報告的數字是軟體渲染參考，不是正式產品效能目標。

重跑方式與產物路徑見 [README.md](README.md)。原始完整 log 在 `.work/fabric-poc/results/<版>/`，精簡 log、量測行與截圖清單已保留在本目錄 `results/<版>.json`。
