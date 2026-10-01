# WorldGit Hub 安全審查報告（2026-10-01）

## 範圍

- `hub/src/main/java/...`（Spring Boot 3.5 / Java 25 後端：帳號、JGit smart HTTP、資料解碼、資源管線）
- `hub/web/src/...`（TypeScript + Vite 前端）
- `hub/Containerfile`、`hub/compose.yaml`、`hub/deploy/*`、`.dockerignore`
- `.github/workflows/ci.yml`
- 共用模組 `core/`（region/NBT/zstd 解析、JGit store、diff）、`protocol/`（僅讀取，未深入）
- 參考文件：`hub/README.md`、`docs/07-remote-hub.md`、`docs/11-phase1-progress.md`（Hub 章節）、`.work/handoff/hub.md`

威脅模型：假設 Hub 會以 `worldgit.org` 公開上網，多租戶、之後開放自行註冊；審查優先順序依任務說明的 AuthN/AuthZ、Git smart HTTP、未信任世界資料解碼、前端、SSRF/完整性、SQL injection、容器與 CI。

## 方法

1. 依 `.work/security-skills/agamm_claude-code-owasp/.claude/skills/owasp-security/SKILL.md` 的流程：列出進入點與信任邊界 → 讀 `languages.md`（Java／TypeScript 段落）與 `config-and-supply-chain.md` → 逐一檢查候選問題 → 用下方四個問題過濾。
2. 每個候選發現都套用 `.../fp-check/SKILL.md` 的規則確認：輸入是否真的可由攻擊者控制、sink 是否真的可達（是否已有中介檢查）、影響範圍、攻擊者是否能完成每一步。只有通過驗證的才列為正式發現；無法完整動態驗證的在證據欄位註明「僅以程式碼追蹤驗證」。
3. 供應鏈：對 `hub/web`（npm + lockfile）執行 `.../supply-chain-risk-auditor/skills/supply-chain-risk-auditor/scripts/collect.py` + `render.py`（stdlib-only，無需 `uv`，改用系統 `python3` 執行）。Gradle 依賴（`gradle/libs.versions.toml`、`hub/build.gradle.kts`）該工具不支援，改為：對實際打包進 `worldgit-hub.jar`（含 Spring Boot 依賴管理解析後的版本，非 `libs.versions.toml` 宣告版本）用 OSV.dev API 逐一查版本對應的已知漏洞。
4. 動態驗證：於本機建置 `:hub:bootJar`，以 `127.0.0.1:8093` 啟動一份全新資料目錄的實例，建立測試帳號（admin／alice／bob）後用 `curl` 測試 AuthZ 邊界、CORS、HTTP 標頭、actuator 曝露。測試完成後已關閉程序並刪除所有測試資料目錄（`.work/hub/secreview/*`）。
   - 原本也打算用一個刻意構造的「解壓縮炸彈」push 動態驗證 DoS 發現，但該操作被安全分類器中斷；未重試，相關發現改為「僅以程式碼追蹤驗證」，如下方標註。

## 發現（依嚴重度排序）

### High

**H1. REST API 與 Git HTTP 對「私人世界是否存在」有時序／狀態碼側漏（matches 程式碼自身宣告的安全目標卻違反它）**

- **狀態：已修補（commit 前）**。REST 無權（含匿名）與不存在一律 404；Git 在查世界之前對所有有效路徑的未帶認證請求統一 401 challenge，帶有效憑證後無權與不存在一律 404。明確的 `anonymous`／空密碼只允許公開讀取，對私人／不存在（含 receive-pack）均 404。取捨：公開 clone 需使用 `https://anonymous:@...` 或在提示時輸入 anonymous／空密碼；保留 Git 帳密重試，同時防止由 challenge 判斷私人世界存在。這是狀態碼／內容的隱私保護，沒有宣稱網路回應時間為常數。
- **修補驗證**：`HubIntegrationTest.visibilityMatrixAndUniformGitChallenge` 覆蓋匿名／他人／擁有者 × 存在／不存在 × 公開／私人；同時驗證未認證 challenge、明確匿名讀取與 push 授權。動態 REST 12 組全部符合 200／404；Git challenge 對私人／不存在一致 401，已認證無權者一致 404（動態空世界尚未有維度 repo，公開已認證 Git 亦為 404；真正有 repo 的 200 路徑由整合測試驗證）。

- 檔案：`hub/src/main/java/org/worldgit/hub/web/Access.java:24-33`（`world()` 方法）；`hub/src/main/java/org/worldgit/hub/git/GitAuthFilter.java:45-62`
- 說明：`Access` 類別的 Javadoc 明確宣稱「找不到或無權讀取的私人世界一律回 404（不洩漏存在）」，但實作在**匿名（無任何憑證）**請求私人世界時回傳 **401**（`需要登入`），而對**不存在的世界**一律回傳 **404**；對**已登入但無權限**的使用者兩種情況才都正確統一為 404。Git smart HTTP 的 `GitAuthFilter` 有相同模式：匿名 clone/fetch 已存在但私人的 repo 會收到 401 挑戰，匿名存取不存在的 repo 收到 404。
- 攻擊情境：未登入的攻擊者可對任意 `owner/world` 組合發送請求，以 401 對 404 的差異列舉出哪些 owner（即使用者名稱）與 world slug 的組合確實存在且為私人，無需任何帳號。這洩漏使用者名稱存在性與其私人世界命名，是多租戶隱私/列舉問題，且與程式碼自己宣告的設計目標矛盾。
- 驗證方式：動態重現（本機 `127.0.0.1:8093`）。建立使用者 `alice`、建立私人世界 `alice/secret`：
  - 匿名 `GET /api/v1/worlds/alice/secret` → `401`
  - 匿名 `GET /api/v1/worlds/alice/doesnotexist` → `404`
  - 已登入但無權限（bob）對兩者皆 → `404`
