# WorldGit Hub

Phase 5 任務 1 保留 Hub 的既有 URL 與介面，core／CLI 已採每維度獨立歷史，不再要求全組 snapshot／publication。不同維度分支的網頁比較、PR／release 與權限模型由任務 2 更新；目前下文跨維度功能是 Phase 4 介面，不能把一次單維度 push 視為世界整組發布。單維度 `BareWorldMerge`、共用 graph lane、progress／result／錯誤報告與組裝介面見 [Phase 5 設計](../docs/16-phase5-design.md)。

Phase 1–4 的 Hub：Spring Boot（Java 25）後端 + TypeScript/Vite 前端，單一 jar／單一容器。提供

- **Git smart HTTP**（JGit `GitServlet`）：`git clone/push` 一個「世界」的各維度 repo，路徑 `/git/{owner}/{world}/{維度目錄}.git`，例如 `/git/alice/castle/minecraft.overworld.git`。push 需要 token（Basic：任意使用者名 + token 當密碼，或 `Authorization: Bearer`）；公開世界允許以 `anonymous`／空密碼 clone（例如 `git clone https://anonymous:@hub.example.org/git/alice/castle/minecraft.overworld.git`）。未帶認證時所有有效 repo 路徑先回相同的 401 challenge，認證後無權與不存在皆回 404，避免列舉私人世界；這會讓沒有明確匿名帳密的公開 clone 出現帳密提示。
- **世界 = 一組維度 repo**：同一次存檔（`WorldGit-Snapshot` trailer 相同）在網頁上合併成一列；宣告的維度 repo 尚未推送或 push 被拒時標示「部分推送」。
- **網頁**：世界首頁（維度分頁、俯視 tile 地圖、變動 chunk 疊圖、clone/push 指令）、commit 列表（auto commit 折疊）、單一 commit 的 3D 檢視與 diff 上色（新增綠、移除紅鬼影、修改黃、衝突紫預留；一般／色盲色票）。
- **分支／比較**：世界分支頁、分支下拉與分支歷史；兩個任意 commit／分支的 a→b 統計、chunk／section 清單與 3D（上色疊圖、只看變動及周圍一格、前／後切換）。
- **協作**：本機帳號＋可選 GitHub／Discord／Microsoft OAuth、owner/admin/write/read、PAT scope、組織與團隊授權；受保護分支、PR／審核／持久衝突選擇、3D 座標釘選留言、通知、release ZIP 與 HMAC webhook。
- **REST API**（`/api/v1`）：見下表與 Phase 4 契約。

## 快速開始

```sh
# 本機（需要 JDK 25 與 Node 22+）
export GRADLE_USER_HOME=$PWD/.work/gradle-home
./gradlew --configure-on-demand :hub:webBuild :hub:bootJar
WORLDGIT_HUB_DATA_DIR=./hub-data WORLDGIT_HUB_BOOTSTRAP_ADMIN_TOKEN=dev-token \
  java -jar hub/build/libs/worldgit-hub.jar --server.address=127.0.0.1 --server.port=8091

# 推送世界（wgit 先 init／commit；對每個維度 repo）
cd server/world/.worldgit
git push http://admin:dev-token@127.0.0.1:8091/git/admin/castle/minecraft.overworld.git main
```

瀏覽器開 `http://127.0.0.1:8091/`，以 `admin`／`WORLDGIT_HUB_BOOTSTRAP_ADMIN_PASSWORD` 登入（密碼留空則隨機產生並印在 log）。首次開 commit 檢視時，Hub 會依該 commit 的 DataVersion 從 Mojang 下載對應版本的 client jar（SHA-1 驗證）並產生貼圖集／模型資料，之後快取在 `/data`；離線環境可把已解開的 client jar 放在目錄並設 `worldgit.hub.assets.source-dir`。

## 容器（Docker／Podman 通用）

