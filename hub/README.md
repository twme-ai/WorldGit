# WorldGit Hub

Phase 1／2 的 Hub：Spring Boot（Java 25）後端 + TypeScript/Vite 前端，單一 jar／單一容器。提供

- **Git smart HTTP**（JGit `GitServlet`）：`git clone/push` 一個「世界」的各維度 repo，路徑 `/git/{owner}/{world}/{維度目錄}.git`，例如 `/git/alice/castle/minecraft.overworld.git`。push 需要 token（Basic：任意使用者名 + token 當密碼，或 `Authorization: Bearer`）；公開世界允許以 `anonymous`／空密碼 clone（例如 `git clone https://anonymous:@hub.example.org/git/alice/castle/minecraft.overworld.git`）。未帶認證時所有有效 repo 路徑先回相同的 401 challenge，認證後無權與不存在皆回 404，避免列舉私人世界；這會讓沒有明確匿名帳密的公開 clone 出現帳密提示。
- **世界 = 一組維度 repo**：同一次存檔（`WorldGit-Snapshot` trailer 相同）在網頁上合併成一列；宣告的維度 repo 尚未推送或 push 被拒時標示「部分推送」。
- **網頁**：世界首頁（維度分頁、俯視 tile 地圖、變動 chunk 疊圖、clone/push 指令）、commit 列表（auto commit 折疊）、單一 commit 的 3D 檢視與 diff 上色（新增綠、移除紅鬼影、修改黃、衝突紫預留；一般／色盲色票）。
- **分支／比較**：世界分支頁、分支下拉與分支歷史；兩個任意 commit／分支的 a→b 統計、chunk／section 清單與 3D（上色疊圖、只看變動及周圍一格、前／後切換）。
- **REST API**（`/api/v1`）：見下表。

## 快速開始

```sh
# 本機（需要 JDK 25 與 Node 22+）
export GRADLE_USER_HOME=$PWD/.work/gradle-home
./gradlew --configure-on-demand :hub:webBuild :hub:bootJar
WORLDGIT_HUB_DATA_DIR=./hub-data WORLDGIT_HUB_BOOTSTRAP_ADMIN_TOKEN=dev-token \
  java -jar hub/build/libs/worldgit-hub.jar --server.address=127.0.0.1 --server.port=8091

# 推送世界（wgit 先 init／commit；對每個維度 repo）
cd server/.worldgit/world/minecraft.overworld
git push http://admin:dev-token@127.0.0.1:8091/git/admin/castle/minecraft.overworld.git main
```

瀏覽器開 `http://127.0.0.1:8091/`，以 `admin`／`WORLDGIT_HUB_BOOTSTRAP_ADMIN_PASSWORD` 登入（密碼留空則隨機產生並印在 log）。首次開 commit 檢視時，Hub 會依該 commit 的 DataVersion 從 Mojang 下載對應版本的 client jar（SHA-1 驗證）並產生貼圖集／模型資料，之後快取在 `/data`；離線環境可把已解開的 client jar 放在目錄並設 `worldgit.hub.assets.source-dir`。

## 容器（Docker／Podman 通用）

```sh
podman build --format docker -f hub/Containerfile -t localhost/worldgit-hub:latest .   # context 必須是 repo 根目錄；docker 不需 --format；podman 預設 OCI 格式會丟掉映像的 HEALTHCHECK
WORLDGIT_ADMIN_PASSWORD_FILE=/安全路徑/admin-password WORLDGIT_ADMIN_TOKEN_FILE=/安全路徑/admin-token podman compose -f hub/compose.yaml up -d --build   # docker compose 同
hub/scripts/container-smoke.sh                                            # 一鍵冒煙測試：建置 → 啟動 → push → API 讀回 → 清理
```

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
| `worldgit.hub.auth.*` | `WORLDGIT_HUB_AUTH_*` | 每 IP／帳號／兩者組合 60 秒最多 30 次認證，失敗 5 次鎖 300 秒，狀態最多 10,000 個鍵；有效 Bearer 不扣嘗試，失敗才扣 |
| `worldgit.hub.tokens.pat-days` | `WORLDGIT_HUB_TOKENS_PAT_DAYS` | 新 PAT 預設 90 天，可指定 `expiresAt` ISO-8601（最長十年）；既有與 bootstrap token 的期限維持既有政策 |
| `worldgit.hub.git.pack-limit-bytes` | — | push 後非同步 repack 的 pack 上限（預設 95,000,000，決定 #17） |
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
| `POST /auth/login`、`GET /me`、`/tokens`（GET/POST/DELETE）、`POST /users`、`POST /orgs` | 本機帳號與 token（Phase 4 再加 OAuth 與細部權限；資料模型已有 owners／users／memberships／tokens） |
| `GET/POST /worlds`、`GET /worlds/{owner}/{world}`、`/snapshots`、`/pushes` | 世界、依 snapshot 合併的歷史、push 紀錄 |
| `GET …/branches?base=分支` | 跨維度同名分支、預設分支、各維度 head／作者／snapshot、一致性；相對基準的 ahead／behind（以可達 snapshot 集合計算） |
| `GET …/snapshots?branch=分支` | 指定分支的存檔歷史；未指定讀世界的預設分支 |
| `GET …/compare?a=起點&b=終點` | 任意分支／HEAD／唯一 commit 前綴的 a→b 統計，依維度回傳 chunk／section 清單、截斷旗標；沒有配對端點的維度排除統計 |
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
WORLDGIT_TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5432/hub WORLDGIT_TEST_POSTGRES_PASSWORD=… ./gradlew --configure-on-demand :hub:test
cd hub/web && npm ci && npm test && npm run build   # 前端 vitest（wire/LOD）＋ tsc ＋ vite build
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

## 已知限制（Phase 1／2）

- 遠景為「伺服器高度圖階梯 LOD」的簡化版，**尚未嵌入 BlueMap core**；近景用 deepslate 模型層 + 自寫 greedy 網格。
- 沒有 AO、告示牌文字／頭顱皮膚；實體只畫線框。
- 初始 commit 的統計只列 chunk 數，不逐格計數。
- 權限僅 owner／reader／writer 基本檢查，沒有 OAuth、S3。配額與認證限流為單 Hub 實例的本機儲存防護；多實例部署需共用配額／限流狀態。

Phase 2 截圖／CSP／互動驗收（使用 18097，取得 `.work/bench.lock`，結束自動關閉 Hub 與瀏覽器）：

```sh
hub/scripts/phase2-acceptance.sh
```

此腳本建立數十 KiB 的三維度固定場景，只向本機暫存 Hub 推送測試 repo；正式專案不 commit／push。可重用 `.work/hub/run2/data/cache/assets/26.2` 資源快取；沒有快取時由既有官方資源管線建立。證據在 `.work/hub-phase2-h/`、精選四張截圖在 `hub/docs/screenshots/phase2/`。並排、分割滑桿、時間軸尚未實作；前／後切換會重建檢視器與載入該版本串流，鏡頭保留。
