# Phase 1 四端端到端驗收（tools/e2e）

`phase1_e2e.py`：Paper／Folia 插件 → CLI → Hub → 模組端封包，用同一份 repo 比對 commit id 與格子座標。

```bash
timeout 3000 python3 tools/e2e/phase1_e2e.py --platform paper            # 1.21.11 與 26.2
timeout 3000 python3 tools/e2e/phase1_e2e.py --platform folia --versions 1.21.11
```

前置：`./gradlew :paper:plugin:jar :cli:fatJar :hub:bootJar`、`.work/servers/<平台>-<版本>/`、`.work/assets/`（Hub 的 client jar 資源）、
`hub/web/node_modules`（playwright-core）、系統 Chrome（`/usr/bin/google-chrome`）。腳本自己取得 `.work/bench.lock`，不要再包外層 flock。

每個版本的流程：

1. 複製伺服器＋合成平坦世界（`paper/tools/ScaleFixture.java`），裝插件，`/wg init`，模組 bot（`wgbot.js … mod`）放兩格、挖一格，
   `/wg status --show`、`/wg diff --show`，`/wg commit`。bot 把收到的 `worldgit:*` payload 原樣寫檔（`WG_BOT_DUMP`），
   `DecodeDump.java` 用 `protocol` 的 `Protocol`／`BatchAssembler` 解出格子與描邊。
2. 伺服器停止後，CLI `log`／`diff HEAD~1 HEAD --blocks` 讀同一個 `.worldgit/` repo。
3. 本機 Hub（127.0.0.1:8197、暫存資料目錄）：對三個維度 repo 做一般 `git push`；API 讀回 snapshots／commit 詳情／WGDF diff；
   `hub_shots.mjs` 用 Playwright 開 commit 3D 頁面截圖，並從頁內 viewer 的 diff 資料取出格子。
4. 斷言：插件 HEAD commit id＝CLI log＝Hub snapshots／commit API；CLI diff 格子＝模組封包格子＝Hub WGDF 格子＝3D 頁面內的 diff 格子。

結果與截圖在 `.work/phase1-e2e/<平台>-<版本>/`（`result-<平台>.json` 為總表）；精選截圖在 `docs/screenshots/phase1-e2e/`。
真正的客戶端畫面（Xvfb／llvmpipe）由 `fabric/tools/accept-paper.py` 驗證，見 `docs/11-phase1-progress.md`。