```sh
podman build --format docker -f hub/Containerfile -t localhost/worldgit-hub:latest .   # context 必須是 repo 根目錄；docker 不需 --format；podman 預設 OCI 格式會丟掉映像的 HEALTHCHECK
WORLDGIT_ADMIN_PASSWORD_FILE=/安全路徑/admin-password WORLDGIT_ADMIN_TOKEN_FILE=/安全路徑/admin-token podman compose -f hub/compose.yaml up -d --build   # docker compose 同
hub/scripts/container-smoke.sh                                            # 一鍵冒煙測試：建置 → 啟動 → push → API 讀回 → 清理
```

Phase 4 最新映像建置與冒煙（SQLite、`DB=postgres`）已於 2026-10-03 以 Podman 驗證通過。

- 多階段建置（Node 建前端 → Gradle 建 jar → `eclipse-temurin:25-jre`），非 root（uid 10001），資料集中在 `/data`（SQLite、世界 repo、資源快取），埠 8080，HEALTHCHECK 走 `/actuator/health/liveness`。
- `compose.yaml` 的兩個 secret 由宿主檔案提供，未指定固定 token 時提供空 token 檔。podman-compose 1.0.6 實測是把檔案原樣 bind mount，容器 uid 10001 必須讀得到：用 `chown 10001:10001 <檔案> && chmod 400 <檔案>`（rootless 用 `podman unshare chown`）；root 擁有的 600 檔案會讓 Hub 啟動失敗（錯誤訊息「無法讀取 bootstrap secret 檔案」，不會靜默退回隨機密碼）。Quadlet 以 Podman secret 掛載為檔案（type=mount）並用 `*_FILE` 讀取，不再以環境變數傳入密碼。
- `compose.yaml` 預設只綁 `127.0.0.1:8091`；對外請放在反向代理（TLS）後面。
- Podman Quadlet（systemd）範例在 `hub/deploy/`（`worldgit-hub.container`、`worldgit-hub.volume`）；2026-10-01 已在 Podman 4.9.3 的 rootful 與 rootless 實機驗證（見 docs/11）。`[Service]` 的 `SuccessExitStatus=143` 是實測後加的（JVM 收 SIGTERM 回 143，否則 `systemctl stop` 後 unit 顯示 failed）。
- 冒煙測試支援 `DB=postgres`（另起 postgres 容器）；流程含 healthcheck 轉 healthy、密碼登入（證明 `*_FILE` 被讀到）、push、clone、重啟後資料仍在。CI 的 `hub-image` job 以 docker 跑 SQLite 與 PostgreSQL 兩輪。
- 目前只有 amd64 實測；arm64 需用 `--platform linux/arm64` 在 arm64 主機（或 qemu）建置，基底映像皆有 arm64，未實測。

## 設定（`application.yml`，環境變數可覆寫）

