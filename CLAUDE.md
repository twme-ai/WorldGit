# WorldGit — 給 Claude 的專案說明

## 專案現況
Minecraft 世界的 git 式版本控制，包含 Paper/Folia 插件、Fabric 模組、CLI、網頁 Hub 四端。設計文件在 `docs/`，已決定事項與路線圖在 `docs/09-roadmap-open-questions.md`，改動設計前先讀它。

## 工作方式
- 文件以繁體中文撰寫；新的決定要記進 `docs/09-roadmap-open-questions.md` 的「已決定」表，並附日期。
- **實作程式碼交給 Codex（使用者的明確要求，2026-09-30 起）**：透過 Codex 插件的 `codex:codex-rescue` 子代理（Agent 工具，`subagent_type: "codex:codex-rescue"`），任務文字前加上 `--model gpt-6.1-sol`，需要寫檔時插件會用 `--write`。主對話負責規劃、拆分任務、審查成果與整合。
- **分工**：插件的寫入沙盒（workspace-write）**不能連網、不能開連接埠、不能寫專案目錄以外的地方**（例如 `~/.gradle`）。因此：
  - Codex 只負責撰寫與離線建置/單元測試。
  - 需要網路的步驟（下載依賴、Mojang 資源、伺服器 jar）與需要跑 Minecraft 伺服器或 bot 的驗證，由主對話執行。
  - 依賴要先由主對話在有網路時抓好，並把快取放在專案內（例如 `GRADLE_USER_HOME=.work/gradle-home`、npm 快取放 `.work/`），讓 Codex 可以用 `--offline` 建置。
- 2026-09-30 之前啟動的 Phase 0 實驗由 Sonnet 5.5 子代理執行（當時的做法）。
- commit 必須以 `twme-ai <299940106+twme-ai@users.noreply.github.com>` 身分並用 SSH 簽章（repo 的 `.git/config` 已設定）；推送後確認 GitHub 顯示 verified。
