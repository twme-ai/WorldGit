# 06 — diff 與 merge

## 1. diff

兩個 tree 的比較是分層短路的：root 雜湊相同 → 沒差；否則往下比 region（每個維度是獨立 repo，見 [02](02-data-model.md) §2.1）→ region → chunk → section，只有雜湊不同的 section 才解開比較方塊。所以就算世界很大，diff 的成本只跟「變動量」有關。

方塊級 diff 的結果：

| 類型 | 條件 | 顯示色 |
|---|---|---|
| 新增 | 原本是空氣（或流體），現在是方塊 | 綠 |
| 移除 | 原本是方塊，現在是空氣 | 紅 |
| 修改 | 方塊類型或 state 不同、或 block entity 內容不同 | 黃 |
| 衝突 | merge 時兩邊都改了同一格且結果不同（§2） | 紫 |
| 實體 | 依 UUID：新增 / 移除 / 屬性改變 / 移動 | 同上三色；移動畫箭頭 |

### 1.1 四端共用的 diff 色彩規範（使用者建議，2026-09-30）

所有顯示 diff 的地方都用**同一套顏色與記號**，玩家在遊戲內、終端機、網頁看到的意思一致，就像 git 的 `+`/`-` 在哪裡都是綠/紅。色票定義在共用的 `protocol/` 模組（插件、模組、CLI、Hub 都讀同一份），不在各端各自寫死。

| 類型 | 顏色（預設色票） | 記號 | 遊戲內（Fabric 模組） | 遊戲內（只有插件） | CLI | 網頁 |
|---|---|---|---|---|---|---|
| 新增 | 綠 `#3FB950` | `+` | 綠色半透明外框（方塊本身已在世界上） | 綠色發光描邊（display entity） | 綠色 `+` 行 / 統計 | 綠色外框或染色 |
| 移除 | 紅 `#F85149` | `-` | 紅色半透明**鬼影方塊**（顯示已不存在的方塊） | 紅色描邊 + 半透明 block display 鬼影 | 紅色 `-` | 紅色半透明鬼影 |
| 修改 | 黃 `#D29922` | `~` | 黃色外框；準星指向時顯示「舊 → 新」 | 黃色描邊 | 黃色 `~` | 黃色染色，懸停看前後 |
| 衝突 | 紫 `#A371F7` | `!` | 紫色外框並緩慢閃爍；衝突區域的包圍盒也用紫色 | 紫色描邊 | 紫色 `!`，`merge` 結束時列出衝突區域 | 紫色，點擊進入衝突解決檢視 |

規則：
- **衝突在解決流程中的三種狀態**：選 ours / theirs / base 時，方塊內容跟著切換，外框維持紫色；已解決的區域改成暗灰色外框，全部解決後消失。
- **色盲友善**：紅綠色盲會分不出新增和移除，所以顏色之外一定搭配**不同的呈現方式**（新增 = 實心外框、移除 = 鬼影方塊、修改 = 虛線或角標、衝突 = 閃爍），不只靠顏色辨識。`worldgit.yml`、模組設定與網頁偏好都可以改用色盲色票（例如新增藍 / 移除橘）或自訂色票。
- **CLI**：比照 git，輸出到終端機時預設上色，導向檔案或 `--color=never` 時不上色；`--format=json` 輸出類型字串（`added`/`removed`/`modified`/`conflict`），不帶顏色。
- 方塊太多時（例如數萬格），遊戲內改畫「區域包圍盒」，顏色取該區域內最重要的類型：衝突 > 修改 > 移除 > 新增。
- Phase 0 已在 Fabric 模組兩版實作這套顏色與呈現方式（`experiments/05-fabric-poc/`，截圖在 `screenshots/`）；實測 10 萬格逐格畫太重，證實需要上一條的包圍盒。

輸出：
- **CLI**：`wgit diff main castle-v2` → 每個 chunk 的 +/-/~/! 統計；`--format=json` 給工具用
- **遊戲內**：有 Fabric 模組時客戶端渲染鬼影與外框；只有插件時用 display entity 發光描邊，方塊太多時改為「區域包圍盒」描邊
- **網頁**：3D 檢視器，左右並排 / 疊圖 / 滑桿切換（見 [10](10-web-frontend.md)）

## 2. 三方合併演算法

```
base   = merge-base(ours, theirs)          # 最近共同祖先
for 每個 section 位置（取三邊 tree 的聯集）:
    if ours == theirs:           結果 = ours          # 包含兩邊都沒改
    elif ours == base:           結果 = theirs        # 只有對方改
    elif theirs == base:         結果 = ours          # 只有我們改
    else:                        下探方塊級合併
        for 每一格 (4096 格):
            同上規則；兩邊都改且結果不同 → 衝突格
        block entity：跟著所在方塊格走，視為方塊的一部分（整個 NBT 當原子）
        實體：以 UUID 為鍵做同樣的三方比較；兩邊都改同一個實體 → 衝突
```

絕大多數情況（兩人蓋在不同地方）會在 section 層就短路完成，**完全不需要人介入**——這符合原始筆記的觀察：「單人情況下大部分時候沒有衝突」。

