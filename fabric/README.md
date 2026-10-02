# WorldGit Fabric（Phase 2）

同一套模組提供單人世界／Fabric 專用伺服器的存檔點，以及 Paper 玩家客戶端的 diff 描邊和鬼影。世界與 bare repo 格式直接共用 core，離線 `wgit` 可讀相同歷史；不需要轉換。

## 安裝與建置

| Minecraft | Java | Loader | Fabric API | Adventure Fabric | Loom |
|---|---|---|---|---|---|
| 1.21.11 | 21 | 0.19.5 | 0.141.6+1.21.11 | 6.8.0 | 1.17.21（remap） |
| 26.2 | 25 | 0.19.5 | 0.161.0+26.2 | 7.1.1 | 1.17.21（不混淆） |

沿用 Phase 0 已驗證的 Loader／API／Loom，未另行升級。兩版 Adventure 發行線分別搭配 Adventure 4.25／5.2；共用程式碼只使用兩邊共有的 API，已經兩版編譯及實機載入驗證。版本以根目錄 `gradle/libs.versions.toml` 為準。

安裝對應版本的 Fabric Loader、Fabric API 與下列 jar；WorldGit 已內嵌 core、platform-api、protocol、i18n、JGit 等必要依賴及 Adventure 平台。客戶端和專用伺服器使用同一個版本的 jar。

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export GRADLE_USER_HOME="$PWD/.work/gradle-home"
flock .work/bench.lock ./gradlew --configure-on-demand --max-workers=1 \
  :core:test :fabric:logic:test :fabric:mc1_21_11:build :fabric:mc26_2:build
