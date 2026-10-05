# 16 — Phase 5：維度獨立與操作體驗

設計日期：2026-10-04。決定 #110–#121。本檔案是 Phase 5 四個任務的共同契約，設計目標不等於已完成驗收；實作與缺口見文末。

## 任務與驗收

| 任務 | 範圍 | 驗收 |
|---|---|---|
| 1（本次） | 路線圖、core、CLI；平臺相容調整 | 單維度 HEAD／逐格隔離、repo 搬移與攜帶、CLI graph／ignore／結果、全專案 build、真 Hub／Paper／Fabric 回歸 |
| 2 | Hub | 每維度專案、PR／release／remote 獨立、互動分支圖、進度與完成通知、錯誤複製 |
| 3 | Paper／Folia | 玩家所在維度 init、可點選追加 init、別名、bossbar、ignore 箱子 GUI、結果與複製、觸及實體事件 |
| 4 | Fabric | 同上；單人／dedicated／客戶端 HUD、ignore 編輯畫面、分支圖畫面評估 |

四端共用 core graph lane、進度及結果模型。最終驗收：只切地獄，主世界 HEAD、逐格內容與世界級資料不變；壓縮世界／維度資料夾會攜帶該 repo，另一臺解壓後可 status、commit；release ZIP 不含 repo。

## A. 獨立 repo 與資料歸屬

獨立單位是 DimensionId，每維度具有自己的 HEAD、refs/heads、refs/tags、refs/remotes、stash、MERGE_HEAD、MERGING、PARTIAL、remote、設定及歷史。snapshot UUID 只是 commit 資訊，不配對其他維度、不要求名稱或版本同步。commit 的便利批次各自建立有變動的提交，任一失敗保留其他成功。

`WorldRepositories.tracked()` 列出已 init 的位置；`initializable()` 包含實際資料目錄、新 repo 位置及 initialized。`initDimensions(Collection,...)` 明確選取，`init(null,...)` 只取路徑維度。`WorldOperations.inDimension(layout,id)` 與 `live(layout,access,id)` 只開啟單一 repo；預設建構使用 layout.currentDimension。平臺的線上 flush／owner apply／verify 屏障沿用，不在遊戲 owner 執行緒阻塞。

主世界保有 `world-meta/level.nbt`、資料包清單／原檔、地圖、記分板及世界級設定；`dimensions` 清單僅供世界組裝發現。26.2 非主世界 saved-data 放自己的 `dimension-meta/`，只容許自己的命名字首。非主世界不能套用 level.nbt／資料包／地圖。版本資訊與啟用資料包的 tag registry 可用於解析，不能因此改寫世界級資料。舊主世界快照曾儲存其他維度 saved-data，需要保守過濾，不能因還原主世界覆蓋其他維度。

## B. 目錄、排除與遷移

| 版面 | 主世界 repo | 地獄／其他維度 repo |
|---|---|---|
| 1.21.11 原版／單人 | `<world>/.worldgit/` | `<world>/DIM-1/.worldgit/`、`DIM1/.worldgit/` |
| 1.21.11 Paper | `<world>/.worldgit/` | `<world>_nether/DIM-1/.worldgit/`、`<world>_the_end/DIM1/.worldgit/` |
| 26.2 | `<world>/.worldgit/`，即使地形位於 dimensions/minecraft/overworld | `<world>/dimensions/<ns>/<path>/.worldgit/` |
| 自訂維度 | — | 該維度實際地形目錄內 `.worldgit/` |

單獨壓縮非主世界維度時，解壓後可以從自己的 `.worldgit` HEAD 取得維度 id 與 DataVersion，進行 status／commit／switch，不需要主世界 `level.dat`。此時離線實體 tag registry 使用內建規則；依賴原世界資料包的自訂 tag 明確提示並採保守語意，不能猜測其成員。沒有主世界的 clone／export 仍明確拒絕組裝可開啟的完整世界。

1.21.11 clone／export 會建立空的 DIM-1／DIM1 目錄，即使該維度未選取，也不建立其地形或 repo。Paper 首次啟動藉此走原版維度搬移流程，複製主世界的種子與 DragonFight；否則缺少 DIM1 時會建立新的終界設定，破壞主世界 metadata 的可攜性。

世界及 saved-data 仍在遊戲原本的位置。repo 不是資料包，不建立在 region／entities／poi 內。capture／diff／apply 只解析正式資料路徑；所有遞迴發現和資料包原檔讀取排除任意層 `.worldgit`。clone 的每維度 repo 放到上述位置；export／release 組裝新發布世界，不複製 repo、玩家或 session.lock。

各 repo 儲存 `.wgignore`、worldgit-repo.yml、worldgit.yml、remotes.yml、worldgit.index、apply-state.yml、stash.yml、merge-state.bin／.updates、last-merge-report.bin、push-state.yml、fetch-state.yml、tag-state.yml。設定全為 YAML；快取／MERGING binary 沿既有有界格式。

`wgit migrate [--dimension id] [--dry-run]` 必須先取得 session 鎖、確認世界已停止。新位置優先；舊外接 `<server>/.worldgit/<world>/<dim>`、單人 `<world>/.worldgit/<dim>` 與保留的 `.worldgit-legacy/<dim>` 仍可讀。流程：

1. 盤點來源與目標，拒絕未完成舊 PARTIAL／MERGING／push／fetch／tag。先以舊版恢復，避免丟棄狀態。
2. 若來源包在目標父目錄，把整個舊 root 原子改名 `.worldgit-legacy`；中斷後 discover 仍能找到。
3. 在目標旁 `.worldgit-migrating` 複製，不跟隨符號連結；寫 `.worldgit-migration.yml`，可重跑。
4. 複製各維度設定、展開舊 remote URL；拆分 stash 與歷史恢復資訊。驗證 HEAD、全部 refs、commit／物件完整性。
5. 原子 rename 到新位置，標 COMPLETE。保留舊備份直到管理者自行清理，不重寫 commit。

每維度各自回報 COMPLETE／NO_OP／FAILED；某維度失敗不阻擋其他維度遷移，CLI 聚合為 PARTIAL（exit 2）或全部失敗 FAILED（exit 1）。取消停止後續維度且可重跑。遷移前仍可 status／log，但舊 active 全組 journal 會阻擋新的單維度寫入，不能繞過舊版恢復流程。

舊 snapshot trailer 和 group／publication refs 可保留作歷史，不再參與一致性驗證。舊 group journal 不可直接當新單維度 journal 重播。跨檔案系統透過複製完成，目標最後的 rename 在同一目錄。

## C. CLI 與 init 範圍

`--world` 預設目前目錄：世界根／server 含 world 指主世界；DIM-1、DIM1、Paper world_nether、world_the_end、26.2 dimensions/ns/path 指該維度。`--dimension` 明確覆寫。改變狀態預設路徑維度；`--all` 逐維度執行，非原子，失敗不回滾別的維度。唯讀 status／log／diff／branch list／tag list／remote list／conflicts 預設列出已 init 維度。

