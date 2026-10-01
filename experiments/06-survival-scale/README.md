# 06：生存雜訊、大世界 init 與本機 Git 傳輸

結果、口徑與限制見 [REPORT.md](REPORT.md)。程式直接複製並擴充 `../02-core-proto/src/main/java/wgproto/`，沒有修改 02。

## 重跑

在專案根目錄執行：

```bash
bash experiments/06-survival-scale/scripts/run-all.sh
```

需求：Java 21/25、Gradle、Python 3、Node.js、C Git、`/usr/bin/time`；既有 `.work/servers/paper-{1.21.11,26.2}`、`.work/worlds/*/baseline` 與 `.work/bot/node_modules`（沿用 03 的 mineflayer 4.39.0 / 26.2 協定 776 補丁）。快取由腳本放在專案 `.work/`。腳本從官方 Modrinth 下載固定 Chunky 1.5.3，驗證 SHA-512。

每個量測腳本自行 `flock` 全域 `.work/bench.lock`，等待時不啟動伺服器；正常結束與 Python 例外時會清理伺服器及 bot。MC 綁 `127.0.0.1`，埠 25641／25642、離線模式；HTTP backend 綁 `127.0.0.1:25643`。所有 Git push 僅送至此本機 HTTP 服務，不修改專案的 Git 歷史。

重跑前須自行移走或刪除 `.work/survival-scale/{survival-26.2,survival-1.21.11,large,transport}`；腳本拒絕覆蓋已有的 `run/`。原始存檔不修改。預設流程約需一小時以上，加上等待其他實驗釋放鎖的時間。磁碟 guard 以本實驗 work + experiment 的實際配置空間檢查，5.8 GiB 時中止。刪除的 clone 會先驗證 HEAD 與連通性，數據與 log 保留。

## 分段執行

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export GRADLE_USER_HOME="$PWD/.work/gradle-home"
gradle -p experiments/06-survival-scale --max-workers=1 --no-daemon jar
/usr/lib/jvm/java-25-openjdk-amd64/bin/java -jar experiments/06-survival-scale/build/libs/survival-scale.jar check
python3 experiments/06-survival-scale/scripts/download.py
python3 -u experiments/06-survival-scale/scripts/survival.py --version 26.2
python3 -u experiments/06-survival-scale/scripts/survival.py --version 1.21.11 --minutes 5 --control-minutes 1 --interval 5
python3 -u experiments/06-survival-scale/scripts/large.py --radius 1136
python3 -u experiments/06-survival-scale/scripts/transport.py
python3 experiments/06-survival-scale/scripts/summarize.py
```

`survival.py` 預設無玩家 5 分鐘、站立 5 分鐘、4 個 bot 活動 30 分鐘，每 5 分鐘存檔一次；額外保留 setup 快照，不把人工建立場景當成遊玩增量。每份存檔對 8 組政策逐一 init/commit，各自保留完整連續歷史，才能測「黏性錨點」而不只是兩份快照的獨立比較。`--minutes` 必須是 `--interval` 的整數倍。

bot 生存場地在 y=189 的平台，console 給工具與材料，補充小礦脈／樹幹與 husk；bot 實際挖掘、放置、種植、餵牛、開箱存物、戰鬥、丟物及睡覺。探索 bot 步行在天然地形。保留預設 gamerule；為了觀察換夜，有一次 `time set night` 與回床位的傳送。這是輔助生存操作負載，不能視為未經干預的真人伺服器樣本。bot log 分開記成功、失敗與死亡；以報告記載的實際成功項目為準。

`snapshot_copy` 短暫 `tick freeze` → `save-all flush` → 複製 world 目錄 → `tick unfreeze`。記錄快照暫停秒數與 `level.dat` 的 game time。測量 CLI 時讀唯讀複本，使用 `--full` 避免 timestamp 秒解析度碰撞。

`large.py` 用 Chunky square/corners 生成約 2 萬個 full chunk，再停機跑兩版 init（`-Xmx1G`）；每版記錄 GNU time RSS，先 gc 再處理另一版以控制磁碟。init root tree 必須完全相同。小改動在 `tick freeze` 下 setblock；探索增量是遠處 16×16 chunk 的 Chunky 模擬載入。init 時伺服器關閉；小改動 commit 前 freeze + flush + save-off，沒有玩家。

`transport.py` 自架 `git http-backend`，同一資料分別以 C Git 和 JGit 試首次 push、初始 clone、增量 push/fetch、完整 clone、depth=1 clone。傳輸量是 handler 實際累計 HTTP request/response body，不含 HTTP header、TCP/IP 與重傳；不是用 pack 檔大小冒充線上傳輸量。HTTP 服務與所有客戶端都在全域鎖內執行。

## CLI 的擴充

```text
init|commit SERVER REPO [--tol 0|2|4] [--ignore FILE] [--extra-noise] [--stream] [--full]
transport push REPO URL
transport clone URL REPO [DEPTH]
transport fetch REPO URL
check
```

`--stream`：region 先只讀 8 KiB header，按需要讀 payload；首次 init 每個 chunk tree 寫完即釋放子節點。增量 commit 延用 02 的惰性 tree 與 timestamp index。`--extra-noise` 去除 Health、裝備 damage 與 item count，保留物品種類及其他 components。

最小 `.wgignore` 支援 `entity <namespace:type|*> [!persistent]`、`!entity` 加回、空行與註解；拒絕未支援語法。排除規則會存成 root `.wgignore` blob；政策或 tol 改變使 index 失效。`persistent` 此處定義為 `PersistenceRequired`／名稱／NoAI／馴服或 Owner／靜態建築實體／船與礦車，**不是 Bukkit removeWhenFarAway 的完整等價判斷**。未標記的牛羊也可能被 `entity * !persistent` 排除。玩家固定排除。

本實驗不驗證 ignore-aware restore；不要拿此原型在正式世界做 restore。

## 補充：重跑注意事項（接手後）

- `transport.py` 已修正：HTTP handler 轉傳 `Content-Encoding`（JGit clone 需要 gzip 請求），錯誤訊息改為 ASCII 安全。重跑前移除 `.work/survival-scale/transport`。
- `run-all.sh` 之後執行 `scripts/summarize.py` 會把各 `results.json`／`gc.json` 複製到 `data/`。
- 已刪除中間產物（伺服器 `run/`、快照、大世界 `run/`、transport remotes）以符合 6 GB 上限；保留各 Git repo、log 與數據。最終 `.work/survival-scale` 約 450 MB。
