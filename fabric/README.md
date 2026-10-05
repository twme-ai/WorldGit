# WorldGit Fabric（Phase 4）

同一套模組提供單人世界／Fabric 專用伺服器的存檔點、復原、切換與合併，遠端 push／pull／PR／座標留言，以及 Paper／Folia 玩家客戶端的 diff 描邊和鬼影。世界與 bare repo 格式直接共用 core，離線 `wgit` 可讀相同歷史；不需要轉換。

Phase 5 任務 1 相容更新：單人與 dedicated 的主世界 repo 都在 `<world>/.worldgit/`，其他維度放各自資料目錄 `.worldgit/`；舊位置可讀並由離線 `wgit migrate` 搬移。玩家的切換／合併／remote／傳輸只作用於目前維度，console 暫用主世界。init 暫保留既有批次入口，creative 使用 `entities: all`，玩家觸及事件尚未接線。所在維度 init／追加詢問、別名、graph／ignore 畫面、完整進度／完成結果／錯誤複製及實體事件由任務 4 實作；下文 Phase 4 語意中有衝突的部分以 [Phase 5 設計](../docs/16-phase5-design.md) 為準。

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

`status --show` 畫 section／chunk 外框，`diff --show`／`preview` 畫逐格外框與半透明方塊模型。顯示與 clear 需玩家執行；console 可執行讀取、寫入與合併命令。指令詳細選項及權限見 `WgCommands`；預設讀取權限等級 0、寫入等級 2，單人世界擁有者可操作。

每個維度一個 repo，主世界保存 world-meta 與維度清單。主世界 repo 在世界根 `.worldgit/`，其他維度在自己的 `DIM-1/`、`DIM1/` 或 `dimensions/<ns>/<path>/` 內；新單人、dedicated 與 CLI clone 使用相同規則。init 只建立磁碟上存在且尚未初始化的維度；第一次進入新維度後可再 init。

預設 creative 範本全部追蹤；survival 範本排除暫態、非 persistent 生物等。`track: modified-only` 與 core 一致，目前只記錄設定，尚未篩掉自然地形。沒有內容變動不產生 commit。

自動 commit 包含定時、專用伺服器玩家登出、關機／離開單人世界。玩家放置／破壞事件記錄 chunk 粒度歸屬，多人參與寫入 Contribution trailers；只有一位參與者時自動提交以該玩家為 author，committer 為伺服器。多人登出目前提交共同工作世界，尚無 per-player staging。

### 預覽、切換與復原

`preview` 只顯示「目前世界 → 目標 commit」的差異；新增／修改顯示目標模型、移除顯示目前模型，沿用綠／紅／黃與實線／鬼影／虛線。`--radius` 是玩家所在 chunk 的正方形半徑（0–256、含端點），未指定時比較該維度全部追蹤 chunk；超過鬼影上限改區域外框。`preview off`／`clear` 清除，連到支援此命令的 Paper／Fabric 伺服器時使用相同 v2 封包；客戶端即使連到舊伺服器也能先清掉本機預覽。

寫入命令同時支援單人整合伺服器與 Fabric 專用伺服器，沿用寫入 op 等級（預設 2）；console 的局部 restore 使用命令來源的維度／座標。`restore` 不移動 HEAD，chunk 半徑以玩家為中心，box 包含端點並逐格裁切方塊／BE，biome 以 4×4×4 sample 起點裁切。`switch` 只切選定維度，hash 為 detached HEAD；dirty 工作區需 commit、`--stash` 或 `--force`。stash pop 要求原基底及乾淨工作區，不做跨分支合併。`reset --hard` 無 revision 只丟棄未提交變動；指定 revision 會改寫歷史且要求 `--force`。

套用期間顯示 bossbar，暫停世界 tick、關閉容器、攔截玩家物品／容器／實體互動及一般 LevelChunk 方塊寫入；不移動玩家、不加藥水效果。範圍內玩家（含中途進入者）在操作全程及結束後 10 秒免受摔落、窒息、溺水傷害，其他傷害照常。原本的 frozen 狀態會恢復，第三方模組若直接改 section／BE 或實體需配合 `ServerRuntime.editsLocked()`，不能繞過鎖寫入。

