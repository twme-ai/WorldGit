# 14 — Phase 4 遠端協作：core／CLI／Hub 進度

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

## Hub

日期：2026-10-03。Phase 4 任務 2 接續既有未 commit 的工作樹；主要在 hub/，core 補向後相容的 merge 額外 trailers、ZIP 固定 commit map overload 與保留空維度目錄。Paper／Fabric／experiments 未改動，WorldGit 自身未 commit／push。新決定 #83–#91 見 docs/09；API／設定詳見 [Hub README](../hub/README.md)，審查見 [Phase 4 安全紀錄](../hub/docs/security-review-phase4-2026-10-03.md)。本章更新較上方「任務 1」歷史記錄晚；上方的 Hub 未完成／限流問題以本章最新狀態為準。

### 完成項目

- 本機帳號、三種可選 OAuth2 client、明確連結／解除、mock provider state/PKCE 測試；預設關閉自助註冊，啟用後 SMTP 信箱驗證／一次兌換與 IP 限流。
- owner/admin/write/read、組織／成員／團隊、個人與同組織團隊授權、公開／私人世界、PAT read/write/admin／到期／最後使用；REST 與 Git 雙重 scope／角色檢查。
- 認證限流只扣失敗：成功 Basic/Bearer PAT 另走預設 6000 次/60 秒 IP／使用者額度；完整傳輸不提高 attempts，錯誤認證仍被鎖。
- 世界可選受保護分支：禁止 force/delete、PR-only／審核數，owner/admin 沒有 bypass；保護 publication 不能宣告與任一維度 head 不符的內容；blob/tree tag 明確拒絕。
- 同世界 PR 建立／列表篩選／詳情／編輯／closed、commit 列表／3D diff、持久區域選擇與全維度 tip fingerprint、approve/request-changes、審核 gate、最後 lease、core snapshot/HUB/trailers/journal/publication 合併。合併競爭與 DB finalize 恢復，事件／通知與 outbox。
- 一般留言／回覆／編刪／座標或範圍釘選、3D 標記聚焦、世界／PR／維度留言 REST，XSS 當純文字。
- tag release 固定全維度 commits、core 串流 ZIP／暫存／時間／解析預算／並行許可、private,no-store、沒有 ZIP 快取。
- 每世界 webhook 設定、HMAC-SHA256、重試／持久投遞紀錄、精確 allowlist／預設 SSRF 拒絕／DNS socket pinning／禁止 redirect。
- 瀏覽器 HttpOnly session＋CSRF、不保存 token 到 localStorage；前端安全 lint、Playwright 流程與自持 bench.lock 的 CLI／Paper 腳本；協作 JSON 錯誤／分頁／大小預算。

### 給 Paper／Fabric 接手的 REST 與 webhook

所有端點以 `/api/v1` 起頭，世界為 `/worlds/{owner}/{world}`。建議 PAT Bearer，scope 與角色同時檢查；私人不可讀／不存在／跨世界子 id 一律 404。列表 `{items,offset,limit,hasMore}`，limit 1–100／預設 50，offset 0–10000。

| 遊戲功能 | REST |
|---|---|
| 建立／列表／查看 PR | POST/GET `…/pulls`；GET `…/pulls/{id}`。建立 body=source,target,title,description；詳情含 pr、preview、choices、reviews、mergeability、commit 列表 |
| 座標留言 | GET `…/comments?pinned=true&pr={id}&dimension=minecraft:overworld`；filters 可省略；pin 含 dimension,x,y,z 與可選 maxX/Y/Z |
| 留言／回覆 | POST `…/pulls/{id}/comments`：body,parentId,pin；PATCH/DELETE `…/comments/{id}`；讀世界的登入者可留言，PAT 需 write，編刪限作者或 admin |
| 合併／審核 | POST `…/pulls/{id}/reviews`：fingerprint,decision；PUT `…/pulls/{id}/choices`：fingerprint,choices；POST `…/pulls/{id}/merge`：fingerprint；舊 tip 回 409 |
| releases | GET `…/releases`、`…/releases/{id}`、`…/releases/{id}/zip`；ZIP 由 reader 授權，建立需 writer |
| webhook 管理 | GET/POST `…/webhooks`、PUT/DELETE `…/webhooks/{id}`、GET `…/webhooks/{id}/deliveries`，admin 角色＋admin scope |

