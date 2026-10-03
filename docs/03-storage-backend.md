# 03 — 儲存後端：用真的 git，還是自己做？

這是最早要拍板、影響最深的決策。

> **已決定（2026-09-30）：採用選項 A（JGit）**，core 內保留 `ObjectStore` / `RefStore` 介面，之後視量測結果再評估是否換成自製後端。

## 選項 A：以 JGit 為後端（把世界映射成真正的 git repo）

每個 section blob 就是 git 裡的一個檔案，路徑例如：

```
r.0.0/c.3.7/s.5.bin
r.0.0/c.3.7/entities.bin
```

**免費得到：**
- 成熟、經過驗證的物件庫、packfile、delta 壓縮、gc
- push / pull / clone 協定、認證、`--filter` 部分 clone、sparse-checkout
- 可以直接放在 GitHub / GitLab / Gitea 上（Hub 初期甚至可以不用自己做儲存，只做「看世界」的前端）
- 用一般 `git log`、`git diff --stat` 就能看出哪些 chunk 變了，除錯方便

**要自己做的：**
- 世界 ↔ git tree 的轉換（本來就要做）
- **合併**：不能用 git 的文字合併；要用 JGit 的 API 自己走三方比較（`TreeWalk` 取 base/ours/theirs 的 blob 再交給我們的方塊級合併器），不透過 git 的 merge driver
- working tree 不是檔案系統而是活的世界 → 不使用 JGit 的 checkout，只使用它的物件庫與 refs

**風險：**
- 檔案數量：大世界可能有數十萬個 section 檔（全空氣 section 可以直接不存、由「缺檔 = 空氣」表示）。git 對此量級沒問題，但 GitHub 有 repo 大小的軟限制（約數 GB）——大型伺服器最終仍需自架 Hub。
- SHA-1 對「一格方塊」這種小物件的開銷相對大（可接受）。

## 選項 B：自製物件庫

類似 git 的設計但針對世界最佳化：BLAKE3、zstd 字典壓縮（同一世界的 section 高度相似，字典壓縮效果很好）、以 region 為單位打包、原生支援「空間查詢」（給我這個 AABB 內所有 chunk 的歷史）。

**優點：** 效能與大小可最佳化；可以設計空間索引；不被 git 的模型綁住。
**缺點：** 傳輸協定、gc、打包、認證、部分 clone 全部自己寫；Hub 一定要自己做儲存。

## 建議

**先做 A，但在 core 裡用一層 `ObjectStore` / `RefStore` 介面隔開。**

理由：前期最大的風險是「方塊級 diff/merge 與線上切換做不做得出來」，不是儲存效率。用 JGit 可以把傳輸、儲存、gc 的工作量歸零，讓專注力放在 MC 特有的難題上；等真的遇到效能瓶頸（有實際世界的量測數據）再換 B，並做 A→B 的遷移工具。

## 預計在 Phase 0 量測的數字

- 一個典型生存伺服器世界（例如 5k～20k chunk）初次 init 的物件數、repo 大小、耗時
- 一般遊玩 1 小時後一次 commit 的新增大小
- 在 GitHub 上 push/clone 的實際表現

## Phase 0 量測結果（2026-10-01，`experiments/06-survival-scale/`）

26.2 Paper，Chunky 預生成 20,521 個 full chunk（3 核心機器，數字為量級）：

| 項目 | 數字 |
|---|---|
| init | 112.5 秒、RSS 481 MB、210,538 個物件；串流寫入版 RSS 420 MB、樹完全相同 |
| gc 後 pack | **110.3 MB**（每 chunk 約 5.4 KB）；gc 主要省磁碟區塊（58 MB → 7 MB 佔用），內容 bytes 不再變小（blob 已是 zstd） |
| 增量 commit | 小改動 1.2 秒／35 KB；探索 289 個新 chunk 2.8 秒／1.6 MB |
| 外推（線性） | 10 萬 chunk 約 540 MB、9–10 分鐘；100 萬 chunk 約 5.4 GB、90–100 分鐘 |
| push／clone（本機 smart HTTP） | 首次 push 110 MB：C git 2.9 秒、JGit 4.8 秒；clone 6–8 秒；增量 push/fetch 約 1.6 MB、< 1 秒 |
| `clone --depth 1` | **不省流量**（112 MB），因為最新 commit 的樹就是整個世界 |

發現與待辦：
- **單一 pack 已超過 GitHub 的 100 MB 單檔限制**（2 萬 chunk 就碰到）。已決定（[09](09-roadmap-open-questions.md) #17）：
  - 所有 repo 一律設定 `pack.packSizeLimit`（預設 95 MB，留安全邊際），`init`、gc 與 push 時產生的 pack 都不超過這個大小；
  - 小世界可直接放 GitHub 等一般 git 託管，大世界放自架服務（WorldGit Hub、Gitea…）；
  - 每個維度是獨立 repo（[02](02-data-model.md) §2.1），單一 repo 也因此變小。
  - 注意：push 時 git 是把要傳的物件組成一個傳輸用的 pack 送出，伺服器端也可能有單次請求大小限制；首次 push 大世界時要能分批推送（依 region 範圍分成多個 commit 或多次 push），Phase 4 正式傳輸見下節，Phase 0／1 數字仍只代表當時實作。
