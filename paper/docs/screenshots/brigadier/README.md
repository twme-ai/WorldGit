# Paper Brigadier 真客戶端截圖（2026-10-04）

使用各版真正 Fabric 客戶端＋Xvfb／llvmpipe 連 Paper 1.21.11／26.2，操作原生聊天列與補全清單。兩版各 9 張，已逐張檢視；伺服器維持 MERGING，PR 由真正 Hub 建立，玩家完成 op→deop。未修改 Fabric 原始碼，fixture 由暫時 Gradle init script 加入 gametest classpath。

兩版使用相同插件 SHA-256：`e966ea25dfffd1a2b2db8fd4f56bf02644794ca631fb71793d7d3e763fe989a3`。原始結果與 client log 在 `.work/brigadier/paper-26.2-1791107122/`、`.work/brigadier/paper-1.21.11-1791107299/`；各結果記錄指令樹、native 參數型別、server suggestions／tooltip、錯誤 cursor 與非 op 樹。

完整回歸與最後 build 的 [可攜摘要](results-2026-10-04.json) 包含各圖與原始 log 雜湊。首次完整 16 組有 15 組通過，一組因等待舊 `/wg merge` 用法逾時；改驗原生錯誤 cursor 及完整 MergeState 不變後，完整補驗 Paper 1.21.11 Phase 3 通過。最終 16 組與全專案 build 均通過。

截圖後補上非空間衝突的空座標 tooltip 保護，最終 jar SHA-256 為 `8e8b00a4d098e0771cba871ce23d0715e2093e38fdedc3b2863abde0d61f3243`。與截圖 jar 比對，唯一不同 entry 為 CommandSuggestions.class；這個邊界由單元測試覆蓋，完整補驗使用最終 jar。其餘回歸與此處畫面保留原 jar 的證據。

| 輸入 | 驗證內容 | 1.21.11 | 26.2 |
|---|---|---|---|
| `/wg ` | 主指令補全；完整樹另由 dispatcher 斷言 | [截圖](paper-1.21.11-root.png) | [截圖](paper-26.2-root.png) |
| `/wg switch ` | 分支與 revision；短 hash／最後訊息 | [截圖](paper-1.21.11-switch-tooltip.png) | [截圖](paper-26.2-switch-tooltip.png) |
| `/wg resolve ` | MERGING 區域；維度、兩端座標、格數、ours／未解決 | [截圖](paper-1.21.11-resolve-tooltip.png) | [截圖](paper-26.2-resolve-tooltip.png) |
| `/wg restore HEAD --box ~ ~ ~ ` | 相對座標與下一個方塊座標提示 | [截圖](paper-1.21.11-restore-relative.png) | [截圖](paper-26.2-restore-relative.png) |
| `/wg restore HEAD --box ~ ~ ~ ~3 ~4 ~5 --dry-run` | 兩組座標上色與尾端 literal | [截圖](paper-1.21.11-restore-complete.png) | [截圖](paper-26.2-restore-complete.png) |
| `/wg pr view ` | Hub PR 編號、標題與 open 狀態 | [截圖](paper-1.21.11-pr-tooltip.png) | [截圖](paper-26.2-pr-tooltip.png) |
| `/wg log nope` | 原生整數錯誤；紅字及位置 | [截圖](paper-1.21.11-integer-error.png) | [截圖](paper-26.2-integer-error.png) |
| `/wg restore HEAD --box ~ nope ~` | 原生座標錯誤；紅字及位置 | [截圖](paper-1.21.11-coordinate-error.png) | [截圖](paper-26.2-coordinate-error.png) |
| `/wg ` | 同一玩家 deop 後只有 clear／help／log | [截圖](paper-1.21.11-no-permission-root.png) | [截圖](paper-26.2-no-permission-root.png) |

重跑（各腳本自持 bench.lock，finally 關閉客戶端／Xvfb／伺服器／Hub 並清除副本）：

```sh
python3 paper/tools/brigadier.py 26.2
python3 paper/tools/brigadier.py 1.21.11
```

首次幾輪曾因未載入 chunk、26.2 的 GUI／維度型別更名、fixture 捲動超界與單字元選項滑鼠越界失敗，均已修正。真客戶端也揭露雙 ask_server 分支會取消彼此請求、使補全不完成；目前只有數字分支發出請求，#編號保留執行相容性。衝突座標改為精簡兩端格式；原版 tooltip 會將換行顯示為控制字元方框，因此使用分隔符。一個長流程曾以 SIGTERM（143）中止；上述最後兩輪均完整通過，先前失敗／中止紀錄保留於 `.work/brigadier/`。