所有套用、heightmap／光照／POI 與 chunk 更新在 server owner 執行；未載入 chunk 加 ticket 等 entity IO，不寫線上 `.mca`。使用共用 ApplyBudget（有玩家：4 section／5 ms／16 chunk；無玩家：8／5 ms／24 chunk），不可搶占的單次工作採軟時間上限；全維度 UUID 先移除再生成。完成後 flush、全組驗證才更新 HEAD，並清除舊 status／diff／preview。`cancel` 等在途清理，已寫入的世界保留 PARTIAL、HEAD 不動；用全範圍 `switch <rev> --force`／`reset --hard` 恢復，PARTIAL 阻擋新 commit／普通 switch／stash。

## Phase 3：合併與衝突解決

```text
/wg merge <分支|commit> [--no-commit] [--strategy-option ours|theirs] [--distance 0-16]
/wg merge --abort | --continue
/wg resolve <id|all> ours|theirs|base|manual  （也接受舊 --ours 等旗標）
/wg revert <commit> | /wg cherry-pick <commit>
/wg conflicts [--show] [--teleport <id>]
/wg conflict-preview <id> ours|theirs|base
/wg conflict-select <id|all> ours|theirs|base|manual
```

單人世界與專用伺服器共用相同流程。要求工作區乾淨；不同位置的修改零介入合併，成功時直接建立兩個 parent 的 merge commit。有衝突時進入 MERGING：無衝突部分與每個衝突區域的 ours 一起寫入，`/wg status` 顯示剩餘區域，自動 commit 暫停。切換或解決區域時**維持快照儲存的方塊 state**，不觸發 `updateShape`／鄰居更新（決定 #46）；交界提示只列出供檢查。全部解決後執行 `/wg merge --continue`（或 `/wg commit -m …`）；`/wg merge --abort` 逐格回到合併前。

**衝突清單畫面**：按 `G`（原版「按鍵設定 → WorldGit」可改）或 `/wg conflicts`。選取區域後：

- Ghost ours／theirs／base：只在客戶端畫半透明疊圖（紫色外框＋目標模型），不改世界，關閉畫面後仍保留，可切換；`hideConflictPreview` 或解決後清除。
- Set blocks ours／theirs／base：把世界中該區域真的換成該版本（不標解決）。
- Resolve ours／theirs／base／manual：切換並標為已解決；manual 以目前世界為準（先自己動手改）。
- Teleport：單人世界直接傳送到區域上方；連 Paper／Folia／Fabric 專用伺服器送 `execute in <維度> run tp`，需要對應的原版命令權限。

未解決區域的紫色外框常駐顯示，已解決改暗灰，全部解決後消失。紅石區域標示「請測試電路」，交界提示（滑過按鈕）列出可能受影響的鄰格。