```

產物：

- `fabric/mc1_21_11/build/libs/worldgit-fabric-1.21.11-0.1.0-SNAPSHOT.jar`（Java 21，remap 至 intermediary）。
- `fabric/mc26_2/build/libs/worldgit-fabric-26.2-0.1.0-SNAPSHOT.jar`（Java 25，不做 remap）。

連到 Paper 只需玩家安裝 Fabric 模組；伺服器裝 WorldGit Paper 插件。連到沒裝 WorldGit 的伺服器時不會產生預覽。

## 世界操作

```text
/wg init [--template creative|survival] [--track all|modified-only] [--dimension minecraft:overworld]
/wg status [--full] [--blocks] [--show]
/wg commit -m 建造城門
/wg log [1–50]
/wg diff [from [to]] [--blocks] [--show]
/wg preview <rev> [--radius r]
/wg preview off
/wg restore <rev> [--chunks r | --box x1 y1 z1 x2 y2 z2] [--dry-run]
/wg switch <branch|rev> [--stash|--force] [--dry-run]
/wg branch [create] <name> [rev]
/wg branch [list] | branch delete <name>
/wg stash push [message] | stash pop [index] | stash list | stash drop [index]
/wg reset --hard [rev --force] [--dry-run]
/wg cancel
/wg clear
/wg info
/wg reload
```

`status --show` 畫 section／chunk 外框，`diff --show`／`preview` 畫逐格外框與半透明方塊模型。顯示與 clear 需玩家執行；console 可用 init/status/commit/log。指令詳細選項及權限見 `WgCommands`；預設讀取權限等級 0、寫入等級 2，單人世界擁有者可操作。

每個維度一個 repo，路徑為世界資料夾旁的 `.worldgit/<世界名稱>/<維度目錄>/`，例如 `.worldgit/My World/minecraft.overworld/`。主世界保存 world-meta 與維度清單。init 只建立磁碟上存在且尚未初始化的維度；第一次進入新維度後可再 init。

預設 creative 範本全部追蹤；survival 範本排除暫態、非 persistent 生物等。`track: modified-only` 與 core 一致，目前只記錄設定，尚未篩掉自然地形。沒有內容變動不產生 commit。

自動 commit 包含定時、專用伺服器玩家登出、關機／離開單人世界。玩家放置／破壞事件記錄 chunk 粒度歸屬，多人參與寫入 Contribution trailers；只有一位參與者時自動提交以該玩家為 author，committer 為伺服器。多人登出目前提交共同工作世界，尚無 per-player staging。

### 預覽、切換與復原

`preview` 只顯示「目前世界 → 目標 commit」的差異；新增／修改顯示目標模型、移除顯示目前模型，沿用綠／紅／黃與實線／鬼影／虛線。`--radius` 是玩家所在 chunk 的正方形半徑（0–256、含端點），未指定時比較該維度全部追蹤 chunk；超過鬼影上限改區域外框。`preview off`／`clear` 清除，連到支援此命令的 Paper／Fabric 伺服器時使用相同 v2 封包；客戶端即使連到舊伺服器也能先清掉本機預覽。

寫入命令目前只供**單人世界的整合伺服器**使用；專用伺服器保留 Phase 1 操作與 revision preview。`restore` 不移動 HEAD，chunk 半徑以玩家為中心，box 包含端點並逐格裁切方塊／BE，biome 以 4×4×4 sample 起點裁切。`switch` 同步全維度同名分支，hash 為 detached HEAD；dirty 工作區需 commit、`--stash` 或 `--force`。stash pop 要求原基底及乾淨工作區，不做跨分支合併。`reset --hard` 無 revision 只丟棄未提交變動；指定 revision 會改寫歷史且要求 `--force`。

套用期間顯示 bossbar，暫停世界 tick、關閉容器、攔截玩家物品／容器／實體互動及一般 LevelChunk 方塊寫入；不移動玩家、不加藥水效果。範圍內玩家（含中途進入者）在操作全程及結束後 10 秒免受摔落、窒息、溺水傷害，其他傷害照常。原本的 frozen 狀態會恢復，第三方模組若直接改 section／BE 或實體需配合 `ServerRuntime.editsLocked()`，不能繞過鎖寫入。

所有套用、heightmap／光照／POI 與 chunk 更新在 server owner 執行；未載入 chunk 加 ticket 等 entity IO，不寫線上 `.mca`。使用共用 ApplyBudget（單人有玩家：4 section／5 ms／16 chunk），不可搶占的單次工作採軟時間上限；全維度 UUID 先移除再生成。完成後 flush、全組驗證才更新 HEAD，並清除舊 status／diff／preview。`cancel` 等在途清理，已寫入的世界保留 PARTIAL、HEAD 不動；用全範圍 `switch <rev> --force`／`reset --hard` 恢復，PARTIAL 阻擋新 commit／普通 switch／stash。

## Phase 3：合併與衝突解決（單人世界）

```text
/wg merge <分支|commit> [--no-commit] [--strategy-option ours|theirs] [--distance 0-16]
/wg merge --abort | --continue
/wg resolve <id|all> [--ours|--theirs|--base|--manual]
/wg revert <commit> | /wg cherry-pick <commit>
/wg conflicts [--show] [--teleport <id>]
```

僅單人世界的整合伺服器可執行寫入（與 Phase 2 相同）。要求工作區乾淨；不同位置的修改零介入合併，成功時直接建立兩個 parent 的 merge commit。有衝突時進入 MERGING：無衝突部分與每個衝突區域的 ours 一起寫入，`/wg status` 顯示剩餘區域，自動 commit 暫停。切換或解決區域時**維持快照儲存的方塊 state**，不觸發 `updateShape`／鄰居更新（決定 #46）；交界提示只列出供檢查。全部解決後執行 `/wg merge --continue`（或 `/wg commit -m …`）；`/wg merge --abort` 逐格回到合併前。

**衝突清單畫面**：按 `G`（原版「按鍵設定 → WorldGit」可改）或 `/wg conflicts`。選取區域後：

- Ghost ours／theirs／base：只在客戶端畫半透明疊圖（紫色外框＋目標模型），不改世界，關閉畫面後仍保留，可切換；`hideConflictPreview` 或解決後清除。
- Set blocks ours／theirs／base：把世界中該區域真的換成該版本（不標解決）。
- Resolve ours／theirs／base／manual：切換並標為已解決；manual 以目前世界為準（先自己動手改）。
- Teleport：單人世界直接傳送到區域上方；連 Paper 送 `execute in <維度> run tp`。

未解決區域的紫色外框常駐顯示，已解決改暗灰，全部解決後消失。紅石區域標示「請測試電路」，交界提示（滑過按鈕）列出可能受影響的鄰格。

連 Paper 時，客戶端握手宣告 `merge-regions-v1`，由 Paper 推送 `worldgit:conflicts` 並以 `worldgit:conflict_preview` 回應疊圖請求；寫入按鈕只用 Paper 支援的 `wg resolve <id> <choice>`。舊伺服器（沒有此能力）時畫面顯示不支援。協定見 [protocol README](../protocol/README.md)，驗收見 [進度 13](../docs/13-phase3-progress.md) 的 Fabric 章節。

驗收（兩版各約 11–17 分鐘）：

```bash
WG_PHASE3=1 ALSOFT_DRIVERS=null fabric/tools/run-gametest.sh 1.21.11 --record
WG_PHASE3=1 ALSOFT_DRIVERS=null fabric/tools/run-gametest.sh 26.2 --record
```

## 設定與多語言

首次啟動寫入附註解的 YAML：

- `config/worldgit-server.yml`：console／提交訊息語言、預設範本、指令權限、自動提交、伺服器身分、預覽上限與每 tick 送包配額。
- `config/worldgit-client.yml`：`palette: auto|default|colorblind`、穿牆、明細距離（48 格）、最大距離（384 格）、明細 section 上限（192）、每幀建置配額（4）。
- 世界旁的 `.worldgit/<世界>/worldgit.yml`：與 CLI 共用的 repo 本機設定，例如實體容許距離和伺服器色票。

```text
/wgc palette auto|default|colorblind
/wgc seethrough true|false
/wgc status
/wgc reload
/wgc clear
```

`auto` 採用伺服器 hello 的色票。色票及穿牆切換會釋放舊 GPU 網格，下幀依新設定重建；`/wgc clear` 清除本機場景。客戶端操作不改動世界。

依玩家的 Minecraft 語言顯示 `en_us`／`zh_tw`，其他語言退回 `en_us`；console 與 git 提交訊息採 server YAML 的語言。`/wgc` 的色票名稱、開關與握手狀態也走相同訊息表。管理者可在 `config/worldgit/lang/<locale>.yml` 覆寫部分 MiniMessage 字串，使用 `/wg reload` 或 `/wgc reload` 重讀。參數以純文字插入，diff 自訂標籤 `<wg_added>`／`<wg_removed>`／`<wg_modified>`／`<wg_conflict>` 取自 protocol 共用色票。

## 執行緒與渲染

`shared` 包含伺服端與客戶端實作，`logic` 放不依賴 Minecraft 的邏輯和 JUnit 測試；兩個版本只有 `Platform` 與 `client/ClientPlatform` 薄轉接層。1.21.11 使用 Mojang mappings 和 remap Loom；26.2 的 payload registry、client command 名稱、GPU draw/vertex binding 和反向深度在 adapter 處理。

dirty 候選是載入事件、`LevelChunk.markUnsaved` mixin 保留的 generation、玩家事件、原版 unsaved 旗標、含實體的 chunk，以及磁碟 index 的聯集。成功 commit 才有條件 acknowledge；status 或原版自動存檔不清除 WorldGit generation。runtime 在世界載入前建立，因此初始 spawn chunks 也會被追蹤。

已載入 chunk 在伺服器執行緒用遊戲序列化器複製，正規化與 repo IO 在背景執行；未載入 chunk 讀磁碟。手動 commit 與關機前會 flush。WorldGit 自有 server task 佇列避免原版在關閉時把 `server.execute` 工作直接放到 repo 執行緒執行。

v2 握手後才接受預覽；分包由 BatchAssembler 收齊再發布。斷線清除場景，每個維度各有預覽。新增為實線加目標模型鬼影、移除為原版模型半透明鬼影、修改為虛線加淡色目標模型、衝突以 alpha 緩慢閃爍。遠處／超出明細預算的 section 只畫包圍盒；視錐及距離裁切，每幀最多建立設定配額的 section 明細，避免一次上傳全部格數。超過伺服器 100,000 格上限時傳 status 包圍盒。

## 驗收

完整結果、失敗紀錄與限制見 [Phase 1 進度](../docs/11-phase1-progress.md)；精選真正 framebuffer 截圖見 [screenshots](docs/screenshots/README.md)。

單人世界以 Xvfb／llvmpipe 啟動真正 Minecraft client，建立世界、init、三格變動（同一 section 的 +1/-1/~1）、強制存檔後再 status、diff、色盲／穿牆、commit、四種類型合成封包、LOD、clear 與繁中訊息。合成封包只驗渲染，另有真實世界的 diff；兩者明確分開記錄。

```bash
fabric/tools/run-gametest.sh 1.21.11 > .work/fabric-gametest-1.21.11.log 2>&1
fabric/tools/run-gametest.sh 26.2 > .work/fabric-gametest-26.2.log 2>&1
# 上一個指令成功退出後，再以 CLI 驗證並保存世界／repo／截圖。
python3 fabric/tools/record-singleplayer.py 1.21.11 .work/fabric-gametest-1.21.11.log
python3 fabric/tools/record-singleplayer.py 26.2 .work/fabric-gametest-26.2.log
```

Phase 2 重跑（先建置 `:cli:fatJar`，同樣自行取得 bench.lock）：

```bash
WG_PHASE2=1 ALSOFT_DRIVERS=null fabric/tools/run-gametest.sh 1.21.11 --record
WG_PHASE2=1 ALSOFT_DRIVERS=null fabric/tools/run-gametest.sh 26.2 --record
```

GameTest 檢查真正客戶端的鬼影格子、方塊同步、玩家保護、光照、實體、裁切、stash 與取消／恢復，並保留四個 flush 後的世界／repo 複本。`record-phase2.py` 使用獨立 CLI jar 對預覽前／後 B、switch A、恢復 A 跑離線 verify，並逐格比較 `wgit diff B A --blocks` 與客戶端預覽；任何不符即非零退出。結果在 `.work/fabric-acceptance/phase2-*/result.json`，精選截圖在 `fabric/docs/screenshots/phase2/`；詳細證據見 [Phase 2 進度](../docs/12-phase2-progress.md)。

先建置 `:cli:fatJar`，或以 `WGIT_JAR`／`--cli-jar` 指定固定的 CLI jar。CLI 比較 init→手動提交的明確 commit id，避免把後來移動相機造成的探索／關機自動 commit 混入三格驗收。驗收世界關閉隨機刻與生物生成，避免草地腐化影響固定格數。

Paper 驗收先複製插件 jar 到 `.work/fabric-acceptance/`，記錄來源 mtime（UTC／ns）、大小和 SHA-256，整輪只使用副本。腳本使用 `.work/servers/paper-<版本>/server.jar` 的複本，port 25663／25664、127.0.0.1、offline-mode；伺服器與世界都在 `.work/`。它先準備存檔再載入插件，讓真客戶端握手、收三格 diff／status、截圖與 clear，finally 關閉程序和確認 port 已關閉。

```bash
python3 fabric/tools/accept-paper.py 1.21.11
python3 fabric/tools/accept-paper.py 26.2
```

以上三個驗收腳本都自行取得 `bench.lock`，不要再包外層 flock。並行開發時可用 `GRADLE_ROOT`（gametest）或 `--gradle-root`（Paper）指定同步過共用模組的私有建置根。專用 Fabric 伺服器兩版的已完成驗收沿用交接紀錄；此次增加 mixin 的載入檢查另記於進度。

## 目前限制

線上不刪除 chunk：stash 若需刪除 HEAD 沒有的新增 chunk，預檢會拒絕，須關閉世界後使用 CLI stash；一般 switch 預設保留這些 chunk 並標 untracked。線上 metadata 目前只接出生點、1.21.11 的 gamerules／難度／邊界等 level.dat 設定，地圖／scoreboard／26.2 各維度 saved-data、世界生成等變動會在任何寫入前拒絕，須離線還原。跨 DataVersion、規則不同仍明確拒絕；沒有 DataFixer；單人合併流程見上方 Phase 3 章節。legacy `ChunkPatch` 套用入口仍拒絕，正式 Phase 2 使用 ApplyPlan。

尚無準星「舊→新」UI、實體／biome 模型、流體或特殊 block entity renderer、Mod Menu 畫面、資源包重載後模型快取重建、Sodium／Iris 或硬體 GPU 驗收。鬼影使用固定光照與 quad 順序，沒有透明面排序／內部面消除；既有大量格數驗收是 3,072 格、6 sections，不能據此宣稱 100,000 格效能。Phase 2 使用受控平坦世界及凍結 tick，不是大型自然生物世界的 TPS 量測。

## 單人世界局部區域切換（2026-10-02）

`conflict-select`／`resolve` 經共用 core 的局部 source、精確 atoms mask、完整受影響 chunk 驗證及增量 MERGING journal。Fabric 在 server owner 以 vanilla ChunkMap serializer、entity storage 與 POI flush 只排入指定 chunk，三種 storage 分別保存（terrain 卸載不代表 entity／POI 已卸載），保留光照／chunk 封包／IO barrier；不寫使用中的 `.mca`。一般區域的 LevelChunk 寫入鎖限受影響 chunk；短暫 tick freeze 及容器／指令屏障保留，防止 vanilla 或跨位置編輯穿越操作。continue／commit 再全組 capture／驗證，abort 保留完整恢復。

`merge-state.bin.updates` 須與基底一起讀取／保存，當機恢復仍用 abort。區域外其他 chunk 的 manual 編輯在 continue 捕捉；其交界提示於 continue 完整重算。全域 IO queue 積壓與 UUID 定位仍可能增加延遲；大型自然世界及第三方忽略鎖的寫入不在本次效能保證內。

`WG_PHASE3=1 JAVA_TOOL_OPTIONS=-Dworldgit.profile=true ALSOFT_DRIVERS=null fabric/tools/run-gametest.sh <版本> --record` 會保留 UI 命令→完成的逐次延遲與 core 分段計時（result.json），同時執行原 Phase 3 客戶端驗收。根因與兩版數字見 [docs/13 區域切換延遲](../docs/13-phase3-progress.md#區域切換延遲)。
