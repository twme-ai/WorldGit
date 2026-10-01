# 08 — 架構與各端分工

## 1. 模組切分

```
worldgit/
├─ core/            純 JVM 函式庫，不依賴任何 MC 程式碼
│   ├─ anvil/       region(.mca) 與 NBT 讀寫（離線用）
│   ├─ model/       Section / ChunkSnapshot / EntitySnapshot 的中性表示
│   ├─ normalize/   正規化規則、忽略表、canonical 編碼
│   ├─ store/       ObjectStore / RefStore 介面 + JGit 實作
│   ├─ diff/        tree diff、section diff、方塊級 diff
│   ├─ merge/       三方合併、衝突分群、合併狀態（MERGE_HEAD 等）
│   └─ config/      .wgignore 解析（版本控制內）、worldgit.yml（本機設定）
├─ platform-api/    「活的世界」抽象：讀快照、套用變更、鎖定、通知玩家
├─ protocol/        插件/模組 ↔ 客戶端模組的網路封包定義（diff 預覽、衝突資訊）
├─ i18n/            多語言訊息表：YAML 語言檔（MiniMessage 字串）、語言退回、管理者覆寫（決定 #22）
├─ paper/           Paper 插件：實作 platform-api + 指令/GUI/預覽
│   ├─ common/      與版本無關的部分（只用 Bukkit/Paper API）
│   ├─ v1_21_11/    需要伺服器內部程式碼（NMS）的部分，每個 MC 版本一個薄轉接層
│   └─ v26_2/
├─ fabric/          Fabric 模組：伺服端實作 platform-api；客戶端鬼影渲染、合併工具 UI
│   └─ （以多版本建置工具同時產出 1.21.11 與 26.2 兩個 jar）
├─ cli/             wgit：離線世界操作、與 Hub 溝通、CI 腳本用
└─ hub/             網頁端（後端 + 前端 3D 檢視器）
```

關鍵介面（草案，只表達責任，不是最終 API）：

```java
interface LiveWorld {                       // 由 paper / fabric 實作；cli 用離線實作
    Set<ChunkPos> dirtyChunks();
    CompletableFuture<ChunkSnapshot> snapshot(ChunkPos pos);   // 在正確執行緒上複製
    CompletableFuture<Void> apply(ChunkPos pos, ChunkPatch patch);
    AutoCloseable lockEdits(Collection<ChunkPos> area, String reason);
    int dataVersion();
}
```

正式依賴方向是 core 定義 `SnapshotSource`，platform-api 的 `LiveWorld` 延伸它；core 不反向依賴 platform-api，避免模組循環。同一套 capture/commit 邏輯在插件、模組、CLI 都能跑；CLI 的 `LiveWorld` 就是直接讀寫 region 檔的離線實作，也是最容易寫單元測試的那個。

## 2. 各端的角色

> **已決定（2026-09-30）：插件、模組、CLI、網頁四端同等優先**，主要使用者是多人伺服器。

| 端 | 主要使用者 | 獨有能力 | 限制 |
|---|---|---|---|
| **Paper / Folia 插件** | 多人伺服器 | 多人作者歸屬、登出自動 commit、權限、worktree 世界管理（Folia 除外） | 預覽只能靠假方塊封包 / display entity；FAWE 等會繞過事件（需靠時間戳 + 雜湊補足）；Folia 見 §7 |
| **Fabric 模組** | 兩種角色：① 裝在 **Paper 伺服器玩家的客戶端**，作為插件的「顯示端」；② 單人 / Fabric 伺服器上的完整實作 | 客戶端半透明鬼影渲染、diff 疊圖、合併工具 UI；伺服端角色下有完整的 dirty 追蹤（mixin） | 需要跟 MC 版本頻繁更新 |
| **CLI** | 管理員、進階玩家、CI | 對關閉中的世界操作、批次處理、與 Hub 同步、腳本 | 不能對運行中的世界操作（除非透過插件的 RPC） |
| **Hub** | 所有人 | 瀏覽、PR、3D diff、下載 | 不能在網頁上「玩」世界 |

## 3. 技術選型建議

