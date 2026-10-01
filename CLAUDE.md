# WorldGit — 給 Claude 的專案說明

## 專案現況
Minecraft 世界的 git 式版本控制，包含 Paper/Folia 插件、Fabric 模組、CLI、網頁 Hub 四端。設計文件在 `docs/`，已決定事項與路線圖在 `docs/09-roadmap-open-questions.md`，改動設計前先讀它。

## 工作方式
- 文件以繁體中文撰寫；新的決定要記進 `docs/09-roadmap-open-questions.md` 的「已決定」表，並附日期。
- **實作程式碼交給 Codex（使用者的明確要求，2026-09-30 起）**：透過 Codex 插件的 `codex:codex-rescue` 子代理（Agent 工具，`subagent_type: "codex:codex-rescue"`），任務文字前加上 `--model gpt-6.1-sol --effort xhigh`（思考強度 xhigh 為使用者要求），需要寫檔時插件會用 `--write`。主對話負責規劃、拆分任務、審查成果與整合。
- **Codex 沙盒**：使用者已在 `~/.codex/config.toml` 開啟 `[sandbox_workspace_write] network_access = true`，所以 Codex 可以連網、開本機連接埠（跑 Minecraft 伺服器與 bot），可以負責完整的實作與驗證。唯一限制是**只能寫專案目錄**（`~/.gradle` 等是唯讀）。交代任務時要求把快取放在專案內：`GRADLE_USER_HOME=.work/gradle-home`、`npm_config_cache=.work/npm-cache`、`PLAYWRIGHT_BROWSERS_PATH=.work/ms-playwright`。
- **Codex 撞到 token／用量限制時，等限制解除再繼續，不改用 Sonnet（使用者要求，2026-10-01，取代先前「改由 Sonnet 5.5 接手」的規則）**：記下錯誤訊息中的解除時間，到時用同一份任務說明（`.work/codex-prompts/`）加上接續說明（Codex 已完成的檔案與 `~/.claude/plugins/data/codex-openai-codex/state/WorldGit-*/jobs/<job>.log`）重新交給 Codex。「模型容量已滿」等暫時性錯誤直接重送 Codex。
- 若本 session 沒有載入 `codex:codex-rescue`，可直接執行插件腳本：`node ~/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs task --background --write --model gpt-6.1-sol --effort xhigh --cwd <repo> --prompt-file <檔案>`。背景任務不會通知，要監看 `task-worker` 程序。
- 2026-09-30 之前啟動的 Phase 0 實驗由 Sonnet 5.5 子代理執行（當時的做法）。
- commit 必須以 `twme-ai <299940106+twme-ai@users.noreply.github.com>` 身分並用 SSH 簽章（repo 的 `.git/config` 已設定）；推送後確認 GitHub 顯示 verified。