| 設定 | 環境變數 | 說明 |
|---|---|---|
| `worldgit.hub.data-dir` | `WORLDGIT_HUB_DATA_DIR` | 資料根目錄（容器內 `/data`） |
| `worldgit.hub.bootstrap.admin-token` / `admin-password` | `WORLDGIT_HUB_BOOTSTRAP_ADMIN_TOKEN` / `_PASSWORD` | 首次啟動建立管理員；token 非空則固定該 token |
| `worldgit.hub.auto-create-worlds` | `WORLDGIT_HUB_AUTO_CREATE_WORLDS` | push 到不存在的世界時自動建立（私人） |
| `worldgit.hub.bootstrap.admin-password-file` / `admin-token-file` | `WORLDGIT_HUB_BOOTSTRAP_ADMIN_PASSWORD_FILE` / `_TOKEN_FILE` | 讀取 secrets 檔案（最多 4096 bytes，移除結尾換行）；Compose 使用 `/run/secrets/`，檔案設定優先於明文值 |
| `worldgit.hub.security.hsts` | `WORLDGIT_HUB_SECURITY_HSTS` | 預設 false；僅在 TLS 反向代理後設 true，回 `max-age=31536000`，未涵蓋子網域 |
| `worldgit.hub.security.trusted-proxies` | `WORLDGIT_HUB_SECURITY_TRUSTED_PROXIES` | 預設空；只列可信代理的精確 IP（逗號分隔），代理必須覆寫 X-Forwarded-For；由右往左找第一個不可信 hop |
| `worldgit.hub.git.max-pack-bytes` | `WORLDGIT_HUB_GIT_MAX_PACK_BYTES` | 收包上限 95,000,000 bytes；增量 push 超過時需分批推送 commit，Git 不會因本機 pack 已分割而自動分批傳輸 |
| `worldgit.hub.git.owner-quota-bytes` | `WORLDGIT_HUB_GIT_OWNER_QUOTA_BYTES` | owner 所有世界／維度的磁碟檔案總和，預設 10 GiB；可配置且必須大於零 |
| `worldgit.hub.limits.*` | `WORLDGIT_HUB_LIMITS_*` | 每次請求／push 共用：解壓 256 MiB、讀入 128 MiB、NBT 100 萬節點、10 萬物件、5000 萬解析工作單位；在配置前檢查，超過回 413／拒絕 push |
| `worldgit.hub.auth.*` | `WORLDGIT_HUB_AUTH_*` | 只計失敗：IP／帳號／組合 30 次/60 秒、5 次鎖 300 秒；成功 Basic/Bearer PAT 不消耗失敗額度、不清除紀錄，另限每 IP／使用者 6000 次/60 秒；兩種狀態各最多 10,000 鍵 |
| `worldgit.hub.tokens.pat-days` | `WORLDGIT_HUB_TOKENS_PAT_DAYS` | 新 PAT 預設 90 天，可指定 `expiresAt` ISO-8601（最長十年）；既有與 bootstrap token 的期限維持既有政策 |
| `worldgit.hub.git.pack-limit-bytes` | — | push 後非同步 repack 的 pack 上限（預設 95,000,000，決定 #17） |
| `worldgit.hub.collaboration.merge-lock-timeout` | `WORLDGIT_HUB_COLLABORATION_MERGE_LOCK_TIMEOUT` | PR 合併取得 owner 鎖的等待上限，預設 `10s`，可設 `1ms`–`30s`；逾時或中斷回 503（Retry-After: 2），中斷保留 interrupt 旗標（決定 #105） |
| `worldgit.hub.assets.source-dir` | `WORLDGIT_HUB_ASSETS_SOURCE_DIR` | 已解開的 client jar 目錄；空白＝自 Mojang 下載 |
| `spring.datasource.*` | `SPRING_DATASOURCE_URL` 等 | 預設 SQLite；改 `jdbc:postgresql://…` 並設 `SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver` 切換 PostgreSQL（driver 已內建；SQLite 的 pragma 已改放 URL 參數，所以切換只需改 URL、driver、帳密；2026-10-01 以 PostgreSQL 16 跑過完整 Hub 測試與容器冒煙） |
| `server.address` / `server.port` | `SERVER_ADDRESS` / `SERVER_PORT` | jar 預設 `0.0.0.0:8080`（容器用）；本機請用 `--server.address=127.0.0.1` |

## 安全邊界

所有 HTTP 回應（靜態檔、API、Git、actuator 與錯誤頁）有 CSP、nosniff、DENY、Referrer-Policy 與 Permissions-Policy。CSP 限制腳本、模組 Worker、API、貼圖與字型為同源；favicon 允許 data URI。前端動態樣式屬性用 `style-src-attr 'unsafe-inline'`，樣式表仍限制同源；WebGL／deepslate 無需 eval、inline script、blob Worker 或外部 CDN。

配額在收包前限制剩餘容量，pre-receive 重新計入 pack／index，再決定是否更新 ref；拒絕且未更新 ref 的新 pack 會清理。單 owner push、刪除世界及 repack 共用鎖。收包期間仍可能短暫超過配額一個受限 pack 與 index，請保留磁碟餘裕；此配額不計共用資源／tile 快取。多實例不能直接共享目前的本機鎖與限流。

commit 物件上限 1 MiB、每個 commit 最多 1024 個 trailer／100000 個 contribution chunk（重複項仍計數）；每次 push 驗證全部新增歷史，最多 10000 個 commit。解碼預算涵蓋 chunks、diff、entities、tile、統計與歷史解析，與核心既有單 blob／NBT 限制並用。

## API 摘要

