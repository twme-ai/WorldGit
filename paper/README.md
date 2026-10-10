# WorldGit Paper / Folia 插件（Phase 5：維度與操作體驗）

同一個發佈 jar 支援 Paper / Folia 的 Minecraft **1.21.11 與 26.2**。1.21.11 使用 Java 21，26.2 使用 Java 25。`common` 只引用公開 Paper API；`v1_21_11`、`v26_2` 以 paperweight-userdev 2.0.0-beta.21 各自編譯薄 NMS 轉接層，啟動時只載入符合版本的類別。

Phase 5 任務 3：所有 repo 指令預設使用玩家所在世界群的目前維度；console 的其他 repo 指令預設伺服器第一個世界的主世界，init 必須明確指定 `--world <世界名>` 與 `--dimension <id>` 或 `--all`。主世界 init 的聊天按鈕可追加既存地獄／終界，沒有自動建立其他 repo。`--dimension` 覆寫目標，`--all` 逐維度完成、各自回報；其中一維度失敗不回滾其他成功。

新增 `/wgit`、`/git`、`/worldgit:git`，保留 `/worldgit`；裸 `/git` 衝突時保留其他插件，名稱空間仍可用。文字 graph、BossBar 進度與終態、完成摘要、可複製且遮罩的錯誤、ignore GUI／preview／確認、creative 玩家觸及實體已接線。Fabric 對應 UX 仍是任務 4；原版客戶端即可使用此次 Paper 功能。

```bash
GRADLE_USER_HOME=.work/gradle-home ./gradlew --configure-on-demand --max-workers=1 :paper:plugin:build
```

將 `paper/plugin/build/libs/worldgit-paper-0.1.0-SNAPSHOT.jar` 放到伺服器的 `plugins/`，啟動後執行 `/wg init`。未知 MC 版本會明確停用插件。26.2 adapter 是 Java 25 bytecode，其餘本體與共用模組是 Java 21。

## Axiom

Paper 1.21.11／26.2 可搭配對應版 AxiomPaper 6.0.1 與客戶端 Axiom 6.1.3。AxiomPaper 是選用插件；未安裝時 WorldGit 不載入其 API。AxiomPaper 沒有宣告 Folia 支援，WorldGit 在 Folia 不啟用此整合。

Axiom 方塊、BE、biome 編輯可由 status／commit 捕捉；CustomIntegration 的座標及已核對的寫入 frame 記錄玩家及 `axiom` cause，實體生成／操作記入 creative 的 player-touched。具備 `axiomadmin.bypass_region_checks` 的方塊編輯跳過座標 API，歸屬保持未知；多人不使用時間窗口猜作者。1.21.11 不版本化日間時計／Axiom 註記；26.2 的 world clocks、天氣、流浪商人等執行期 saved-data 同樣視為暫態、不追蹤，時間與天氣變動不會使線上 switch 被拒（決定 #159）。gamerules 等設定依既有 world-meta 規則追蹤。

WorldGit 套用期間拒絕該鎖定世界的 Axiom 編輯（局部套用也保守拒絕整個世界），套用前取消尚未完成的 Axiom buffer；玩家會收到自己的語系訊息與區塊重送。插件 API／queue 結構不相容時，明確拒絕套用，請更新整合或停用 AxiomPaper。套用後請重整 Axiom editor preview；其 clipboard／undo 不跟隨 WorldGit 歷史。視覺重疊時先 `/wg clear`。線上 world-meta 還原仍需離線 CLI。

研究依據、精確度與真伺服器命令見 [Axiom 互通性](../docs/17-axiom.md)。26.2 的註記／clock 差異需離線還原，沒有專屬線上快取重載整合。


## 指令與權限

