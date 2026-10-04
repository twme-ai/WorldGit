# Fabric Phase 4 安全審查（2026-10-04）

範圍：兩版 Fabric remote 命令、使用者憑證、通知、可選 comments-v1 與客戶端 HUD／範圍描邊。沿用 platform-api remote／core publication 與 Fabric live coordinator。實機結果與未完成項目以 [docs/14](../../docs/14-phase4-progress.md#fabric) 為準；原始結果與來源／產物／截圖雜湊收錄於 [可攜驗收摘要](phase4/results-2026-10-04.json)。本文件記錄程式邊界，不把人工檢視當成壓力或故障注入測試。

| 邊界 | 控制與證據 |
|---|---|
| 權限 | remote list／fetch／pr list/view／comments 使用 read op，remote 設定／push／pull／pr create／comment 使用 write op。WgCommands 沿 owner／console／op 判定；單人 owner 無需開作弊。通知只到 write 權限玩家與 console。撤銷讀取權限後 tick 清掉留言；沒有網頁 approve／merge 或遊戲 force 入口。 |
| PAT | PlatformCredentials 只讀環境或 config 直接子檔案，POSIX 普通 600 檔；拒絕 symlink／越界。per-save remotes.yml 只存名稱／非秘密 URL，core 驗 URL 不含 userinfo。token 不進世界／聊天／logger，異常只對應固定 i18n，YAML parser 不攜原始內容或 cause。RemoteConfigTest 驗非法 token 欄位／型別與 parser 遮罩、環境優先與檔案權限。 |
| 網路 | HttpClient／JGit 在 repo 背景 queue，結果只透過 server executor 存取 sender／玩家 API。HubClient 沿共用限制：完整請求 deadline、body 上限、禁任意 redirect、驗 endpoint；401／403／404／409／429／timeout／unreachable 固定訊息。一次 sender 只一個 remote 操作，停止後抑制回覆，離線舊 player object 不接新連線。 |
| pull | sender UUID／console 綁 120 秒一次性 code；確認消耗 code、重新 fetch、固定 URL／完整 remote tips、本地 HEAD 與分支。真正套用在既有 coordinator／repo queue／全組鎖內再次驗證，不持編輯鎖等網路。dirty／MERGING／PARTIAL 沿 core 拒絕；metadata 預檢使用與快照相同的 portablePacks（只移除平台標記），真資料包增刪／Disabled／順序仍拒絕；live apply 沿 ApplyBudget、EditGuard、玩家保護、光照／IO／verify／HEAD barrier，#46 不執行鄰居更新。 |
| 通知 | dedicated receiver 預設關閉／loopback，沿 WebhookReceiver HMAC constant-time、雙重持久去重、header/body/deadline、2 workers／8 queue／速率上限。事件只匹配當前 remote 世界與分支，再 fetch 完整 publication；失敗最多五次 2/4/8/16/32 秒退避、fetch coalesce。poll 間隔 0 或至少 60 秒。單人完全不建 receiver，即使設定 enabled。通知無 apply 路徑。 |
| 留言文字 | Hub DTO 經 CommentText，作者 32／摘要 240 Unicode code points（截斷加省略號）；去控制／雙向字元／§ 色碼。MiniMessage 僅格式化 i18n，Hub 文字透過 literal argument；HUD 使用 Component.literal，HTML／MiniMessage 都是字面。沒有 click／hover event 解析。 |
| 留言 wire | 握手需 comments-v1＋讀取權限，專屬 per-player queue。每批 64／128 KiB／8 parts、每包 28,000；座標／維度／UUID／長度驗證、五秒組包超時、一次僅一個 pending batch、重複 part/id／錯 count／trailing bytes 拒絕。snapshot id floor 防舊包重建，request id 防 hide／換維度後 HTTP 回覆復活；斷線 reset。CommentsProtocolTest 包含多包亂序、literal/range、晚到 floor、上限與錯封包。 |
| 顯示資料 | 僅 client HUD／PreviewScene GPU 線框，無 server entity／block／saved-data。每 tick 距離裁切、近者排序與數量上限，畫面高度限制文字；hide、換維度、撤權、退出／關閉世界清理與 close GPU。原版 client show 固定說明不支援空間顯示，仍可讀文字清單。Paper TextDisplay 原行為保留。 |
| 打包 | Jackson 與 platform-api 同版本 jar-in-jar，由 Loader 管理；check 驗三組必要 class／nested 宣告與正式 jar 不含 fixture。沒有改成獨立 Gson REST 實作或 relocate 共用 DTO；其他模組若帶不相容 Jackson，不保證任意組合相容。正式 dedicated 啟動與 client 實測／classpath 差異見進度報告。 |

server PAT 代表一個 Hub 帳號：遊戲的 op／owner 是本地授權，Hub 仍以 PAT scopes／world ACL／protected branches 拒絕寫入；本次沒有每玩家 PAT。可讀伺服器 config／程序環境的人屬信任管理員。一般 config reload 不重建 RemoteCommands／receiver，修改 remote 環境／檔案名稱／poll／webhook 設定需重啟；每次請求會重新讀 credentials 檔，換 PAT 可立即生效。

未實跑公開 TLS／反向代理、任意第三方 Jackson 模組衝突、socket flood、磁碟故障或任意時刻 kill／fuzz；loopback、平坦世界、llvmpipe／Xvfb 結果不代表大型自然世界與真 GPU。範圍線框沿既有渲染管線，單個極大範圍不生成逐格幾何。Paper／Folia 的既有熱卸載清理邊界仍沿其安全報告，不由 Fabric 改寫。
