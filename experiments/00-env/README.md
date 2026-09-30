# 00-env：測試伺服器、測試世界與存檔格式盤點

所有產物在 `.work/`（已 gitignore），本目錄只放腳本與報告。結論見 [REPORT.md](REPORT.md)。

## 需求
- Linux、Python 3（僅用標準函式庫，NBT/.mca 由 `mcnbt.py` 自行解析，不需 nbtlib）
- `/usr/lib/jvm/java-21-openjdk-amd64`（1.21.11）與 `java-25-openjdk-amd64`（26.2）
- 可連網（fill.papermc.io）；一次只跑一台伺服器（-Xmx3G）

## 用法（皆冪等，可重跑）
```bash
cd experiments/00-env
python3 setup_servers.py            # 下載 paper/folia × 1.21.11/26.2 的最新 stable build（Folia 26.2 只有 BETA 就用 BETA），寫 server.properties/eula.txt
python3 build_world.py 1.21.11 26.2 # 用 Paper 生成世界 + 套用 scene.txt + 預生成半徑 10 chunk + save-all flush + stop，複製為 baseline
./smoke_folia.sh                    # （選用）Folia 冒煙啟動測試
# 盤點
for v in 1.21.11 26.2; do
  python3 inspect_world.py ../../.work/worlds/$v/baseline -o ../../.work/worlds/$v/inspect.json
  python3 dump_scene.py    ../../.work/worlds/$v/baseline -o ../../.work/worlds/$v/scene.json
done
python3 compare_worlds.py ../../.work/worlds/1.21.11/inspect.json ../../.work/worlds/26.2/inspect.json -o ../../.work/compare.md
```

## 產物位置
| 路徑 | 內容 |
|---|---|
| `.work/servers/{paper,folia}-{1.21.11,26.2}/` | server.jar、server.properties（port 25601–25604）、eula.txt；`builds.json` 記錄 build 編號與 sha256 |
| `.work/worlds/<ver>/baseline/` | **乾淨基準世界**，之後實驗請 `cp -a` 複本再用，不要直接改 |
| `.work/worlds/<ver>/{build-report.json,server-log.txt,command-log.txt}` | 建置耗時、失敗指令、完整 log |
| `.work/worlds/<ver>/{inspect.json,scene.json}` | 盤點輸出 |

## 檔案
- `scene.txt`：場景指令清單（同一份套用到兩版；`#` 註解）。場景在 y=150 的石磚平台（x -8..56、z -8..40，出生點 0,150,0），實體以 `Tags:["wg"]` 標記。
- `mcnbt.py`：零相依 NBT/.mca 讀取（可 `import`），保留 byte/short/long/float 型別供比較。
- `inspect_world.py` / `dump_scene.py` / `compare_worlds.py`：盤點與比較。

## 注意
- `eula.txt` 寫入 `eula=true` 僅為本機測試（伺服器只綁 127.0.0.1、online-mode=false）。
- 世界種子固定 `worldgit`，但**同種子在兩版的地形不保證相同**（不影響本實驗：場景在平台上）。
- 場景中的「自由走動的牛」與天然生成的實體（drowned、掉落物等）位置每次重建會不同；固定實體請以 `Tags:["wg"]` 篩選。
- 實體 UUID 每次重建都不同，baseline 不具位元組級可重現性；要位元組級一致請一律從 baseline 複製。