- 修法：`Access.world()` 在 `role == Role.NONE` 時，無論 `user` 是否為 `null` 都應回傳 `ApiError.NotFound`（移除 `if (user == null) throw Unauthorized` 分支，讓匿名與已登入無權者得到相同的 404）。`GitAuthFilter` 同理：world 存在但角色不足時，若 `role == Role.NONE` 一律回 404，不要依 `user == null` 來決定回 401 挑戰還是 404（除非是 push 到不存在的 world 走 auto-create 流程，那裡的「需要登入才能建立」提示本身不洩漏既有私人世界，可保留）。

### Medium

**M1. 未設定任何安全性 HTTP 標頭（CSP / X-Content-Type-Options / X-Frame-Options / Referrer-Policy）**

- **狀態：已修補（commit 前）**。最高優先級 servlet filter 涵蓋 REQUEST／FORWARD／ERROR／ASYNC／INCLUDE，加 CSP、nosniff、DENY、Referrer-Policy、Permissions-Policy。HSTS 由 `worldgit.hub.security.hsts` 開關，預設 false，TLS 反向代理後設 true。
- **修補驗證**：靜態／API／404／Git 401／actuator 回應皆有標頭；開關開啟時回 HSTS `max-age=31536000`，預設關閉由整合測試確認。Playwright 使用正式產品 CSP 成功載入 256 chunks、2068 sections、兩個同源 module Worker；`securitypolicyviolation=[]`、前端 errors=[]、失敗資源回應=[]。CSP 只允許同源 script／worker／connect／stylesheet／font、同源與 data 圖片；deepslate／WebGL 無需額外來源或 eval。僅動態 style 屬性保留 `style-src-attr 'unsafe-inline'`，未允許 inline script。證據見 `secfix-2026-10-01/csp-results.json`；可重跑 `hub/web/scripts/security-csp.mjs`。

- 檔案：專案未使用 `spring-boot-starter-security`（`hub/build.gradle.kts` 只引入 `spring-security-crypto` 做 BCrypt），因此 Spring Security 預設會加上的這些標頭完全不存在；也沒有任何自訂 `Filter`/`WebMvcConfigurer` 補上。
- 驗證方式：動態重現，`curl -I http://127.0.0.1:8093/` 與 `/api/v1/worlds` 回應皆無 `Content-Security-Policy`、`X-Content-Type-Options`、`X-Frame-Options`、`Referrer-Policy`、`Strict-Transport-Security`。
- 攻擊情境：雖然目前前端審查未發現 `innerHTML`／`insertAdjacentHTML` 等明顯 XSS sink（見「未發現/已緩解」一節），但沒有 CSP/X-Frame-Options 仍使任何未來引入的 XSS 缺陷、或第三方腳本注入風險的爆炸半徑最大化，也允許頁面被嵌入 `<iframe>`（clickjacking）。公開上線前應視為必要的縱深防禦層。
- 修法：加一個簡單的 `Filter`（或 `WebMvcConfigurer` + `HandlerInterceptor`）替所有回應加上至少：`X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`（或 `frame-ancestors 'none'`）、`Referrer-Policy: strict-origin-when-cross-origin`、依前端實際需要的資源來源訂一個 `Content-Security-Policy`；反向代理層再疊加 `Strict-Transport-Security`。

**M2. push 大小上限（4 GiB）遠大於「每個 pack < 100 MB」的設計決定，且無帳號／owner 層級配額**

- **狀態：已修補（commit 前）**。`max-pack-bytes` 改為 95,000,000（與事後 repack 的設定分開且皆可配置）；owner 所有世界／維度的實體磁碟檔案總和預設配額 10 GiB。收包前用剩餘配額縮小 pack 上限，pre-receive 計入 pack/index 後再核對，超額不更新任何 ref；完全拒絕的新增 pack 清理。push（含會 auto-create 的廣告請求）、刪世界及 repack 共用固定大小的 owner 鎖池，防止競態。無足夠建 repo 餘裕（4096 bytes）時亦提前拒絕。
- **修補驗證**：單元測試確認跨世界加總與清理僅刪新增 pack；整合測試以稀疏檔模擬已滿 owner，拒絕另一世界 push 且無 main ref。動態以 4096-byte 收包上限拒絕 100 KB 隨機 blob，並確認無殘留 pack；1 MiB 配額滿時對其他世界的 Git 廣告回 413、含配額訊息且不 auto-create repo（結果見後方驗證紀錄）。收包暫存仍可能短暫多出一個受限 pack 與 index，部署需留餘裕；共用 tile／資源快取未計入 owner 配額。Git 傳輸不會因本機 pack 已切檔而自行分批，合法的大型首次快照需分批提交或由管理員調整收包上限。

