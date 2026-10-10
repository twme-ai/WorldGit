# 05 — 切換時間點、局部還原、worktree

原始筆記的痛點：「按照這個方法開世界，世界會越來越多而且很龐雜；在自己世界下切換會簡單很多。」

## 核心原則：一個 repo 只有一個活的世界

- 分支、commit 都**不是**世界資料夾，只是歷史裡的指標。
- `switch` = 把活的世界原地改成目標快照。只有需要「同時存在兩個版本」時才開 worktree 世界，而且由系統託管、自動回收。

## 1. switch / checkout 的流程

```
前置：工作區乾淨？ ── 否 ──> 提示 commit / 自動 stash / --force 丟棄
  │是
  ▼
計算 HEAD tree 與 target tree 的差異 → 只得到「需要改寫的 section 清單」
  │ （兩個相近的分支通常只差幾十個 chunk，所以切換很快）
  ▼
鎖定世界的編輯（事件層攔截放置/破壞，或把世界設為唯讀/冒險模式）
  ▼
逐 chunk 套用：
   已載入 → 在主執行緒（Folia：region 執行緒）直接替換 section 內容、
            刪除/生成 block entity 與被追蹤實體，送出 chunk 更新封包給附近玩家
   未載入 → 透過伺服器的 chunk IO 讀出 → 替換 → 寫回（不直接碰 .mca）
  ▼
光照重算（標記受影響 section 的光照為過期）
  ▼
移動 HEAD、解鎖、廣播「已切換到 castle-v2 @ a1b2c3」
```

### 需要特別處理的情況

| 情況 | 處理 |
|---|---|
| 玩家站在會變成實心方塊、或腳下變成空氣的位置 | **不移動玩家**（已決定，2026-09-30），只在切換後給世界內的玩家短暫的保護（預設 10 秒內免受窒息與摔落傷害），讓他們自己走出來或飛下來。創造/旁觀模式本來就不受影響。需要時可在 `worldgit.yml` 改成「移到上方安全位置」或「切換前傳送出去」。實作方式：取消 `EntityDamageEvent` 的 FALL/SUFFOCATION/DROWNING（Phase 0 驗證，比 Resistance 藥水好：只擋指定原因、沒有圖示、斷線不殘留；Folia 上可用） |
| 目標快照中不存在的 chunk（分支上沒生成過） | 兩種策略：刪除該 chunk（讓遊戲重新生成） / 保留現狀（標記為 untracked）——預設保留 |
| 大量變動（數千 chunk） | 分批、每 tick 限額，顯示進度條（bossbar）；可選「切換期間踢出玩家」模式。預設限額見下方 Phase 0 結果 |
| 切換到 MC 舊版本的 commit | 先經過 DataFixer 升級再套用 |
| 活塞正在推、水正在流、紅石時鐘 | 小變動同 owner tick 寫入及擷取驗證資料；其餘只鎖受影響 chunk 與邊界，完成驗證後恢復排程，其他 chunk／世界照常運作（使用者決定，2026-10-08；見 [18](18-performance.md)） |

## 2. restore：局部還原（最常用的「復原」）

```
/wg restore <commit|branch> --selection      # 用 WorldEdit 選取範圍
/wg restore HEAD~3 --chunks here 3            # 以自己為中心 3 chunk 半徑
```

- 流程同 switch，但只套用範圍內的 section；範圍邊界落在 section 中間時做方塊級裁切。
- **不移動 HEAD**，所以結果是「未存檔的變動」，玩家看過滿意再 commit。
- 這是給單人最直覺的「悔棋」：不用理解分支就能用。

