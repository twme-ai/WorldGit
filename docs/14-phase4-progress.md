# 14 — Phase 4 遠端協作：core／CLI 進度

日期：2026-10-03。範圍是 Phase 4 任務 1，基於 main `6f84654`；未 commit／push WorldGit 自身，也未修改 experiments。本文件記錄 core／CLI 的完成範圍與後續 Hub／Paper／Fabric 接線契約。新決定見 [09](09-roadmap-open-questions.md) #75–#82。

## 完成項目

- 世界組 remote 的 YAML 設定、Hub URL 展開、一般 git URL 樣板與公開 YAML 世界清單；每維度一 repo。
- PAT Basic/Bearer、明確 anonymous clone；環境、使用者 600 credentials YAML、平台 Provider；秘密不入 trees/config/log。
- 全組 fetch/push、分支/tag/group refs、FF 預檢與 force-with-lease；publication＋PARTIAL journal 與安全重試；tracking ahead/behind。
- 離線 pull 的 FF／三方合併／MERGING；線上同一 API 只在明確 live coordinator 屏障內套用。
- clone 可直接開啟世界，支援 branch／單維度；metadata/seed/worldgen/資料包還原，光照與 POI 重建；共用 release ZIP 組裝器。
- 裸 repo 合併的 preview、是否 FF／可合併、衝突區域 ours/theirs/base、共用 snapshot／來源 trailer／發布 journal／recover。
- 真正網路分批 PACK，不靠本機 packSizeLimit 冒充傳輸分割；push 計實際 HTTP PACK bytes，fetch 計落地 pack bytes。
- CLI remote/fetch/push/pull/clone/tag/export、JSON、適用的 dry-run；新增完整 baseline、CLI JSON 與中斷恢復測試及可重跑的真伺服器驗收腳本。
- modified-only 的可靠集合入口與 clone 稀疏歷史支援；平台持久事件蒐集尚待接線，缺集合時保守全存並警告。

## 給 Hub／Paper／Fabric 的 API 摘要

### remote 設定與憑證

`RemoteSpec.parse(String)`、`expand(DimensionId)`、`read/write(Path worldGroupRoot, …)`。Hub URL `https://hub.example/alice/castle` → `/git/alice/castle/minecraft.overworld.git`。一般 git 使用 `https://git.example/team/castle-{dimension}.git`，或 `manifest+file:///…/world.yml`／`manifest+https://…/world.yml`，YAML 根只有 dimensions mapping。維度目錄名中的 `%` 在 URL 再編碼一次。manifest 讀入結果固定保存到 remotes.yml；最多 64 KiB／32 維度，取得 manifest 不帶認證，現在限公開清單。

HTTP(S)/file；不支援 SSH。不接受 URL userinfo/query/fragment；remotes.yml 在世界組 root，永不進 git trees。`WorldRemotes.configure(add/remove/set-url,name,url,dryRun)` 同組鎖；未完成 push 的 remote 不能改 URL/移除。

`Credentials(Map<String,String> env, Path userFile, Credentials.Provider platform)`；`Credentials.system()` 供 CLI。優先 WGIT_TOKEN/WGIT_AUTH/WGIT_USERNAME → 使用者 `~/.config/worldgit/credentials.yml`（WGIT_CREDENTIALS_FILE 可指定，普通非 symlink，POSIX 權限恰為 600）→ platform provider → anonymous Basic。使用者 YAML 以 `https://host[:port]` 為 key，值含 mode/username/token。Provider 依 remote name/展開 URL 提供 Secret，不要把秘密放進其例外訊息。

Secret 只在 transport 設 Authorization；toString/redact 遮罩原 token、Basic/Bearer 與 URL 帳密。HTTP redirects 禁用，不把 PAT 轉送其他端點。平台保管秘密、權限與 MiniMessage 訊息，core 不寫平台設定；不要把 credentials 放進會保存的 datapacks。

### fetch／push 與恢復