| 路徑 | 說明 |
|---|---|
| `POST /auth/login`、`GET /me`、`/tokens`（GET/POST/DELETE）、`POST /users`、`POST /orgs` | 本機帳號、HttpOnly session 與 PAT（scope、expiresAt、lastUsedAt） |
| `GET/POST /worlds`、`GET /worlds/{owner}/{world}`、`/snapshots`、`/pushes` | 世界、依 snapshot 合併的歷史、push 紀錄 |
| `GET …/branches?base=分支` | 跨維度同名分支、預設分支、各維度 head／作者／snapshot、一致性；相對基準的 ahead／behind（以可達 snapshot 集合計算） |
| `GET …/snapshots?branch=分支` | 指定分支的存檔歷史；未指定讀世界的預設分支 |
| `GET …/compare?a=起點&b=終點` | 任意分支／HEAD／唯一 commit 前綴的 a→b 統計，依維度回傳 chunk／section 清單、截斷旗標；沒有配對端點的維度排除統計 |
| `GET …/merge-preview?ours=&theirs=`、`…/merge-preview/view/{chunks,diff,summary}` | 唯讀合併預覽（Phase 3）：core MergeEngine 在記憶體計算，回傳可否零介入合併、衝突區域、規則差異與前提衝突原因；`view` 依 `choices` 回傳 ours／theirs／base／選擇結果。不寫 repo，有 DecodeBudget、記憶體快取上限（8 筆／32 MiB）。網頁：`/{owner}/{world}/merge-preview/ours...theirs`；PR 頁會保存選擇並可產生 merge commit |
| `GET …/dims/{維度目錄}/commits/{rev}` | commit 詳情：+/-/~ 統計、變動 chunk、實體變動 |
| `…/commits/{rev}/chunks?x0&z0&x1&z1` | 方塊資料串流（WGCK 二進位；格式見 `data/ChunkWire.java`） |
| `…/commits/{rev}/diff?base&x0…` | diff（WGDF 二進位） |
| `…/commits/{rev}/entities?base=commit&plain=true`、`/tiles`、`/tiles/{rx}/{rz}.png`、`.height` | 實體、伺服器預先計算的俯視 tile 與高度圖（內容定址快取） |
| `GET /diff-palettes`、`/assets/{version}/{file}` | 色票（protocol.DiffPalette）、資源包 |

`rev` 可為 `HEAD` 或 commit 前綴。回應多為內容雜湊，設有長期快取。

比較頁網址為 `/{owner}/{world}/compare/<a>...<b>`，方向為 a→b；鏡頭 `cam`、維度 `dim`、呈現 `view=color|changed|before|after` 可分享與重載。含 `/` 的分支在網址保留斜線，例如 `compare/main...build/castle`。`base` 的 3D diff／entities 查詢使用解析後的 commit id；`plain=true` 只回該版本實體，不附上一版的差異。

分支代表各維度的目前 head；commit 代表該 commit 及其他維度**同 snapshot UUID**的 commit。不依時間猜測沒有變動維度的 head。缺少端點會明示且不計入總計；用分支可比較各維度目前狀態。分支 `consistent` 表示宣告／實際維度都有分支，`aligned` 表示所有 head 同一 snapshot；兩者分開呈現。

比較回應全維度最多 2000 個 chunk／6000 個 section（座標序），統計與 bounds 保持完整；每維度實體樣本 200、metadata 50。`chunksTruncated`／`sectionsTruncated` 表示省略清單；前端各顯示最多 300 列。每世界最多 32 維度／500 分支；ahead／behind 每 tip／維度最多走 20,000 個 commit，`countsTruncated` 時視為估算。JSON 上限 4 MiB、3D wire 上限 16 MiB；視窗仍最多 1024 chunk、DecodeBudget 限制照常生效。超額回 413；branches／compare 回應 `no-store`，權限檢查先於讀取／快取。compare 不建立磁碟快取，避免任意 commit 配對累積空間。

## 開發與測試

```sh
./gradlew --configure-on-demand :hub:test          # 後端（NameRules、端到端 push → API）約 10–60 秒
# 選用：整個後端測試套件改跑 PostgreSQL（預設不設＝SQLite）
set -a
. .work/pg-test.env  # 私有測試連線環境檔（600）；不要印出秘密
set +a
./gradlew --no-daemon --configure-on-demand --max-workers=1 :hub:test --rerun-tasks
cd hub/web && npm ci && npm run lint && npm test && npm run build   # 前端 vitest（wire/LOD）＋ tsc ＋ vite build
cd hub/web && npm run dev                           # Vite dev server（127.0.0.1:5191，proxy 到 Hub 8091）
```