| 指令 | 用途 | 權限（預設） |
|---|---|---|
| `/wg init [--template creative\|survival]` | 建立目前維度 repo、初始快照與 ignore 範本 | `worldgit.command.init`（op） |
| `/wg status [--full] [--show]` | HEAD 與活世界摘要；full 為全量掃描 | `worldgit.command.status`（op） |
| `/wg commit -m 訊息` | 手動存檔點；沒有變動就不寫 commit | `worldgit.command.commit`（op） |
| `/wg log [數量] [--graph] [--page 頁]` | 目前維度歷史／文字分支圖；hover 完整資訊，點選填入 diff | `worldgit.command.log`（所有人） |
| `/wg diff [rev] [--show] [--radius 6]` | 玩家附近的方塊明細，hover 前後狀態、點擊填入傳送指令 | `worldgit.command.diff`（op） |
| `/wg clear` | 清除自己的客戶端預覽 | `worldgit.command.clear`（所有人） |
| `/wg reload` | 重新載入語言覆寫 | `worldgit.command.reload`（op） |
| `/wg restore <rev> [--selection\|--chunks r\|--box x1 y1 z1 x2 y2 z2] [--dry-run]` | 原地還原；局部裁切方塊／BE，不移動 HEAD | `worldgit.command.restore`（op） |
| `/wg switch <branch\|rev> [--stash\|--force]` | 所選維度原地切換；驗證成功後才移動該維度 HEAD | `worldgit.command.switch`（op） |
| `/wg branch [-d] [name]` | 所選維度的分支清單、建立、刪除 | `worldgit.command.branch`（op） |
| `/wg stash push [message]\|pop [index]\|list\|drop [index]` | 保存／套回未提交內容；pop 要求原基底與乾淨工作區 | `worldgit.command.stash`（op） |
| `/wg reset --hard` | 全範圍還原 HEAD，保留 HEAD 指標 | `worldgit.command.reset`（op） |
| `/wg cancel` | 停止派發，等待在途清理，留下 PARTIAL 供完整重套 | `worldgit.command.cancel`（op） |
| `/wg merge <branch\|rev>` / `--continue` / `--abort` | 線上三方合併（noCommit）：無衝突直接寫入並提交；有衝突進入 MERGING（預設 ours）。`--abort` 逐格還原合併前世界 | `worldgit.command.merge`（op） |
| `/wg conflict-select <#\|all> ours\|theirs\|base\|manual` | 原地切換精確區域 atoms，維持 unresolved；manual 保留現況 | `worldgit.command.resolve`（op，與 resolve 共用） |
| `/wg resolve <#\|all> ours\|theirs\|base\|manual` | 切換並標記區域已解決；manual 以世界目前內容為準 | `worldgit.command.resolve`（op） |
| `/wg conflicts [頁]` | 衝突清單 GUI（玩家）或文字清單（主控台）；點擊傳送。`conflicts preview <#> ours\|theirs\|base` 對 Fabric 客戶端送預覽 | `worldgit.command.conflicts`（op） |
| `/wg tool` | 取得合併工具（命名的指南針）：站進衝突區域，右鍵 ours→theirs→base 循環，Shift+右鍵標記已解決 | `worldgit.command.tool`、使用時另需 `worldgit.command.resolve`（op） |
| `/wg revert <rev>` / `/wg cherry-pick <rev>` | 反向／正向 patch；乾淨直接 commit，有衝突進同一 MERGING 流程 | `worldgit.command.revert`／`worldgit.command.cherry-pick`（op） |
| `/wg ignore [list\|add\|remove\|move\|enable\|disable\|test\|check\|preview\|confirm]` | 箱子規則清單／聊天新增、預覽與確認；下一 commit 記錄歷史 | `worldgit.command.ignore`（op） |
| `/wg tag [-d] [name]`／`verify [rev]` | 目前維度 tag 與線上驗證 | `worldgit.command.tag`／`verify`（op） |
| `/wg help [子指令]` | 依權限列出用法，點擊填入聊天列 | 所有人 |

`worldgit.admin` 包含上述指令與 `worldgit.notify` 通知。開發量測入口 `/wg debug` 只開放主控台，其他 sender 必須有 `worldgit.debug`（預設 false）。

`/wg`、`/wgit`、`/git` 與 `/worldgit` 使用 **Paper Brigadier Command API**。`onEnable` 透過 `LifecycleEvents.COMMANDS` 註冊完整指令樹，`plugin.yml` 只保留權限，沒有 Bukkit `commands:`／字串解析器。每個節點的 `requires` 同時檢查權限與 sender：tool、diff、clear、conflict-preview 限玩家；status 的 --show、restore 的 --selection、comments 的 show／hide／--here 也限玩家。預設非 op 玩家只收到 log、clear、help；help 的主題同樣過濾。直接輸入無權限入口仍回覆既有多語言權限訊息。

聊天列會逐參數提示與上色；log 數量 1–100、diff 半徑 1–32 chunk、restore 的 --chunks 0–256、stash index ≥0、PR／衝突編號／頁碼 ≥1。`--box` 是兩個 `ArgumentTypes.blockPosition()`，除了原本六個整數，也接受相對於指令來源的 `~` 與區域座標 `^`；留言維度使用 namespaced key，debug 的玩家使用 player resolver。型別／範圍錯誤交由 Brigadier 標示位置，repo／Hub 業務錯誤仍使用原有 i18n 訊息。