`WorldRemotes(WorldLayout,Credentials)` 或 `(Path groupRoot,Map<DimensionId,Path> repos,Credentials)`；持有世界組/各 repo operation locks，**不開世界 session、不 capture、不 apply**。必須 close 後才開 WorldOperations，避免重入 repo 鎖。

- `fetch(remote,dryRun)` → TransferResult(state,operation,commits,packs,error)。先 incoming objects/refs，驗證所有遠端分支的 publication/group，才發布 tracking/groups/tags；任何混合維度拒絕。fetch-state.yml 在發布中斷後，下一 fetch 先檢查 lease 並回復舊 refs。沒有 marker 的舊世界以 group/snapshot 檢查。同名 tag／snapshot group 與本機不同時，在發布任何 tracking refs 前拒絕，不覆寫既有 snapshot 身分。fetch 不 prune 遠端已刪分支/tag。
- `push(remote,branch,tags,forceLease,dryRun,author)` → 同一結果。全維度 FF 預檢後逐維度 expected-old CAS。forceLease 要求 advertised tip 等於最近 fetch tracking；server 政策仍可拒絕。不同 tag/group 同名值拒絕覆寫。
- `trackingHeads(remote,branch)` → 全維度 commit map，供 pull。`tracking()` 用兩側可達 snapshot UUID 聯集計 ahead/behind；每維度超過 20,000 commits 標 estimated，detach 或未 fetch 不顯示。

跨 repo 沒有原子 transaction。各分支 publication commit 含相同 operation/branch/完整 commits map，且 marker 沿舊 marker 作 parent。push-state.yml 保存原 URL/tips/branch/options/targets/leases 與每維度完成狀態；PARTIAL 以原參數重試。已完成 ref 跳過；第三方變更不符合 old/target 時阻擋，不回滾別人的遠端提交。其間 WorldGit fetch/clone 拒絕混合組；**原生 git 和目前 Hub 網頁 reader 尚不檢查 marker，可能看見短暫混合 tips**。Hub PR 接手應讀取同一完整組契約，並在部分發布期間避免展示「合併已完成」。

`GitTransfer.PackSize(preparedBytes,wireBytes)`：push 先序列化 PackWriter，HTTP 再量真正 POST PACK；file transport 的 wireBytes 為 null。fetch 的 preparedBytes 是實際落地 `.pack`，wireBytes 為 null。最新中繼 tree 累積引用全部已送批次（以扁平 batch 子樹清單共用，不做深鏈），以相依順序分批中繼 commits，最多 10,000 物件/批，≤95,000,000 bytes，禁用 delta/reuse/thin；預計 pack 超限時再遞迴切小批次，保存單調編號 `refs/worldgit/transfers/`，clone 必須順序 fetch 才能 negotiation。裸合併先 `stageLocal`。外部 native repo 沒分批 refs 的超限 pack 拒絕並丟棄，不發布 incoming ref；託管端自行 GC／原生 git clone 的 pack 不受 core 控制。

### pull 的預覽／套用契約

`WorldOperations.pull(Map targets,Map expectedHeads,boolean ffOnly,MergeOptions)` → PullPreview(expectedHeads,targets,fastForward,MergeResult)。先檢查 COMPLETE／乾淨（含 untracked）／同名分支／snapshot group。全部遠端已包含於本地則不變；FF 以 Phase 2 journal/apply/verify 更新**目前分支**，不 detach；分歧走 Phase 3 MergeEngine／MERGING／resolve／continue。ffOnly 分歧拒絕；跨 DataVersion／不相容 DataPacks 仍拒絕。

