# 03 — 儲存後端：用真的 git，還是自己做？

這是最早要拍板、影響最深的決策。

## 選項 A：以 JGit 為後端（把世界映射成真正的 git repo）

每個 section blob 就是 git 裡的一個檔案，路徑例如：

```
overworld/r.0.0/c.3.7/s.5.bin
overworld/r.0.0/c.3.7/entities.bin
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
