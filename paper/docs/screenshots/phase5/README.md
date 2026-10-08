# Paper Phase 5 UX 修正截圖

2026-10-07 修正後，以 Paper 1.21.11 伺服器與 Xvfb 真客戶端重拍本目錄八張截圖。`python3 paper/tools/phase5.py paper 1.21.11` 50／50 通過；`python3 paper/tools/phase5_screenshots.py paper 1.21.11` 的真客戶端 GameTest 通過。兩個腳本各自取得 `.work/bench.lock`，依序執行。

遊戲聊天新增斷言，確認 init／status 等操作不顯示離線 CLI 實體提示。Paper ignore GUI 原本直接顯示檔案原文，沒有另加 `#`；本次 GameTest 檢查第一行名稱並把游標移到該欄位，讓正確的 creative 註解與單一 `#` 顯示在 [ignore GUI tooltip](paper-1.21.11-ignore-gui.png)。[完成訊息](paper-1.21.11-completion.png) 沒有 CLI 提示。

八張截圖的 SHA-256、來源、受測 plugin 雜湊與驗收結果見 [acceptance.json](acceptance.json)。完整說明與 build 結果見 [Phase 5 設計／驗收紀錄](../../../../docs/16-phase5-design.md)。