- 檔案：`hub/src/main/resources/application.yml`（`worldgit.hub.git.max-pack-bytes: 4294967296`）；`hub/src/main/java/org/worldgit/hub/git/GitConfiguration.java:52`（`rp.setMaxPackSizeLimit(props.git().maxPackBytes())`）；`hub/src/main/java/org/worldgit/hub/git/Maintenance.java`（push 後才非同步 repack 到 `pack-limit-bytes`=95 MB）
- 說明：JGit 在收包階段真正擋下超大 push 的限制是 `maxPackBytes`＝4 GiB，`pack-limit-bytes`（95 MB）只是「push 完成後」才觸發的非同步 repack 門檻。也就是說任何擁有 WRITER 權限（包含透過 `auto-create-worlds` 自動建立自己世界的一般使用者）都能在**單次 push**先把最多 4 GiB 的資料寫入磁碟，才輪到非同步維護去縮減。`LocalRepoStorage`／`AccountService` 完全沒有依 owner 或使用者的總儲存配額限制（找不到任何 quota 相關程式碼）。
- 攻擊情境：已註冊使用者（或透過 auto-create-worlds 的任何 WRITER）可重複建立世界並各推送接近 4 GiB 的 pack，快速耗盡 `/data` 磁碟空間，造成全站 DoS（SQLite 寫入失敗、其他使用者無法 push／repack）。
- 驗證方式：程式碼追蹤確認（`maxPackBytes` 的 4 GiB 預設值與 `pack-limit-bytes` only 影響事後 repack，兩者語意不同在程式碼與設定檔中皆明確）；未動態重現實際推送 4 GiB（避免磁碟/時間成本過高且非必要）。
- 修法：把 `max-pack-bytes` 下修到接近業務上合理的單次 push 上限（例如與 `pack-limit-bytes` 同量級，如 200–500 MB），並新增 owner 層級的總儲存配額（查詢 `/data/repos/{owner}` 樹大小，超過配額時在 pre-receive 或 `HubRepositoryResolver`/`GitAuthFilter` 階段拒絕新 push）。

**M3. 未信任的 commit trailer／NBT 結構在 pre-receive 與資料端點中缺少聚合上限，構成解壓縮放大與 CPU 放大型 DoS**

- **狀態：已修補（commit 前）**。core 新增向後相容 `DecodeBudget.open()` scope；Hub 每個同步請求／push 共用解壓 bytes、輸入 bytes、NBT 節點、物件數及解析工作預算，在配置／解壓前檢查。涵蓋 chunk／diff／entities／tile、commit 統計與歷史，亦計入 section 展開引用與 diff 物件／BE Base64 的配置量。未開 scope 的既有 core API 維持原本單物件限制與方法簽章。
- **修補驗證**：core 測試涵蓋跨 blob、跨 NBT、跨 commit、跨 contribution chunk（重複項先計數）、scope 還原及 uniform section 展開 diff。commit 上限 1 MiB、1024 trailers／100000 contribution chunks；pre-receive 驗證全部新增歷史（最多10000 commits），超過拒絕 push。本機 `-Xmx1g`／8093 實際推送四個有效 section blob，各解壓約 4 MiB、壓縮約數百 bytes；請求預算設8 MiB，chunks／diff／tile PNG／height 四條路徑皆413，之後 health=200，未發生OOM。1100 trailers 的後續 push 被拒。資料皆在 `.work/hub/secfix/`，全程 timeout 與 bench lock；此動態驗證補足初次審查未完成的重現。

- 檔案：`core/src/main/java/org/worldgit/core/store/CommitTrailers.java`（`parse()`，無限制 `WorldGit-Contribution` trailer 數量、每個 contribution 內 `chunks` 清單長度僅受 `Nbt.read` 單一呼叫的 100 萬節點上限約束，但該上限是「每次 NBT 解析」而非「整個 commit message 內所有 trailer 加總」）；`core/src/main/java/org/worldgit/core/normalize/SnapshotCodec.java:23-33`（`decode()`：單一 blob 解壓後上限 `Nbt.MAX_BYTES`＝32 MiB，檢查在 `Zstd.decompress` 呼叫之前用 `Zstd.getFrameContentSize` 讀取宣告大小，而非先驗證壓縮比）；`hub/src/main/java/org/worldgit/hub/data/DataService.java:14`（`MAX_WINDOW_CHUNKS = 1024`）與 `ChunkWire.chunks()`／`TileRenderer` 會在單一 HTTP 請求的同一執行緒內，對視窗內每個 chunk 的每個 section blob 都呼叫 `SectionBlob.parse → SnapshotCodec.decode → Zstd.decompress`。
- 說明：zstd 對高度重複資料的壓縮比可達數萬倍，單一 blob 在 32 MiB 上限保護下看似安全，但攻擊者能在一次 push（甚至遠低於 `pack-limit-bytes` 95 MB 的 pack）中塞入數千個這樣的小型高壓縮比 blob（例如 1000 個各 1 KB 的壓縮 blob，總 pack 僅約 1 MB），分散在 1024 個 chunk 視窗內的多個 section。當任何使用者（包含該世界的一般 READER，只要世界公開或自己有權限）呼叫 `/chunks`、`/diff` 或 `/tiles/*.png` 等端點時，伺服器會在單一請求執行緒內同步解壓所有這些 blob，疊加可達數十 GB 的暫時記憶體／CPU 用量，造成記憶體或 CPU 耗盡型 DoS；`PushHooks.Pre` 也會在 pre-receive 階段對每個 push 同步解析 commit 全文與其中全部 `WorldGit-Contribution` trailer（Base64 解碼＋NBT 解析），trailer 數量與每個 trailer 內 `chunks` 清單長度皆無跨 trailer 的總量上限，單一超大 commit message（受 64 MB 單物件上限保護，但 64 MB 仍可塞入巨量 trailer）足以讓每次 push 的前置檢查變得非常昂貴。
- 攻擊情境：（a）任何 WRITER 使用者 push 一個刻意構造、內含大量小型高壓縮比 section blob 的 commit；（b）任何能讀取該世界的使用者（含匿名，若世界公開）呼叫 `/chunks?x0=..&x1=..`（視窗可達 1024 chunk）觸發伺服器端大量解壓縮，拖垮該 Hub 實例。
- 驗證方式：**僅以程式碼追蹤驗證**，未動態重現（原規劃用自行構造的 zstd 高壓縮比 blob 透過 `git push` 送入本機測試實例驗證，但該操作在執行中被安全分類器中斷而未繼續嘗試；此發現标記為高信心的程式碼層級問題，但未附上動態重現證據，依 fp-check 規則如實標註）。
- 修法：
  1. 在 `DataService`／`ChunkWire` 讀取路徑加入「單一請求總解壓位元組數」預算（例如所有視窗內 blob 解壓後總和不得超過某個合理上限，如 256 MB），超過即提前中止並回錯誤而非繼續解壓。
  2. `CommitTrailers.parse()` 加入 trailer 總數上限與單一 commit 所有 `WorldGit-Contribution` 的 `chunks` 清單總長度上限（而不只是單次 NBT 解析的 100 萬節點上限），超過即在 pre-receive 階段直接拒絕該 push。
  3. 考慮把 pre-receive 的 trailer 解析結果快取或限制每個 push 只解析一次（目前 Pre／Post 各解析一次 tip commit，屬輕量重複，非本發現重點，但一併檢討）。

