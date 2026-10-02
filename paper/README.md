# WorldGit Paper / Folia 插件（Phase 3：含線上合併）

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
| `/wg restore <rev> [--selection\|--chunks r\|--box x1 y1 z1 x2 y2 z2] [--dry-run]` | 原地還原；局部裁切方塊／BE，不移動 HEAD | `worldgit.command.restore`（op） |
| `/wg switch <branch\|rev> [--stash\|--force]` | 全維度原地切換；驗證成功後才移動 HEAD | `worldgit.command.switch`（op） |
| `/wg branch [-d] [name]` | 全維度分支清單、建立、刪除 | `worldgit.command.branch`（op） |
| `/wg stash push [message]\|pop [index]\|list\|drop [index]` | 保存／套回未提交內容；pop 要求原基底與乾淨工作區 | `worldgit.command.stash`（op） |
| `/wg reset --hard` | 全範圍還原 HEAD，保留 HEAD 指標 | `worldgit.command.reset`（op） |
| `/wg cancel` | 停止派發，等待在途清理，留下 PARTIAL 供完整重套 | `worldgit.command.cancel`（op） |
| `/wg merge <branch\|rev>` / `--continue` / `--abort` | 線上三方合併（noCommit）：無衝突直接寫入並提交；有衝突進入 MERGING（預設 ours）。`--abort` 逐格還原合併前世界 | `worldgit.command.merge`（op） |
| `/wg resolve <#\|all> ours\|theirs\|base\|manual` | 切換並標記區域已解決；manual 以世界目前內容為準 | `worldgit.command.resolve`（op） |
| `/wg conflicts [頁]` | 衝突清單 GUI（玩家）或文字清單（主控台）；點擊傳送。`conflicts preview <#> ours\|theirs\|base` 對 Fabric 客戶端送預覽 | `worldgit.command.conflicts`（op） |
| `/wg tool` | 取得合併工具（命名的指南針）：站進衝突區域，右鍵 ours→theirs→base 循環，Shift+右鍵標記已解決 | `worldgit.command.tool`、使用時另需 `worldgit.command.resolve`（op） |
| `/wg revert <rev>` / `/wg cherry-pick <rev>` | 反向／正向 patch；乾淨直接 commit，有衝突進同一 MERGING 流程 | `worldgit.command.revert`／`worldgit.command.cherry-pick`（op） |

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

## 合併（Phase 3）

`/wg merge` 在線上世界使用 core 的 `WorldOperations.live(...)`（noCommit）：無衝突部分直接寫入，衝突區域預設 ours，世界狀態 MERGING（持久化於 `merge-state.bin`，伺服器重啟後 bossbar、外框、工具與 GUI 自動恢復）。一次切換／解決只改該區域的精確 atoms，**一律維持快照儲存的方塊 state，不觸發 updateShape／鄰居更新（#46）**，所以柵欄連接、紅石 state 與快照逐格相同；`updateShapes` 只是交界提示（GUI 顯示「交界提示數」）。

- **提示**：MERGING 期間有權限的玩家看到紫色 bossbar「合併中：剩 N 個衝突」；在該維度會用 `BlockDisplay` 發光外框標出衝突區域（已解決變灰）；走進區域時動作列顯示編號、目前版本與狀態。
- **工具與 GUI**：合併工具右鍵／Shift+右鍵（250 ms 防連點，操作中或世界被鎖時忽略）；GUI 是 54 格箱子介面（每頁 45 區域），每區顯示座標、格數、雙方作者、目前版本／狀態、紅石警示與交界提示數，點擊用 `teleportAsync` 傳到區域上方（Folia 安全）。
- **玩家保護**：切換前註冊 operation 保護（窒息／摔落／溺水，結束後再延 10 秒），並沿用 Phase 2 的「實體 UUID 移除→生成」barrier，避免殘影與重複實體。
- **MERGING 期間**：一般 commit／switch／reset／stash／自動 commit 都被擋下並提示 `merge --continue`／`--abort`；`/wg status` 顯示 MERGING。
- **Fabric**：握手宣告 `merge-regions-v1`，對支援的客戶端送 `worldgit:conflicts`（狀態變更時更新）與 `worldgit:conflict_preview`（`/wg conflicts preview` 或客戶端請求）。
- **驗收**：`python3 paper/tools/acceptance.py <paper|folia> <1.21.11|26.2> phase3`（port 25701–25704，自帶 bench.lock），證據放 `.work/paper-phase3/`。結果見 [docs/13](../docs/13-phase3-progress.md)「Paper／Folia」。

## 重現驗收與量測

所有腳本使用伺服器／baseline 的副本、127.0.0.1、offline mode、port 25671–25674。**acceptance、smoke、benchmark 自己取得 bench.lock，不要再包外層 flock。** SIGTERM 與例外會走清理流程；最後會停止 server 與 bot。

```bash
timeout 1800 python3 paper/tools/acceptance.py paper 1.21.11 basic mod display shutdown
timeout 1800 python3 paper/tools/acceptance.py paper 26.2
timeout 1800 python3 paper/tools/acceptance.py folia 1.21.11
timeout 1800 python3 paper/tools/acceptance.py folia 1.21.11 sigterm   # Folia：SIGTERM 關閉的離線 commit
# 四端端到端（插件 → CLI → Hub → 模組端封包）：python3 tools/e2e/phase1_e2e.py --platform paper|folia
timeout 1800 python3 paper/tools/acceptance.py folia 26.2
timeout 3600 python3 paper/tools/benchmark.py paper 1.21.11 1000
timeout 3600 python3 paper/tools/benchmark.py paper 1.21.11 10000
# Phase 2：各平台／版本都可執行；phase2 含三個 bot 與 1000 chunk switch 量測
timeout 2400 python3 paper/tools/acceptance.py paper 1.21.11 phase2
timeout 1800 python3 paper/tools/acceptance.py folia 26.2 phase2-shutdown
timeout 1800 python3 paper/tools/acceptance.py folia 26.2 phase2-entities
# 只補驗收寫入後取消／ticket 清理／完整恢復／保護到期，不重跑切換與量測
timeout 1800 python3 paper/tools/acceptance.py paper 26.2 phase2-cancel
```

