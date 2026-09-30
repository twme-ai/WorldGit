# 04 — commit、status 與存檔點策略

## 1. 一次 commit 做了什麼

```
1. 找出「可能變動」的 chunk 集合（dirty set）
2. 對每個 dirty chunk 取得一致的 NBT 快照
3. 正規化 → 切成 section/entities/biomes blob → 計算雜湊
4. 雜湊跟 HEAD 相同的丟掉（例如只是 InhabitedTime 變了）
5. 寫入新 blob、重建受影響的 tree、寫 commit、移動分支指標
```

步驟 2～5 可以在背景執行緒做；只有「取得快照」這一步需要跟主執行緒（或 Folia 的 region 執行緒）互動。

## 2. 怎麼知道哪些 chunk 變了（dirty 追蹤）

### 先釐清：抓到變動「只影響速度與作者歸屬，不影響正確性」

commit 最後一定會用**內容雜湊**比對，所以就算某個改動沒被任何機制事先察覺，只要它所在的 chunk 被檢查到，就不會漏存。dirty 追蹤的目的是：
1. **速度**：不用每次都把整個世界重新雜湊一遍
2. **作者歸屬**：知道「誰」改的（只有部分機制做得到）

### 各種偵測機制

| 機制 | 做法 | 抓得到 FAWE / 其他插件直接改 NMS 嗎？ | 知道是誰改的嗎？ | 備註 |
|---|---|---|---|---|
| Bukkit 事件 | 監聽 BlockPlace/Break/Explode/Piston/Physics… | ✗ | ✓ | 最基本的作者來源 |
| **WorldEdit / FAWE API** | 在 `EditSessionEvent` 包一層 Extent，看到每一格被設定的方塊 | ✓（FAWE 也支援此事件，部分極速模式需驗證） | ✓（知道是哪個玩家下的指令） | CoreProtect 記錄 WorldEdit 改動也是用這個方式 |
| **攔截送給玩家的方塊更新封包** | 用 PacketEvents 監聽 `BlockUpdate`、`SectionBlocksUpdate`、chunk 資料封包，收到就把該 chunk 標為 dirty | ✓（FAWE 改完一定會送封包給附近玩家） | ✗ | 只有**附近有玩家**的 chunk 才會送封包；同一個變動會送給 N 個玩家，要以 chunk 去重 |
| **chunk 的「未存檔」旗標** | 伺服器內部每個 chunk 被改動時都會被標記為需要存檔（`LevelChunk` 的 unsaved 旗標）；插件透過版本轉接層定期讀取已載入 chunk 的這個旗標 | ✓（任何要被存下來的改動都一定會設它，不管來源） | ✗ | 伺服器自動存檔後旗標會被清掉，所以要搭配下一列的時間戳 |
| region 時間戳 | region 檔頭有每個 chunk 的「最後寫入時間」；跟上次 commit 記錄的時間戳比 | ✓ | ✗ | 只對已寫回磁碟的 chunk 有效；也會因不重要的欄位變化而更新（由雜湊過濾） |
| Fabric mixin | 直接攔 `LevelChunkSection.setBlockState` 與 chunk 的 unsaved 標記 | ✓（Fabric 上任何改動都經過這裡） | 部分 | 模組端最完整 |
| 內容雜湊 | 對候選 chunk 做正規化 + 雜湊比對 | — | — | 最終真相 |

### 回答「能不能像 FAWE 一樣直接收到世界被更改的封包」

可以，而且值得做，但要注意 FAWE 本身並不是「收到」變更，它是**直接把方塊寫進 chunk 的記憶體**（所以才會繞過 Bukkit 事件），改完再主動送 chunk 封包給附近玩家。我們能利用的是兩個點：

1. **FAWE 自己的 API**：透過 `EditSessionEvent` 可以看到 FAWE 寫了哪些方塊、是誰下的指令 → 同時解決「偵測」和「作者」。
2. **送出的封包**：伺服器要讓玩家看到變化，一定會送方塊更新或 chunk 封包，所以監聽送出的封包可以抓到**任何來源**（FAWE、其他插件、指令）造成的可見變化。限制是：沒有玩家在附近的 chunk 不會送封包，而且封包不帶「誰改的」。

所以封包監聽適合當作「補漏」而不是唯一來源。

### 插件端的組合（建議）

```
已載入的 chunk：  unsaved 旗標  ∪  封包監聽  ∪  事件/WorldEdit API
已存回磁碟的 chunk：region 時間戳
                     ↓
              候選 chunk → 內容雜湊 → 真正的變動
作者：事件 + WorldEdit/FAWE API（抓不到作者的變動記為「未知/系統」）
```

「unsaved 旗標 + region 時間戳」兩者合起來，理論上已經能抓到所有變動（沒存的看旗標、存了的看時間戳），封包監聽則提供即時性（例如 `/wg status` 描邊要即時更新）。

### Phase 0 驗證結果（2026-09-30，`experiments/03-paper-poc/`）

在 Paper 與 Folia 的 1.21.11、26.2 共四個平台，測了 22 種改動來源。**對方塊與 block entity，「旗標 ∪ 時間戳」沒有縫隙**：改動要嘛還在記憶體（旗標為真），要嘛已存檔（時間戳變了）。但實作時要照下列修正：

