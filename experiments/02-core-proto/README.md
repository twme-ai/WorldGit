# 02-core-proto：core 原型（正規化、section 雜湊、JGit 映射、量測、寫回）

結論與數據見 [REPORT.md](REPORT.md)。

## 需求
- Java 21（建置用 toolchain 21；執行 jar 用 `java`，Java 21/25 皆可）、Gradle（`/usr/local/bin/gradle`，需連 Maven Central 下載 JGit / zstd-jni / lz4-java）
- Python 3（只用標準函式庫；讀 NBT 用 `../00-env/mcnbt.py`）
- `../../.work/servers/paper-{1.21.11,26.2}` 與 `../../.work/worlds/{1.21.11,26.2}/baseline`（見 00-env）。baseline 只會被複製，不會被改動。
- 測試伺服器埠：1.21.11 → 25611，26.2 → 25612（綁 127.0.0.1）。機器只有 3 核心；一次只跑一台，腳本保證結束時關閉。

## 建置
```bash
cd experiments/02-core-proto
gradle --max-workers=1 --no-daemon jar      # → build/libs/core-proto.jar（fat jar）
```

## CLI
```
java -jar build/libs/core-proto.jar init    <serverRoot> <repo.git> [--tol 2] [-m msg]
java -jar build/libs/core-proto.jar commit  <serverRoot> <repo.git> [--tol 2] [-m msg] [--full] [--allow-empty]
java -jar build/libs/core-proto.jar diff    <repo.git> <revA> <revB> [-v]
java -jar build/libs/core-proto.jar restore <serverRoot> <repo.git> <rev> minecraft/overworld <cx1> <cz1> <cx2> <cz2> [--poi keep|delete]
java -jar build/libs/core-proto.jar stats|gc|size|log <repo.git> ...
```
`serverRoot` = 含 `world/`（與 1.21.x 的 `world_nether`、`world_the_end`）的目錄。`--tol` 為實體黏性容許距離（格，0 = 關閉）。
index 快取存在 repo 旁（`<repo.git>.index`）。restore 必須在伺服器關閉時執行。

## 一鍵重跑 T1–T4
```bash
python3 scripts/run_tests.py 1.21.11 26.2      # 每版約 15–20 分鐘
```
產物：`.work/core-proto/<ver>/{results.json, logs/*.log, repoA.git(tol0), repoB.git(tol2), ...}`；stdout 有進度。
階段：T1a（預設 gamerule 的自然演化）→ T1b（randomTickSpeed=0，三次重啟 + churn）→ T2（setblock/fill/箱子）→ T3（量測、gc）→ T4（restore poi=keep/delete）。
另有 `scripts/mcserver.py`（伺服器包裝）與 `scripts/run_tests.py`（測試）。
