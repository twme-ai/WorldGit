# 15 — WorldGit 使用手冊

本手冊依「你是誰」與「你用哪個平臺」整理 WorldGit 的實際用法。共通規則、CLI、Hub、Paper／Folia 與 Fabric 已更新至 2026-10-07；Phase 5 四個任務皆已完成並通過驗收。設計理由見 [09 決定表](09-roadmap-open-questions.md)，驗收與交接見 [16](16-phase5-design.md) 及各模組 README。

---

## Axiom 建築工具

Paper 玩家在客戶端安裝對應 Minecraft 版的 Axiom，管理員在伺服器另裝 AxiomPaper；Fabric 單人直接搭配 Axiom，dedicated 則兩端安裝。已核對 Axiom 6.1.3／AxiomPaper 6.0.1 的 1.21.11 與 26.2。Folia 不啟用 Axiom 整合。

Axiom 編輯後照常 `/wg status`、`/wg commit -m <訊息>`。方塊、BE、biome 按內容追蹤；creative 也記錄 Axiom 生成／操作的實體。作者是 chunk 級貢獻摘要；Paper 的區域 bypass 權限及缺乏來源的操作可能保持未知。1.21.11 不追蹤日間時計／Axiom 註記；26.2 的 world clocks／註記則由 saved-data 捕捉，時間變動也可能使線上 switch 因 metadata 預檢拒絕，須離線還原。gamerules 等設定沿用 world-meta 規則與平台限制。

WorldGit 正在 switch／restore／merge 等套用時，Axiom 修改會被拒絕並顯示訊息。Paper 局部鎖保守擋該世界，Fabric 保守擋全部 Axiom 請求；請等完成再編輯。尚未完成的 Paper buffer 會取消，不在完成後重播。客戶端區塊會重送，但 Axiom 自己的 undo／clipboard／editor cache 不會改寫，套用或拒絕後需重整預覽。外框／鬼影重疊時用 `/wg clear`；ghost 是歷史疊圖，不能當成已套用的世界。

Fabric 存檔的離線 CLI 需提供同版 jar：`wgit --world <世界> --mod-pack axiom=<Axiom.jar> verify HEAD`；只讀 datapack 資源，不執行模組。完整研究與驗收證據見 [17 — Axiom](17-axiom.md)。26.2 註記沒有專屬線上快取重載整合。


## 目錄

