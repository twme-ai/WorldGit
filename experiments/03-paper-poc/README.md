# 03-paper-poc：插件端 PoC（變動偵測與線上替換 section）

結論與數據見 [REPORT.md](REPORT.md)。所有暫存物（伺服器複本、結果 jsonl、下載的第三方 jar、bot 的 node_modules）都在 `.work/`（已 gitignore）。

## 內容
| 路徑 | 說明 |
|---|---|
| `build.gradle.kts`、`settings.gradle.kts` | Gradle 建置（Java 21 bytecode；**對 1.21.11 的 Mojang 名稱 NMS 編譯**，同一個 jar 也直接跑在 26.2） |
| `src/main/java/wg/poc/` | 插件：`PocPlugin`（入口）、`Tests`（/wgpoc 子指令與場景）、`Watcher`（unsaved 旗標監看）、`Pkt`（PacketEvents 監聽）、`We`/`FaweHook`/`WeTests`（WorldEdit/FAWE）、`Sect`/`Section`（section 替換與 chunk IO）、`Protect`（玩家保護）、`Sched`（Paper/Folia 通用排程）、`Out`（JSON 結果輸出） |
| `src/main/resources/plugin.yml` | `folia-supported: true`，softdepend packetevents / FastAsyncWorldEdit / WorldEdit |
| `tools/harness.py` | 伺服器/機器人驅動（複製 baseline、啟動、送指令、解析 `[WGPOC]` 結果行） |
| `tools/bot.js` | 最小 mineflayer 機器人（放置/挖掘/查看方塊、光照、封包統計） |
| `tools/run_flags.py` | 改動來源 × unsaved 旗標 × region 時間戳 × PacketEvents 封包（可加 `extra=2` 多個 bot） |
| `tools/run_fp.py` | 誤報觀察、chunk 載入旗標、同秒時間戳碰撞（`fp`）；自動存檔清旗標（第 4 個參數 `autosave`） |
| `tools/run_we.py` | FAWE / WorldEdit 的 EditSessionEvent 驗證 |
| `tools/run_section.py` | section 線上替換 + bot 驗證 + 未載入 chunk 的 chunk IO 讀寫 |
| `tools/run_protect.py` | 玩家保護（事件取消 vs 藥水效果） |
| `tools/run_light.py` / `run_fp2.py` / `run_io.py` / `run_bot_sources.py` | 光照外溢、誤報重複樣本、未載入 chunk 的 chunk IO、真玩家（bot）作為改動來源 |
| `tools/run_diag*.py`、`t_folia_save.py` | 診斷：旗標時間線、磁碟 NBT 欄位差異、Folia 存檔方法探測 |
| `tools/check_binary_compat.py` | 把插件 jar 的 NMS 引用對另一版伺服器 jar 檢查（成員存在且 public）——抓 1.21.11 ↔ 26.2 的簽章差異 |
| `tools/summarize_*.py` | 把 `.work/paper-poc/flags-*.jsonl` 整理成 Markdown 表格 |

## 需求
- Linux、JDK 21 與 JDK 25（`/usr/lib/jvm/java-{21,25}-openjdk-amd64`）、Gradle、Python 3、Node 22+
- 已完成 [00-env](../00-env/README.md)：`.work/servers/{paper,folia}-{1.21.11,26.2}/` 與 `.work/worlds/{1.21.11,26.2}/baseline/`
- 第三方 jar 放 `.work/jars/`（版本與來源見 REPORT §1）：`packetevents-spigot-2.14.0.jar`、`FAWE-1.21.11-2.15.0.jar`、`FAWE-26.2-2.15.4.jar`、`worldedit-bukkit-7.4.5.jar`（26.2）、`worldedit-bukkit-7.4.2.jar`（1.21.11，7.4.5 需要 Java 25）、`ViaVersion-5.12.0.jar`（只用於失敗的 26.1→26.2 嘗試，可略）
- mineflayer：`cd .work/bot && npm i mineflayer@latest`。mineflayer 4.39 只支援到 26.1（協定 775），要連 26.2 需要 hack，見 REPORT §1.3（`harness.py` 的 `BOT_VER`）。

## 建置
```bash
cd experiments/03-paper-poc
gradle --max-workers=1 --no-daemon build     # 產出 build/libs/worldgit-paper-poc.jar
```
（jar 的 manifest 帶 `paperweight-mappings-namespace: mojang`，否則 Paper 1.21.11 會把它當 Spigot 名稱去 remap。）

## 重跑
每個腳本都會把 baseline 複製到 `.work/paper-poc/run/<platform>-<ver>/`，使用 port 25621（paper-1.21.11）、25622（paper-26.2）、25623（folia-1.21.11）、25624（folia-26.2），綁 127.0.0.1、online-mode=false，**腳本結束時關閉伺服器與 bot**。一次只跑一個。

```bash
cd experiments/03-paper-poc/tools
python3 run_flags.py paper 1.21.11            # 22 種改動來源（約 4 分鐘）；結果 .work/paper-poc/flags-paper-1.21.11.jsonl
python3 run_flags.py paper 26.2
python3 run_flags.py paper 1.21.11 extra=2    # 另外 2 個 bot（共 3 玩家），看封包重複
python3 run_fp.py    paper 1.21.11 bot        # 或 nobot；第 4 個參數 autosave 改跑自動存檔測試
python3 run_we.py    paper 1.21.11 fawe       # 26.2 用 paper 26.2 fawe；Folia 沒有 FAWE，用 folia <ver> we（純 WorldEdit：1.21.11→7.4.2、26.2→7.4.5）
python3 run_section.py paper 1.21.11
python3 run_protect.py paper 1.21.11
python3 run_flags.py folia 1.21.11            # Folia 版本同理
```
插件也可以手動操作（console 或 op）：`/wgpoc prep <n>`、`/wgpoc flags`、`/wgpoc sec prep|snap|apply|report|disk ...`、`/wgpoc io <cx> <cz> <sy> <block>`、`/wgpoc we api <set|fast|pattern|blocks> <cx> [player]` 等，結果以 `[WGPOC] {json}` 印在 log，並累加到 `plugins/WorldGitPoc/results.jsonl`。

## 注意事項
- **Folia 沒有 `/save-all`**：`/wgpoc saveall` 在 Folia 上改在各 region 執行緒呼叫 Moonrise `saveAllChunks`。
- FAWE 預設擋第三方 Extent：`harness.py` 會預先寫入 `plugins/FastAsyncWorldEdit/config.yml` 的 `extent.allowed-plugins: ["wg.poc"]`。
- `tools/check_binary_compat.py <server-jar>`：建置後對 `.work/servers/paper-26.2/versions/26.2/paper-26.2.jar` 跑一次，可在不啟動伺服器的情況下找出 NMS 簽章差異（唯一預期的輸出是 `Priority.NORMAL` 的 CLASS MISSING——那個類別在 library jar，不在 server jar，是誤報）。
