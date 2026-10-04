# Paper／Folia Phase 4 安全審查（2026-10-03）

第二輪驗收與文件結案：2026-10-04 UTC。

依決定 #23 以公開服務入口的標準檢查。範圍：遊戲遠端指令、Hub REST client、PAT／webhook secret、內建 webhook 接收、座標留言。產品程式在 paper/common、兩版 adapter 與 platform-api；Fabric 遠端功能沒有在本次接線。

## 威脅、控制與界線

| 威脅 | 實作控制 | 驗證 |
|---|---|---|
| PAT 進 config／世界／log | YAML 只接受非秘密欄位；PAT 環境優先，否則插件資料夾普通非 symlink 的 600 credentials YAML；不讀玩家 home、不寫世界；core transport Secret 遮罩，REST 錯誤只回分類，不回 body/cause/header | CredentialsAndTextTest、RemoteConfigTest；真錯誤 PAT 場景 |
| 憑證被 redirect 帶到他站 | git／REST 不 redirect；Hub URL 禁 userinfo/query/fragment；REST 路徑由驗證過 owner/world/UUID 組成 | HubClientTest（302 不跟隨）、既有 core 憑證／URL 測試 |
| 憑證目標被管理者改動 | worldgit.command.remote 是管理權限，僅授予可信管理者。環境 PAT 沿 core 契約對所有 remote 優先生效；若需限定目的地，清空該環境變數，改用 600 credentials YAML 按 scheme＋host＋port 的 origin 指定。PR／comment 權限本身不能變更 remote URL | 權限 mapping／plugin.yml 測試；Credentials.resolve 的 origin 契約 |
| 私人存在性或伺服器錯誤洩漏 | 401/403/404/409/429 等只回固定 i18n；404 為「不存在或不可讀」，不輸出 Hub body；JSON parser exception 不附原文 | HubClientTest 各狀態與敏感 body |
| 網路阻塞 tick／無界 REST body | repo 背景 queue 讀寫 remote；玩家結果用 EntityScheduler、console/global 用 GlobalRegionScheduler；完整 REST future deadline（含 body），自訂 subscriber 4 MiB 上限；JSON 深度 40、字串 32768、數字 32；PR 最多 1000／留言 512，單頁 100／offset 10000 | body 慢傳／超大 body／分頁測試、真 tick probe；git timeout overload 保留舊 API 預設 |
| webhook 慢 headers／slowloris／連線數與執行緒 DoS | 預設關閉／loopback；ServerSocket backlog 16、2 workers＋8 queued、超額關閉；每條接受連線從 headers 前起計 5 秒 deadline，socket timeout 5 秒；headers 16 KiB，body 預設 64 KiB（最大 256 KiB）；Connection close，不 keep-alive，不 chunked；每分鐘全域預設 60（包含錯誤認證） | WebhookReceiverTest；端到端真 Hub POST |
| webhook 注入／簽章偽造 | 精確路徑、HTTP/1.1 POST、Content-Length、拒絕重複 headers／折行／Transfer-Encoding；raw body HMAC-SHA256，64 hex 固定長度與 MessageDigest.isEqual；先驗簽再解析 JSON、世界／分支匹配 | 簽章、GET／超大 body、未知事件／過期測試 |
| replay／竄改未簽名 delivery header | delivery UUID 去重之外，簽章 body 的 event id 也持久去重。Hub 的 event id 與 delivery id **不同**，不要求兩者相等。簽章 at 限 24h／未來 5min；4096 keys 滿額 503，不能淘汰未過期項目；持久化先於接受，enqueue 失敗移除供重試 | 相同 delivery、改 delivery 重播、receiver 重啟回歸 |
| notification 不完整 publication／擅自改世界 | receiver 只 enqueue 事件；比對目前預設 remote 的 owner/world/current branch，fetch 使用 core 全組 publication 驗證，成功且 behind 才提示；失敗有限退避；定時 fetch 同流程，永不 apply | 真 webhook 合併後世界／HEAD 未變；FF 確認後才變 |
| 預覽與確認之間的競爭／越權 | 120 秒、sender 綁定、一次性確認碼；命令權限每次檢查；確認重新 fetch，tip/URL 不同拒絕；core 的 group lock 內再驗 URL／本地 HEAD／dirty；沿既有編輯鎖、保護與 verify barrier | 真遠端競爭、非 FF、MERGING；權限 YAML／mapping 測試 |
| Hub 留言造成命令／HTML／MiniMessage／雙向字元注入 | Component.text，內容不反序列化；控制/雙向格式/legacy 色碼移除，Unicode 摘要截斷；PR 點擊連結僅由本機 URL＋Hub UUID 生成 | CommentText 測試、真客戶端 component 文字與 click event 檢查／截圖 |
| 留言洩漏給其他玩家／capture 汙染 | hidden by default，僅請求者 owner showEntity；nonpersistent，兩版 capture 再排除插件 text/block display tags；hide/離線/換維度清除並作廢 REST request id | 雙 bot 可見性、DisplayRequests 回歸、capture tag 測試、離線 verify |
| 遠端座標生成無限地形／顯示負載 | pin 維度／座標／完整範圍檢查；目前維度已載入 chunk 才 spawn，最多 64 TextDisplay；範圍框只有 player particles，每秒最多 384 點／64 格距離；PR／留言清單最多 20 列，所有 Hub 顯示欄位截斷 | DTO 邊界與顯示實機 |