**離線寫回的 Phase 0 驗證（2026-09-30，`experiments/02-core-proto/` T4）**：在伺服器關閉時把快照寫回 region 檔，1.21.11 與 26.2 都通過。寫回時要：
- 移除 `BlockLight`/`SkyLight`/`starlight.*`/`Heightmaps` 並設 `isLightOn=0`：Paper 載入後會重算光照，結果與原本逐 nibble 相同，log 無錯誤。
- **刪除範圍內 chunk 的 POI**：伺服器會由方塊重建。保留舊 POI 則會留下過期紀錄，新放的工作站不會被登記。
- 範圍內 chunk 的實體整批取代，並以 UUID 掃描整個維度移除範圍外的同一隻；被黏性位置沿用的紀錄可能不在實際所在 chunk，寫回時要依 `Pos` 重新分配 chunk。
- 待辦：原型整檔重寫 region，正式版應就地更新 sector；`.mcc` 大 chunk 寫入、LZ4 尚未驗證。線上（伺服器執行中）替換 section 見下一段。

**線上替換 section 的 Phase 0 驗證（2026-09-30，`experiments/03-paper-poc/` §6）**：Paper 與 Folia 的 1.21.11、26.2 四個平台都成功，bot 看到的方塊與伺服器逐格一致，還原後整個 section（含 block entity NBT）的雜湊與原本相同，log 無錯誤。每個 section 約 2–16 ms，遠低於 1 tick。流程：
1. 在擁有該 chunk 的執行緒（Folia 的 region 執行緒）上，先移除舊 section 內的 block entity，再換上新的 `LevelChunkSection`，然後重建 block entity。
2. 重算 heightmap；對發光或遮光有變的格子呼叫光照引擎的 `checkBlock`；呼叫 POI 一致性檢查。
3. **呼叫 `markUnsaved()`**，否則伺服器不會存。
4. **一定要通知玩家**，否則客戶端繼續顯示舊畫面：零星變動逐格通知（每 section 合併成一個封包），整個 section 替換就重送整個 chunk。
- 未載入的 chunk 不要直接改磁碟：chunk 系統裡若已有該 chunk 的 holder，寫進磁碟的資料會被記憶體版本蓋掉（實測重現）。改用「加 ticket 載入 → 用同一套流程在記憶體替換 → 釋放 ticket」。
- 尚未驗證：section 內的實體處理、殘留的排程 tick、POI 實際效果、客戶端光照（測試 bot 讀不到正確光照）。

## 3. reset --hard 與 revert

- `reset --hard`：= `restore HEAD` 全範圍（丟掉未存檔變動）。若加上 commit 參數則會移動分支指標（改寫歷史），僅限管理員且不可對已 push 的歷史做。
- `revert <commit>`：計算該 commit 的反向 diff，對目前世界做三方合併；乾淨就直接生成新 commit，有衝突走 [06](06-diff-merge.md) 的流程。多人伺服器上推薦用 revert 而不是 reset。

## 4. worktree：真的需要兩個世界同時存在時

使用情境：
- 想**並排比較**兩個版本（在 A 世界看一眼，/wg tp 到 B 世界的同座標看一眼）
- 多人伺服器上，**不同人同時在不同分支上工作**（因為一個活世界同時只能站在一個分支上）

設計：
- 名稱固定為 `<world>@<branch>`，由插件用自己的方式建立/載入（不需要 Multiverse）
- 不出現在一般世界列表；`/wg worktree list` 可見
- 分支被合併或刪除時提示回收；閒置 N 天自動卸載（資料仍在 repo，隨時可重建）
- worktree 世界也是 working tree：在裡面 commit 就是 commit 到那個分支
- 建立方式：不用複製整個世界資料夾，而是從 repo 物件「長出」一個世界（只寫入被追蹤的 chunk；未追蹤的地形用相同種子生成）

## 5. 唯讀預覽（不改變任何世界）

`/wg preview <commit>`：在目前世界中，對自己**一個人**顯示該 commit 與現況的差異方塊（客戶端假方塊封包 / display entity 鬼影），不改變伺服器上的任何東西。

- 插件端：用假方塊封包（只有該玩家看得到），或半透明 display entity 描出「會出現/會消失」的方塊。
- 模組端：客戶端直接渲染半透明鬼影方塊，效果最好。

這可以取代很多「想看一眼舊版本長怎樣」而去開 worktree 的需求。

## 6. 大量 switch 的 Phase 0 壓力測試（2026-10-01，`experiments/08-folia-switch/`）

