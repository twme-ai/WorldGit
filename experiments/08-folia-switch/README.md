# 08：Folia 大量 section switch 壓測

結果與限制見 [REPORT.md](REPORT.md)（三平台 A→B→A 全部通過離線比對；摘要表由 `tools/summarize.py` 產生，輸出於 `.work/folia-switch/summary.txt`）。程式從 03-paper-poc 複製後擴充；03 原檔未修改。所有伺服器與資料皆為 `.work/folia-switch/` 的獨立副本。

## 一鍵重跑

需求：既有 `.work/servers/{folia-1.21.11,folia-26.2,paper-26.2}/`、`.work/worlds/{1.21.11,26.2}/baseline/`、`.work/bot/node_modules/`（含 03 的 26.2 協定 hack）、Gradle、Python 3、Node、JDK 21/25。

```bash
experiments/08-folia-switch/tools/reproduce.sh --server all --budgets 5 10 --bots 3 --limits 4 16 --inflight 24
```

腳本自動持有 `.work/bench.lock`：**不要在外面再套同一把 flock**，否則會自我等待。fixture 建立、伺服器、bot、磁碟比對全程持鎖。Gradle 建置在取鎖前，限制為一個 worker。

單一平台與保留世界：

```bash
experiments/08-folia-switch/tools/reproduce.sh --server folia-26.2 --budgets 5 10 --keep-run
experiments/08-folia-switch/tools/reproduce.sh --server paper-26.2 --budgets 5 --bots 6 --inflight 12
```

預設執行 A → B → A 兩組（5/10 ms；`run.py` 單一平台失敗時會繼續下一個）；`--limits 4 16` 會再比較兩種 section 上限，再做取消與 A 恢復。`--skip-cancel` 可略過取消測試。預設結束會刪伺服器副本，保留結果、manifest 與 log；`--keep-run` 才保留全部副本。每個平台只啟動一台，綁 127.0.0.1、online-mode=false，退出與例外路徑都會停止 server/bot。

## 測試內容

- 3072 個預先寫入的 full chunk，分成三個 32×32 區域，相距 128 chunk。修改範圍為各區域 x=4..27、z=8..21，共 1008 chunk。
- 每 chunk 修改 section Y=8..11（128..191），包括大量石頭/泥土、木造/石磚建築、玻璃屋頂、發光方塊、裝物品的箱子、講台。
- 三個 bot 分別停在 chunk (16,15)、(144,15)、(272,15)。預設 view/simulation=2，Xmx=3G，Folia region threads=2、chunk worker/IO 各 1。
- 每區域 12 個實體位置，每位置含盔甲座、展示框、有名字的 NoAI 牛，共 108 隻。B 將相同 UUID 循環移至另一區域，修改名稱與物品。
- 初始化時保存 A，短暫套 B 並保存 B，再還原 A；此段不計入 switch 壓測。快照是 NMS palette copy + 完整 BE NBT + 實體完整 NBT，沒有建立 JGit repo。
- switch 只處理 A/B 有差異的 section。依執行當下的 Folia region ID/tick 累計 section 數與時間，不能將固定座標格網誤認為 region。
- 每個 chunk 經 async 載入、owner callback 加 plugin ticket、記憶體替換、釋放 ticket；全域 in-flight 限制獨立於 region 限額。
- 全域 removal barrier 完成後，才在各目標 region 生成實體，避免同 UUID 跨 region 重複存在。
- 以 02 複製的 `Nbt.java`、`Region.java`、`Codec.java` 做離線讀取與正規化雜湊。每輪 24192 個 section 皆檢查，實體 UUID/正規化 NBT 與講台 POI 亦檢查。

## 程式與輸出

| 路徑 | 用途 |
|---|---|
| `src/main/java/wg/poc/Bench.java` | coordinator、預算、實體、存檔、保護與量測 |
| `src/main/java/wg/poc/Sect.java` | 03 替換流程，另加明確 POI remove/add |
| `src/main/java/wgproto/Offline.java` | fixture、離線 section/entity/POI 驗證 |
| `tools/run.py`、`harness.py`、`bot.js` | 持鎖、複製、啟動、命令、bot 與清理 |
| `.work/folia-switch/results-*.json` | 全部量測與比對結果 |
| `.work/folia-switch/evidence/<平台>/` | console、插件 JSONL、A/B SHA-256 manifest |
| `.work/folia-switch/failures/` | 開發過程失敗證據 |

03 的舊測試類別留在複本中供來源對照，08 的入口只啟動 `Bench`，不啟動旗標掃描、PacketEvents 或 WorldEdit hook。

手動命令（console 或 op）：

```text
wgpoc init
wgpoc switch B 5 4 24
wgpoc switch A 10 4 24
wgpoc save
wgpoc status
wgpoc cancel
wgpoc sample 16 15 11 manual
wgpoc park WgBot1 0
wgpoc protection-test WgBot1
```

離線驗證已保留的伺服器（先關服；26.2 的 overworld 路徑多一段）：

```bash
/usr/lib/jvm/java-21-openjdk-amd64/bin/java -cp experiments/08-folia-switch/build/libs/worldgit-folia-switch.jar \
  wgproto.Offline verify \
  .work/folia-switch/run/folia-26.2/world/dimensions/minecraft/overworld \
  .work/folia-switch/evidence/folia-26.2/manifests A
```

`verify` 回傳 0 才代表 section、實體與 POI 全部通過；2 表示有差異。玩家保護測試是把 Bukkit 傷害事件送入事件匯流排，與真實落下/窒息/溺水的物理場景驗證不同。

## 產生摘要表

```bash
python3 experiments/08-folia-switch/tools/summarize.py > .work/folia-switch/summary.txt
```

## 已知陷阱（詳見 REPORT 第 3 節）

- Folia 上對非 ticking chunk 呼叫 `addEntity` 後 `isValid()` 可能為 false，但實體已加入；以存檔後離線比對為準。
- 離線實體比對略過沒有 modifiers 的 `movement_speed`（伺服器惰性建立）。
- 重跑時伺服器與 bot 會用同一把全域鎖排隊，其他任務持鎖時可能等數十分鐘。