```sh
wgit --world world init --only
wgit --world world init --with-dimensions nether,end
wgit --world world init --with-dimensions all
wgit --world world/DIM-1 init
wgit --world world --dimension minecraft:the_nether switch cavern
wgit --world world reset --hard --all
```

主世界 init 只詢問既存且未 init 的地獄／終界，一起以 `[y/N]` 決定；不詢問自訂維度。無 TTY、JSON 或 CI 預設只主世界，stderr 提示旗標；`--only` 抑制詢問，與 --with-dimensions 互斥。`all` 表示實際存在的維度，不替遊戲創造維度。

遊戲玩家 init 取目前維度；主世界追加 init 以聊天按鈕表示，各維度結果分別回報。console 必須明確指定，不能猜玩家位置。屬於任務 3／4。

## Remote、clone、export 與裸合併

remote 每維度寫展開後的 URL。世界 URL 的 add 是便利入口，對每個已 init 維度分別配置；set-url／remove 預設只路徑維度，--all 逐一執行。一般 repo 完整 `.git` URL 不再展開；仍支援 `{dimension}` 與 manifest YAML。

單維度 push 保有 expected-old、fetch lease、refs/worldgit/transfers 分批及可恢復 journal，pack ≤95,000,000 bytes（#17／#77）。不再寫全組 publication 或要求 group／tag 名稱一致。fetch 的 incoming／tracking 發布、恢復只屬該維度，ahead／behind 計算 commit hash 可達集合，不合併 snapshot UUID。

```sh
wgit remote add origin https://hub.example/alice/castle
wgit --dimension minecraft:the_nether remote set-url origin https://git.example/nether.git
wgit --dimension minecraft:the_nether push origin cavern
wgit clone https://hub.example/alice/castle copy --branch minecraft:the_nether=cavern
wgit export --rev minecraft:overworld=v1 --rev minecraft:the_nether=cavern release.zip
```

clone 的 `--dimension minecraft:overworld,minecraft:the_nether` 可限制維度，必須明確包含主世界；只指定地獄會報錯，不自動添加。clone 各維度使用遠端 symbolic HEAD 的預設分支（兩個分支同 tip 時仍保留 symbolic 名稱），--branch dim=branch 可重複。file 世界 URL 列舉當前 bare repo；Hub 世界 URL 以有界的既有 world REST API 發現當前維度（帶憑證、禁止 redirect、64 KiB／32 維度）。舊主機 404 時沿用主世界維度清單／明確 URL 清單；只選部分也必須有主世界 metadata，core 缺主世界時報錯。目的地不存在，以 sibling temp 完整下載／組裝後 rename。export 未指定維度用 HEAD，支援舊 `<revision> <out.zip>` 便利語法；各維度 DataVersion 必須能由同一遊戲開啟，拒絕冒充升級。

裸合併的新入口固定單維度、兩 parent、CAS／journal 在自己的 repo，無 publication。Hub 的舊組入口僅供任務 2 過渡；世界容器／權限／smart HTTP 路徑暫保留。不得把過渡入口視為完整獨立 Hub UX。

## D. 共用分支圖

`CommitGraph.read(refs,limit,all)` 回傳 nodes、truncated、lanes。node 有 id、parents、snapshot、精確時間、作者、訊息、lane、before／after lane 中的 commit id、parent edges 與 labels。labels 包含 branch／tag／HEAD／tracking；附註 tag peel 到 commit。拓樸先於時間，同層以時間降序、hash 升序；最多 10,000 列、遍歷 20,000 commits，截斷必須標示。合成 transfers／舊 publication 不納入使用者歷史起點。

```text
minecraft:the_nether cavern
* a1b2c3d4 (HEAD, cavern) 新通道
|\
| * e5f6a7b8 (main, origin/main) 修正入口
* | c9d0e1f2 基礎地形
```

CLI `log --graph --all` 顯示所有分支，--dimension 篩選，--format=json 保留同一份 lane／edges。支援 ASCII，branch 名依既有色彩開關上色，NO_COLOR 優先。Hub 任務 2 用 SVG／canvas 可點 commit 到 3D、顯示 PR 合併標籤。Paper／Fabric 聊天文字圖 hover 內容、點選填入 preview 指令；Fabric 另評估可捲動畫面。

## E. 指令別名

任務 3／4 註冊 wg、wgit、git；保留既有 worldgit。Paper 走 Brigadier Lifecycle API，名稱空間 `worldgit:git` 始終可用；已有 /git 時不搶覆蓋，設定可停用別名。Fabric dispatcher 發現衝突時跳過裸 /git、輸出一次管理提示，提供設定開關。CLI 執行檔維持 wgit。

## F. 進度與取消

`OperationProgress` 的 operationId／operation／dimension／phase／completed／total／unit／remainingMillis／cancellable 為四端共用事件。total=null 是不定進度；unit 是 chunk、section、object、bytes、commit。計算端更新記憶體，獨立 dispatcher 每 ≥100 ms 派送最新事件；listener 不在世界鎖執行 IO，不允許阻塞計算端。可取消 token／thread interrupt 在安全檢查點轉入取消結果；已改世界內容以 PARTIAL 恢復，不宣稱回滾。

必須覆蓋 init／commit／status full 的 scan、capture、normalize；diff blocks；restore／switch／reset／stash 的 preflight、apply、verify；merge／revert／cherry-pick 的 compute、write；verify；fetch／push／pull 的 pack、bytes、publish；clone download、Anvil；export assemble、ZIP；migrate copy、verify；Hub bare merge／ZIP。

CLI TTY 一行 phase／dimension／百分比／計數／速率／ETA，結束清除後結果行。JSON、非 TTY、NO_COLOR、color=never 無動畫；本次決定 JSON 只在 stdout 輸出最終檔案，不混入 stderr 進度 JSON，未來另設明確 opt-in 旗標。Ctrl+C 發 token，等待已派出 IO／驗證清理，exit 130 並指出 PARTIAL 是否存在。

Hub 用有界且授權檢查的 operation endpoint＋SSE／輪詢；Paper／Fabric bossbar／actionbar 給執行者與有權限觀察者，console 節流文字行，完成狀態短暫留在 bar。

## G. `.wgignore` 編輯

`IgnoreEditor.Document` 儲存有序原始行、註解／空行及尾端換行；編號是檔案行號。add、remove、move、enabled；停用標記 `# worldgit-disabled: ` 可恢復。256 KiB／4096 行上限，純文字，不解析 MiniMessage／HTML；驗證錯誤包含行號。repo lock 下原子 replace，MERGING 禁止修改。改規則的歷史效果仍由下次 commit 儲存。

preview 以 HEAD 已追蹤樹套新規則，回傳被排除方塊、BE、實體、biome、metadata 計數與有界樣本；test 提供 block x,y,z／entity type x,y,z／field selector field，回傳 excluded、最後命中行及規則。平臺測試可帶實際 entity persistence／tag registry；CLI 無完整 NBT 的測試必須明示使用預設 entity 語意。

