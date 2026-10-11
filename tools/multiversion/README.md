# 多版本研究與驗收工具

目標版本、資料模型與平台交接見 [docs/19](../../docs/19-multiversion.md)。工具只在 `.work` 下載原版 jar／mappings、產生世界及保存證據；不得把這些產物加入 Git。

需要 Java 21、Java 25、Python 3／PyYAML、curl，以及已安裝依賴的 `hub/web`。真服玩家檢查使用 `paper/tools/wgbot.js`；Hub 瀏覽器檢查使用 Playwright 與 Google Chrome（可用 `CHROME` 指定）。

- `reports.py`：下載並驗證精確版本，在本機執行原版 data generator。
- `catalog.py`：由報告建立 registry、state defaults、耐久物品等 YAML；不產生反編譯原始碼。
- `fixtures.py`：啟動真原版十版，產生箱子、附魔／名稱／自訂資料、BE、實體與 biome。既有 fixture 預設保留，`--replace` 才重建。
- `acceptance-pure.py`：九個來源在任何目標開服前升到 26.3 再降回，嚴格比較全部正規化 component。
- `acceptance-dfu-targets.py`：1.20.1 真實 terrain／entity／level 分別轉到九個較新目標，驗證舊混淆版及 26.x 的原版 DFU 入口與目標 codec。
- `acceptance.py`：九組升級、五組降級，檢查損失確認、目標真服、箱子、玩家進出 chunk 及 runtime 狀態。
- `acceptance-extra.py`：原版 forceUpgrade 對照、真服往返、外部升級、跨版 diff／switch／restore／merge、永久 audit 與 export 預覽。
- `platform-probe.py`：Java 21 CLI 呼叫 Java 25 DFU，以及舊版 Paper 載入 Java 21 bytecode 插件；不代表舊版 WorldGit adapter 已完成。
- `hub-acceptance.py`：跨版摘要、3D wire、實體、tile／LOD、merge preview 與前後切換的真瀏覽器驗收。

先以 `GRADLE_USER_HOME=.work/gradle-home` 執行完整 build，外包 `flock .work/bench.lock`，並加 `--configure-on-demand --max-workers=1`。上述 Python 驗收工具自行取鎖，**不要外包 flock**，重型 runner 必須依序執行。

可用 `WORLDGIT_MV_JAR` 固定待驗 CLI artifact、`WORLDGIT_MV_RESULTS` 指定證據目錄。長 runner 使用背景 log／PID，例如：

```sh
nohup python3 tools/multiversion/acceptance-extra.py > .work/mv-extra.log 2>&1 & echo $! > .work/mv-extra.pid
```

`acceptance-extra.py --operations-only` 只跑外部升級／跨版操作，結果標記 scope=operations；可在新 build 後先檢查操作情境，完整驗收仍須另外跑全部來源矩陣。失敗時保留操作副本路徑於 retainedCases，便於查看 journal。

啟動下一個 runner 前確認前一個已結束。`acceptance-extra.py --resume-from <version|operations>` 只接受相同 artifact SHA-256，並要求此前每個來源的四項檢查全數通過。

`SemanticProbe.java` 預設輸出四個代表 chunk 的所有 component hash，`--ticks` 可檢查原始正規化排程。真服往返的 1.21.11／26.x 測試副本移遠出生點、停服前解除強制載入，開服先凍結再載入代表區域；tick 欄位仍完整比較。純轉換測試使用原始 fixture，不需要這個時序控制。

Hub 使用 `FixtureWindow.java` 只裁掉獨立副本的外圍生成地形，保留四個代表 chunk 的原始 NBT；CLI／原版轉換矩陣仍使用完整 fixture。Hub 解碼與候選物件上限保持原值。

跨版本套用案例先驗證不同 DataPacks 仍被拒絕，再以 `FixturePacks.java` 手動同步測試副本的未啟用實驗包清單；Enabled 必須只有 vanilla，未知 Disabled 包會拒絕。這個設定控制不改 core 預檢。