1,008 個 chunk、4,032 個差異 section、108 個實體，3 個 bot 分散在世界各處，做 A → B → A：

| 平台 | 每 region 每 tick 4 個 section | 16 個 / 5 ms | 16 個 / 10 ms |
|---|---|---|---|
| Folia 1.21.11 | 18 秒（約 225 section/秒） | 7.0 秒 | 5.6 秒 |
| Folia 26.2 | 17.7 秒 | 6.3 秒 | 5.6 秒 |
| Paper 26.2 | 51 秒（約 79 section/秒） | 15.5 秒 | 13.6 秒 |

- **正確性**：24 次 switch 每次存檔後離線比對，section、實體、POI 差異全為 0；A → B → A 後與原本完全一致；實體無重複或遺失；三平台都沒有執行緒檢查錯誤。
- **速度**：Folia 因多個 region 並行，約為 Paper 的 2.9 倍。限額 4 時瓶頸是 section 數，預算 5 或 10 ms 沒有差別。
- **伺服器負載**：限額 4 時 TPS 幾乎不受影響（Folia 各 region 平均 ≥ 19.97、Paper 19.7）。限額 16 / 10 ms 時 Folia 最低降到約 18.4。偶發 400–750 ms 的最差 tick 與存檔、GC 時間吻合，尚未切開驗證。
- **未載入的 chunk**：用 plugin ticket 載入，同時持有 8–24 個，每次結束都歸零。
- **取消**：約 0.8 秒內停止並釋放所有 ticket，HEAD 標為 `PARTIAL`；重新套用 A 可完整恢復；關服後磁碟上的世界與目標一致。
- **建議預設**：每 region 每 tick 8 個 section、時間預算 5 ms、全域同時載入 16–24 個 chunk。範圍內有玩家時降到 4 個，無人時可提高到 16 個 / 10 ms。
- **Folia 特有現象**：只靠 plugin ticket 載入的 chunk 裡，剛加入的實體 `isValid()` 會是 false（每次約 54 隻），但存檔後全部都在且正確。所以不能用 `isValid()` 判斷生成失敗，要以存檔後的比對為準。根因尚未確認。
- **正規化要再加一條**：伺服器會惰性補上沒有 modifier 的 `movement_speed` attribute，比較時要略過（見 [02](02-data-model.md) §3）。
- **尚未驗證**：光照逐格比對、真村民與自然生物、switch 進行中關服後的恢復、真玩家活動下的負載，以及重複量測。

## 7. Phase 2 正式 core／離線 CLI（2026-10-01）

### 操作與一致性

`WorldOperations` 先持有世界組／所有維度 repo／OS session 鎖，再全量 capture 目前世界，與目標 tree 分層比較。每個維度的 DataVersion、規則、metadata 路徑等預檢都通過才開始寫回。`apply-state.yml` 記錄 operation UUID、模式、from／to、原分支、範圍、已套用維度與錯誤；from／to 另用 `refs/worldgit/operations/<UUID>` pin。全組寫回後重新掃描，確認 ApplyPlan 為空，再移動 HEAD。

只有 refs 可以回復；多個 region 檔的部分寫入不回滾。失敗或中途終止保留 APPLYING／PARTIAL（兩者都阻擋新 commit、普通 switch、branch、stash），以 `switch <branch> --force` 或 `reset --hard` 全範圍重套恢復。離線 journal 是維度級，不是逐 section 的續傳位元圖；重開自動續傳尚未提供。範圍限定的 restore 不可消除全世界的 PARTIAL。

`switch <branch>` 附著同名的全維度分支；hash、HEAD、HEAD~n 等非分支目標使所有維度 detached。此時 commit 仍可建立新歷史，可用 `branch saved` 保存，再 switch 附著。分支 create/delete 預檢全維度，部分 ref 更新失敗會回復已更新部分，回復失敗標 PARTIAL。刪除目前分支或未合併分支被拒。

