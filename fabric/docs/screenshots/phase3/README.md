# Fabric Phase 3 截圖（2026-10-02）

真正的 Minecraft 客戶端（Xvfb／llvmpipe），由 `Phase3ClientGameTest` 在單人世界擷取，原始 854×480。測試場景：門、柵欄、紅石線三個衝突區域，ours 是橡木門／橡木柵欄／紅石線，theirs 是鐵門／地獄磚柵欄／中繼器。

| 檔名 | 內容 |
|---|---|
| `1.21.11-01-conflict-list.png` | `G` 鍵開啟的衝突清單：3 個未解決區域（`!`），預覽與寫入按鈕 |
| `26.2-04-conflict-detail.png` | 選取紅石區域 #3：座標、ours／theirs 作者、狀態、紅石警示、交界提示、傳送 |
| `1.21.11-02-overlay-theirs.png` | 疊圖 theirs：世界中仍是橡木門／橡木柵欄，theirs 的鐵門以紫色半透明鬼影疊上，三個區域外框常駐 |
| `26.2-02-overlay-theirs.png` | 同上，26.2 |
| `1.21.11-03-overlay-ours.png` | 疊圖 ours：與世界相同的方塊，只剩區域外框，證明預覽不改世界 |
| `1.21.11-05-region-outline.png` | 沒有疊圖時，未解決區域的外框 |

每張截圖前後，整個測試範圍逐格比對世界未被修改；完整證據在 `.work/fabric-acceptance/phase3-*/`。