webhook 原始 UTF-8 JSON body：`id,event,at,world:{owner,name},data`。`X-WorldGit-Signature-256=sha256=<hex>`，以 secret HMAC-SHA256 並 constant-time 驗證；`X-WorldGit-Delivery` 穩定 UUID，按 id 去重，`X-WorldGit-Attempt` 從 1 起，同 delivery body 不變。2xx 成功，其餘最多 5 次退避；預設 30/60/120/240 秒，60 秒 worker lease；管理列表不回 secret。

`pr.merged` 的 data 有 pr,number,target,snapshot,commits；`push` 的 data 有 dimension,ref,old,new，逐維度事件不能當成完整 publication。收通知→背景 fetch 驗全組 publication→提示「main 有新版本」→玩家明確 pull 才走 live coordinator，不得自動 apply。只有 PR merge 發 pr.merged，沒有經 Git receive 所以不另發 push。此次不提供 Paper／Fabric 的遊戲內 remote 或 PR 指令。

### 驗收與證據

第四輪已讀取前輪背景程序全部結束後的結果，保留最新實作與測試，沒有重複啟動已通過的驗收。最後執行結果與原始 log／來源檔案 SHA-256 整理於 [acceptance.json](../hub/docs/phase4-security/acceptance.json)。以下時間均為 2026-10-03 UTC。

| 驗收 | 實際結果與證據 |
|---|---|
| SQLite 後端 | 13 suites／58 tests，失敗、錯誤、略過皆 0；12:49 完成。`.work/phase4-hub-sqlite-complete.log`／`-sqlite-complete-results/`；可攜摘要 [sqlite-tests.json](../hub/docs/phase4-security/sqlite-tests.json) |
| PostgreSQL 16 後端 | 測試容器 127.0.0.1:55432、每 context 獨立 schema；13 suites／58 tests，失敗、錯誤、略過皆 0；12:43 完成。`.work/phase4-hub-pg-complete.log`／`-pg-complete-results/`；摘要 [postgres-tests.json](../hub/docs/phase4-security/postgres-tests.json)，不含連線秘密 |
| 完整 `./gradlew build` | 12:47 的 build 成功（4 分 50 秒），12:49 最後 build 成功（19 秒）；最後 XML 共 237 tests，失敗、錯誤、略過皆 0，含 core 95／Hub 58／Paper common 22／Fabric logic 40。`.work/phase4-hub-build-verified.log`、`-build-complete.log`；兩版平台編譯亦通過 |
| hub-web | lint／26 tests／build 通過；12:43 完成。`.work/phase4-hub-web-{lint,test,build}-verified.log` |
| CLI／網頁 PR／Paper | 1.21.11 與 26.2 各完成雙使用者（owner／write）、真三維度 clone/edit/push、受保護 main 的審核與網頁合併、無衝突／衝突區域選擇後 pull、全維度 heads 與 PR 結果相同。每版 clean／conflict／release 各開真 Paper，6 次離線 verify 均 COMPLETE、已追蹤內容差異 0、伺服器 errors 0；baseline 原件未變 |
| Playwright／release ZIP | 兩版共 10 次瀏覽器流程，登入、建立 PR、跨帳號審核、衝突選擇保存／重載、座標聚焦、HTML 注入當純文字、合併、release 下載通過；CSP violation 0、JS／console error 0。ZIP 解壓後直接開 Paper；保留空維度、沒有玩家／session／歷史 |
| 供應鏈 | 實際 jar 70 個 Maven 座標的 OSV 版本命中 0（WorldGit 自身 2 個 jar 無 advisory coordinate），npm 生產／開發依賴 audit 皆 0；證據 [osv-packaged.json](../hub/docs/phase4-security/osv-packaged.json)、[npm-audit.json](../hub/docs/phase4-security/npm-audit.json) |
| 容器映像／冒煙 | 通過。Codex 沙盒因 `cannot write uid_map` 無法建置；主對話於 2026-10-03 17:00 UTC 補驗：`podman build --format docker -f hub/Containerfile .` 成功，`hub/scripts/container-smoke.sh`（SQLite）與 `DB=postgres`（postgres:16-alpine）冒煙皆通過（log：`.work/claude-p4hub-smoke-{sqlite,pg}.log`）；主對話另以目前 jar 重跑完整端到端（`.work/phase4-hub-claude/`，兩版 PASS、10 次瀏覽器流程 error 0）。 |

