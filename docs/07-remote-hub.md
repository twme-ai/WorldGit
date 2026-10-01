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
- Phase 0 實測（`experiments/06-survival-scale/`）：2 萬 chunk 世界首次 push／clone 約 110 MB、數秒（本機）；之後增量約 1–2 MB。`--depth 1` 不省流量。**單一 pack 會超過 GitHub 100 MB 單檔限制**。已決定（[09](09-roadmap-open-questions.md) #17）：pack 一律切成 < 100 MB；小世界可放 GitHub，大世界放自架服務；每個維度是獨立 repo（#18），各自 push/pull。
- **部分 clone**：大伺服器（數十 GB）只想拉某區域 → 先依維度選 repo，repo 內路徑本身就帶座標（`r.x.z/c.x.z/...`），可用 git 的 sparse-checkout / partial clone 以 region 為單位篩選。

## 3. Hub（類 GitHub 網頁端）功能

| 頁面 | 內容 |
|---|---|
| Repo 首頁 | 俯視地圖（類 BlueMap/squaremap 的 tile），分支選單 |
| Commit 列表 | 每個 commit 附變動區域縮圖、作者、+/-/~ 統計；auto commit 折疊 |
| 實體檢視 | 生物也會出現在 3D 檢視中（使用簡化模型或圖示標記），diff 中標出新增/移除/移動 |
| Commit / Diff 檢視 | 3D 檢視器（全新撰寫，見 [10](10-web-frontend.md)），新增/移除/修改上色，地圖上標出變動 chunk |
| Pull Request | diff、座標釘選留言（「這裡的屋頂可以再高兩格」→ 留言帶 x,y,z，遊戲內可看到）、衝突解決（見 [06](06-diff-merge.md)）、合併按鈕 |
| Release | tag 對應的世界 zip 下載（由 Hub 從物件組出 region 檔） |
| 權限 | 誰可以 push 到哪個分支；受保護分支（main 只能經 PR） |

## 4. 部署方式：自架與公開服務都要（已決定，2026-09-30）

**同一套程式碼，兩種部署**，避免維護兩個版本：

| | 自架版 | 公開服務 |
|---|---|---|
| 對象 | 想把資料留在自己手上的伺服器、團隊 | 單人玩家、小團隊、不想架設的人 |
| 形式 | 單一容器映像（Docker／Podman 皆可，見 §4.1；或直接跑 jar），內建資料庫預設用 SQLite、repo 存本機磁碟 | 同一個 image，改用 PostgreSQL + 物件儲存（S3 相容），可水平擴充 |
| 帳號 | 本機帳號，可選 OAuth | OAuth 登入（GitHub、Discord、Microsoft 帳號…） |
| 額外需求 | — | 容量配額、速率限制、濫用檢舉、公開/私人 repo、帳單（若有） |
| Minecraft 端設定 | `/wg remote add origin https://hub.example.com/team/world` | 同樣語法，指向公開服務網址 |

### 4.1 容器打包（已決定，2026-10-01）

Hub 以 **OCI 容器映像**發佈，**Docker 與 Podman 都要能直接部署**（[09](09-roadmap-open-questions.md) #20）：

- 一個映像同時涵蓋自架版與公開服務，差別只在環境變數／設定檔（資料庫、物件儲存、OAuth）。
- 以非 root 使用者執行、不依賴 Docker 專屬功能，確保 **rootless Podman** 可用；資料目錄（repo、SQLite、BlueMap tile 快取）集中在一個 volume（例如 `/data`），在 SELinux 主機上用 `:Z` 掛載。
- 提供 `compose.yaml`（`docker compose` 與 `podman compose` 共用）：預設只有 Hub 一個服務；公開服務的範例再加上 PostgreSQL 與 S3 相容儲存（例如 MinIO）。
- 另外提供 Podman 的 Quadlet（systemd）範例，方便不用 compose 的 Linux 主機開機自動啟動。
- 映像支援 amd64 與 arm64；健康檢查用 Spring Boot actuator 的 health endpoint。
- CI 對映像做冒煙測試：分別用 Docker 與 rootless Podman 啟動，跑一次 push → 網頁讀取。

**Phase 1 實作狀態（2026-10-01）**：`hub/Containerfile`（node 建前端 → Gradle 建 jar → `eclipse-temurin:25-jre`，非 root uid 10001，資料在 `/data`，映像約 400 MB）、`hub/compose.yaml`、`hub/deploy/` 的 Quadlet 範例與 `hub/scripts/container-smoke.sh` 已完成。已用 Podman 4.9.3（root 模式）驗證 `podman build`、`podman run` 的 push → API／網頁讀取冒煙測試，以及 `podman-compose up`（healthcheck 變 healthy）。其後（同日）又驗證 Quadlet 由 systemd 啟動（rootful 與 rootless）與 PostgreSQL 後端，細節見 [11 的部署驗證](11-phase1-progress.md)。**尚未驗證**：Docker 引擎實跑（語法只用兩者共通部分）、arm64 映像。podman 建置要用 `--format docker` 才保留 HEALTHCHECK。建置 context 必須是 repo 根目錄（`podman build -f hub/Containerfile .`）；`.dockerignore` 排除 paper／fabric 等其他模組。CI 的 `hub-image` job 已加入冒煙測試（docker；SQLite 與 PostgreSQL），尚未在 GitHub 上實跑。

設計上的影響：
- 儲存層與帳號層都要做成可替換的介面（本機/S3、SQLite/PostgreSQL、本機帳號/OAuth）。
- 從第一版就要支援**多租戶**（使用者、組織、repo 權限），自架版只是「只有一個組織」的特例。
- 自架版與公開服務之間可以互相 push/pull（都是標準 git 協定），所以使用者可以隨時搬家。

### 4.2 上線前必做（worldgit.org，決定 #23）

安全修補與實測見 [Hub 安全審查](../hub/docs/security-review-2026-10-01.md)。公開部署必須使用 TLS 反向代理、開啟 `worldgit.hub.security.hsts`，只有代理確實覆寫 X-Forwarded-For 時才能設定可信代理 IP；配置 owner 配額、解析預算與認證限流，檢查磁碟餘裕與 secrets 權限。現有配額／限流以單 Hub 實例為界；水平擴充前必須實作共享狀態。Git 未帶認證一律 challenge，公開 clone 使用明確的 anonymous／空密碼；REST 私人與不存在一律 404。

**政策項目仍需部署者完成，本次未實作自助註冊或 bootstrap 政策變更**：開放自助註冊前決定信箱驗證、註冊限流、保留字與冒充名稱處理、濫用檢舉政策。正式環境須以 secret 提供 bootstrap 密碼，避免使用印在 log 的隨機密碼；上線時更換並妥善保存管理員密碼，建立具到期日 PAT、撤銷不再使用的 bootstrap token，停止在一般操作中使用 bootstrap 憑證。既有永不過期 PAT 也應檢視並重建；新 PAT 已有預設期限與最後使用時間。

## 5. 伺服器 ↔ Hub 的整合

- 插件設定 Hub token 後，可在遊戲內 `/wg push`、`/wg pull`、`/wg pr create`
- Hub 上合併 PR 後可 webhook 通知伺服器（顯示「main 有新版本，/wg pull 更新」），**不自動套用**到活的世界，避免玩家腳下的方塊突然消失
- Hub 上的座標留言可以同步到遊戲內顯示（例如 TextDisplay 標記）
