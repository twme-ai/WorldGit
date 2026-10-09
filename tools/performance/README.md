# 效能量測

完整模型、fixture 限制與前後結果見 [docs/18](../../docs/18-performance.md)。每個 runner 自行持有 `.work/bench.lock`，同時只執行一個；不要再外包 flock。指定舊 jar 用於重現前測，不由工作樹 reset 或 checkout 取得。

```sh
export GRADLE_USER_HOME="$PWD/.work/gradle-home"
python3 tools/performance/benchmark.py --label cli-after
python3 tools/performance/noop.py --label real-after
python3 tools/performance/extra.py --label extra-after
python3 paper/tools/benchmark.py --latency paper 1.21.11 --label paper-after --require-isolation
python3 paper/tools/benchmark.py --latency folia 1.21.11 --label folia-after --require-isolation
python3 fabric/tools/benchmark.py 1.21.11 --label fabric-after --require-isolation
python3 tools/performance/single.py 1.21.11 --label single-after \
  --artifact fabric/mc1_21_11/build/libs/worldgit-fabric-1.21.11-0.1.0-SNAPSHOT.jar --require-isolation
```

CLI 預設 1,296／20,000 chunk，各三輪。線上固定 2,704 chunk、無玩家；單人固定 1,296 loaded chunk、一位玩家、20 FPS、停用 AFK 降速。commit 與 merge 三筆、switch 往返六筆，保存所有樣本及 p50／max；送出到終態包含 capture、寫入、驗證及持久化屏障。`--counts`、`--rounds` 可用於診斷；正式完整矩陣保留預設。每次 label 必須唯一，避免覆蓋失敗證據。

先建置 Fabric 正式 jar 與 dedicated fixture；單人 runner 自行建置測試客戶端。Paper／Folia runner 在持鎖期間檢查 shared jar 是否過期，必要時重建，量測時間不包含此準備。成功結束會清理世界副本；線上失敗會保留 `failed-world` 供診斷，用完可清除副本並保留 JSON／log。

舊線上 jar 使用 `--artifact <before.jar> --legacy-baseline --rounds 1`。相容探針仍量 TPS；此模式略過新版 chunk 隔離／對抗驗收，不能與 `--require-isolation` 並用。現行驗收每筆大型 switch 必須通過其他世界及同世界遠處的時間／流水／紅石、至少 18 TPS，並通過完整 verify 及邊界水／熔岩／活塞／落沙／實體／BE 對抗檢查。Fabric 另確認停服前的 entity 磁碟新增與最後移除。

結果在 `.work/perf/<label>/result.json`，命令證據在 `commands.jsonl`。用下列指令產生比較表；fixture、版本、玩家數或 FPS 不同時拒絕直接比較：

```sh
python3 tools/performance/report.py --before <before/result.json> --after <after/result.json>
```

TPS 的短窗口有整數 tick 首尾誤差；穩態隔離結論以千 chunk 切換窗口為準。原子 owner 耗時另從 `WorldGit atomic ... ownerNanos=...` 日誌取樣，不用端到端時間代替單 tick 的寫入成本。