```java
SortedMap<DimensionId,String> targets;
try (var remotes = new WorldRemotes(layout, credentials)) {
    var result = remotes.fetch("origin", false);
    if (!result.success()) throw new IOException(result.error());
    targets = remotes.trackingHeads("origin", "main");
}
// caller 已 lockEdits + flush，在背景 repo executor 上執行。
WorldOperations.PullPreview preview;
try (var ops = WorldOperations.live(layout, access)) {
    preview = ops.pull(targets, null, false,
        new WorldOperations.MergeOptions(false, null, 1, true, author, source));
}
// close 後解鎖。顯示 preview 的 plans/reports；由玩家明確選擇套用。
// 再次 lockEdits + flush；同一 repo executor；不可在 owner thread 等待。
try (var ops = WorldOperations.live(layout, access)) {
    var result = ops.pull(preview.targets(), preview.expectedHeads(), false,
        new WorldOperations.MergeOptions(false, null, 1, false, author, source));
}
// close 後 unlock。依結果推既有 MERGING/region UI。
```

LiveAccess 沿既有 source/validate/applyAll/beforeComplete 契約，全組 UUID remove→spawn、owner 正確執行緒、完整存檔、verify/HEAD barrier；不直接離線寫 Anvil。preview 只建候選 objects/index，不寫世界/HEAD；apply 再檢查 local heads lease、工作區與 fixed targets。preview 後新的 remote tip 不隱式替換目標；想追最新版要重新 fetch/preview。webhook／定時 fetch 永不自動 apply（07 §5）。等待衝突選擇不需持續 freeze，沿既有區域協調。CLI pull --dry-run 仍真 fetch，僅 apply dry-run。

### 伺服器端合併與受保護分支

`BareWorldMerge(groupRoot,dimensionRepoPaths[,EntitySemantics])`，無世界資料夾。Hub 須先完成 actor 授權、branch policy、quota/maintenance coordination，再使用 core group lock；其他 git 寫入仍須 CAS，不能假設 Hub receive-pack 自動遵守 core lock。

`preview(targetBranch,sourceBranch,distance)` → Preview(dimensions Candidate, distance, fastForward, canMerge)。每維度找 MergeBases.unique，拒絕多個最佳 base、版本/pack 不一致；規則三方合併後過濾 trees，沿 Phase 3 精確 atoms 與全域 region id。canMerge 無未解決區域，fastForward 是所有 target tips 為 source ancestors；特殊預檢錯誤以 IOException 回報，不冒充可合併。

`merge(preview,Map<Integer,MergeReport.Choice>,distance,author,message,dryRun)` → Result(state,snapshot,commits,reports,error)。每區域只接受 OURS/THEIRS/BASE；不完整回 CONFLICTS 且不動分支，未知 id/改變 tips/距離拒絕。無衝突或完整選擇後各維度產生共用 snapshot、Source.HUB、WorldGit-Merge-Source/Ours/Theirs trailers 的整合 commit；不同 tips 兩 parent，相同 tips 依 Phase 3 去重為一 parent。保持來源方塊 state（#46），updateShapes 只提示。dry-run 可建 objects，不動 refs。

bare-merge-state.yml 保存 group/branch/publication ref changes，CAS 發布；PARTIAL/中斷用 `recover()` lease 檢查後回復舊組，重新 preview/merge。先建立 transfer stage refs，讓大合併也能有界 clone。Hub 接手負責持久 PR/區域選擇、受保護分支政策、REST response、作業資源預算與公開服務的解析安全；本次沒有 PR/帳號/UI 接線。

### clone／ZIP／metadata／modified-only

`WorldClone.cloneWorld(spec,destination,branch,selectedDimensions,credentials,budget)`：先下載主世界 repo 讀 dimensions 清單，再下載選取維度，驗證全組、建立分支與 sidecars，組裝成功 atomic rename。目的地必須不存在；失敗刪暫存。部分維度仍下載整個主世界 repo 作 metadata（目前未做 blob-only fetch），未選主世界時移到 .worldgit/metadata。保留 dimension-manifest.yml/clone-selection.yml；不完整 clone 禁止 push 完整世界。只接受 branch，不做 detached revision clone；用 export 下載任意 revision。