端到端於 12:22 凍結 jar、12:41 完成；完整原始結果、CLI／Hub／Paper logs、18 張原始截圖與 artifact hashes 在 `.work/phase4-hub-e2e-complete/`，成功 log 為 `.work/phase4-hub-e2e-complete.log`。可攜結果見 [e2e-results.json](../hub/docs/phase4-security/e2e-results.json)。此後完成的 webhook response close 與組織 owner 並行撤權安全修正由上述最後 SQLite／PostgreSQL 全套回歸及 build 覆蓋；端到端 frozen hash 不代表最後重新建置的 jar。第四輪只補文件與證據，不再改動執行程式。

Paper 26.2 在 Nether／End 各新生成 1 個未追蹤 chunk，verify 報告 `untrackedKept=1`；依 #29 保留，其他已追蹤方塊／實體／metadata 差異皆 0。沒有從驗證中排除 DragonFight 或其他已追蹤欄位。本次使用受控平坦場景、凍結 tick 與固定 gamerules，不能代表大型自然世界或線上玩家負載。

精選截圖在 `hub/docs/screenshots/phase4/`：兩版 `*-clean-pr.jpg`、`*-conflict-pr.jpg`、`*-conflict-merged.jpg`、`*-release.jpg`，另保留 `1.21.11-merged.jpg`；共 9 張。端到端 finally 已關閉 Hub／瀏覽器／Paper，刪除大型世界、server、jar、hub-data 與含秘密的暫存設定；本輪未留下修改檔案的背景程序。測試 PostgreSQL 由主對話管理，未停止。

### 失敗與修正

| 執行／發現 | 處理 |
|---|---|
| 前兩輪編譯／啟動：歷史摘要非 public、Spring bean 同名、PR trailer 命名不合 core 規範 | 公開向後相容入口、修正 filter bean 名稱、使用 WorldGit-Merge-PR；保留原 log |
| e2e-r1/r3 release 下載：一般 API 100 萬 NBT nodes 阻擋完整世界，ZIP content type 令錯誤 Map 變 500 | downloads.limits 獨立有限解析預算、未串流錯誤回 JSON 413 並保留安全標頭，補 scope／converter 回歸 |
| SQLite-r3：新增回歸仍期待 null Content-Type，但修補已清楚設 JSON | 改斷言 JSON 與無下載檔名，再重跑整個 suite |
| e2e-r4 ZIP 成功後驗收腳本把 init 回應當作有 state 欄位 | 依實際 snapshot/dimensions/value/error 檢查，沒有放寬 Paper verify |
| e2e-r5／final：release ZIP 缺空維度目錄，Paper 把終界當新世界並重建 DragonFight.Gateways | ZIP 加入 deterministic directory entries，兩格式回歸確認解壓維度完整；保留逐欄位診斷與完整 Paper verify，沒有忽略 DragonFight |
| 安全複查：兩位組織 owner 同時撤掉自己可繞過 COUNT 檢查 | transaction 首個 SQL 取得 owners 寫入鎖，在鎖後驗授權與最後 owner；新增雙 HTTP 並行撤權回歸 |
| 依賴 OSV 命中 HTTP Client/Core 與 Log4j API 的 4 筆版本 advisory | 加入修復版 strict constraints／BOM 並重驗實際打包版本，原始掃描與公告留存 |
| Webhook review：HTTP client GRACEFUL close 可能排空無界 response body | status 後 IMMEDIATE 丟棄連線；新增持續 body 測試驗 3 秒內成功且對端斷線 |
| 容器 build 與 smoke：cannot write uid_map, operation not permitted | 沙盒禁止 UID namespace，沒有提升權限／停止既有測試 PG；保留真失敗 log；改由主對話在沙盒外建置與冒煙，通過 |

