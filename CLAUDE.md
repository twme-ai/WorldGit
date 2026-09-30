# WorldGit — 給 Claude 的專案說明

## 專案現況
Minecraft 世界的 git 式版本控制，包含 Paper/Folia 插件、Fabric 模組、CLI、網頁 Hub 四端。設計文件在 `docs/`，已決定事項與路線圖在 `docs/09-roadmap-open-questions.md`，改動設計前先讀它。

## 工作方式
- 文件以繁體中文撰寫；新的決定要記進 `docs/09-roadmap-open-questions.md` 的「已決定」表，並附日期。
- **實作程式碼交給 Codex（使用者的明確要求，2026-09-30 起）**：透過 Codex 插件的 `codex:codex-rescue` 子代理（Agent 工具，`subagent_type: "codex:codex-rescue"`），任務文字前加上 `--model gpt-6.1-sol --effort xhigh`（思考強度 xhigh 為使用者要求），需要寫檔時插件會用 `--write`。主對話負責規劃、拆分任務、審查成果與整合。
- **Codex 沙盒**：使用者已在 `~/.codex/config.toml` 開啟 `[sandbox_workspace_write] network_access = true`，所以 Codex 可以連網、開本機連接埠（跑 Minecraft 伺服器與 bot），可以負責完整的實作與驗證。唯一限制是**只能寫專案目錄**（`~/.gradle` 等是唯讀）。交代任務時要求把快取放在專案內：`GRADLE_USER_HOME=.work/gradle-home`、`npm_config_cache=.work/npm-cache`、`PLAYWRIGHT_BROWSERS_PATH=.work/ms-playwright`。
- 2026-09-30 之前啟動的 Phase 0 實驗由 Sonnet 5.5 子代理執行（當時的做法）。
- commit 必須以 `twme-ai <299940106+twme-ai@users.noreply.github.com>` 身分並用 SSH 簽章（repo 的 `.git/config` 已設定）；推送後確認 GitHub 顯示 verified。