**M4. 登入與 token 端點無速率限制／鎖定機制**

- **狀態：已修補（commit 前）**。登入與 Git Basic 在 credential check 前保留嘗試；IP、帳號及其組合預設每60秒30次，5次失敗暫鎖300秒，回429與Retry-After。有效 Bearer 不扣嘗試以支援 viewer 串流，失敗 token 仍計入IP防護。限流狀態最多10000鍵，容量滿時拒絕新鍵，不驅逐有效鎖定；目前為單實例記憶體狀態。
- **修補驗證**：單元測試涵蓋成功登入的速率上限、失敗鎖定、換IP仍受帳號鎖、時間到解除、有效Bearer串流，以及XFF只信任明確代理IP／由右往左找到不可信hop。本機登入5次失敗後正確密碼仍429，Git也429；未信任的偽造XFF不能繞過。另在8094直接對Git Basic重試驗證鎖定。

- 檔案：`hub/src/main/java/org/worldgit/hub/web/AccountController.java`（`/api/v1/auth/login`）；`hub/src/main/java/org/worldgit/hub/account/AccountService.java`（`authenticatePassword`）；`hub/src/main/java/org/worldgit/hub/git/GitAuthFilter.java`（Basic auth 驗證同樣會呼叫 `authenticatePassword`／`authenticateToken`）
- 說明：全專案搜尋未發現任何登入失敗計數、鎖定、延遲或速率限制機制（無 Spring Security、無 bucket4j 等）。使用者密碼最短僅 8 字元（`AccountService.createUser`），BCrypt 雖可抵抗離線暴力破解，但線上無限制重試仍可對弱密碼帳號進行認證碼填充／暴力破解；Git Basic Auth 端點同樣可被用來做密碼猜測（且每次失敗都要跑一次 BCrypt，對伺服器也是計算成本）。
- 驗證方式：程式碼追蹤確認（遍覽 `AccountController`、`AccountService`、`GitAuthFilter`、`application.yml`，未發現任何限流元件或設定）。
- 修法：在 `/api/v1/auth/login` 與 Git Basic Auth 路徑前加上以 IP／帳號為鍵的速率限制與漸進式延遲或鎖定（例如 bucket4j、或反向代理層 `fail2ban`／`nginx limit_req`），並考慮提高最短密碼長度或要求更高熵（例如 passphrase 建議）。

### Low

**L1. 攻擊者能以推送的 git tree 內任意非規範命名的 `r.*`／`c.*` 目錄，觸發資料端點未捕捉的 `NumberFormatException`，造成單一 commit 的檢視端點回 500**

- **狀態：已修補（commit 前）**。region／chunk tree 名稱先檢查格式與整數範圍；section 名稱溢位由資料錯誤處理。core diff 的chunk與過深tree也清楚失敗。NumberFormatException與其他資料錯誤回400／422，通用500不再含例外類別。
- **修補驗證**：實際推送 `r.x.y` tree，再請求 `/tiles` 回400「世界 tree 座標無效」，內文無Exception。另測視窗整數溢位與Integer.MAX_VALUE邊界，避免無限迴圈。

