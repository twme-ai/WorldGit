# Hub Phase 4 安全審查（2026-10-03）

## 範圍與方法

依 docs/09 #23 以公開、多租戶網路服務標準檢查 Phase 4 的本機／OAuth/session、PAT scope、組織／團隊／世界授權、受保護分支與合成 refs、PR 合併／座標留言、release ZIP、webhook/outbox。接續 [Phase 1 安全審查](security-review-2026-10-01.md)，本文件記錄最新行為；舊報告的 localStorage／無 cookie CSRF 評估已被本次 session／CSRF 設計取代。

方法是追蹤入口到資料／Git 寫入、SQLite 與 PostgreSQL 測試、mock OAuth provider（不連真第三方）、真 CLI／瀏覽器／Paper 兩版本流程、CSP 事件與 HTML 注入文字測試。依賴掃描依實際 jar 的 BOOT-INF/lib 與 Gradle resolved cache 版本，不只看宣告；OSV 證據在 [phase4-security](phase4-security/)。前輪曾實際嘗試容器驗收，沙盒失敗不算通過；映像建置與冒煙已由主對話在沙盒外以 Podman 驗證通過。

最後 SQLite／PostgreSQL 各 13 suites／58 tests 全數通過、無略過，涵蓋下列安全回歸；完整 build 通過。兩版共 10 次 Playwright 流程的 CSP／JS error 皆 0、6 次 Paper verify 已追蹤內容差異 0。端到端 jar 於 12:22 凍結，後續組織撤權與 webhook response close 修正由 12:43／12:49 完成的資料庫全套測試及 build 覆蓋；不把凍結 jar hash 當作最後建置版本。逐測項、artifact／source hashes 與執行結果見 [acceptance.json](phase4-security/acceptance.json)、[postgres-tests.json](phase4-security/postgres-tests.json)、[sqlite-tests.json](phase4-security/sqlite-tests.json) 與 [e2e-results.json](phase4-security/e2e-results.json)。第四輪保留最新實作，補齊證據與文件，未重跑已成功的驗收。

## 發現與處理

