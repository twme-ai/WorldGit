# 17 — Axiom 互通性研究與整合

研究日期：2026-10-08。基準為 Phase 5（`ca83d51`）；決定 #142–#146。此整合沿用 #11 的自行歸屬、#37／#39 的線上鎖、#121／#133／#139 的 player-touched，不改既有 repo 格式。

## 研究材料與授權

- [Axiom](https://modrinth.com/mod/axiom) 6.1.3：Fabric jar 的 `fabric.mod.json` 為 All Rights Reserved，`environment: *`，有伺服器端入口；可用於單人整合伺服器及 dedicated，並非只能安裝在客戶端。
- [AxiomPaper](https://github.com/Moulberry/AxiomPaperPlugin) 6.0.1：Paper 上的伺服器元件，MIT。來源固定在 `69c1b0a2fff6999dddacfe945c33eccf66877b8d`；該 master 已到 26.3，因此另以 **1.21.11／26.2 正式 jar 的 javap** 核對事件、CustomIntegration、OperationQueue 與封包簽章，不能只用 master 猜發行版。
- `paper/axiom-api` 保留五個用到的 Bukkit 事件及 CustomIntegration 最小編譯簽章，附 MIT LICENSE、來源 SHA；獨立 `compileOnly` 專案，CI 不必建置 AxiomPaper 或下載閉源模組。`verifyOptionalApi` 檢查正式插件不含 `com/moulberry/axiom/`。
- 所有下載 jar、`javap -p -c` 輸出均放在 `.work/axiom/`。Fabric 只分析方法簽章、呼叫與控制流程以實作互通性；沒有反編譯成 Java，沒有散布閉源 bytecode／程式碼。WorldGit 的 mixin 與 bot 是自己的實作。

| 材料 | SHA-256 |
| --- | --- |
| Axiom 6.1.3／1.21.11 | `cda9d880fbc3729f0000c8e365ce58621a9f1b3a419dc2cd755817bb9e9d91f0` |
| Axiom 6.1.3／26.2 | `63687ab24ece38749449de3467b8bf454fc3e20e81b7ce1a5d9eb9e65f3fb1cd` |
| AxiomPaper 6.0.1／1.21.11 | `042437e74212be2b6687aa495ce3115f4272700171601620f00551d77be34f97` |
| AxiomPaper 6.0.1／26.2 | `06f38a38e068e6008663b42c031df44f83cbb4dd097f945c234a5d5d64be0018` |

原始發行檔：[Axiom／1.21.11](https://cdn.modrinth.com/data/N6n5dqoA/versions/4fv8luPO/Axiom-6.1.3-for-MC1.21.11.jar)、[Axiom／26.2](https://cdn.modrinth.com/data/N6n5dqoA/versions/Sc1qsNWg/Axiom-6.1.3-for-MC26.2.jar)、[AxiomPaper／1.21.11](https://cdn.modrinth.com/data/evkiwA7V/versions/AtKpiumb/AxiomPaperPlugin-6.0.1-for-MC1.21.11.jar)、[AxiomPaper／26.2](https://cdn.modrinth.com/data/evkiwA7V/versions/93qRRLuz/AxiomPaperPlugin-6.0.1-for-MC26.2.jar)。上游授權與下載頁仍由上游維護，不把 Axiom 隨 WorldGit 發佈。

## 寫入路徑與原有涵蓋程度

Paper 原始碼位於 `packet/impl`、`operations/SetBlockBufferOperation`、`integration/Integration`。Fabric javap 研究的是兩版 `com.moulberry.axiom.packets.AxiomServerbound*`，1.21.11 的 Minecraft 呼叫為 intermediary，26.2 為 Mojang 名稱；用專案內 mappings 對照 Minecraft 方法。

| 資料 | Paper AxiomPaper | Fabric Axiom（單人／dedicated 相同 server handler） | 原有 WorldGit 行為 |
| --- | --- | --- | --- |
| 方塊 | `set_block` 可走 `ServerLevel.setBlock` 或直接 `LevelChunkSection.setBlockState`；`set_buffer` 直接改 section，更新 heightmap／POI／光照並 `markUnsaved`。buffer 透過 OperationQueue，可跨 tick／等非同步載入。 | `SetBlock.handle` 同樣有 vanilla 與 section 路徑；`SetBuffer.handle` 呼叫同步 `applyBlockBufferServer`，直接改 section 後 `markUnsaved`。此版未見 Paper 式分批 server operation queue。 | Paper unsaved／磁碟 index／最終內容比對可捕捉內容，但直寫不會產生一般方塊事件與歸屬。Fabric `LevelChunk.markUnsaved` mixin 原已保留 generation，故內容可捕捉；原 `LevelChunk.setBlockState` 鎖攔不到 section 直寫。 |
| Block entity | buffer 的壓縮 NBT 解析、移除舊 BE、建立／註冊新 BE、載入資料及更新 ticker；set_block 也可能帶物品的 BE 資料。 | buffer 有相同的 section、BE 建立／載入及 tick 更新流程。 | BE 在 chunk 快照內，仍依既有正規化與 `.wgignore` 入庫；沒有通用玩家來源。不能只攔方塊 setter 就宣稱涵蓋 BE。 |
| Biome | buffer type 1 直接改 section 的 PalettedContainer，`markUnsaved` 後送 biome packet。 | 同步 `applyBiomeBufferServer` 直接改 biome container，標未存檔並通知玩家。 | section biome 的內容比對可捕捉；一般 block lock、block 事件均不涵蓋。 |
| 實體生成 | NBT 生成根／乘客，嘗試加入世界；**AxiomSpawnEntityEvent 在成功加入後**才觸發，取消時 Axiom 移除根與乘客。 | NBT 生成、`tryAddFreshEntityWithPassengers`／`addFreshEntity`，在 handler 內完成。 | `entities: all` 可抓到。既有 COMMAND spawn 事件可能已涵蓋部分 Paper 類型，不能假設全漏或全部涵蓋；creative 必須可靠地把 Axiom 根／乘客記為觸及。Fabric 原 TouchScope 沒有 Axiom handler 來源。 |
| 實體操作／刪除 | manipulate 前有可取消事件，完成後 After 事件；改位置、旋轉、NBT、乘客關係。remove 事件在移除前。 | `ManipulateEntity.handle` 查 UUID、改位置／NBT／乘客；Delete handler 查 UUID 移除。 | creative 對未觸及自然實體的操作會漏追蹤；移除已追蹤 UUID 可由完整 capture 發現，不應因刪除而新增未知 UUID。 |
| 時間 | set_world_time 呼叫 modify 事件及 TimeChangeEvent，再用 Bukkit setTime／advance_time gamerule。26.2 的 setTime 寫 default world clock。 | 1.21.11 SetTime 改 DayTime／advance_time；26.2 改 default clock 的 totalTicks／paused，取消暫停也設 advance_time=true。 | **1.21.11** level 白名單不追蹤 Time／DayTime，gamerule 可追蹤。**26.2** 的 `data/minecraft/world_clocks.dat` 由一般 SavedData 完整捕捉，含時間／paused／rate；只排除 Paper level_override 的 game_time 並不排除 world clocks。因此時間會造成 world-meta 差異，兩端線上還原須依既有預檢，必要時離線 CLI。 |
| 世界屬性 | property registry 更新 gamerules、天候、邊界等；modify 取消時仍回 ACK。擴充屬性也可能只存在插件記憶體。 | property holder 更新 ServerLevel，handler 最後確認 updateId。 | 寫進受支援 world-meta／saved-data 的設定可捕捉；純客戶端視覺、插件記憶體屬性與 Axiom clipboard／undo history 不入庫。Paper 線上 world-meta 套用仍拒絕；Fabric 沿用 README 的有限 metadata 套用範圍。 |
| 強制 tick／鄰居更新 | **tick_blocks 未呼叫 canModifyWorld，也沒走 CustomIntegration**，可直接 tick fluid／重算 neighbor shape。 | TickBlocks handler 可同步造成世界寫入。 | vanilla freeze 不代表此封包會停止；必須另擋入口。Paper 此路徑內容可捕捉，歸屬無座標 API，保留未知。 |
| 修復區域 | 兩版 AxiomPaper 6.0.1 沒有註冊 fix_area。 | FixArea handler 同步重算 heightmap、光照與 POI，並移除不相容 BE／補建缺少的 BE；也會 markUnsaved。 | 衍生 heightmap／光照不入庫；修復 BE 的內容會入庫，必須和一般編輯一樣受鎖與作者來源保護。 |
| Axiom 註記 | UpdateAnnotation 先呼叫 modify，寫世界的 Bukkit PDC。 | AnnotationUpdate 同步寫 ServerAnnotations SavedData 並 setDirty，整合也擋此 handler。 | **1.21.11**：Paper 的 level BukkitValues 及 Fabric 自訂 annotation saved-data 不在既有捕捉範圍。**26.2**：一般 SavedData 會捕捉 Paper 的 `data/paper/persistent_data_container.dat` 及 Fabric 的 `data/minecraft/axiom_annotations.dat`；其內容屬 world-meta，線上不能直接覆寫仍快取的資料，須離線還原。玩家模式、飛速與傳送等玩家狀態仍不版本化；探索的新 chunk 依既有規則捕捉。 |

`AxiomModifyWorldEvent` 也用於部分讀取／檢查請求，且沒有座標；把事件後「新變成 unsaved」的 chunk 全歸給玩家，會把自然更新、其他玩家／插件、原已 dirty 的 chunk 混在一起。因此沒有使用時間窗口。內容能被偵測也不代表能還原所有世界屬性，仍須遵守各平台既有 apply 預檢。

26.2 的時間／註記結論來自正式 Minecraft／Paper jar 的 SavedDataType 路徑、Axiom handler 呼叫及 `WorldLayout`／`SavedData.capture` 逐項核對。SavedData 對新版未知 `.dat` 保留完整 NBT，不能把 level 白名單的排除推論到全部 saved-data。這次沒有修改全域時鐘正規化；日間時間變動也可能使 26.2 的線上 switch 因 metadata 預檢拒絕，屬既有平台限制。註記並未加入專屬線上還原／客戶端重新載入 API。

## Paper 整合

`softdepend: AxiomPaper`，只在非 Folia 且 AxiomPaper 已啟用時載入 AxiomHook。Folia 的插件本身仍可啟動；AxiomPaper 的兩版 plugin.yml 沒有 folia-supported，且 OperationQueue 要求主執行緒，不宣稱 Axiom／Folia 相容。

1. 四個可取消事件用 HIGHEST／ignoreCancelled 阻擋被鎖世界。世界有局部鎖時，也保守地拒絕整個該世界的 Axiom 修改，因 modify 事件沒有範圍。
2. CustomIntegration 的 `canPlaceBlock`／`canBreakBlock`／`checkSection` 提供明確 player、world、chunk。相同 API 也被唯讀 `request_entity_data` 與 debug 使用，因此用有界 StackWalker 核對兩版的 SetBlock／SetBlockBufferPacketListener（含主執行緒 biome lambda）／SetBlockBufferOperation 寫入 frame，才記 cause=axiom 及 dirty；讀取不記來源，不以跨 tick ThreadLocal 承接非同步 buffer。frame 不符時保留未知。section 是嘗試編輯的範圍上界，仍以內容比對決定實際差異；這是 chunk 級貢獻摘要，不是逐格審計。
3. `axiomadmin.bypass_region_checks` 會跳過 CustomIntegration。此時方塊／BE／biome 內容仍由 unsaved／index 捕捉，缺少的歸屬保持未知，不拿同世界另一個 Axiom 玩家代填。實體事件有玩家與座標，仍可歸屬。多人改同 chunk 可有兩位 contributor，依既有規則選主要 git author。
4. 所有套用共用 `PaperLiveWorld.lockEdits`：設鎖後、在 server owner 凍結前，以 Axiom queue 的 executionLock → queueLock 移除該世界已有的 SetBlockBufferOperation。否則套用前接受的 buffer，尤其 bypass 玩家，仍可在鎖期間跨 tick 寫入。移除後發通知／重送區塊；不在套用結束後重播過時 buffer。取消前已完成的部分及已排程的 chunk 載入不會反向復原，未完成操作也沒有跨工具原子性。
5. tick_blocks 及 set_block 的直送 listener 與 tunnel registry 共用防護代理。前者補上缺漏的鎖；後者拒絕時讀取預測序號並呼叫伺服器確認方法，再重送已載入的玩家視窗區塊，不因拒絕而載入新 chunk。
6. spawn MONITOR 及 AfterManipulate 記錄成功、仍存在的實體根／乘客；remove 只標 dirty／記歸屬，完整成功 commit 依舊清掉不存在的 touched UUID。

queue／packet guard 的少量反射及寫入 frame 是版本結構相依，已核對兩版 6.0.1。若 queue／packet 結構、註冊或型別不符，明確記錄啟動警告，並在 AxiomPaper 仍啟用時**拒絕 WorldGit 套用**，避免無保護地改世界。一般 status／commit 仍可用；更新整合或停用 AxiomPaper 後才能套用。這不是任意未來 AxiomPaper 版本的保證。

權限 API 記錄的是可定位的編輯嘗試上界；其他整合稍後拒絕、相同狀態的 no-op、局部 section 權限或後續覆寫，均不能解讀成該玩家改了這個 chunk 的每一格。Contribution trailers 可列多位直接參與此 chunk 的玩家，不提供逐格最終責任。缺乏玩家／座標或寫入 frame 的路徑保持無 Axiom 歸屬；既有手動 commit 仍使用執行指令者作 git author，不能把這個 fallback 當成已找到 Axiom 作者。

## Fabric 整合

選用 `@Pseudo` mixin 指向兩版 6.1.3 的 `handle(MinecraftServer, ServerPlayer)`。不引用 Axiom 型別、不需其 jar 編譯，IMixinConfigPlugin 在 `axiom` 未安裝時跳過全部 Axiom 專用 mixin／lookup。修改點是自己的封包包裝及 Minecraft 方法 hook，沒有複製 Axiom 實作。

- SetBlock／SetBuffer／Spawn／Manipulate／Delete／SetTime／SetWorldProperty／TickBlocks／FixArea／AnnotationUpdate 的 handler 開始前檢查套用鎖。沒有範圍的共同入口保守地在任一套用期間拒絕 Axiom 請求，即使另一維度也短暫受影響；內部 WorldGit apply 不經 Axiom handler。
- handler 以 try/finally 建立 ThreadLocal 玩家來源與 AXIOM TouchScope。Minecraft 的 `markUnsaved` 在此範圍內提供實際維度／chunk，記玩家與 axiom；本次範圍每個 chunk 去重。無時間窗口，其他玩家的下一個 handler、自然 tick、非 Axiom 模組的寫入不會繼承來源。
- 同 chunk 的原因也依玩家 UUID 分開保存。修正原 Fabric Attribution 的 chunk 共用 causes，避免普通 place／break 玩家繼承另一位玩家的 axiom 原因；不可變 capture 與 generation 確認規則保持不變，新增混合玩家／維度／capture 後寫入的回歸測試。
- addFreshEntity 成功後暫收根／乘客，在 handler 收尾保存觸及閉包與歸屬。manipulate 的 UUID lookup 只在操作範圍內暫收，完成後納入存活實體及乘客／載具；包含請求所參照的關聯實體。這是「玩家操作觸及」的上界，不聲稱每個參照實體的 NBT 都改變。
- delete 不為從未觸及的 UUID 新增資格。entity-only 刪除的內容由完整實體 capture 判定；若該 handler 沒有 markUnsaved 或新增／操作實體可定位，chunk 級作者資訊可能缺少，保持未知。
- 拒絕 SetBlock 時回原版 block ACK；世界屬性回 Axiom update ACK；每玩家每秒至多一次通知／已載入區塊重送。設定無新開關，訊息使用共用 MiniMessage 的 en_us／zh_tw。

同步 applyBlockBufferServer 的實際寫入均在 handler 內，故單人和 dedicated 用同一整合。此結論限定被核對的 6.1.3；若未來改成非同步寫入或另有入口，必須重新核對，不能用現有玩家 scope 猜跨執行緒來源。required mixin 的目標方法改名／簽章不符會明確失敗，不悄悄宣稱已有鎖。

## 預覽、鬼影與客戶端狀態

WorldGit Fabric 的 ghost 是客戶端疊圖，不寫世界；Axiom 從真 server chunk／BE 取得資料，不能把 ghost 當成已套用的方塊。Paper 的 WorldGit BlockDisplay fallback 是伺服器管理的暫態顯示，沿用既有快照排除。兩種工具的外框、半透明模型及 Axiom editor preview 可能視覺重疊，沒有統一的 renderer API；可先 `/wg clear` 再操作 Axiom。

拒絕會確認可識別的封包序號，重送原版區塊並提示等待。這可以同步原版世界畫面，**不會重寫 Axiom 的私有 clipboard、undo／redo 或 editor cache**。套用後／收到拒絕訊息後應重新整理或重新開啟 Axiom 編輯預覽；不要將套用前的 undo 當成 WorldGit 歷史。GUI 狀態尚不能以 bot 等同完整實測，截圖的驗收範圍另列於下方。

## Fabric 存檔的離線 CLI

Fabric 的 `level.dat` 啟用清單含模組內建 `axiom` pack。線上 ModPacks adapter 可提供資源；CLI 原本會拒絕沒有 adapter 的未知 pack，即使研究的 6.1.3 沒有 entity_type tags。不能只把 axiom 加進空資源白名單，否則未來版本或不同檔案可能改變忽略規則的語意。

CLI 新增可重複的 `--mod-pack id=jar`。指定與伺服器相同的正式模組 jar，僅以 ZipFile 讀 fabric.mod.json、核對 id，載入根目錄的 entity_type tag 資源；不載入 Java 類別、不執行 Axiom、不需要放入 CLI classpath。解析沿用同一套嚴格格式、參照與預算檢查。資源傳到 OfflineWorld／WorldOperations 的 capture、ignore、規劃與寫回；不認得的其他 pack 仍拒絕。模組的不同內建子 pack／其他模組需各自的 adapter，不宣稱任意 mod registry／codec 均可離線重建。

```sh
java -jar cli/build/libs/wgit.jar --world /path/to/world \
  --mod-pack axiom=/path/to/Axiom-6.1.3-for-MC1.21.11.jar \
  --format=json verify HEAD --dimension minecraft:overworld
```

不提供 jar 時原本的診斷保留；不會根據附近 mods 目錄自動信任任意檔案。core 測試涵蓋空 pack、實際 tag／遞迴參照、未知 pack、錯誤 manifest、缺必要參照與資源超限；CLI 測試串接 init、ignore、restore 與嚴格零差異 verify。

## 真伺服器與回歸驗收

`paper/tools/axiom.py <paper|fabric> <1.21.11|26.2> [--screenshots]` 自取 bench.lock。使用真伺服器與兩位協定 bot，API 10 handshake、set_block、單一 palette set_buffer、spawn／manipulate／delete／time；不需要重製 GUI。斷言直接查世界、git contribution trailer、不可變 HEAD entity blob、真實 switch journal、拒絕聊天及完整離線 verify。受測世界副本、bot、server／client 在 finally 清理；每次 result.json、console、完整 payload／聊天事件保留。

所有最終命令使用 `.work/gradle-home`、`.work/npm-cache`、`.work/ms-playwright`；Python runner 自取鎖，Gradle 單獨外包 flock，沒有把自帶鎖腳本再包一次。套用使用真 `switch` 的 LOCKING／APPLYING journal，沒有以 debug lock 代替；Paper 另注入真正等待 future 的 SetBlockBufferOperation，驗證舊佇列及 bypass 一併取消。

| 真 Axiom 驗收命令 | 結果 | 原始結果 |
| --- | --- | --- |
| `python3 paper/tools/axiom.py paper 1.21.11` | 53/53 通過 | `.work/axiom/paper-1.21.11-1791445919/result.json` |
| `python3 paper/tools/axiom.py paper 26.2` | 54/54 通過 | `.work/axiom/paper-26.2-1791447260/result.json` |
| `python3 paper/tools/axiom.py fabric 1.21.11 --screenshots` | 56/56 通過，dedicated＋真客戶端 | `.work/axiom/fabric-1.21.11-1791445407/result.json` |

三組皆驗證 status／commit、兩位玩家各自的 axiom Contribution、唯讀請求不記作者、不新增實體資格、creative 生成／操作實體入庫、真正套用拒絕、en_us／zh_tw、block prediction／world property ACK。Fabric 額外有 chest BE 與 fix_area 修復／拒絕的正向控制。各組線上 verify 成功，離線 verify 的 state=COMPLETE，chunks、sections、biomeSections、entityPuts、entityRemoves、chunkDeletes、untrackedKept、metaFiles **八項全為零**。伺服器、兩位 bot 退出碼均為 0，連接埠已關閉，server log 沒有 WorldGit／Axiom／mixin 錯誤。

真客戶端同時載入 Axiom 6.1.3 與 WorldGit，完成 CLIENT_HANDSHAKE_OK、`status --show`，透過真 key binding 開啟編輯器，EditorUI.isEnabled=true。原始 PNG 為 [Fabric 1.21.11](../fabric/docs/screenshots/axiom/fabric-1.21.11.png)，SHA-256 `f1a354faef44a4597c0f680583c593b64c4fca7fa5ad9b82d3812fec30e7cd0c`；[截圖說明](../fabric/docs/screenshots/axiom/README.md) 列出範圍與開發客戶端的非整合錯誤訊息。Axiom 原始 jar 的內嵌依賴在驗收時解到 `.work/axiom/client-deps` 並以 modLocalRuntime 載入，沒有 include 進 WorldGit 發佈 jar。

回歸與完整建置的最終結果於以下表格記錄，原始日誌在 `.work/axiom/regressions/`。

| 無 AxiomPaper 啟動命令 | 結果 | 原始結果 |
| --- | --- | --- |
| `python3 paper/tools/axiom-optional.py 1.21.11` | 5/5 通過 | `.work/axiom/without-axiom-1.21.11-1791447815/result.json` |
| `python3 paper/tools/axiom-optional.py 26.2` | 5/5 通過 | `.work/axiom/without-axiom-26.2-1791447838/result.json` |

兩組使用正式 WorldGit plugin jar，沒有安裝 AxiomPaper；檢查 compileOnly API 未打包、啟動成功、help 指令成功、退出碼 0、無 linkage／WorldGit 錯誤及連接埠關閉。

| 既有回歸命令 | 結果 | 原始結果 |
| --- | --- | --- |
| `python3 paper/tools/phase5.py paper 1.21.11` | 50/50 通過 | `.work/paper-phase5/paper-1.21.11-1791447855/results.json` |
| `python3 paper/tools/acceptance.py paper 1.21.11` | 32/32 通過，FAILED: none | `.work/paper-delivery/results/paper-1.21.11.json` |
| `python3 fabric/tools/accept-phase5.py 1.21.11 --mode dedicated` | 172/172 通過；bot、真客戶端、YAML 停用與第三方別名衝突；兩個埠已關閉 | `.work/fabric-phase5/dedicated-1.21.11-1791448679/result.json` |

Fabric 回歸在上一輪用量中斷後由既有 runner 接續跑完，沒有重複啟動。這輪沒有安裝 Axiom，涵蓋選用 mixin 關閉時的既有行為。十張新回歸畫面另存 `.work/axiom/regressions/phase5-screenshots/`，原有 Phase 5 文件截圖已恢復；`screenshot-mapping.json` 對照 result.json 的原始截圖名稱與本輪證據路徑。

最終完整建置命令：

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 \
GRADLE_USER_HOME=.work/gradle-home npm_config_cache=.work/npm-cache \
PLAYWRIGHT_BROWSERS_PATH=.work/ms-playwright \
flock .work/bench.lock ./gradlew --no-daemon --configure-on-demand --max-workers=1 build
git diff --check
```

完整 build 為 **BUILD SUCCESSFUL in 1m 52s**，84 actionable tasks（30 executed／54 up-to-date），353 個單元測試：core 122、CLI 7、i18n 4、platform-api 25、protocol 10、Hub 74、Fabric logic 69、Paper common 42，failure／error／skip 均為 0。兩版正式 Fabric jar 的 runtime 檢查與 Paper `verifyOptionalApi` 通過；正式插件未包含 Axiom API。原始日誌為 `.work/axiom/regressions/full-build-resume.log`。`git diff --check` 通過。

[可攜驗收摘要](../fabric/docs/screenshots/axiom/results-2026-10-08.json) 由原始 JSON／JUnit XML 彙整，保留八組驗收的命令、項數、程序退出碼／埠狀態、離線 verify 八項零差異與最終產物 SHA-256；完整事件與失敗日誌仍留在上述 `.work` 路徑。這次 Axiom 證據約 233 MiB，低於 4 GB 上限；最終確認沒有遺留 Java、bot、Python runner 或 Xvfb 背景程序。工作樹保留，未 commit。

### 失敗重跑紀錄

失敗不計通過，原始 result／log 保留。早期 Paper harness 使用不存在的 `status --blocks`、錯誤 CLI `--json` 或錯誤狀態摘要斷言；修正語法後從新世界重跑。為結束首輪等待，管理 bot 發出明確 FAILED 中止訊息，沒有當作成功輸出。Fabric 初次實機暴露 UUID lookup 應 hook 宣告方法的 Level（不是繼承它的 ServerLevel），以及共用訊息 key 必須註冊；兩者均修正後重跑。其他 client harness 失敗包含缺少 jar 內嵌依賴、對未安裝 WorldGit 的 bot 請求 status --show，以及 CLI 缺少 Axiom pack 資源；分別修正真客戶端 runtime、使用真客戶端與新增明確 `--mod-pack`，沒有跳過斷言。

Paper 26.2 的 `1791446310` 因舊 time query 語法中止；`1791446761` 因正向時間控制使絕對 world clock 改變，WorldGit 正確拒絕線上 metadata 還原，尚未進入鎖。後續改用 `time query minecraft:day`，並在正向控制後先提交設定再建立套用基準。最終 `1791447260` 全程重跑且進入真套用鎖；兩次失敗的伺服器與 bot 均退出碼 0，port_closed=true。這也提供 #146 的實際拒絕證據，沒有放寬世界屬性預檢。無 AxiomPaper 首輪也誤用 Fabric 的 info 子命令；改用 Paper help 後兩版完整重跑通過，原本失敗仍保留。

上一輪背景 runner 最後的完整 build 因 `fabric/mc26_2/build.gradle.kts` 誤用 `modLocalRuntime` 而在 Kotlin DSL 編譯階段失敗，原始日誌保留於 `.work/axiom/regressions/full-build.log`。依 [Fabric Loom 的版本／插件區分](https://docs.fabricmc.net/develop/loom/#plugin-ids) 及本機 1.17.21 設定核對，26.2 的選用測試依賴改用不需 remap 的 `localRuntime`，1.21.11 保留 `modLocalRuntime`；兩者皆不 include 到正式 jar。沒有刪除測試或放寬斷言。

## 變更檔案

- Paper：`paper/axiom-api/`（MIT 編譯簽章）、`AxiomHook.java`、`WorldGitPlugin.java`、`PaperLiveWorld.java`、plugin.yml；common／plugin 的 Gradle 設定及根 settings.gradle.kts。
- Fabric：`AxiomEdits.java`、四個 Axiom mixin、`OptionalIntegrationMixins.java`、worldgit.mixins.json；`TouchScope`、既有 addEntity mixin、`WorldGitMod`、`Attribution`／測試、`MessageKeys`；兩版 Gradle 的選用真客戶端 runtime。
- CLI／core／platform-api：`EntityTagRegistry`／測試、`WorldOperations`、`OfflineApplier`、`OfflineWorld`、`Wgit`／CLI 流程測試，讓明確模組資源傳到整條離線操作路徑。
- 驗收：`paper/tools/axiom.py`、`axiom-optional.py`、`axiom-bot.js`、`axiom-protocol.js`、`axiom-fixture/`；wgbot 的選用協定入口，Fabric DedicatedServerFixture 的 BE 測試指令及 Phase5ClientGameTest 的真 Axiom 編輯器操作。
- 文件／訊息：本文、docs/09 的 #142–#146、docs/15、Paper／Fabric README、根 README、i18n en_us／zh_tw；`fabric/docs/screenshots/axiom/` 保存真客戶端畫面與可攜結果索引。

## 已知限制與未驗範圍

- Folia 不載入整合；Fabric 單人使用相同 handler，但本輪真 Axiom 驗收選 dedicated 1.21.11。Fabric 26.2 完成 bytecode 核對與建置，未列為真伺服器 Axiom 驗收通過。
- Paper 歸屬是可定位的 chunk 編輯嘗試上界；bypass、tick_blocks 等缺乏來源時保持未知。Fabric 是單次同步 handler 中被標 dirty／觸及的 chunk；非同步後續寫入不在此範圍。多人同 chunk 可有多位 contributor，無逐格審計。
- 跨 tick Paper buffer 取消不回滾已完成部分；客戶端 ACK／chunk 重送不修改私有 undo、clipboard、editor cache。真 GUI 驗證共同啟動、握手、開啟編輯器與顯示，沒有自動操作每個工具及 undo／redo。
- 26.2 時鐘／註記 saved-data 及其他平台不支援的 world-meta 需離線還原；沒有新增線上 registry／saved-data 快取重載。離線 `--mod-pack` 提供根 datapack 的 entity tags，不重建任意模組 registry／codec 或不同內建子 pack。
- 方塊 direct／buffer、實體生成／操作／刪除、time／property／tick 及 Fabric fix_area 有協定實測；bulk BE NBT、biome buffer、註記 GUI 的內容路徑則為 source／bytecode＋既有 capture 分析，沒有另稱獨立封包驗收通過。沒有驗所有 renderer／Sodium／Iris 組合或其他未研究的 Axiom 版本。