截圖／載入量測：`hub/web/scripts/screenshots.mjs`（Playwright，系統 Chrome + SwiftShader；重負載請用 `flock`）。驗收截圖在 `hub/docs/screenshots/`。

## 結構

```
src/main/java/org/worldgit/hub/
  config/ account/ storage/   設定、帳號與多租戶資料模型（SQLite/PostgreSQL）、儲存層介面（本機磁碟；S3 預留）
  git/                        GitServlet、驗證 filter、pre-receive 驗證 WorldGit trailers、push 後 bounded repack
  history/ data/              snapshot 合併、commit 詳情、chunk/diff 二進位、俯視 tile（呼叫 core）
  assets/                     資源管線（client jar → 貼圖集、blockstates、models、biomes、mapcolors）
  web/                        REST controllers、SPA 轉發
web/src/                      TypeScript/Vite 前端：pages/、map/（2D tile 地圖）、viewer/（worker 網格、LOD、diff shader）
```

## 已知限制

- 遠景為「伺服器高度圖階梯 LOD」的簡化版，**尚未嵌入 BlueMap core**；近景用 deepslate 模型層 + 自寫 greedy 網格。
- 沒有 AO、告示牌文字／頭顱皮膚；實體只畫線框。
- 初始 commit 的統計只列 chunk 數，不逐格計數。
- 儲存只支援本機磁碟，S3 未接。配額、鎖、session、認證限流與下載許可為單 Hub 實例；多實例部署需共用這些狀態。

Phase 2 截圖／CSP／互動驗收（使用 18097，取得 `.work/bench.lock`，結束自動關閉 Hub 與瀏覽器）：

```sh
hub/scripts/phase2-acceptance.sh
```

此腳本建立數十 KiB 的三維度固定場景，只向本機暫存 Hub 推送測試 repo；正式專案不 commit／push。可重用 `.work/hub/run2/data/cache/assets/26.2` 資源快取；沒有快取時由既有官方資源管線建立。證據在 `.work/hub-phase2-h/`、精選四張截圖在 `hub/docs/screenshots/phase2/`。並排、分割滑桿、時間軸尚未實作；前／後切換會重建檢視器與載入該版本串流，鏡頭保留。

## Phase 4 設定與登入

本機帳號可由全站管理員建立。瀏覽器登入使用 JSESSIONID（HttpOnly、SameSite=Lax、30 分鐘、登入換 id），呼叫 `GET /api/v1/me` 取得 XSRF-TOKEN cookie，寫入送 `X-XSRF-TOKEN` header；前端不保存 token 到 localStorage。`POST /auth/login` 回應保留 14 天的相容 session bearer，但網頁忽略它；Git smart HTTP 拒絕 cookie 與此 SESSION token，CLI／遊戲端應建立 PAT，秘密只顯示一次且 DB 只存 SHA-256。TLS 部署須設 `server.servlet.session.cookie.secure: true`，並配置下例的真實 public-url；轉送 Host 由代理覆寫，可信代理 IP 設定只用於來源 IP，不自動信任任意 forwarded headers。

```yaml
worldgit:
  hub:
    auth:
      successful-requests: 6000
    collaboration:
      merge-lock-timeout: 10s
      registration:
        enabled: false
        public-url: https://hub.example.org
        from: worldgit@example.org
      oauth:
        github:
          enabled: true
          client-id: ${GITHUB_CLIENT_ID}
          client-secret: ${GITHUB_CLIENT_SECRET}
        discord:
          enabled: false
        microsoft:
          enabled: false
      downloads:
        max-bytes: 536870912
        max-seconds: 300
        concurrency: 2
        limits:
          decoded-bytes: 536870912
          read-bytes: 536870912
          nodes: 20000000
          objects: 1000000
          work: 200000000
      webhooks:
        allowed-hosts: []
        attempts: 5
        retry-seconds: 30
server:
  servlet:
    session:
      cookie:
        secure: true
```