### 未完成事項與實際界線

- fork PR、squash／rebase、Hub manual 方塊編輯、S3／多實例共享鎖／配額／session／限流未提供；原有一般 compare／歷史 reader 尚未全面檢查 publication，PR／merge／release 檢查完整 snapshot，PR 另檢查 publication。
- 公開註冊的保留字／冒充／檢舉、重寄／帳號恢復、跨 IP 治理與 bootstrap 政策仍需完成；OAuth 未知 subject 須先建立本機帳號再明確連結，不依第三方 email 自動建／合併。
- ZIP 不是無磁碟串流；單 region 套用／阻塞 socket、webhook DNS lookup 沒有硬 deadline，部署需 proxy timeout。ZIP 與單 owner 寫入共鎖，慢下載可阻擋該 owner；沒有公平隊列。
- webhook secret 需明文保存在 DB 以計簽章；events/notifications/deliveries 沒有自動 retention／dead-letter 管理。遊戲端驗簽／去重／fetch 提示與明確 pull 由下一個平台任務接。
- 真第三方 OAuth／TLS 公開部署、Docker 引擎／arm64／GitHub CI 實跑、任意時點 kill/fuzz 未驗證。容器映像建置與冒煙已由主對話在 Podman 驗證（Docker 引擎未測）。

<a id="paperfolia"></a>

<a id="paper-folia"></a>

## Paper／Folia

日期：2026-10-03；第二輪驗收與文件於 2026-10-04 UTC 結案。基於任務 1 `5dc271c` 與任務 2 `10dd24e`；本次未 commit／push WorldGit，未修改 experiments 或 Fabric 正式原始碼。決定 #92–#97；操作說明見 [Paper README](../paper/README.md)，入口安全審查見 [Paper Phase 4 安全審查](../paper/docs/security-review-phase4-2026-10-03.md)。

### 完成項目與契約

- config.yml 的 remote 非秘密設定：Hub 世界 URL、預設 remote、PAT 環境變數名稱／插件資料夾 600 credentials YAML、socket／REST timeout、預設關閉的 webhook／定時 fetch。未知欄位、scalar 區段、錯誤型別與秘密欄位拒絕；不讀使用者 home、不保存 PAT 進世界，不輸出 token。
- remote add/remove/list/set-url、fetch、push（可選 branch／tags）。新增 remote/fetch/push/pull/pr/comment 權限皆預設 op，comments 共用 comment 權限。遊戲 push 僅 FF，沒有 force；非 FF 提示先 pull。推送的是既有 commit，不自動 capture。
- pull fetch 後經既有 live coordinator dry-run，顯示 FF／三方、衝突區域、受影響 chunk（候選計畫＋精確衝突 atoms，即使預設 ours 沒有立即改方塊）、估計套用時間；sender 綁定一次性確認碼有效 120 秒，必須親自送出 confirm。確認重新 fetch，比對完整遠端 tips／URL；group lock 內重驗 URL、本地 HEAD、乾淨工作區（含 untracked）。套用仍由 ApplyQueue／EditGuard／PlayerProtection／廣播／save／verify／HEAD barrier；保持 #46 的快照 state、不做鄰居更新。衝突沿 Phase 3 MERGING／MergeUi／select／resolve／continue／abort。
- PR create 必要時 push 本機來源；來源只在 remote 時先 fetch 驗全組，再建立 PR。list／view 顯示狀態、mergeability、審核數與 Hub 連結；編號由有界分頁解析為 UUID。**merge／approve 只在 Hub 網頁**，審核與衝突選擇應配合 3D 檢視。PR 清單顯示前 20 列，超出提示 view／Hub。
- comments 世界／PR／維度釘選清單；--here 過濾目前 chunk 相交釘選。comment --here 以玩家 owner thread 的維度／整數座標送出；Hub 作者是伺服器 PAT 帳號，不冒充另一位 Hub 使用者。
- 私人 TextDisplay（最多 64）與範圍 DUST 粒子框（每秒最多 384 點、64 格距離）；只在目前維度已載入 chunk 顯示，不為遠端座標生成世界。Component.text，HTML／MiniMessage 為字面，移除 legacy 色碼、控制／雙向格式字元，作者／摘要有 Unicode 長度限制。hidden by default＋per-player showEntity、nonpersistent，兩版 capture 額外排除插件 text/block display tags；hide／離線／換維度作廢晚到的 REST 回應並在 owner 移除。
- webhook 只接受有界 HTTP/1.1 Content-Length POST；預設 loopback／關閉，HMAC-SHA256 constant-time、delivery＋簽章 event id 持久雙重去重、時效／速率／header／body／queue／完整連線 deadline。push／pr.merged 必須符合目前預設 remote 的世界／本機分支；背景 fetch 驗全組 publication 才對 pull 權限玩家及 console 提示。定時 fetch 至少 60 秒，使用同流程；失敗有限退避，**永不自動 apply**。
- 指令網路走 repo 背景 queue；webhook 使用有界 workers。結果、顯示實體與玩家通知回 Paper 主執行緒或 Folia entity／region／global scheduler。REST 401／403／404／409／429／逾時／不可達以固定 i18n 分類，404 不洩漏 body；JGit 認證失敗亦對應認證提示。

