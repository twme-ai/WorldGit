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

三層機制，由便宜到可靠：

| 層級 | 做法 | 可靠度 |
|---|---|---|
| 事件提示 | 插件監聽 BlockPlace/Break/Explode/Piston/Physics、WorldEdit `EditSessionEvent`；模組用 mixin 攔 `LevelChunk.setBlockState` | 插件端不完整（FAWE、其他插件直接改 NMS 會繞過事件）；模組端幾乎完整 |
| region 時間戳 | region 檔頭有每個 chunk 的「最後寫入時間」；跟上次 commit 記錄的時間戳比 | 可靠但只對「已存回磁碟」的 chunk 有效；且任何存檔都會更新（包括只是 InhabitedTime 變） |
| 內容雜湊 | 對候選 chunk 做正規化 + 雜湊比對 | 最終真相 |

這跟 git 的 index 一樣：**index 記錄每個 chunk 上次看到的時間戳與雜湊**，時間戳沒變就不重算。完整掃描（`/wg status --full`）只在懷疑不一致時用。

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