`WorldRepositories` 的 init／commit 在所有成功維度建立 `refs/worldgit/groups/<snapshot>`，包含當次沒產生 commit 的 HEAD，hash 入口優先用它配對。不具此 ref 的 Phase 1 歷史，按入口 commit 的 first-parent snapshot 祖先配對其他維度；找不到則拒絕，建議改用同步分支。不同平台直接呼叫 DimensionRepository 的提交者也應建立完整 group refs，否則僅享有舊歷史的回溯規則。

### 寫回與範圍

RegionWriter 就地改動變更 chunk 的 sector／location／timestamp，未變 chunk 的 sector bytes 不動；相同 sector 數可沿用原位置，不同大小以 free run／檔尾配置。寫入 zlib，讀取 gzip／zlib／raw／LZ4；超過 255 sector 的 payload 原子寫入 `.mcc`，回到小 chunk／刪除時清掉外部檔。payload／header 強制落盤，但這仍不是斷電時可回滾的檔案系統 transaction。

變更 terrain chunk 刪除所有 section 的 BlockLight／SkyLight／starlight、chunk Heightmaps／starlight，設 isLightOn=0，刪除該 chunk 的 POI 記錄。實體分成完整 UUID 移除（全維度，含 passengers）與依 Pos 生成兩階段；保留不在操作中的 UUID。這允許範圍外的同 UUID 被移除，否則還原會重複生成。黏性儲存路徑不決定實體的寫回 chunk。

chunk 半徑為正方形且含端點；block box 與 BE 逐格裁切。biome 是 4×4×4 sample，以 sample 起點選取，sample 不可再分割。ticks／structures 是 chunk 級原子資料，只在 chunk 完整涵蓋且沒有 area 規則時套用；box 的完整高度採 -64..319，其他高度的維度用 chunk 範圍較直接。只改光照／POI 等衍生內容不列為追蹤資料差異。

目標沒有的 chunk 預設保留，寫入 repo `untracked.yml`；status／下一次 switch 不把它算成未提交變動，explicit commit 重新納入追蹤，自動 commit 保留標記。`--delete-untracked` 只允許完整涵蓋的 chunk，存在 area 排除時拒絕。verify 回報的是可套用的已追蹤資料差異，保留的 untracked 另列數量，並非 raw region 位元組相同。

目前 `.wgignore` 與目標不同時拒絕，而非自動遷移規則；低階 planner 可依兩邊排除區域產生 mask。相同規則下，範圍外方塊／BE 原始欄位保留，覆蓋的同類 BE／實體保留使用者明確忽略的頂層欄位；內建暫態資料不列入快照。跨 DataVersion 一律清楚拒絕，沒有 DataFixer 實作；舊→新可先用同版還原至複本，再啟動新版伺服器升級與重新 commit，不直接更改 DataVersion 數字。

### world-meta 還原規則

| 資料 | 全範圍 restore／switch／reset／stash | 局部 restore |
|---|---|---|
| 出生點、LevelName、難度、hardcore、gamerules、world border、worldgen 設定 | 套用已追蹤的目標欄位；未追蹤／明確忽略欄位保留 | 保留 |
| 地圖、scoreboard、custom boss events、26.2 各維度設定檔 | 還原已追蹤 NBT；目標移除且規則仍追蹤的檔案刪除 | 保留 |
| DataVersion | 預檢要求同版，保留原值 | 保留 |
| DataPacks | 清單必須相同，否則預檢拒絕；沒有快照的 datapack 檔案可供還原 | 保留 |
| 玩家資料／背包／進度、Time／DayTime、天氣、未追蹤 level.dat 欄位 | 保留 | 保留 |
| repo 的 track 設定 | 移動 HEAD 的 switch／有 revision 的 reset 同步 sidecar；其他 restore 保留 sidecar | 保留 |

### 暫態 saved-data

26.x 把原本放在 `level.dat` 的執行期狀態拆成獨立檔案；這些狀態與 1.21.11 的 `level.dat` 白名單同語意，**不版本化、不還原**（決定 #159–#161）：