`WorldAssembler.assemble(group,commitMap,world,selected)`／`zip(group,revision,OutputStream,tempRoot)`，共用逐 region ApplyPlanner/OfflineApplier/RegionWriter。組回 region/entities、還原 level.dat、map/scoreboard/gamerules/border/worldgen 與資料包原檔；1.21.11 用單人 DIM-1/DIM1 布局，26.2 用 dimensions/namespace/path。保存完整 WorldGenSettings／26.2 world_gen_settings（含種子及每維度設定）；不能把種子單獨還原當完整生成設定。

metadata 納入 DragonFight/CustomBossEvents/GameType/allowCommands；舊 Paper 分離世界的 DragonFight 從終界 level.dat 取得。Paper 的 paper 標記、Fabric API 的 fabric-convention-tags-v2 是平台供應，不影響 portable DataPacks；其他包及順序原樣保存。datapacks 路徑防穿越/符號連結，每檔≤32 MiB、總計≤64 MiB，不含模組 jar/外部依賴。

光照/Heightmaps/POI 不存，clone 後交遊戲重建；保留 snapshot 方塊連接 state，不做鄰居重算。玩家資料、天氣/時間不是可攜歷史；ZIP 不帶 repo/player/session.lock，clone 帶 .worldgit。Budget 預設組裝輸出與 ZIP 各≤2 GiB、15 分鐘，逐 region／輸出 buffer 檢查時間及 thread interruption；Anvil 暫存檔可 overshoot 一個 region，沒有硬中止 caller 的阻塞 OutputStream。暫存/output 失敗清理；stream output 已發出的 bytes 不能回收，HTTP caller 應中止回應。

`SnapshotSource.modifiedChunks()` 回完整持久集合，unknown=Optional.empty；`ModifiedChunks.read/write` 保存每維度 modified-chunks.yml，本機集合與 provider 聯集。集合變更綁 index fingerprint；missing 保守全存並警告。clone sparse 歷史以已存 chunks 初始化集合，未存自然地形遊戲重生不自動納入；apply journal 內先納入收到的追蹤 chunks，FF/merge 後驗證可見新資料。**Paper/Fabric 捕捉「曾編輯」事件與第三方工具接線未實作**；離線沒有可靠辦法從現存 NBT 猜玩家修改，手動補集合必須完整，不可傳當次 dirty batch。

## 驗收與量測

真驗收結果保存在 `.work/phase4-core-r6/results.json`、各平台 log 與 `1.21.11-partial.json`／`26.2-partial.json`。兩版與一般 git HTTP 全部通過；大型世界與完整 build 結果另列下表。來源只複製，scripts 自取 bench.lock；Hub/遊戲伺服器 finally 停止，複本與凍結 jar 用完移除。

自動測試也涵蓋只有地獄改動、主世界 tip 不動時的 group/tag/hash/clone/pull 配對；carrier 可是任何改動維度，不能固定取主世界舊 UUID。舊 Phase 1 缺 group pin 時的 hash 配對排除 publication/incoming/transfers 合成 refs，避免同 UUID 的 marker 被當成重複世界 commit。自動測試涵蓋 snapshot UUID collision 不覆寫本機 pin、URL/manifest/credentials 600 與遮罩、FF/非 FF/lease/dirty、PARTIAL push 與 mixed clone 拒絕、fetch/tag 中斷恢復、pull FF/merge/MERGING/resolve、兩版 clone/種子/modified-only、部分維度、裸合併/選擇/過期 preview、tag、ZIP 大小/時間、CLI JSON/ahead/behind/dry-run。`Phase4LocalIntegrationTest` 使用完整 .work baseline（缺檔略過）；`RemotePackLimitTest` 重負載另跑。

