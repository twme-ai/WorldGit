# Phase 0 實驗

每個實驗一個資料夾，內含可重現的腳本/程式碼與 `REPORT.md`（結論、數據、建議）。
大型產物（伺服器 jar、測試世界、遊戲資源）放在 repo 根目錄的 `.work/`，不進 git。

| 資料夾 | 內容 |
|---|---|
| `00-env/` | 測試伺服器與測試世界的建立腳本；1.21.11 與 26.2 的存檔差異盤點 |
| `01-bluemap/` | 前端實驗 B：BlueMap 核心能否以 WorldGit 的資料產生 tile（結論：可以，建議直接嵌入） |
| `02-core-proto/` | core 原型：正規化、section 雜湊、JGit 映射、寫回 |
| `03-paper-poc/` | 插件端 PoC：變動偵測、線上替換 section、Folia |
| `04-deepslate/` | 前端實驗 A：deepslate 近景渲染與 diff 上色 |
