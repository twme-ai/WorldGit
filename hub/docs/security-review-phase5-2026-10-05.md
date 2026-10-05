# Phase 5 Hub 安全審查（2026-10-05）

本輪範圍是維度獨立 PR／policy／HEAD／圖、operation／SSE／下載準備、共用結果與錯誤報告、schema v5。沿用 Phase 4 的入口追蹤、攻擊面／防禦／回歸證據格式。既有 OAuth、SMTP、SSRF、PAT hash／期限、配額、pack 與 CSRF 控制保留；舊審查中的 publication 配對要求由決策 #122 取代，namespace／不可任意改寫與 branch policy 仍保護實際 refs。

## 威脅模型與檢查結果

| 攻擊面 | 防禦／限制 | 回歸證據 |
|---|---|---|
| 私人世界 graph／operation 枚舉 | Access.world 先檢查 ACL＋PAT read scope，無權限回 404。圖／merge annotation 只查該 world id／dimension。UUID v4 不當成授權本身，operation 還需同一啟動 actor；即使另一位 admin 可讀世界也不能讀他人的作業。匿名公開作業綁 session 隨機 actor。 | CollaborationTest 的私人圖 404、read 可用、limit 0／10001 拒絕、其他使用者作業／SSE／下載 404；實機腳本同項檢查。 |
| merge／HEAD／policy／release 越權 | merge 要 writer＋write scope；HEAD／policy／hook test 要 admin＋admin scope；PR 編輯／選維度仍要求作者或 admin。非同步 worker 執行前重查目前世界與 actor 角色；查作業／下載每次重驗，SSE 每次派送清除認證快取後重驗 PAT／ACL。 | 角色與 scope 矩陣、read PAT merge 403、reader HEAD 403、撤權既有回歸；地獄合併不改其他維度 refs。 |
| SSE 連線／慢客戶端 | 全站 32、每 actor 2，SseEmitter 30 秒 timeout；2 派送執行緒，每 100 ms 檢查 sequence；timeout／錯誤／完成皆退還計數、cancel ScheduledFuture。網路 IO 不持 repo／owner／operation registry 鎖。前端斷線改 500 ms 輪詢，最多 1800 次。 | 同 actor 第三連線 503、完成後 connections=0；真瀏覽器 SSE 與 abort 後輪詢。HTTP 長連線實際阻塞時間仍受反向代理／socket 設定影響，部署應設定代理傳輸 timeout。 |
| 作業記憶體／排程耗盡 | 全站 128 records，每筆 64 最新事件，每 actor 8 active，2 worker＋8 queue；結果 4 MiB、解析預算同 Hub request。完成 records 15 分鐘 TTL，每分鐘清理；滿時只淘汰已完成非下載紀錄。queue／active 超過回 503；重啟不保存作業簿。 | OperationsTest 的 150 次保留仍 128、90 事件仍 64、8 active 上限、取消與背景解析預算、TTL 刪除；HTTP active 上限 503。 |
| 圖資源放大 | limit 1–10000，core traversal 20000，labels refs 2000；JSON 共用 BoundedJson 4 MiB；不讀 snapshot/group/publication 來拼跨 repo 圖。 | core CommitGraph 上限測試、Hub graph API 權限／limit／truncation／PR annotation、前端 lane 測試與手機水平捲動截圖。 |
| ZIP 暫存／傳輸 | 維持 WorldAssembler bytes/time budget、下載 permit、owner 寫入鎖與解析預算；最多 2 個 ZIP 準備／保留 artifact，5 分鐘 TTL、成功下載清除，重啟清除殘檔；準備下載 endpoint 另限 2 concurrent。路徑由 UUID 產生，不採使用者路徑。世界至少需主世界 metadata，revision map 最多 32 維度且僅已存在 repo。 | 既有下載 bytes/time／併發／scope 測試保留；新固定 revision／async ZIP／progress／他人下載 404；Phase 4 兩版解壓與 Paper verify。 |
| 錯誤秘密洩漏 | core ErrorReport 前先收集 bootstrap、OAuth client secret、mail password、當次 Bearer／Basic 密碼、JSON token/PAT/password/secret/Authorization 已知值及已授權世界至多 10 個 hook secret。再做 PAT、Authorization、URL userinfo 與秘密欄位模式遮罩。結果 header 不含完整報告，message／operation 分別限 256／200 字元；未匹配 API／Servlet 錯誤同樣使用安全 ErrorReport；JSON body／複製用相同安全報告。一般內部 DB／框架異常給安全類別訊息，不回 stack／SQL；IO／參數錯誤保存安全完整訊息，core 報告文字上限 8192。 | 新 HTTP 已知非 token 字串遮罩；core 與前端 PAT/userinfo 模式測試；真 Hub 失敗報告含 UTC／version／dimension／id，真瀏覽器選取文字複製後備；PR／留言同步動作的完成與失敗皆驗實際專案維度。 |
| schema 升級放寬舊權限 | v5 同一 transaction＋migration version；world、memberships、world_grants、team_grants 不改。release／merged PR 固定 commit map 不改；舊 open PR 清 choices／reviews、legacy=1，合併須重新確認；確認時同步留言專案維度，保留舊釘選座標維度。舊 branch_rules 轉為 * 繼承，不把既有保護只套主世界。僅 admin 可明確設單維度覆寫或刪繼承；舊 pending root journal 僅恢復既有 refs／DB。 | DimensionMigrationTest 冪等、PRIVATE／READER 保留、review 清理及固定 release；真 Phase 4 schema 複製後啟動／讀 PR／release／私人無權限 404。 |
| CSRF／同源 | 新 POST／PUT／DELETE 沿用 JSON 1 MiB、cookie CSRF token、Origin 同源檢查，Authorization 模式仍需合法 scope／ACL。SSE 只讀、同源 CSP connect-src self、cookie HttpOnly／SameSite=Lax；沒有 query-string PAT。 | 舊 cookie／空 Authorization／跨站 Origin 測試保留；瀏覽器所有寫入與 SSE 在原 CSP 下完成。 |
| 通知 XSS／複製 | toast/banner、圖 commit text、報告 textarea 只以 DOM text 輸出；不使用 innerHTML／eval。錯誤有關閉／複製及 aria-live；通知最多 6，錯誤保留到關閉或容量淘汰。Clipboard 不可用只呈現已選取 readonly 文字。 | web lint、原留言 HTML 字串 XSS 回歸、前端狀態／遮罩測試、實機 screenshot／CSP／JS error 檢查。 |