```sh
wgit ignore list
wgit ignore add 'entity minecraft:item' --dry-run
wgit ignore remove 8
wgit ignore move 8 3
wgit ignore disable 3
wgit ignore test 'block 0,64,0'
wgit ignore test 'field worldgit:map data'
wgit ignore check --format=json
```

Paper 任務 3：Brigadier 型別／ID 補全、準星方塊或手持物品 test、箱子 GUI 分頁／刪除／移動，新增用聊天或書本；先 preview 確認，權限 worldgit.command.ignore 預設 op。Fabric 任務 4：客戶端規則清單／排序／即時錯誤／預覽，server 重新驗證與寫入權限，單人 owner 同入口。

## H. 完成結果

`OperationResult` 有 operationId、operation、status、dimension、summary、elapsedMillis、nextSteps、error。狀態 SUCCESS／NO_OP／PARTIAL／FAILED／CANCELLED，CLI exit 0／0／2／1／130。CLI JSON 一份 `{result,data}`，批次 data 每維度含自己的 result／data。每個使用者動作都必須有終止結果；成功摘要含短 hash／變動數、分支 @ hash、剩餘衝突、bytes／commit 數、耗時與必要下一步。

CLI 既有缺口：branch／stash drop／tag／remote 修改只有列資料；status／log／diff／verify 沒有統一終止行；錯誤 handler 無 operationId；transfer PARTIAL 和一般 failure 都 exit 1。新的 execution boundary 統一收斂結果。

後續盤點：Hub PR create/edit/review/choices/merge、release／webhook test／token、組織設定；Paper／Fabric 手動與 auto commit／logout／shutdown、init、apply、merge region、resolve、remote／fetch／push／pull／PR／comments、cancel、reload／clear；不得僅開始訊息或重新整理。遊戲 title／音效可關，bar 先顯示終態再消失，auto 完成通知只給授權者且可設定，console 永遠保留結果行。

## I. 複製錯誤

`ErrorReport` 包含 code、operation／id、dimension、WorldGit／Minecraft／platform 版本、UTC、全文及純文字 report。所有文字先遮罩 URL userinfo、PAT、Authorization、token／secret／password 等敏感鍵，最長 8192 字元，截斷加標記。秘密來源的已知值也必須由平臺 credential masker 消除，不能只依賴 token 形狀。

Paper／Fabric 每則 error／failure／partial 都加獨立 `[複製]`（Adventure copyToClipboard），hover「點擊複製錯誤內容」由 i18n MiniMessage 提供；不更動原文字、顏色或既有 teleport／suggestCommand 點選行為。console 一行同欄位，Hub error toast 附複製按鈕，CLI JSON `result.error` 保留相同欄位。

## J. 創造範本的實體追蹤

本次解讀：方塊、BE、生態域與已生成地形照舊完整追蹤；創造範本只把實體改為「玩家放出／更改」。`entities: all|player-touched` 是 versioned repo 設定。新 creative 預設 player-touched；survival 維持 all 並沿用既有 .wgignore。舊 repo 沒有 entities 鍵時仍 all，不自動改設定。

`player-touched.yml` 為 version=1＋排序 UUID 清單，repo sidecar 隨 commit 入 root blob，push／clone 隨歷史移動。上限 100,000 UUID／8 MiB；完整成功 commit 後清掉世界裡已消失的 UUID；status 不破壞 sidecar，dirty batch 不可當完整集合。

平臺事件：蛋、summon／data merge、WorldEdit／FAWE、盔甲座／展示框／畫／載具／水晶放置、命名／拴繩／馴服／裝備／染色／繁殖幼體／騎乘／展示框內容等 touch；自然刷怪／掉落物／XP／自然投射物不 touch，之後玩家更改才納入。CLI 沒事件來源，init creative 集合為空且必須提示，沿用既有 sidecar；不以 entity type 猜自然或玩家來源。

touch 乘客或載具時記錄該關聯的完整根／乘客閉包，避免只儲存無法還原的子實體。UUID 轉換事件繼承觸及資格到新 UUID、舊 UUID 下次完整 commit 清理。跨維度傳送時目的維度先記錄 UUID，來源下一次 commit 移除；各維度歷史獨立，還原只操作自己的 UUID。若跨維度獨立還原造成活世界 UUID 重複，平臺應在寫入前拒絕並指出所在維度，不偷偷刪其他維度實體。

capture、diff、merge、clone 的樹只包含集合內實體；apply 只移除／放回追蹤 UUID，集合外實體保留原位置。merge sidecar 必須與最終 entity 樹一致，新增集合取合併後存在的 UUID，不能把 sidecar 文字衝突變成無法辨別來源的假 entity 衝突。restore／switch 要在驗證前同步目標集合並能從 journal 恢復。

## 受影響決定與風險

被取代的全部／部分語意：#2、#16、#18、#26–#27、#29、#31、#33–#34、#37–#41、#43–#44、#49–#50、#52、#75–#76、#78–#80、#82、#87–#90、#93–#95、#98–#99、#103、#105。原決定保留，獨立／位置／範本的新規則優先；#28 的版本／規則遷移拒絕與 #42 的線上預檢限制保持有效，來源 state、權限及 pack 上限不因此放寬。

主要風險：Paper 首次開原版世界會搬 DIM-1／DIM1，須驗 repo 隨之搬移；26.2 shared 與 dimension saved-data 不能混放；legacy active journal 不可猜測拆分；線上 adapter 的全世界 UUID 掃描須改為隔離且預檢重複；不同分支圖標籤與未初始化 remote 的發現；取消不等於世界回滾；秘密 masker 與中文終端寬度；大量 sidecar 集合的 capture／merge 費用；平臺事件涵蓋不完整時不能宣稱已達 player-touched 語意。

## 本次實作與後續缺口

本檔案規定全部 A–J 的目標。

相容過渡：Paper／Fabric 的遊戲內 init 暫保留既有批次入口；玩家範圍與追加詢問由任務 3／4 更新，CLI 與 core 已採新範圍。遊戲內 init 在觸及事件接線前明確使用 entities: all，避免既有放置實體失去追蹤。core／CLI 的新 creative 預設已改為 player-touched；任務 3／4 接事件後，平臺預設才一併切換。Paper／Fabric 的 fetch／push／pull 與 remote 指令暫以玩家目前維度執行，console 使用主世界；Hub PR 與背景通知仍保有主世界入口，任務 3／4 再接完整 UX。Phase 5 任務 1 的實測見下方最終驗收表；仍未接線的項目依後續任務表交接，不以設計目標代替完成狀態。

26.2 `data/minecraft/chunk_tickets.dat` 的 tickets 為無順序的集合，capture 依完整 NBT 固定排序，保留每筆座標、type、level、額外欄位與重複項；只消除 hash map rehash 的序列化順序差異，不排序其他 saved-data 清單，也不忽略票券內容。差異證據見 `.work/fabric-phase4/single-26.2-1791183422/final-difference.json` 與 `.work/phase5-complete-regression/single-26.2-ticket-lists.txt`。