- 檔案：`core/src/main/java/org/worldgit/core/model/ChunkPos.java`／`hub/src/main/java/org/worldgit/hub/data/TreeNav.java:48-51`（`regionCoords()` 直接 `Integer.parseInt(p[1])`，未檢查格式）；`hub/src/main/java/org/worldgit/hub/data/TileRenderer.java`（`c.*` 命名同樣直接 parse）
- 說明：`PushHooks.Pre` 只驗證 commit message 的 `WorldGit-Dimension` trailer 是否符合 repo 的維度，**完全不驗證 tree 內容結構**。攻擊者可推送一個 tree，其中含有名稱為 `r.` 開頭但座標非數字（如 `r.x.y`）的目錄；呼叫 `/tiles` 等端點時 `TreeNav.regions()` 會依 `startsWith("r.")` 選中它，之後 `regionCoords()` 對其 `split(".")[1]` 做 `Integer.parseInt` 失敗丟出未捕捉例外，被 `ApiError.other` 捕捉後回傳 HTTP 500（內文含例外類別名稱如 `NumberFormatException`，不含訊息或堆疊，洩漏資訊有限）。
- 攻擊情境：對自己可寫入的世界推送這類畸形 tree 後，會讓之後所有人（含自己）瀏覽該 commit 的 tile／chunk 端點收到 500；影響範圍侷限在該 commit 的資料端點，屬輕度可用性問題與極小幅資訊洩漏（例外類別名稱），非跨租戶影響。
- 驗證方式：程式碼追蹤驗證（確認 pre-receive 無 tree 結構驗證、`regionCoords`／chunk 名稱解析無防禦性檢查、`ApiError.other` 的回應格式）。
- 修法：在 `TreeNav`／`TileRenderer`／`HistoryService` 讀取 region／chunk 名稱時用 try/catch 或正規表示式先驗證格式，不符合即忽略該項目（視為非 WorldGit 產生的雜項目錄）而非讓例外往外拋；或在 pre-receive 階段對 tree 結構做基本格式檢查後才接受 push。

**L2. Personal Access Token（PAT）預設永不過期，且刪除／列出均無「最後使用時間」可供稽核**

- **狀態：已修補（commit 前）**。PAT建立API與前端可填 `expiresAt`，預設90天（可配置），只能在未來且不超過十年。認證成功更新 `last_used_at`，列出API與設定頁顯示期限／最後使用。舊SQLite／PostgreSQL表採相容的欄位升級，不變更既有／bootstrap PAT期限。
- **修補驗證**：整合測試與本機API核對指定期限、最後使用與過去期限被拒；8094另確認到期PAT回401。舊SQLite schema冪等升級測試保留原有資料。

- 檔案：`hub/src/main/java/org/worldgit/hub/account/AccountService.java`（`createToken(..., session=false)` 時 `expires = null`）；`schema.sql`（`tokens` 表有 `expires_at` 欄位但 PAT 建立時從未設定）
- 說明：資料庫 schema 已支援 `expires_at`，但只有 session token（14 天）使用；個人 token（`wgt_` 前綴）建立後預設永久有效，介面上也看不到「最後使用時間」，洩漏後若使用者未主動到 `/settings` 頁刪除即會一直有效。
- 驗證方式：程式碼追蹤確認。
- 修法：建立 PAT 時提供可選的到期日（API 與前端皆可選填），並在 `tokens` 表加一個 `last_used_at` 欄位，在 `authenticateToken` 命中時更新，供使用者在 `/settings` 頁面判斷是否該撤銷。

**L3. 容器基底映像未釘選 digest**

- **狀態：已修補（commit 前）**。四個Node／Temurin基底全釘選多架構index SHA-256，FROM／COPY保留對應tag，註解提供registry digest核對與更新後build/smoke流程。
- **修補驗證**：Docker Registry實際查詢紀錄在 `secfix-2026-10-01/image-digests.json`；容器建置／冒煙實測見後方。

- 檔案：`hub/Containerfile`（`docker.io/library/node:22-slim`、`docker.io/library/eclipse-temurin:25-jdk`、`eclipse-temurin:21-jdk`、`eclipse-temurin:25-jre` 皆用 tag 而非 `@sha256:...`）
- 說明：tag 可變（即使是版本化 tag，上游仍可能重新推送同一 tag 指向不同內容），無法保證建置重現性與供應鏈完整性。
- 驗證方式：直接閱讀 `Containerfile` 確認。
- 修法：改用 digest 釘選（`FROM eclipse-temurin:25-jdk@sha256:...`），並視情況加上 Renovate/Dependabot 自動更新 digest 的流程。

**L4. CI workflow 的 Actions 未釘選到 commit SHA**

- **狀態：已修補（commit 前）**。checkout、setup-java、setup-gradle、setup-node、upload-artifact釘選完整40字元commit SHA，註解保留v4；僅替換uses欄位，保留並行Paper／Fabric工作。
- **修補驗證**：以官方repo `git ls-remote refs/tags/v4 refs/tags/v4^{}` 解析tag，紀錄在 `secfix-2026-10-01/action-shas.json`；重新讀共用workflow後做最小替換。CI尚未在GitHub實跑（依任務未commit/push）。

- 檔案：`.github/workflows/ci.yml`（`actions/checkout@v4`、`actions/setup-java@v4`、`gradle/actions/setup-gradle@v4`、`actions/setup-node@v4`、`actions/upload-artifact@v4` 皆用版本 tag）
- 說明：tag 可被上游帳號重新指向惡意 commit（supply-chain 攻擊面），`permissions: contents: read`（top-level）已是好的最小權限起點，值得肯定。
- 驗證方式：直接閱讀 workflow 確認；`permissions` 區塊存在且為 `contents: read`。
- 修法：改為釘選各 action 的完整 commit SHA（例如用 `step-security/harden-runner` 或手動更新）。

**L5. `compose.yaml` 以一般環境變數（非 secret）傳遞管理員密碼／token**