### 給 Fabric 接手的共用模組與協定摘要

共用模組是 `platform-api`，套件 `org.worldgit.platform.remote`，不依賴 Bukkit：

| 類別 | 可重用入口與限制 |
|---|---|
| RemoteSettings | Java record 非秘密設定／Webhook 設定、驗證與保守預設；Fabric 自行接 YAML adapter |
| PlatformCredentials | 指定環境 PAT 優先，否則平台資料夾直接子檔案 600 YAML；Secret 交 core Credentials；webhook secret 環境或 600 純文字檔。插件／模組資料夾必須普通非 symlink，不讀 home |
| HubClient | JDK 21 HttpClient，禁止 redirect；世界 URL 推導含反向代理 prefix 的 REST／web endpoint。pulls/find/view/create/comments/comment；Pull／Detail／Pin／Comment DTO。沒有 merge／approve 方法 |
| HubClient.Error／Failure | 不攜帶原始 body／cause／token 的分類；caller 映射自己的 i18n，所有 client 操作須在背景 executor，try-with-resources 關閉 |
| WebhookReceiver | 有界 ServerSocket receiver；constructor 的 Predicate<Event> 只能 enqueue 通知／fetch，不能修改世界；close 停止 sockets／workers／deadline；replay YAML 需跨重啟保存 |
| CommentText | plain／display 純文字 Unicode 摘要；Fabric 仍須用 literal Component/Text，不能再解析 MiniMessage |

runtime 使用與 Hub 一致的 Jackson BOM **2.21.7**（不是 Paper 專用 library）；Fabric 正式 jar-in-jar 接線時需包含 Jackson runtime。現有 Fabric build 不會因這次新增共用類別而自動打包其所有外部依賴，下一任務必須補齊並驗 dedicated／client。核心僅新增保留舊預設的 GitTransfer／WorldRemotes timeout constructor overload，以及 hasBranch 全維度查詢，舊呼叫介面仍可用。

Fabric 下一任務還要接 YAML／憑證資料夾／權限、server executor 與玩家／世界 owner thread、自己的 live coordinator、私人顯示／清理生命週期；不能把離線 Anvil apply 搬到線上。沿上方核心「線上 pull 的預覽／套用契約」傳入 fixed targets／expectedHeads，confirm 前重新驗遠端，不因通知直接 apply。

本次沒有新增 `remote_status`／`comments` plugin message 或 capability。通知已是 i18n 聊天；原版客戶端與 Fabric 客戶端皆能渲染 Paper 發出的私人 TextDisplay／粒子。現有 Phase 3 diff／conflict-select／MergeUi 協定保持相容；Fabric 若下一任務需要自訂 HUD／留言 renderer，再按實際 UI 定義可選 capability，見 [protocol README](../protocol/README.md)。