1. [WorldGit 是什麼](#1-worldgit-是什麼)
2. [我該看哪一節](#2-我該看哪一節)
3. [安裝與取得](#3-安裝與取得)
4. [所有人都要知道的共通規則](#4-所有人都要知道的共通規則)
5. [單人玩家（Fabric 客戶端）](#5-單人玩家fabric-客戶端)
6. [伺服器管理員：Paper／Folia](#6-伺服器管理員paperfolia)
7. [伺服器管理員：Fabric 專用伺服器](#7-伺服器管理員fabric-專用伺服器)
8. [伺服器上的玩家／建築者](#8-伺服器上的玩家建築者)
9. [CLI 使用者（離線、備份、腳本）](#9-cli-使用者離線備份腳本)
10. [Hub 網頁使用者（協作者、審核者）](#10-hub-網頁使用者協作者審核者)
11. [Hub 架站者](#11-hub-架站者)
12. [完整工作流程範例](#12-完整工作流程範例)
13. [疑難排解](#13-疑難排解)
14. [已知限制](#14-已知限制)
15. [四端指令對照表](#15-四端指令對照表)

---

> Phase 5 四端已完成並通過驗收（2026-10-07）：遊戲玩家 init 只取所在維度、新 creative 使用 player-touched；所有 repo 操作有維度目標、明確終態與可複製錯誤。完整契約與驗收狀態見 [16](16-phase5-design.md)。

## 1. WorldGit 是什麼

WorldGit 把 Minecraft 世界當成 git 的工作區（working tree）：

| git 概念 | WorldGit 中的意義 |
|---|---|
| repo | **每個維度一個 git repo**（主世界、地獄、終界、資料包維度各一個）；主世界 repo 另存 level.dat 等世界層級資料 |
| commit（存檔點） | 單維度快照，各維度有自己的 snapshot UUID／歷史；批次 commit 只是便利操作 |
| working tree | 正在玩的世界本身 |
| branch | 各維度獨立，例如主世界留在 `main`，地獄使用 `cavern` |
| diff | 方塊、方塊實體（箱子內容等）、實體、生態域、世界設定的差異；綠＝新增、紅＝移除、黃＝修改、紫＝衝突 |
| merge | 方塊級三方合併；不同位置的修改自動合併，同位置不同修改成為「衝突區域」，可逐區選 ours／theirs／base |
| remote／push／pull | 推送到 WorldGit Hub（或一般 git 主機），與他人協作 |
| PR | 在 Hub 網頁上審核、用 3D 檢視比對、選衝突區域後合併 |

**四個端點共用同一份歷史格式**，可以互相交替使用：

| 端點 | 用途 |
|---|---|
| **Fabric 模組** | 單人世界、Fabric 專用伺服器；客戶端可畫 diff 鬼影、衝突清單畫面、座標留言 |
| **Paper／Folia 插件** | 多人伺服器的線上存檔點、復原、切換、合併、遠端協作 |
| **CLI（`wgit`）** | 世界關閉時的離線操作、備份、腳本、clone 世界 |
| **Hub** | 自架網頁：歷史瀏覽、3D diff、PR、審核、release 下載、webhook |

同一個世界可以在 Paper 上 commit、關服後用 CLI 檢查、push 到 Hub、別人 clone 下來用 Fabric 單人開啟，歷史完全相同。

支援的 Minecraft 版本：**1.21.11（Java 21）與 26.2（Java 25）**。

---

## 2. 我該看哪一節

| 你是… | 主要平臺 | 先讀 | 再讀 |
|---|---|---|---|
| 自己玩單人、想要存檔點／試驗分支 | Fabric 客戶端 | [§5](#5-單人玩家fabric-客戶端) | [§4](#4-所有人都要知道的共通規則)、[§12.1](#121-單人存檔點與試驗分支) |
| 經營 Paper／Folia 伺服器 | Paper 插件 | [§6](#6-伺服器管理員paperfolia) | [§12.2](#122-伺服器事故回滾)、[§12.3](#123-團隊建造與-pr) |
| 經營 Fabric 專用伺服器 | Fabric 模組（伺服端） | [§7](#7-伺服器管理員fabric-專用伺服器) | [§6](#6-伺服器管理員paperfolia)（觀念相同） |
| 在伺服器上建築的玩家 | 原版或 Fabric 客戶端 | [§8](#8-伺服器上的玩家建築者) | [§10](#10-hub-網頁使用者協作者審核者) |
| 想做備份、腳本、CI，或下載別人的世界 | CLI | [§9](#9-cli-使用者離線備份腳本) | [§12.4](#124-發布地圖-release) |
| 在網頁上審核 PR、留言 | Hub 網頁 | [§10](#10-hub-網頁使用者協作者審核者) | [§12.3](#123-團隊建造與-pr) |
| 架設 Hub 給團隊或社群用 | Hub 伺服器 | [§11](#11-hub-架站者) | [§10](#10-hub-網頁使用者協作者審核者) |

---

## 3. 安裝與取得

目前沒有發行版下載，請從原始碼建置。需要 JDK 21（建置與 1.21.11）與 JDK 25（26.2 與 Hub），Hub 前端另需 Node 22+。

```sh
git clone https://github.com/twme-ai/WorldGit.git
cd WorldGit
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export GRADLE_USER_HOME="$PWD/.work/gradle-home"
./gradlew --no-daemon --configure-on-demand --max-workers=1 build
```

| 產物 | 路徑 | 放到哪裡 |
|---|---|---|
| CLI | `cli/build/libs/wgit.jar`（或 repo 根目錄的 `./wgit` 啟動腳本） | 任意位置，`java -jar wgit.jar …` |
| Paper／Folia 插件（兩版共用一個 jar） | `paper/plugin/build/libs/worldgit-paper-0.1.0-SNAPSHOT.jar` | 伺服器 `plugins/` |
| Fabric 1.21.11 | `fabric/mc1_21_11/build/libs/worldgit-fabric-1.21.11-0.1.0-SNAPSHOT.jar` | 客戶端或伺服器 `mods/` |
| Fabric 26.2 | `fabric/mc26_2/build/libs/worldgit-fabric-26.2-0.1.0-SNAPSHOT.jar` | 客戶端或伺服器 `mods/` |
| Hub | `hub/build/libs/worldgit-hub.jar`，或 `hub/Containerfile` 建容器 | 見 [§11](#11-hub-架站者) |

Fabric 需要的環境：

| Minecraft | Java | Fabric Loader | Fabric API |
|---|---|---|---|
| 1.21.11 | 21 | 0.19.5 | 0.141.6+1.21.11 |
| 26.2 | 25 | 0.19.5 | 0.161.0+26.2 |

WorldGit 的 Fabric jar 已內嵌 core、JGit、Jackson、Adventure 等依賴，只需另裝 Fabric API。客戶端與專用伺服器用同一個 jar。

---

## 4. 所有人都要知道的共通規則

### 4.1 世界資料存在哪裡

每個維度具有自己的 HEAD、分支、tag、stash、MERGING 與 remote，repo 位於該世界／維度資料夾內：

| 版面 | repo 位置 |
|---|---|
| 主世界（兩版） | `<world>/.worldgit/`；26.2 地形在 dimensions 底下仍如此 |
| 1.21.11 原版／單人 | `<world>/DIM-1/.worldgit/`、`<world>/DIM1/.worldgit/` |
| 1.21.11 Paper | `<world>_nether/DIM-1/.worldgit/`、`<world>_the_end/DIM1/.worldgit/` |
| 26.2／自訂維度 | 該維度 `dimensions/<ns>/<path>/.worldgit/` |

壓縮該世界／維度資料夾就會帶 repo；release ZIP／export 是發布副本，仍不帶歷史。舊外置 `<server>/.worldgit/<world>/<dim>` 與舊單人 `<world>/.worldgit/<dim>` 可讀，世界停止後用 `wgit migrate` 搬移。

1.21.11 clone／export 即使未選地獄或終界，也保留空的 `DIM-1/`、`DIM1/`，不建立其地形或 repo；Paper 初次開啟會沿用主世界生成與終界設定。ZIP 也保存這些空目錄。

每 repo 的 `.wgignore`、worldgit-repo.yml 與 player-touched.yml 隨 commit／push 分享；worldgit.yml（palette／entity-tolerance）及 remotes.yml 是本機非秘密設定，不推送。apply／MERGING／stash／transfer 狀態也屬各 repo。玩家、secret 不得放進世界 repo。

### 4.2 「乾淨的工作區」

切換分支、stash pop、merge、pull 都要求工作區乾淨（自上次 commit 後沒有未提交的改動，含 untracked 新 chunk）。不乾淨時：

- 先 `commit` 存起來，或
- `stash push` 暫存，或
- 對 switch 使用 `--stash`（自動暫存）／`--force`（丟棄改動）。

### 4.3 合併狀態 MERGING

合併有衝突時，世界進入 **MERGING**：

- 無衝突的部分已經寫進世界；每個衝突區域暫時顯示 **ours**（你目前分支的版本）。
- 一般 commit、switch、stash、reset 與自動 commit 都會被擋下。
- 逐區選擇 ours／theirs／base／manual（manual＝以你自己手動修改後的現況為準）。
- 全部解決後 `merge --continue`（或 `commit -m`）產生合併提交；反悔用 `merge --abort` 逐格回到合併前。
- 伺服器重啟後 MERGING 狀態會恢復。

**方塊狀態保持快照原樣**：切換區域時不會觸發鄰居更新，柵欄連接、紅石狀態與快照逐格相同。交界處可能需要人工檢查，畫面會列出「交界提示」；紅石區域會提示「請測試電路」。

### 4.4 PARTIAL（套用中斷）

線上套用被取消、伺服器崩潰或關閉時，世界可能停在部分套用的 **PARTIAL** 狀態，此時禁止 commit。恢復方式：

- 一般切換／還原中斷：`switch <目標> --force` 或 `reset --hard` 重新完整套用。
- 合併切換中斷：`merge --abort`。

### 4.5 線上與離線的分工

伺服器運行中，**插件／模組**負責所有操作；**CLI 只在世界關閉時使用**（偵測到 `session.lock` 被伺服器持有會拒絕）。

線上套用有兩類保守限制，遇到時請關服用 CLI 處理：

- 不刪除 chunk（目標版本沒有的 chunk 會保留並標為 untracked）。
- 地圖、記分板、世界生成等世界層級設定有差異時，線上會拒絕套用。

### 4.6 安全與憑證

- **PAT（個人存取權杖）**是在 Hub 網頁建立的 token，CLI、插件、模組都用它 push／pull。
- **永遠不要把 PAT 寫進 URL、remote 設定、config.yml 或世界資料夾。** 只放環境變數或權限 600 的 credentials 檔。
- 遠端通知（webhook／定時 fetch）**只會提示「有新版本」，永遠不會自動套用**；必須有人明確執行 pull 並確認。

---

## 5. 單人玩家（Fabric 客戶端）

### 5.1 安裝

1. 安裝對應版本的 Fabric Loader 與 Fabric API。
2. 把 WorldGit Fabric jar 放進 `.minecraft/mods/`。
3. 進入單人世界。單人世界的擁有者不需開作弊即可使用所有指令。

### 5.2 第一次使用

```text
/wg init                       # 建立目前維度的 repo 與第一個快照（預設 creative 範本）
/wg init --template survival   # 生存世界：排除暫態資料、非 persistent 生物等
```

`init` 只建立玩家目前維度；主世界 init 後會提供追加地獄／終界／全部的可點選按鈕。第一次進入新維度後可在該維度再 init；也能用 `/wg init --dimension minecraft:the_nether` 或 `/wg init --all` 明確選取。creative 預設只追蹤玩家觸及的實體，自然牛不入庫、命名後入庫；還原盔甲座不刪除自然實體。

### 5.3 日常：存檔點、歷史、差異

```text
/wg status                     # 目前世界相對 HEAD 改了什麼
/wg status --show              # 在世界中畫出改動的 section／chunk 外框
/wg commit -m 蓋好城門         # 建立存檔點；沒有改動不會產生 commit
/wg log                        # 最近的存檔點（可加數量 1–50）
/wg diff                       # HEAD → 目前世界
/wg diff HEAD~1 HEAD --blocks  # 兩個版本間的逐格明細
/wg diff --show                # 在世界中畫出逐格鬼影
/wg preview <版本> --radius 8  # 預覽「目前世界 → 某版本」會變成怎樣（不改世界）
/wg preview off                # 關閉預覽（或 /wg clear）
```

鬼影的意義：新增＝綠色實線＋目標方塊、移除＝紅色半透明原方塊、修改＝黃色虛線＋淡色目標方塊、衝突＝紫色閃爍。

**自動 commit**：定時、離開世界、關閉遊戲時會自動建立存檔點（可在 `config/worldgit-server.yml` 調整）。

### 5.4 復原與試驗分支

```text
/wg restore HEAD~1 --chunks 2               # 把玩家周圍半徑 2 chunk 還原到上一版（HEAD 不動）
/wg restore HEAD~3 --box 0 60 0 31 80 31    # 只還原一個方塊範圍（含端點）
/wg restore HEAD~1 --chunks 2 --dry-run     # 先看會改多少，不動世界

/wg branch create 實驗塔                    # 建立分支
/wg switch 實驗塔                           # 切換（世界原地變成該分支內容）
/wg switch main --stash                     # 有未提交改動時先自動暫存再切
/wg branch list
/wg branch delete 實驗塔

/wg stash push 還沒想好的屋頂
/wg stash list
/wg stash pop                               # 要求原基底與乾淨工作區
/wg reset --hard                            # 丟棄所有未提交改動
/wg cancel                                  # 中止進行中的套用（留下 PARTIAL，見 §4.4）
```

套用期間會顯示 bossbar、暫停世界 tick、攔截編輯；你和範圍內的玩家在操作中與結束後 10 秒不受摔落、窒息、溺水傷害。

### 5.5 合併與衝突畫面

```text
/wg merge 實驗塔                     # 不同位置的改動自動合併，直接產生合併提交
/wg merge 實驗塔 --no-commit         # 合併後停在 MERGING，先檢查再提交
/wg merge 實驗塔 --strategy-option theirs   # 衝突一律選對方（無衝突部分照常合併）
```

有衝突時按 **`G`**（可在「按鍵設定 → WorldGit」修改）或輸入 `/wg conflicts` 開啟**衝突清單畫面**，選取一個區域後可以：

| 按鈕 | 作用 |
|---|---|
| Ghost ours／theirs／base | 只在你的畫面疊上半透明預覽，不改世界 |
| Set blocks ours／theirs／base | 真的把世界中這區換成該版本（還不算解決） |
| Resolve ours／theirs／base／manual | 換成該版本並標記已解決；manual＝保留你手動修改後的現況 |
| Teleport | 傳送到區域上方 |

未解決的區域在世界中有紫色外框，解決後變灰。全部解決後：

```text
/wg merge --continue      # 產生合併提交
/wg merge --abort         # 或放棄合併，回到合併前
```

也可以用指令操作：`/wg resolve <編號|all> ours|theirs|base|manual`、`/wg conflict-select <編號|all> …`（只切換不標解決）、`/wg revert <commit>`、`/wg cherry-pick <commit>`。

### 5.6 客戶端顯示設定

```text
/wgc palette auto|default|colorblind   # 色票（auto＝跟隨伺服器）
/wgc seethrough true|false             # 鬼影穿牆
/wgc status                            # 握手狀態
/wgc clear                             # 清除本機所有鬼影
/wgc reload
```

細部設定在 `config/worldgit-client.yml`（明細距離、最大距離、每幀建置量、留言顯示上限等）。

### 5.6.1 分支圖、進度與 ignore 編輯器

`/wg log --graph` 顯示聊天分支圖：hover 看完整提交，點選填入 diff。`/wg graph` 或 H 開啟可捲動圖畫面，選取提交後可複製完整 ID、填入 diff 或 switch dry-run；畫面最多 200 提交，更早歷史用聊天 `--page`。G 仍開衝突清單。

長操作有 vanilla BossBar 與模組 HUD，顯示階段、維度、百分比／不定進度與 ETA，完成後保留終態數秒。每個指令以及自動／登出／存檔退出 commit 都有明確完成摘要；錯誤後的 `[複製]` 可取得遮罩秘密的 ErrorReport。

`/wg ignore gui` 或 K 開啟 `.wgignore` 編輯器。可新增、刪除、排序、啟停規則，新增框即時檢查語法。修改先顯示 HEAD preview，再按「確認修改」，120 秒內有效；HEAD 或原規則變更後須重新預覽，MERGING 時禁止修改。server 再驗單人 owner／寫入權限與規則；下一次 commit 保存規則歷史。也可使用 `/wg ignore list|add|remove|move|enable|disable|test|check|preview`；不帶 selector 的 test 取準星目標。

所有 repo 命令預設目前維度；`--dimension <id>` 明確選取，`--all` 逐維度非原子執行、各自回報。目標旗標放在 commit／stash 訊息、ignore 規則、PR 標題或留言正文之前。

### 5.7 把單人世界放上 Hub、或下載別人的世界

上傳（需要先在 Hub 建立 PAT，見 [§10.2](#102-建立-pat)）：

1. 設定 PAT：環境變數 `WGIT_TOKEN`，或在 Fabric config 目錄建立 `config/credentials.yml`（權限 600）：

   ```yaml
   credentials:
     https://hub.example.com:
       mode: bearer
       token: YOUR_PAT
   ```

2. 遊戲內：

   ```text
   /wg remote add origin https://hub.example.com/alice/my-world
   /wg push
   ```

每個存檔有自己的 remote 設定；PAT 存在使用者層級，不會跟著存檔或 clone 移動。

下載別人的世界直接開玩：

```sh
java -jar wgit.jar clone https://hub.example.com/alice/castle ~/.minecraft/saves/castle
```

clone 出來的資料夾就是完整的單人世界（光照與 POI 由遊戲重建），放進 `saves/` 就能直接開啟，裡面也已經有 `.worldgit/` 與 remote 設定，之後可在遊戲內 `/wg pull`。

單人世界的 pull、PR 與座標留言用法與伺服器相同，見 [§8.3](#83-遠端協作指令)。單人世界**永遠不開 webhook port**；可在 `config/worldgit-server.yml` 開啟定時 fetch 提示新版本。

---

## 6. 伺服器管理員：Paper／Folia

### 6.1 安裝

1. 把 `worldgit-paper-0.1.0-SNAPSHOT.jar` 放進 `plugins/`（1.21.11 與 26.2、Paper 與 Folia 共用同一個 jar；版本不符會明確停用插件）。
2. 啟動伺服器，玩家在目前維度執行 `/wg init`（生存服用 `/wg init --template survival`）；主控臺必須用 `/wg init --world world --dimension minecraft:overworld`，或以 `--all` 明確初始化世界群。
3. 依需要調整 `plugins/WorldGit/config.yml`。

### 6.2 權限

所有寫入類指令預設僅 op。`worldgit.admin` 包含所有正式指令與 `worldgit.notify`（接收通知）。

| 權限 | 指令 | 預設 |
|---|---|---|
| `worldgit.command.init`／`status`／`commit` | `/wg init`、`/wg status`、`/wg commit` | op |
| `worldgit.command.log` | `/wg log` | 所有人 |
| `worldgit.command.diff` | `/wg diff` | op |
| `worldgit.command.clear` | `/wg clear` | 所有人 |
| `worldgit.command.reload` | `/wg reload` | op |
| `worldgit.command.restore`／`switch`／`branch`／`stash`／`reset`／`cancel` | Phase 2 復原與切換 | op |
| `worldgit.command.merge`／`resolve`／`conflicts`／`tool`／`revert`／`cherry-pick` | Phase 3 合併（`conflict-select` 與 `resolve` 共用權限） | op |
| `worldgit.command.remote`／`fetch`／`push`／`pull`／`pr`／`comment` | Phase 4 遠端協作 | op |
| `worldgit.command.ignore`／`tag`／`verify` | 規則編輯、標籤、驗證 | op |
| `worldgit.debug` | `/wg debug`（開發用，預設只有主控臺） | false |

Paper／Folia 會依權限把完整指令樹傳給客戶端；預設非 op 玩家只看得到 `/wg log`、`/wg clear`、`/wg help`。`/wg help [子指令]` 只列出你可用的指令，點擊用法可填入聊天列。tool、diff、clear 與衝突預覽限玩家；debug 對玩家另需明確授予 `worldgit.debug`。

### 6.3 設定檔重點（`plugins/WorldGit/config.yml`）

| 區段 | 說明 |
|---|---|
| `auto-commit.interval-minutes` | 定時自動 commit，預設 15 分鐘；小變動最晚 30 分鐘合併寫入 |
| `auto-commit.min-changed-sections` | 生存服可調高，避免自然變化（作物、水流）頻繁產生 commit |
| `dirty-poll-interval-ticks` 等 | 掃描頻率、每 tick 複製量、timeout |
| `show.*` | 無模組玩家的 BlockDisplay 描邊上限與顯示秒數 |
| `language` | 主控臺語言（預設 zh_tw）；玩家依客戶端語言顯示 |
| `remote.*` | 遠端協作，見 [§6.7](#67-遠端協作與-hub) |
| `aliases.git`／`aliases.wgit` | 裸別名開關（預設 true）；worldgit:git 一直保留，衝突不覆蓋 |
| `feedback.*` | 終態預設停留 3 秒；title／sound 預設關，auto-notify 預設 true |

訊息可在 `plugins/WorldGit/lang/zh_tw.yml`、`en_us.yml` 覆寫個別鍵，`/wg reload` 生效。

**自動 commit 的觸發**：定時、玩家登出（提交的是**整個世界**目前的改動，不只該玩家）、伺服器關閉。Paper 在關閉流程內提交；Folia 在所有世界存檔完成後以離線路徑提交，結果寫在 `plugins/WorldGit/shutdown-commit.log`。kill -9 或崩潰不會有關閉前 commit。

**作者歸屬**：依世界群、維度、玩家、chunk 記錄，commit 帶 `Co-authored-by`；單維度 commit 不消耗其他維度的記錄。

### 6.4 WorldEdit／FAWE

WorldEdit 為選用依賴，會自動記錄操作的 chunk 與作者。使用 FAWE 時請在 FAWE 的 `config.yml` 加入：

```yaml
extent:
  allowed-plugins:
    - org.worldgit.paper
```

`/wg restore … --selection` 可直接使用你的 WorldEdit cuboid 選區。

### 6.5 日常操作

```text
/wg status [--full] [--show]
/wg commit -m 活動場地完成
/wg log 20
/wg diff --show --radius 6          # 玩家附近明細；hover 看前後狀態、點擊填入傳送指令
/wg restore "HEAD~1" --selection      # 用 WorldEdit 選區還原
/wg restore "HEAD~2" --chunks 3 --dry-run
/wg restore HEAD --box ~ ~ ~ ~3 ~4 ~5 --dry-run   # 兩個相對於指令來源的方塊座標
/wg switch event-map --stash
/wg branch event-map
/wg stash push|pop|list|drop
/wg reset --hard
/wg cancel
```

`/wg`、`/wgit`、`/git`、`/worldgit`（另有 `/worldgit:git`）使用 Paper Brigadier：聊天列會上色、逐參數提示，錯誤型別會以紅字指出位置。補全分支／revision 可查看短 hash 與訊息，stash 可查看訊息；log 數量限 1–100、diff 半徑限 1–32、restore 的 chunks 半徑限 0–256。既有 `--xxx` 旗標可任意排列，selection／chunks／box 擇一。含特殊字元的分支／revision 補全會加雙引號，舊未加引號寫法仍可執行。

commit 的 `-m` 與 stash 訊息取後面全部文字；PR 建議 `/wg pr create --source topic --target main 標題`，原本標題後放旗標也可用。若標題／留言本身含 `--source`、`--target` 或 `--here`，以雙引號括住文字，避免當成選項。PR／衝突編號建議用純數字（如 `/wg resolve 1 ours`）；`#1` 仍相容，但原生整數提示會標紅。

`/wg reload` 只重載語言覆寫。指令透過 Paper Lifecycle 註冊，在伺服器需要重建指令時會重新註冊；更新插件 jar 或伺服器設定請重新啟動。第三方熱重載未驗收。

沒裝 Fabric 模組的玩家看 `--show` 時，會用只有自己看得到的發光 BlockDisplay 描邊代替鬼影，`show.display-seconds`（預設 60 秒）後自動消失。

**線上套用的保護**：操作期間全伺服器 tick freeze（玩家仍可移動）、攔截玩家編輯／活塞／爆炸／流體／紅石／WorldEdit；範圍內玩家受保護不受摔落、窒息、溺水傷害。只有全部驗證成功才廣播「已切換到 X」。直接改 NMS 的第三方插件需自行呼叫 `WorldGitPlugin.isEditLocked(world)` 配合。

量測參考：1,000 chunk 切換，Paper 約 48 秒、Folia 約 34–37 秒，TPS 維持約 19.6–20。

### 6.5.1 維度、分支圖與排除規則

所有 repo 指令預設玩家目前世界群的所在維度；`--dimension <id>` 指定其他維度，`--all` 逐維度完成並各自回報，不回滾已成功的維度。`--world <載入世界名>` 可補全並指定世界群。console 的 init 必須明確指定世界與維度／all；其他 repo 指令預設伺服器第一個世界的主世界。commit／stash／PR／留言的目標旗標放在 greedy 文字前。

```text
/wg init                         # 玩家只初始化所在維度；主世界結果可點「也 init 地獄／終界／全部」
/wg status --dimension minecraft:the_nether --full
/wg commit --all -m 各維度存檔
/wg log --graph                   # 文字 lane、branch／tag／HEAD；hover 完整 commit，點選填入 diff
/wg log --graph --page 2
/wg ignore                       # 箱子清單；左鍵刪除，右鍵啟停，Shift 點擊上移，新增用聊天
/wg ignore add entity minecraft:cow
/wg ignore confirm <預覽代碼>      # 或點聊天確認按鈕；120 秒內綁定執行者／目標／HEAD／原文
/wg commit -m 更新排除規則
/wg ignore test target           # 準星方塊／實體；test hand 測手持物品欄位
/wg ignore check
/wg tag 活動完成
/wg verify HEAD
```

每次修改先以 HEAD 顯示會排除的數量和樣本，再確認。MERGING 禁止修改；檔案行號是規則 ID，註解／空行會保留。確認只寫工作區 `.wgignore`，下一 commit 記錄規則歷史。未提交世界內容不在 HEAD preview 的統計中。

新 creative repo 完整追蹤方塊／BE／biome／地形，實體使用 `entities: player-touched`，只存玩家放出／更改的實體（玩家除外）：自然牛不入庫，命名後入庫；蛋、summon／data entity、放置／互動、WE／FAWE、乘客／載具、UUID 轉換與跨維度傳送保留資格。init 前觸及於本次開服期間暫存並在 init 繼承。restore 保留集合外自然實體；如果目標 UUID 已在另一維度，套用前拒絕並指出維度。新 survival repo 使用 `entities: all`，再由 `.wgignore` 排除非 persistent 生物、掉落物等；舊 repo 維持原 entities 設定。

每個正式動作都有 SUCCESS／NO_OP／PARTIAL／FAILED／CANCELLED 終態與摘要、目標及耗時；耗時操作以 BossBar 顯示階段／比例／ETA，console 節流文字。執行者與有 `worldgit.notify` 的觀察者可見，成功綠／失敗紅終態預設三秒後移除。錯誤後的獨立 `[複製]` 可複製已遮罩的版本／UTC／維度／operation id 報告，hover 來自語言檔。

### 6.6 線上合併

```text
/wg merge castle-v2
/wg conflicts               # 54 格箱子 GUI：每區座標、格數、雙方作者、紅石警示；點擊傳送
/wg tool                    # 合併工具（指南針）：站進區域右鍵循環 ours→theirs→base，Shift+右鍵標記解決
/wg conflict-select 3 theirs
/wg resolve all ours
/wg merge --continue
/wg merge --abort
/wg revert <commit>
/wg cherry-pick <commit>
```

MERGING 期間有權限的玩家會看到紫色 bossbar「合併中：剩 N 個衝突」、衝突區域的發光外框，走進區域時動作列顯示編號與目前版本。Fabric 客戶端玩家可使用完整的衝突清單畫面（[§5.5](#55-合併與衝突畫面)）。

### 6.7 遠端協作與 Hub

**步驟 1：設定 PAT**（擇一）

- 環境變數 `WGIT_TOKEN`（以 Bearer 使用），或
- `plugins/WorldGit/credentials.yml`，權限必須恰為 600、不可是 symlink：

  ```yaml
  credentials:
    https://hub.example.com:
      mode: bearer
      token: YOUR_PAT
  ```

**步驟 2：設定 remote**

```text
/wg remote add origin https://hub.example.com/myteam/survival
```

或在 `config.yml` 設 `remote.hub-url`，遠端操作會自動補上預設 remote。

```yaml
remote:
  hub-url: ""                  # 空白則用 /wg remote add
  default-name: origin
  token-environment: WGIT_TOKEN
  credentials-file: credentials.yml
  timeout-seconds: 30
  fetch-interval-seconds: 0    # 0 關閉；啟用至少 60 秒，只 fetch＋提示
```

**步驟 3：日常**

```text
/wg push                      # 只能 fast-forward；被拒時會提示先 pull
/wg fetch
/wg pull                      # 顯示預覽：FF／三方合併、衝突區域數、受影響 chunk、估計時間；不改世界
/wg pull confirm <代碼>       # 120 秒內、同一位執行者確認後才套用
/wg pr create 新增碼頭 --source dock --target main
/wg pr list
/wg pr view 3                 # 狀態、可否合併、審核數、可點擊的 Hub 連結
```

- pull 確認時會重新 fetch；若遠端在預覽後又變了，會拒絕並要求重新 pull。
- 衝突會進入 MERGING，用 [§6.6](#66-線上合併) 的方式解決，完成後 `/wg push`。
- **PR 的合併與核准只在 Hub 網頁進行**（需要 3D 檢視與衝突選擇）。
- `/wg push` 只推送已 commit 的歷史，不會自動建立 commit；pull 前工作區必須乾淨。

**步驟 4（選用）：接收 Hub 通知**

webhook 接收器預設關閉。啟用：

```yaml
remote:
  webhook:
    enabled: true
    bind: 127.0.0.1
    port: 25731
    path: /worldgit/webhook
    secret-environment: WGIT_WEBHOOK_SECRET
    secret-file: webhook.secret      # 32–4096 字元，權限 600
    max-body-bytes: 65536
    requests-per-minute: 60
```

然後在 Hub 世界設定頁新增 webhook：URL 指向上述位址、填同一個 secret（Hub 要求 32–256 字元，插件接受 32–4096，請取兩者交集）、事件勾 `push` 與 `pr.merged`。Hub 預設拒絕內網與 loopback 位址，自架在同一臺機器時要在 Hub 設定精確的 `allowed-hosts`（[§11.4](#114-webhook-與-ssrf)）。對外公開時請放在 TLS 反向代理後面。

收到通知後插件會在背景逐維度 fetch、驗證各自傳輸，然後提示有 pull 權限的線上玩家與主控臺「遠端有新版本，`/wg pull` 檢視」。**永遠不會自動套用**。無法對外開 port 的伺服器可改用 `fetch-interval-seconds` 定時 fetch。

**座標留言**

```text
/wg comments                    # 列出世界的釘選留言
/wg comments 3 --here           # PR #3 中與你目前 chunk 相交的留言
/wg comment 3 這裡的牆要加高 --here   # 以 PAT 帳號身分留言，帶你目前的維度與座標
/wg comments show 3             # 在世界中顯示留言文字（只有你看得到）
/wg comments hide
```

留言以 TextDisplay 顯示，只對請求者可見、不存檔、不會被 WorldGit 快照捕捉；範圍釘選以粒子外框顯示。最多 64 則、只顯示已載入 chunk；離線、換維度、hide 時清除。

### 6.8 事故處理速查

| 狀況 | 做法 |
|---|---|
| 有人炸了出生點 | `/wg diff --show` 確認範圍 → `/wg restore HEAD --box …` 或 `--selection` 局部還原 |
| 需要回到昨天的整個世界 | `/wg log` 找版本 → `/wg branch backup-now`（先保留現況）→ `/wg switch <commit> --force` |
| 套用到一半當機（PARTIAL） | `/wg switch <目標> --force` 或 `/wg reset --hard` |
| 合併中途當機 | 重啟後 MERGING 自動恢復；或 `/wg merge --abort` |
| 線上拒絕（要刪 chunk／世界設定不同） | 關服 → CLI `wgit restore`／`switch`（[§9](#9-cli-使用者離線備份腳本)）→ 開服 |

---

## 7. 伺服器管理員：Fabric 專用伺服器

Fabric 專用伺服器與 Paper 插件**功能對等**：存檔點、復原、切換、合併、遠端協作都可使用，觀念與 [§6](#6-伺服器管理員paperfolia) 相同。差異如下。

### 7.1 安裝與權限

- 伺服器與玩家客戶端使用同一個 WorldGit Fabric jar；伺服器需 Fabric API。
- 權限以 op 等級控制：**讀取類預設等級 0**（status、log、diff、remote list、fetch、pr list/view、comments），**寫入類預設等級 2**；主控臺可執行全部指令。可在 `config/worldgit-server.yml` 調整。

### 7.2 設定檔

| 檔案 | 內容 |
|---|---|
| `config/worldgit-server.yml` | 語言、預設範本、指令權限、自動 commit、伺服器身分、預覽上限、`remote` 區段 |
| `config/worldgit-client.yml` | 客戶端顯示（伺服器上不需要） |
| `config/credentials.yml` | PAT（權限 600） |
| `config/worldgit/lang/<locale>.yml` | 訊息覆寫 |

remote 區段：

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

憑證與通知設定變更需重啟伺服器。

### 7.3 指令差異

- 指令與 Paper 幾乎相同；Fabric 多了 `/wg preview <版本> [--radius r]`、`/wg info`、`/wg conflict-preview`，合併工具用客戶端的衝突清單畫面（`G` 鍵）取代 Paper 的指南針與箱子 GUI。
- 主控臺 init 必須帶 `--dimension <id>` 或 `--all`；其他 repo 操作預設主世界。玩家預設目前維度；`--all` 逐維度非原子執行。局部 restore 的中心以來源座標為準。
- `wg`、`wgit`、`git`、`worldgit` 共用入口。YAML `aliases.wgit`／`aliases.git` 可停用，需重啟；其他模組已占用 `/git` 時不覆蓋。
- 原版玩家能看 BossBar、完成摘要與錯誤複製；沒有模組時 `.wgignore` 使用指令與聊天 preview／confirm。圖形編輯器、圖畫面與 HUD 需模組客戶端。`ignore-permission-level` 預設 2。
- 自動 commit 在玩家登出、定時與關機時觸發；MERGING 期間全部跳過。

### 7.4 玩家客戶端

- 裝了 WorldGit Fabric 模組的玩家：可使用衝突清單畫面（Ghost／Set blocks／Resolve／傳送，多位玩家同步）、diff 鬼影、**客戶端渲染的座標留言**（左上 HUD＋世界內線框，伺服器不產生任何實體）。
- 原版客戶端：可使用聊天指令；`/wg comments show` 會說明需要 WorldGit Fabric 模組，但 `/wg comments` 文字清單仍可用。

量測參考：1,000 chunk 合併期間平均 TPS 約 20；單一衝突區域切換中位數約 0.7 秒。

---

## 8. 伺服器上的玩家／建築者

你能做什麼取決於伺服器給你的權限，以下是常見情境。

連 Paper／Folia 時，輸入 `/wg ` 按 Tab 只會列出你有權限的子指令，`/wg help` 也會過濾並提供可點擊填入的用法。不需安裝模組就能使用語法上色與參數提示；輸入 `/wg switch `、`/wg resolve ` 或 `/wg pr view ` 時，可在補全清單查看分支訊息、MERGING 衝突資料或 PR 標題。Hub 暫時無回應時補全可能為空，稍後再試；查詢不會改動世界。

### 8.1 查看與預覽

```text
/wg log                         # 通常所有人可用
/wg diff --show                 # 看附近改了什麼（需權限）
/wg clear                       # 清除自己的預覽
```

- **沒有模組**：預覽以只有你看得到的發光方塊描邊顯示；聊天訊息中的座標可點擊。
- **裝 Fabric 模組**（連 Paper／Folia／Fabric 伺服器都可）：看到完整的鬼影、衝突清單畫面（`G`）、留言 HUD。Fabric 模組只需裝在你的客戶端，伺服器裝 WorldGit 插件或模組即可。

### 8.2 合併期間

看到紫色 bossbar「合併中：剩 N 個衝突」時，世界正在合併。衝突區域有發光外框；若你有 resolve 權限，可以用 `/wg tool`（Paper）、`G` 鍵畫面（Fabric 客戶端）或 `/wg resolve` 協助選版本。若選 **manual**，先自己動手把區域改好再標記解決。

### 8.3 遠端協作指令

需要伺服器授予對應權限，並由伺服器（或單人世界的你）設定 PAT：

| 指令 | 作用 |
|---|---|
| `/wg fetch` | 下載 Hub 上的新版本（不改世界） |
| `/wg pull` → `/wg pull confirm <代碼>` | 預覽並確認套用遠端更新 |
| `/wg push` | 推送已提交的歷史 |
| `/wg pr create <標題> [--source 分支] [--target main]` | 從遊戲內開 PR |
| `/wg pr list`／`/wg pr view <#>` | 看 PR 狀態、點連結到網頁 |
| `/wg comment <#> <內容> --here` | 在你站的位置留言 |
| `/wg comments show [#]`／`hide` | 顯示／隱藏座標留言 |

留言內容一律當純文字顯示，不會解析顏色或點擊指令。

---

## 9. CLI 使用者（離線、備份、腳本）

Java 21+，只在世界停止時寫世界或 migrate。完整旗標見 [CLI README](../cli/README.md)；線上操作與 Hub 協作分別見本手冊的平臺／Hub 章節。

### 9.1 範圍與結果

`--world` 預設目前目錄：世界根指主世界，DIM-1／world_nether／dimensions/ns/path 指該維度；--dimension 可覆寫。變更預設單一維度，--all 逐維度獨立執行；commit 默認對有變動的已 init 維度各自提交，唯讀預設列全部。

`--format=json` 回傳 `{result,data}`，每個動作明確終止。SUCCESS／NO_OP／PARTIAL／FAILED／CANCELLED 的 exit code 是 0／0／2／1／130；結果含操作 id、摘要、耗時與遮罩後的錯誤報告。TTY 有進度動畫，非 TTY／JSON／NO_COLOR／color=never 沒有動畫。

### 9.2 初始化與分支圖

```sh
wgit --world world init --only
wgit --world world init --with-dimensions nether,end
wgit --world world/DIM-1 init
wgit --world world status --full
wgit --world world commit -m '完成入口'
wgit --world world --dimension minecraft:the_nether branch cavern
wgit --world world --dimension minecraft:the_nether switch cavern
wgit --world world log --graph --all
```

主世界互動詢問既存未 init 的地獄／終界；無 TTY／CI／JSON 不追加，自訂維度不詢問。--with-dimensions all 明確選全部既存維度；--only 不詢問。每維度 graph 有自己的 branch／tag／HEAD／tracking 與共用 lane，截斷會提示。

### 9.3 復原、合併與遠端

```sh
wgit restore HEAD~1 --box 0,60,0,31,80,31 --dry-run
wgit stash push -m '暫存'
wgit reset --hard
wgit merge feature
wgit conflicts
wgit resolve all --theirs
wgit merge --continue
wgit verify HEAD
wgit remote add origin https://hub.example/alice/castle
wgit --dimension minecraft:the_nether push origin cavern
wgit clone https://hub.example/alice/castle copy --branch minecraft:the_nether=cavern
wgit clone https://hub.example/alice/castle selected --dimension minecraft:overworld,minecraft:the_nether
wgit export --rev minecraft:overworld=v1 --rev minecraft:the_nether=cavern release.zip
```

switch／merge／stash／pull 只影響所選維度，其他維度可留在不同分支。MERGING／PARTIAL 也獨立；跨版本／資料包不一致仍拒絕。remote add 世界 URL 是批次便利，其他修改預設一維度。clone 各維度默認分支，組世界仍需要主世界 metadata。

### 9.4 忽略規則與遷移

```sh
wgit ignore list
wgit ignore add 'entity minecraft:item' --dry-run
wgit ignore remove 8
wgit ignore move 8 3
wgit ignore test 'block 0,64,0'
wgit ignore check --format=json
wgit migrate --dry-run
wgit migrate
```

ignore 使用檔案行號，保留註解、空行與順序；修改在下一 commit 記歷史，MERGING 期間禁止。creative 新 repo 的 `entities: player-touched` 只追蹤 UUID 集合中的實體；離線 CLI 沒有平台事件來源，沿用已保存的集合，離線 init 集合為空。CLI 文字輸出以自己的語系提示此限制；Paper／Fabric 遊戲內有平台事件來源，不顯示離線提示。地形／方塊／BE／biome 仍完整，新 survival repo 使用 `all` 搭配排除規則，舊 repo 維持原 entities 設定。Fabric ignore 編輯器與 Paper GUI 直接顯示原始註解行，不另加 `#`；停用規則仍可重新啟用。

migrate 必須世界停止，先複製驗證、再原子發布，保留舊備份且可重跑。舊版有未完成 journal 時先用舊版恢復，不猜測拆分。自行備份世界時要包含 `.worldgit`；export／release ZIP 不含 repo。PAT 用 WGIT_TOKEN 或權限 600 的使用者 credentials YAML，不放世界或 URL。

## 10. Hub 網頁使用者（協作者、審核者）

### 10.1 登入

- 管理員建立帳號，或（若站方開放）自助註冊並完成信箱驗證。
- 若站方啟用 GitHub／Discord／Microsoft 登入：先用本機帳號登入，在**設定**頁連結第三方帳號，之後可直接用第三方登入。

### 10.2 建立 PAT

右上角進入**設定**（`/settings`）→「存取 token」→「建立 token」，輸入名稱。**token 只顯示一次，請立即複製**。

- PAT 用於 git push／pull、CLI、伺服器插件與模組。
- 使用 git 時帳號欄任意、密碼欄填 token，或用 `Authorization: Bearer`。
- PAT 預設 90 天到期；scope 分 read／write／admin（透過 API 建立時可指定），實際權限仍取決於你在世界的角色。

### 10.3 世界與權限

- 首頁「新增世界」建立世界（小寫英數、`-`、`_`），或由站方開啟「push 到不存在的世界時自動建立」。
- 世界可設為公開（匿名可 clone）或私人（無權限者一律看到 404）。
- 角色：**owner／admin／write／read**，可授予個人或同組織的團隊；有多重授權時取最高者。
- 世界的**設定頁**（`/{owner}/{world}/settings`）：成員權限、可見性、受保護分支、webhook。

### 10.4 瀏覽世界

| 頁面 | 內容 |
|---|---|
| 世界首頁 | 維度分頁、俯視地圖、變動 chunk 疊圖、clone／push 指令 |
| commits | 每維度自己的歷史（自動 commit 折疊），不依 snapshot UUID 配對 |
| 單一 commit | 3D 檢視與上色 diff，可切一般／色盲色票 |
| branches | 選定維度的分支 head、作者、相對預設分支的 commit ahead／behind |
| 分支圖 | 每維度 lane／合併線、branch／tag／HEAD／remote tracking；合併 commit 可連到 PR |
| compare | 任意兩個版本的 3D 比較，網址可分享鏡頭位置 |

### 10.5 Pull Request 流程

1. **開 PR**：在 Pull Requests 頁先選**維度**，再選來源分支 → 目標分支，或從遊戲內 `/wg pr create`、CLI push 分支後在網頁開。
2. **檢視**：PR 頁有來源 commit 列表、3D diff、可合併狀態：

   | 狀態 | 意義 |
   |---|---|
   | ff／clean | 可直接合併 |
   | conflicts | 有衝突區域尚未選擇 |
   | needs-review | 受保護分支要求的核準數未達 |
   | changes-requested | 有人要求修改 |
   | unmergeable | 無法合併（例如版本不一致） |

3. **衝突選擇**：在 PR 頁逐區選 ours／theirs／base，3D 檢視可切換「依目前選擇的合併結果」。選擇存在 PR 上；**PR 所選維度的來源或目標 tip 改變，舊選擇與審核全部作廢**，頁面會提示重新選擇。
4. **審核**：Approve 或 Request changes。作者不能審核自己的 PR；選擇改變也會清除審核。
5. **座標留言**：在 3D 檢視點擊方塊釘選座標（或手動輸入，可選範圍），也可一般留言與回覆。點擊留言的座標會在 3D 中跳到該位置。遊戲內的玩家可用 `/wg comments show` 看到這些留言。
6. **合併**：按「合併 PR」。Hub 再檢查一次 tip 未變與權限，只在 PR 維度產生合併提交，其他維度 refs 保持原值，並觸發 `pr.merged` webhook。只支援 merge commit（不支援 squash／rebase／fork PR）。
7. **伺服器取得結果**：伺服器收到通知後提示管理員 `/wg pull`，確認後世界內容即與 Hub 一致。

### 10.6 受保護分支

在世界設定頁選維度再設定分支（例如地獄的 `main`）：

- 禁止 force push 與刪除；
- 「只能經 PR 合併」：直接 push 會被拒；
- 需要的核準數（0–10）。

owner／admin 也不能繞過。Phase 4 的舊規則會以 `*` 繼承到各維度；管理員可明確設定該維度規則或刪除繼承規則。

### 10.7 Release

在 Releases 頁輸入名稱、標題及說明，**每維度各選 revision**（branch／tag／commit；預設 HEAD，也就是該維度預設分支）。release 保存建立當下的固定 commit map，之後各分支移動不會改變下載內容。不同維度可來自不同分支，無須同名 tag 或相同 snapshot UUID。

世界首頁也提供每維度 revision 的完整世界 ZIP。下載先顯示組裝進度，完成後開始下載；解壓後可直接開啟（不含 repo、玩家資料）。至少需要主世界 metadata，各維度 DataVersion 必須相容。私人世界需要讀取權限；準備檔保留最多 5 分鐘，成功下載後刪除，再次下載會重新準備。

### 10.8 通知

右上角「通知」顯示與你相關的 PR 開啟、審核、合併事件。所有網頁動作完成都有成功／沒有變更／部分完成／失敗通知，換頁後仍可讀、可關閉，螢幕報讀器會播報。

合併、預覽及 ZIP 顯示階段、維度、計數／百分比及可估算的 ETA；未知總量使用不定進度。SSE 連線中斷會改為輪詢，可取消的預覽／ZIP 提供取消按鈕。PR 發布 refs 的階段不可取消。

每個錯誤通知或頁內 banner 都有「複製」，內容包含 code、operation id、維度、WorldGit／Hub 版本、UTC 與已遮罩的完整訊息。瀏覽器 Clipboard 無法使用時會開啟已選取的純文字，可按 Ctrl/Cmd+C。舊開放 PR 的頁面要求先確認維度，再重新選擇衝突與審核。

### 10.9 互動分支圖

世界頁選好維度後進入「分支圖」，或使用 `/{owner}/{world}/graph/{dimension-repo}`。勾選「所有 refs（--all）」可納入全部分支、tag 與 remote tracking。點 commit 開啟既有 3D／commit 頁，點 PR 編號可查看合併討論；Tab／Enter 可操作連結，手機可水平捲動，支援亮／暗色。預設讀取 200 列，最多 10,000 列，截斷時顯示提示。admin／owner 可在圖頁設定這個維度的預設分支。

---

## 11. Hub 架站者

### 11.1 本機快速啟動

```sh
export GRADLE_USER_HOME=$PWD/.work/gradle-home
./gradlew --configure-on-demand :hub:webBuild :hub:bootJar
WORLDGIT_HUB_DATA_DIR=./hub-data \
WORLDGIT_HUB_BOOTSTRAP_ADMIN_TOKEN=dev-token \
  java -jar hub/build/libs/worldgit-hub.jar --server.address=127.0.0.1 --server.port=8091
```

瀏覽器開 `http://127.0.0.1:8091/`，以 `admin` 登入；密碼設在 `WORLDGIT_HUB_BOOTSTRAP_ADMIN_PASSWORD`，留空則隨機產生並印在 log。

首次開啟 commit 3D 檢視時，Hub 會依世界版本從 Mojang 下載 client jar（SHA-1 驗證）產生貼圖，之後快取在資料目錄；離線環境可設 `worldgit.hub.assets.source-dir` 指向已解開的 client jar。

### 11.2 容器部署（Docker／Podman）

```sh
podman build --format docker -f hub/Containerfile -t localhost/worldgit-hub:latest .
WORLDGIT_ADMIN_PASSWORD_FILE=/安全路徑/admin-password \
WORLDGIT_ADMIN_TOKEN_FILE=/安全路徑/admin-token \
  podman compose -f hub/compose.yaml up -d --build
hub/scripts/container-smoke.sh            # 冒煙測試；DB=postgres 測 PostgreSQL
```

- 建置 context 必須是 repo 根目錄；podman 要加 `--format docker`，否則 HEALTHCHECK 會遺失。
- 容器以 uid 10001 執行，資料在 `/data`，埠 8080；secret 檔要讓 uid 10001 可讀（`chown 10001:10001 檔案 && chmod 400 檔案`，rootless 用 `podman unshare chown`）。
- `compose.yaml` 預設只綁 `127.0.0.1:8091`，對外請放在 TLS 反向代理後。
- systemd：`hub/deploy/` 有 Podman Quadlet 範例。
- 目前只實測 amd64。

### 11.3 重要設定

`application.yml` 中的項目都可用環境變數覆寫。

| 設定 | 環境變數 | 說明 |
|---|---|---|
| `worldgit.hub.data-dir` | `WORLDGIT_HUB_DATA_DIR` | 資料根目錄 |
| `worldgit.hub.bootstrap.admin-password(-file)`／`admin-token(-file)` | `WORLDGIT_HUB_BOOTSTRAP_ADMIN_*` | 首次啟動的管理員 |
| `worldgit.hub.auto-create-worlds` | `WORLDGIT_HUB_AUTO_CREATE_WORLDS` | push 到不存在的世界時自動建立（私人） |
| `spring.datasource.*` | `SPRING_DATASOURCE_URL` 等 | 預設 SQLite；改 `jdbc:postgresql://…` 並設 driver `org.postgresql.Driver` 切換 PostgreSQL |
| `worldgit.hub.git.owner-quota-bytes` | — | 每個 owner 的磁碟配額，預設 10 GiB |
| `worldgit.hub.git.max-pack-bytes` | — | 單次收包上限 95 MB |
| `worldgit.hub.auth.*` | — | 認證失敗限流（30 次／60 秒、5 次失敗鎖 300 秒）；成功的 PAT 請求另有 6000 次／60 秒額度 |
| `worldgit.hub.tokens.pat-days` | — | 新 PAT 預設期限（90 天） |
| `worldgit.hub.security.hsts` | — | 只在 TLS 反向代理後設 true |
| `worldgit.hub.security.trusted-proxies` | — | 可信代理的精確 IP，代理必須覆寫 X-Forwarded-For |
| `worldgit.hub.collaboration.merge-lock-timeout` | — | PR 合併等待鎖的上限，預設 10s |
| `worldgit.hub.collaboration.downloads.*` | — | release ZIP 的大小（512 MiB）、時間（300 秒）、並行數（2） |

### 11.4 Webhook 與 SSRF

世界 webhook 預設只能投遞到 **HTTPS 且所有 DNS 位址都是公網** 的目標，禁止 redirect。若遊戲伺服器與 Hub 在同一臺機器或內網，需設定精確的允許清單（沒有萬用字元）：

```yaml
worldgit:
  hub:
    collaboration:
      webhooks:
        allowed-hosts: [127.0.0.1]
        attempts: 5
        retry-seconds: 30
```

投遞失敗會以 30／60／120／240 秒退避重試最多 5 次，世界設定頁可查看投遞紀錄。

### 11.5 OAuth 與自助註冊

```yaml
worldgit:
  hub:
    collaboration:
      registration:
        enabled: false                # 開啟需要 SMTP（spring.mail.*）
        public-url: https://hub.example.org
        from: worldgit@example.org
      oauth:
        github:
          enabled: true
          client-id: ${GITHUB_CLIENT_ID}
          client-secret: ${GITHUB_CLIENT_SECRET}
        discord:
          enabled: false
        microsoft:
          enabled: false
server:
  servlet:
    session:
      cookie:
        secure: true                  # TLS 部署必設
```

第三方回呼 URL：`https://hub.example.org/login/oauth2/code/{github|discord|microsoft}`。OAuth 不會依 email 自動建立或合併帳號；使用者須先有本機帳號再連結。

### 11.6 上線前檢查清單

- [ ] TLS 反向代理、`cookie.secure: true`、正確 `public-url`、`hsts: true`
- [ ] 代理設定有限的 idle／write timeout（ZIP 下載與收包沒有硬 deadline）
- [ ] 只在代理確實覆寫 X-Forwarded-For 時設定 `trusted-proxies`
- [ ] 配額、磁碟餘裕與備份（資料目錄＋資料庫；webhook secret 以明文存在資料庫）
- [ ] 公開註冊需另訂保留字、冒充、檢舉與濫用治理政策（目前只有最小版本）
- [ ] 單一 Hub 實例：配額、鎖、session、限流都在本機，不能直接水平擴充

---

## 12. 完整工作流程範例

### 12.1 單人：存檔點與試驗分支

```text
/wg init
/wg commit -m 基地完成
/wg branch create 地下城實驗
/wg switch 地下城實驗
…盡情破壞…
/wg commit -m 地下城版本一
/wg switch main            # 世界回到基地完成時
/wg merge 地下城實驗       # 喜歡的話合併回來；不同位置的改動自動合併
```

### 12.2 伺服器事故回滾

1. 玩家回報出生點被炸：`/wg diff --show` 確認範圍。
2. 局部還原：`/wg restore HEAD --box -50 50 -50 50 120 50`（HEAD 不動，只還原範圍內）。
3. 若需要整個世界回到早上：`/wg log` 找到版本 → `/wg branch incident-now` 保留現況 → `/wg switch <commit> --force`。
4. 事後在 Hub 或 `wgit diff` 比對 `incident-now` 與還原版本，找出被破壞的內容。

### 12.3 團隊建造與 PR

角色：伺服器 A（Paper，正式服）、建築者 B（家裡用單人或 CLI）、審核者 C（網頁）。

1. **A 上傳**：管理員設定 PAT → `/wg remote add origin https://hub.example.com/team/city` → `/wg push`。
2. **Hub 設定**：C 在世界設定頁把 `main` 設為受保護（只能經 PR、需要 1 個核准），並新增 webhook 指向 A。
3. **B 建造**：
   ```sh
   wgit clone https://hub.example.com/team/city ~/.minecraft/saves/city
   ```
   用 Fabric 單人開啟 → `/wg branch create harbor` → `/wg switch harbor` → 建造 → `/wg commit -m 港口` → `/wg push origin harbor` → `/wg pr create 新港口 --source harbor`。
4. **C 審核**：在 PR 頁看 3D diff、在需要修改的位置釘選留言；B 在遊戲內 `/wg comments show <#>` 看到留言位置，修改後再 push（舊審核作廢）。C 核准，若有衝突就逐區選擇，按「合併 PR」。
5. **A 套用**：A 收到 webhook，線上管理員看到「遠端有新版本」→ `/wg pull` 看預覽 → `/wg pull confirm <代碼>` → 世界與 Hub 合併結果一致。
6. **B 同步**：`/wg switch main` → `/wg pull`。

### 12.4 發布地圖 release

```sh
wgit tag v1.0 HEAD -m '冒險地圖第一版'
wgit push origin main --tags
```

在 Hub 的 Releases 頁對 `v1.0` 建立 release，玩家從 release 頁下載 ZIP，解壓到 `saves/` 即可開玩。也可以離線產生：`wgit export v1.0 adventure-v1.zip`。

---

## 13. 疑難排解

| 訊息／狀況 | 原因與處理 |
|---|---|
| CLI：世界正在使用（session.lock） | 伺服器或遊戲還開著世界；關閉後再用 CLI，或改用遊戲內指令 |
| 工作區不乾淨，拒絕 switch／merge／pull | 先 `commit`、`stash push`，或 switch 加 `--stash`／`--force` |
| commit 被擋：PARTIAL | 上次套用中斷；`switch <目標> --force` 或 `reset --hard`（合併中斷用 `merge --abort`） |
| commit 被擋：MERGING | 合併尚未完成；解決所有區域後 `merge --continue`，或 `merge --abort` |
| 線上拒絕：需要刪除 chunk／世界設定不同 | 關服用 CLI 處理（[§4.5](#45-線上與離線的分工)） |
| 拒絕：DataVersion／.wgignore／資料包清單不同 | 不同版本或規則的快照不能互相套用；先統一版本與規則 |
| push 被拒：non-fast-forward | 遠端有你沒有的提交；先 `pull` 合併再 push |
| push 被拒：受保護分支 | 推到其他分支並開 PR |
| 401 | PAT 錯誤、過期或沒設定；重新建立並檢查 credentials 檔或 `WGIT_TOKEN` |
| 404（但世界確實存在） | 你沒有該私人世界的讀取權限（Hub 不透露私人世界是否存在） |
| 409 | PR 的分支 tip 已改變；重新載入，衝突選擇與審核需重做 |
| 429 | 請求過多或認證失敗太多次；等待 Retry-After 秒數 |
| 503 | Hub 暫時忙碌（推送、合併或下載進行中）；稍後重試 |
| credentials 檔被拒 | 權限必須恰為 600、普通檔案、不可是 symlink |
| `pull confirm` 被拒 | 超過 120 秒、不是同一位執行者，或遠端在預覽後又改變；重新 `/wg pull` |
| 收不到 webhook 通知 | 確認接收器已啟用、port 可達、secret 一致、Hub 的 `allowed-hosts` 有包含內網位址；查 Hub 世界設定頁的投遞紀錄；或改用定時 fetch |
| Fabric 客戶端看不到鬼影／衝突畫面 | 伺服器需裝 WorldGit；`/wgc status` 查握手狀態 |
| 生存服自動 commit 太頻繁 | 調高 `auto-commit.min-changed-sections`；用 survival 範本 |
| clone 後地形與原本不同 | 若使用 `modified-only`，未存的自然地形由種子重新生成；預設 `track: all` 不受影響 |

---

## 14. 已知限制

- **Phase 5 Fabric**：玩家維度 init／追加按鈕、別名、圖／ignore 畫面、BossBar／HUD、完成摘要、錯誤複製與 touched 事件已接線；驗收與各項限制見 [16](16-phase5-design.md)。分支圖畫面顯示至多 200 提交，更早歷史用聊天分頁；別名及伺服器設定改動需重啟。
- **線上套用**：不刪除 chunk；地圖、記分板、世界生成等世界層級差異需離線處理；跨 DataVersion 不支援（沒有 DataFixer）。
- **作者歸屬**是 chunk 粒度，沒有逐格 blame；玩家登出提交的是整個世界，沒有 per-player staging。
- **`modified-only`** 目前只記錄設定，平臺尚未自動蒐集玩家編輯集合，實際仍追蹤全部 chunk。
- **遠端**：沒有 SSH；沒有 fork PR、squash／rebase；遊戲內不能合併或核准 PR；真實 GitHub／Gitea 未實測（URL 樣板以 JGit 與 git http-backend 驗證）。
- **座標留言**只顯示當下已載入的 chunk，移動到新區域需重新 show。
- **Hub**：單一實例；儲存只支援本機磁碟；遠景尚未嵌入 BlueMap；只實測 amd64、Podman；公開註冊的治理政策尚未完成。
- **客戶端渲染**：沒有流體、特殊方塊實體、實體模型 renderer；未驗證 Sodium／Iris 與硬體 GPU。
- 量測數字來自受控平坦世界與凍結 tick，不代表大型自然世界或大量真實玩家的負載。

各項詳細原因與證據見 [14 Phase 4 進度](14-phase4-progress.md) 的「未完成事項」與各平臺 README。

---

## 15. 四端指令對照表

| 功能 | Fabric（單人／專用伺服器） | Paper／Folia | CLI | Hub 網頁 |
|---|---|---|---|---|
| 初始化 | `/wg init` | `/wg init` | `wgit init` | 新增世界 |
| 狀態 | `/wg status [--show]` | `/wg status [--show]` | `wgit status` | — |
| 存檔點 | `/wg commit -m` | `/wg commit -m` | `wgit commit -m` | — |
| 歷史 | `/wg log [--graph]`、H 圖畫面 | `/wg log` | `wgit log` | commits 頁 |
| 差異 | `/wg diff [--show]`、`/wg preview` | `/wg diff [--show]` | `wgit diff [--blocks]` | commit／compare 3D |
| 局部還原 | `/wg restore` | `/wg restore [--selection]` | `wgit restore` | — |
| 分支 | `/wg branch`、`/wg switch` | `/wg branch`、`/wg switch` | `wgit branch`、`wgit switch` | branches 頁 |
| 暫存 | `/wg stash` | `/wg stash` | `wgit stash` | — |
| 重設 | `/wg reset --hard` | `/wg reset --hard` | `wgit reset --hard` | — |
| 驗證 | `/wg verify [rev]` | — | `wgit verify` | — |
| 合併 | `/wg merge` | `/wg merge` | `wgit merge` | PR 合併按鈕 |
| 衝突處理 | `G` 畫面、`/wg resolve`、`/wg conflict-select` | `/wg conflicts` GUI、`/wg tool`、`/wg resolve`、`/wg conflict-select` | `wgit conflicts`、`wgit resolve` | PR 區域選擇 |
| revert／cherry-pick | `/wg revert`、`/wg cherry-pick` | 同左 | `wgit revert`、`wgit cherry-pick` | — |
| remote | `/wg remote` | `/wg remote` | `wgit remote` | — |
| fetch／push | `/wg fetch`、`/wg push` | `/wg fetch`、`/wg push` | `wgit fetch`、`wgit push` | — |
| pull | `/wg pull` → `confirm` | `/wg pull` → `confirm` | `wgit pull` | — |
| clone | （用 CLI clone 後放進 saves/） | — | `wgit clone` | clone 指令提示 |
| tag／release | `/wg tag list\|create\|delete` | `/wg tag` | `wgit tag`、`wgit export` | Releases 頁 |
| PR | `/wg pr create\|list\|view` | `/wg pr create\|list\|view` | — | 建立、審核、合併 |
| 座標留言 | `/wg comment`、`/wg comments show`（客戶端 HUD） | `/wg comment`、`/wg comments show`（TextDisplay） | — | 3D 釘選留言 |
| ignore | `/wg ignore`、K 編輯器（preview → confirm） | `/wg ignore`（箱子 GUI） | `wgit ignore` | — |
| 取消套用 | `/wg cancel` | `/wg cancel` | — | — |
| 客戶端設定 | `/wgc palette\|seethrough\|status\|clear` | — | — | 色票切換 |