| 項目 | 建議 | 理由 / 替代方案 |
|---|---|---|
| core 語言 | **Java 21**（core、插件、模組、CLI 維持 Java 21 相容，因為 1.21.11 伺服器跑在 Java 21 上） | 插件和模組都是 JVM，零橋接成本；替代：Rust core + JNI，效能好但兩套語言、跨平台打包麻煩 |
| NBT/Anvil | 自寫精簡讀寫器（離線）；線上則直接從伺服器記憶體結構取資料 | 避免依賴大型函式庫；可參考現有開源 NBT 函式庫 |
| 儲存 | JGit | 見 [03](03-storage-backend.md) |
| 壓縮 | zstd（section 編碼層）+ git 自身的 zlib | |
| CLI 發佈 | Phase 1 採 Java 21 fat jar + `wgit` 腳本；native-image／jlink 延後 | 無需另外編譯各 OS 的原生執行檔 |
| Hub 後端 | **Java 25 + Spring Boot**（已決定，2026-09-30：為了嵌入需要 Java 25 的 BlueMap core），直接重用 core；git 協定用 JGit 的 `GitServlet` | 需要在伺服器端做合併與世界 zip 組裝；Spring 的 OAuth、權限、速率限制對公開服務現成可用；虛擬執行緒處理大量 git 連線。替代：Javalin（較輕，但 OAuth/權限要自己組） |
| Hub 前端 | **全新撰寫**（不沿用 BlockForge）：TypeScript + Vite，3D 用 Three.js 加上自寫的 chunk 網格生成器 | 見 [10](10-web-frontend.md) |
| 遊戲內預覽 | 插件：display entity / 假方塊封包（可參考既有的 VirtualEntities、WorldEditDisplay 經驗）；模組：客戶端渲染 | |
| 目標版本 | **首發：Paper 與 Fabric 的 26.2 與 1.21.11**；長期目標是支援範圍越廣越好 | 已決定（2026-09-30），見 §5 |

## 4. repo 在磁碟上的位置

```
<server>/
├─ world/                   ← working tree（MC 自己管）
├─ world_nether/ …
└─ .worldgit/
    └─ world/
        ├─ worldgit.yml        ← 本機設定（不進版本控制）
        ├─ minecraft.overworld/ ← 每個維度一個 bare repo + index（chunk 時間戳/雜湊快取）
        ├─ minecraft.the_nether/
        └─ minecraft.the_end/   # .wgignore 在各 repo 的 tree 裡，跟世界內容一起被版本控制
```

每個維度是獨立 repo（已決定，2026-10-01，見 [02](02-data-model.md) §2.1）。core 需要一層「世界 = 一組維度 repo」的管理：對每個 repo 做同名操作、寫入共用的 `WorldGit-Snapshot` trailer、處理部分失敗。

放在世界資料夾外面：MC 不會碰到、傳統的世界備份不會把歷史也打包進去、刪世界不會連歷史一起刪。

## 5. 支援版本與多版本策略（已決定，2026-09-30）

首發目標：

| 平台 | 1.21.11 | 26.2 |
|---|---|---|
| Paper 插件 | ✓ | ✓ |
| Folia（同一個插件 jar） | ✓ | ✓（視 Folia 對 26.2 的釋出狀態） |
| Fabric 模組（伺服端 + 客戶端） | ✓ | ✓ |

長期目標是盡可能擴大支援範圍（更舊的 1.20.x、之後的 26.x 等），所以架構從一開始就要能承受多版本：

- **core 不碰任何 MC 類別**。版本差異只出現在兩個地方：
  1. 離線讀寫 region 時的 chunk NBT 結構差異（由 `DataVersion` 分派到不同的轉換器）
  2. 線上存取伺服器內部結構的轉接層（Paper 的 `v1_21_11/`、`v26_2/`；Fabric 的多版本建置）
- **Paper 端盡量只用公開 API**（chunk 快照、方塊資料、實體序列化夠用的部分），只有效能關鍵或 API 缺少的操作（例如直接替換 section、實體完整 NBT 讀寫）才放進版本轉接層。
- **1.21.11 → 26.2 的存檔差異（Phase 0 盤點結果，詳見 `experiments/00-env/REPORT.md`）**：
  - 目錄結構大改：1.21.11 是三個世界資料夾（`world`、`world_nether/DIM-1`、`world_the_end/DIM1`）；26.2 是單一 `world/dimensions/minecraft/<維度>/{region,entities,poi}`，`playerdata` 改為 `players/data`。→ 需要以維度 id 為鍵的目錄轉接層。
  - `level.dat` 的 gamerule、天氣、時間等搬到 `data/minecraft/*.dat`。→ `world-meta` 需要轉接層。
  - **chunk / section 的 NBT 結構完全相同**，只有 `DataVersion` 不同（4671 → 4903）。→ section 解析與正規化兩版可共用。
  - 實體多了/少了少數欄位；空的 `minecraft:bed` block entity 在 26.2 消失。
