# Phase 0 實驗 06：生存 status 雜訊、commit 成本、大世界 init 與本機傳輸

> 測試日期：2026-10-01。所有量測（A、B）已完成；接手後的補跑情況見「執行紀錄」。

## 方法與重跑條件

程式直接複製 02 的 Java 正規化／NBT／Anvil／JGit 原型到本實驗，保留 blob 格式與樹階層；不修改 `02-core-proto`。MC 伺服器與 baseline 都複製至 `.work/survival-scale`，沒有改動來源。Java 21 建置，Java 25 執行 CLI；Paper 26.2 用 Java 25、1.21.11 用 Java 21。全部重負載與量測由 Python `fcntl.flock` 持有全域 `.work/bench.lock`（與 shell `flock` 同一把 advisory lock）。等待鎖期間不啟動伺服器。Gradle 只有 1 worker。

A 使用 4 個 mineflayer bot，沿用 03 的 26.2→26.1 資料補丁；`random_tick_speed=3`、睡眠門檻等 gamerule 保留預設。以可重現的平台／工具／材料支援採集、建築、農場、戰鬥等操作，探索 bot 在天然地形走動。console 定期補 12 格礦脈、3 格樹幹與 husk，不把此輸入當成真人採集速率；一次人工換夜並傳送 bot 回床位。失敗與成功動作分開計數。無玩家對照、站立對照與活動樣本分開。

每次採樣：`tick freeze`、`save-all flush`、複製 world、恢復 tick。CLI 讀複本，8 種政策各自維護連續歷史，使用 `--full` 保留刪除偵測並避免秒時間戳漏報。記錄 `level.dat Time`、快照暫停秒數、CLI wall time／RSS、變動 section、方塊、BE、chunk、實體、實際新增 loose object 數與檔案 bytes。新增地形格數和既有 chunk 格數分開；方塊格數是 snapshot 淨差異，不是 bot 的操作總次數。

實體分為 natural、player_related、transient。這是 NBT 標記／類型分類，不是事件層作者歸屬：PersistenceRequired、名字、NoAI、Owner／Tame、靜態建築實體／船／礦車列 player_related；item／orb／常見投射物列 transient，其他列 natural。玩家固定排除。未標記的繁殖後代可落入 natural；正式實作應以 Bukkit 事件來源補足。

`.wgignore` 僅支援 `entity TYPE [!persistent]` 與 `!entity` 加回，拒絕其他語法；存進 root tree 並在政策改變時使 index 失效。`!persistent` 只用上述 NBT／類型標記，不能完整判斷 vanilla 的不自然消失行為；普通牛羊也可能被排除。`--extra-noise` 去除 Health、equipment/ArmorItems/HandItems 裡的 damage，以及掉落物 Item 的 count；**保留物品種類及其他 components**。