`ScaleFixture.java` 產生合成平坦 chunk，保留原 baseline 的世界設定，清除主世界實體／BE／流體與地形；原 baseline 不變。驗收以它隔離自然演化。早期 baseline 的海洋生物、kelp、水與燃燒熔爐會自行變動，因此「沒有人編輯」不等於「世界完全不變」；插件應保留這些真正變化，不能以正規化抹掉。

結果、console log 與失敗歷史在 `.work/paper-delivery/`。benchmark 先載入／init／暖機，再量測兩輪各 1,000 或 10,000 個變動 chunk；per-copy 耗時是擁有執行緒阻塞時間，probe 是出生點所屬 region 的 tick 間隔，包含 GC 與伺服器其他工作。Folia probe 不代表所有 region。

CI 對選定 adapter 與其內部類別檢查 NMS 方法／欄位描述子、存取權限與反射 unsaved 欄位。Paperclip 先 patch 出真正 server jar；同目錄不同版本不共用錯誤 cache。CI 的 Fill v3 下載已在 GitHub Actions 實跑驗證（2026-10-01）；本機開發環境呼叫時曾回 429／503／504，`fetch_paper.py` 會重試。

## 線上套用、安全與復原

repo executor 在全組編輯鎖內完成 flush、capture、預檢、journal、套用與驗證。chunk 由伺服器 async IO 載入，加 plugin ticket 後交由真正 owner 替換 section／BE／biome／scheduled ticks／structures，明確更新 POI、heightmap、光照及 unsaved。Starlight 完成回呼後重送 chunk，最後完成 terrain／entity／POI IO barrier；不直接寫使用中的 `.mca`。Folia 依當下 region ID／tick 共用預算、多 lane 並行；Paper 全維度共用同一個 tick 預算。有玩家時 4 section／5 ms／16 ticket，無玩家時 8／5 ms／24 ticket；section 不可搶占，時間是軟上限。

實體先依 UUID 掃描全維度的已存／已載入資料，只 ticket 載入含操作 UUID 的磁碟 chunk；全部移除（含舊 passengers）後才生成。正規化省略的乘客位置在 LOAD 前補母實體位置，避免加入原點或錯誤的 Folia region。剛生成實體不以 Folia `isValid()` 作成功判準；以全組 capture／存檔後 verify 為準。明確忽略的 BE／實體頂層欄位保留。套用後清除模組 status／diff 分包與 display fallback。

操作期間使用 vanilla 全伺服器 tick freeze，保存並恢復原 freeze／step 狀態；玩家仍可移動。事件攔截玩家編輯、容器、活塞、流體、紅石、爆炸、生物改方塊與 WorldEdit／FAWE。第三方直接寫 NMS 的插件須先查詢 `WorldGitPlugin.isEditLocked(world)` 配合，Bukkit 沒有通用攔截任意插件寫入的機制。WorldEdit `--selection` 目前接受 cuboid。

玩家不被傳送；FALL／SUFFOCATION／DROWNING 保護涵蓋整個操作與結束後 10 秒，也涵蓋中途進入範圍的玩家。bossbar／通知排到各玩家 EntityScheduler。只有全組驗證成功才廣播「已切換到 X @ abc1234」並更新 HEAD。

取消／插件關閉留下 PARTIAL，崩潰留下的 APPLYING 下次啟動轉成 PARTIAL 並提示；commit 被阻擋，使用 `/wg switch <target> --force` 或 `/wg reset --hard` 全範圍恢復。關閉時先停 bossbar 更新；玩家通知若與停用競爭，走退休清理，不再註冊新排程。沒有自動續傳或反向回滾。切換時目標沒有的 chunk 保留並標 untracked，包含 ticket 載入期間新生成的周邊 chunk；explicit commit 才重新追蹤。

目前線上預檢拒絕 chunk 刪除與有差異的 world-meta（地圖／記分板／世界設定等）；使用 CLI 離線還原。新增地形使 stash push／pop 必須刪 chunk 時也會先拒絕，保存 stash 前不改世界。跨 DataVersion、不同 .wgignore／DataPacks 沿用 core 的明確拒絕。merge 留待 Phase 3。

Paper／Folia 的 1.21.11／26.2 四平台 Phase 2 驗收已通過，涵蓋原地切換、局部 restore、stash、取消恢復、關服重開、玩家保護、UUID／巢狀乘客、光照／POI 與 mod status／diff 清除。每平台量測三次，1,000 chunk 切換每次總耗時：Paper 約 47.7–48.0 秒、Folia 約 34.0–37.2 秒；tick probe TPS 估計 19.57–19.96，偶有 0.42–0.64 秒間隔尖峰。完整 build 全綠；測試範圍與 Phase 0 比較限制見進度報告。

具體驗收與量測見 [Phase 2 Paper／Folia 進度](../docs/12-phase2-progress.md#paperfolia)；Phase 1 功能的證據見 [Phase 1 進度](../docs/11-phase1-progress.md)。Phase 2 原始結果、失敗歷史在 `.work/paper-phase2/`，console log 在 `.work/paper-delivery/logs/`；`ApplyEvidence.java` 讀存檔的全維度 UUID、光源／鄰格 nibble 與 POI，驗收結束刪除 server／world 副本。
