# Phase 2 Hub 驗收截圖

2026-10-01，正式 Hub jar，127.0.0.1:18097，Playwright／Chromium（SwiftShader），1280×1000 viewport、完整頁面截圖。四張 JPEG 共 **402,184 bytes**，小於 1.5 MB。

| 截圖 | 驗收內容 |
|---|---|
| [branches.jpg](branches.jpg) | 跨維度分支、預設分支、作者／head、缺少維度、snapshot 領先／落後 |
| [compare-color.jpg](compare-color.jpg) | a→b 上色：+64 金塊（綠外框）、-5 石柱／綠寶石（紅鬼影）、~4 石柱改鑽石（黃角標） |
| [compare-changed.jpg](compare-changed.jpg) | 只看變動與周圍一格，保留相同鏡頭 |
| [compare-after.jpg](compare-after.jpg) | 完整 b 版本、保留鏡頭、不帶 diff 上色 |

重跑：在專案根目錄執行 `hub/scripts/phase2-acceptance.sh`。腳本取得 `.work/bench.lock`，以固定場景建立本機暫存 Hub，結束關閉 Hub／瀏覽器；證據路徑由 `.work/hub-phase2-h/latest-run.txt` 指向，其中 `acceptance.json` 記錄四個模式的實際 commit／方塊、鏡頭、Worker、CSP／錯誤與截圖大小。

CSP 保持產品設定；測試 CJK 字型與補充 CSS 透過同源回應提供。CSP 違規、console／page error、失敗資源回應均為 0。前版本也有互動驗證；另外驗證含 `/` 的分支、深連結重載、整個 chunk 移除的鬼影及離開頁面釋放 viewer。
