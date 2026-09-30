# WorldGit — 給 Claude 的專案說明

## 專案現況
Minecraft 世界的 git 式版本控制，包含 Paper/Folia 插件、Fabric 模組、CLI、網頁 Hub 四端。設計文件在 `docs/`，已決定事項與路線圖在 `docs/09-roadmap-open-questions.md`，改動設計前先讀它。

## 工作方式
- 文件以繁體中文撰寫；新的決定要記進 `docs/09-roadmap-open-questions.md` 的「已決定」表，並附日期。
- **實作程式碼時，交給 Sonnet 5.5 子代理（Agent 工具，`model: "sonnet"`）撰寫**；主對話負責規劃、拆分任務、審查子代理的成果與整合。這是使用者的明確要求。
- commit 必須以 `twme-ai <299940106+twme-ai@users.noreply.github.com>` 身分並用 SSH 簽章（repo 的 `.git/config` 已設定）；推送後確認 GitHub 顯示 verified。
