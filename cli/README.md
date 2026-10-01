# wgit

離線 Minecraft 世界的 git 式版本控制。需要 Java 21+；操作期間取得實際 `session.lock`，若世界正由伺服器使用會警告並以非零狀態結束。

```sh
export GRADLE_USER_HOME="$PWD/.work/gradle-home"
./gradlew --no-daemon --max-workers=1 :cli:fatJar
./wgit --world /srv/minecraft/world init --template creative
# 也接受 --world /srv/minecraft（含 world/）
./wgit --world /srv/minecraft/world status
./wgit --world /srv/minecraft/world commit -m '完成第一層'
./wgit --world /srv/minecraft/world log -n 10
./wgit --world /srv/minecraft/world diff
./wgit --world /srv/minecraft/world diff HEAD~1
./wgit --world /srv/minecraft/world diff HEAD~1 HEAD --blocks
./wgit --world /srv/minecraft/world diff HEAD~1 HEAD --blocks --format=json
```

世界路徑預設目前目錄；全域選項也能放在指令後。跨維度操作逐一回報成功／沒變動／失敗，部分失敗保留成功結果並回傳 1。`--dimension minecraft:the_nether` 只操作該維度。

| 指令／選項 | 行為 |
|---|---|
| `init --template creative\|survival` | 建立 repo、`.wgignore` 及初次完整快照；預設 creative |
| `init --track all\|modified-only` | 追蹤設定寫入 repo；後者目前只記錄，仍儲存所有 full chunk |
| `status [--full]` | HEAD → 活世界的摘要；一般模式使用 index，全量模式重驗 |
| `commit -m '訊息'` | 所有已追蹤維度使用同一 snapshot trailer，只有改變的維度產生 commit |
| `log [-n 20]` | 依 snapshot 分組，列出當次有 commit 的維度 |
| `diff [a [b]]` | 0 參數 HEAD → 世界；1 參數 a → 世界；2 參數 a → b |
| `--blocks` | 方塊座標、舊→新 state、BE 變化、biome sample；大範圍的明細需要較多記憶體 |
| `--format=json` | 結構化輸出，kind 為小寫、無 ANSI；status/init/commit 的警告在 `warnings`，diff 的提示寫 stderr |
| `--color=auto\|always\|never` | 預設僅終端上色；`NO_COLOR` 存在時停用，包含 always |

新增綠 `+`、移除紅 `-`、修改黃 `~`、衝突紫 `!`；關色仍有記號。預設每 section 統計方塊及 BE 的唯一變動格；實體、biome、metadata 另列。色票由 protocol 共用。

作者預設 OS 使用者與 `worldgit@localhost`，可用 `GIT_AUTHOR_NAME`／`GIT_AUTHOR_EMAIL` 指定。repo 使用真正 git commit 欄位與 WorldGit trailers，可用 native git 唯讀檢視歷史。

設定位置：`<server>/.worldgit/<world>/worldgit.yml`（本機）例如：

```yaml
palette: colorblind
entity-tolerance: 2
```

編輯 `<server>/.worldgit/<world>/<dimension>/.wgignore` 後，`status` 提示將移除的已追蹤內容，下一次 commit 存下新規則。repo 設定 sidecar `worldgit-repo.yml` 使用 `track: all` 或 `track: modified-only`，會進版本控制；本機設定不進版本控制。YAML 未知鍵、錯誤型別、重複鍵會附來源報錯。

離線 tag 支援兩版 vanilla 與已啟用資料夾/ZIP datapack，修改 registry 時 index 會重建。可用 `field worldgit:map *` 排除地圖、`field worldgit:scoreboard *` 排除記分板；其他 world-meta selector 與根 NBT 欄位規則見 core README。

發佈可複製 fat jar，執行 `java -jar wgit.jar …`；`wgit` launcher 可用 `WGIT_JAR=/path/wgit.jar`。Gradle 也產生標準 `cli:installDist` 發佈目錄。目前沒有 native-image。
