# Fabric 真客戶端截圖（2026-10-01）

以 Xvfb／llvmpipe 啟動 Minecraft 1.21.11（Java 21）與 26.2（Java 25），由 Fabric client gametest 擷取真正 framebuffer，保留原始 854×480 PNG。14 張合計低於 5 MB；原始完整輪次、其他畫面與失敗紀錄保留於 `.work/fabric-acceptance/`。結果見 [Phase 1 進度](../../../docs/11-phase1-progress.md)。

每個檔名均以版本開頭，例如 [1.21.11 真實世界 diff](1.21.11-03-diff-default.png)、[26.2 真實世界 diff](26.2-03-diff-default.png)。

| 檔名尾綴（兩版各一張） | 驗證內容 |
|---|---|
| `03-diff-default.png` | 真實單人世界：同一 section 的新增石頭、移除樓梯鬼影、修改泥土虛線，+1/-1/~1 |
| `04-diff-colorblind.png` | 同一批 preview 切換色盲色票；新增藍、移除橘，明細重新建置 |
| `08-four-kinds-a.png` | 16 格合成 v2 diff：新增、移除樓梯、修改、衝突；衝突是渲染素材，尚無 merge 功能 |
| `10-lod-far-bbox.png` | 3,072 格合成 preview，6 sections，遠處 0 個明細 section，只畫區域包圍盒 |
| `11-lod-near-detail.png` | 同一 preview 靠近後 6 個明細 section |
| `14-zh-tw-chat.png` | 真實單人世界的伺服端繁中訊息、`/wgc` 繁中握手／色票／開關 |
| `paper-diff.png` | 連到真正 WorldGit Paper 插件，v2 握手後接收 3 格 diff，外框及樓梯鬼影 |

Paper 使用固定插件副本（來源時間戳 `2026-10-01T10:52:18.091997Z`、12,546,211 bytes、SHA-256 `f8d6c58581f5a57db4a24124be509a550e253c45f4492cca312127f94710a51e`），兩版各自連到 127.0.0.1:25663／25664。來源與副本詳列於驗收 result.json；同一輪使用副本，避免並行重建影響驗收。

軟體渲染只驗證功能；此次沒有硬體 GPU 或 100,000 格的效能量測。

Phase 2 的單人世界 revision preview／switch／restore 畫面另放在 [phase2/](phase2/README.md)，兩版各三張；原始 PNG 與取消恢復畫面保留於 Phase 2 驗收證據目錄。