| 檔案／欄位 | 處理 | 理由 |
|---|---|---|
| `data/minecraft/world_clocks.dat`、`weather.dat`、`wandering_trader.dat` | 整檔不追蹤 | 只含遊戲時間、天氣與生成倒數，每 tick 或每次天氣變化都會變 |
| `random_sequences.dat` 的 `sequences` | 欄位不追蹤；`salt`、`include_*` 追蹤，預設值視為不存在 | RNG 游標隨戰利品／隨機使用變動；`/random reset` 設定屬於使用者意圖 |
| `stopwatches.dat` 的時間戳 | 歸零比較，只追蹤碼表名稱 | 時間戳隨遊戲時間變動 |
| `raids.dat` 的 `tick`、Paper `level_override(s).dat` 的 `game_time` | 欄位不追蹤 | 遊戲時鐘（沿用既有規則） |

raid 清單與 `next_id`、龍戰、地圖、記分板、gamerule、世界邊界等仍照常追蹤。套用（switch／restore／reset／stash）保留目標世界目前的暫態值，不回寫舊時鐘或天氣；新組裝的世界由遊戲自行建立預設值。

舊版建立的 repo 歷史若已含這些條目，不改寫 Git 歷史：diff／status／switch 規劃／merge／Hub 檢視讀 tree 時兩邊都會過濾，不會出現假刪除或 world-meta 差異；正規化 fingerprint 升版，舊索引自動重建。若仍有其他 world-meta 差異使線上操作被拒，訊息會列出解碼後的檔名，請停止伺服器後用 CLI 離線還原。

### 預檢與上鎖之間的競態

線上套用（Paper、Folia、Fabric 單人與 dedicated 共用同一套判定）的順序是：鎖外預檢（世界照常 tick）擷取世界並算出計畫 → 只鎖住受影響的 chunk → 套用 → 驗證。預檢到上鎖之間世界仍在變動，所以以前「上鎖後與預檢基底比較、不同就中止」會讓有生物走動、流水、作物、紅石時脈、漏斗或熔爐運作的 chunk 反覆出現「預檢期間世界已變動，尚未寫入」，重試也無效。

現行規則（決策 #163–#167）：

- **鎖後的擷取才是權威**：被鎖的 chunk 不再 tick。鎖後擷取受影響範圍，與計畫基底相同就直接套用；不同就以鎖後狀態重算計畫（同一目標、範圍與選項）。重算後的足跡超出已鎖範圍，或多出未追蹤的實體 UUID，就擴大鎖範圍重試（迴圈有上限）。
- **覆蓋型操作**（restore、reset、`switch --force`、stash pop、pull）本來就會覆蓋工作區的未提交變動，重算後一次成功。
- **switch（不加 `--force`／`--stash`）不可覆蓋未提交變動**：預檢時工作區是乾淨的，鎖後範圍卻與 HEAD 不同，就是窗口內才出現的新變動。先自動重新預檢（最多 3 次，含第一次）；重新預檢發現 dirty，或第 3 次鎖後仍不同，就拒絕，尚未寫入。訊息列出變動的 chunk 座標與種類（方塊、biome 取樣、實體類型與數量，最多 8 個 chunk，其餘只給總數），並提示 `/wg status` 查看，commit、`stash push`、`switch --stash` 或 `--force` 後再試。窗口內若變動又恢復成與 HEAD 相同，則照常套用。
- **`switch --stash` 與 `stash push`**：自動 stash 改在鎖後的權威擷取上儲存，窗口內的變動一併進 stash；預檢乾淨且窗口內沒有新變動時不建立多餘的 stash。
- **單 tick 原子套用**沒有鎖，owner tick 內核對失敗時（`AtomicChangedException`）尚未寫入：還原 journal 與 HEAD，禁用原子套用後走上鎖路徑重算，不標 PARTIAL。
- **merge 類操作**（merge-start／select／abort）沒有安全的重算器，鎖後有差異就拒絕並列出變動（它們本來就要求乾淨工作區）。
- 只看計畫足跡：足跡外的演變仍是工作區變動（沿用 `LiveApplyVerification`）。實體跨界仍由 #154／#157 的原生移動回呼保護，鎖後重算的足跡包含實體新位置。