真伺服器腳本 `scripts/verify-phase4.py`：Hub jar＋SQLite、loopback 8097、bootstrap PAT（log 不印 token）；Paper 1.21.11/26.2 複本 25691/25692、offline；Fabric dedicated 原版＋Fabric API。兩版 baseline 複本經 synthetic/mutate fixture 與真 Paper save 整理，重建後查 log、verify、light/POI/UUID/seed；雙 clone 不同位置改動、三方/FF/衝突 resolve；Hub 存放的 bare repos 呼叫 API 合併後 clone 開 Paper、tree hashes 與 CLI 合併相同。一般 git 用原生 git http-backend（loopback 8098）驗證 URL 樣板。**沒有真的 GitHub／Gitea 測試**。

所有 Gradle 指令使用 JDK 21、`GRADLE_USER_HOME=.work/gradle-home`、`--configure-on-demand --max-workers=1`；量測持有 bench.lock。CLI/工具 heap 1.5 GiB，Hub 為 JDK 25/1.5 GiB。重跑：

```sh
export GRADLE_USER_HOME="$PWD/.work/gradle-home"
./gradlew :cli:acceptanceToolsJar :hub:bootJar --no-daemon --configure-on-demand --max-workers=1
python3 scripts/verify-phase4.py --skip-scale --results-dir .work/phase4-new
python3 scripts/verify-phase4.py --only-scale --results-dir .work/phase4-scale-new
flock .work/bench.lock ./gradlew :core:integrationTest --tests org.worldgit.core.Phase4LocalIntegrationTest --no-daemon --configure-on-demand --max-workers=1
flock .work/bench.lock ./gradlew :core:packLimitTest --tests org.worldgit.core.RemotePackLimitTest --no-daemon --configure-on-demand --max-workers=1
flock .work/bench.lock ./gradlew build --no-daemon --configure-on-demand --max-workers=1
```

### 真 Hub／平台與一般 git HTTP

| 世界版本 | 首次 push | clone＋完整世界組裝 | 傳輸 PACK 合計 | 驗證 |
|---|---:|---:|---:|---|
| 1.21.11 | 2.26 s | 6.74 s | 11,430 bytes | Paper/Fabric log 0 errors，tracked verify 0 差異；baseline 雜湊不變 |
| 26.2 | 1.95 s | 7.14 s | 11,491 bytes | Paper/Fabric log 0 errors，tracked verify 0 差異；baseline 雜湊不變 |
| 原生 git http-backend | — | — | 43,726 bytes | `{dimension}` URL 樣板 push/匿名 clone/verify COMPLETE |

前兩列是 baseline 複本再改成 441 chunk 的平坦控制 fixture（不是 20k 自然地形 benchmark），高度重複的空氣/石頭可去重，因此 PACK 很小，不能拿此數字外推自然世界。四種 server 開服後都有 chunk(0,0) 的 BlockLight/SkyLight，minecraft:librarian POI 在 [4,65,4]，控制實體 1 個、重複 UUID 0。Paper 仍按其 starlight.light_version=10 表示光照，isLightOn=0 不能單獨當失敗；Fabric isLightOn=1。完整世界驗證允許遊戲新生成的 untracked chunks（不冒充已存歷史）。

雙 clone 的 disjoint edit 三方 merge、B push→A ff-only、同位置 edit 進 MERGING、theirs resolve/continue/push，兩版通過。無衝突 bare merge 與有衝突 base 選擇的最終 **全維度 tree hashes 與 CLI 本地 merge 相同**；兩種結果都 clone 在真 Paper 開服，verify 0 差異、log 0 errors，light/POI/UUID 正常。Fabric dedicated 兩版驗證原始 clone；bare merge 後的副本僅用 Paper 開啟，沒有重跑 Fabric。成功流程用 Basic/Bearer PAT 與匿名 Basic 三種實際認證。

### 20,521 chunk 世界與大 pack

`.work/phase4-scale-r4/results.json`／`summary.json`：真 Hub smart HTTP，使用 Phase 1 從 Phase 0 初始物件重建的 26.2 世界複本（`.work/phase1/scale/run`），只複製世界、忽略舊 repo。主世界為 20,521 full chunks，另含原有地獄／終界；不是用 modified-only 空白歷史降低容量。