### 驗收、截圖與原始證據

`paper/tools/phase4.py` 使用真 Hub jar＋SQLite、CLI clone 與兩個 wgbot、真 Paper／Folia live apply。PR 審核／合併與網頁釘選留言走相同 Hub REST，沒有用假 Hub 代替端到端。`--screenshots` 另啟動真 Fabric 客戶端（Xvfb），檢查收到的 TextDisplay 純文字／click event，截取 show／hide；fixture 位於 paper/tools，暫時加入 gametest source set，不納入正式 Fabric jar。Hub 8096、Paper 25721–25722、Folia 25723–25724、webhook 25731–25734；各腳本自持 bench.lock，finally 停服／停止 Hub、bot、客戶端並刪除秘密與大型複本。

第二輪接續先讀取已完成的兩份背景 log：`.work/p4-paper-e2e-final-r3.log` 的四組 Phase 4 全通過；`.work/p4-paper-regressions-final.log` 的九組既有回歸有三組失敗。Paper 1.21.11 Phase 2 已有修正後完整通過的獨立紀錄，因此本輪沒有重跑它，也沒有重跑三組已通過的 interop。另兩組 Paper 26.2 失敗的真正原因是 live gamerule 與磁碟別名不同，修正後重驗；本輪另補 Folia 26.2 Phase 2／3／interop。

可攜驗收摘要、原始結果／log SHA-256、凍結 jar 與最終原始碼雜湊見 [results-2026-10-03.json](../paper/docs/phase4/results-2026-10-03.json)。Phase 4 四組均各通過 20 項檢查：真玩家 remote／push／PR、通知不自動套用、FF 明確確認、遠端 tip 競爭拒絕、非 FF、三方衝突／MERGING／選擇／resolve／push、非 op 拒絕、雙玩家留言隱私、純文字真客戶端截圖、hide／換維度／離線清理、錯 PAT、不可達時 tick probe 與最終全組離線 verify。

| 驗收 | Paper 1.21.11 | Paper 26.2 | Folia 1.21.11 | Folia 26.2 |
|---|---|---|---|---|
| Phase 4 真 Hub／live apply／留言 | 通過，webhook | 通過，webhook | 通過，webhook | 通過，定時 fetch |
| 既有 Phase 2 完整場景 | 通過（修正 bot 後 39 項） | 通過（39 項） | 通過（39 項） | 通過（39 項） |
| 既有 Phase 3 完整場景 | 通過（90 項） | 通過（90 項） | 通過（90 項） | 通過（90 項） |
| 真 Fabric 客戶端 ↔ Paper／Folia | 通過（17 項） | 通過（17 項） | 通過（17 項） | 通過（17 項） |

本輪選擇性的五組補驗全部成功，log：`.work/p4-paper-regressions-round2.log`，原始結果：`.work/paper-phase4/regressions-1791068547/results.json`；最後 Folia 26.2 interop 在 2026-10-04 00:15 UTC 完成。上一輪 `regressions-1791058177/results.json` 仍保留原始三組失敗，沒有改寫成成功；最終判定採上表各自最新的通過證據。真客戶端對接每平台都驗 Ghost／Set blocks／Resolve、200 區域重連與實際多包傳輸、清單清理、四張 UI 截圖與最終離線 verify。

所有本任務的 Hub／server／bot／Fabric 客戶端與 Xvfb 已結束，負載鎖可重新取得，驗收 ports 全部關閉；run 世界／伺服器與秘密已刪除。本輪保存的兩版平坦 fixture 雜湊最終比對相同；保留原始結果、log、截圖與來源雜湊，移除已用完的凍結 jar。沒有留下背景驗收或子代理。

既有回歸保留三維度完整 verify、逐格 client／server／快照 hash、玩家保護、光照／POI、UUID、MERGING 重啟／工具／GUI／abort／continue、乾淨與衝突 patch。各平台 Phase 2 都有三次 1000 chunk switch；Phase 3 各有一次 1000 chunk merge，以及 2864 stored chunks／200 區域世界中的六次單區切換。

