# Fabric Phase 4 實機畫面（2026-10-04）

下表 Fabric 原生留言／衝突／clone 世界圖來自 Xvfb／llvmpipe 的真正 Fabric 客戶端 framebuffer；正式 jar 與獨立驗收 mod 由 Loom production task 載入。未裁切或加工。逐張人工核對世界方塊、文字、座標、青色範圍線框與 hide；原始結果及各圖 SHA-256 見 [可攜摘要](../../phase4/results-2026-10-04.json)，完整流程與失敗原因見 [docs/14](../../../../docs/14-phase4-progress.md#fabric)。每個版本／場景只保留一張代表圖，共 14 張；檔名不帶時間戳／流水號。

| 場景 | 顯示 | hide／其他 |
|---|---|---|
| 單人 1.21.11（本輪重跑） | [literal HUD＋範圍](single-1.21.11-phase4-comments-visible.png) | [hide](single-1.21.11-phase4-comments-hidden.png)、[clone 開啟後世界](single-1.21.11-phase4-clone-open.png) |
| 單人 26.2（本輪重跑） | [literal HUD＋範圍](single-26.2-phase4-comments-visible.png) | [hide](single-26.2-phase4-comments-hidden.png)、[clone 開啟後世界](single-26.2-phase4-clone-open.png) |
| dedicated 1.21.11 完整 PR／衝突流程 | [literal HUD＋範圍](dedicated-1.21.11-phase4-comments-visible.png) | [hide](dedicated-1.21.11-phase4-comments-hidden.png)、[MERGING 清單](dedicated-1.21.11-phase4-conflict-list.png) |
| dedicated 26.2 留言補驗 | [literal HUD＋範圍](dedicated-26.2-phase4-comments-visible.png) | [hide](dedicated-26.2-phase4-comments-hidden.png)、[完整流程 MERGING 清單](dedicated-26.2-phase4-conflict-list.png) |

留言測試字串含 HTML、MiniMessage click 標籤與 § 色碼：HTML／MiniMessage 保持白色字面文字，§ 色碼由 CommentText 去除。範圍為 `(8,224,0)` 至 `(10,226,2)`，線框包住鑽石方塊；hide 後 HUD 與青框消失，世界方塊保留。部分 dedicated 圖有原版離線伺服器聊天驗證 toast，遮住右半 HUD；完整字串另可在兩版單人圖核對，沒有把 toast 當成模組錯誤或移除它。

1.21.11 完整流程先通過 24 項，但最後 verify 因 Paper strider 的 AgeLocked 移除而失敗；其畫面只證明該段 UI。後續只在測試副本先載入地獄才 init，14 項補驗與最後零差異通過。原失敗沒有改寫為成功。

兩版 `single-<版本>-phase4-clone-open.png` 已取代並刪除舊 Loading terrain 圖。原版 saves 開世界後，先等玩家與 WorldGit 握手完成、Screen／overlay 自然關閉，再只將相機玩家設為觀察者，定位 `(4.5,229,6.5)`、yaw 180／pitch 45。要求周圍 3×3 chunk 載入、金／鑽石方塊已同步、目標區段完成編譯可見及渲染佇列清空，連續三個 client tick 成立才隱藏 GUI 擷取畫面。每段等待最多 2400 client ticks，Python 截圖請求另限 300 秒，逾時回報狀態；沒有以固定 sleep 代替條件。圖中左側金方塊是本機提交，右側鑽石方塊是 PR 合併改動；兩圖皆已人工確認可見，真客戶端方塊比對與開啟前後完整 verify=0 也通過。結果見 [1.21.11](../../phase4/single-1.21.11-results-2026-10-04.json)／[26.2](../../phase4/single-26.2-results-2026-10-04.json)。

另存 Paper 1.21.11 Phase 4 回歸的 [TextDisplay show](regression-paper-1.21.11-phase4-comments-visible.png)／[hide](regression-paper-1.21.11-phase4-comments-hidden.png)：使用真 Fabric `runClientGameTest`（開發 runtime）與 Paper 私人 TextDisplay，驗證既有文字顯示沒有退化。它們是 Paper 顯示回歸的證據；上表的正式 jar runtime 驗證另有獨立 production task。Paper 驗收 helper 回傳原有畫廊路徑，本輪新圖另存於此，先前 Paper 畫廊原圖依備份逐位元還原。