- **狀態：已修補（commit 前）**。Compose採兩個file-backed secrets，環境只帶`*_FILE`路徑；Hub在啟動早期讀檔、限制4096 bytes、去除結尾換行。文件說明宿主檔案與uid10001可讀權限，未使用固定token時提供空檔。
- **修補驗證**：secret檔讀取優先序／保留密碼空白有單元測試；容器smoke改用唯讀secret檔掛載與`*_FILE`，實測見後方。

- 檔案：`hub/compose.yaml`（`WORLDGIT_HUB_BOOTSTRAP_ADMIN_PASSWORD`／`WORLDGIT_HUB_BOOTSTRAP_ADMIN_TOKEN` 直接用 `${...}` 環境變數插值）
- 說明：對比 `hub/deploy/worldgit-hub.container`（Podman Quadlet）已正確使用 `Secret=worldgit-admin-password,type=env,...`，`compose.yaml` 版本的密碼會出現在 `docker inspect`、shell 環境、有時是 shell history 中。`compose.yaml` 註解本身也寫明「首次」才需要帶這個變數，一般情況風險可控，但仍建議補上 compose secrets 範例。
- 補充（收尾）：Quadlet 原本以 `type=env` 注入密碼，現亦改為 `type=mount` 檔案 + `WORLDGIT_HUB_BOOTSTRAP_ADMIN_PASSWORD_FILE`（未在 systemd 實機啟動驗證）；compose 路徑經容器 inspect 確認無明文。
- 驗證方式：直接閱讀設定檔確認；對比同一專案 Quadlet 版本已採用更安全做法。
- 修法：`compose.yaml` 改用 `secrets:` 區塊 + `*_FILE` 慣例（Hub 目前程式碼是否支援 `_FILE` 後綴讀法未見實作，若不支援則需先補上，或至少在文件中明確建議正式環境改用 Quadlet／Kubernetes Secret）。

### Info

**I1. SQLite 檔案權限未在程式中顯式設定**

- **狀態：未修補**。仍依部署者umask／volume權限；非容器部署須自行保護資料目錄。此Info項未列入本次指定程式修補範圍。

- `data-dir` 下的 `hub.db` 建立時依賴容器 `umask`／宿主 OS 預設權限，程式未呼叫 `Files.setPosixFilePermissions` 強制 `0600`。目前容器以非 root（uid 10001）執行且 `/data` 目錄歸屬正確，風險主要出現在非容器化本機部署場景。建議之後補一行在 `DataDirPostProcessor` 或啟動時對 `data-dir` 設定 `0700`。

**I2. Mojang client jar 下載具備 SHA-1 完整性校驗，SSRF 面已侷限**

- **狀態：部分（既有緩解維持）**。此次未更動下載策略；既有manifest／SHA-1校驗由初次審查程式追蹤確認，非待修漏洞。

- `AssetService.downloadClient()` 的 URL 來源是先讀取 `manifestUrl`（預設 Mojang 官方 manifest，設定檔可覆寫）再用其中回傳的 `url` 下載，兩次請求的目標都來自可信任的 Mojang API 回應，不接受使用者輸入的任意 URL；下載後以 SHA-1 比對官方 manifest 宣告值才使用，符合其設計註解所述。唯一的理論風險是若管理者自行把 `worldgit.hub.assets.manifest-url` 設成攻擊者可控的網址（屬於管理員自己的設定錯誤，非典型 SSRF 攻擊面），不建議列為正式漏洞，僅記錄供參考。

## 未發現問題 / 已確認的緩解措施（值得記錄，避免之後重工）

- **SQL injection**：`AccountService`／`HistoryService` 等全面使用 `JdbcClient` 的具名/位置參數化查詢（`.params(...)`），未發現任何字串拼接組 SQL 的地方。
- **前端 XSS**：`hub/web/src/ui.ts` 的 `h()`／`append()` 一律使用 `document.createElement` + `el.append(textNode)` 與 `setAttribute`，全專案搜尋 `innerHTML`／`outerHTML`／`insertAdjacentHTML`／`document.write` 均為零筆；commit 訊息、作者名稱、世界顯示名稱、告示牌/橫幅文字等不信任字串經檢查後都只進入文字節點或屬性值，未發現可注入 HTML 的路徑。
- **CORS**：未設定任何 `CorsConfigurationSource`／`@CrossOrigin`，動態測試確認跨來源 `OPTIONS` 預檢請求回 403 且無 `Access-Control-Allow-Origin`，瀏覽器會正確阻擋跨站讀取，預設即安全。
- **CSRF**：API 認證完全基於 `Authorization: Bearer`（token 存於 `localStorage`，非 Cookie），瀏覽器無法在跨站請求中自動夾帶，傳統表單式 CSRF 對本 API 不適用；Git smart HTTP 的 `git-receive-pack` 需要特定 `Content-Type` 與原始二進位 body，一般 HTML 表單無法偽造，CSRF 風險低。
- **Actuator 曝露**：`management.endpoints.web.exposure.include` 僅開放 `health`，動態測試確認 `/actuator` 只列出 `health`／`health-path` 連結，無 `env`、`beans`、`heapdump` 等高風險端點。
- **路徑穿越（owner/world/dimension）**：`NameRules.SLUG` 正規表示式與 `DimensionId` 建構子的正規表示式皆不允許空字串片段，因此 `..` 無法以合法片段出現；`DimensionId.directoryName()` 另外把 `.` 與 `/` 分別編碼為 `%2E`／`%2F` 才落地成目錄/檔名，審查未發現可逃逸 `data/repos/` 樹狀結構之外的路徑。
- **NBT 解析防護**：`core/.../anvil/Nbt.java` 對深度（64 層）、節點數（100 萬）、陣列/清單長度（相對 `MAX_BYTES` 與 `available()`）皆有上限檢查，單一 NBT 解析本身對深度炸彈／巨大陣列有合理防禦（見 M3 對「跨多個 blob 聚合」缺口的補充）。
- **密碼雜湊**：使用 `BCryptPasswordEncoder(10)`，成本因子合理；token 僅以 SHA-256 雜湊存於 `tokens.token_hash` 並有唯一索引，明文只在產生當下回傳一次，均符合預期做法。
- **指令注入**：`hub/src`、`core/src`、`protocol/src` 全文搜尋 `ProcessBuilder`／`Runtime.exec` 均為零筆，未發現命令注入面。
- **自行註冊**：目前 `POST /api/v1/users` 強制要求 `actor.admin()==true`，尚未開放公開自助註冊，降低了當前的帳號濫用面（但上線前若要開放註冊，需重新評估 M4 的速率限制與驗證碼/信箱驗證需求）。