| 項目 | 發現 | 做法 |
|---|---|---|
| 讀哪個旗標 | Paper/Folia 把 `LevelChunk.isUnsaved()` 改成「含未完成的排程 tick 與 chunk PDC」，有流水的 chunk 永遠是真；在 Folia 的非 region 執行緒呼叫會 NPE | 用 `VarHandle` 直接讀 `ChunkAccess.unsaved` 原始欄位（volatile，任何執行緒可讀，兩版同名） |
| 光照外溢 | 改一格方塊會連帶讓最多 8 個鄰居 chunk 被設旗標 | 旗標只當候選，靠內容雜湊濾掉；雜湊前可先只比 `block_states` 做便宜篩選 |
| 閒置誤報 | 閒置 chunk 120 秒內約 7–8/10 會被設一次旗標（光照、`InhabitedTime`、自然流體等） | 同上，正規化已丟棄這些欄位 |
| 時間戳秒解析度 | 同一秒內存兩次檔，時間戳不變（6 次測試撞 4 次） | 時間戳等於「目前這一秒」或「上次掃描那一秒」的 chunk 一律視為候選 |
| 實體 | 實體存在獨立的 entity storage，**沒有任何旗標**；entities 檔的時間戳只要 chunk 有實體就每次存檔都更新 | 有實體的 chunk 每次 commit 都序列化實體再雜湊（配合 [02](02-data-model.md) §8 的黏性位置）；Bukkit 實體事件做即時提示 |
| 直接寫 section | 繞過 `markUnsaved()` 的寫入，旗標、時間戳、封包都看不到，伺服器也不會存 | WorldGit 自己的 switch/restore 替換 section 後**必須**呼叫 `markUnsaved()`；定期或 `status --full` 做全量雜湊兜底 |
| block entity 內部進度 | 熔爐燒煉進度等不一定會讓 chunk 變髒 | 以雜湊為準（這些欄位本來就在忽略表） |
| FAWE | `EditSessionEvent` 拿得到 actor；但 FAWE **預設會靜默丟掉第三方 Extent**，需在 FAWE `config.yml` 設 `extent.allowed-plugins`。`//set`、`//replace` 等 bulk 操作看不到逐格，只看到 Region 與 `IBatchProcessor` 的每 chunk 寫入數 | 插件啟動時檢查並提示設定；以 Region / chunk 層級資料推出受影響 chunk，作者歸屬照常 |
| 純 WorldEdit | 逐格都看得到；Folia 上在 region 執行緒回呼 | FAWE 沒有 Folia 版，Folia 只支援純 WorldEdit，偵測碼分兩條路 |
| 封包（PacketEvents） | 附近沒玩家就 0 個封包；N 個玩家收到 N 份；NMS 直寫、biome、容器內容、實體都沒有方塊封包 | 只當即時 UI 的選用訊號，不能保證不漏 |

修正後的插件端組合：

```
已載入 chunk：原始 unsaved 旗標 ∪ 事件/WorldEdit（附作者） ∪ 封包（選用，即時 UI）
已存檔 chunk：region 時間戳變了，或落在「同一秒」的不確定窗內
實體：有實體的 chunk 每次 commit 都重新雜湊
              ↓
       候選 chunk → 正規化 + 內容雜湊 → 真正的變動；定期全量雜湊兜底
```

### index

這跟 git 的 index 一樣：**index 記錄每個 chunk 上次看到的時間戳與雜湊**，時間戳沒變、旗標沒設、也沒收到封包的 chunk 就不重算。完整掃描（`/wg status --full`）只在懷疑不一致時用。

## 3. 一致的快照

問題：commit 期間玩家還在改方塊。

- **已載入的 chunk**：在主執行緒（Folia：該 region 執行緒）上把 section 資料複製一份（palette container 的 copy 很便宜），複製完就放手，之後的正規化在背景做。每個 chunk 本身是一致的；跨 chunk 的「同一 tick」一致性則靠在同一 tick 內批次複製，太多時分批（接受跨幾 tick 的輕微不一致，建築用途可接受）。
- **未載入的 chunk**：透過伺服器自己的 chunk IO（而非直接開 .mca 檔）讀取，避免與伺服器的非同步存檔競爭。
- **CLI（離線世界）**：直接讀 region 檔；要求伺服器沒在跑（檢查 `session.lock`）。

## 4. 作者歸屬

事件層記錄「誰改了哪個 chunk」，commit 時把這段期間的參與者寫入 `authors`。自動 commit 以伺服器為 committer、玩家為 authors。
這也讓 Hub 上可以看到「這個存檔點誰參與了」，以及粗粒度的 `blame`。

## 5. 存檔點策略（可組合）

| 策略 | 說明 |
|---|---|
| 手動 | `/wg commit <訊息>`，或 GUI（書本輸入訊息） |
| 登出時 | 玩家登出時 commit **他本次登入期間改動過的 chunk**（需要 staging 的概念：只收自己的變動）→ 多人同時在線時，每人的存檔點互不混雜 |
| 定時 | 每 N 分鐘有變動才 commit，標記為 `auto` |
| 關機前 | 伺服器關閉時 |
| 危險動作前 | 執行 `//set` 大量 WorldEdit、`merge`、`switch` 之前自動 commit，確保一定能退回 |

多人同一分支、各自在登出時 commit，本質上是「同一分支上的連續 commit」而不是分支協作，不會有衝突問題（因為世界只有一個）。

`auto` commit 太多時：`/wg squash --auto --before 7d` 把舊的 auto commit 壓縮（改寫歷史，只允許在尚未 push 的部分做，或由管理員做）。

## 6. status 的呈現

```
On branch castle-v2 (3 commits ahead of main)
Changes since last commit (12 chunks, 3 players):
  overworld  r.0.0  c.3.7   +214 -12 ~3   alice
  overworld  r.0.0  c.4.7   +88           alice, bob
  ...
Ignored: 2 chunks in area "刷怪塔"
```

遊戲內：`/wg status show` 用粒子或 display entity 把變動 chunk 的邊框描出來，10 秒後消失。