- **跨版本的 repo**：同一個 repo 可能先後被 1.21.11 與 26.2 的伺服器使用。commit 記錄 `DataVersion`；新版本讀舊 commit 時透過遊戲自己的 DataFixer 升級（在伺服器/模組端做，不在 core 裡重寫）；**舊版本不能 checkout 新版本的 commit**（明確報錯）。
- **網頁端的版本**：Hub 的 3D 檢視需要對應版本的方塊模型與材質，依 commit 的 `DataVersion` 載入對應資源（見 [10](10-web-frontend.md) §3）。

## 6. 插件 ↔ 客戶端模組的連線

主要使用情境是「多人 Paper 伺服器 + 玩家自選安裝的 Fabric 客戶端模組」：

```
Paper 伺服器（WorldGit 插件）                     玩家客戶端（WorldGit Fabric 模組，選用）
  │ 玩家加入時透過 plugin channel 握手  ─────────▶  回報協定版本、能力（可渲染鬼影…）
  │ /wg diff、/wg preview、合併衝突
  │   → 傳送精簡的差異資料  ────────────────────▶  客戶端渲染半透明鬼影、上色外框、
  │     （範圍 + 方塊調色盤 + 變動格）               衝突區域清單 UI、ours/theirs 切換預覽
  │ ◀──────────────────────────  玩家在 UI 上的選擇（解決衝突、跳到下一個衝突）
```

- **沒裝模組的玩家**照樣可用：插件退回 display entity / 假方塊封包的顯示方式。
- 協定定義放在獨立的 `protocol/` 模組，插件、模組共用同一份，並帶協定版本號，讓 1.21.11 / 26.2 的插件與模組可以互通。
- 客戶端鬼影預覽**只在客戶端顯示**，不會改變伺服器上的世界，所以預覽另一個分支不需要真的切換。

### Phase 0 驗證結果（2026-10-01，`experiments/05-fabric-poc/`）

同一個插件 jar 在 Paper 1.21.11 與 26.2 上，跟兩版 Fabric 模組在 Xvfb 的真正客戶端裡完成握手與鬼影顯示，有截圖為證。
- **握手**：`worldgit:hello` 帶協定版本、nonce、能力清單。玩家剛加入時 channel 可能還沒註冊，要重試；約 6 秒沒回覆就判定沒裝模組（實測 mineflayer bot 正確判定）。
- **diff 訊息**：`worldgit:diff` 以 section 為單位，每段帶自己的 block state 調色盤，每格約 3 bytes。每包上限 28 KB（伺服器→客戶端本來可到 1 MiB，但客戶端→伺服器只有 32 KB，統一用保守值），客戶端收齊整批才一次套用。10 萬格約 320 KB、12 包。
- **渲染**：移除格用原版方塊模型畫紅色半透明鬼影，形狀正確（樓梯、半磚、柵欄、玻璃片）。新增、修改、衝突用三種不同的外框。預設不穿牆，遵守深度遮擋。幾何只在新批次到達時建一次，每幀只畫 4 個固定 buffer。
- **兩版差異大**：26.2 的渲染 API 大改（反向深度、vertex binding、`drawIndexed` 參數、payload 註冊名稱等），26.2 用不混淆的 Loom（沒有 `modImplementation`）。做法是每版一個小 `Adapter`，其餘共用。**編譯與載入成功不代表畫面正確**，26.2 的鬼影第一次就因深度方向錯誤而消失，所以 CI 要有真正客戶端的截圖回歸測試。
- **效能**：10 萬格的 vertex buffer 約 99 MB，軟體渲染下每幀約 0.5 秒。正式版必須依 section 分塊、做視錐裁切與 LOD，數萬格以上改畫區域包圍盒（[06](06-diff-merge.md) §1.1）。
- **Phase 1 收尾已做**：沒裝模組時的 display entity 顯示（`paper/` 的 `DisplayFallback`，見 [11](11-phase1-progress.md)「Phase 1 端到端驗收」）。
- **尚未做**：準星「舊 → 新」提示、衝突選擇 UI、色盲色票設定、Sodium／Iris 相容、資源包重載、硬體 GPU 量測。

## 7. Folia 首發支援（已決定，2026-09-30）

Folia 把世界切成多個 region，各自在不同執行緒上 tick，沒有「主執行緒」。插件用**同一個 jar** 同時支援 Paper 與 Folia（`folia-supported: true`），所以從第一天起就要以 Folia 的執行緒模型來寫，Paper 只是「只有一個 region」的特例：