| 作業 | 耗時 | 實際 pack 合計 | pack 數／最大 |
|---|---:|---:|---|
| init | 317.40 s | — | 第一次初始化的安全複本供修正後重跑；不計入 push／clone |
| 首次 push | 84.41 s | 123,593,628 bytes | 27／6,306,923 bytes |
| clone＋組裝 | 243.34 s | 落地 123,593,532 bytes | 24／6,306,923 bytes |
| 單方塊增量 push | 4.74 s | 29,842 bytes | 6／27,575 bytes |

首次 push 的總量超過 100 MB，**每個實際 HTTP PACK 與 clone 落地 pack 都 ≤95,000,000 bytes**；push 預測與 HTTP 實測逐筆相等。clone 的 24 個非空 pack 比 push 少三個 32-byte 空 pack。組裝 44 個 region、140,940,359 bytes，組裝部分 215.711 s；seed/worldgen metadata 隨世界還原。增量只有一個 chunk／section，B 的 FF pull COMPLETE、獨立全量 verify COMPLETE（0 差異）。本次大型 clone 沒有再開真遊戲伺服器，兩版開服驗收使用上面的控制 fixture；不混淆兩種量測。

`RemotePackLimitTest` 另用 110 MB 不可壓縮隨機物件，file transport 的兩個資料 pack 約 88.03 MB／22.01 MB，接收後可完整讀回。階層 tree 回歸用 50 KB 上限驗證多批 root、後續更新與 stage trailers；這兩項補足自然地形每批約 6 MB、未逼近 95 MB 邊界的限制。

量測成功後停止 Hub，刪除 source／clone／Hub 大型 repos／初始化快取／凍結 jars；保留 JSON 與失敗 log。腳本失敗保留初始化複本供重跑，成功會清理。20k pull 的全量 capture／apply 後驗證仍沿 Phase 2 掃描流程，耗時明顯高於網路增量傳輸，未在此次改成新的索引演算法。

### 最後 build／測試

最新程式碼以 `build :core:integrationTest --tests org.worldgit.core.Phase4LocalIntegrationTest :core:packLimitTest --tests org.worldgit.core.RemotePackLimitTest` 全綠，4m 40s、79 tasks（25 executed／54 up-to-date），log：`.work/p4-build6.log`。JUnit 共 **214 tests，0 failures／errors／skipped**：core 95（含 remote 14）、CLI 4、Hub 36、Paper 22、Fabric 40、protocol 7、platform-api 8，另 baseline integration 1（內含兩版本）／large-pack 1。

最後大 pack 測試實際發送／接收資料 pack 為 88,027,457／22,007,639 bytes，另發送 32-byte 空 pack，逐一檢查上限與重新開啟後所有 blobs。只有 Gradle 既有 deprecation／JDK native-access 等警告，沒有測試失敗。檢查 loopback 8091–8099、25691／25692 均無驗收服務存留；`git diff --check` 通過，HEAD 仍為 `6f84654`，experiments 沒有差異。

### 初次失敗與修正