| 項目 | 風險／處理 | 驗證與界線 |
|---|---|---|
| Cookie 登入 CSRF/session fixation | 新增 HttpOnly、SameSite=Lax、30 分鐘 session，登入／OAuth 成功換 id；cookie 寫入需 X-XSRF-TOKEN。登入／註冊／驗證只收 JSON，拒絕跨來源 Origin／Sec-Fetch-Site；不設 CORS。Authorization 認證不回退到 cookie，Git 明確拒絕 SESSION 認證。 | cookie session、缺／偽 CSRF、cross-site Origin 回 403；Git 用 session 回 401。TLS 部署需設 secure cookie、真實 public-url、代理覆寫 Host；可信代理 IP 不等於任意 forwarded headers 可信。 |
| OAuth 帳號認領／錯誤連結 | Spring Security 授權碼＋state＋S256 PKCE；provider/subject 為唯一身分，不按第三方 email 自動合併。既有本機使用者明確 POST link，回呼再核 session user/provider/10 分鐘 link 窗口，state 錯誤不建立身分。未知 subject 須先註冊本機帳號；第三方全部預設關閉。 | GitHub／Discord／Microsoft 三種本機 mock provider 登入／連結、錯 state、另一使用者認領同 subject 被拒、解除連結。沒有真第三方／TLS/OIDC provider 實測；Microsoft 使用 OAuth2 Graph userinfo，非 OIDC id_token 驗證流程。 |
| PAT scope 提權／IDOR | read/write/admin 與世界有效角色雙重檢查；read PAT 不可 push、write PAT 不可管理／建立 admin PAT。世界與 PR/comment/release/hook 子 id 同時限定 world id。私人不可讀／不存在都 404；Git 未認證統一 challenge。團隊授權須仍是組織成員；最後 owner 不可移除。 | 角色與 scope × Git advertise/實際 push/fetch × REST、跨世界留言 id、私人 ZIP、撤權後通知過濾測試。全站 admin 為明確信任角色。 |
| 組織最後 owner 的並行撤權 | 只有 COUNT 檢查會讓兩位 owner 同時移除自己，留下無 owner 的組織。setMember transaction 第一個 SQL 鎖定 owners 列，兩資料庫序列化成員異動，授權與最後 owner 檢查在鎖後讀取。 | 雙 HTTP 請求同時撤除各自 owner，僅一個成功、另一個 400，最終恰有一位 owner；SQLite／PostgreSQL 回歸。 |
| 多維度傳輸被成功認證限流阻擋 | 成功 Basic/Bearer PAT 不消耗或重設失敗紀錄；廉價 PAT 雜湊成功不被其他人的 IP 鎖攔住。錯誤憑證仍記 IP／account／pair，30 次/60 秒、5 次鎖 300 秒；成功另限每 IP/user 6000 次/60 秒。 | 100 次成功與隨後暴力嘗試回 429；成功仍通過，真多維度 clone/push/pull 用正式預設。密碼 BCrypt 先查失敗鎖；PAT 即使被鎖仍需廉價 DB 查詢，狀態有 max-keys 上限、單實例。 |
| 受保護分支／合成 publication 繞過 | force/delete/PR-only owner/admin 也拒絕；只可補發與全部維度既有 heads 一致的保護 publication，不能宣告別的維度不存在／不同內容。tag/group/transfer 不可覆寫／刪除，其他 ref namespace 拒絕；未保護分支改寫需 admin。 | 直接／force／刪除被拒、PR 審核後合併；惡意其他維度 publication 被拒、合法補發可接受。跨 repo 仍是 journal＋CAS，不提供跨 repo 原子 transaction。 |
| Tag 非 commit 造成 ClassCastException | 所有 peel 後先 instanceof RevCommit；blob/tree tag 明確 pre-receive 拒絕，現有非 commit refs 不直接強轉。 | blob 與 tree tag 推送被拒、訊息含 commit，沒有 500。 |
| PR tip／審核／並行競爭與中斷 | 全維度 fingerprint 綁選擇與審核；tip 變動清兩者，選擇改變清審核；作者不可自審、request-changes 阻擋。與 receive-pack 同 owner 鎖，core 最後再驗 tips/CAS，合併全部維度共用 snapshot、publication；先記 DB pending，發布後以 trailer/journal reconcile finalize。 | 舊 fingerprint 409、不同維度 tip 作廢、同時兩個合併只有一次成功、模擬 DB finalize 前中斷／重複 reconcile 只發一次 merge 事件。沒有任意時點 kill/fuzz 測試。 |
| 留言／標題 XSS | 一般留言／回覆／描述／release 說明全部 DOM text，不渲染 HTML／Markdown；安全 lint 禁 innerHTML/eval/Function/token localStorage。pin 為有範圍的整數／維度值，編刪限作者／admin，刪除保留 tombstone 與回覆。 | `<img src=x onerror=alert(1)>` 只顯文字、無 img 元素，真頁面座標跳轉與 CSP 0 violation。DOM 投影按鈕不插入使用者 HTML。 |
| ZIP 超額錯誤回 500 | 普通 API 預算的 100 萬 NBT nodes 誤用於完整世界組裝，且 Content-Type=ZIP 使錯誤 Map 無 converter。改 downloads.limits 獨立有限預算；尚未 committed 時清 buffer／下載檔名，回 JSON 413，保留安全標頭。ApiError 明確 JSON content type。 | 1-byte 下載預算、外層 1-node 額度、HTTP error converter 回歸；真瀏覽器 release 下載。資料已串流時只中止，不能承諾完整 JSON。 |
| ZIP DoS／私人快取 | 固定 tag 全組 commits，授權先於讀取；逐 region 有界暫存、ZIP 串流、private,no-store、沒有 ZIP 快取。預設 512 MiB／300 秒／2 並行；解析另限解壓／讀入各512 MiB、2000 萬 nodes、100 萬 objects、2 億 work。所有 finally 清暫存並釋放 semaphore／owner lock。 | 私人 404、ZIP entries 無玩家/history/session、過額、並行第二個 503、暫存空目錄；Paper 開 ZIP 後 verify。單 region 套用和慢 socket 無硬 deadline；owner 鎖可能被慢下載持有，部署需代理 write/idle timeout。 |
| Webhook SSRF／DNS rebinding | 預設 HTTPS、公網全部 DNS addresses；拒絕內網/loopback/link-local/保留 IPv4、非 global IPv6、6to4／特殊隧道。每次投遞重新解析驗證後 pin 地址給 socket，禁止 redirects/cookies/automatic retries；內網例外只允精確 hostname/IP。 | IPv4 整數／縮寫／mapped IPv6／loopback／metadata IP／隧道與萬用 allowlist 被拒；resolver 只接受原 host，redirect 302 不追蹤。部署設定的 allowlist 屬高信任管理決策，DNS lookup 自身無硬 timeout。 |
| Webhook 回應 body 關閉 | 核對實際 HttpClient 5.6.3 bytecode：一般 close() 是 GRACEFUL，會嘗試排空 response body。只取 status 後改用 ModalCloseable IMMEDIATE 丟棄連線，不排空對端 body。 | 新增持續 chunked body 回歸，要求 3 秒內 DELIVERED 且對端觀察斷線；避免持續送資料規避 socket idle timeout。 |
| Webhook 完整性／重試 | HMAC-SHA256 原始 UTF-8 JSON body、穩定 delivery id、attempt；持久 outbox 最多 5 次退避，60 秒 worker lease／每次10筆、5秒連線／10秒 socket、不讀無界 response。API 永不回傳 secret，錯誤不洩露 URL query／secret。 | 固定 HMAC known vector、503→204 重試 body／簽章相同、API 無 secret、SSRF/redirect 邊界。接收方須 constant-time 驗簽、去重，事件只提示 fetch／明確 pull，不自動套用。secret 明文 DB 是必要簽章秘密，須保護 DB／備份。 |
| 註冊濫用／資訊列舉 | 預設關閉；SMTP 啟用後每可信來源 IP 3次/小時、pending10000；token SHA-256／1 小時／刪除 CAS 一次兌換，驗證前不建帳號；重複 name/email 同回應。SQL 參數綁定、JSON 1 MiB、協作回應4 MiB、分頁100、有限openPR／留言／hooks。 | Mock SMTP 信箱驗證、登入前無帳號、重用驗證碼失敗、超額429。保留字／冒充／重寄與恢復／檢舉／跨IP治理未完成，不能以本最小版本代表公開服務政策已齊備。 |