| 操作 | 做法 |
|---|---|
| commit 時取 chunk 快照 | 依 chunk 所在 region 分組，用 `RegionScheduler` 在各自的執行緒上複製 section，全部完成後在背景執行緒正規化 |
| switch / restore 套用變更 | 同樣依 region 分組、分批排程；進度以原子計數彙整到 bossbar |
| 玩家相關（傳送、訊息、給保護效果） | 用 `EntityScheduler` 在該玩家的執行緒上執行 |
| repo 狀態（HEAD、合併狀態、鎖） | 不屬於任何 region，放在 core 內自己的單執行緒 executor，所有修改都經過它，避免競爭 |
| 事件監聽（dirty 追蹤） | 事件會在不同 region 執行緒上同時觸發，dirty set 用並行安全的資料結構 |

Folia 上的限制：
- **不能在執行中建立或載入新世界**，所以 worktree 世界在 Folia 上不可用，改用客戶端鬼影預覽（模組）或 display entity 預覽代替。
- 跨 region 的操作無法在同一 tick 內完成，commit 快照的「跨 chunk 一致性」會比 Paper 更弱（已在 [04](04-commit-and-status.md) §3 接受）。
- Folia 的版本通常晚於 Paper 釋出，26.2 的 Folia 支援時程需要確認。

Phase 0 實測（2026-09-30，`experiments/03-paper-poc/` §7）：Folia 1.21.11（build 14）與 26.2（build 7，**BETA**）上偵測、section 替換、WorldEdit 都沒有執行緒錯誤。要注意的差異：
- Folia **沒有 `/save-all`**，`World#save()` 也不能用。commit 前的強制存檔要在每個 region 自己的執行緒上呼叫 `ChunkHolderManager.saveAllChunks(...)`，按 region 分組。
- `LevelChunk.isUnsaved()` 在非 region 執行緒會 NPE，改讀原始欄位（見 [04](04-commit-and-status.md) §2）。
- 實體傳送要用 `teleportAsync`。
- FAWE 沒有 Folia 版，Folia 上只能搭配純 WorldEdit（7.4.x 有 Folia 支援；7.4.5 需要 Java 25，1.21.11 要用 7.4.2）。

### NMS 轉接層（Phase 0 發現）

PoC 用一個對 1.21.11 Mojang 名稱 server jar 編譯的 jar 同時跑兩版，約 98 個 NMS 引用中有 3 處在 26.2 執行期出錯，例如 `BlockState.getLightBlock()` 改名為 `getLightDampening()`，`ChunkPos.x` 改成 private。正式專案：
- 照 §1 的規劃，每個 MC 版本一個 adapter 模組，用 paperweight-userdev 各自編譯，讓差異在編譯期出現。
- CI 加上「對另一版 server jar 的二進位相容檢查」（PoC 的 `tools/check_binary_compat.py`）。
- Java 21 bytecode 在 26.2（Java 25）上可以直接跑。

## Phase 1 API 與磁碟版面補充（2026-10-01）

正式 API 摘要在 [core README](../core/README.md) 與 [進度報告](11-phase1-progress.md)。`DimensionRepository` 是單維度入口；`WorldRepositories` 協調一組維度，使用共用 snapshot trailer，保留並列出每維度成功/無變動/失敗。repo HEAD 採條件更新，operation lock 涵蓋 index、HEAD 與 pack 維護。

自訂維度的 repo 名仍以 namespace 與 path 組成；兩部分內的 `.` 編成 `%2E`，path 的 `/` 編成 `%2F`，避免路徑穿越及不同 id 的名稱碰撞。預設三維度仍是 `minecraft.overworld`、`minecraft.the_nether`、`minecraft.the_end`。

protocol 正式版為 v2；hello 帶版本、nonce、capabilities 與色票；diff 帶 section palette 的前後 state；status 帶 section/chunk 包圍盒與統計；clear 取消 preview。≤ 28,000 bytes 分包，收齊才發布，批次最多 100,000 entries/8 MiB。與 Phase 0 v1 不相容，平台 handshake 需檢查版本。詳見 [protocol](../protocol/README.md)。

CLI 發佈先採 Java 21 fat jar 與 `wgit` 腳本，native-image 延後。Phase 2 的 `apply/lockEdits/flush/notifyPlayers` 已在 LiveWorld 預留，離線 apply 尚未開放。

### Phase 1 Fabric 實作補充（2026-10-01）

正式 `fabric/` 分成純 Java 21 的 `logic`、共用 Minecraft 原始碼 `shared`，以及 `mc1_21_11`／`mc26_2` 兩個薄轉接層；各自產出 Java 21／25 jar。沿用 PoC 的 Loom 1.17.21、Loader 0.19.5、Fabric API 0.141.6／0.161.0。Adventure Fabric 6.8.0／7.1.1 已在兩版實機驗證，伺服器及 `/wgc` 都透過共用 i18n YAML 與 MiniMessage，原版 Component 由 Adventure 平台轉換；開關、握手狀態與色票名稱也可翻譯。

