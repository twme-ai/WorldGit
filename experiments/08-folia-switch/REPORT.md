# Phase 0 / 08：Folia 大量 chunk switch（Paper 對照）

> 測試日期：2026-10-01，環境只有 3 核心、19 GiB RAM。同時有 06、07 任務；本實驗的 fixture、伺服器、bot、壓測與離線比對**全程持有 `.work/bench.lock`**。程式與重跑方式見 [README.md](README.md)。原始 JSON、A/B manifest 與 console 證據在 `.work/folia-switch/`。
> 只修改 `experiments/08-folia-switch/` 與 `.work/folia-switch/`；03 原插件與 baseline 未修改；沒有 commit、push 或外部部署。

## 1. 方法與範圍

### 1.1 A/B 與大範圍 fixture

從 03-paper-poc 複製插件，再擴充 `Bench` coordinator，沿用 `Sect.replace`、`Nms`、`Sched`。02 的 `Nbt`、`Region`、`Codec` 複製到 08，沒有改 02。

使用 baseline **副本的 level.dat/世界設定**，在副本 overworld 寫入 3 個 32×32 的 full-chunk 區域，共 3072 個 chunk。區域原點 chunk x=0、128、256，彼此相距 2048 格；各區域的 switch 範圍 x=4..27、z=8..21，共 **1008 個 chunk、4032 個有差異的 section**（Y=8..11）。halo 讓本測試不必生成周邊地形；這是可重現的合成負載，不是自然生成的大型生存世界。

A：分層石頭/泥土、稀疏木造建築與地板；B：厚實地形、石磚外牆與玻璃屋頂。每個 chunk 另有上層平台、發光方塊、裝有鑽石／綠寶石的箱子與不同位置的講台。沒有水流、紅石或燃燒中的 BE；random_tick_speed=0，關閉自然刷怪、時間與天氣。這能把 switch 正確性與自然演化分開量測。

快照採**伺服器記憶體快照**，沒有建立 JGit repo：每個 section 是 palette 容器複本與完整 BE NBT；實體是完整的 NMS 存檔 NBT。初始化在 owner thread 保存 A，短暫套 B 並讀回正常化後的伺服器 B 內容，再還原 A。初始化（含 JIT、首次載入、建立快照）不算 switch 壓测。

每區域 12 個實體位置，每位置各有盔甲座、固定展示框、有名字的 NoAI 牛，共 **108 隻**。B 將同 UUID 循環移到下一區域，並改名稱、盔甲座裝備與展示框物品；A/B 的 UUID 集合相同。

### 1.2 switch 實作

1. commit 前存檔：向所有候選 chunk 排 owner callback，在執行當下取得 region ID，對每個 ID 去重後呼叫 `ChunkHolderManager.saveAllChunks(true,false,false,true)`。Paper 只有一個 region。
2. 全域 coordinator 限制 in-flight chunk 數為 24，呼叫 `getChunkAtAsync`；其 callback 排到 owner region，加 plugin ticket，從傳回的 `CraftChunk` 取得 full handle。
3. 使用當下的 region ID + 該 region tick 編號共用預算。每個 section 開始前檢查累積耗時與 section 數；用完即延到該 chunk 的下一個 region tick。固定 chunk 格網僅用於 fixture，**沒有把格網当作 Folia region**。
4. 只替換 A/B 差異 section，沿用 03：移除舊 BE、換 section、重建 BE、heightmap、光照 `checkBlock`/`updateSectionStatus`、POI、`markUnsaved`。每個 chunk 全部 section 完成後 `refreshChunk`，再釋放 ticket。
5. 在來源 region 按追蹤 UUID 移除舊實體。所有 block/removal 工作完成後才進入生成階段，在各目標 region 從完整 NBT 生成；跨 region 不同步 teleport、不從一個 region 讀另一個 region 的 live entity。
6. switch 後同樣按實際 region 去重存檔。成功才將 prototype HEAD 改成 A/B；取消或錯誤則標記 `PARTIAL`。
7. 期間取消玩家放置、破壞與方塊 physics 事件；取消玩家 FALL/SUFFOCATION/DROWNING 傷害，成功或取消後再延續 10 秒。計數與期限採並行安全欄位。

時間預算是**軟上限**：單一 section 不可中途搶占；palette copy、替換與光照提交計入 section 預算，chunk 載入、refresh、實體階段、flush、GC 與伺服器自己的 tick 不受此限。

