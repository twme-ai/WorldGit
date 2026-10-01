# WorldGit Fabric（Phase 1）

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
/wg clear
/wg info
/wg reload
```

`status --show` 畫 section／chunk 外框，`diff --show` 畫逐格外框與舊方塊鬼影。顯示與 clear 需玩家執行；console 可用 init/status/commit/log。指令詳細選項及權限見 `WgCommands`；預設讀取權限等級 0、寫入等級 2，單人世界擁有者可操作。

每個維度一個 repo，路徑為世界資料夾旁的 `.worldgit/<世界名稱>/<維度目錄>/`，例如 `.worldgit/My World/minecraft.overworld/`。主世界保存 world-meta 與維度清單。init 只建立磁碟上存在且尚未初始化的維度；第一次進入新維度後可再 init。

預設 creative 範本全部追蹤；survival 範本排除暫態、非 persistent 生物等。`track: modified-only` 與 core 一致，目前只記錄設定，尚未篩掉自然地形。沒有內容變動不產生 commit。

自動 commit 包含定時、專用伺服器玩家登出、關機／離開單人世界。玩家放置／破壞事件記錄 chunk 粒度歸屬，多人參與寫入 Contribution trailers；只有一位參與者時自動提交以該玩家為 author，committer 為伺服器。多人登出目前提交共同工作世界，尚無 per-player staging。

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

v2 握手後才接受預覽；分包由 BatchAssembler 收齊再發布。斷線清除場景，每個維度各有預覽。新增為實線、移除為原版模型半透明鬼影、修改為虛線加淡色舊模型、衝突以 alpha 緩慢閃爍。遠處／超出明細預算的 section 只畫包圍盒；視錐及距離裁切，每幀最多建立設定配額的 section 明細，避免一次上傳全部格數。超過伺服器 100,000 格上限時傳 status 包圍盒。

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

先建置 `:cli:fatJar`，或以 `WGIT_JAR`／`--cli-jar` 指定固定的 CLI jar。CLI 比較 init→手動提交的明確 commit id，避免把後來移動相機造成的探索／關機自動 commit 混入三格驗收。驗收世界關閉隨機刻與生物生成，避免草地腐化影響固定格數。

Paper 驗收先複製插件 jar 到 `.work/fabric-acceptance/`，記錄來源 mtime（UTC／ns）、大小和 SHA-256，整輪只使用副本。腳本使用 `.work/servers/paper-<版本>/server.jar` 的複本，port 25663／25664、127.0.0.1、offline-mode；伺服器與世界都在 `.work/`。它先準備存檔再載入插件，讓真客戶端握手、收三格 diff／status、截圖與 clear，finally 關閉程序和確認 port 已關閉。

```bash
python3 fabric/tools/accept-paper.py 1.21.11
python3 fabric/tools/accept-paper.py 26.2
```

以上三個驗收腳本都自行取得 `bench.lock`，不要再包外層 flock。並行開發時可用 `GRADLE_ROOT`（gametest）或 `--gradle-root`（Paper）指定同步過共用模組的私有建置根。專用 Fabric 伺服器兩版的已完成驗收沿用交接紀錄；此次增加 mixin 的載入檢查另記於進度。

## 目前限制

Phase 2 apply/restore/switch/merge 尚未提供，`lockEdits` 的空實作目前不構成編輯鎖。尚無準星「舊→新」UI、實體／biome 模型、流體或特殊 block entity renderer、Mod Menu 畫面、資源包重載後模型快取重建、Sodium／Iris 或硬體 GPU 驗收。鬼影使用固定光照與 quad 順序，沒有透明面排序／內部面消除；大量格數的此次驗收是 3,072 格、6 sections，不能據此宣稱 100,000 格的效能。
