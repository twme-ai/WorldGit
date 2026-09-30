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
│   └─ config/      worldgit.toml
├─ platform-api/    「活的世界」抽象：讀快照、套用變更、鎖定、通知玩家
├─ paper/           Paper（含 Folia）插件：實作 platform-api + 指令/GUI/預覽
├─ fabric/          Fabric 模組：伺服端實作 platform-api；客戶端鬼影渲染、合併工具 UI
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

| 端 | 主要使用者 | 獨有能力 | 限制 |
|---|---|---|---|
| **Paper 插件** | 多人伺服器 | 多人作者歸屬、登出自動 commit、權限、worktree 世界管理 | 預覽只能靠假方塊封包 / display entity；FAWE 等會繞過事件（需靠時間戳 + 雜湊補足）；Folia 需要按 region 執行緒排程 |
| **Fabric 模組** | 單人玩家、小團隊 | 客戶端半透明鬼影渲染、完整的 dirty 追蹤（mixin）、單人存檔直接可用 | 單人時伺服器是內建的，同一套伺服端邏輯即可；需要跟 MC 版本頻繁更新 |
| **CLI** | 管理員、進階玩家、CI | 對關閉中的世界操作、批次處理、與 Hub 同步、腳本 | 不能對運行中的世界操作（除非透過插件的 RPC） |
| **Hub** | 所有人 | 瀏覽、PR、3D diff、下載 | 不能在網頁上「玩」世界 |

## 3. 技術選型建議

| 項目 | 建議 | 理由 / 替代方案 |
|---|---|---|
| core 語言 | **Java 21+（或 Kotlin）** | 插件和模組都是 JVM，零橋接成本；替代：Rust core + JNI，效能好但兩套語言、跨平台打包麻煩 |
| NBT/Anvil | 自寫精簡讀寫器（離線）；線上則直接從伺服器記憶體結構取資料 | 避免依賴大型函式庫；可參考現有開源 NBT 函式庫 |
| 儲存 | JGit | 見 [03](03-storage-backend.md) |
| 壓縮 | zstd（section 編碼層）+ git 自身的 zlib | |
| CLI 發佈 | GraalVM native-image 單一執行檔，或 jlink 精簡 JRE | |
| Hub 後端 | Kotlin/Ktor 或 Spring，直接重用 core | 需要在伺服器端做合併與世界 zip 組裝，所以用 JVM 最省事 |
| Hub 前端 | Three.js 3D 檢視器，沿用 BlockForge 的方塊模型/材質管線 | 已有真實 26.3 方塊渲染 |
| 遊戲內預覽 | 插件：display entity / 假方塊封包（可參考既有的 VirtualEntities、WorldEditDisplay 經驗）；模組：客戶端渲染 | |
| 目標版本 | 以目前主力的 MC 26.x 為主；舊版本只保證讀取（DataFixer 升級） | 待確認 |

## 4. repo 在磁碟上的位置

```
<server>/
├─ world/                   ← working tree（MC 自己管）
├─ world_nether/ …
└─ .worldgit/
    └─ world/               ← git 物件庫（bare repo）+ index（chunk 時間戳/雜湊快取）+ worldgit.toml
```

放在世界資料夾外面：MC 不會碰到、傳統的世界備份不會把歷史也打包進去、刪世界不會連歷史一起刪。