## 供應鏈

新增 OAuth2/client、mail 與 Apache HTTP client 依賴使用 Spring Boot BOM，但實際打包版本仍要查核。首次 OSV 掃描 44 個帶 Maven metadata 的 jar 命中下列4筆；並非4筆都已證明在 Hub 使用路徑可利用，更新依賴以消除版本命中。

- HttpClient 5.5.2：GHSA-hjcp-jmpx-g3qm（Content-Encoding 解碼錯誤導致 connection leak），改嚴格 5.6.3；[Apache 發布](https://github.com/apache/httpcomponents-client/releases/tag/rel/v5.6.3)。
- HttpCore/httpcore5-h2 5.3.6：GHSA-hf6x-8p5f-cgmf／GHSA-v3jc-474w-2wm6（HTTP header/HPACK 記憶體額度），改嚴格 5.4.3；[Apache 發布](https://github.com/apache/httpcomponents-core/releases/tag/rel/v5.4.3)。
- Log4j API 2.24.3：GHSA-qv9r-c865-cp47（非有限數浮點 JSON serialization），以 Log4j BOM 2.25.5 對齊；[Apache 安全公告](https://logging.apache.org/security.html#CVE-2026-49844)。Hub 預設使用 Logback，此命中不等同已證明 log4j MapMessage 攻擊路徑可達。

逐筆 OSV 回應與原始版本證據已保存；最後打包版本與掃描結果見 `phase4-security/osv-packaged.json`。Spring／安全依賴在 jar 無 pom.properties 時，從實際 Gradle cache 座標配對檔名，覆蓋限制寫在 JSON；Spring Boot Jarmode Tools 另以 manifest 與官方 Maven POM 配對；WorldGit 自身 jar 沒有 advisory coordinate。這是已知版本 advisory 查詢，不能當作程式安全證明；npm audit 亦檢查既有 lockfile 的生產與開發依賴，皆 0 筆，結果見 phase4-security/npm-audit.json；未掃描作業系統映像（容器無法建置）。

## 部署／未完成界線

- 全部鎖／配額／rate limiter／session／download permits 為單 Hub；S3、共享 session／分散式鎖與公平隊列未提供。events／notifications／deliveries 沒有自動 retention 或 dead-letter 管理，需部署者清理及容量監控。
- 公開部署需 TLS、secure cookie、正確 public-url、有限 proxy timeout、HSTS、真實 secrets、可信代理來源 IP；bootstrap 印隨機密碼與固定測試 token 的政策未變，正式秘密不應進文件／log／commit。
- SQLite／PostgreSQL／Playwright／Paper 最後結果與原始 log、截圖在 [docs/14 Hub](../../docs/14-phase4-progress.md#hub)。新 Podman image 建置與 SQLite／PostgreSQL 冒煙已由主對話於 2026-10-03 驗證通過（Codex 沙盒禁止 uid_map 無法執行）；Docker 引擎未測。容器 OS 依賴尚未掃描。