### 衝突區域（conflict region）

一格一格問使用者是不可能的。把衝突格做 3D 連通分量分群（相鄰或距離 ≤ k 格的合併在一起），再以包圍盒呈現：

```
衝突 #1  overworld (120,64,-40) ~ (138,80,-22)   312 格   ours: alice   theirs: bob
衝突 #2  overworld (400,70,15)  ~ (402,72,17)      9 格   ...
```

每個衝突區域是一個解決單位，選項：
- **ours**（主線 / 目前分支）
- **theirs**（對方分支）
- **base**（兩邊都不要，回到分歧前）
- **手動**：自己在世界裡改，改完標記為已解決
- （可選）**區域內混合**：用 WorldEdit 選取子範圍分別指定

### 語意上的問題（沒有衝突但結果不對）

方塊有相鄰依賴，純逐格合併可能得到「每格都合法、整體不對」的結果：
- 柵欄/牆/玻璃片的連接狀態、紅石線的連接方向、門的上下半、床的頭尾、大型植物
- 快照保存的是遊戲當時算好的方塊 state（含連接方向），合併時每一格原樣取自來源，一般情況結果正確。只有兩邊變動直接相鄰、正確的組合兩邊都沒保存過時（例如兩邊各在相鄰位置放了一段柵欄），連接可能不一致。
- 決定（2026-10-02，#46）：**不重算、不觸發鄰居更新，維持儲存的 state**；這類交界格列入報告的提示清單供使用者檢查。多格結構（門、床）在分群時強制綁在一起。
- 紅石電路的邏輯正確性無法自動保證 → 在合併報告中標註「此區域含紅石元件，建議測試」。

## 3. 遊戲內的合併流程

原始筆記提到「應該要有像藍圖或 Axiom 那種方塊預覽介面」。我的建議是：**既然活的世界就是 working tree，最直觀的預覽就是直接把方塊放在世界裡**，讓玩家切換。

```
/wg merge castle-v2
 → 無衝突部分直接寫入世界
 → 衝突區域預設先放 ours，並用發光外框標出；bossbar 顯示「合併中：剩 3 個衝突」
 → 世界狀態 = MERGING（記錄 MERGE_HEAD）

走進衝突區域 → 動作列提示；手持「合併工具」（例如特殊的指南針）：
   右鍵 = 在 ours / theirs / base 之間循環（真的替換世界中的方塊，即時看到）
   Shift+右鍵 = 標記為已解決（目前看到的版本）
   或直接動手改 → /wg resolve <#> 
 → GUI（箱子介面）列出所有衝突，點擊傳送過去

全部解決 → /wg commit → 產生有兩個 parent 的 merge commit
隨時   → /wg merge --abort → 世界回到合併前（= HEAD），零風險
```

「在原地切換」之外的補充預覽：
- 插件端：對單一玩家顯示「另一個版本」的鬼影（display entity），用於比較而不必切換
- 模組端：客戶端半透明渲染，體驗接近 Axiom

## 4. 網頁端的合併（PR）

- 同樣的衝突區域清單 + 3D 檢視器，每個區域可切換 ours/theirs/base 並選擇
- 「手動」選項在網頁上無法編輯方塊 → 標記為「待遊戲內處理」；之後可考慮在網頁檢視器中加入簡單的方塊編輯
- 完成後由 Hub 伺服器產生 merge commit，玩家下次 pull 就拿到

## 5. 合併前提

- ours／theirs／base 任一 DataVersion 與目前世界不同 → 依決定 #28 明確拒絕；core 不提供 DataFixer，也不修改版本數字。需要升級時先在複本用相應伺服器升級並重新提交。
- 兩邊的 `.wgignore` 不同 → 先把 `.wgignore` 本身當成一般檔案合併（可能衝突），再用合併後的規則過濾兩邊內容；在合併報告中列出規則差異。

## Phase 1 的中性 diff 模型（2026-10-01）

`WorldDiff` 由 core 提供，包含 `SectionChange`、`BlockChange`（含 BE 前後 NBT）、`EntityChange`、`BiomeChange`、`BlobChange`；四種 `ChangeKind` 小寫名稱為 added/removed/modified/conflict。同格 state 與 BE 都變化只計一格。實體在各維度內以 UUID 全域比對。

預設 capture/CLI 使用 SUMMARY：只算 section 的 +/-/~/!，不建立幾千萬筆方塊物件；biome 用 `sampleIndex=-1` 與 count 表示 section 統計。BLOCKS 模式才展開逐格與逐 biome sample。Hub/Fabric 可用 `DiffEngine.compare(..., Detail.BLOCKS, Set<ChunkPos>)` 限定顯示視窗，範圍外方塊/biome 不解碼；實體先全域比對再裁切，跨視窗移動不會變成錯誤的新增/移除。

CLI 的正式機器介面是 `--format=json`，明細另加 `--blocks`；無參數為 HEAD→世界，一參數為該 commit→世界，兩參數為 commit→commit。`NO_COLOR` 存在時一律關色，包含 `--color=always`。色票與 symbol/style 統一由 `protocol.DiffPalette` 提供。


