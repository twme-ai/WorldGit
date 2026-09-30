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
│   └─ config/      .wgignore 解析（版本控制內）、worldgit.toml（本機設定）
├─ platform-api/    「活的世界」抽象：讀快照、套用變更、鎖定、通知玩家
├─ protocol/        插件/模組 ↔ 客戶端模組的網路封包定義（diff 預覽、衝突資訊）
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

core 只認識 `LiveWorld`，所以同一套 commit/switch/merge 邏輯在插件、模組、CLI 都能跑；CLI 的 `LiveWorld` 就是直接讀寫 region 檔的離線實作，也是最容易寫單元測試的那個。

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
| core 語言 | **Java 21+** | 插件和模組都是 JVM，零橋接成本；替代：Rust core + JNI，效能好但兩套語言、跨平台打包麻煩 |
| NBT/Anvil | 自寫精簡讀寫器（離線）；線上則直接從伺服器記憶體結構取資料 | 避免依賴大型函式庫；可參考現有開源 NBT 函式庫 |
| 儲存 | JGit | 見 [03](03-storage-backend.md) |
| 壓縮 | zstd（section 編碼層）+ git 自身的 zlib | |
| CLI 發佈 | GraalVM native-image 單一執行檔，或 jlink 精簡 JRE | |
| Hub 後端 | **Java 21 + Spring Boot**（已決定用 Java），直接重用 core；git 協定用 JGit 的 `GitServlet` | 需要在伺服器端做合併與世界 zip 組裝；Spring 的 OAuth、權限、速率限制對公開服務現成可用；虛擬執行緒處理大量 git 連線。替代：Javalin（較輕，但 OAuth/權限要自己組） |
| Hub 前端 | **全新撰寫**（不沿用 BlockForge）：TypeScript + Vite，3D 用 Three.js 加上自寫的 chunk 網格生成器 | 見 [10](10-web-frontend.md) |
| 遊戲內預覽 | 插件：display entity / 假方塊封包（可參考既有的 VirtualEntities、WorldEditDisplay 經驗）；模組：客戶端渲染 | |
| 目標版本 | **首發：Paper 與 Fabric 的 26.2 與 1.21.11**；長期目標是支援範圍越廣越好 | 已決定（2026-09-30），見 §5 |

## 4. repo 在磁碟上的位置

```
<server>/
├─ world/                   ← working tree（MC 自己管）
├─ world_nether/ …
└─ .worldgit/
    └─ world/               ← git 物件庫（bare repo）+ index（chunk 時間戳/雜湊快取）+ worldgit.toml（本機設定）
                              # .wgignore 在 repo 的 tree 裡，跟世界內容一起被版本控制
```

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
