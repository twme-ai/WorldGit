# WorldGit

Minecraft 世界的 git 式版本控制。世界是 working tree，每個維度是獨立 git repo；以正規化 section 儲存快照，共用歷史給 CLI、Paper/Folia、Fabric 與 Hub。

目前已完成 Phase 4：四端共用的遠端協作（remote／fetch／push／pull／clone／tag／release ZIP），Hub 帳號與權限、受保護分支、PR 審核與網頁合併、3D 座標釘選留言、webhook；Paper／Folia 與 Fabric（單人／專用伺服器）遊戲內 push／pull 預覽確認、PR 與留言顯示。各平台結果與限制見 [Phase 4 紀錄](docs/14-phase4-progress.md)。

**使用方式請看 [使用手冊](docs/15-user-guide.md)**：依單人玩家、伺服器管理員、建築者、CLI、Hub 使用者與架站者分章說明。

| 模組 | 責任 | Java |
|---|---|---|
| [core](core/README.md) | Anvil/NBT、正規化、JGit、維度 repo、快照／套用／三方合併、YAML 與 `.wgignore` | 21 |
| platform-api | `LiveWorld`、dirty generation、離線來源、session lock、Phase 2 apply/lock 介面 | 21 |
| [protocol](protocol/README.md) | 協定 v2、衝突清單／預覽與可選能力、色票、≤ 28,000 bytes 分包與重組 | 21 |
| [cli](cli/README.md) | 離線快照、復原／切換、合併／解決衝突、JSON、終端色彩、fat jar | 21 |
| [Paper／Folia](paper/README.md) | 1.21.11／26.2 插件、線上合併、區域工具與 GUI | 21／25 |
| [Fabric](fabric/README.md) | 兩版單人操作、客戶端鬼影與衝突清單、Paper／Folia 對接 | 21／25 |
| [Hub](hub/README.md) | 歷史、3D diff、PR／審核／合併、座標留言、release、webhook | 25 |

根 Gradle 納入上述模組，各平台使用自己的 project 與 toolchain。core 不引用任何 Minecraft 類別，Java 25 平台可依賴 Java 21 的 core。

## 建置與使用

Gradle 以 JDK 21 執行，完整建置另需 JDK 25 toolchain。Wrapper 固定 Gradle 9.6.1 並驗證 distribution SHA-256；依賴版本在 Gradle 慣例的 `gradle/libs.versions.toml`，WorldGit 設定全部是 YAML。

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export GRADLE_USER_HOME="$PWD/.work/gradle-home"
mkdir -p "$GRADLE_USER_HOME"
flock .work/bench.lock ./gradlew --no-daemon --configure-on-demand --max-workers=1 build
./wgit --world /srv/minecraft/world init --template creative
./wgit --world /srv/minecraft/world status
./wgit --world /srv/minecraft/world commit -m '城堡完成'
./wgit --world /srv/minecraft/world log
./wgit --world /srv/minecraft/world diff HEAD~1 HEAD --blocks
```

可指定世界資料夾或含 `world/` 的伺服器資料夾；預設目前目錄。支援 1.21.11 的 Paper 三資料夾／原版 DIM-1、DIM1，以及 26.2 的 `dimensions/<namespace>/<path>/`。repo 放在世界外的 `<server>/.worldgit/<world>/<namespace>.<path>/`。

發佈產物是 `cli/build/libs/wgit.jar`，也可 `java -jar wgit.jar …`；根 `wgit` 可用 `WGIT_JAR` 指向發佈 jar。運行中的世界會因 `session.lock` 警告並拒絕離線操作。

## 測試

```sh
# CI 用的小型真實世界 fixture 與單元測試；不需要 .work 世界。
./gradlew --no-daemon --max-workers=1 build

# 完整 baseline 驗證；世界不存在時由 JUnit 自動略過。
flock .work/bench.lock ./gradlew --no-daemon --max-workers=1 :core:integrationTest

# > 95 MB 的實際 pack 分割測試（記憶體與磁碟負載）。
flock .work/bench.lock ./gradlew --no-daemon --max-workers=1 :core:packLimitTest

# 真正 Paper 的複本重寫；腳本自行取得 bench.lock，finally 關閉伺服器。
./gradlew --no-daemon --max-workers=1 :cli:acceptanceToolsJar :cli:fatJar
python3 scripts/verify-paper.py

# 大世界 CLI 量測；腳本自行拿鎖；沒有 raw 世界時可由保留的 Phase 0 repo 重建。
python3 scripts/benchmark-scale.py
```

本機腳本產物在 `.work/phase1/`，為避免蓋掉證據，已存在的驗證目錄會報錯。刪除或搬走自己的舊驗證產物後才重跑。CI 不啟動 Minecraft、不跑大型 pack 測試。

可用 `WGIT_VERIFY_DIR`／`WGIT_BENCH_DIR` 指定新的驗證目錄。重現本次最終數字時，Paper 使用 `WGIT_VERIFY_STABLE_SOURCE="$PWD/.work/phase1/paper-fixed"`（前次暖機的 baseline 複本）；大型量測使用 `WGIT_BENCH_SOURCE="$PWD/.work/phase1/scale/run"`（由 Phase 0 repo 重建的世界），來源與限制見進度報告。

## 設計文件

[概念](docs/01-concept-mapping.md)、[資料模型](docs/02-data-model.md)、[儲存](docs/03-storage-backend.md)、[commit/status](docs/04-commit-and-status.md)、[diff](docs/06-diff-merge.md)、[架構](docs/08-architecture.md)、[決策與路線圖](docs/09-roadmap-open-questions.md)、[Phase 1 實作與驗收](docs/11-phase1-progress.md)、[Phase 4 進度](docs/14-phase4-progress.md)、[使用手冊](docs/15-user-guide.md)。Phase 0 原型保留於 `experiments/`，正式程式不依賴它們。