26.2 saved-data 保留所有非時鐘欄位；只正規化 `data/minecraft/raids.dat` 的 `data.tick` 與 Paper `level_override[s].dat` 的 `data.game_time`。套用時保留目前時鐘，新組裝時補零值以符合遊戲 codec。1.21.11 缺少 `DragonFight.Gateways` 時依世界種子具體化預設順序；已存在（包括空或縮短）的清單保持原樣，不忽略終界進度。

測試語意更新：舊測試中 init(null) 表示全世界的場景改成明確 initAll；驗獨立 commit 的場景不再斷言同 snapshot UUID。Fabric log 的一次三維度初始化改驗三筆獨立 commit；Hub 索引改驗每維度各自的 commit。原有逐格、refs、父節點、衝突、pack 上限與秘密遮罩斷言保留，不刪除驗收。

驗收腳本相容：所有 `--format=json` 呼叫先讀 `{result,data}`；`--all` 的逐維度 batch，以及未指定維度的 branch／stash／tag／remote／conflicts 唯讀 batch，每筆資料另有自己的 `{result,data}`；log／diff 的 `data` 仍以維度 key 對應 graph／差異。conflicts 的主世界清單比較明確指定 `--dimension minecraft:overworld`；log 讀每維度的 graph `nodes`／`id`／`parents`，不再把各維度歷史當作共用 snapshot 列表。Paper／Fabric Phase 4 的 remote 驗收以分別位於三個維度的真玩家設定 remote、push 與 pull，每次 push 驗單維度完成，最後仍要求三個本地 HEAD 與 Hub refs 完整相等；不可只把三維度斷言改成一維度。先載入受測維度再 init，避免首次載入與生成 chunk 被誤認為 remote 操作造成的變動。Fabric gametest 的等待與 HEAD 選擇只讀主世界歷史；三維度獨立 commit 不再充當一次主世界提交。跨維度 UUID fixture 明確在各維度建立分支，先移除來源維度的 UUID 再 switch 目的維度，仍驗唯一性、乘客與主世界 HEAD 不變。

完整世界的 CLI fixture 使用 `init --with-dimensions all`；需要跨維度 branch／switch／push／pull／tag 時明確使用 `--all` 或逐維度呼叫。舊實體往返 fixture 在 init 前明確設定 `entities: all`，保留既有實體覆蓋範圍，不改新 creative 預設。Fabric Phase 2／3 各 checkpoint、Paper／Fabric dedicated、Paper-pair 與 Phase 4 最終離線 verify 要求三維度皆 exit=0、state=COMPLETE，且 chunk／section／biome／實體／metadata 差異全為零。repo 定位使用世界／維度內的 `.worldgit`；release ZIP 的排除檢查涵蓋任意層 `.worldgit`。

續跑 fixture 修正：1.21.11 Fabric dedicated 使用 Paper baseline 時，必須把 `world_nether/DIM-1` 與 `world_the_end/DIM1` 一併複製到 vanilla 世界根；只複製 `world/` 會遺漏兩個受測維度。單人 Phase 4 在各維度 init 前等待完整 11×11 視距範圍載入並存檔，gametest 以持續推進客戶端 ticks 的 future 等待執行 server 任務，避免換維度後仍生成 chunk、讓 pull 正確拒絕 dirty 世界。Paper fixture 的首次維度載入及 forceload 設定在 init 前以 `save-all flush` 固定（Folia 不提供此指令，使用線上 capture 的 saved-data IO barrier）；大型編輯的暫時 plugin chunk tickets 在 commit 前釋放，不把測試工具的載入狀態當分支內容。這些調整不忽略 saved-data、流體或 ticks；完整 refs、三維度驗證與 200 區域 multipart 斷言保持。最終 verify 失敗時保留逐格差異與 metadata 的前後 NBT blob，然後才清理測試世界。Fabric Phase 2 先在主世界驗完傷害保護及十秒到期，再獨立切換地獄，避免地獄操作另開保護時窗干擾原斷言。

Paper Phase 4 截圖原本暫時解除伺服器全域 freeze；三維度玩家在線時，會觸發終界龍戰首次掃描與地獄排程 ticks，停服後產生真實 metadata 差異。續跑保存了前後 `level.nbt` 與 ticks blob（`.work/paper-phase4/paper-1.21.11-1791163700/`），確認差異後，截圖 fixture 改為只解除客戶端 tick freeze，讓原版 TextDisplay renderer 初始化，伺服器仍凍結。不忽略 `DragonFight`、不補提交掩蓋差異；仍用真伺服器封包、字面留言／click 行為檢查及真客戶端截圖驗證。

26.2 Paper／Folia 相容缺陷：`PaperLiveWorld` 原本從磁碟讀取新增的維度 saved-data，而 chunk flush 只等待 terrain／entity／POI，記憶體的 `forceload` tickets 要到停服才出現。`NmsBridge.saveMetadata` 的 26.2 adapter 在全域 scheduler 複製 PDC／dirty saved-data 並排入 vanilla 背景 IO，repo 執行緒等待該 future 完成後才讀 metadata；不在 tick 執行緒等待 IO，也不忽略票券或改動 commit 內容。Paper↔Fabric fixture 在既有 bench.lock 內核對來源時間，必要時重建 plugin，避免續跑誤用舊 jar。

26.2 `TicketStorage` 的 codec 從 hash map 打包 `chunk_tickets.dat`，讀入或 restore 後只改變票券列表排列也會產生假的 metadata 差異。core 僅對此已知 saved-data 的 `data.tickets` 以完整 NBT 編碼穩定排序，保留每筆座標、level、type、未知欄位與重複項；其他 saved-data／列表順序不變。單元測試驗相同票券重新排列相等，實際欄位變更仍有差異，未知 saved-data 的順序仍受追蹤；原始前後 121 筆票券證據位於 `.work/fabric-phase4/single-26.2-1791183422/ticket-comparison.json`。

盤點涵蓋 `paper/tools`、`fabric/tools`、`hub/scripts`、`cli/tools`、`scripts`、`tools/e2e`、core 驗收工具及 Hub fixture。遷移測試刻意建立舊位置，單人證據工具保留舊存檔回退；Hub 的手寫 shared snapshot／publication fixture 保留作任務 2 過渡與舊歷史相容測試。core 新 fixture 改驗每維度獨立 snapshot UUID，逐格、refs、父節點、衝突、pack 上限與秘密遮罩要求均保留。盤點索引：`.work/phase5-complete-regression/compatibility-audit.json`。

實作修正：各維度 remote URL 展開成 `/git/<owner>/<world>/<dimension>.git` 後，`HubClient.endpoint` 必須還原世界 REST／網頁 URL（含 reverse proxy 前綴及編碼的自訂維度），否則 PR、留言與 webhook 比對會把 world／dimension 誤當 owner／world，得到 404。共用 client 增加實際 REST request／網頁連結與拒絕 userinfo／query 的測試。
CLI 完成、init 詢問與規則狀態文字使用共用 YAML MiniMessage 純文字樣板；`WGIT_LOCALE=en_us|zh_tw` 選擇語言，預設沿用 zh_tw。

### 後續任務的介面與待辦