共用 client 的 Jackson BOM 與 Hub 修補版本一致（2.21.7），解析仍有獨立 body／depth／string 預算；這次沒有宣稱已全面掃描所有 Paper／Folia 自帶依賴。

## 部署與恢復

secret 需 32 字元以上高熵值，放環境或插件資料夾 `webhook.secret`（600），與 Hub 設定相同；不能寫 config.yml／世界／repo。receiver 的 replay YAML 不含 secret，部署備份時應保留。重放窗口 24h，接收新事件超過上限先拒絕，待過期或管理者確認後處理；不要直接刪除仍有效的 replay 檔來繞過滿額。

公開入口應以 TLS reverse proxy 對接 loopback，保持 body/header/connection timeout 與速率限制；本機最小端點沒有 TLS 或通用 HTTP 功能。代理需轉送原始 body 與 signature/delivery headers，使用 Content-Length；不支援 chunked。錯誤請求不回解析原因／世界資訊。HTTP 明文 outbound URL 是管理者明確配置（loopback 驗收亦使用）；公開 Hub 應使用 HTTPS。

一組世界 repo 以背景 queue 序列化。git timeout 是單 transport/socket timeout，跨多個 pack 的整組作業總時間可能較長；伺服器 tick 不等待，但同組後續 repo 操作需排隊。定時／通知不做 commit，不變更活世界；fetch journal PARTIAL 後可按 core 契約重試。

Paper 停用同步移除 display；Folia 停服時 owner scheduler 可能已停止，display 不持久化，隨世界卸載消失。**Folia 不支援的第三方熱卸載沒有跨 region 同步刪除保證**；一般 hide／離線／換維度走真正 owner remove。這是平台關閉生命週期的界線，不能為清理而在非 owner 強制 NMS 刪除。

## 證據與待補項目

單元與真伺服器最新結果、失敗歷史、截圖與清理狀態集中在 [docs/14 Paper／Folia](../../docs/14-phase4-progress.md#paper-folia)；[可攜驗收摘要](phase4/results-2026-10-03.json) 保存實際結果、來源與 SHA-256。第二輪完整 build 的 254 個 JUnit 測試全通過；四組 Phase 4 真 Hub／遊戲場景各 20 項通過，包含 notification 未改活世界／HEAD、明確確認與 stale tip 拒絕、錯 PAT、留言隱私／注入／清理／全組 verify。八張最新版 show／hide 圖已逐張人工檢查，隱私另由雙 bot 封包斷言驗證。

第二輪發現 26.2 live gamerule 的主世界別名仍取磁碟舊值，修正為與維度名稱相同的活資料；完整回歸仍檢查 gamerule／metadata，沒有以 ignore 規則避開差異。四平台 Phase 2 各 39 項、Phase 3 各 90 項、真 Fabric 客戶端 interop 各 17 項全通過。背景程序已結束、驗收 ports 全關閉、run 世界與秘密已清除；凍結 jar 移除前保存雜湊。最終結果以可攜摘要與 docs/14 的完整矩陣為準。未測的公開 TLS／高並行 socket flood／磁碟故障任意時點 kill，不能由 loopback 控制 fixture 外推。對固定 header/body/queue/deadline 的測試不等於完整網際網路滲透測試。

實體顯示 API 的依據：[Paper display entities](https://docs.papermc.io/paper/dev/display-entities/)；REST lifecycle 的依據：[JDK 21 HttpClient](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html)。本次以完整有界 subscriber＋future deadline，避免只收到 headers 就結束逾時計時。
