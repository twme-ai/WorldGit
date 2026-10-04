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

- 若採用 JGit 後端（見 [03](03-storage-backend.md)）：設計採用 git 的 smart HTTP / SSH 協定（Phase 4 本次實作 HTTP(S)/file，SSH 尚未提供），Hub 可以先用現成的 git 伺服器（Gitea、GitHub）當儲存，自己只做「看世界」的層。
- push/pull 只傳對方沒有的 section，通常一次幾百 KB～數 MB。
- Phase 0 實測（`experiments/06-survival-scale/`）：2 萬 chunk 世界首次 push／clone 約 110 MB、數秒（本機）；之後增量約 1–2 MB。`--depth 1` 不省流量。**單一 pack 會超過 GitHub 100 MB 單檔限制**。已決定（[09](09-roadmap-open-questions.md) #17）：pack 一律切成 < 100 MB；小世界可放 GitHub，大世界放自架服務；每個維度是獨立 repo（#18），底層各自傳輸，正式 CLI/core 以全維度 snapshot group 協調發布。
- **部分 clone**：大伺服器（數十 GB）只想拉某區域 → 先依維度選 repo，repo 內路徑本身就帶座標（`r.x.z/c.x.z/...`），未來可用 git 的 sparse-checkout / partial clone 以 region 為單位篩選。本次只提供 `clone --dimension`，尚未實作 region sparse。

## 3. Hub（類 GitHub 網頁端）功能

| 頁面 | 內容 |
|---|---|
| Repo 首頁 | 俯視地圖（類 BlueMap/squaremap 的 tile），分支選單 |
| Commit 列表 | 每個 commit 附變動區域縮圖、作者、+/-/~ 統計；auto commit 折疊 |
| 實體檢視 | 生物也會出現在 3D 檢視中（使用簡化模型或圖示標記），diff 中標出新增/移除/移動 |
| Commit / Diff 檢視 | 3D 檢視器（全新撰寫，見 [10](10-web-frontend.md)），新增/移除/修改上色，地圖上標出變動 chunk |
| Pull Request | diff、座標釘選留言（「這裡的屋頂可以再高兩格」→ 留言帶維度與 x,y,z；網頁標記與 Paper／Folia 私人 TextDisplay 已有，Fabric 遠端接線為下一任務）、衝突解決（見 [06](06-diff-merge.md)）、合併按鈕 |
| Release | tag 對應的世界 zip 下載（由 Hub 從物件組出 region 檔） |
| 權限 | owner/admin/write/read、個人／團隊授權、PAT scope；受保護分支可選 main PR-only／審核數 |
| 帳號／通知 | 本機＋可選 OAuth、組織／團隊、最小信箱驗證註冊；PR 通知與有簽章 webhook |

## 4. 部署方式：自架與公開服務都要（已決定，2026-09-30）

**同一套程式碼，兩種部署**，避免維護兩個版本：

| | 自架版 | 公開服務 |
|---|---|---|
| 對象 | 想把資料留在自己手上的伺服器、團隊 | 單人玩家、小團隊、不想架設的人 |
| 形式 | 單一容器映像（Docker／Podman 皆可，見 §4.1；或直接跑 jar），內建資料庫預設用 SQLite、repo 存本機磁碟 | 同一個 image，已可換 PostgreSQL；S3 與水平擴充為規劃，尚未實作 |
| 帳號 | 本機帳號，可選 OAuth | 本機／驗證註冊後明確連結 OAuth（GitHub、Discord、Microsoft） |
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

