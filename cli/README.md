# wgit

離線 Minecraft 世界的 git 式版本控制。需要 Java 21+；世界讀寫操作期間取得實際 `session.lock`，若世界正由伺服器使用會警告並以非零狀態結束。

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
| `init --track all\|modified-only` | 追蹤設定寫入 repo；後者有完整曾編輯集合才篩選，缺集合保守全存並警告 |
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

## Phase 2 離線復原

```sh
./wgit --world /srv/minecraft branch before-edit
./wgit --world /srv/minecraft restore HEAD~1 --chunks 0,0,3 --dry-run
./wgit --world /srv/minecraft restore before-edit --box 0,60,0,31,80,31 --dimension minecraft:overworld
./wgit --world /srv/minecraft switch before-edit --stash
./wgit --world /srv/minecraft stash list --format=json
./wgit --world /srv/minecraft stash pop 0
./wgit --world /srv/minecraft reset --hard
./wgit --world /srv/minecraft verify HEAD --format=json
```

| 指令 | 行為 |
|---|---|
| `restore <revision> [--chunks x,z,r \| --box x1,y1,z1,x2,y2,z2]` | 只改差異，方塊／BE 邊界逐格裁切，HEAD 不動；無範圍時含 world-meta |
| `switch <branch\|commit> [--stash\|--force]` | 同步所有已追蹤維度；不乾淨預設拒絕。hash／HEAD~n 進 detached HEAD |
| `branch [-d] [name] [start]` | 列出／建立／刪除同名分支；拒絕刪除目前或尚未合併的分支 |
| `reset --hard [revision --force]` | 無 revision 還原 HEAD；有 revision 改寫目前分支指標，必須 --force，勿對已 push 歷史使用 |
| `stash push [-m message]` | 全維度保存工作區並還原 HEAD，包含工作區新增 chunk |
| `stash pop [index]` | index 預設 0；只允許原基底與乾淨工作區（包含保留的 untracked），成功才移除 stash |
| `stash list\|drop [index]` | 列出／丟棄；drop 不支援 dry-run |
| `verify [revision] [--chunks ...\|--box ...]` | 全量離線掃描，所選範圍的已追蹤內容一致時 exit 0，有差異 exit 1 |

套用指令支援 `--dry-run`（不改世界／HEAD／stash，列出 chunk、section、實體、biome 與 metadata 數；capture 仍會寫入物件與可丟棄 index）。新指令的 JSON result 是 `state`（COMPLETE／DRY_RUN／PARTIAL）、`dimensions` 統計與 `error`，exit 0 表示成功／dry-run。branch／stash list 為 JSON 陣列。

restore／switch 預設保留目標沒有的 chunk 並列出 untracked；加 `--delete-untracked` 才刪除整個被涵蓋 chunk。範圍外的同 UUID 舊實體仍會移除以避免重複。switch／branch／reset／stash 必須對維度組操作，拒絕 `--dimension`；restore／verify 可限定維度。

所有新指令（含 list／dry-run）都拒絕伺服器持有的 session.lock。部分寫回失敗會留下 `apply-state.yml` 的 PARTIAL；先 `reset --hard` 或 `switch <branch> --force` 全範圍重套後才能 commit。DataVersion 不同、`.wgignore` 不同、DataPacks 清單不同會預檢拒絕。玩家資料／時鐘／天氣不還原，完整 metadata 與 stash 規則見 [05](../docs/05-switch-restore.md)，驗收見 [12](../docs/12-phase2-progress.md)。

## Phase 3 合併（2026-10-02）

```sh
wgit merge castle-v2
wgit merge castle-v2 --no-commit --format=json
wgit merge castle-v2 --strategy-option=theirs --dry-run
wgit conflicts --format=json
wgit resolve 1 --theirs
wgit resolve all --manual
wgit merge --continue
wgit merge --abort
wgit revert HEAD~2
wgit cherry-pick feature
```

合併操作要求全維度一致，不接受 --dimension；開始前工作區（含 untracked）乾淨，先 commit／stash push。`--distance=0..16` 設定區域分群距離，預設 1；衝突維持紫色 `!`。resolve 同時原地寫回精確區域並標解決；manual 保留目前世界。status 顯示 MERGING／來源／剩餘區域；全部解決後 `merge --continue` 或 `commit -m` 生成整合提交。revert／cherry-pick 的衝突也使用 merge --continue／--abort。

merge／resolve／continue／abort／revert／cherry-pick 支援 --dry-run，JSON 無 ANSI。merge JSON 包含 state、reports、remaining、commits、plans 統計與 error；區域 choice 為小寫 ours／theirs／base／manual。無衝突預設自動 commit；--no-commit 保留 MERGING，--strategy-option 只選衝突、不丟棄無衝突的另一邊內容。