- 原型問題：JGit gc 留下重複 pack；串流版會產生 dangling tree；init 尚未平行化讀 region、也沒 profile 過瓶頸。
- 尚未測：Gitea、partial／sparse clone、真實網路。

## Phase 1 的 pack 實作補充（2026-10-01）

每維度 bare repo 設 `pack.packSizeLimit = 95000000`，但 JGit 7.3 的 `PackConfig` / `PackWriter` 不讀取這項分割設定；只寫 config 並不足以讓 JGit GC 符合決定 #17。正式 `JGitStore.repack()` / `gc()` 使用 JGit PackWriter，自行依 zlib 的壓縮上界分組，禁用 delta/reuse，確保每個 pack ≤ 95,000,000 bytes。分組各自完整、不依賴另一個 pack 的 delta；先安裝全部 pack 與 index，再移除舊 pack 與已打包 loose objects。測試實際產生超過 95 MB 的物件，確認分包及重新開啟後完整可讀。

停用 JGit 自動 GC，所有 WorldGit 維護經 `DimensionRepository` 的 operation lock 與有界 repack API。外部 JGit GC 沒有上述保證。GC 目前保守保留不可達 loose objects，尚未提供到期 prune；pack 不使用 delta 的大小與效能代價列於 [Phase 1 報告](11-phase1-progress.md)。

依據：[JGit 7.3 PackConfig 原始碼](https://github.com/eclipse-jgit/jgit/blob/v7.3.0.202506031305-r/org.eclipse.jgit/src/org/eclipse/jgit/storage/pack/PackConfig.java)、[PackWriter 原始碼](https://github.com/eclipse-jgit/jgit/blob/v7.3.0.202506031305-r/org.eclipse.jgit/src/org/eclipse/jgit/internal/storage/pack/PackWriter.java)。

## Phase 4 正式傳輸（2026-10-03）

`GitTransfer` 支援 JGit smart HTTP 與 file transport。每個維度獨立 repo，但 remote 操作以 snapshot group 發布一個世界。`pack.packSizeLimit` 不會分割一次 HTTP push；本次先按物件相依順序建立合成中繼 commit，逐批推到單調序號 `refs/worldgit/transfers/000…`。最新中繼 tree 累積引用所有已送批次（共用 batch 子樹，root 為扁平批次清單），使 JGit boundary 排除已知樹；只串 parent 會在大型階層樹重送早期物件。每批最多 10,000 物件，以 zlib 最壞上界限量，禁用 delta/reuse/thin，PackWriter 先實際序列化驗證 ≤95,000,000 bytes。預計 pack 超限時繼續遞迴切小批次，合成 commit 日期不早於 parents。原始 trees/commits/tags 不改寫；中繼 refs 不在 WorldGit 使用者 log 顯示，必須保留供空 repo clone 逐批 negotiation。裸 repo 新合併先用 `stageLocal` 建立相同 refs。

push 的 `PackSize.preparedBytes` 是預計 PACK，`wireBytes` 是截取 HTTP POST（含 gzip request 解碼）內 PACK 到 request 結尾的實際 bytes；file transport 的 wireBytes 為 null。fetch 的 preparedBytes 是實際落地 `.pack` 大小，wireBytes 為 null。超限 fetch pack 不發布 ref 並刪除超限 pack/index。接收端若省略分批 refs 且最新樹超過上限，目前明確拒絕；由 WorldGit 發布的世界可正常分批 clone。一般服務的 GC/配額及原生 git 直接 clone 不由 core 保證，真正 GitHub／Gitea 尚未測。

全組 FF 預檢後，各維度使用 expected-old CAS；僅 `--force-with-lease` 可以改寫使用者分支，且 remote tip 必須等於本機最近 fetch 的 tracking tip。tags 與 snapshot group refs 不可覆寫不同內容。各分支 `refs/worldgit/publications/<branch>` 指向含全維度 tip map 的 YAML commit；marker 沿用舊 marker 為 parent，符合既有 Hub 的非 FF 拒絕政策。多 repo 無原子 transaction：PARTIAL 持久 journal 固定 targets，WorldGit fetch/clone 拒絕缺 marker、混合 marker 或不匹配 tips，原推送端以同參數重試即可補齊。第三方已修改的 ref 會阻擋恢復，不盲目回滾遠端。

fetch 先下載到 incoming refs，檢查全組一致後才更新 tracking/groups/tags；本機發布以 journal 回復。完整 API、真 HTTP 驗收、量測及失敗紀錄見 [14](14-phase4-progress.md)。