## Phase 3 core／CLI 正式規則（2026-10-02）

`MergeEngine` 的 root／region／chunk／section id 相同時短路；只有兩邊都改且不同的 section 解碼為 4096 格比較。BE 跟所在格組成一個原子值，不能把兩邊分別修改的 NBT 欄位拼起來。biome 逐 4×4×4 sample 比較；ticks／structures 維持 chunk blob 原子；world-meta 的 NBT compound 遞迴逐鍵比較，list／陣列／scalar 原子。一般 YAML／dimensions 檔案採整 blob 三方比較。

實體另以維度內全域 UUID 比較（含跨 chunk 移動）。只有一邊改／刪除會自動套用；兩邊都修改同 UUID 即衝突，**相同新 NBT 也保守衝突**，因此 entity 階段不能被 ours==theirs 的 tree 短路省略。一刪一改為衝突；移動兩端與 base 位置共用同一個區域。

`MergeBases.best/unique` 走所有 parent，不只 first-parent。維度之間不共用 commit id：先配對 snapshot group，再各自找 base。來源分支缺少某維度保留 ours；某維度的兩個 tip 無共同歷史，以空 tree 為 base。criss-cross 產生多個最佳 base 時明確拒絕並列出 id，需先建立明確共同整合基底，不任選一個 base。

區域採曼哈頓距離 ≤ k 的連通分量，k 預設 1、可設 0..16。門與大型植物的 `half=upper/lower`、床的 part／facing、伸出活塞與活塞頭的 facing 強制綁定（即使 k=0），且把不衝突的另一半加入切換集合。區域包含 bounds、blockCount、dimension、commit 作者／Contribution 身分、紅石旗標、choice 與 resolved；metadata 區域 bounds=null、blockCount=0。作者是來源 tip 的主要作者與 contributions 摘要，尚非逐格歷史 blame。Mod 的特殊多格結構未提供 registry，平台需補自己的語意綁定。

`Region.atoms` 是精確切換集合；**bounds 內其他格子不會被覆蓋**。BE 隨格、biome 隨 sample、實體隨 UUID、設定隨 NBT key path。實體依距離分群或歸入包含其位置的區域，沒有方塊衝突時獨立成區。

鄰居清單檢查來自 theirs 的變動格與六鄰居，包含來源交界處未修改的 ours 柵欄／牆／玻璃片／鐵欄杆／紅石線，以及門、樓梯、軌道等保守候選。切換區域後重新計算清單。清單屬於各維度的報告，cell 為世界座標。CLI 保留快照 state，不套規則表、不假設伺服器載入會更新；輸出提示與 JSON 清單，完成報告留在 `last-merge-report.bin`。平台套用時同樣維持儲存的 state，不做 updateShape（#46）；清單僅為提示。紅石警示只提示「建議測試」，不能驗證電路。

開始合併要求乾淨工作區（包括保留的 untracked），使用者先 commit 或 stash push；不自動 stash。無衝突部分＋衝突區預設 ours 一起套用，HEAD 保持原位，持久化 MERGING。`selectRegion(id, choice, false, dryRun)` 只切換；`markResolved` 另標記。CLI `resolve` 同時切換與標解決，manual 不寫回、以當下世界為準。`merge --continue`／`commit -m` 要求全部解決，所有維度 capture 完成後才移動 refs；同一個新 snapshot UUID，不同 tip 有兩個 parent，相同 tip 去重。來源 id 記入 WorldGit-Merge-Source／Revert-Source／CherryPick-Source trailer。

`merge --abort` 重新套用合併前內容並恢復規則，HEAD／分支不變。合併要求乾淨，所以沒有需要自動還原的未提交追蹤資料；規則變動時另保存合併前被排除的內容，防止重新納入後的修改無法 abort。合併期間的手動修改會被丟棄。APPLYING／PARTIAL 與 MERGING 分別記錄寫回狀態與衝突流程；失敗不移動 HEAD，MERGING 的 PARTIAL 用 merge --abort 恢復，避免 switch／reset 把合併狀態留成孤兒。

規則使用 JGit 有序文字三方合併，規則衝突在任何世界寫入前拒絕，報告包含 base／ours／theirs／merged 的原文。新規則過濾三邊保存的內容；ours 重新 capture 可取回剛重新納入的活世界資料，其餘歷史無法取回當時未追蹤的資料，視為未保存。area 排除保持活世界內容；有 area 時 ticks／structures 沿用 Phase 2 保守套用限制。biome 的空字串表示未追蹤 sample，不能要求把活世界 biome 變成空值。

revert／cherry-pick 走相同 MERGING、resolve、abort；乾淨時直接建立單 parent 新 commit。以 snapshot UUID 識別不變維度，避免誤撤銷該維度上一次較早的 commit。多 parent 來源缺少 mainline 選項，先拒絕；root commit 的 revert 若需刪除 level.dat／dimensions 等不可安全套用資料，會在預檢拒絕。

API、驗收與未完成項目見 [13](13-phase3-progress.md)。