## 供應鏈掃描結果摘要

### npm（`hub/web`，使用 `supply-chain-risk-auditor` 腳本，stdlib-only，以系統 `python3` 執行；`uv` 在本機環境未安裝，改用 `python3` 直接跑）

- 7 個直接依賴（`deepslate`、`gl-matrix` 為正式依賴；`@types/node`、`playwright-core`、`typescript`、`vite`、`vitest` 為開發依賴）。
- **已知漏洞（依鎖定版本查核）**：7 個直接依賴與 84 個經 `package-lock.json` 解析確認版本的傳遞依賴，均**未發現任何已知 advisory**。
- 其他訊號：`deepslate`（進入正式產出）由單一維護者（misode）發布，週下載量僅 2,387，屬於小眾但專案本身是 Minecraft 資料結構的權威函式庫（PrismarineJS/生態圈常用），非典型供應鏈濫用模式但維護者集中度本身值得記錄；`vite`／`playwright-core` 的 OpenSSF Scorecard「Binary-Artifacts」項偏低（分別 1/10、6/10）。
- 完整報告見 `.work/secreview/report.md`（含 Coverage／Not assessable 明細），`.work/secreview/findings.json` 為原始資料。

### Gradle（`gradle/libs.versions.toml`、`hub/build.gradle.kts`；工具不支援 Gradle，改用 OSV.dev API 人工核對實際打包進 `worldgit-hub.jar` 的版本）

- 以 `unzip -l hub/build/libs/worldgit-hub.jar` 取得 Spring Boot 依賴管理實際解析後的版本（與 `libs.versions.toml` 宣告版本不同，Spring Boot BOM 會覆寫部分版本），逐一查 OSV.dev：
  - **`org.postgresql:postgresql:42.7.7`**（`hub/build.gradle.kts` 宣告版本；注意：此為 `runtimeOnly`，預設 SQLite 部署下不會被實際使用，但一旦切換 PostgreSQL 就會啟用）：命中 **GHSA-98qh-xjc8-98pq**（PBKDF2 疊代次數無上限，CPU 耗盡 DoS，HIGH，修復於 42.7.11）與 **GHSA-j92g-9f8w-j867**（channel-binding 認證降級，HIGH，修復於 42.7.12）。惟實際打包進 jar 的是 Spring Boot BOM 解析後的 **42.7.11**（見下）。
  - **`org.eclipse.jgit`／`org.eclipse.jgit.http.server:7.3.0.202506031305-r`**：OSV 查無已知漏洞。
  - **`com.github.luben:zstd-jni:1.5.6-6`**：OSV 查無已知漏洞。
  - **`org.xerial:sqlite-jdbc:3.50.3.0`**：OSV 查無已知漏洞。
  - **`org.lz4:lz4-java:1.8.0`**（`core` 模組用於讀取舊版 Anvil region 檔的 LZ4 壓縮段落，見 `core/.../anvil/RegionFile.java:115`）：命中三筆，其中 **GHSA-cmp6-m4wj-q63q**（安全解壓縮器資訊洩漏，HIGH，修復於 1.10.1）、**GHSA-vqf4-7m7x-wgfc**（越界記憶體操作可致 DoS，HIGH，修復於 1.8.1）、**GHSA-xx22-p4ch-683r**（原生 XXHash 可使 JVM 當機，MODERATE，修復於 1.11.1）。此函式庫處理的是**從玩家伺服器讀入的既有世界存檔**（Anvil region 檔可能用 LZ4 壓縮某些舊格式區塊），輸入來源可視為半信任（伺服器管理者自己的世界檔，但若之後支援由不受信任來源匯入既有存檔，風險會提高）。**建議升級至 ≥1.11.1**（`gradle/libs.versions.toml` 的 `lz4 = "1.8.0"` 需更新；為 `core` 模組的直接依賴，不受 Spring Boot BOM 管理）。
  - **實際打包版本的額外核對**（因 Spring Boot BOM 覆寫而與 `libs.versions.toml`/直接宣告不同）：
    - `jackson-databind` 實際為 **2.21.4**（非 `libs.versions.toml` 的 2.18.3）：OSV 命中多筆 MODERATE/HIGH（如 **GHSA-cxp5-3px4-pw24** 二次方前向引用、**GHSA-q4xh-88c3-wmh7** Duration 解析 DoS，皆 HIGH），修復版本多為 2.18.9+／2.21.5+／2.21.6+／2.21.7+ 等微幅後續版本，**建議跟進 Spring Boot 的下一個 patch release**（這些是 Spring Boot BOM 管理的版本，不是專案自行宣告，需等 Spring Boot 更新或手動覆寫 `jackson-databind` 版本）。
    - `tomcat-embed-core` 實際為 **10.1.55**：OSV 命中三筆 **CRITICAL**（**GHSA-9xv2-5v5q-p794** DIGEST 認證重放繞過、**GHSA-gcx9-497g-6cp6** 存取控制錯誤、**GHSA-h3x4-894j-xpx5** FORM 認證授權錯誤），修復版本為 **10.1.58**。本專案未使用 Tomcat 的 DIGEST／FORM 認證機制（Hub 自行用 `GitAuthFilter`／`RequestUser` 做 Bearer／Basic 驗證，未啟用 Servlet 容器層級的認證），**實際可被觸發的風險因此較低**，但仍建議升級 Tomcat 以消除攻擊面（同樣受 Spring Boot BOM 管理，需等 Spring Boot patch 或手動覆寫）。
    - `postgresql` 實際為 **42.7.11**（已修復 GHSA-98qh-xjc8-98pq），但**尚未修復 GHSA-j92g-9f8w-j867**（channel-binding 降級，需 42.7.12），且此套件目前在 SQLite 預設部署下為 `runtimeOnly` 未啟用。
    - `spring-security-crypto` 實際為 **6.5.11**：OSV 查無已知漏洞。
  - **建議動作**：(1) 在 `hub/build.gradle.kts` 明確覆寫 `jackson-databind`、`tomcat-embed-core`、`postgresql` 到已修復版本（`implementation("...:版本")` 或 Gradle 的 `resolutionStrategy`），不要等 Spring Boot 下一個小版本；(2) 升級 `gradle/libs.versions.toml` 的 `lz4` 到 ≥1.11.1。

