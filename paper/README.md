# WorldGit Paper / Folia 插件（Phase 1）

同一個發佈 jar 支援 Paper / Folia 的 Minecraft **1.21.11 與 26.2**。1.21.11 使用 Java 21，26.2 使用 Java 25。`common` 只引用公開 Paper API；`v1_21_11`、`v26_2` 以 paperweight-userdev 2.0.0-beta.21 各自編譯薄 NMS 轉接層，啟動時只載入符合版本的類別。

```bash
GRADLE_USER_HOME=.work/gradle-home ./gradlew --configure-on-demand --max-workers=1 :paper:plugin:build
```

將 `paper/plugin/build/libs/worldgit-paper-0.1.0-SNAPSHOT.jar` 放到伺服器的 `plugins/`，啟動後執行 `/wg init`。未知 MC 版本會明確停用插件。26.2 adapter 是 Java 25 bytecode，其餘本體與共用模組是 Java 21。

## 指令與權限

| 指令 | 用途 | 權限（預設） |
|---|---|---|
| `/wg init [--template creative\|survival]` | 建立各維度 repo、初始快照與 ignore 範本 | `worldgit.command.init`（op） |
| `/wg status [--full] [--show]` | HEAD 與活世界摘要；full 為全量掃描 | `worldgit.command.status`（op） |
| `/wg commit -m 訊息` | 手動存檔點；沒有變動就不寫 commit | `worldgit.command.commit`（op） |
| `/wg log [數量]` | 依共享 snapshot 分組的歷史 | `worldgit.command.log`（所有人） |
| `/wg diff [--show] [--radius 6]` | 玩家附近的方塊明細，hover 前後狀態、點擊填入傳送指令 | `worldgit.command.diff`（op） |
| `/wg clear` | 清除自己的客戶端預覽 | `worldgit.command.clear`（所有人） |
| `/wg reload` | 重新載入語言覆寫 | `worldgit.command.reload`（op） |

`worldgit.admin` 包含上述指令與 `worldgit.notify` 通知。開發量測入口 `/wg debug` 只開放主控台，其他 sender 必須有 `worldgit.debug`（預設 false）。

## 儲存、設定與多語言

每個維度使用 CLI 可直接讀取的 bare repo：`.worldgit/<世界>/<namespace>.<dimension>/`。`.wgignore` 與 `worldgit-repo.yml` 是各 repo 的可編輯 sidecar；`.worldgit/<世界>/worldgit.yml` 是與 CLI 共用的本機設定（色票、實體黏性距離）。`modified-only` 目前只記錄設定，仍追蹤所有 full chunk。

`plugins/WorldGit/config.yml` 管理輪詢、每 tick 複製鏈數、滑動視窗、timeout、自動 commit 與預覽半徑。整數、布林值與身分字串會驗證；錯誤設定會明確停用插件。

訊息使用共用 `i18n.MessageCatalog` 與 Paper 內建 MiniMessage。玩家使用客戶端語言，主控台使用 `language`（預設 zh_tw）；未支援的語言退回 en_us。管理者可在 `plugins/WorldGit/lang/en_us.yml`、`zh_tw.yml` 覆寫個別鍵，再 `/wg reload`。`paper.*` 是插件的鍵；參數不會被解析成 MiniMessage 標籤。差異色彩使用 `<wg_added>`、`<wg_removed>`、`<wg_modified>`、`<wg_conflict>`，對應 protocol 的一般／色盲色票。

## 快照與作者

背景 repo executor 序列化所有 repo 操作。已載入 chunk 由 Paper 主執行緒／Folia 擁有它的 region 執行緒複製 palette、BE 與實體 NBT；背景才做編碼、正規化與 JGit 寫入。滑動視窗限制尚未消費的複本。跨 chunk 不是同 tick transaction；移動實體在一次掃描內依 UUID 去重。

候選來源為原始 `ChunkAccess.unsaved` volatile 欄位、Bukkit 事件、WorldEdit/FAWE，以及 entity chunk。未載入部分使用 core 的 region 時間戳、payload 雜湊與同秒不確定窗；卸載存檔仍在排隊時，可能到下一次 scan 才被看到。沒有強制 flush barrier，不宣稱所有卸載操作當下即已持久化。世界級 gamerule 另外在全域排程器複製，避免尚未落盤的設定在 commit 中落後。

事件與 WorldEdit 以**維度／玩家／chunk／原因**記錄作者；commit 帶 primary author、多位 `Contribution` 與 `Co-authored-by` trailers。目前是 chunk 級歸屬，沒有逐格 blame 或 per-player staging。失敗或未達自動門檻時，作者資料保留到下一次；新事件不會被較舊的 dirty generation 清掉。

