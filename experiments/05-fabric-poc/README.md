# 05-fabric-poc：Fabric 鬼影 diff 與 Paper 握手

完整結果、限制與 API 查證見 [REPORT.md](REPORT.md)。原始客戶端截圖在 [screenshots/](screenshots/)，精簡驗證 log 在 [results/](results/)。

## 建置

在專案根目錄執行：

```bash
bash experiments/05-fabric-poc/tools/build.sh
```

需求：Gradle 9.6.1（PATH 中的 `gradle`）、Java 21 與 Java 25、Python 3、網路。Gradle 本身使用 Java 25；protocol／Paper／1.21.11 使用 Java 21 toolchain，26.2 使用 Java 25。建置會執行零外部測試框架的協定檢查（10 萬格、亂序重組、截斷／多餘資料、重複封包、大 palette）。

輸出：

| 路徑 | 用途 |
|---|---|
| `.work/fabric-poc/build/paper/libs/worldgit-fabric-poc-paper.jar` | 同一個插件 jar 跑兩版 Paper |
| `.work/fabric-poc/build/client121/libs/worldgit-fabric-poc-1.21.11-0.0.5.jar` | 1.21.11 的 remapped 模組 |
| `.work/fabric-poc/build/client262/libs/worldgit-fabric-poc-26.2-0.0.5.jar` | 26.2 的不混淆模組 |
| `.work/fabric-poc/build/protocol/libs/protocol-0.0.5.jar` | 不依賴 Minecraft 的 Java 21 協定 |

Gradle 快取是 `.work/gradle-home`；其中 `caches/fabric-loom` 連到 `.work/fabric-poc/loom-cache`。專案快取、Minecraft assets、native library、runClient 目錄、log 也在 `.work/fabric-poc/`。每次 Gradle 使用 `--max-workers=1`。請勿同時執行兩次重跑。

## 一鍵重跑實際客戶端

```bash
# 建置後依序測兩版
bash experiments/05-fabric-poc/tools/run-all.sh

# 或只重跑某版（已建置時可直接用 Python）
python3 experiments/05-fabric-poc/tools/run.py 1.21.11
python3 experiments/05-fabric-poc/tools/run.py 26.2
```

另需 `/usr/bin/xvfb-run`、Mesa DRI、Node.js，以及 00-env 已備妥的 `.work/servers/paper-{1.21.11,26.2}/`、`.work/worlds/<版>/baseline/`、03-paper-poc 已備妥的 `.work/bot/node_modules/`（26.2 使用先前的 minecraft-data 協定 776 hack）。

腳本複製 baseline 到 `.work/fabric-poc/servers/paper-<版>/`，每次重跑會重建這個**實驗複本**。伺服器只綁 `127.0.0.1`，1.21.11 使用 25631、26.2 使用 25632，`online-mode=false`，離線帳號 `WgFabric` 是該複本的管理員。它依序驗證握手、未裝模組 bot 的逾時、64／10,000／100,000 格、clear、截圖及幀時間，最後退出客戶端、Xvfb 和伺服器；例外也走 `finally` 清理。不要把測試插件裝進正式世界。

原始 log 與每版 summary 在 `.work/fabric-poc/results/<版>/`；summary 也寫入本目錄 `results/<版>.json`。截圖直接由 Minecraft `Screenshot.takeScreenshot` 讀取遊戲 framebuffer 寫入本目錄，沒有另外生成示意圖。效能量測使用 1280×720、6 chunk、關閉 vsync、Mesa 軟體渲染與 3 個 CPU 核心。

## 手動使用

把對應模組與表列版本的 Fabric API 放入真正 Fabric 客戶端的 `mods/`，共用插件放入 Paper 的 `plugins/`。加入伺服器後等待約 1–3 秒握手，再以管理員玩家執行：

```text
/wgpoc diffdemo
/wgpoc clear
/wgpoc diffdemo 10000
/wgpoc clear
/wgpoc diffdemo 100000
/wgpoc clear
```

`n` 是**總格數**，範圍 4..100000；依序交錯分到四種 diff。預設 64 格分成四排，涵蓋多個 section；大量示範填入 64×64 的多層區域。每 tick 建立最多 1,000 格、還原最多 2,000 格、送最多 2 個 diff 封包。前一組示範要先 clear 才能產生下一組。

`diffdemo` 為建立測試素材，會實際修改實驗世界，移除格會變成空氣；`clear` 同時清除客戶端 buffer 並還原示範前的 block state。**正式唯讀預覽只能重用訊息與 renderer，不可使用 fixture 寫世界的程式。**此 PoC 的還原只保存 block state，並非 block entity／實體的通用 restore。

## 程式結構

| 目錄 | 責任 |
|---|---|
| `protocol/` | 色票、Java 21 wire codec、section palette、分包、完整批次重組 |
| `paper/` | Bukkit 公開 API、nonce 握手／逾時、示範場景、清除 |
| `client-common/` | payload 邏輯、模型快取、固定幾何／GPU buffer、量測、自動化、GLSL |
| `client121/`、`client262/` | 各版 Loom 設定與小型 `Adapter.java` |
| `tools/` | 建置與真實客戶端驗證驅動 |

自動化僅在 `-Pautotest` 建置 runClient 設定中啟用（對應 `-Dwgpoc.autotest=true`）。控制檔 `.work/fabric-poc/control-<版>.txt` 由驅動寫入，第一行遞增序號，第二行是 `command ...`、`screenshot <檔名>`、`measure` 或 `quit`；一般玩家載入模組時不讀取這個控制檔。