第三方回呼 URL 為 `https://hub.example.org/login/oauth2/code/{github|discord|microsoft}`。authorization code 採 state＋S256 PKCE；啟用 provider 的帳號先登入本機帳號，再於設定頁連結，之後可直接 OAuth 登入。未知第三方 subject 不自動註冊，也不根據 email 連結已有帳號。端點 `authorization-uri`／`token-uri`／`user-info-uri`／`user-name-attribute`／`scopes` 可覆寫供本機 mock；測試不連真第三方。全部 OAuth 可關閉，自助註冊另獨立開關。

啟用 registration 須設定 `spring.mail.host` 等 SMTP，否則回 503；密碼 12–72 UTF-8 bytes、驗證碼 1 小時、一個 token 只能兌換一次，驗證後才建立使用者。每 IP 最多 3 次/小時、待驗證註冊最多 10000；重複名稱／email 回相同訊息。完整的公開服務治理與 bootstrap 政策見 [docs/07 §4.2](../docs/07-remote-hub.md#42-上線前必做worldgitorg決定-23)。

## Phase 4 REST 契約（給 CLI／Paper／Fabric）

以下路徑都以 `/api/v1` 起頭，`…` 代表 `/worlds/{owner}/{world}`。建議 `Authorization: Bearer <PAT>`；Basic 的密碼欄也接受 PAT。PAT scope read→write→admin 逐層包含，但仍取決於使用者世界角色。全站 admin 保有管理權，受保護分支沒有 owner/admin bypass。舊 token scope 升級為 admin，原期限維持；新 PAT 預設 90 天，回傳最後使用時間。

| 操作 | 路徑／body | 世界角色；PAT scope |
|---|---|---|
| PR 建立／列表 | `POST …/pulls`：source,target,title,description；`GET …/pulls?status=open`（open/merged/closed，可省略） | write；write／列表 read；read |
| PR 詳情 | `GET …/pulls/{id}`：pr,preview,choices,reviews,mergeability,approvals,requiredReviews,commits,commitsTruncated | read；read |
| PR 編輯／關閉 | `PATCH …/pulls/{id}`：title,description,status（open/closed；僅仍 open 的 PR） | 作者或 admin；write |
| 區域選擇 | `PUT …/pulls/{id}/choices`：fingerprint,choices（region id→ours/theirs/base/manual） | write；write |
| 審核 | `POST …/pulls/{id}/reviews`：fingerprint,decision（approve/request-changes）；作者不可自審 | write；write |
| 合併 | `POST …/pulls/{id}/merge`：fingerprint | write；write |
| 留言／回覆 | `POST …/pulls/{id}/comments`：body,parentId（選填）,pin（選填）；`PATCH/DELETE …/comments/{id}` | 可讀的登入者；write；編刪限作者或 admin |
| 遊戲座標留言 | `GET …/comments?pr={id}&pinned=true&dimension=minecraft:overworld`，各 filter 選填 | read；read |
| release | `GET/POST …/releases`（建立：tag,title,body）、`GET …/releases/{id}`、`GET …/releases/{id}/zip` | 建立 write；write，其餘 read；read |
| 世界授權 | `GET …/permissions`；`PUT …/permissions/users/{username}`／`…/permissions/teams/{team}`：role（read/write/admin/none） | admin；admin |
| 可見性／分支保護 | `PUT …/visibility`：isPublic；`GET/PUT …/protected-branches`：branch,prOnly,reviews；`DELETE …/protected-branches?branch=main` | 讀保護 read；read，其餘 admin；admin |
| webhook | `GET/POST …/webhooks`、`PUT/DELETE …/webhooks/{id}`；`GET …/webhooks/{id}/deliveries` | admin；admin |
| 組織／團隊 | `POST/GET /orgs`；`GET /orgs/{org}/members`；`PUT /orgs/{org}/members/{username}`：role；`GET/POST /orgs/{org}/teams`（建立：slug）；`GET /orgs/{org}/teams/{team}/members`；`PUT/DELETE …/members/{username}` | 成員列表需 org read，團隊管理需 org admin，組織成員管理需 owner；寫入 admin scope |
| OAuth／註冊 | `GET /auth/options`、`GET /auth/identities`；`POST /auth/oauth/{provider}/link`；`DELETE /auth/identities/{provider}`；`PUT /auth/password`：password；`POST /auth/register`：username,email,password；`POST /auth/verify`：token | 連結／解除／密碼需登入＋admin scope，register/verify 依開關 |
| 通知 | `GET /notifications`；`PUT /notifications/{id}/seen` | 本人；read／標已讀 write |

PR／留言／release／webhook／投遞／通知列表回 `{items,offset,limit,hasMore}`；offset 預設 0、最多 10000，limit 預設 50、1–100，下一頁 offset+=limit。組織／團隊／保護規則列表目前是陣列。錯誤一致為 `{code,error}`：400 格式／參數或最後 owner 不變量、401 無效／缺憑證、403 可讀但操作不足、404 私人不可讀或不存在（子資源都以 world id 限定）、409 lease／審核／狀態競爭、413 解析／下載預算、429 rate-limit（Retry-After）、503 併發或暫時忙碌（Retry-After: 2）。JSON 寫入 1 MiB、協作回應 4 MiB，文字只按 text 輸出；協作回應 private,no-store。

```json
{"source":"build/roof","target":"main","title":"屋頂修改","description":"加高兩格"}
```

建立 PR 後讀詳情取得 `pr.id` 與 `pr.fingerprint`；送 choices/reviews/merge 均必須使用這個 fingerprint。`mergeability` 為 ff/clean/conflicts/needs-review/changes-requested/unmergeable/merged/closed；任何維度 tip 改變使舊選擇與審核作廢，回 409 或詳情 `selectionsInvalidated: true`。選擇改變也清除審核。合併回傳 `status: merged`、snapshot 與全維度 commits；全部維度走 core publication，PR 與 receive-pack 共用 owner 鎖，發布後 DB finalize 中斷可 reconcile。只有 merge commit（FF 也建立整合提交），fork／squash／rebase 未提供。

push 後 `Maintenance.afterPush` 會非同步持有同一把 owner 鎖檢查 repack 並呼叫 `storage.afterWrite`。PR 合併最多等候 `merge-lock-timeout`，讓短暫維護完成後繼續；取得鎖後重新檢查權限、PR 狀態與 fingerprint，逾時或中斷回 503。此設定只限制取得鎖的等待時間，合併本身的執行時間另計。ZIP 下載仍在下載許可或 owner 鎖忙時立即回 503；git receive-pack（含 info/refs）仍立即回 429，兩者都帶 Retry-After: 2。兩者在完整下載／收包期間持鎖，慢速 socket 沒有硬 deadline；維持立即拒絕可避免請求執行緒與下載許可排隊等候慢速串流（決定 #105）。

```json
{"body":"屋頂請加高","pin":{"dimension":"minecraft:overworld","x":3,"y":65,"z":3,"maxX":8,"maxY":67,"maxZ":8}}
```

pin 可省略，範圍三個 max 欄位須一起提供；刪除留言保留 tombstone 與回覆串。每 PR 最多 10000 則留言、每世界最多 500 個 open PR；來源 commit 列表最多 200 筆並標 commitsTruncated。

release 由 tag 全組建立且固定各維度 commit id；ZIP 不帶 .worldgit、玩家與 session.lock，還原 seed/worldgen/資料包並保留空維度目錄，避免 Paper 把終界當新世界重建 DragonFight。逐 region 的 Anvil 暫存＋ZIP 串流，預設暫存／輸出各 512 MiB、300 秒、全站 2 個下載；沒有 ZIP 磁碟快取。下載解析獨立設定於 downloads.limits，預設解壓／讀入各 512 MiB、2000 萬 nodes、100 萬 objects、2 億 work；不以普通 JSON 預算誤擋完整世界。過額在串流前回 JSON 413，已送出時中止串流；client 需檢查 ZIP 完整性。暫存與許可最後釋放；同 owner 寫入忙時回 503。單 region 或 socket 阻塞無硬 deadline，代理需有限 timeout。

## Webhook 接收契約

設定 body：`url,secret,events,enabled`，secret 32–256 字元，events 為 push/pr.opened/pr.merged/release，最多 10 hooks/世界；API 不回傳 secret。修改 secret=null 表示沿用。預設 HTTPS＋全部 DNS 地址都是公網；loopback／內網／link-local／特殊 IP／IPv6 隧道拒絕，禁止 redirect，socket 固定本次驗證地址。自架內網需精確 `collaboration.webhooks.allowed-hosts`，沒有萬用字元。secret 目前明文存 DB 以簽章，須保護 DB／備份與磁碟存取。

```json
{"id":"event-uuid","event":"pr.merged","at":1791025200000,"world":{"owner":"alice","name":"castle"},"data":{"pr":"pr-uuid","number":1,"target":"main","snapshot":"snapshot-uuid","commits":{"minecraft:overworld":"40-hex-commit"}}}
```

`X-WorldGit-Signature-256: sha256=<hex>` 是以 secret 對**原始 UTF-8 JSON body**計 HMAC-SHA256。接收方用 constant-time 比較，不能先重排 JSON。`X-WorldGit-Delivery` 為穩定投遞 UUID，`X-WorldGit-Attempt` 從 1 開始；同 id 重試 body 不變。2xx 成功，其餘與網路錯誤重試：預設最多 5 次，30/60/120/240 秒退避（上限 1 小時），狀態 PENDING/PROCESSING/DELIVERED/FAILED；worker 每次最多 10 筆、60 秒 lease，可當機重投，連線 5 秒／socket 10 秒，禁止無界讀 response body。

push payload 的 data 是 `dimension,ref,old,new`，刪 ref 的 new 為全零 SHA；**逐維度 push 不保證完整 publication 已到齊**。遊戲端只通知／背景 fetch，驗證全組 publication 才提示「main 有新版本」，玩家明確 pull 經 live coordinator 才套用。PR 合併會發 pr.merged，沒有經 receive-pack 故不另發 push。投遞與事件沒有自動保留期／dead-letter 管理；公開服務需配置清理政策與容量監控。

## Phase 4 驗收

`hub/scripts/phase4-acceptance.sh` 自持 bench.lock，Hub jar＋SQLite 綁 127.0.0.1:8097，Paper 複本 25691／25692；兩位使用者的真 wgit clone/edit/push→網頁 PR 審核／合併→pull→兩版本 Paper verify，並含 release 下載開世界。程序與大型複本 finally 清理。單獨前端腳本由它產生 600 私有設定檔（用完刪除），CSP 與 JS error 都須 0；Playwright 使用專案瀏覽器快取及系統 Chrome/SwiftShader。驗收主機須有繁中字型；亦可設定 FONTCONFIG_FILE 或提供 .work/fonts/fonts.conf（本次使用專案內 Noto Sans CJK TC，字型不打包到 Hub）。

最後驗收（2026-10-03）：SQLite／PostgreSQL 各 58 項測試通過且無略過，完整 `./gradlew build` 通過；前端 lint／26 項測試／build 通過。兩版 Paper 的 PR 合併後 pull 與 release ZIP 重開皆 verify COMPLETE、已追蹤內容差異 0；26.2 Nether／End 各保留 1 個新生成的 untracked chunk。10 次 Playwright 流程的 CSP／JS error 皆 0。容器映像建置及 SQLite／PostgreSQL 冒煙已以 Podman 驗證通過；主對話另以最終 jar 重跑端到端兩版 PASS。

可攜摘要：[acceptance.json](docs/phase4-security/acceptance.json)；完整原始 logs／results 在 `.work/phase4-hub-e2e-complete/` 與 `.work/phase4-hub-*-complete.log`，9 張精選截圖在 `docs/screenshots/phase4/`。端到端使用 12:22 凍結 jar，後續兩項安全修正由最後兩種資料庫測試與 build 覆蓋；artifact hashes 及詳細界線見 [docs/14 Hub](../docs/14-phase4-progress.md#hub)。程序已結束，未留下背景驗收。安全審查：[security-review-phase4-2026-10-03.md](docs/security-review-phase4-2026-10-03.md)。