B 使用固定 Chunky Bukkit 1.5.3（[官方版本資料](https://api.modrinth.com/v2/version/MdY6JATr)列 Paper／26.2，下載經 SHA-512 檢查），square 以 ±1136 格附近為目標，約 2 萬個 full chunk。初始化時關閉伺服器，對完全相同的資料比較原型路徑與串流路徑（各一次，暖快取順序可能影響時間）。CLI `-Xmx1G`，GNU time 量 RSS；伺服器 `-Xmx3G`。改進：region 先讀 8 KiB header、需要時才讀 payload；首次 init 每個 chunk tree 寫出即釋放子節點，避免全世界子樹常駐。兩個 init 的 root tree 必須相同。

小改動在 freeze 下載入既有 chunk，於 `(480,250,480)` 放 1 格金塊。探索增量以 Chunky 載入遠處 16×16 chunk，量測新 full chunk 的成本，屬受控探索輸入。增量 commit 前 freeze、flush、save-off，沒有玩家。

本機傳輸原先嘗試 Gitea 28.0.0 single binary，因 root 執行被拒（保留 `gitea-attempt.log`），因此改用 `git http-backend`。服務僅 `127.0.0.1:25643`，JGit 7.3 和 C Git 2.43 分別測首次 push、完整／淺 clone、增量 push/fetch。傳輸 bytes 是 HTTP handler 讀寫的 body（含 Git pkt-line／pack 與 sideband，不含 header/TCP/IP/retransmission），不是磁碟 pack 的估算。未連至任何外部 Git hosting。

重跑見 [README.md](README.md)。小型驗證：排除／加回／玩家永久排除、未支援語法拒絕、黏性錨點、靜態實體精確位置、保留 item 身份、Smart HTTP `ls-remote`。大世界以 tree hash 同一性、Git fsck、clone/fetch HEAD 與深度驗證。

## 執行紀錄（持鎖與時間）

- 全部量測（A 的伺服器與 bot、CLI；B 的預生成、init、transport）都在持有全域 `.work/bench.lock` 下進行；等鎖期間不啟動伺服器。快照、CLI 與 transport 的耗時都是 3 核心機器上、另兩個實驗輪流持鎖的環境，只能當量級參考。
- 原 Codex 任務於 01:46 UTC 遇用量上限中斷，之後由接手代理等鎖並跑完 26.2 正式活動、1.21.11 縮短版、大世界、transport。
- 接手後發現並修正一個 transport 腳本缺陷：Smart HTTP handler 沒有轉傳 `Content-Encoding: gzip`，JGit 的 upload-pack 請求被 `git http-backend` 拒絕（clone 失敗，`transport-first-failed.out` 保留）。修正後把 transport 整段（C Git 與 JGit）重跑一遍，下表是重跑的數字。

## A. 生存雜訊與 commit 大小

### A1 對照組（26.2，每次 `save-all flush` 後 commit）

| 條件 | 遊戲時間 | 變動 section | commit 新物件 | 說明 |
|---|---|---|---|---|
| 無玩家閒置（5.7 分，只有伺服器） | 約 6870 tick | 0 | 0（commit 為空，不產生） | 區塊、BE、實體皆 0 差異；1.21.11 同樣 0（1 分鐘縮短版） |
| 玩家在場站立不動（5 分，場地已建，bot 靜止） | 5 分 | 74（1479 格） | 142 物件／78 KB（tol2） | 無新 chunk；實體 +33／-11／~2（自然生物與掉落物） |
| 活動 30 分（4 bot） | 約 30.7 分 | 4501（含新 chunk 3070） | 7403 物件／3.62 MB（tol2，8 次 commit 加總） | 見 A2 |

站立對照代表「有人在線但不操作」時的底噪：約 74 個 section、約 80 KB／5 分鐘，大部分來自隨機刻（作物、草、流體等）與少量生物變動，**與實體忽略規則無關**，所以自動 commit 不能只靠「有任何 section 變動就觸發」。（1.21.11：站立 48 section、103 物件／58 KB。）

### A2 26.2 逐次 commit（5 分鐘間隔，tol2，預設政策）

| 採樣 | 新 chunk | 變動 section | 既有 chunk 變動格 | BE | 實體 +/-/~ | 新物件 | 新 loose bytes | CLI 秒／RSS | 快照暫停 |
|---|---|---|---|---|---|---|---|---|---|
| active-01 | 102 | 1083 | 3035 | 12 | 348/36/13 | 1568 | 764 KB | 4.8 s／323 MB | 1.06 s |
| active-02 | 124 | 1220 | 2396 | 5 | 185/228/27 | 1687 | 808 KB | 4.3 s／324 MB | 0.83 s |
| active-03 | 49 | 680 | 3364 | 5 | 388/195/33 | 1218 | 603 KB | 4.7 s／321 MB | 1.01 s |
| active-04 | 0 | 222 | 1674 | 0 | 229/302/40 | 591 | 286 KB | 4.5 s／318 MB | 0.64 s |
| active-05 | 0 | 254 | 2065 | 0 | 336/285/46 | 700 | 335 KB | 5.8 s／313 MB | 0.73 s |
| active-06 | 94 | 1042 | 2821 | 13 | 383/243/33 | 1639 | 825 KB | 5.3 s／323 MB | 0.85 s |
| 合計 | 369 | 4501（新 chunk 3070 + 既有 1431） | 15355 | 35 | 1869/1289/192 | 7403 | 3.62 MB | | |

（CLI 讀的是 3823 chunk 的 baseline 世界，`-Xmx1G`。）新 chunk 一律約 5.5 KB／chunk（zstd 後 section blob），這是 commit 增量的最大來源（約 2.0 MB，占 55%）；兩個沒有新 chunk 的區間每 5 分鐘約 290–340 KB。以上全部是 4 個 bot 的受控輸入，bot 動作與死亡次數見 `data/summary.json`（例：miner 挖 742、砍 742，builder 蓋 124／種 148／繁殖 106／改建 615，keeper 開箱存物 739、戰鬥 739、睡覺 424，explorer 只成功睡覺 1 次；bot 死亡共 556 次，其中很多是場地／戰鬥負載造成，不是自然遊玩）。**實際玩家的操作強度預期遠低於此**。

### A3 政策比較（26.2 活動 8 次 commit 加總；實體是 added/removed/modified）

| 政策 | 新物件 | 新 loose bytes | 實體 +/-/~ | 對 tol2 的差異 |
|---|---|---|---|---|
| tol0（無黏性） | 7429 | 3,630,116 | 1869/1289/295 | 實體 ~ +54% |
| **tol2（預設）** | 7403 | 3,621,165 | 1869/1289/192 | 基準 |
| tol4 | 7397 | 3,618,941 | 1869/1289/174 | 實體 ~ -9% |
| `entity minecraft:item` | 7378 | 3,604,503 | 1703/1176/189 | -0.5% bytes |
| `entity minecraft:experience_orb` | 7403 | 3,621,371 | 1869/1289/192 | 0（此場景幾乎沒有經驗球） |
| `entity * !persistent`（wild） | 6256 | 3,065,269 | 35/22/89 | **-15.4% bytes、-15.5% 物件**；實體 +/- 降 98% |
| `--extra-noise`（Health/equipment damage/Item count） | 7402 | 3,609,051 | 1869/1289/188 | -0.3% bytes |
| combo（items + extra） | 7377 | 3,593,034 | 1703/1176/185 | -0.8% bytes |

1.21.11 縮短版（5 分活動，1 次 commit）的比例相同：wild 少 16% 物件／16% bytes，items 少 0.5%，tol0→tol4 的物件數幾乎不變（726 vs 726），實體 modified 4→1。

每次 commit 的 CLI 耗時 3.3–5.8 s，RSS 約 310–330 MB（`-Xmx1G`），主要是重讀 baseline 的 3823 chunk 範圍，不是 diff 本身。

### A4 gc 效果（每個政策 repo 在 8 次 commit 之後）

| 項目 | 26.2 tol2 | 1.21.11 tol2 |
|---|---|---|
| gc 前磁碟（loose 物件，含 4 KB 區塊開銷） | 58.2 MB（4 KB 區塊開銷） | 30.5 MB |
| gc 後磁碟 | 7.0 MB | 3.9 MB |
| pack | 6.63 MB | 3.57 MB |
| gc 耗時／RSS | 3.4 s／293 MB | 3.4 s／290 MB |

gc 對「檔案系統占用」效果顯著（約 8×），但 blob 已用 zstd 壓縮，pack 位元組大小與 loose 實際內容大致相同，增量 delta 幾乎無額外壓縮。wild 的 pack 6.12 MB，比 tol2 少 0.51 MB（-7.7%，因 init 部分相同）。

### A5 哪些變動玩家在意、哪些是雜訊

在意：既有 chunk 的方塊（建築、挖掘、農田）、block entity（箱子內容、告示牌等）、有名字／馴服／NoAI／PersistenceRequired 的生物、靜態建築實體（畫框、盔甲架）、船與礦車，新生成的地形（至少要「保存地形」，但屬於可再生內容）。

雜訊：掉落物、經驗球、投射物（transient）；自然刷出／消失的敵對與野生生物（本次 1869 新增／1289 移除，近乎全是此類）；Pos 微小漂移（tol0→tol2 把 modified 從 295 降到 192）；裝備耐久與 Health；隨機刻造成的小量 section 變動（站立對照的 74 section）。

實體本身只占 commit 位元組的 15%，但占「變動筆數」的大多數（3350 筆 vs 約 4500 section），對 `status` 可讀性才是主要問題；對儲存成長量而言，新 chunk 才是最大項。

尚未驗證的細節：wild 政策下 `transient` 類仍有 57 筆 modified 沒被濾掉，我沒有追原因（可能是帶 PersistenceRequired 的物品或 Owner 標記，或分類與 `!persistent` 判斷不一致），正式實作前要查。「未標記繁殖後代」落入 natural，要以 Bukkit 事件補足。

### A6 建議

預設 `.wgignore`（`default.wgignore`）：

```
entity minecraft:item
entity minecraft:experience_orb
entity minecraft:arrow
entity minecraft:spectral_arrow
entity minecraft:snowball
entity minecraft:egg
entity minecraft:ender_pearl
entity * !persistent
```

- 預設就用 `entity * !persistent`：自然生物不記錄，對 per-commit 位元組 -15%、實體變動筆數 -98%。代價：普通牛羊（未命名、未被標記 PersistenceRequired）不會被保存；需要養殖場保存者，要用名牌或 `!entity minecraft:cow` 加回。**但這項取捨要靠 `!persistent` 的實際判斷來源（建議改用 Bukkit 事件／spawn reason 而非 NBT 推測）**。
- 黏性容許距離預設 2 格：tol0 的實體 modified 比 tol2 多 54%，tol4 只再少 9%，且更大的容許會讓記錄所在 chunk 與實際位置偏離更久。tol2 是甜蜜點。
- 掉落物／經驗球／投射物預設忽略；`--extra-noise`（Health、裝備 damage 合併）只有 0.3% 的位元組效益，但能降低 `status` 的假 modified，建議在列出狀態時預設啟用，儲存時保留完整資料。
- 自動 commit 觸發規則：
  - 只有實體變動（或只有被忽略後仍剩的 transient）不觸發。
  - 有任何「既有 chunk 方塊／BE 變動」且距上次 commit 滿 5 分鐘觸發；少於約 100 個 section 且無 BE 變動的底噪（站立對照 74 section）可延到 15–30 分鐘或玩家離線時合併。
  - 新 chunk 累積到約 100 個（約 0.5 MB）或 10 分鐘觸發，不要每個 chunk 觸發。
  - 玩家全部離線後做一次收尾 commit；無玩家時（0 差異）不 commit。
  - 排程 gc：每日或每累積約 2000 個 loose 物件；gc 單次僅 3–4 s。
- 5 分鐘一次 commit 在真正的 server 上需要重新設計快照流程（本實驗是 freeze+flush+複製，暫停 0.4–1.1 s；大世界會更久），不能直接照搬。

### A7 成長量推算（假設明確，非實測）

用實測單價：新 chunk 約 5.5 KB／chunk（含 section、biomes、少量 structures；大世界探索量測值 5.47 KB）；既有 chunk 活動區間（無新 chunk）tol2 約 64 KB／分鐘、wild 約 44 KB／分鐘（4 個高強度 bot 合計）= 約 11–16 KB／bot-分鐘。把 bot 當成真人上限，真人強度假設 0.25×（假設值）。

| 情境（10 人） | 假設 | 每日 | 每月 |
|---|---|---|---|
| 年輕伺服器 | 10 人、每人在線 3 h（共 30 活躍小時）、強度 0.25×、每人每日新生成 500 chunk | 既有活動 30 h × 0.8 MB × 0.25 ≈ 6 MB；新 chunk 5000 × 5.5 KB ≈ 27.5 MB；**合計約 33 MB** | 約 1.0 GB |
| 成熟伺服器 | 同上，但每人每日新生成 100 chunk | 6 MB + 2.75 MB = **約 9 MB** | 約 0.27 GB |
| 上限（把 bot 速率當真人） | 10 人、每人 3 h、強度 1×、探索速率取實測（每 bot-小時約 185 chunk，約 1 MB） | 30 h × (0.95 + 1.0) MB ≈ **約 58 MB** | 約 1.75 GB |

說明：既有活動以每活躍 bot-小時 0.65 MB（wild）–0.95 MB（tol2）計（A2 的兩個無新 chunk 區間平均 218／318 KB 每 5 分鐘，4 個 bot 平分）；新 chunk 以 5.5 KB 乘 chunk 數；未計每次 commit 約 100–140 物件的固定樹開銷（站立對照 5 分鐘 142 物件／78 KB，一天 288 個 commit 全開也只是 22 MB，可靠觸發規則壓低）。gc 後 pack bytes 與 loose 內容相近（無額外壓縮），所以磁碟以 pack bytes 估，不要用 loose 目錄大小估（loose 因 4 KB 區塊是 8× 膨脹）。月增量中「新 chunk」占大宗且可再生；若政策選擇不保存未被修改的天然 chunk（只存有玩家變動的 chunk），可再降一個數量級——本實驗沒有量測這種策略。

## B. 大世界 init 與 push/clone

### B1 預生成與 init（26.2，Chunky 1.5.3，持鎖）

| 項目 | 結果 |
|---|---|
| 預生成 | ±1136 方形，20,521 個 full chunk，1093.5 s（約 19–22 chunk/s）；world 目錄 220 MB（伺服器 `-Xmx3G`）。受 6 GB 磁碟上限取捨，未做 4 萬 chunk |
| init（原型路徑，`-Xmx1G`） | 112.5 s，RSS 481 MB；210,538 物件（174,991 section 變動，其中 section 177,552 筆，去重僅 1%）；loose 內容 112 MB，但磁碟占用 880 MB（4 KB 區塊） |
| init（`--stream`） | 114.9 s，RSS 420 MB（-13%）；root tree 與原版**完全相同**（872a7d8…） |
| gc 後 | pack 110.3 MB（單檔），磁碟 116 MB，gc 35.4 s／RSS 470 MB |
| 每 chunk 平均 | 約 10.3 物件、5.37 KB（pack）、5.5 ms（02：698 chunk 4.5 s＝6.4 ms、3.2 MB＝4.6 KB／chunk，量級吻合） |
| section blob 統計 | p50 521 B、p90 869 B、max 2291 B；biomes 去重 65.6%，structures 去重 93.7%；ticks 紀錄 8389 筆合計 4.3 MB（約 4% pack） |

改進結果要照實說：`--stream`（只讀 region header、串流釋放）只把峰值 RSS 降低 13%，**耗時沒有改善**（114.9 s vs 112.5 s，先後順序與快取有混淆，各只跑一次）；沒有做平行化讀 region，瓶頸多半是單執行緒的 NBT 解析、正規化與 zstd，沒有 profile 驗證。另外 `--stream` 會先寫出 chunk tree 才附上 entities，導致 952 個多餘的 tree 物件（剛好等於有實體的 chunk 數 952，約 316 KB），成為 dangling，需 gc prune；fsck 回傳 0 但列出這些 dangling tree。

外推（線性假設，僅供量級）：

| 規模 | 耗時 | pack 大小 |
|---|---|---|
| 2 萬 chunk（實測） | 112 s | 110 MB |
| 10 萬 chunk | 約 9–10 分鐘 | 約 540 MB |
| 100 萬 chunk | 約 90–100 分鐘 | 約 5.4 GB |

記憶體：小改動時 RSS 182 MB，init 峰值 420–480 MB，單 chunk 增量約 12–14 KB，線性外推 10 萬 chunk 約 1.4–1.6 GB、100 萬 chunk 超過 10 GB——這不是實測，只能說明在 100 萬 chunk 之前，樹節點的記憶體駐留一定要處理（分 region 提交、釋放子樹）。本實驗只驗證到 2 萬 chunk。

### B2 增量

| 增量 | 耗時 | RSS | 新物件 | 新 loose bytes |
|---|---|---|---|---|
| 小改動（1 格金塊，8 section 變動） | 1.16 s | 182 MB | 27 | 35 KB |
| 探索新區域（289 新 chunk，2560 section，8.36M 方塊） | 2.8 s | 324 MB | 3150 | 1.60 MB（5.5 KB／chunk） |

小改動所需耗時主要是讀 region header 與重算 25 個 chunk，與 chunk 總數的關係不大；探索增量與一般生存的新 chunk 成本一致。原型的 JGit gc 有缺陷：最後一次 gc 後 `stream.git` 留下兩個各約 110 MB 的 pack（舊 pack 未被刪除，424,253 個 in-pack 物件，磁碟 244 MB），C Git 的 `git gc` 或自行清理才會回到單檔。傳輸時 C Git 只打包可達物件，因此不受影響（下面 clone 為 111.8 MB）。

### B3 push/clone（本機 `git http-backend`，127.0.0.1:25643，持鎖）

資料為 3 個 commit（init、小改動、探索），pack 約 111.8 MB。傳輸量是 HTTP body 位元組，不含 header、TCP/IP。

| 操作 | C Git 2.43 | JGit 7.3 |
|---|---|---|
| 首次 push（110.3 MB pack） | 2.9 s、上行 110.28 MB、RSS 120 MB | 4.8 s、上行 110.28 MB、RSS 332 MB |
| 初始 clone（1 個 commit 時） | 6.0 s、下行 110.34 MB、RSS 34 MB | 7.4 s、下行 110.34 MB、RSS 257 MB |
| 增量 push（2 個新 commit） | 0.5 s、上行 1.58 MB | 0.8 s、上行 1.58 MB |
| 增量 fetch | 0.5 s、下行 1.56 MB | 0.9 s、下行 1.56 MB |
| 完整 clone（3 commit） | 6.1 s、下行 111.89 MB、磁碟 118.8 MB | 7.7 s、下行 111.90 MB、磁碟 117.9 MB |
| `--depth 1` clone | 6.3 s、下行 111.88 MB | 7.6 s、下行 111.88 MB |

重點：`--depth 1` 沒有節省，因為此 repo 的 HEAD tree 就是全部資料（歷史只有 3 個 commit），淺 clone 省的是歷史；所以「首次下載 = 整個世界」，約 5.4 KB／chunk。增量傳輸很小（約 1.6 MB／探索 289 chunk 的 commit）。兩種客戶端的線上位元組幾乎相同，JGit 稍慢且 RSS 較高。

託管限制：
- 單一 pack 110 MB 已超過 GitHub 單檔 100 MB 硬限制（push 會被拒絕），2 萬 chunk 的世界就會碰到，更不用說建議 repo 大小（約 1–5 GB）。10 萬 chunk 約 540 MB、100 萬約 5.4 GB。
- 建議：(1) push 前設定 `pack.packSizeLimit`（例如 50–90 MB）使 pack 分檔，並以 JGit `PackConfig.setPackSizeLimit` 實作；(2) 世界資料天然分區，可以依維度或 region 範圍分成多個 repo／子模組；(3) 不要把可再生的天然 chunk 全部放進託管 repo（只存玩家改過的 chunk），或將 baseline pack 以「靜態 pack + promisor」方式放自家儲存，增量走 Git；(4) LFS 對 zstd 小 blob 沒用，不建議；(5) 淺 clone 要配合 partial clone（`--filter=blob:none` 或依路徑 sparse）才有意義，這是後續要驗證的事項，本實驗只測了 `--depth 1`。
- 沒測 Gitea（不能以 root 啟動，保留 `gitea-attempt.log`）、真實 WAN、auth、HTTP 上傳大小限制（反向代理常見 100 MB 限制，同樣會擋住單一 110 MB 上傳；用分 pack 可避開）。

## 數據檔案

`data/summary.json`（A 摘要，含 bot 動作計數）、`data/survival-{26.2,1.21.11}-results.json`（逐 commit、逐政策）、`data/*-gc.json`、`data/no-player-control-26.2.json`、`data/large-results.json`、`data/transport-results.json`。log 在 `.work/survival-scale/logs/`。

## 失敗紀錄與目前限制

- 最初腳本改睡眠人數門檻，被發現後中止並修正，正式活動資料不使用該次。
- 第二次準備場地時，未載入 chunk 的 fill/setblock 被拒，bot／牛反覆摔死。中止，保留 log；加入 setup forceload 後重跑。該次場地建立前的 5 分鐘無玩家資料有效，另存 `data/no-player-control-26.2.json`。
- 第三次在站立對照前人工生成 husk，造成 keeper 被殺；中止並將人工敵人移至活動階段，活動前補齊工具。正式結果不含該次。
- mineflayer 26.2 沿用 03 的協定資料補丁，會有 partial packet 警告，不能視為正式 26.2 相容性驗證。
- Gitea 不可在 root 啟動，改測本機 Smart HTTP backend；沒有完成 Gitea 的 auth、quota、UI 或真實 WAN 測試。
- 只測一個 seed、4 個 bot、一次各條件，並非統計代表性真人生存伺服器。後續成長估算應列假設與範圍。
- 26.2 的無玩家對照來自較早（被中止之前）的同版本準備流程，與正式活動不是同一個伺服器執行；`--skip-no-player` 是為了不重做這段。
- 預生成只有約 2 萬 chunk（約 20.5k），沒做 4 萬；大世界只量到 20.5k chunk 與 `--stream` 的一次比較，沒做平行化 region 讀取；100 萬 chunk 的耗時與記憶體純外推。
- 大世界只跑 26.2，沒做 1.21.11 的大世界。
- JGit gc 留下重複 pack、`--stream` 產生 dangling tree 兩個原型問題未修。
- `wild` 政策下仍剩 57 筆 transient 變動的原因未查。
- 成長量推算依賴假設（真人強度 0.25×、上線時數、新 chunk 數），沒有真人伺服器數據驗證；沒有量測「只存玩家改過的 chunk」策略。
- 沒測 partial clone／sparse、Gitea、真實網路。
- 收尾：伺服器、bot、HTTP backend 皆已關閉。最終占用：`.work/survival-scale` 約 450 MB，`experiments/06-survival-scale` 約 13 MB（含 build）。