`--xxx` 旗標保留原寫法，合法旗標可任意排列，同一旗標只能出現一次，selection／chunks／box 擇一；`init --template=creative|survival` 也保留。commit 的 `-m` 與 stash 訊息使用 greedy string，後面的所有字元就是文字。PR 標題／留言使用自訂 greedy argument：保留文字前、後的 `--source`／`--target`／`--here`；若文字本身含有這些旗標，將文字放在雙引號內，例如 `/wg pr create "標題 --source 是文字" --source topic`。建議將 PR 旗標放在標題前，以取得獨立型別節點提示。分支／revision 使用 quoted string 作客戶端型別，含 `/`、`~` 或中文時可加雙引號，補全會自動加上所需引號；伺服器仍接受舊的未加引號寫法。PR／衝突編號建議直接用 `1`，既有 `#1` 仍可送給伺服器，但原生 integer 客戶端會將帶 `#` 的寫法標為紅字。只有數字分支發出動態補全請求，避免原版客戶端取消同位置的另一個在途請求。

動態補全包含分支 head 短 hash／最後訊息、tag／HEAD~n／最近 revision、stash 訊息、MERGING 的衝突座標／格數／選擇／解決狀態、remote URL、Hub PR 標題／狀態與留言維度。tooltip 透過 Adventure `MessageComponentSerializer` 與 i18n，依玩家語言顯示；玩家文字及 Hub 資料為純文字，remote URL 不顯示憑證。查詢走既有 repo 背景 executor、唯讀 store，不 capture 或寫入；每類最多 256 項，本機快取 2 秒、PR 10 秒，同類在途查詢共用。補全 750 ms 未完成即回空，Hub 查詢 timeout 1.5 秒，失敗靜默；下次請求可使用已完成的快取。