## 上線 worldgit.org 前檢查清單

- [x] 修正 H1：統一私人世界「不存在／無權限」皆回 404（API 與 Git HTTP 兩層）
- [x] 加上安全性回應標頭（CSP、X-Content-Type-Options、X-Frame-Options、Referrer-Policy；反向代理層加 HSTS）
- [x] 重新評估 `max-pack-bytes`（已由4 GiB改95 MB）並實作 owner 層級儲存配額
- [x] 替 `CommitTrailers.parse()` 與資料解碼路徑加入跨物件的聚合解壓縮/解析預算上限
- [x] 登入與 Git Basic Auth 端點加上速率限制／鎖定
- [x] 升級 `jackson-databind`、`tomcat-embed-core`、`postgresql`（JDBC driver）、`lz4-java` 到已修復版本
- [x] 容器基底映像與 CI Actions 釘選 digest／commit SHA
- [x] `compose.yaml` 改用 secrets 機制管理管理員密碼/token（比照 Quadlet 版本）
- [ ] 開放自助註冊前重新評估：速率限制、是否需信箱驗證、保留字/冒充使用者名稱政策
- [x] 決定是否要讓 PAT 可設定到期日、加上 `last_used_at`
- [ ] 正式環境請勿使用隨機產生並印在 log 的 bootstrap 密碼直接長期使用；上線當下立即登入並建立具到期日的個人 token，撤銷 bootstrap 密碼登入習慣

## 未覆蓋範圍

- `core/` 的完整 diff engine（`DiffEngine.java`）、`protocol/` 模組，以及 `paper/`、`fabric/`、`cli/`、`experiments/`（依任務指示排除，且為其他 Sonnet agent 同時處理中的範圍）。
- 初次審查的M3重現未完成；本次修補已以受控本機高壓縮比blob完成動態驗證，見M3修補驗證。深入巢狀與其他格式fuzzing仍未全面覆蓋。
- （2026-10-01 後續已驗證 PostgreSQL 後端，見 docs/11）未測試 S3 儲存後端、OAuth（文件已註明 Phase 1 未實作）。
- （後續已驗證 rootless Podman 與 Quadlet，見 docs/11）未做 arm64 的容器環境差異驗證（`.work/handoff/hub.md` 已註明這些是已知未做項目）。
- 未對前端 3D 檢視器（`viewer/*.ts`、WebGL shader、Web Worker）做逐行審查，僅檢查其資料反序列化（`wire.ts`）入口與已知的字串/HTML sink；深入的 WebGL/shader 層與記憶體安全（WASM 等）未在範圍內逐行覆蓋。
- 未對 `AssetPipeline.java`（資源包處理管線本身，讀取 Mojang client jar 內的模型/材質並轉成 `atlas.json` 等）做詳細的 zip bomb／畸形 JSON 的壓力測試，僅讀過其呼叫鏈（`ResourceSource.zip`／`directory`），未發現該檔案本身有長度上限檢查；由於 client jar 來源已鎖定在設定檔白名單的 Minecraft 版本並經 SHA-1 驗證，攻擊面有限，但若之後允許管理員自行上傳任意 client jar，建議另外審查 `AssetPipeline.java` 的 zip bomb 防護（未在本次範圍內逐行確認是否已有上限）。
