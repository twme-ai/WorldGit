# 07 — 雲端同步與網頁端 Hub

原始筆記中「MC 應該用不到的功能」是筆誤：**類 GitHub 的網頁檢視端是本專案的正式目標之一**（已確認，2026-09-30）。它也是多人協作合併（痛點 2）的前提：兩個人要各自蓋再合併，他們的世界必須能交換歷史——不管是兩台伺服器、兩個單人存檔，還是一台伺服器上的兩個分支。

## 1. 使用情境

| 情境 | 需要什麼 |
|---|---|
| 換電腦、換主機商繼續開發 | `clone` → 得到可以直接開的世界 |
| 建築團隊：每人在自己的單人世界蓋一區，最後合成一張地圖 | 各自 `push` 分支 → Hub 上發 PR → 合併 → 伺服器 `pull` |
| 地圖作者發布作品 | `tag` → Hub 的 release 頁提供世界 zip 下載 |
| 伺服器異地備份 | 定時 `push`（比傳統備份省空間，而且可以回到任意存檔點） |
| 測試伺服器 → 正式伺服器 | 在測試服 commit，正式服 `pull` 或 `restore --source origin/main --selection` 只拉某區域 |

## 2. 傳輸

- 若採用 JGit 後端（見 [03](03-storage-backend.md)）：直接使用 git 的 smart HTTP / SSH 協定，Hub 可以先用現成的 git 伺服器（Gitea、GitHub）當儲存，自己只做「看世界」的層。
- push/pull 只傳對方沒有的 section，通常一次幾百 KB～數 MB。
- **部分 clone**：大伺服器（數十 GB）只想拉某區域 → 路徑本身就帶座標（`overworld/r.x.z/...`），可用 git 的 sparse-checkout / partial clone 以 region 為單位篩選。

## 3. Hub（類 GitHub 網頁端）功能

| 頁面 | 內容 |
|---|---|
| Repo 首頁 | 俯視地圖（類 BlueMap/squaremap 的 tile），分支選單 |
| Commit 列表 | 每個 commit 附變動區域縮圖、作者、+/-/~ 統計；auto commit 折疊 |
| 實體檢視 | 生物也會出現在 3D 檢視中（使用簡化模型或圖示標記），diff 中標出新增/移除/移動 |
| Commit / Diff 檢視 | 3D 檢視器（可沿用 BlockForge 的真實方塊模型渲染），新增/移除/修改上色，地圖上標出變動 chunk |
| Pull Request | diff、座標釘選留言（「這裡的屋頂可以再高兩格」→ 留言帶 x,y,z，遊戲內可看到）、衝突解決（見 [06](06-diff-merge.md)）、合併按鈕 |
| Release | tag 對應的世界 zip 下載（由 Hub 從物件組出 region 檔） |
| 權限 | 誰可以 push 到哪個分支；受保護分支（main 只能經 PR） |

## 4. 部署方式：自架與公開服務都要（已決定，2026-09-30）

**同一套程式碼，兩種部署**，避免維護兩個版本：

| | 自架版 | 公開服務 |
|---|---|---|
| 對象 | 想把資料留在自己手上的伺服器、團隊 | 單人玩家、小團隊、不想架設的人 |
| 形式 | 單一 Docker image（或 jar），內建資料庫預設用 SQLite、repo 存本機磁碟 | 同一個 image，改用 PostgreSQL + 物件儲存（S3 相容），可水平擴充 |
| 帳號 | 本機帳號，可選 OAuth | OAuth 登入（GitHub、Discord、Microsoft 帳號…） |
| 額外需求 | — | 容量配額、速率限制、濫用檢舉、公開/私人 repo、帳單（若有） |
| Minecraft 端設定 | `/wg remote add origin https://hub.example.com/team/world` | 同樣語法，指向公開服務網址 |

設計上的影響：
- 儲存層與帳號層都要做成可替換的介面（本機/S3、SQLite/PostgreSQL、本機帳號/OAuth）。
- 從第一版就要支援**多租戶**（使用者、組織、repo 權限），自架版只是「只有一個組織」的特例。
- 自架版與公開服務之間可以互相 push/pull（都是標準 git 協定），所以使用者可以隨時搬家。

## 5. 伺服器 ↔ Hub 的整合

- 插件設定 Hub token 後，可在遊戲內 `/wg push`、`/wg pull`、`/wg pr create`
- Hub 上合併 PR 後可 webhook 通知伺服器（顯示「main 有新版本，/wg pull 更新」），**不自動套用**到活的世界，避免玩家腳下的方塊突然消失
- Hub 上的座標留言可以同步到遊戲內顯示（例如 TextDisplay 標記）