定時自動 commit 預設每 15 分鐘，小變动最晚合併到 30 分鐘；`min-changed-sections` 可調高以降低生存世界底噪。只有實體變動預設不觸發定時 commit。登出觸發提交當前世界的全部變動；**並非只提交該玩家的變動**。Paper 在關閉流程內聯 commit。Folia 的 disable 階段沒有可用的 region 排程，改走**離線路徑**：onDisable 預載插件類別與離線來源，JVM 關閉鉤子等伺服器印出「All RegionFile I/O tasks to complete」（所有世界已存完）後，用 core 的離線掃描 commit（與 CLI 同一條路徑，標記為自動存檔點；作者歸屬沿用）。結果寫在 `plugins/WorldGit/shutdown-commit.log`（此時 log4j 已不可靠）。等不到訊號（180 秒）就放棄、不動世界。`/stop` 與 SIGTERM 兩種關閉方式都驗證過；kill -9／崩潰當然不會有關閉前 commit。

## WorldEdit 與客戶端模組

WorldEdit 是選用依賴。Paper 可搭配 FAWE；請在其 `config.yml` 加入：

```yaml
extent:
  allowed-plugins:
    - org.worldgit.paper
```

FAWE bulk 路徑用 `IBatchProcessor` 記錄 chunk 與 actor，純 WorldEdit 用 Extent 的逐格回呼。Folia 使用純 WorldEdit；本機 1.21.11 為 WE 7.4.2，26.2 為 WE 7.4.5（Java 25）。是否支援其他操作模式需另驗證。

`--show` 對完成 protocol v2 hello、nonce 與能力驗證的玩家傳送 status 描邊／diff 鬼影。每包最多 28,000 bytes、每 tick 最多兩包；方塊超過設定上限改送區域摘要。沒有模組時提供聊天提示與可點擊座標，另外退回 **display entity fallback**：`/wg diff --show` 對請求者以 `BlockDisplay` 發光描邊（ADDED／MODIFIED 描 after、REMOVED 描 before，顏色用目前色票）；超過 `show.display-max-entities`（預設 512）或 `/wg status --show` 時改畫每個 section 的 12 條邊包圍盒。實體 `visibleByDefault=false` 後只對請求者 `showEntity`，旁邊的玩家看不到；不持久化、不進 WorldGit 的實體快照；`show.display-seconds`（預設 60）後自動移除，`/wg clear`、登出、插件停用也會移除。

## 重現驗收與量測

所有腳本使用伺服器／baseline 的副本、127.0.0.1、offline mode、port 25651–25654。**acceptance、smoke、benchmark 自己取得 bench.lock，不要再包外層 flock。** SIGTERM 與例外會走清理流程；最後會停止 server 與 bot。

```bash
timeout 1800 python3 paper/tools/acceptance.py paper 1.21.11 basic mod display shutdown
timeout 1800 python3 paper/tools/acceptance.py paper 26.2
timeout 1800 python3 paper/tools/acceptance.py folia 1.21.11
timeout 1800 python3 paper/tools/acceptance.py folia 1.21.11 sigterm   # Folia：SIGTERM 關閉的離線 commit
# 四端端到端（插件 → CLI → Hub → 模組端封包）：python3 tools/e2e/phase1_e2e.py --platform paper|folia
timeout 1800 python3 paper/tools/acceptance.py folia 26.2
timeout 3600 python3 paper/tools/benchmark.py paper 1.21.11 1000
timeout 3600 python3 paper/tools/benchmark.py paper 1.21.11 10000
```

`ScaleFixture.java` 產生合成平坦 chunk，保留原 baseline 的世界設定，清除主世界實體／BE／流體與地形；原 baseline 不變。驗收以它隔離自然演化。早期 baseline 的海洋生物、kelp、水與燃燒熔爐會自行變動，因此「沒有人編輯」不等於「世界完全不變」；插件應保留這些真正變化，不能以正規化抹掉。

結果、console log 與失敗歷史在 `.work/paper-delivery/`。benchmark 先載入／init／暖機，再量測兩輪各 1,000 或 10,000 個變動 chunk；per-copy 耗時是擁有執行緒阻塞時間，probe 是出生點所屬 region 的 tick 間隔，包含 GC 與伺服器其他工作。Folia probe 不代表所有 region。

CI 對選定 adapter 與其內部類別檢查 NMS 方法／欄位描述子、存取權限與反射 unsaved 欄位。Paperclip 先 patch 出真正 server jar；同目錄不同版本不共用錯誤 cache。CI 的 Fill v3 下載已在 GitHub Actions 實跑驗證（2026-10-01）；本機開發環境呼叫時曾回 429／503／504，`fetch_paper.py` 會重試。

具體結果與限制見 [Phase 1 進度](../docs/11-phase1-progress.md)。Phase 2 的 switch／restore／保護／merge 尚未提供。