| 平台 | Phase 3 1000 chunk merge | tick probe TPS 估值 | 200 區域單區切換中位數／最大 |
|---|---|---|---|
| Paper 1.21.11 | 151.76 s | 19.98 | 0.701／0.703 s |
| Paper 26.2 | 151.81 s | 19.99 | 0.701／0.704 s |
| Folia 1.21.11 | 125.60 s | 19.97 | 0.601／0.701 s |
| Folia 26.2 | 117.55 s | 19.96 | 0.701／0.701 s |

tick probe 是出生點所屬 region 的間隔，Folia 不代表所有 region。Phase 4 錯 PAT／不可達的短量測中，四組 p95 都為 50.1 ms、最大 50.2–50.5 ms、沒有超過 100 ms 的間隔；這不是長時間玩家負載量測。Phase 4 換維度測試可能生成未追蹤 chunk，依 #29 保留並回報 `untrackedKept`；已追蹤內容／metadata 仍須差異 0。

完整 `./gradlew build --configure-on-demand --max-workers=1 --no-daemon` 於第二輪通過，19 秒、77 tasks（6 executed／71 up-to-date），log：`.work/p4-paper-build-round2.log`。JUnit XML 共 **254 tests，failure／error／skip 都是 0**：core 95、CLI 4、Hub 58、i18n 3、platform-api 19、protocol 7、Paper common 28、Fabric logic 40；兩版 Paper／Fabric 的正式程式亦編譯成功。單元覆蓋設定型別／token 遮罩／權限、REST 分頁／302／401／403／404／409／429／慢 body deadline／4 MiB、webhook 簽章／重放／重啟／超大 body／速率／header budget／slow headers／enqueue 重試、留言淨化與晚到回應清理。

Phase 4 凍結插件 jar 為 `6d1a7b59…`；第二輪只補上 PaperLiveWorld 的 26.2 gamerule 主世界別名，最終 jar 為 `636148e3…`（完整雜湊見可攜摘要）。不能把較早的 Phase 4 截圖／log 說成用最後重建的 jar 重跑；本輪修正由 Paper／Folia 26.2 完整 Phase 2／3 的 gamerule 變更、live apply、停服後全欄位 verify 與 Folia interop 覆蓋。沒有排除 gamerule／metadata，也沒有放寬 verify。

精選 [八張截圖](../paper/docs/screenshots/phase4/README.md) 是四組平台各一張 show／hide，第二輪逐張人工確認文字位置、HTML／MiniMessage 字面與 hide 後消失。client 同時檢查沒有 click event；另一位玩家不可見由雙 bot 的 wire entity id 與真正 TextDisplay id 比對證明。hide 後少量粒子是已送出的短暫效果，不能當成 TextDisplay 存留。

### 失敗、修正與未完成事項