## 驗證與部署界線

驗證命令與最終結果記錄於 [共同設計任務 2](../../docs/16-phase5-design.md)；可重跑腳本是 `hub/scripts/phase5-acceptance.sh`，原 Phase 4 自帶鎖腳本仍驗兩版全世界下載／逐格內容。新動作的 API 共用結果由 CollaborationTest 的 mutation response helper 逐一驗證，包含既有 PR／留言／release／policy／token／成員／授權動作，新作業另驗進度及 actor 邊界。

此輪實機資料庫是 SQLite；遷移 SQL 保持兩資料庫共用語法，但不把本輪未跑的 PostgreSQL 實機驗收記為通過。Containerfile 與 compose 原有 jar／前端建置、非 root uid、/data volume 已涵蓋新 schema 及 operation-downloads，不需改動；沙盒沒有 Podman，容器映像／container-smoke 由主對話驗證。

operation 是有界即時觀察介面，非持久 audit log；頁面遺失或 Hub 重啟後應重讀 PR／release／refs 的最終狀態。push 的完成代表索引及 outbox 排程，hook 遠端 HTTP 是否成功需查看 delivery。下載準備完成不保證客戶端最終保存成功，原生瀏覽器下載管理回報傳輸結果。外部 webhook outbox 原有保留政策仍由站方管理，本輪的 TTL 僅適用 operation／ZIP 暫存。

最終證據：[acceptance.json](phase5-security/acceptance.json)，含來源與受測 jar SHA-256、各 module JUnit、兩版 refs／Paper verify、SSE／輪詢與八張 screenshot 的雜湊。完整 build 315 項（Hub 74）、web 26 項均無 failure／error／skip；兩版 Phase 4 共 10 次瀏覽器流程的 CSP／console／JS error 0，六次 Paper 重開三維度內容差異 0，Phase 5 舊資料庫遷移／actor／遮罩／圖與進度通過。HTTP 診斷只保存已遮罩報告，至多 50 筆，沒有把錯誤從既有驗收斷言排除。測試 ports／鎖／副本清理已核對；中間失敗保留，界線見共同設計最終驗證。
