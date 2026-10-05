# wgit

Java 21+ 的離線世界版本控制。世界寫入與 migrate 必須在遊戲停止後執行；使用真正的 session.lock 檢查。Phase 5 的完整契約、實測紀錄及後續任務見 [設計](../docs/16-phase5-design.md)。Paper／Fabric／Hub 的使用介面由後續任務更新。

```sh
export GRADLE_USER_HOME="$PWD/.work/gradle-home"
./gradlew :cli:fatJar --no-daemon --configure-on-demand --max-workers=1
./wgit --world /srv/minecraft/world init --only
./wgit --world /srv/minecraft/world init --with-dimensions nether,end
./wgit --world /srv/minecraft/world status --full
./wgit --world /srv/minecraft/world commit -m '完成入口'
./wgit --world /srv/minecraft/world --dimension minecraft:the_nether branch cavern
./wgit --world /srv/minecraft/world --dimension minecraft:the_nether switch cavern
./wgit --world /srv/minecraft/world log --graph --all
```

`--world` 預設目前目錄，接受世界根、含 world 的 server、DIM-1／DIM1、Paper world_nether／world_the_end、26.2 dimensions/ns/path。變更預設路徑所指維度，`--dimension id` 覆寫；`--all` 逐維度獨立執行，失敗保留其他成功。commit 預設對所有已 init 維度的變動各自提交；status／log／diff／分支、tag、remote 清單預設列出全部。

repo 在主世界根 `.worldgit`、其他維度地形資料目錄 `.worldgit`。壓縮該世界／維度資料夾就攜帶 repo；發布 ZIP 不含 repo。`.wgignore`、worldgit-repo.yml、worldgit.yml、remotes.yml 和狀態檔皆屬各維度。舊外接與舊單人巢狀位置可讀，`wgit migrate [--dry-run]` 安全搬移並保留備份。

| 指令 | 行為 |
|---|---|
| `init [--template creative\|survival] [--only\|--with-dimensions all\|nether,end]` | 只初始化路徑／指定維度；主世界互動詢問地獄與終界，無 TTY／JSON／CI 不追加；自訂維度不詢問 |
| `status [--full]`、`diff [a [b]] [--blocks]` | 每維度獨立摘要／逐格差異；tracking 以最近 fetch 的 commit hash 算 ahead／behind |
| `commit -m message` | 只有有變動的維度產生獨立 commit；snapshot 不構成全組約束 |
| `log [-n count] [--graph] [--all]` | 每維度歷史；--all 含所有分支／tag／tracking，JSON 提供共用 lane／edges／截斷 |
| `branch [-d] [name [start]]` | 建立／刪除預設維度的分支；無名稱列出全部已 init 維度 |
| `switch revision [--stash\|--force]` | 只切選定維度，hash 為 detached；工作區預設要求乾淨 |
| `restore revision [--chunks x,z,r\|--box x1,y1,z1,x2,y2,z2]` | 還原所選維度與範圍，HEAD 不動；方塊／BE 逐格裁切 |
| `reset --hard [revision --force]` | 還原所選維度；有 revision 必須 force，勿改寫已推送歷史 |
| `stash push\|pop\|list\|drop [index]` | 各維度自己的 stash，pop 須原基底且乾淨，成功才 drop |
| `merge revision [--no-commit] [--strategy-option=ours\|theirs]` | 單維度合併；有衝突保留自己的 MERGING |
| `conflicts`、`resolve id\|all --ours\|--theirs\|--base\|--manual` | 列衝突、選精確 atoms 並解決；不重算鄰居 shape |
| `merge --continue\|--abort`、`revert rev`、`cherry-pick rev` | 各維度獨立合併／patch 流程 |
| `verify [revision]` | 全量核對，差異不為 0 時非零結束 |
| `remote add\|set-url\|remove\|list` | add 世界 URL 為已 init 維度各自展開；set-url／remove 預設一維度 |
| `fetch [remote]`、`push [remote [branch]] [--tags] [--force-with-lease]` | 單維度傳輸、CAS／有界 pack；不要求其他維度同分支／publication |
| `pull [remote [branch]] [--ff-only]` | fetch 後只對選定維度 FF／三方合併 |
| `clone url [dir] [--branch dim=branch]` | 每維度預設分支，branch 可重複；--dimension 可用逗號選取多個，必須明確包含主世界 |
| `tag [-l\|-d] [name [revision]] [-m message]` | 各維度獨立輕量／附註 tag |
| `export [revision] out.zip [--rev dim=rev]` | 每維度 revision 組裝釋出 ZIP；未指定用 HEAD，不包含 repo／玩家 |
| `ignore list\|add\|remove\|move\|test\|check\|enable\|disable` | 有序規則編輯；行號保留註解／空行，--dry-run 看移除追蹤預覽，MERGING 禁止寫入 |
| `migrate [--dry-run]` | 世界停止後搬舊位置，先驗證再原子發布、可重跑；未完成舊 journal 先用舊版恢復 |