| 執行／發現 | 處理與證據 |
|---|---|
| 第一輪編譯：RemoteConfigTest lambda 捕捉重設變數；後續新增 locale 測試缺 Supplier return | 分開 invalid fixture／補 return；保留 build log，最新完整 build 通過 |
| e2e 第一輪把 merge API 回應當成 PR detail | merge 後另 GET detail，依 REST 真契約檢查完整 commits map |
| 早期截圖 fixture 忘記 disconnect，gametest 判定仍連線 | 明確 disconnectFromWorld 後等 level=null，再停止客戶端／process group |
| 錯 PAT 的 JGit 回應是「Authentication is required but no CredentialsProvider…」 | 對應固定 401 i18n；新增真錯誤 PAT 與原訊息回歸。狀態解析排除 URL，避免世界名稱／port 內數字被誤認為 HTTP code |
| deop 與 bot 指令競爭 | 先等待 console 撤權成功，再送指令；沒有放寬權限斷言 |
| 人工檢查發現早期截圖沒有文字：camera 落下／vanilla tick freeze 阻止 Display render state | 把自動成功但畫面失敗的紀錄標成 manualVisualReview=false；截圖時暫時恢復 tick。相機改由玩家 owner 設 flight/no-gravity＋teleportAsync，畫面逐張人工確認；舊圖不作正式存證 |
| 26.2 bot 把 TextDisplay 名稱辨識成 tnt | 不靠舊 registry 名稱計數；由真正 Bukkit TextDisplay 查 wire entity id，再比對兩位 bot 收到的 id，另用真正 26.2 客戶端檢查型別、純文字與畫面 |
| 26.2 截圖 fixture 沿用 1.21.11 的 hideGui／setScreen，client 編譯失敗 | 改用測試輸入按 F1 與既有版本化 GameTestScreens；只修 paper/tools 驗收 fixture，不改 Fabric 正式功能。失敗證據 `.work/paper-phase4/paper-26.2-1791057034/results.json`；後續真 26.2 客戶端截圖通過 |
| 預設 ours 沒有立即變動時，衝突預覽顯示 0 affected chunks | union 候選 chunks／entity hints／精確衝突 atoms，metadata/file 不假造 chunk；新增測試與真 1-chunk conflict 斷言 |
| 換行／tab 轉空白可繞過字數統計 | 轉成的空白同樣計入 Unicode 上限，新增大量控制字元測試；保留 HTML／MiniMessage 字面與 emoji 邊界測試 |
| Folia 26.2 polling：pull 已完成 MERGING，緊接 conflict-select 卻讀到舊 UI 快取 | RepoService 在同一 executor、完成 future 前讀取 durable MERGING 並發布 UI，避免 refresh 排在定時 fetch 後；不加等待來掩蓋問題。失敗證據 `.work/paper-phase4/folia-26.2-1791058176/results.json`；後續驗收讓 remote／fetch／push／pull／PR／comments 全部由真玩家送出 |
| Paper 1.21.11 Phase 2 舊鬼影斷言失敗；實際已收到 clear | wgbot 把 clear 當成單一 id 刪除；協定／真 Fabric ClientPreviews 是清除所有 ≤ floor 的 previews。修正 bot 依契約以 BigInt 比較，不放寬斷言；保留第一輪失敗 log，重新驗整個 Phase 2 |
| 真玩家版 Folia 26.2 已通過留言離線清理，最後錯 PAT 測試發生 BrokenPipe | 驗收腳本沿用已登出的 bot；改成重新登入，再由玩家送出 fetch／不可達測試。失敗證據 `.work/paper-phase4/folia-26.2-1791059543/results.json`；不是把已關閉 bot 改回 console 來略過玩家執行緒驗收 |
| 既有 Paper 26.2 Phase 2 reset／Phase 3 停服 verify 出現 `game_rules.dat.nbt` 差異 | core WorldLayout 的主世界別名讀到舊磁碟值，Paper live capture 只更新有維度前綴的名稱。讓主世界兩個名稱使用同一份 live gamerule NBT，保留設定追蹤與線上 metadata 預檢；重驗完整 Phase 2／3，不以重存 commit 或忽略 metadata 繞過。原失敗 `.work/paper-phase2/paper-26.2-1791062135/results.json`、`.work/paper-phase3/paper-26.2-1791063036/results.json` |

Folia 正常停服時 nonpersistent display 隨世界卸載清除；一般 hide／離線／換維度走 entity owner 移除。**第三方熱卸載插件後立即跨 region 刪除仍無保證**：onDisable 時 Folia region scheduler 可能已不可用，本次不越過 owner 限制強刪。Paper 停用可同步清除。這項 Folia 熱卸載邊界仍未完成，不能把正常停服結果當成已支援第三方熱卸載。

gamerule 修正只影響後續 capture；先前在 gamerule 變更後保存的 26.2 歷史可能已有不一致的別名，讀取時仍依完整 metadata 契約驗證。本次修正不改寫既有 commit。

公開 TLS／代理部署、大量並行 socket flood、磁碟故障／任意時點 kill／fuzz 未實跑；loopback、固定平坦 fixture、凍結自然 tick 的結果不能代表大型自然世界或真玩家負載。顯示僅覆蓋 show 當下已載入的 chunk，移動到新 chunk 需再次 show；沒有自訂 Fabric HUD 或無限距離留言巡覽。本次沒有遊戲 force、PR merge／approve、token 管理指令，沒有自動 apply。