| 任務 | 可直接使用的介面 | 待接功能與驗收 |
|---|---|---|
| 2：Hub（已實作） | 單維度 BareWorldMerge、core 圖／progress／result；graph／operations／revisions REST；schema v5 | 每維度 PR／policy／預設分支／圖、SSE／輪詢、ZIP、全部動作結果／錯誤複製與舊資料遷移已接線；驗收狀態見下方任務 2 實作紀錄。 |
| 3：Paper／Folia | `WorldRepositories.initializable/initDimensions`、`WorldOperations.live(layout, access, id)`、`IgnoreEditor`、共用 graph／progress／result、`PlayerTouchedEntities.touch` | 玩家所在維度 init、主世界追加按鈕、console 明確維度、wgit／git 別名與衝突開關、ignore GUI／Brigadier、bossbar 與完成／複製；觸及事件依 owner／repo queue 串行寫 sidecar，完整覆蓋 WE／FAWE／指令／互動／乘客／UUID 轉換及跨維度；事件完成前保持 entities: all。驗自然牛不入庫、命名後入庫、盔甲座可還原且自然實體不刪除，補 Folia owner／停服回歸。 |
| 4：Fabric | 同上，另有 `ServerRuntime.live(id, action)`／`region(id, action)` 的單維度相容入口 | 單人／dedicated 的玩家／console 範圍、別名、HUD／bossbar、ignore 畫面及聊天 graph（另評估圖形畫面）、完成／複製；伺服器驗證與權限不依賴 client。接觸及 mixin／事件、第三方編輯與跨維度 UUID 預檢後再切 creative 預設，補單人及 dedicated 真客戶端驗收。 |

三個後續任務都應在原本的 repo executor 建立 `OperationProgress` context，讓同一操作的 capture／merge／transfer／assemble 沿同 operation id 回報；callback 回平台 owner 或網頁 dispatcher 呈現，不能在世界鎖內寫聊天／HTTP。`PlayerTouchedEntities.touch` 要由平台串行化呼叫，與完整 capture／commit 共用 repo 作業順序，不能直接在各 Folia region 並行改檔。單維度傳輸可用 `WorldRemotes(layout.repository(id), Map.of(id, path), credentials)`，必須 close 後才開 `WorldOperations`，避免重入 repo 鎖。

### 驗收中發現與修正

Paper r3（`.work/phase5-paper-regression-r3.log`）的 `paper-26.2-phase2` 通過。預期的 PARTIAL／MERGING commit 拒絕仍保留失敗 Outcome 與訊息，只對這兩種已處理的狀態不印 stack trace；其他例外仍記錄完整原因，沒有放寬遊戲行為斷言。

整合 r6 的 1.21.11 已通過；26.2 在 Paper 啟動後、腳本送出 freeze 前，消耗地獄的排程熔岩刻，新增一格熔岩並改變 12 個 chunk 的 ticks。r7 的診斷工具編譯錯誤已修正，r8 保留完整逐格／tree 差異證據。r9 提早送 console freeze 時，Paper 建立的 command source 尚無 level，發生 NPE，因此改用獨立驗收插件在 POSTWORLD onEnable 凍結、早於第一個遊戲 tick；該 fixture 不進正式產物。不忽略 lava／ticks，也不放寬每維度 verify COMPLETE／零差異與無錯誤 log 的要求。baseline 全程只複製並比較 manifest。

r10 的 1.21.11／26.2 均通過：真 Hub jar＋SQLite、主世界與地獄不同分支、世界 ZIP 攜帶 repo／解壓後 status 與 graph、各維度 push、指定不同分支 clone、Paper 開服無錯誤、兩維度 verify 為零差異；1.21.11 外置與 26.2 舊單人 repo 遷移後 HEAD／歷史保留且可 commit／push。兩版 `baselineUnchanged=true`，結果見 `.work/phase5-acceptance-r10/{1.21.11,26.2}-result.json`。

### 任務 1 最終驗收（2026-10-04）

| 命令 | 結果與證據 |
|---|---|
| `python3 paper/tools/phase4_regressions.py --case paper-26.2-phase2` | r3 通過、exit 0。PARTIAL 拒絕／取消恢復、逐格內容、UUID、光照與 POI、最終離線零差異及無例外日誌均通過；`.work/phase5-paper-regression-r3.log`、`.work/paper-phase4/regressions-1791134904/results.json`。 |
| `python3 cli/tools/phase5_acceptance.py --results-dir .work/phase5-acceptance-r10` | 1.21.11／26.2 全過、exit 0；兩版各有兩個維度零差異，Hub／Paper 無錯誤，兩種舊位置遷移與 baseline manifest 比較通過；`.work/phase5-integration-r10.log`、`.work/phase5-acceptance-r10/results.json`。 |
| `python3 fabric/tools/accept-dedicated.py 26.2` | 本輪 r3 的 44 項檢查全過、exit 0：真 dedicated＋Xvfb client、第二觀看者、重啟、ghost／Set／Resolve、1,000 chunk 合併、200 區域 multipart／重連與最終離線零差異；server／client exit 0、server_problems=[]、兩個 port 關閉。`.work/phase5-fabric-regression-r3.log`、`.work/fabric-acceptance/dedicated-fabric-26.2-20261004-202411/result.json`。 |
| `python3 cli/tools/phase5_cli_terminal.py` | 先前已通過且相關 CLI 未再修改：真 PTY 主世界詢問／進度、非 TTY／JSON 範圍、NO_COLOR／color=never 無動畫、Ctrl+C exit 130；`.work/phase5-terminal-results.json`。 |
| `flock .work/bench.lock env GRADLE_USER_HOME=.work/gradle-home ./gradlew --no-daemon --configure-on-demand --max-workers=1 build` | 最終 r9 全綠、exit 0，1 分 11 秒，80 tasks（4 executed／76 up-to-date）；299 項 JUnit、0 failure／error／skip，其中 core 117 項、Phase5Test 21 項、CLI 5 項。`.work/phase5-full-build-r9.log`、`.work/phase5-unit-results-final.json`。 |
| `git diff --check` | 通過；未改 experiments、未 commit／push 專案。完整變更清單與驗證索引：`.work/phase5-final-validation.json`。 |

重型腳本本身取得 bench.lock，沒有再套外層鎖。最終測試程序均已結束；Hub 8091–8099、Paper 使用的測試 port、Fabric 25702／25712 均確認關閉。驗收伺服器、世界副本、ZIP、Hub SQLite 與複製的 jar／classes 已清理，保留雜湊、日誌、結果與截圖；本輪暫存副本未超過 4 GiB，不清除既有 baseline、工具或快取。

Fabric 本輪量測：1,000 chunk 合併 150.30 秒，平均 TPS 20.00、p99 tick 54.16 ms、最長 tick 1,842.77 ms；六次區域選擇中位數 0.701 秒、最大 0.801 秒。這是固定 fixture 的回歸證據，仍不能保證大型生產世界的延遲上限。任務 2–4 的完整 UX、觸及事件與 Folia 專項驗收仍按上表交接；平台的 creative init 維持 entities: all，CLI 離線 creative 初始集合為空。