離線保留柵欄／牆／紅石線等來源連接 state，報告列出需要線上 updateShape 的格子，不能假設開服後自動修正。完整驗收與限制見 [Phase 3 進度](../docs/13-phase3-progress.md)。

## Phase 4 遠端與世界下載（2026-10-03）

```sh
wgit remote add origin https://hub.example.com/alice/castle
# 一般 git 每維度一 repo：
wgit remote set-url origin 'https://git.example/team/castle-{dimension}.git'
# 或明確世界清單：
wgit remote add backup manifest+file:///srv/worlds/castle.yml
wgit remote list --format=json
wgit fetch origin
wgit status
wgit push origin main --tags
wgit pull origin main --ff-only
wgit clone https://hub.example.com/alice/castle castle --branch main
wgit clone https://hub.example.com/alice/castle nether-only --dimension minecraft:the_nether
wgit tag v1 HEAD -m '城堡完成'
wgit tag -l
wgit tag -d v1
wgit export v1 castle.zip --max-bytes 2147483648 --max-seconds 900
```

| 指令 | 行為 |
|---|---|
| `remote add/remove/list/set-url` | 世界組 YAML sidecar；add/set-url 解析 URL/manifest，禁止帳密/query/fragment；變更支援 --dry-run |
| `fetch [remote]` | 預設 origin；全維度下載 refs/objects/tags，驗證完整 publication/group 才更新 tracking；不套用世界 |
| `push [remote] [branch] [--tags] [--force-with-lease]` | 預設 origin/目前分支；非 FF 提示 pull；force lease 比對最近 fetch tip，不等於無條件強推；server 政策仍可拒絕 |
| `pull [remote] [branch] [--ff-only]` | fetch→對目前分支 FF 或三方合併；乾淨含 untracked；衝突進入 MERGING，resolve/merge --continue/abort 沿用 Phase 3 |
| `clone <url> [dir] [--branch b] [--dimension d]` | 預設 main／URL 最後一段；輸出單人原版世界與世界內 .worldgit；目的地必須不存在；先 temp 完整組裝再 rename |
| `tag <name> [rev] [-m msg]`／`tag -l`／`tag -d` | 輕量／附註 tag，預設 HEAD；全維度共用快照配對，list/delete 有全組 journal |
| `export <rev> <out.zip>` | release 世界 ZIP，串流輸出且不帶 repo/player/session；暫存 Anvil 同時受大小與時間預算限制 |

remote/fetch/push/tag/export 不開 session、不寫世界；pull 以及既有世界操作仍要求世界停止。fetch/push/pull/tag/export/remote 變更支援 --dry-run（clone/list 不適用）；fetch/push dry-run 只查 refs，尚不量測 PACK。**pull --dry-run 仍執行真 fetch、更新 tracking，只對世界套用做 dry-run**。export dry-run 只解析 revision；資料完整性／組裝大小需真正 export 才知道。JSON 沿用無 ANSI 的統計格式，pull 為 expectedHeads/targets/fastForward/result，其中 result.plans 是統計，report/remaining/commits 與 merge 一致。MERGING 是正常流程，exit 0；PARTIAL/error 非零。

status 的 tracking 以 snapshot UUID 的聯集算 ahead/behind（不重複計三維度），只用最近 fetch 資料，不連網；每維度遍歷超過 20,000 commits 標 estimated，detach 不顯示。fetch/push/pull/tag/export 對所有已追蹤維度同步，拒絕 --dimension；clone 可以限定一維度，仍下載主世界 metadata repo，不完整 clone 禁止 push 整個世界。

PAT 使用環境 `WGIT_TOKEN`，搭配 `WGIT_AUTH=basic|bearer` 和可選 WGIT_USERNAME；或使用者 `~/.config/worldgit/credentials.yml`，格式與權限 600 詳見 [07](../docs/07-remote-hub.md)。不要把 PAT 放 URL、remote 設定或資料包。沒有憑證時明確送 anonymous Basic，公開世界可 clone；私人世界會被 Hub 拒絕。HTTP(S)/file 支援；SSH 尚未提供。

clone 丟棄光照／POI／Heightmaps，由遊戲重建；保留 seed/worldgen、規則、實體、metadata 與資料包。modified-only 未存自然地形會依種子生成，但平台的玩家編輯蒐集尚未接線；可用 core ModifiedChunks 完整 sidecar，不可把當次 dirty 當完整集合。ZIP 是發布副本，不包含玩家進度／背包。pack 每批 ≤95 MB；PARTIAL push 用原 remote/branch/options/tips 重試，失敗紀錄不會印 PAT；若第三方改 refs 會阻擋恢復。完整 API、驗收、量測與限制見 [14](../docs/14-phase4-progress.md)。