[Folia scheduler 文件](https://docs.papermc.io/paper/dev/folia-support/) 與 [region 邏輯](https://docs.papermc.io/folia/reference/region-logic/) 說明了 location/entity owner 與 region 合併／分裂。此原型每次 callback 重新取得 owner ID，避免把初始分組沿用到 region 合併後。

### 1.3 量測與驗證定義

- 伺服器為既有 Folia 1.21.11 build 14、Folia 26.2 build 7、Paper 26.2 build 129 的副本。JDK 21/25、同一 Java 21 插件 jar。
- 127.0.0.1、online-mode=false、Xmx=3G、view/simulation=2、Folia region threads=2、chunk worker/IO 各 1。3 個 mineflayer bot 分散在 chunk (16,15)、(144,15)、(272,15)。26.2 沿用 `.work/bot/` 的 26.1→776 hack。
- 比較 5/10 ms 與 4/16 section 上限。每組 A→B→A；有限次單機測試，非多次重複的統計信賴區間。
- switch 時鐘從 commit 前存檔完成後開始，含 ticket 載入、替換、實體 barrier/生成、switch 後 flush，**不含**額外 5 秒光照等待、抽樣與離線驗證。commit 前存檔時間另記在 `region_save`。
- Folia 在 owner callback 讀 NMS 的 5 秒 rolling tick report，保存每個執行過的 region 的 TPS、tick time 平均／中位／最小／最大；tick time 為 ns，表格換成 ms，含 intermediate task execution time。短命 region 少於 20 tick 的記錄保留在原始 JSON，不混入主要平均範圍。這些是重疊視窗，不是假装獨立的 tick 分位數。
- Paper 以 `ServerTickEndEvent` 記完整伺服器 tick duration；全域 TPS 另按其定義說明。region 存檔、GC 與光照可能造成最差 tick，不能歸為單一 section 替換耗時。
- Java process RSS 每 0.5 秒取樣，heap 每約 1 秒取樣；RSS 數值是樣本峰值，非硬體精確瞬時峰值。每輪 RSS 視窗包含該輪的等待、驗證與 bot 抽樣。全流程峰值包括初始化。
- ticket load latency 從派出 async load 到取得 owner callback；含 IO/光照/full promotion/排程，不等於純磁碟讀取。`ticketLoads` 表示派出時沒有 full chunk；所有 chunk 都加 ticket，`ticketPeak` 是 block 階段同時持有的 plugin ticket 數，**不是 chunk 系統全部 ticket/holder 數**。實體生成階段另有最多 36 個目的 chunk 的 async requests。
- 每次存檔後複製 region/entities/poi，離線讀取 **24192 個 section**（包含 20160 個未變 section），按 02 `Codec.encodeSection` 的正規化 blob 做 SHA-256；驗證實體 UUID 次數、完整正規化 NBT、108 隻的有無、額外實體；驗證每個目標 chunk 只有目標講台 POI。完成後 stop 再驗 A。
- bot 抽樣每個 bot 的 section 8/11，共 6 個 section、24576 格，比對伺服器與 client 的材質名称雜湊。此項不驗證方塊 properties、BE 內容或客戶端光照；後兩者分別有離線 BE 檢查與未完成註記。

## 2. 實測數據

量測時間 2026-10-01 03:28 起（Folia 1.21.11、Folia 26.2）與 02:50 起（Paper 26.2），**三個平台全程持有 `.work/bench.lock`**，3 個 bot、1008 chunk／4032 個差異 section／108 隻實體。每個平台各做 A→B→A 兩次（預算 5／10 ms × section 限額 4／16），共 8 次 switch；每次 switch 後存檔、離線比對 24192 個 section。每組只有 1 次量測，沒有重複，差異小於約 5% 不應視為有意義。原始 JSON：`.work/folia-switch/results-*.json`，完整表：`.work/folia-switch/summary.txt`（`tools/summarize.py` 產生）。

### 2.1 switch 結果

「載入 / 已載入」＝派出時需要從磁碟載入的 chunk 數／已在記憶體的 chunk 數。「RSS」為該輪視窗內進程 RSS 樣本峰值（含驗證與 bot 抽樣）。「離線差異」是 section、實體、POI 不一致的總數；「bot 抽樣」是 6 個 section 的方塊雜湊一致數。

| 平台 | 方向 | 預算 ms / 限額 | 秒 | section/s | 載入 / 已載入 | ticket 峰值 | load p95 ms | 單 section p50/p95/max ms | 錯誤 | RSS 峰值 MiB | 離線差異 | bot 抽樣 |
|---|---|---|---:|---:|---|---:|---:|---|---:|---:|---:|---|
| folia-1.21.11 | →B | 5 / 4 | 18.1 | 223 | 513 / 495 | 22 | 83 | 0.46 / 0.76 / 21.3 | 0 | 1652 | 0 | 6/6 |
| folia-1.21.11 | →A | 5 / 4 | 18.0 | 225 | 496 / 512 | 22 | 84 | 0.45 / 0.70 / 23.3 | 0 | 1653 | 0 | 6/6 |
| folia-1.21.11 | →B | 10 / 4 | 17.8 | 227 | 518 / 490 | 21 | 84 | 0.45 / 0.70 / 19.7 | 0 | 1654 | 0 | 6/6 |
| folia-1.21.11 | →A | 10 / 4 | 17.8 | 227 | 507 / 501 | 21 | 85 | 0.44 / 0.73 / 18.2 | 0 | 1654 | 0 | 6/6 |
| folia-1.21.11 | →B | 5 / 16 | 7.2 | 560 | 575 / 433 | 17 | 132 | 0.40 / 0.67 / 15.3 | 0 | 1655 | 0 | 6/6 |
| folia-1.21.11 | →A | 5 / 16 | 6.8 | 593 | 604 / 404 | 15 | 132 | 0.38 / 0.65 / 12.3 | 0 | 1655 | 0 | 6/6 |
| folia-1.21.11 | →B | 10 / 16 | 5.6 | 726 | 630 / 378 | 8 | 130 | 0.41 / 0.71 / 15.9 | 0 | 1655 | 0 | 6/6 |
| folia-1.21.11 | →A | 10 / 16 | 5.6 | 720 | 638 / 370 | 11 | 131 | 0.40 / 0.63 / 21.4 | 0 | 1655 | 0 | 6/6 |
| folia-26.2 | →B | 5 / 4 | 18.0 | 225 | 529 / 479 | 21 | 97 | 0.42 / 0.63 / 15.4 | 0 | 1134 | 0 | 6/6 |
| folia-26.2 | →A | 5 / 4 | 17.7 | 227 | 465 / 543 | 21 | 97 | 0.40 / 0.72 / 26.2 | 0 | 1135 | 0 | 6/6 |
| folia-26.2 | →B | 10 / 4 | 17.7 | 228 | 533 / 475 | 21 | 97 | 0.39 / 0.61 / 15.1 | 0 | 1135 | 0 | 6/6 |
| folia-26.2 | →A | 10 / 4 | 17.7 | 228 | 537 / 471 | 21 | 97 | 0.38 / 0.58 / 14.2 | 0 | 1135 | 0 | 6/6 |
| folia-26.2 | →B | 5 / 16 | 6.4 | 635 | 618 / 390 | 13 | 102 | 0.36 / 0.59 / 13.3 | 0 | 1136 | 0 | 6/6 |
| folia-26.2 | →A | 5 / 16 | 6.2 | 655 | 627 / 381 | 17 | 103 | 0.34 / 0.57 / 17.1 | 0 | 1136 | 0 | 6/6 |
| folia-26.2 | →B | 10 / 16 | 5.6 | 714 | 641 / 367 | 12 | 103 | 0.36 / 0.62 / 12.8 | 0 | 1136 | 0 | 6/6 |
| folia-26.2 | →A | 10 / 16 | 5.7 | 714 | 641 / 367 | 12 | 104 | 0.36 / 0.64 / 11.4 | 0 | 1136 | 0 | 6/6 |
| paper-26.2 | →B | 5 / 4 | 51.3 | 79 | 588 / 420 | 23 | 51 | 0.47 / 0.72 / 4.2 | 0 | 1465 | 0 | 6/6 |
| paper-26.2 | →A | 5 / 4 | 51.3 | 79 | 575 / 433 | 24 | 51 | 0.45 / 0.65 / 21.3 | 0 | 1469 | 0 | 6/6 |
| paper-26.2 | →B | 10 / 4 | 51.2 | 79 | 588 / 420 | 23 | 51 | 0.45 / 0.74 / 21.4 | 0 | 1469 | 0 | 6/6 |
| paper-26.2 | →A | 10 / 4 | 51.2 | 79 | 588 / 420 | 23 | 51 | 0.44 / 0.63 / 18.9 | 0 | 1469 | 0 | 6/6 |
| paper-26.2 | →B | 5 / 16 | 15.8 | 255 | 159 / 849 | 23 | 54 | 0.38 / 0.58 / 2.5 | 0 | 1470 | 0 | 6/6 |
| paper-26.2 | →A | 5 / 16 | 15.1 | 266 | 185 / 823 | 23 | 54 | 0.37 / 0.57 / 14.6 | 0 | 1470 | 0 | 6/6 |
| paper-26.2 | →B | 10 / 16 | 13.8 | 291 | 163 / 845 | 23 | 56 | 0.42 / 0.89 / 17.3 | 0 | 1470 | 0 | 6/6 |
| paper-26.2 | →A | 10 / 16 | 13.4 | 300 | 163 / 845 | 22 | 56 | 0.37 / 0.61 / 15.3 | 0 | 1470 | 0 | 6/6 |


重點（同一組 B 與 A 的結果幾乎相同，以下取兩者平均）：

| 平台 | 4 section/tick/region | 16 section/tick/region |
|---|---|---|
| Folia 1.21.11（3 個 region） | 18 s，約 225 section/s | 5 ms：7.0 s，約 575 section/s；10 ms：5.6 s，約 723 section/s |
| Folia 26.2（3 個 region） | 17.7 s，約 227 section/s | 5 ms：6.3 s，約 645 section/s；10 ms：5.6 s，約 714 section/s |
| Paper 26.2（1 個執行緒） | 51 s，約 79 section/s | 5 ms：15.5 s，約 260 section/s；10 ms：13.6 s，約 295 section/s |

- 限額 4 時速度由「每 region 每 tick 4 個 section」決定（4×20＝80/s/region），預算 5 與 10 ms 無差別；Folia 因為三個 region 同時運作約為 Paper 的 2.9 倍，與 region 數線性吻合。
- 限額 16 時預算開始起作用：單 section 的 p50 約 0.4 ms、p95 約 0.6–0.9 ms，所以 5 ms 預算大約容納 8–12 個 section，10 ms 約 16 個以上；這時瓶頸另外包含 chunk 載入（Folia 載入 p95 約 100–130 ms，I/O 單執行緒）。
- 單一 section 的最大耗時 12–26 ms 是偶發（JIT／GC／光照 queue），出現在所有組態，且不可搶占，所以「預算」是軟上限。

### 2.2 tick 負載

Folia 取各 region 的 5 秒 rolling report（視窗彼此重疊，只能看範圍，不能當逐 tick 分位數）；Paper 取 `ServerTickEndEvent` 的完整 tick。MSPT 單位 ms。**最差 tick 包含 commit 前／後的存檔與 GC，不能全部歸咎於 section 替換**；Paper 每輪都有 490–750 ms 的最差 tick，Folia 在 16 次中有 3 次（1.21.11 的 10 ms/16 兩次、26.2 的 5 ms/16 →B）出現 420–480 ms，這與 switch 開頭／結尾的 `saveAllChunks`（commit 前存檔 + switch 後存檔）時間吻合，但本實驗沒有把兩者切開驗證。

**folia-1.21.11**（進程 RSS 全程峰值 1655 MiB）

- →B 5ms/4：region 窗平均 MSPT 0.77..5.24; 窗平均 TPS 最低 20.00; 最差 tick 28.5；regions=[0, 4, 8]；heap 峰值 1124 MiB；spawnNotValid=54
- →A 5ms/4：region 窗平均 MSPT 0.62..3.81; 窗平均 TPS 最低 19.99; 最差 tick 24.6；regions=[0, 4, 8]；heap 峰值 1097 MiB；spawnNotValid=54
- →B 10ms/4：region 窗平均 MSPT 0.50..3.69; 窗平均 TPS 最低 19.99; 最差 tick 27.8；regions=[0, 4, 8]；heap 峰值 1107 MiB；spawnNotValid=54
- →A 10ms/4：region 窗平均 MSPT 0.38..3.85; 窗平均 TPS 最低 19.97; 最差 tick 24.2；regions=[0, 4, 8]；heap 峰值 1118 MiB；spawnNotValid=54
- →B 5ms/16：region 窗平均 MSPT 0.30..7.63; 窗平均 TPS 最低 20.00; 最差 tick 23.0；regions=[0, 4, 8]；heap 峰值 1038 MiB；spawnNotValid=54
- →A 5ms/16：region 窗平均 MSPT 0.30..7.27; 窗平均 TPS 最低 20.00; 最差 tick 18.9；regions=[0, 4, 8]；heap 峰值 1069 MiB；spawnNotValid=54
- →B 10ms/16：region 窗平均 MSPT 0.28..13.47; 窗平均 TPS 最低 18.63; 最差 tick 428.1；regions=[0, 4, 8]；heap 峰值 946 MiB；spawnNotValid=54
- →A 10ms/16：region 窗平均 MSPT 0.30..13.20; 窗平均 TPS 最低 18.43; 最差 tick 476.3；regions=[0, 4, 8]；heap 峰值 925 MiB；spawnNotValid=54

**folia-26.2**（進程 RSS 全程峰值 1136 MiB）

- →B 5ms/4：region 窗平均 MSPT 0.79..3.58; 窗平均 TPS 最低 20.00; 最差 tick 35.0；regions=[0, 6, 10]；heap 峰值 725 MiB；spawnNotValid=54
- →A 5ms/4：region 窗平均 MSPT 0.77..5.09; 窗平均 TPS 最低 19.99; 最差 tick 39.8；regions=[0, 6, 10]；heap 峰值 724 MiB；spawnNotValid=54
- →B 10ms/4：region 窗平均 MSPT 0.56..2.95; 窗平均 TPS 最低 20.00; 最差 tick 21.7；regions=[0, 6, 10]；heap 峰值 722 MiB；spawnNotValid=54
- →A 10ms/4：region 窗平均 MSPT 0.41..2.81; 窗平均 TPS 最低 19.97; 最差 tick 16.8；regions=[0, 6, 10]；heap 峰值 734 MiB；spawnNotValid=54
- →B 5ms/16：region 窗平均 MSPT 0.33..10.29; 窗平均 TPS 最低 18.63; 最差 tick 437.2；regions=[0, 6, 10]；heap 峰值 684 MiB；spawnNotValid=54
- →A 5ms/16：region 窗平均 MSPT 0.29..6.58; 窗平均 TPS 最低 20.00; 最差 tick 18.9；regions=[0, 6, 10]；heap 峰值 653 MiB；spawnNotValid=54
- →B 10ms/16：region 窗平均 MSPT 0.33..7.58; 窗平均 TPS 最低 18.57; 最差 tick 42.3；regions=[0, 6, 10]；heap 峰值 692 MiB；spawnNotValid=54
- →A 10ms/16：region 窗平均 MSPT 0.31..7.57; 窗平均 TPS 最低 20.00; 最差 tick 28.3；regions=[0, 6, 10]；heap 峰值 721 MiB；spawnNotValid=54

**paper-26.2**（進程 RSS 全程峰值 1470 MiB）

- →B 5ms/4：全服 tick mean 4.65 / p95 5.27 / p99 8.52 / max 748.7; TPS 19.72；regions=[0]；heap 峰值 1012 MiB；spawnNotValid=None
- →A 5ms/4：全服 tick mean 3.94 / p95 4.22 / p99 5.46 / max 688.8; TPS 19.75；regions=[0]；heap 峰值 1016 MiB；spawnNotValid=None
- →B 10ms/4：全服 tick mean 4.33 / p95 7.59 / p99 12.25 / max 644.8; TPS 19.77；regions=[0]；heap 峰值 1031 MiB；spawnNotValid=None
- →A 10ms/4：全服 tick mean 3.70 / p95 3.88 / p99 4.69 / max 696.1; TPS 19.75；regions=[0]；heap 峰值 1036 MiB；spawnNotValid=None
- →B 5ms/16：全服 tick mean 9.45 / p95 8.16 / p99 23.25 / max 724.8; TPS 19.15；regions=[0]；heap 峰值 981 MiB；spawnNotValid=None
- →A 5ms/16：全服 tick mean 8.93 / p95 8.51 / p99 26.40 / max 491.9; TPS 19.42；regions=[0]；heap 峰值 1027 MiB；spawnNotValid=None
- →B 10ms/16：全服 tick mean 12.04 / p95 13.34 / p99 24.11 / max 689.7; TPS 19.07；regions=[0]；heap 峰值 973 MiB；spawnNotValid=None
- →A 10ms/16：全服 tick mean 10.41 / p95 11.30 / p99 18.93 / max 596.7; TPS 19.19；regions=[0]；heap 峰值 992 MiB；spawnNotValid=None

判讀：
- 限額 4：Folia 各 region 窗平均 MSPT 0.4–5.2，TPS 窗平均 ≥19.97；Paper 全服 tick p95 3.9–7.6 ms，TPS 19.7–19.8。都不影響 20 TPS。
- 限額 16：Folia 窗平均 MSPT 最高 7.6–13.5，TPS 窗平均最低 18.4–18.6（發生在存檔 spike 的視窗）；Paper 全服 tick 平均 9–12 ms、p95 8–13 ms、TPS 19.1–19.4。仍低於 50 ms，但 TPS 已有可見下降，且 10 ms 預算在兩個平台都明顯比 5 ms 更吃 tick。
- 記憶體：進程 RSS 峰值 Folia 1.21.11 約 1.65 GiB、Folia 26.2 約 1.14 GiB、Paper 26.2 約 1.47 GiB（Xmx=3G）；heap 取樣峰值 0.7–1.1 GiB。主要壓力是 3072 個 chunk 加上 A/B 兩份記憶體快照，不是 ticket。
- ticket：同時持有的 plugin ticket 峰值為 8–24（受 in-flight=24 限制），所有 ticket 在 job 結束時為 0（`ticketRemaining=0`）。chunk 載入延遲 Folia p95 約 83–132 ms，Paper 約 51–56 ms；沒有出現載入失敗。這是 view/simulation=2、只有 3 個 bot 的小世界，沒有量到 ticket 對大型生存世界 chunk 系統的長期負擔。
- 執行緒檢查：三個平台的 server log 在整個流程中**沒有** Folia thread-check 例外、沒有警告（排除 root／offline 的固定警告）。

### 2.3 正確性

- 初始 A（fixture + 快照）：24192 個 section、108 隻實體、1008 個 chunk 的 POI 全部一致（Folia 1.21.11 的初次驗證曾因實體 attributes 失敗，見 3.5）。
- 8 次 switch × 3 平台 = 24 次，**每次**存檔後離線比對：section 不一致 0、缺 chunk 0、實體遺失 0、實體 NBT 不一致 0、UUID 重複 0、多餘實體 0、POI 不一致 0。A→B→A 後與原本 A 一致（每個 →A 都以同一份 A manifest 驗證）。
- bot 視角：每次 switch 後 3 個 bot 各抽樣 2 個 section（共 6 個、24576 格）與伺服器一致 6/6，每次 switch 都是（Folia 1.21.11、Folia 26.2、Paper 26.2）。僅比對方塊材質名稱，不含 properties、BE 內容與光照。
- 實體：每次 switch 的 `entitiesRemoved=108`、`entitiesSpawned=108`；跨 region 的 UUID 搬移（B 把同 UUID 移到另一座 island 的 region）在 Folia 上成功，沒有重複。
- 玩家保護：FALL／SUFFOCATION／DROWNING 事件被取消，ENTITY_ATTACK 對照未被取消（事件匯流排測試，三平台一致）。

### 2.4 取消、恢復、關服後

- folia-1.21.11: 取消 head=PARTIAL chunks=30 sections=36 耗時 0.80s ticketRemaining=0；重新套用 A head=A errors=0 17.6s；關服後離線驗 A: {'sectionsChecked': 24192, 'missingChunks': 0, 'sectionMismatches': 0, 'entitiesExpected': 108, 'entitiesFound': 108, 'entityMissing': 0, 'entityMismatches': 0, 'duplicateUUIDs': 0, 'unexpectedEntities': 0, 'poiChunksChecked': 1008, 'poiMismatches': 0}
  protection: [('FALL', True), ('SUFFOCATION', True), ('DROWNING', True), ('ENTITY_ATTACK', False)]
- folia-26.2: 取消 head=PARTIAL chunks=30 sections=33 耗時 0.75s ticketRemaining=0；重新套用 A head=A errors=0 17.6s；關服後離線驗 A: {'sectionsChecked': 24192, 'missingChunks': 0, 'sectionMismatches': 0, 'entitiesExpected': 108, 'entitiesFound': 108, 'entityMissing': 0, 'entityMismatches': 0, 'duplicateUUIDs': 0, 'unexpectedEntities': 0, 'poiChunksChecked': 1008, 'poiMismatches': 0}
  protection: [('FALL', True), ('SUFFOCATION', True), ('DROWNING', True), ('ENTITY_ATTACK', False)]
- paper-26.2: 取消 head=PARTIAL chunks=26 sections=12 耗時 0.80s ticketRemaining=0；重新套用 A head=A errors=0 51.1s；關服後離線驗 A: {'sectionsChecked': 24192, 'missingChunks': 0, 'sectionMismatches': 0, 'entitiesExpected': 108, 'entitiesFound': 108, 'entityMissing': 0, 'entityMismatches': 0, 'duplicateUUIDs': 0, 'unexpectedEntities': 0, 'poiChunksChecked': 1008, 'poiMismatches': 0}
  protection: [('FALL', True), ('SUFFOCATION', True), ('DROWNING', True), ('ENTITY_ATTACK', False)]

- 取消（`wgpoc cancel`，在 `switch B 1 1` 啟動 0.5 s 後）：約 0.75–0.8 s 內停止，已派出的 owner callback 全部完成並釋放 ticket（`ticketRemaining=0`），head 標為 `PARTIAL`。Folia 完成 30 個 chunk／33–36 個 section，Paper 26 個 chunk／12 個 section。
- 取消後重新套用 A：成功（errors=0），Folia 17.6 s、Paper 51.1 s（與正常 A 的耗時相同，因為每次都重新比對全部 chunk）。
- 關服後（`stop` 後）直接讀磁碟上的 world 比對 A：三個平台皆 0 差異。
- 未測：取消**之後**不重新套用而直接重啟；關服發生在 switch 進行中。

### 2.5 磁碟

`.work/folia-switch/` 約 9 MB（結果、evidence、log）；實驗目錄 build 約 9 MB。伺服器副本與世界副本於每輪結束時刪除，執行期單一平台最大約 1 GB。遠低於 6 GB 上限。

## 3. 開發過程遇到的失敗

### 3.1 async callback 與 full chunk 發布時序

第一次初始化在 owner callback 直接用 `getChunkNow`，部分剛載入的 chunk 回傳 null；future 完成不保證這個查詢在該瞬間看得到 full table。改用 async callback 傳回的 `CraftChunk#getHandle(FULL)`，仍在 owner thread 取得 handle，後續初始化成功。此失敗是原型自己的時序假設，沒有被當成 Folia 性能數據。原始證據：`failures/01-callback/`。

### 3.2 Bukkit EntitySnapshot 不含 UUID/位置

第二輪初始化的 24192 個 section 與 1008 個 POI 全部一致，但 108 個 snapshot 在 UUID manifest 中縮成 3 個 `nouuid-*`；切換生成階段取 UUID 時失敗。原因是 CraftEntitySnapshot 以省略 UUID/位置的旗標存 NBT，適合複製實體而不是版本控制。改用 `Entity.save(TagValueOutput)` 的完整 NBT，不能只靠 Bukkit snapshot 外觀認為資料完整。原始證據：`failures/02-entity-snapshot/`。

### 3.3 26.2 的 state factory 改名

靜態相容檢查抓到 `CraftBlockData.fromData(BlockState)` 在 26.2 改名為 `createData(BlockState)`。使用與 03 light method 相同的執行期選擇模式修正；08 實際使用的 76 個 NMS/CraftBukkit 引用對 Folia 26.2 檢查為 **0 問題**，結果在 `binary-compat-26.2.txt`。

### 3.4 valid POI 不會自動 refresh

03 只有呼叫 `checkConsistencyWithBlocks`；檢查 NMS 發現既有 PoiSection 的 `refresh` 在 `isValid=true` 時直接返回，因此「呼叫成功」不保證移動講台後 POI 正確。08 在每個變更方塊上比較舊／新 PoiTypes，明確 remove/add 後再 checkConsistency。離線驗證會抓過期／缺漏／多餘 POI，無法只靠無例外 log 判斷。

### 3.5 NoAI 牛的 movement_speed attribute 惰性出現

第一次完整測試在**初始 A 驗證**就失敗：fixture 後 24192 section、POI 全部一致，但 108 隻中有 6 隻（NoAI 牛，Folia 1.21.11）的 NBT 比快照多一個 `attributes: movement_speed`。原因是伺服器惰性建立 attribute instance（快照時還沒有、存檔時才有），不是內容被改動。處理：正規化比對時略過沒有 modifiers 的 `movement_speed`，其餘 attribute 仍比對。這使「實體 NBT 一致」的判定略為放寬，正式設計應在 UUID 層級比對、對 attribute 也建立類似的白名單。證據：該次失敗的 console／結果被後續重跑覆蓋，只留下報告記載與 evidence 內的 manifest；修正前後用同一份 manifest 對保留世界離線重驗，由 6 個差異變 0。

### 3.6 Folia 上非 ticking chunk 的實體 `isValid()==false`

第二、三次執行時，Folia 1.21.11 與 26.2 都在 A→B 的實體生成階段失敗（Paper 沒有）：每個平台恰好 18 個盔甲座 `world.addEntity` 後 `isValid()==false`，原型以此丟例外，導致同 chunk 的其他實體也沒生成（`run-all-attempt2-spawn-invalid.log`、`run-all-attempt3-paper-ok-folia-spawn-invalid.log`）。先懷疑是 chunk 重新載入後 entity section 尚未就緒，加入 `isEntitiesLoaded()` 等待（`entityReadyWaits`=0，表示不是原因）；失敗仍然完全相同。最後改為不以 `isValid` 判失敗，記錄診斷，交給存檔後的離線比對判定。結果：每次 switch 都有 54 個實體 `isValid()==false`（108 隻的一半，位於只有 plugin ticket 的非 ticking chunk），但離線驗證 108 隻全部存在、UUID 無重複、NBT 一致。**結論：Folia 上對非 ticking chunk 呼叫 `addEntity` 後，`isValid()` 不是成功判準**；根因（ticking 狀態與 Folia 的 `isChunkLoaded` 語意）沒有進一步用原始碼確認，屬推測。由於 Paper 執行（02:50 起）使用的是修改前的 jar（Paper 沒有觸發此問題），Paper 的 `spawnNotValid` 欄位為空。

### 3.7 並行排程與中斷

原 Codex 任務在 01:47 UTC 因用量上限中斷，當時一個測試排在全域鎖後面；其初始 A 驗證因 3.5 失敗（不是 switch 失敗）。之後由接手者修正、重跑。為了不浪費鎖的排隊時間，`run.py --server all` 現在遇到單一平台失敗會繼續下一個平台。

## 4. 對正式設計的建議

### 4.1 預算與限額預設值（依本實驗數據）

- **預設：每 region 每 tick 8 個 section、時間預算 5 ms、全域 in-flight 16–24 chunk。**依據：限額 16 + 5 ms 在 Folia 達約 600 section/s，且各 region 窗平均 MSPT 峰值約 7–10 ms、TPS 窗平均大多 20.0；10 ms／16 在 Folia 1.21.11 的 TPS 窗平均最低 18.4，Paper 全服 tick 平均 10–12 ms，TPS 19.1–19.2，不建議作預設。限額 4 對 tick 幾乎無影響（MSPT <5.2）但在 Paper 上 4032 個 section 要 51 s。
- 「保守模式」（有玩家在範圍內、或 Paper 單執行緒）：4 section／5 ms；「快速模式」（無人在範圍、維護視窗）：16 section／10 ms。本實驗沒有量測玩家同時活動的真實 MSPT，這些數字是起點，需在正式世界重測。
- 預算只能當軟上限：單一 section 最大 12–26 ms，不可搶占。若要硬上限，必須切小單位（例如半個 section 的 palette 寫入）或接受偶發超時。
- 存檔與 GC 造成的最差 tick（Folia 420–480 ms、Paper 490–750 ms）才是對玩家體感最大的風險，不是 section 替換；commit 前後的 flush 應避開玩家密集時間，或分批、分 region。

### 4.2 Folia 與 Paper 的差異

- 吞吐：相同的每 region 限額下，Folia 約為 Paper 的 2.9 倍（3 個 region 並行）；若 switch 範圍落在單一 region，Folia 沒有優勢。16/5ms 時 Folia 約 575–650 section/s，Paper 約 260。
- 排程：Folia 必須每次在 owner callback 重新取 region ID；Paper 只有一個預算池。實體跨 region 搬移要「來源 region 移除 → barrier → 目標 region 生成」。沒有 save-all，要對所有實際 region 分別呼叫 `saveAllChunks`。
- 陷阱：`getChunkNow` 在 async 完成後不保證可見（3.1）；非 ticking chunk 的 `isValid()`（3.6）；Bukkit EntitySnapshot 不含 UUID（3.2）；26.2 的 NMS／CraftBukkit 改名（3.3）。這些在 Paper 上多半不出現，只在 Folia 才有。
- 載入：Folia 載入 p95 約 100–130 ms（chunk I/O 單執行緒），Paper 約 55 ms；兩者在 in-flight 24 下沒有失敗。

### 4.3 進度回報與中斷

以下結構性建議已有原型依據：

- owner 排程與 coordinator 必須分離。以 section 作最小不可搶占工作單位、每 region/tick 共用計數；合併／分裂後重新路由。Paper 將所有 worker 視為同一份預算，避免把多個 lane 當成多個 region。
- 全域 in-flight、每 region 的 section/time 限額、光照 queue/backpressure 應是三種控制；已載入率與預生成狀態會影響 ticket latency。不可一次 ticket 數千 chunk。
- POI 要明確更新，不能只保留舊 POI 檔或相信有效 section 的 checkConsistency。仍需真村民的職業／記憶／尋路驗證。
- 實體儲存必須保留 UUID/Pos/Passengers/關係。UUID 索引需覆蓋範圍外移動實體，透過 EntityScheduler 在實體當下所在 region 移除，再在目標 location scheduler 生成；退休 callback 與異步 load 失敗都要計入 barrier。此原型只處理受控範圍內的三類靜態／NoAI 實體，沒有完整全維度 UUID 索引。
- 存檔分兩步：各 owner 生成／提交資料，background coordinator 等 IO durability barrier；不能把長時間同步 flush 塞進「5 ms 內」的期待。Folia 沒有單一 save-all／世界主執行緒可依赖。
- 進度包含 loading、blocks、entity removal、entity spawn、lighting、saving 六階段，分別顯示完成量、持 ticket/等待數、吞吐與估計剩餘時間；HEAD 只能在存檔／驗證 barrier 後移動。bossbar 更新排到各玩家的 EntityScheduler，global 只彙整計數。
- 取消是停止派新工作並等待已派 owner callbacks 清理 ticket，不是立即反向全部改回。原型取消後設 `PARTIAL`、可重套 A；正式版持久化 transaction journal（from/to、每 chunk/section 完成位元、UUID remove/spawn、寫入版本），清楚提供「繼續／還原」。
- 關服前先停止新派發，在可用的 owner callbacks 釋放 ticket；持久化 journal，不標記完成。不應在 onDisable/global 中讀寫 live chunk 或做跨 region flush，也不應在關服執行緒 `.join()` 等已停止的 region scheduler。啟動時保留編輯鎖，從 journal 跑可重入恢復，再移動 HEAD。

## 5. 未完成與適用限制

- 客戶端光照、光照 queue 完全排空、伺服器光照逐 nibble 目標比對未做；只提交 checkBlock 並等待 5 秒。不能宣稱光照完全正確。
- 真村民尋路與 POI ticket/Brain 行為、自然移動實體、跨範圍 UUID 去重、Passengers/拴繩、自然生成／消失、scheduled block/fluid ticks 未驗證。
- 玩家保護是 owner thread 的真 Bukkit event bus 測試，包含三種取消與 ENTITY_ATTACK 對照；沒有重跑真實落下、窒息與溺水的物理場景。
- physics/放置/破壞取消不足以冻结完整生存世界；容器互動、活塞／液體／紅石／其它插件寫入仍需正式 world edit lock/協調機制。NoAI 與關閉 random ticks 是本次正確性條件。
- 中途關服後重啟恢復未測（取消後的重新套用 A 與正常關服後磁碟一致已測）。snapshot 在記憶體；manifest 是雜湊與實體 NBT 證據，不是可完整重啟恢復的 section object store。onDisable 僅記 partial，不具備 transaction recovery。
- 未測自然地形、跨維度、3000 chunk、10 bot、沒有預生成的 chunk、更多 region threads、多機器／長時間重複與版本升級。Folia 26.2 既有 build 的結果不能代表未來版本。

## 6. 產物與清理

- 程式與重跑腳本：`experiments/08-folia-switch/`（`tools/reproduce.sh`、`tools/run.py`、`tools/summarize.py`）。結果與證據：`.work/folia-switch/`（約 9 MB）。
- 實驗結束後沒有遺留 server、bot 或 Java 程序；伺服器副本與世界副本已刪除。測試用 port 25651–25653 已釋放。
- 本機同時有 06、07 的重負載；本實驗所有量測都在 `.work/bench.lock` 內，量測期間其他任務不在跑重負載。等待鎖的時間很長（約 20–40 分鐘），不影響數據。
- 沒有 git commit／push、沒有外部部署；03 與 baseline 未修改。