### 完整平台回歸最終驗收（2026-10-05）

| 命令 | 結果 | 證據 |
|---|---|---|
| `python3 paper/tools/phase4_regressions.py --case paper-1.21.11-phase4 --case folia-1.21.11-phase4 --case folia-26.2-polling` | exit 0；補跑 3/3＋首輪 13＝16/16 | `.work/phase5-complete-regression/paper-phase4-all-final.log`、`.work/phase5-complete-regression/paper-16-combined-results.json` |
| `python3 paper/tools/acceptance.py paper 1.21.11` | exit 0 | `.work/phase5-complete-regression/paper-accept-paper-1.21.11.log` |
| `python3 paper/tools/acceptance.py paper 26.2` | exit 0 | `.work/phase5-complete-regression/supplement-paper-26.2-basic.log` |
| `python3 paper/tools/acceptance.py folia 1.21.11` | exit 0 | `.work/phase5-complete-regression/paper-accept-folia-1.21.11.log` |
| `python3 paper/tools/acceptance.py folia 26.2` | exit 0 | `.work/phase5-complete-regression/supplement-folia-26.2-basic.log` |
| `python3 paper/tools/brigadier.py 1.21.11` | exit 0 | `.work/phase5-complete-regression/brigadier-1.21.11-resume.log` |
| `python3 paper/tools/brigadier.py 26.2` | exit 0 | `.work/phase5-complete-regression/brigadier-26.2-resume.log` |
| `python3 fabric/tools/regress-phase4.py` | exit 0 | `.work/phase5-complete-regression/fabric-regress-all-resume.log` |
| `python3 fabric/tools/accept-phase4.py 1.21.11` | exit 0 | `.work/phase5-complete-regression/accept-phase4-1.21.11.log` |
| `python3 fabric/tools/accept-phase4.py 26.2` | exit 0 | `.work/phase5-complete-regression/accept-phase4-26.2.log` |
| `python3 fabric/tools/accept-phase4-singleplayer.py 1.21.11` | exit 0 | `.work/phase5-complete-regression/rerun-accept-phase4-singleplayer-1.21.11-final-1791193949851061300.log` |
| `python3 fabric/tools/accept-phase4-singleplayer.py 26.2` | exit 0 | `.work/phase5-complete-regression/rerun-accept-phase4-singleplayer-26.2-final-1791193529447742308.log` |
| `python3 fabric/tools/accept-dedicated.py 1.21.11` | exit 0 | `.work/fabric-phase4/regressions-1791175208/dedicated-1.21.11.log` |
| `python3 fabric/tools/accept-paper.py 1.21.11` | exit 0 | `.work/phase5-complete-regression/fabric-paper-1.21.11.log` |
| `python3 fabric/tools/accept-paper.py 26.2` | exit 0 | `.work/phase5-complete-regression/fabric-paper-26.2.log` |
| `python3 fabric/tools/accept-paper-phase3.py paper 1.21.11` | exit 0 | `.work/paper-phase4/regressions-1791163700/paper-1.21.11-interop.log` |
| `python3 fabric/tools/accept-paper-phase3.py paper 26.2` | exit 0 | `.work/paper-phase4/regressions-1791163700/paper-26.2-interop.log` |
| `python3 fabric/tools/accept-paper-phase3.py folia 1.21.11` | exit 0 | `.work/paper-phase4/regressions-1791163700/folia-1.21.11-interop.log` |
| `python3 fabric/tools/accept-paper-phase3.py folia 26.2` | exit 0 | `.work/paper-phase4/regressions-1791163700/folia-26.2-interop.log` |
| `bash hub/scripts/phase4-acceptance.sh` | exit 0 | `.work/phase5-complete-regression/hub-phase4.log` |
| `flock .work/bench.lock env GRADLE_USER_HOME=/root/projects/ProjectCollection/WorldGit/.work/gradle-home npm_config_cache=/root/projects/ProjectCollection/WorldGit/.work/npm-cache PLAYWRIGHT_BROWSERS_PATH=/root/projects/ProjectCollection/WorldGit/.work/ms-playwright ./gradlew --no-daemon --configure-on-demand --max-workers=1 build` | exit 0；JUnit 305，failure／error／skip 皆 0 | `.work/phase5-complete-regression/full-build-final.log`、`.work/phase5-complete-regression/unit-results-final.json` |

| 補充命令 | 結果 | 證據 |
|---|---|---|
| `python3 paper/tools/acceptance.py paper 1.21.11 phase2-entities` | exit 0 | `.work/phase5-complete-regression/supplement-paper-1.21.11-phase2-entities.log` |
| `python3 paper/tools/acceptance.py paper 26.2 phase2-entities` | exit 0 | `.work/phase5-complete-regression/supplement-paper-26.2-phase2-entities.log` |
| `python3 paper/tools/acceptance.py folia 1.21.11 phase2-entities` | exit 0 | `.work/phase5-complete-regression/supplement-folia-1.21.11-phase2-entities.log` |
| `python3 paper/tools/acceptance.py folia 26.2 phase2-entities` | exit 0 | `.work/phase5-complete-regression/supplement-folia-26.2-phase2-entities.log` |
| `python3 paper/tools/acceptance.py folia 26.2 phase2-shutdown` | exit 0 | `.work/phase5-complete-regression/supplement-folia-26.2-phase2-shutdown.log` |
| `python3 paper/tools/acceptance.py paper 26.2 phase2-cancel` | exit 0 | `.work/phase5-complete-regression/supplement-paper-26.2-phase2-cancel.log` |
| `python3 paper/tools/acceptance.py paper 26.2` | exit 0 | `.work/phase5-complete-regression/supplement-paper-26.2-basic.log` |
| `python3 paper/tools/acceptance.py folia 26.2` | exit 0 | `.work/phase5-complete-regression/supplement-folia-26.2-basic.log` |

Paper 矩陣 16/16（首輪 13＋補跑 3；第三階段會再完整跑）、Fabric 矩陣同一輪 9/9；索引 `.work/phase5-complete-regression/paper-16-combined-results.json`、`.work/fabric-phase4/regressions-1791175208/results.json`。補充實體、取消與停服案例也全部 exit 0。早期失敗保留於 matrix／resume／supplemental 索引，最終選用最新完整通過的證據，未改寫歷史結果。