restore／switch／reset／stash／merge／resolve／remote 變更及傳輸支援既有 --dry-run；pull dry-run 仍真 fetch 更新 tracking。capture 可以寫入物件／可丟棄 index，不能把 dry-run 當成檔案系統全唯讀。目標沒有的 chunk 預設保留 untracked，explicit commit 可重新納入；`--delete-untracked` 須明確指定且符合排除限制。跨 DataVersion／不一致 DataPacks 仍預檢拒絕。

```sh
wgit ignore list
wgit ignore add 'entity minecraft:item' --dry-run
wgit ignore move 8 3
wgit ignore test 'block 0,64,0'
wgit ignore test 'entity minecraft:item 0,64,0'
wgit ignore test 'field worldgit:map data'
wgit ignore check --format=json
```

規則在下次 commit 儲存；直接編輯 `<repo>/.wgignore` 仍可用。新 creative 範本設定 `entities: player-touched`，方塊／BE／biome／地形完整追蹤，實體只沿用版本化 UUID sidecar。離線 init 沒有事件來源，集合為空並提示。survival 與舊 repo 維持 all；未追蹤的自然實體不能被 apply 刪除或移動。

Paper／Fabric 遊戲內 creative init 過渡期仍用 `entities: all`，觸及事件由任務 3／4 接線。1.21.11 clone／export 即使只取部分維度仍保留空的 `DIM-1/`、`DIM1/`（無地形與 repo），讓 Paper 沿用主世界 seed／DragonFight；發布 ZIP 同樣保存空目錄。

每個指令以明確完成行結束；JSON 是 `{result,data}`，result 有 operationId、operation、status、dimension、summary、elapsedMillis、nextSteps、error。狀態 SUCCESS／NO_OP／PARTIAL／FAILED／CANCELLED 對應 exit 0／0／2／1／130；批次每維度各有結果。錯誤包含 UTC／版本／操作 id，遮罩秘密且有長度上限。

`--color=auto|always|never`、NO_COLOR 沿用，新增綠、移除紅、修改黃、衝突紫，關色保留記號。進度只在 TTY 且未禁色時以單行動畫呈現；非 TTY／JSON 無動畫，stdout JSON 不混入進度事件。

PAT 放 WGIT_TOKEN（WGIT_AUTH=basic|bearer、WGIT_USERNAME），或權限 600 的使用者 credentials YAML；不能放 URL／世界資料。HTTP(S)／file transport，SSH 尚未支援。所有 pack ≤95 MB；遠端服務自行 GC／原生 git 直接 clone 的 pack 大小不由 WorldGit 保證。

CLI 新增訊息使用共用 MiniMessage 訊息表，`WGIT_LOCALE=en_us` 可切換完成提示與 init 詢問（預設 zh_tw）。單獨攜帶已 init 的維度資料夾時，也可從 `.worldgit` HEAD 辨識版本及維度，執行 status／log／commit／switch；完整 clone／export 仍必須含主世界。
