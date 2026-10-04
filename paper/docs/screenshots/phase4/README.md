# Phase 4 真客戶端截圖

日期：2026-10-03。以真 Paper／Folia、Hub jar＋SQLite 與對應版本 Fabric 測試客戶端取得；不是示意圖。腳本是 `paper/tools/phase4.py --screenshots`，測試客戶端 fixture 只放在 `paper/tools/fixtures/`，不加入正式 Fabric jar。

| 平台 | 顯示留言 | hide 後 |
|---|---|---|
| Paper 1.21.11 | [visible](paper-1.21.11-phase4-comments-visible.png) | [hidden](paper-1.21.11-phase4-comments-hidden.png) |
| Paper 26.2 | [visible](paper-26.2-phase4-comments-visible.png) | [hidden](paper-26.2-phase4-comments-hidden.png) |
| Folia 1.21.11 | [visible](folia-1.21.11-phase4-comments-visible.png) | [hidden](folia-1.21.11-phase4-comments-hidden.png) |
| Folia 26.2（定時 fetch） | [visible](folia-26.2-phase4-comments-visible.png) | [hidden](folia-26.2-phase4-comments-hidden.png) |

釘選為 `minecraft:overworld (8,224,0)`，範圍為 `(8,224,0)` 至 `(10,226,2)`；文字錨點在方塊上方，青色粒子畫出範圍。相機在 `(8.5,224,-5.5)`，驗收用 owner-thread 飛行／無重力設定固定位置；截圖期間恢復 vanilla tick，避免凍結時 Display render state 不更新。正式留言功能不會更改玩家飛行狀態。

文字包含 `<red><click:run_command:/op bad><script>alert(1)</script>`，畫面顯示其字面；legacy 色碼已移除。客戶端另檢查 Component 沒有 click event。hide 圖沒有文字；粒子是短暫效果，停止發送後仍可能有少量尚未消失的粒子。截圖不能單獨證明另一位玩家不可見，這項由雙 bot 收到的 wire entity id 與伺服器真正 TextDisplay id 比對驗證。

四組最新截圖均已在第二輪逐張人工檢查。結果與 SHA-256 見 [可攜驗收摘要](../../phase4/results-2026-10-03.json)，流程、失敗紀錄與界線見 [docs/14 Paper／Folia](../../../../docs/14-phase4-progress.md#paper-folia)。早期相機落下／文字未渲染的截圖不作正式證據，原執行紀錄已標示人工檢查失敗。