真伺服器驗證（生存範本 `entities: all`；chunk (0,0) 內有圍欄的牛、羊、村民、紅石時脈、乒乓漏斗、燃燒熔爐、成長中的作物，`switch before/main --force` 往返）：修正前 Paper 26.2 六次中四次失敗、Folia 26.2 六次中三次失敗、Paper 1.21.11 六次中四次失敗、Fabric dedicated 26.2 六次全部失敗，皆為「預檢期間世界已變動」；修正後四者連續八次全部成功，凍結 tick 後 `verify` 零差異（Folia 的 `tick freeze` 無法凍結實體，改以套用內建的鎖後驗證為準）。以 `-Dworldgit.test.preflight-delay-ms` 在窗口內由玩家放置方塊（Paper 26.2、Folia 26.2、Paper 1.21.11、Fabric dedicated 26.2）：不加旗標的 switch 被拒絕並列出 `[0,0] 方塊 1`，方塊保留；`switch --stash` 成功且窗口內的方塊進了 stash。證據在 worktree 的 `.work/preflight-lock/`（`*-result.json`、`repro.py`、`fabric_repro.py`）。

驗證用測試鉤子：`-Dworldgit.test.preflight-delay-ms=N` 在預檢與上鎖之間暫停 N 毫秒（未設定時無作用），用來在真伺服器穩定製造窗口，例如窗口內由玩家放置方塊。

### stash 與 reset

stash 保存全組 working tree，每維度一個以原 HEAD 為 parent 的獨立 commit，pin 在 `refs/worldgit/stash/<UUID>`，世界組 `stash.yml` 按新到舊保存 UUID、time、message、commits／bases。全組 capture／refs 成功後才發布清單；push 發布後再還原 HEAD，清除已保存的新增 chunk，寫回失敗保留 stash 與 PARTIAL。`switch --stash` 只保存工作區後直接切換，省去一次中間還原。pop 限原基底、乾淨工作區（連保留的 untracked 也要先 commit／stash push，避免覆蓋或刪掉它），全部套用／驗證成功才 drop；不做三方合併，跨分支 stash 合併留待 Phase 3。drop 移除目錄項與 pin，既有保守 GC 不立即 prune 物件。

`reset --hard` 保持分支／HEAD 指向並還原 HEAD；帶 revision 時要求 --force，附著 HEAD 則更新目前分支，detached HEAD 則改其 commit。勿用於已 push 的歷史。revert 尚屬 Phase 3。

批次 coordinator、玩家保護／取消契約與實測證據見 [12](12-phase2-progress.md)。


## 8. Phase 3 MERGING 與反向 patch（2026-10-02）

`WorldOperations.merge/revert/cherryPick` 共用 Phase 2 全組鎖、LiveAccess、apply journal、全量驗證與 refs barrier。合併前要求乾淨（含 untracked），可自行先 stash push。`.wgignore` 的三方合併為合併操作特例；switch／restore／reset 仍要求目標與工作區規則一致。

MERGING 保存於 merge-state.bin，普通 commit／branch／stash／restore／switch／reset 被阻擋；CLI `commit -m` 改走 merge commit，要求全部 resolved。選擇 ours／theirs／base 只覆蓋區域精確 atoms，manual 以目前世界內容為準。merge --continue 產生新整合快照；merge --abort 丟棄合併期間手動變動，恢復原世界／規則／HEAD。規則不同時保存原來被排除的內容以便 abort。

APPLYING／PARTIAL 不等於 MERGING：前者是未完成寫回，後者是等待衝突選擇。合併的 PARTIAL 保留原 HEAD 與 MERGING，允許 merge --abort 全量恢復，禁止強制 switch／reset 略過狀態。這仍非跨 region 檔的斷電原子交易，也不自動續傳。

revert 以來源 commit 為 base、其單 parent 為 theirs；cherry-pick 反過來。snapshot 中未變維度為零 patch；多 parent 來源先拒絕。乾淨自動建立新單 parent commit，衝突時用同一套 resolve／continue／abort。CLI 指令與重跑驗收見 [13](13-phase3-progress.md)。