Fabric 單人 26.2 歷史失敗的 `data/minecraft/chunk_tickets.dat`：三維度各 121 張票券與所有欄位完全相同，只有原版 hash map 序列化順序不同。共用正規化只固定此清單順序，保留重複票券及所有欄位，不忽略檔案；歷史 blob 的完整 NBT 對照見 `.work/phase5-complete-regression/ticket-order-verified.json`。單人 fixture 的續跑建置改由共用 helper 執行實際存在的 `:cli:fatJar`／`:paper:plugin:jar`，修正執行間新增的錯誤 `shadowJar` 任務；新補跑證據見 `post-resume-results.json`，舊建置失敗與 metadata 失敗皆保留。 第一次新補跑的原世界 verify 已通過，但 clone 斷線後 CLI 遇到尚未釋放的 session.lock；fixture 現在等待原整合伺服器執行緒完全退出，維持既有 1200 tick 期限。因為共用 fixture 有變動，26.2 與 1.21.11 皆順序補跑，原世界、clone 初始及 clone 真客戶端重開後的三維度 verify 全部零差異。此輪新失敗亦完整保留於 post-resume 索引及個別日誌。 補上停服等待後，clone 的地獄自然更新差異也被完整 verify 揭露；單人 fixture 改在 SERVER_STARTING 即凍結整合伺服器，並斷言 clone 已凍結，避免在等待世界畫面及握手時先執行自然 ticks。凍結只在驗收 mod 啟用，不改正式平台；clone 失敗診斷也在刪除副本前保留。 完整 build 已成功；第一次清理檢查將外部 `/tmp/sculpt-e2e/server` 的 25599 listener 誤當成本任務資源。已逐次核對設定檔及 Minecraft status 身分為 Sculpt preview E2E，記錄於清理證據，不操作外部服務；所有 WorldGit 測試 ports 仍須關閉。沿用成功 build 並核對受測來源雜湊，只補做清理與報表；原 runner 的清理失敗證據保留於 cleanup-after-build.json。

所有自帶鎖腳本均直接呼叫，只有最後 Gradle build 外包 flock；同一 namespace 的 Java／node／Xvfb 測試程序已退出，測試 ports 關閉，bench.lock 可取得。暫存相對本輪起始增加 1.69 GiB（低於 4 GiB）；未改 experiments、未 commit。清理證據 `.work/phase5-complete-regression/cleanup-final.json`，逐命令結果 `.work/phase5-complete-regression/final-results.json`。

## 任務 2：Hub 動作盤點（2026-10-05，實作前）

盤點來源是全部 REST controllers 與 `hub/web/src/pages`／`main.ts`；完成結果由共用 HTTP 邊界產生，網頁 fetch 邊界呈現，可關閉的 aria-live 通知不隨換頁消失。保留既有 DTO，物件增加 `result`，陣列透過 `X-WorldGit-Result`（UTF-8 JSON 的 Base64）提供同一結果；錯誤另有 `errorReport`。

| 類別 | 全部寫入／使用者動作 |
|---|---|
| 帳號 | 登入、登出、申請註冊、驗證信箱、密碼更新、OAuth 連結／解除、管理員建立帳號 |
| PAT | 建立、撤銷、複製一次性 token |
| 世界 | 建立、刪除、公開／私人、每維度預設分支、完整世界 ZIP 準備與下載 |
| PR | 建立、編輯、關閉、舊 PR 確認維度、核准、要求修改、衝突 choices、單維度合併 |
| 留言 | 一般留言、座標釘選、回覆、編輯、刪除、清除未提交釘選 |
| release | 每維度 revision 選取、建立、刪除、ZIP 準備、下載 |
| Webhook | 建立、編輯／啟停、刪除、測試、讀投遞紀錄 |
| 授權 | 個人授權／撤權、團隊授權／撤權、分支保護設定／刪除 |
| 組織 | 建立組織／團隊、設定／移除組織成員、增加／移除團隊成員 |
| 通知與操作 | 標為已讀、可取消操作的取消、合併預覽計算、push 索引與 webhook 排程 |
| 本機介面 | 複製指令／錯誤、Clipboard 失敗後選取文字；唯讀切換分支／維度／相機以目前選取狀態呈現 |

SUCCESS／NO_OP／PARTIAL／FAILED／CANCELLED 共用 core 結果。非同步啟動只呈現「已開始」，operation 終態才呈現完成；失敗不宣稱回滾。驗證狀態及交接見下一節。


## 任務 2：Hub 實作紀錄（2026-10-05）

A、D、F、H、I 與上表的 Hub 契約已接線：世界只作 ACL／配額容器，每維度的 refs／HEAD／tag／PR／policy／ahead／behind／push 獨立；source／target lease、衝突、審核及留言都限定 PR 維度。新 PR 合併只呼叫 `BareWorldMerge(Path, DimensionId)`；主世界更新不會讓地獄 PR 選擇失效。世界歷史可並列各維度 commit，不再拼 snapshot UUID，分支頁的未對齊／partial group 警告已移除。

圖頁每維度使用 core lane／before／after／edges／labels 繪 SVG；--all、PR merge annotation、commit／3D 連結、鍵盤連結、手機水平捲動、亮／暗色、截斷提示及 admin 的 per-repo HEAD 設定已提供。API 先驗 read／PAT scope，limit 1–10000、refs 2000、traversal 20000、JSON 4 MiB。smart HTTP 與 CLI 的 world URL 發現保持原路徑。

PR merge、合併預覽、ZIP 準備及 push 索引／webhook outbox 排程使用同一工作內的 operation id。Hub 排程先配置 UUID，core 新增接受外部 id 的 OperationProgress constructor，工作 thread 再開 context；WorldAssembler 在切維度時回報 dimension，沒有改組裝語意。前端使用同源 SSE，斷線改有界輪詢；網頁所有 JSON 動作由 fetch 邊界呈現可關閉 aria-live 終態，OAuth callback 結果經同 session 一次性 outcome 取回。作業列表只列自己的世界作業，世界頁可監看 push 後處理。queue 滿而 refs 已接受的 push 記 PARTIAL，沒有宣稱資料回滾；PR refs 發布不可取消，可取消 preview／ZIP。同步 PR／留言動作在授權後直接取 PR／留言的專案維度，完成結果與 ErrorReport 不依賴 body 重複帶 dimension。

release／完整世界 ZIP 接受每維度 revision map，省略的維度使用該 repo HEAD；release 保存固定 commits，無須同名 tag。保留 WorldAssembler 的 metadata／DataVersion／bytes／time 預算及下載角色檢查。既有同步 ZIP／merge／preview 保留；新網頁採非同步準備再下載。

schema v5 冪等 transaction 保留 world ACL、merged PR／release 的固定 commits；舊 open PR 要確認維度及重新審核；確認時同步更新留言的專案維度，原有釘選座標維度保留。舊 branch_rules 以 * 繼承全部維度，沒有放寬。舊 root journal 僅供 recovery／finalize，新 journal 在單維度 repo。舊 publication 讀寫入口仍保留 namespace／FF／immutable 既有限制，內容不再要求指向別的 repo，且不能替代受保護分支的 merge 授權。

### 設計的具體化與相容差異