**Phase 4 驗收狀態（2026-10-03）**：jar／SQLite／PostgreSQL 與完整協作端到端已通過；容器映像建置與 SQLite／PostgreSQL 冒煙已由主對話以 Podman 驗證通過（Codex 沙盒禁止 `uid_map` 無法執行）；指令與證據見 [14 Hub](14-phase4-progress.md#hub)。

設計上的影響：
- 儲存層與帳號層都要做成可替換的介面（本機/S3、SQLite/PostgreSQL、本機帳號/OAuth）。
- 從第一版就要支援**多租戶**（使用者、組織、repo 權限），自架版只是「只有一個組織」的特例。
- 自架版與公開服務之間可以互相 push/pull（都是標準 git 協定），所以使用者可以隨時搬家。

### 4.2 上線前必做（worldgit.org，決定 #23）

安全修補與實測見 [Phase 4 安全審查](../hub/docs/security-review-phase4-2026-10-03.md) 與 [Phase 1 紀錄](../hub/docs/security-review-2026-10-01.md)。公開部署必須使用 TLS 反向代理、secure session cookie、正確 public-url、有限 idle/write timeout，開啟 `worldgit.hub.security.hsts`，只有代理確實覆寫 X-Forwarded-For 時才能設定可信代理 IP；配置 owner 配額、解析預算與認證限流，檢查磁碟餘裕與 secrets 權限。現有配額／限流以單 Hub 實例為界；水平擴充前必須實作共享狀態。Git 未帶認證一律 challenge，公開 clone 使用明確的 anonymous／空密碼；REST 私人與不存在一律 404。

**Phase 4 已提供預設關閉的最小自助註冊**：啟用時需配置 SMTP 與 `collaboration.registration.public-url`，驗證信 1 小時、token 雜湊與一次兌換；每 IP 3 次/小時，驗證前不建立可登入帳號。公開服務仍需決定保留字／冒充名稱處理、信箱重寄／恢復、濫用檢舉與跨 IP／多實例治理政策。OAuth 先建立並驗證本機帳號，再明確連結，不以第三方 email 自動認領既有帳號。bootstrap 政策未變更。正式環境須以 secret 提供 bootstrap 密碼，避免使用印在 log 的隨機密碼；上線時更換並妥善保存管理員密碼，建立具到期日 PAT、撤銷不再使用的 bootstrap token，停止在一般操作中使用 bootstrap 憑證。既有永不過期 PAT 也應檢視並重建；新 PAT 已有預設期限與最後使用時間。

## 5. 伺服器 ↔ Hub 的整合

Paper/Folia 已提供 `/wg remote`、fetch／push／pull、PR 建立／列表／詳情與座標留言（[Paper README](../paper/README.md)）。PAT 只從環境或插件資料夾普通 600 credentials YAML 取得，不能寫 config.yml／世界／URL。Hub REST、憑證模型、webhook 接收／驗簽與文字淨化放在平台中立的 `platform-api.remote`，Fabric 單人／專用伺服器亦接同一套 API（[Fabric README](../fabric/README.md)，決定 #98–#102）。

Hub 合併 PR 或 push 的 webhook，與可選定時 fetch，**都只提示、永遠不自動套用**。接收器預設關閉/loopback，只接受有大小、deadline、速率限制的 POST；HMAC-SHA256 constant-time，按 delivery 與簽章 body event id 持久去重。逐維度 push 還不代表發布完成，接收後背景 fetch 驗證完整 publication，成功才向有 pull 權限的玩家及 console 提示「遠端 main 有新版本，/wg pull 檢視」。

玩家 `/wg pull` 取得預覽，120 秒內再明確 `/wg pull confirm <code>`；確認前重新 fetch，遠端 tips/URL 與本地 HEAD lease 改變即拒絕，要求重新預覽。世界套用只走既有 live coordinator、編輯鎖、玩家保護、存檔／verify／HEAD barrier；衝突進 Phase 3 MERGING，保持 #46。遊戲 push 沒有 force；PR merge/approve 只在網頁 3D 檢視完成。

Paper／Folia 遊戲留言是只有請求者可見的非持久 TextDisplay，範圍用 per-player 粒子外框。Hub 資料當純文字，限制數量、長度與已載入 chunk；hide／離線／換維度清除，capture 額外排除插件的 display tags。Paper 停用同步清除；Folia 正常停服隨世界卸載消失，第三方熱卸載沒有立即跨 region 清理保證。安全邊界見 [Paper Phase 4 審查](../paper/docs/security-review-phase4-2026-10-03.md)，實測與 Fabric 接手摘要見 [14 Paper／Folia](14-phase4-progress.md#paper-folia)。


Fabric 留言以 comments-v1 有界快照送到請求者，由客戶端 literal HUD 與座標／範圍線框渲染，不建立實體、不入 capture；原版客戶端 show 明確說明需要模組，仍可讀文字清單。換維度、hide、撤權、離線清除。Fabric 單人新存檔／CLI clone 使用存檔內 `.worldgit/` 非秘密 remotes；PAT 使用使用者 config 600 credentials 檔或環境。單人不啟動 webhook port，選用定時 fetch 預設關閉，通知永遠不套用。專用伺服器沿 #95 receiver 與相同通知契約。細節與實測見 [14 Fabric](14-phase4-progress.md#fabric)。

## 6. Phase 4 core／CLI remote 契約（2026-10-03）

Hub 世界 URL `https://hub.example.com/alice/castle` 展開為 `https://hub.example.com/git/alice/castle/minecraft.overworld.git` 等路徑；反向代理前綴可保留。一般 GitHub/Gitea 各維度建立 repo，使用 `https://git.example/team/castle-{dimension}.git`，`{dimension}` 是安全的維度目錄名（例如 minecraft.the_nether），或建立 YAML 世界清單：

```yaml
dimensions:
  minecraft:overworld: https://git.example/team/castle-main.git
  minecraft:the_nether: https://git.example/team/castle-nether.git
  minecraft:the_end: https://git.example/team/castle-end.git
```

`wgit remote add origin manifest+file:///path/world.yml` 或 `manifest+https://example/world.yml` 讀取清單並把展開結果保存到本機 remotes.yml，之後不隱式重新抓清單。清單最多 64 KiB／32 維度；clone 需要包含 world-meta 的主世界 repo。manifest 取得目前限公開 URL，不帶 PAT；repo 傳輸各自按 origin 解析憑證。URL 禁止 userinfo/query/fragment，SSH URL 尚未支援。

憑證優先 `WGIT_TOKEN`（WGIT_AUTH=basic/bearer、WGIT_USERNAME 預設 token）→ `~/.config/worldgit/credentials.yml`（或 WGIT_CREDENTIALS_FILE；普通檔案且 POSIX 600）→ 平台 Provider → anonymous 空密碼 Basic。使用者檔格式：

```yaml
credentials:
  https://hub.example.com:
    mode: bearer
    token: YOUR_PAT
```

token 不寫 remotes.yml／git config／trees，不接受 URL 內秘密，禁止 HTTP redirect 轉送 Authorization；錯誤遮罩原 token、Authorization 與 URL userinfo，parser 不附秘密原文。平台自行保管設定及權限；不要把 credentials 檔放進世界 datapacks。公開 Hub clone 用明確 anonymous Basic，與既有 GitAuthFilter 一致。

伺服器流程是：背景 executor `WorldRemotes.fetch` → `trackingHeads` → 在既有 live coordinator 取得 dry-run `WorldOperations.pull` 預覽 → 玩家/管理員明確執行套用 → 再次鎖編輯、flush、檢查 expectedHeads、applyAll/verify/HEAD barrier。fetch 自己完全不開 session.lock、不套用世界；preview/套用由 caller 以 `WorldOperations.live` 完成，不可對活世界建立離線 WorldOperations。遠端通知與 preview 不得自動觸發 apply。Paper/Folia 與 Fabric 單人／專用伺服器已依本節契約接線。Hub 的 PR／帳號／受保護分支 HTTP 層已完成，見下節。

跨維度 PARTIAL、安全重試、有界 packs、clone/export、裸合併與真平台驗收詳見 [14](14-phase4-progress.md)。分批 protocol 會產生多次 HTTP 認證。Phase 4 Hub 已把成功 PAT 與失敗認證分開：成功不消耗失敗額度，另限每 IP／使用者 6000 次/60 秒；錯誤憑證仍 30 次/60 秒、5 次失敗鎖 300 秒。一次多維度傳輸使用預設即可完成，不需提高 attempts；真正超額仍回 429，client 持久化 PARTIAL 供安全重試。

## 7. Phase 4 Hub 協作（2026-10-03）

本機帳號與三種可選 OAuth、組織／團隊、個人／團隊世界授權、PAT scope、受保護分支、PR／審核／3D 衝突選擇、座標留言、release ZIP、通知與 webhook 已實作。API／YAML 範例見 [Hub README](../hub/README.md)，安全邊界見 [Phase 4 安全審查](../hub/docs/security-review-phase4-2026-10-03.md)，實測與限制見 [14 Hub](14-phase4-progress.md#hub)。

受保護分支由管理者自行設定（預設沒有規則），可設 `main` PR-only／需要審核。合併使用 core 裸 repo API、所有維度共享 snapshot，維持 #46 快照 state，不執行鄰居更新。PR／release 完整性檢查拒絕缺少維度或 group；PR 額外檢查 publication 與全部分支 tips 一致。普通歷史／compare reader 仍維持既有行為。

ZIP 固定建立 release 時的 tag commit map；逐 region 暫存後串流，預設 512 MiB／300 秒／2 個並行，不保存 ZIP 快取，私人世界每次下載重新授權。downloads.limits 解析額度獨立且有限，預設輸入／解壓各 512 MiB、2000 萬 nodes；超額或磁碟錯誤中止，暫存與下載許可必定釋放。單 region 套用與阻塞的客戶端輸出沒有硬截止期限，反向代理仍須配置 idle/write timeout。實際持有 owner 鎖直到串流完成，同 owner 推送／合併期間下載可回 503。

給 Paper／Fabric：以 PAT 呼叫 `POST/GET …/pulls`、`GET …/pulls/{id}`、`GET …/comments?pinned=true&dimension=…`、`GET …/releases`；REST 路徑、分頁與 JSON 範例見 README。webhook payload 有 `world` 與 `data.target`／`data.commits`（PR merged），或 `data.dimension/ref/old/new`（Git push）；Git push 是逐維度事件，尚不代表全世界發布完成。接收方驗 `X-WorldGit-Signature-256`、按 `X-WorldGit-Delivery` 去重，背景 fetch 驗證 publication 後才提示「main 有新版本」，玩家明確 `/wg pull` 再走 live coordinator。Paper/Folia 與 Fabric 遊戲內指令與通知接線已完成（§5），各端皆禁止通知自動套用。