Paper 的 [Lifecycle 註冊](https://docs.papermc.io/paper/dev/command-api/basics/registration/)會在需要重建指令時重新註冊（包含伺服器 `/reload`）。WorldGit 的 `/wg reload` 只重載語言覆寫；更新插件 jar、憑證環境或設定請重新啟動伺服器。第三方熱卸載／重載不是本插件的驗收範圍，Folia 的排程與停用清理限制仍適用；兩版共用相同公開指令 API。

## Brigadier 驗收（2026-10-04）

新增 10 個指令樹／補全測試，涵蓋全部入口、native 型別注入邊界、旗標排列與舊寫法、requires／玩家邊界、錯誤 cursor、純文字 tooltip、憑證遮罩、唯讀檔案比對、有界快取、逾時／失敗與 single-flight。真客戶端另核對收到的原生 block position／namespaced key 型別、29 個玩家 op 子指令與 worldgit 別名，以及 deop 後只有 log／clear／help。

兩版 Paper 各 9 張真客戶端截圖已逐張檢視，見 [Brigadier 截圖與失敗修正紀錄](docs/screenshots/brigadier/README.md)。fixture 與 driver 都在 paper/tools，由暫時 Gradle init script 注入測試 classpath，Fabric 原始碼不變。

Phase 3 的四個 conflict-select 非法參數案例改為等待原生錯誤 cursor，並同時斷言錯誤訊息、`<--[HERE]` 與完整 MergeState 不變；原先等待舊 parser 的 `/wg merge` 整頁用法會逾時。其他回歸場景與行為斷言保留。

完整 16 組回歸首次為 15 組通過、Paper 1.21.11 Phase 3 因上述舊斷言逾時；更新斷言後完整補驗該組通過，16 組最終全數通過。最後全專案 `build` 通過（80 個 task，含 common 的 `verifyNoNms` 及兩版轉接層）；最終 jar 另通過兩版 Paper 的 `check_binary_compat.py`，直接 NMS 引用與反射 unsaved 欄位均無問題。[可攜驗收摘要](docs/screenshots/brigadier/results-2026-10-04.json) 保留首次結果、補驗、18 張畫面與原始 log 雜湊；後續空座標 tooltip 保護由新增單元案例與新 jar 的補驗覆蓋，兩個 jar 唯一不同 entry 是 CommandSuggestions.class。

```sh
GRADLE_USER_HOME=.work/gradle-home flock .work/bench.lock ./gradlew --no-daemon --configure-on-demand --max-workers=1 :paper:common:test :paper:plugin:build
python3 paper/tools/brigadier.py 26.2
python3 paper/tools/brigadier.py 1.21.11
python3 paper/tools/phase4_regressions.py --phase4  # 四種遠端＋四平台各 Phase2／Phase3／interop，共 16 組；子腳本自持鎖
# 本次首次完整回歸的舊錯誤格式斷言逾時後，完整補驗這一組
python3 paper/tools/phase4_regressions.py --case paper-1.21.11-phase3
GRADLE_USER_HOME=.work/gradle-home flock .work/bench.lock ./gradlew --no-daemon --configure-on-demand --max-workers=1 build
```

## 儲存、設定與多語言

每個維度使用 CLI 可直接讀取的 bare repo：主世界 `<world>/.worldgit/`，其他維度的資料目錄內 `.worldgit/`。`.wgignore`、`worldgit-repo.yml` 與本機 `worldgit.yml` 跟著各 repo 攜帶；repo 指令由目標維度讀取色票／實體黏性距離。`modified-only` 尚未接平台的持久玩家編輯集合，缺集合時保守追蹤所有 full chunk。

`plugins/WorldGit/config.yml` 管理輪詢、每 tick 複製鏈數、滑動視窗、timeout、自動 commit 與預覽半徑。整數、布林值與身分字串會驗證；錯誤設定會明確停用插件。

訊息使用共用 `i18n.MessageCatalog` 與 Paper 內建 MiniMessage。玩家使用客戶端語言，主控台使用 `language`（預設 zh_tw）；未支援的語言退回 en_us。管理者可在 `plugins/WorldGit/lang/en_us.yml`、`zh_tw.yml` 覆寫個別鍵，再 `/wg reload`。`paper.*` 是插件的鍵；參數不會被解析成 MiniMessage 標籤。差異色彩使用 `<wg_added>`、`<wg_removed>`、`<wg_modified>`、`<wg_conflict>`，對應 protocol 的一般／色盲色票。

## 快照與作者

背景 repo executor 序列化所有 repo 操作。已載入 chunk 由 Paper 主執行緒／Folia 擁有它的 region 執行緒複製 palette、BE 與實體 NBT；背景才做編碼、正規化與 JGit 寫入。滑動視窗限制尚未消費的複本。跨 chunk 不是同 tick transaction；移動實體在一次掃描內依 UUID 去重。

候選來源為原始 `ChunkAccess.unsaved` volatile 欄位、Bukkit 事件、WorldEdit/FAWE，以及 entity chunk。未載入部分使用 core 的 region 時間戳、payload 雜湊與同秒不確定窗；卸載存檔仍在排隊時，可能到下一次 scan 才被看到。沒有強制 flush barrier，不宣稱所有卸載操作當下即已持久化。世界級 gamerule 另外在全域排程器複製，避免尚未落盤的設定在 commit 中落後。

事件與 WorldEdit 以**世界群／維度／玩家／chunk／原因**記錄作者；commit 帶 primary author、多位 `Contribution` 與 `Co-authored-by` trailers。目前是 chunk 級歸屬，沒有逐格 blame 或 per-player staging。失敗或未達自動門檻時，作者資料保留到下一次；新事件不會被較舊的 dirty generation 清掉；單維度 commit 保留其他維度的作者資料。

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

`/wg merge` 在線上世界使用 core 的 `WorldOperations.live(...)`（noCommit）：無衝突部分直接寫入，衝突區域預設 ours，世界狀態 MERGING（持久化於 `merge-state.bin`＋`.updates`，伺服器重啟後 bossbar、外框、工具與 GUI 自動恢復）。一次切換／解決只改該區域的精確 atoms，**一律維持快照儲存的方塊 state，不觸發 updateShape／鄰居更新（#46）**，所以柵欄連接、紅石 state 與快照逐格相同；`updateShapes` 只是交界提示（GUI 顯示「交界提示數」）。

- **提示**：MERGING 期間有權限的玩家看到紫色 bossbar「合併中：剩 N 個衝突」；在該維度會用 `BlockDisplay` 發光外框標出衝突區域（已解決變灰）；走進區域時動作列顯示編號、目前版本與狀態。
- **工具與 GUI**：合併工具右鍵／Shift+右鍵（250 ms 防連點，操作中或世界被鎖時忽略）；GUI 是 54 格箱子介面（每頁 45 區域），每區顯示座標、格數、雙方作者、目前版本／狀態、紅石警示與交界提示數，點擊用 `teleportAsync` 傳到區域上方（Folia 安全）。
- **玩家保護**：切換前註冊 operation 保護（窒息／摔落／溺水，結束後再延 10 秒），並沿用 Phase 2 的「實體 UUID 移除→生成」barrier，避免殘影與重複實體。
- **MERGING 期間**：一般 commit／switch／reset／stash／自動 commit 都被擋下並提示 `merge --continue`／`--abort`；`/wg status` 顯示 MERGING。
- **Fabric**：握手宣告 `merge-regions-v1` 與 `conflict-select-v1`（允許 Fabric Set blocks），對支援的客戶端送 `worldgit:conflicts`（狀態變更時更新）與 `worldgit:conflict_preview`（`/wg conflicts preview` 或客戶端請求）。
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

repo executor 先在鎖外預檢，再鎖住受影響 chunk；鎖後 flush、capture 才是權威：與預檢基底不同時以鎖後狀態重算計畫（足跡變大就擴大鎖重試），switch 不得覆蓋預檢後才出現的未提交變動（自動重新預檢最多 3 次，仍不同就列出 chunk 座標與種類後拒絕，`switch --stash` 則把它們一併 stash），單 tick 原子套用核對失敗時退回鎖路徑（決定 #163–#167）。之後在編輯鎖內完成 journal、套用與驗證。chunk 由伺服器 async IO 載入，加 plugin ticket 後交由真正 owner 替換 section／BE／biome／scheduled ticks／structures，明確更新 POI、heightmap、光照及 unsaved。Starlight 完成回呼後重送 chunk，最後完成 terrain／entity／POI IO barrier；不直接寫使用中的 `.mca`。Folia 依當下 region ID／tick 共用預算、多 lane 並行；Paper 全維度共用同一個 tick 預算。有玩家時 4 section／5 ms／16 ticket，無玩家時 8／5 ms／24 ticket；section 不可搶占，時間是軟上限。

2026-10-08 使用者決定：套用不使用全伺服器／整世界 freeze、agent 或 attach。小變動在單一 owner tick 寫入並複製驗證資料；較大變動只鎖受影響 chunk 與一圈邊界，保留 scheduled ticks 的剩餘延遲。其他世界及同世界遠處 chunk 照常模擬。預設原子門檻為 1 chunk／1 section／8 個實體操作，設定與逐平台量測、Folia 邊界見 [18](../docs/18-performance.md)。

常用操作 benchmark：`python3 paper/tools/benchmark.py --latency paper 1.21.11 --label after --require-isolation`；Folia 改第一個平台參數。自行拿 bench.lock，不外包 flock；完整量測三輪及 1／10／100／1000 chunk。

實體先預檢其他維度的活 UUID；若重複則拒絕並指出維度。只在目標維度 ticket 載入含操作 UUID 的磁碟 chunk，先移除舊 passengers，再生成；集合外自然實體保留。正規化省略的乘客位置在 LOAD 前補母實體位置，避免加入原點或錯誤的 Folia region。剛生成實體不以 Folia `isValid()` 作成功判準；以全組 capture／存檔後 verify 為準。明確忽略的 BE／實體頂層欄位保留。套用後清除模組 status／diff 分包與 display fallback。

本節的舊 tick 暫停流程已由 2026-10-08 使用者決定取代，現行 chunk 隔離與門檻見 [18](../docs/18-performance.md)。

玩家不被傳送；FALL／SUFFOCATION／DROWNING 保護涵蓋整個操作與結束後 10 秒，也涵蓋中途進入範圍的玩家。bossbar／通知排到各玩家 EntityScheduler。只有該維度驗證成功才廣播「已切換到 X @ abc1234」並更新 HEAD。

取消／插件關閉留下 PARTIAL，崩潰留下的 APPLYING 下次啟動轉成 PARTIAL 並提示；commit 被阻擋，使用 `/wg switch <target> --force` 或 `/wg reset --hard` 全範圍恢復。關閉時先停 bossbar 更新；玩家通知若與停用競爭，走退休清理，不再註冊新排程。沒有自動續傳或反向回滾。切換時目標沒有的 chunk 保留並標 untracked，包含 ticket 載入期間新生成的周邊 chunk；explicit commit 才重新追蹤。

目前線上預檢拒絕 chunk 刪除與有差異的 world-meta（地圖／記分板／世界設定等）；使用 CLI 離線還原。新增地形使 stash push／pop 必須刪 chunk 時也會先拒絕，保存 stash 前不改世界。跨 DataVersion、不同 .wgignore／DataPacks 沿用 core 的明確拒絕。合併流程見上方 Phase 3 章節。

Paper／Folia 的 1.21.11／26.2 四平台 Phase 2 驗收已通過，涵蓋原地切換、局部 restore、stash、取消恢復、關服重開、玩家保護、UUID／巢狀乘客、光照／POI 與 mod status／diff 清除。每平台量測三次，1,000 chunk 切換每次總耗時：Paper 約 47.7–48.0 秒、Folia 約 34.0–37.2 秒；tick probe TPS 估計 19.57–19.96，偶有 0.42–0.64 秒間隔尖峰。完整 build 全綠；測試範圍與 Phase 0 比較限制見進度報告。

具體驗收與量測見 [Phase 2 Paper／Folia 進度](../docs/12-phase2-progress.md#paperfolia)；Phase 1 功能的證據見 [Phase 1 進度](../docs/11-phase1-progress.md)。Phase 2 原始結果、失敗歷史在 `.work/paper-phase2/`，console log 在 `.work/paper-delivery/logs/`；`ApplyEvidence.java` 讀存檔的全維度 UUID、光源／鄰格 nibble 與 POI，驗收結束刪除 server／world 副本。

## 局部區域切換（2026-10-02）

合併工具、`/wg conflict-select` 與 `/wg resolve` 使用共用 core 的局部 source／chunk journal。一般方塊區域只讀取與保存受影響 chunk，owner 使用 Moonrise `NewChunkHolder.save(false)` 同步排入 terrain／entity／POI；保持 Starlight 完成回呼與 IO barrier，再驗證整個受影響 chunk，最後保存 MERGING 增量。精確 atoms mask 不重寫同 section 的其他 BE，也不觸發鄰居更新。merge 開始、continue／commit、abort 仍走完整世界驗證。

本節的舊 tick 暫停流程已由 2026-10-08 使用者決定取代，現行 chunk 隔離與門檻見 [18](../docs/18-performance.md)。

Phase 3 驗收新增 6 次工具切換、在大世界的 200 個衝突區域中切換一個區域 6 次，報告中位數／最大值。開啟分段計時：`JAVA_TOOL_OPTIONS=-Dworldgit.profile=true python3 paper/tools/acceptance.py paper 1.21.11 phase3`。較短的 4 格重現：`JAVA_TOOL_OPTIONS=-Dworldgit.profile=true python3 paper/tools/profile-region.py paper 1.21.11 optimized`。兩者自行取得 bench.lock。結果與完整限制見 [docs/13 區域切換延遲](../docs/13-phase3-progress.md#區域切換延遲)。

## 遠端協作（Phase 4，2026-10-03）

| 指令 | 行為 | 權限（皆預設 op） |
|---|---|---|
| `/wg remote add <name> <Hub URL>`／`remove <name>`／`list`／`set-url <name> <URL>` | 目前維度 remote sidecar；--all 逐維度設定，一般 git 樣板沿用 core | `worldgit.command.remote` |
| `/wg fetch [remote]` | 背景下載、驗證目前維度後發布其 tracking | `worldgit.command.fetch` |
| `/wg push [remote] [branch] [--tags]` | FF 推送／PARTIAL 原參數重試；拒絕非 FF，提示 pull；沒有 force | `worldgit.command.push` |
| `/wg pull [remote] [branch]` | fetch 後顯示 FF／三方／衝突區域／chunk／套用估計；不改世界 | `worldgit.command.pull` |
| `/wg pull confirm <code>` | 120 秒內一次性、綁執行者確認；重新 fetch，tip／URL／本地 HEAD 改變即拒絕；live 套用／MERGING | 同上 |
| `/wg pr create <title> [--source b] [--target main]`／`list`／`view <#>` | 本機來源先 push；另一端已發布來源先 fetch 驗指定維度；狀態／mergeability／審核數／可點擊 Hub 連結 | `worldgit.command.pr` |
| `/wg comments [pr #] [--here\|--dimension d]` | 世界／PR／維度釘選清單；--here 為目前 chunk 相交釘選 | `worldgit.command.comment` |
| `/wg comment <#> <text> [--here]` | PAT 身分留言，--here 帶玩家目前維度與整數座標 | 同上 |
| `/wg comments show\|hide [pr #]` | 目前維度已載入 chunk 的私人 TextDisplay；hide 清除全部自己的留言顯示 | 同上 |

PR **merge／approve 僅在 Hub 網頁**，因為審核與衝突選擇應搭配網頁 3D 檢視。`worldgit.admin` 包含新增權限。遊戲 `/wg push` 僅推已 commit 的歷史；不自動 capture。pull 要求目標維度乾淨（含 untracked），沿用 Phase 3 的 commit／stash 與 MERGING 規則。chunk 統計包含候選計畫與精確衝突 atoms（即使預設 ours 暫不改方塊）。估計以候選計畫每秒 80 section 的保守顯示基準計算，至少 1 秒，不含網路、完整 capture／存檔／驗證，也不是延遲保證；後續衝突選擇的套用另計。

`config.yml` 的 `remote` 區段範例見 [內建設定](common/src/main/resources/config.yml)。`hub-url` 空白時使用 `/wg remote add`；有值時遠端操作會補上尚不存在的預設 remote；要永久移除配置的預設 remote，須清空 hub-url 並重新啟動。`default-name: origin`；`fetch-interval-seconds: 0` 關閉定時 fetch，啟用至少 60 秒；REST 與 git socket timeout 使用 `timeout-seconds`（1–120，預設 30）。世界 remotes.yml 不含憑證，token 不可放在 URL。

PAT 來源：指定 `token-environment`（預設 WGIT_TOKEN，以 Bearer 使用）優先，否則 `plugins/WorldGit/credentials.yml`：

```yaml
credentials:
  https://hub.example.com:
    mode: bearer
    token: YOUR_PAT
```

檔案必須是插件資料夾直接子檔案、普通非 symlink，POSIX 權限恰為 `600`；不要放進世界 `.worldgit/`、datapacks 或 config.yml。PAT 檔案輪替後，下一操作重新解析，不輸出 token；環境變數、remote 設定與 webhook secret 的更換需重新啟動插件／伺服器。沒有 PAT 時使用 core 的明確 anonymous Basic，私人 Hub 回認證錯誤。遊戲留言作者是 PAT 帳號，座標由執行者 owner thread 取得。

webhook 預設關閉，啟用後預設 `127.0.0.1:25731/worldgit/webhook`；secret 從 `WGIT_WEBHOOK_SECRET` 或插件資料夾 `webhook.secret`（UTF-8 純文字、32–4096 字元、600）取得。Hub 管理者另在世界 webhook 設定同一 URL／secret，事件選 push／pr.merged；loopback 需 Hub 的精確 allowlist。接收器只支援 HTTP/1.1、Content-Length POST、Connection close；不接受 chunked／redirect／其他方法。公開入口應由 TLS reverse proxy 終結，固定 body/header/連線限制。

通知只針對預設 remote 的目前本機分支。簽章／重放／大小／速率檢查後，背景 fetch 驗各維度傳輸，對有 pull 權限的線上玩家與 console 提示 `/wg pull`；傳輸尚未完成／網路失敗有限退避 2/4/8/16/32 秒，仍失敗則提示手動 fetch。**永遠不自動 apply**。安全審查見 [Phase 4 紀錄](docs/security-review-phase4-2026-10-03.md)。

留言最多讀 512 則（不足時可按 PR／維度篩選）；PR／留言清單顯示前 20 則，PR 可用 view 指定編號，show 最多 64 個 TextDisplay，不載入遠端指定的未知 chunk。文字以 `Component.text` 當純文字，作者 32／摘要 240 Unicode 字元，截斷時另加省略號；HTML／MiniMessage 保持字面，legacy 色碼、控制／雙向格式字元移除。範圍是每玩家 DUST 粒子外框（每秒最多 384 點／64 格距離）。`visibleByDefault=false` 後只向請求者 showEntity，`persistent=false`＋兩版 capture tag 排除；hide／離線／換維度清除，晚到的 REST 回應不能重建已清除顯示。Paper 停用同步刪除；Folia 正常停服隨世界卸載清除，第三方熱卸載沒有立即跨 region 刪除保證，詳見安全審查。

平台中立類別位於 `platform-api/src/main/java/org/worldgit/platform/remote/`：RemoteSettings／PlatformCredentials／HubClient（PR、留言 DTO）／WebhookReceiver／CommentText。Fabric 下一任務可直接使用，另接自己的 YAML、權限、owner scheduler 與 live coordinator；Fabric jar-in-jar 必須納入 client 的 Jackson runtime 依賴。這次沒有 Fabric 遠端 UI／render 改動，也沒有新增 protocol capability，原版 TextDisplay／粒子和聊天已能由 Fabric 客戶端顯示。

重跑真 Hub jar＋SQLite 與遊戲場景（自帶 bench.lock，Paper 25721/25722，Folia 25723/25724，webhook 25731–25734，Hub 8096）：

```sh
python3 paper/tools/phase4.py paper 1.21.11 --screenshots
python3 paper/tools/phase4.py paper 26.2 --screenshots
python3 paper/tools/phase4.py folia 1.21.11 --screenshots
python3 paper/tools/phase4.py folia 26.2 --polling --screenshots
python3 paper/tools/phase4_regressions.py
```

完整序列可用 `python3 paper/tools/phase4_regressions.py --phase4`（四組遠端＋四平台各 Phase 2／3／interop，共 16 組）；`--phase4-only` 只跑四組遠端。補驗失敗或缺少的組合可用 `--case`，避免重跑已通過的項目：

```sh
python3 paper/tools/phase4_regressions.py --case paper-26.2-phase2 --case paper-26.2-phase3
python3 paper/tools/phase4_regressions.py --case folia-26.2-interop
```

遠端場景使用 `.work/servers/phase4-runs/` 複本，既有回歸各自使用 harness 的 run 目錄；finally 關閉 Hub／bot／瀏覽客戶端並刪除秘密與大型複本。`paper/tools/fixtures/Phase4DisplayGameTest.java` 是臨時客戶端截圖 fixture，透過 init script 加入既有 gametest task，fixture 不納入正式 Fabric jar，沒有修改 Fabric 原始碼。實際結果與限制見 [docs/14 Paper／Folia](../docs/14-phase4-progress.md#paper-folia)，[可攜驗收摘要](docs/phase4/results-2026-10-03.json) 保留原始證據雜湊，精選截圖在 `paper/docs/screenshots/phase4/`。

## Phase 5 用法（2026-10-05）

```text
/wg init                         # 只初始化玩家目前維度
/wg init --world world --dimension minecraft:the_nether  # console 的明確目標
/wg status --dimension minecraft:the_nether --full
/wg commit --all -m 各維度存檔      # 目標旗標放在 greedy 訊息前
/wg log --graph --all             # 各維度、全部 refs，非原子批次
/wg log --graph --page 2
/wg ignore                       # 54 格箱子，每頁 45 行
/wg ignore add entity minecraft:cow
/wg ignore confirm <預覽代碼>      # 或點選聊天確認按鈕
/wg commit -m 更新排除規則
/wg ignore test target           # 準星方塊／實體；test hand 測手持物品欄位
/wg ignore move 8 3
```

`--world` 用載入中的 Bukkit 世界名稱（可補全）；`--dimension` 用 namespaced ID（可補全），不能與 `--all` 同時使用。目標旗標可在一般參數前後；commit／stash／PR／留言的 greedy 文字尾端保持文字語意，應將目標旗標放在文字前。graph 每頁預設 10 列，可指定 1–100；branch／tag／HEAD 有色彩、ASCII lane 用 uniform font，hover 保留完整 hash／父節點／作者／時間／snapshot。

ignore 左鍵刪除、右鍵啟停、Shift 點擊上移，新增按鈕轉為聊天輸入。任何修改先顯示 HEAD 的排除數量與樣本；120 秒內確認綁定執行者、世界群、維度、原規則與 HEAD，再驗權限和 MERGING。規則 ID 是檔案行號；取消可不確認，新的 preview 取代舊的。同次確認只寫 `.wgignore`，下一 commit 才寫歷史。預覽只計算 HEAD 已追蹤內容，不代表未提交世界的完整掃描。

新 creative repo 使用 `entities: player-touched`，自然牛等不入庫；生怪蛋、summon／data entity、放置、命名／拴繩／馴服／裝備／染色／繁殖／騎乘、展示框互動與 WE／FAWE 建立實體都記錄 UUID 閉包。init 前觸及在本次伺服器執行期間暫存，init 時繼承；UUID 轉換／跨維度傳送繼承資格。sidecar 由 repo queue 串行寫入，restore／merge 後更新事件資格。survival 與已有 repo 的 entities 設定保持原值。

每個正式動作有 SUCCESS／NO_OP／PARTIAL／FAILED／CANCELLED 終態，含目標、短 hash／分支、變動或傳輸摘要、耗時。耗時操作 BossBar 給執行者和 `worldgit.notify` 觀察者；console 每秒最多一行進度。成功綠／失敗紅終態預設停留 3 秒；`feedback.title`、`sound` 預設關，`auto-notify` 可關閉授權玩家的自動完成通知，console 保留結果。錯誤後的獨立 `[複製]` 保存版本、UTC、維度與 operation id，遮罩已知 PAT 和敏感欄位，不更動原文字 click。

```yaml
aliases:
  git: true
  wgit: true
feedback:
  terminal-seconds: 3
  title: false
  sound: false
  auto-notify: true
```

所有設定需重啟；`/wg reload` 只重載語言。線上 clone／export／migrate 沒有入口，沿用 CLI 的停止世界要求。第三方直接寫 NMS 或未經 WE／FAWE hook 的 UUID 改寫不屬於可攔截事件；無法把任意第三方寫入推測成玩家觸及。

驗收腳本自持 bench.lock：`python3 paper/tools/phase5.py --matrix` 跑 Paper／Folia 兩版及別名衝突／停用；`python3 paper/tools/phase5_screenshots.py paper 1.21.11`（亦可指定其他組合）用真客戶端拍攝八種畫面。證據與限制見 [Phase 5 設計紀錄](../docs/16-phase5-design.md)，截圖在 [phase5](docs/screenshots/phase5/)。