- 世界 ACL／配額保留，維度不是另外創一個 world DB row；角色適用容器各維度，branch policy 與預設 HEAD 各自獨立。
- 物件 DTO 增加 result；陣列保持原形狀，用 X-WorldGit-Result 的 UTF-8 JSON Base64 帶相同結果。HTTP status 與原成功欄位保留，錯誤增加 errorReport。非同步 202 只代表已排程。
- operation 是記憶體有界觀察介面：128 records／64 events／8 active per actor／2 workers＋8 queue；完成 15 分鐘 TTL。SSE 32 global／2 per actor／30 秒，前端 500 ms、1800 次輪詢。ZIP 準備／保留最多 2、5 分鐘 TTL、成功下載清除。重啟不續看作業，應讀 PR／release／refs 最終狀態。
- ErrorReport 純文字由伺服器已知秘密＋core 模式遮罩產生，header 不重複完整報告且 message／operation 分別限 256／200 字元；未匹配 API／Servlet 錯誤也帶安全報告；Clipboard 不可用呈現已選取 textarea。報告上限沿用 core 8192 字元，內部 SQL／stack 不當作使用者訊息。
- webhook progress 到 outbox 排程為止，HTTP retry／DELIVERED／FAILED 另查 deliveries；下載 progress 到準備完成，實際保存由瀏覽器下載管理處理。
- 舊 Hub 測試的跨 snapshot 配對期望改為各維度 commit／refs 檢查；Phase 4 驗收仍對照 CLI 三維度 heads 與容器 API，並保留兩版世界 ZIP／Paper verify／CSP／XSS／衝突逐格斷言。

### 留給任務 3／4 的 Hub 介面

| 用途 | 契約 |
|---|---|
| 每維度 PR／預覽／policy | PR create body.dimension；list 的 dimension filter；preview query dimension；policy body.dimension 與 delete query dimension。舊未帶維度客戶端預設主世界，任務 3／4 應明確帶玩家維度。 |
| 圖與預設分支 | `GET /api/v1/worlds/{o}/{w}/dims/{repo}/graph?all=true&limit=N`，原樣使用 core lanes／edges／labels；PUT default-branch 是 admin，不由玩家本地 HEAD 推測 Hub 預設。 |
| 作業 | POST operations 的 merge／preview／world-zip／release-zip；GET operations?limit=20 列同 actor 作業，GET id／events／download；POST id/cancel；scope 與角色仍需驗。push sideband 回傳 Hub operation UUID，CLI JSON 目前保留自己的 transfer id，可另外查有界作業列表。 |
| 完成與錯誤 | OperationResult 五狀態、X-WorldGit-Operation／X-WorldGit-Result；錯誤 body.errorReport.text 是已遮罩的安全純文字，遊戲端仍須用 MiniMessage 的安全 literal／可複製 UI。 |
| 通知與留言 | push／pr.opened／pr.reviewed／pr.merged 的 data.dimension；release 的 data.dimensions＋commits；Comment.dimension 是專案維度，新 pin.dimension 必須相同；舊 PR 釘選保留原座標維度。不要等待 publication 配對後才提示單維度更新。 |
| 保留任務 | Paper／Folia bossbar、GUI ignore、玩家範圍、wgit/git 別名、觸及 sidecar；Fabric 的單人／dedicated／client UX 同屬任務 3／4。Hub 不辨識玩家觸及事件，不改平台 creative init。 |

最終驗證結果與可攜證據追加於本節下方；安全細項見 [Phase 5 審查](../hub/docs/security-review-phase5-2026-10-05.md)，動作完整盤點在上一節，決策 #122–#128。

### 任務 2 最終驗證（2026-10-05）

| 命令 | 結果／證據 |
|---|---|
| `flock .work/bench.lock env GRADLE_USER_HOME=.work/gradle-home npm_config_cache=.work/npm-cache ./gradlew --no-daemon --configure-on-demand --max-workers=1 build` | exit 0，1 分 17 秒、80 tasks；JUnit 315 項（Hub 74），failure／error／skip 皆 0。`.work/phase5-full-build8.log`。 |
| `npm --prefix hub/web run test` | exit 0，4 files／26 項；新增 graph lane、五種完成狀態、未知進度／ETA、錯誤遮罩測試。`.work/phase5-web-tests-final2.log`。 |
| `npm --prefix hub/web run lint`／`run typecheck`／`run build` | 均 exit 0；安全 lint、TypeScript 與 Vite 產物通過。`.work/phase5-web-lint-final.log`、`.work/phase5-web-typecheck-final.log`；最後前端 build 由 Phase 4 腳本執行。 |
| `env SKIP_BUILD=1 RESULTS_DIR=.work/phase5-phase4-final-1_21_11-r3 hub/scripts/phase4-acceptance.sh --version 1.21.11`；26.2 使用 `RESULTS_DIR=.work/phase5-phase4-final-26_2`／`--version 26.2` | 兩次均 exit 0，同一最終 Hub／CLI jar 雜湊；兩版各 5 次瀏覽器流程（CSP／console／JS error 0）、三次 Paper 重開（主世界／地獄／終界 verify COMPLETE、內容差異 0）、refs 相符、baseline 未變。各目錄 results.json 與同名 .log。 |
| `env SKIP_BUILD=1 RESULTS_DIR=.work/phase5-hub-acceptance-final5 hub/scripts/phase5-acceptance.sh` | exit 0；真 Hub jar＋SQLite／CLI：獨立地獄 PR／tag、主世界 refs 不變、graph、merge／release／push progress、秘密遮罩、真 Phase 4 schema 重啟遷移、瀏覽器 SSE→輪詢與 8 張截圖。`.work/phase5-acceptance-final5.log`。 |
| `git diff --check` | 通過；不改 experiments，未 commit。 |

前端命令使用 `npm_config_cache=.work/npm-cache` 的專案絕對路徑；瀏覽器使用 `PLAYWRIGHT_BROWSERS_PATH=.work/ms-playwright` 的專案絕對路徑與 Phase 4 同一系統 Chrome／SwiftShader／繁中字型。兩個驗收腳本自持鎖，沒有再外包 flock；`SKIP_BUILD=1` 僅沿用已完整 build 的 jar，腳本預設會自行建置。

可攜摘要及受測來源／jar／截圖 SHA-256：[acceptance.json](../hub/docs/phase5-security/acceptance.json)；完整變更清單：[changed-files.txt](../hub/docs/phase5-security/changed-files.txt)。截圖位於 `hub/docs/screenshots/phase5/`：graph-light／dark／mobile、operation-progress、success-notification、error-notification-copy、copy-fallback、pr-dimension-selector。

整批補跑曾因 SIGTERM（exit 143）中斷；另一輪 1.21.11 出現單次瀏覽器 HTTP 400，原始日誌及失敗 JSON 都保留。Phase 4 瀏覽器腳本補上至多 50 筆失敗 request 的 URL／status／已遮罩 ErrorReport 診斷，沒有排除錯誤或放寬零 console／JS／CSP 的斷言；之後完整 1.21.11 與兩版最終 jar 驗收沒有再現，未把無法重現的情況宣稱為已修正。先前整批兩版成功證據也保留在 `.work/phase5-phase4-acceptance2/`。

最終 Hub 8091–8099、Paper 25691／25692 均確認無 listener，bench.lock 可取得；所有本輪驗收 sessions 已退出，世界／SQLite／複製 jar／ZIP／私有瀏覽器設定已清理。只保留日誌、結果、雜湊及截圖，不清除既有快取／baseline。未修改 Paper／Fabric 或 experiments，未 commit。容器映像與 container-smoke 留給主對話；本輪只確認 Containerfile／compose 不需改動，SQLite 通過不代表 PostgreSQL 實機驗收已跑。