連 Paper／Folia／Fabric 專用伺服器時，客戶端握手宣告 `merge-regions-v1`，由伺服器推送 `worldgit:conflicts` 並以 `worldgit:conflict_preview` 回應疊圖請求；hello 同時公告 `conflict-select-v1` 時啟用 Set blocks，送 `wg conflict-select <id> <choice>`；Resolve 送 `wg resolve <id> <choice>`。舊 Paper 沒有 select 能力時 Set blocks 停用，滑過顯示原因，Ghost／Resolve 仍可使用；沒有 `merge-regions-v1` 時畫面顯示不支援合併。協定見 [protocol README](../protocol/README.md)，真客戶端驗收見 [Paper／Folia 對接進度](../docs/13-phase3-progress.md#fabric--paper-實機對接)與 [Fabric 專用伺服器進度](../docs/13-phase3-progress.md#fabric-專用伺服器)。

驗收（兩版各約 11–17 分鐘）：

```bash
WG_PHASE3=1 ALSOFT_DRIVERS=null fabric/tools/run-gametest.sh 1.21.11 --record
WG_PHASE3=1 ALSOFT_DRIVERS=null fabric/tools/run-gametest.sh 26.2 --record
```

## 設定與多語言

首次啟動寫入附註解的 YAML：

- `config/worldgit-server.yml`：console／提交訊息語言、預設範本、指令權限、自動提交、伺服器身分、預覽上限與每 tick 送包配額。
- `config/worldgit-client.yml`：`palette: auto|default|colorblind`、穿牆、明細距離（48 格）、最大距離（384 格）、明細 section 上限（192）、每幀建置配額（4）。
- 各維度 repo 內的 `worldgit.yml`：與 CLI 共用的本機設定，例如實體容許距離和伺服器色票；平台目前由主世界讀取，完整逐維度設定 UX 待任務 4。

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

以上驗收腳本都自行取得 `bench.lock`，不要再包外層 flock。並行開發時可用 `GRADLE_ROOT`（gametest）或 `--gradle-root`（Paper）指定同步過共用模組的私有建置根。Fabric 專用伺服器的寫入與合併驗收見下方章節。

## 目前限制

線上不刪除 chunk：stash 若需刪除 HEAD 沒有的新增 chunk，預檢會拒絕，須關閉世界後使用 CLI stash；一般 switch 預設保留這些 chunk 並標 untracked。線上 metadata 目前只接出生點、1.21.11 的 gamerules／難度／邊界等 level.dat 設定，地圖／scoreboard／26.2 各維度 saved-data、世界生成等變動會在任何寫入前拒絕，須離線還原。跨 DataVersion、規則不同仍明確拒絕；沒有 DataFixer；合併流程見上方 Phase 3 章節。legacy `ChunkPatch` 套用入口仍拒絕，正式 Phase 2 使用 ApplyPlan。

尚無準星「舊→新」UI、實體／biome 模型、流體或特殊 block entity renderer、Mod Menu 畫面、資源包重載後模型快取重建、Sodium／Iris 或硬體 GPU 驗收。鬼影使用固定光照與 quad 順序，沒有透明面排序／內部面消除；既有大量格數驗收是 3,072 格、6 sections，不能據此宣稱 100,000 格效能。Phase 2 使用受控平坦世界及凍結 tick，不是大型自然生物世界的 TPS 量測。

## 局部區域切換（2026-10-02）

`conflict-select`／`resolve` 經共用 core 的局部 source、精確 atoms mask、完整受影響 chunk 驗證及增量 MERGING journal。Fabric 在 server owner 以 vanilla ChunkMap serializer、entity storage 與 POI flush 只排入指定 chunk，三種 storage 分別保存（terrain 卸載不代表 entity／POI 已卸載），保留光照／chunk 封包／IO barrier；不寫使用中的 `.mca`。一般區域的 LevelChunk 寫入鎖限受影響 chunk；短暫 tick freeze 及容器／指令屏障保留，防止 vanilla 或跨位置編輯穿越操作。continue／commit 再全組 capture／驗證，abort 保留完整恢復。

`merge-state.bin.updates` 須與基底一起讀取／保存，當機恢復仍用 abort。區域外其他 chunk 的 manual 編輯在 continue 捕捉；其交界提示於 continue 完整重算。全域 IO queue 積壓與 UUID 定位仍可能增加延遲；大型自然世界及第三方忽略鎖的寫入不在本次效能保證內。

`WG_PHASE3=1 JAVA_TOOL_OPTIONS=-Dworldgit.profile=true ALSOFT_DRIVERS=null fabric/tools/run-gametest.sh <版本> --record` 會保留 UI 命令→完成的逐次延遲與 core 分段計時（result.json），同時執行原 Phase 3 客戶端驗收。根因與兩版數字見 [docs/13 區域切換延遲](../docs/13-phase3-progress.md#區域切換延遲)。

## Fabric ↔ Paper／Folia Phase 3 實機對接

```bash
python3 fabric/tools/accept-paper-phase3.py paper 1.21.11
python3 fabric/tools/accept-paper-phase3.py paper 26.2
python3 fabric/tools/accept-paper-phase3.py folia 1.21.11
python3 fabric/tools/accept-paper-phase3.py folia 26.2
```

自行取 bench.lock，不加外層 flock。先建置 plugin 與 CLI fat jar；腳本凍結 jar、複製 server 與乾淨平坦 fixture，伺服器 port 25701–25704，客戶端 TCP 入口 25711–25714，透過 Xvfb／llvmpipe 啟動真 Fabric client。客戶端點擊 Ghost／Set blocks／Resolve／Teleport，與伺服器 durable 清單、候選 BE 與完整 section state 比對；200 區域清單必須實際分片且重連後相同，continue 清空。最後停止 client／Xvfb／server、完整離線 verify，刪除 server/world 副本。結果、control 檢查點、原始 log、四張截圖在 `.work/fabric-acceptance/pair-*/`；結果與精選截圖見 [docs/13](../docs/13-phase3-progress.md#fabric--paper-實機對接)。

## Fabric 專用伺服器寫入與合併

專用伺服器使用相同 live coordinator，不直接改寫使用中的 Anvil 檔。套用期間有全組／局部 chunk 編輯鎖、vanilla tick freeze、玩家保護與 bossbar；完成驗證後廣播 MiniMessage。活塞、爆炸與肥料的批量變更在受鎖維度開始前整體攔截，避免部分寫入；玩家容器／互動與操作期間非 WorldGit 命令採保守屏障。MERGING 閒置時可手動編輯，manual resolve 以目前世界為準。

連線的 Fabric 客戶端可使用清單、Ghost、Set blocks、Resolve 與傳送。伺服器公告 merge／select 能力，握手後推送持久化清單；多位檢視者在選擇／解決後同步更新。重啟恢復 MERGING；登出、定時與關機的自動 commit 均依 #60 跳過，手動 commit 等同 continue。

```bash
python3 fabric/tools/accept-dedicated.py 1.21.11
python3 fabric/tools/accept-dedicated.py 26.2
```

腳本自行取得 bench.lock，不套外層 flock；使用伺服器 25701／25702、client TCP 入口 25711／25712、真 Fabric client／Xvfb 與第二個協定 viewer。涵蓋 Phase 2／Phase 3、重啟、批量編輯鎖與玩家保護、1,000 chunk merge 的 TPS、6 次區域切換延遲及真正 framebuffer 截圖。fixture 是另外打包的測試 mod，正式 jar 不含測試指令。原始結果與失敗保存在 `.work/fabric-acceptance/dedicated-*/`；完成後清除 server 副本並確認 port 關閉。實測結果見 [Fabric 專用伺服器進度](../docs/13-phase3-progress.md#fabric-專用伺服器)。

2026-10-03 兩版各 44 項通過、最終離線 verify=0；區域切換中位數為 0.701／0.702 秒，1,000 chunk 合併平均 TPS 約 20，最大 tick 間隔約 1.6–1.7 秒。兩版單人 Phase 2／Phase 3 與四組 Paper／Folia Phase 3 回歸全過，最後完整 build 通過（199 個單元測試，0 failure／error／skip）。八張原始畫面見 [專用伺服器截圖](docs/screenshots/dedicated/README.md)。量測使用受控平坦世界與凍結世界 tick，實際範圍及限制見進度報告。

沿用 Phase 1 的本機驗收環境：`.work/fabric-srv/<版本>/` 需有 Fabric Launcher、libraries、versions 與 fabric-api.jar；平坦 fixture 沿用 `.work/paper-delivery/fixtures/acceptance-flat-<版本>/`，缺少時由既有 `.work/worlds/<版本>/baseline/` 產生副本。26.2 另需已驗收 Fabric baseline 的 `world/data/minecraft/world_gen_settings.dat`；只複製到測試副本的根目錄，規則使用平坦 fixture 的安靜設定，以適配 Paper／vanilla saved-data 路徑差異。腳本自行建置並凍結正式模組、fixture 與 CLI，不載入既有伺服器的世界或 repo。

## Phase 4 遠端協作

單人與專用伺服器使用相同指令；單人 owner 不需開作弊，dedicated 沿既有 op 等級，console 可執行。讀取類是 remote list、fetch、pr list/view、comments；remote 設定、push/pull、pr create、comment 使用寫入等級。

```text
/wg remote add <name> <url>
/wg remote remove <name>
/wg remote list
/wg remote set-url <name> <url>
/wg fetch [remote]
/wg push [remote [branch]] [--tags]
/wg pull [remote [branch]]
/wg pull confirm <code>
/wg pr create <title> [--source branch] [--target branch]
/wg pr list
/wg pr view <number>
/wg comments [show|hide] [pr <number>] [--here]
/wg comment <number> <text> [--here]
```

push 只接受 FF。pull 先 fetch、沿線上 dry-run 預覽 FF／三方、區域／chunk 數與估計；120 秒內以 sender 綁定的一次性 code 確認。確認重新 fetch，遠端 URL／完整 tips／本地 HEAD／本地分支改變就要求重做。套用沿既有 live coordinator、ApplyBudget、編輯鎖、玩家保護與廣播；衝突進 MERGING，沿原本 G 衝突清單／resolve／continue，不做鄰居更新。PR 附可點擊 Hub 連結；merge／approve 在網頁進行。

`config/worldgit-server.yml` 加入下列區段（其他既有設定保留）；兩種伺服器皆由 Fabric config 目錄讀取。remote 名稱／URL 等非秘密設定由 core 寫入各維度 repo 的 `remotes.yml`。單人每個存檔／維度各自 remote，PAT 不跟著存檔／clone 移動。

```yaml
remote:
  hub-url: ''
  default-name: origin
  token-environment: WGIT_TOKEN
  credentials-file: credentials.yml
  timeout-seconds: 30
  fetch-interval-seconds: 0
  webhook:
    enabled: false
    bind: 127.0.0.1
    port: 25761
    secret-environment: WGIT_WEBHOOK_SECRET
    secret-file: webhook.secret
```

PAT 來源優先環境 `WGIT_TOKEN`，或 Fabric config 下普通檔案 `credentials.yml`（POSIX 權限必須 600；拒絕 symlink／越界路徑），格式共用 [platform-api](../platform-api/README.md)：

```yaml
credentials:
  https://hub.example.com:
    mode: bearer
    token: YOUR_PAT
```

不要把 PAT 寫入 world、remote URL 或一般設定。錯誤只回固定 i18n，沒有輸出 HTTP body／exception stack／憑證。REST／Git 網路工作在背景 repo executor，回覆回 server executor；401／403／404／409／429、逾時與不可達都有專用訊息。

專用伺服器 webhook 預設關閉／loopback，HMAC、重放、body／速率／deadline 上限沿共用 WebhookReceiver；回呼只排背景 fetch，完整 publication 有新版才通知有寫入權限玩家與 console。單人**永遠不開 webhook port**，即使 YAML 啟用也不監聽；選用定時 fetch（0 關閉，啟用至少 60 秒）。兩者通知都不 apply。憑證／通知設定變更需重啟世界或伺服器；既有 reload 不重建 receiver。

### 客戶端座標留言

連 Fabric 時 `/wg comments show [pr <number>]` 只對自己顯示：左上 literal HUD 列座標、距離、作者、摘要；世界內畫該座標或範圍線框。HTML／MiniMessage 保持字面，§ 色碼與控制字元由 CommentText 去除。不建立伺服器實體、不寫存檔、不入 capture。客戶端 `worldgit-client.yml` 可設 `comments-enabled: true`、`comments-max-count: 64`（1–64）、`comments-distance: 64`（16–512）；超出距離不渲染，HUD 高度限制優先顯示較近項目。

hide／換維度／撤權／離線／關閉世界清除，HTTP 晚到回應不重新顯示。原版客戶端連 Fabric dedicated 時不支援空間留言；show 明確說明需要 WorldGit Fabric 模組，`/wg comments [pr <number>]` 仍可讀文字。連 Paper／Folia 時仍正常看到插件提供的 TextDisplay，無需 comments-v1。

### Phase 4 建置與驗收

兩版正式 jar-in-jar 補齊 Jackson runtime，`check` 自動執行 [check-phase4-jars.py](tools/check-phase4-jars.py)，確認依賴宣告／必要 classes／正式 jar 不含 fixture。其餘模組使用不相容 Jackson 版本時仍應檢查 Loader 的依賴解析；本次不宣稱任意第三方模組組合相容。

```bash
python3 fabric/tools/accept-phase4.py 1.21.11
python3 fabric/tools/accept-phase4.py 26.2
python3 fabric/tools/accept-phase4-singleplayer.py 1.21.11
python3 fabric/tools/accept-phase4-singleplayer.py 26.2
python3 fabric/tools/regress-phase4.py
```

腳本自行取 bench.lock，啟動真 Hub jar／SQLite、dedicated 正式 jar、Xvfb 真客戶端；finally 關閉／清除世界副本、憑證與服務。截圖在 [phase4](docs/screenshots/phase4/)，可攜結果／來源與產物雜湊見 [驗收摘要](docs/phase4/results-2026-10-04.json)，失敗與限制見 [docs/14 Fabric](../docs/14-phase4-progress.md#fabric)，安全界線見 [Phase 4 安全審查](docs/security-review-phase4-2026-10-04.md)。