| 執行 | 發現 | 修正／界線 |
|---|---|---|
| phase4-core-r1 | Paper 1.21.11 開世界報缺 DragonFight | 從 Paper 終界世界捕捉 metadata，組裝舊歷史加 vanilla 預設，後續 Paper 通過 |
| phase4-core-r2 | Fabric 1.21.11 開服沒有 error，verify 被 DataPacks 新增 fabric-convention-tags-v2 阻擋 | 只去除此平台標記，不去除真正模組 worldgen/data 包；加入回歸測試 |
| phase4-core-r3 | 第二次 push 的 publication 被 Hub 全 refs 非 FF 政策拒絕 | marker 沿前版 parent；不同 group pins 明確拒絕覆寫 |
| phase4-core-r4 | 密集 fetch 達 Hub 預設 attempts=30/60s，HTTP 429 | client 安全留下 PARTIAL；臨時 loopback 驗收提高 attempts=10000，正式預設不改。Hub 下個任務須評估成功 PAT 的限流 |
| phase4-core-r5 | FF pull 已套用，但 CLI JSON 直接序列化 ApplyPlan 失敗 | 共用既有 merge 統計輸出，新增 CLI JSON 完整流程回歸；不把序列化失敗算套用驗證成功 |
| phase4-scale-r1 | Started log 後立即 REST 建世界，bootstrap PAT 尚未建立，401 | 腳本先等 /api/v1/me 回 admin，程序 finally 已停止，重新量測 |
| phase4-scale-r2 | 已接受 21 個中繼 refs，最後 pack 預計 107,776,407 bytes 被 client 拒絕 | 小型階層樹回歸重現：563,563 bytes 對 50k 預算；只修時間／thin negotiation 未解決。改為最新 boundary tree 累積引用全部批次，保持 parent 時間順序，恢復禁用 thin/delta；回歸通過後重新量測 |
| phase4-scale-r3 | 合成 commit 的日期更新後，Hub 拒絕不相符的 WorldGit-Time trailer | 同步合成 metadata 的 time/committer，原始世界 commit 不改；新增逐筆 stage trailer 解析斷言。已初始化的測量複本暫存供安全重跑，不修改 baseline |
| 最後程式審查（group 身分） | fetch 原本只拒絕同名不同 tag，會覆寫不同 SHA 的同 UUID group | 全組下載驗證後、任何 tracking 發布前拒絕衝突 group；回歸確認既有 HEAD/pin 保持原值 |
| 最後程式審查 | 舊 Phase 1 缺 group pins 時，allCommits 會混入 publication/incoming 合成歷史，造成同 snapshot 對應多個 commit | 排除合成 refs，clone 兩版後刪 pins、以原始 hash verify 的回歸測試；修正後再次完整 build |
| p4-build 第一次 | 舊 Phase 1 測試用「尚未」字樣判斷 modified-only 警告，新的完整集合 API 警告不再含此字 | 更新斷言檢查實際 unknown→保守全存行為；保留失敗 log，重新跑完整 build |

## 與設計的差異／未完成事項

- 本次完成 core/CLI，平台 remote 指令、PAT 設定 UI、線上 pull UX、Hub PR/帳號/受保護分支/下載 HTTP 層仍由後續任務接。實測 bare merge 直接呼叫 API，不冒充「網頁 PR 已完成」。
- 不同 repo 是 journal＋publication 的可恢復發布，沒有跨 repo 原子交易；第三方競爭阻擋時須人工處理，不提供盲目強推/回滾。目前 Hub reader 不檢查完整 publication。
- 同 tips 維度的 merge parent 依 Phase 3 去重；沒有為了「兩 parent」複製相同 parent 或生成虛構提交。其餘維度整合提交、snapshot 與 trailers 保持一致。
- SSH、region sparse/partial、manifest 認證、GitHub/Gitea 真實服務、WAN/斷線任意時點 fuzz、受保護分支實際 PR REST 流程未測/未實作。core force-with-lease 已測，但 Hub 現有非 FF 政策仍可拒絕；保護政策不在此次改動範圍。
- modified-only 平台持久蒐集與「真正新自然地形生成後」實機測試未接；core sparse clone 的完整 seed/worldgen 及收到新 chunk 的 FF 驗證已測。資料包的外部模組/資源包、玩家進度不隨 ZIP 還原。
- ZIP 是串流壓縮輸出，仍需有界 Anvil 暫存，不是完全無磁碟串流；一個 region 套用/阻塞 caller stream 沒有硬 deadline。pack stages 需保留，會增加 refs/小型合成 objects 與 HTTP requests；自架 Hub 限流目前需要部署者評估。
