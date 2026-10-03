# Fabric ↔ Paper／Folia Phase 3 截圖（2026-10-02）

真正 Fabric 客戶端透過原版連線連到 Paper／Folia，由 Xvfb／llvmpipe 擷取原始 854×480 framebuffer。流程與各輪結果見 [docs/13](../../../../docs/13-phase3-progress.md#fabric--paper-實機對接)。執行入口為 `python3 fabric/tools/accept-paper-phase3.py <paper|folia> <1.21.11|26.2>`，腳本自行取得 bench.lock。

檔名以前綴 `<平台>-<版本>-` 區分，每組有四張：

| 後綴 | 內容與對照 |
|---|---|
| `01-conflict-list.png` | 1 區、5 格的跨 chunk 衝突；bounds、作者、choice／resolved 與伺服器持久化清單一致 |
| `02-ghost-theirs.png` | theirs 鬼影與紫色外框；ours／theirs／base 的完整 state 與 BE bytes 都逐格比對候選快照，預覽前後世界 hash 相同 |
| `03-set-blocks.png` | Set blocks 寫入 theirs 後仍顯示 unresolved，清單即時更新；完整 section hash 與 theirs 相同 |
| `04-200-regions.png` | 200 區域清單與分頁；實際分為 2 個 payload，重連後重新收到同一清單 |

| 平台／版本 | 衝突清單 | Ghost | Set blocks 後 | 200 區域 |
|---|---|---|---|---|
| Paper 1.21.11 | [清單](paper-1.21.11-01-conflict-list.png) | [Ghost](paper-1.21.11-02-ghost-theirs.png) | [unresolved](paper-1.21.11-03-set-blocks.png) | [分頁](paper-1.21.11-04-200-regions.png) |
| Paper 26.2 | [清單](paper-26.2-01-conflict-list.png) | [Ghost](paper-26.2-02-ghost-theirs.png) | [unresolved](paper-26.2-03-set-blocks.png) | [分頁](paper-26.2-04-200-regions.png) |
| Folia 1.21.11 | [清單](folia-1.21.11-01-conflict-list.png) | [Ghost](folia-1.21.11-02-ghost-theirs.png) | [unresolved](folia-1.21.11-03-set-blocks.png) | [分頁](folia-1.21.11-04-200-regions.png) |
| Folia 26.2 | [清單](folia-26.2-01-conflict-list.png) | [Ghost](folia-26.2-02-ghost-theirs.png) | [unresolved](folia-26.2-03-set-blocks.png) | [分頁](folia-26.2-04-200-regions.png) |

截圖只作畫面存證；通過依據還包括真 UI 點擊、client／server／候選快照比對、Resolve／傳送／continue 與最終離線 verify。箱子完整資料已接收並比對，Ghost 未加入特殊 BE renderer。