runtime 在世界載入前建立；`LevelChunk.markUnsaved` mixin 將變動保留在 generation tracker，避免單人世界的原版存檔清除旗標後漏掉 status。原版旗標、載入／玩家事件、實體 chunk、磁碟 index 仍一起參與候選篩選；最後以正規化雜湊決定差異。快照在伺服器執行緒複製，背景 repo executor 計算；關閉時透過自有 task 佇列在伺服器執行緒處理 flush／複製，避免原版 execute 在停止期間於呼叫者執行緒執行。

正式渲染改為 section 分塊、視錐／距離裁切、每幀明細建置配額、遠處包圍盒與 GPU 網格釋放。色盲設定切換會重建明細。兩版 Xvfb 真客戶端已驗證 1 section 的真實世界 diff、強制存檔後 dirty 保留、3,072 格合成預覽的遠／近 LOD、clear 和繁中顯示；這不是 100,000 格的效能量測。1.21.11 client gametest 需停用 Fabric 測試框架的 NetworkSynchronizer 才能載入整合世界，26.2 不需要；此開關只在測試 run 設定。

完整驗收、Paper 插件副本的時間戳及限制見 [Phase 1 進度](11-phase1-progress.md)／[Fabric README](../fabric/README.md)。資源包重載、Sodium／Iris、硬體 GPU、準星前後 state UI、實體／biome 模型及 Phase 2 操作仍未提供。

## Phase 2 apply 介面（2026-10-01）

core 的 `apply.ApplyPlan`／`ApplyPlanner` 不依賴 Minecraft，提供 section mask、biome samples、UUID entity 操作、world-meta、serialization／only／batches。`WorldOperations` 僅是離線世界組服務；Paper／Fabric 在自己的 repo executor 計畫，再交給 `platform.ApplyScheduler`，由平台實作 owner 排程。

`LiveWorld.apply(ApplyPlan, ApplyBudget)` 的 future 表示該批已套用且 ticket 已釋放／失敗也清理；實體 remove 必須全維度查 UUID（含 passenger），put 依 Pos，不直接改線上 `.mca`。`nextApplyTick` 在當下真正 owner 的下一 tick 排程。預設 8 section／5 ms／24 chunk，有玩家模式 4／5 ms／16 chunk；時間是不可搶占 section 的軟預算，平台所有 lane 必須共享實際 region／tick 的計數，不把 32×32 格網當 Folia region。

共用 ApplyScheduler 是保守的單維度、單批 coordinator，按 section 限額分批並維持 blocks→全部 entity remove→spawn→metadata 的 barrier。線上世界組呼叫端須先完成所有維度的 remove barrier，再開始任何維度的 spawn；不可逐維度完整套用而刪掉已移到另一維度的 UUID。離線 WorldOperations 透過 `OfflineApplier.applyAll` 已共用此 barrier。取消停止派發，等待在途 future 清理，再完成派出工作的 heightmap／光照／POI／封包／flush，最後解鎖與回報 PARTIAL；observer、光照或存檔任一失敗仍嘗試後續清理。執行緒 executor 必須存活到 result 完成；不在 owner thread 阻塞等另一 region。取消不反向復原。

`lockEdits` 須涵蓋容器、活塞、流體、紅石、生物與第三方插件協調，可使用 tick freeze；close 恢復先前狀態。`finishApply` completion 表示衍生資料與玩家 chunk 封包已完成，`flush` 包含 terrain/entity/POI IO 持久化。通知排在玩家 EntityScheduler／server owner，`ApplyProgress` 含 operation、phase、完成批次／section、取消旗標，可轉為 bossbar／MiniMessage。

`PlayerProtection` 帶 operation UUID 與 active：active=true 的 FALL／SUFFOCATION／DROWNING 保護持續到 active=false（成功、取消、失敗皆結束），再延續 duration=10 秒；須涵蓋中途加入範圍的玩家，不移動玩家、不用 Resistance。observer 在開始／每批／結束持久化操作狀態，失敗停止；只有 COMPLETE 加上呼叫端驗證 barrier 後才移動 HEAD。

新增 LiveWorld 方法以清楚失敗的 default 保持 Phase 1 Paper／Fabric 二進位／原始碼相容，未實作的線上端不能宣稱已有 switch。OfflineWorld 提供真正 Anvil 寫回；完整離線流程與平台接手摘要見 [12](12-phase2-progress.md)。
