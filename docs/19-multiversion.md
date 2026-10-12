# 19 — 多版本世界與跨版本歷史

研究日期：2026-10-09；實作與驗收持續至 2026-10-12。本文件涵蓋任務 1（core／CLI／Hub／platform-api），以及任務 2（Paper／Folia）、任務 3（Fabric）的交接。平台能下載不等於 WorldGit 已驗收；任務 2 子任務①新增 1.20.1／1.20.4，子任務②新增 1.20.6／1.21.1、子任務③新增 1.21.4／1.21.8 的線上轉接層，與既有 1.21.11／26.2 共用發行 jar；Folia 1.21.1 無官方 build、不可驗收，其餘平台仍待後續子任務。

## 1. 來源與目標版本

版本號與 Java 下限取自 Mojang manifest、server bundle 內 `version.json`；registry／屬性／預設 state 由各版原版 data generator `--reports` 取得。原始證據、jar、官方 mappings 留在 `.work/versions/`，不進 Git；可重跑 `tools/multiversion/reports.py`、`catalog.py`、`fixtures.py`。manifest 的最新正式版為 26.3，不把 26.4 snapshot 當正式支援。

| Minecraft | DataVersion | 原版 Java 下限 | WorldGit 執行 Java | Paper 可下載 | Folia 可下載 | Fabric API |
|---|---:|---:|---:|---|---|---|
| 1.20.1 | 3465 | 17 | 21 | 是 | 是 | 0.92.12+1.20.1 |
| 1.20.4 | 3700 | 17 | 21 | 是 | 是 | 0.97.3+1.20.4 |
| 1.20.6 | 3839 | 21 | 21 | 是 | 是 | 0.100.8+1.20.6 |
| 1.21.1 | 3955 | 21 | 21 | 是 | **否** | 0.116.17+1.21.1 |
| 1.21.4 | 4189 | 21 | 21 | 是 | 是 | 0.119.4+1.21.4 |
| 1.21.8 | 4440 | 21 | 21 | 是 | 是 | 0.136.1+1.21.8 |
| 1.21.11 | 4671 | 21 | 21 | 是 | 是 | 0.141.6+1.21.11 |
| 26.1.2 | 4790 | **25** | 25 | 是 | 是 | 0.155.3+26.1.2 |
| 26.2 | 4903 | 25 | 25 | 是 | 是 | 0.161.0+26.2 |
| 26.3 | 5023 | 25 | 25 | 是 | **否** | 0.162.0+26.3 |

Paper／Folia 可用性查 [官方下載 API](https://fill.papermc.io/v3/projects)；Fabric API 取 [官方 Maven metadata](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml)，Loader 0.19.5 的 stable metadata 涵蓋十版。這是日期固定的盤點，後續新 build 仍要驗收。

2026-10-11 再查官方 API，Folia 1.21.1／26.3 仍回 `version_not_found`，Fabric 26.3 的 stable Loader 仍為 0.19.5；回應證據在 `.work/mv1-finish/platform-current-check.json`。

同日重新查 [Mojang manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)，最新正式版仍為 26.3；26.4-snapshot-3 不納入正式支援。26.3 的 metadata SHA-1 與研究時的快取一致，回應證據在 `.work/mv1-finish/mojang-current-check.json`。

[26.1 官方說明](https://feedback.minecraft.net/hc/en-us/articles/44551668333837-Minecraft-Java-Edition-26-1)明定 Java 25；[Paper Java 建議](https://docs.papermc.io/paper/getting-started/)亦以 26.1 為界。1.20.1／1.20.4 的原版 fixture 已用 Java 21 啟動；Paper build 196／499 亦已在 Java 21 成功載入 Java 21 bytecode 的測試插件。Java 21 CLI 呼叫 Java 25 DFU 並完成兩版升降級也通過，證據見 `.work/mv1-recovery/multiversion/platform-java21.json`。共用 core 維持 Java 21 bytecode，26.x adapter 編 Java 25，舊版 WorldGit 伺服器也要求 Java 21；任務 2 子任務①已完成 WorldGit 1.20.1／1.20.4 adapter 與 Paper／Folia 真服驗收，結果見 §8。沒有把「原版可用 Java 17」誤寫成「WorldGit 可用 Java 17」。

保留使用者十版清單；不改選 1.21.10。1.20.2／1.20.3／1.20.5／1.21.2／1.21.5／1.21.9 等是**格式邊界**，需要轉換規則，但不是額外正式驗收平台。不在 catalog 的 DataVersion 不以鄰近值猜測。後續同 adapter 啟動成功的版本只能標「相容、未正式驗收」。

## 2. 存檔邊界與正規化

| 相鄰支援版 | 中間格式變更 | 正規化／反向處理 |
|---|---|---|
| 1.20.1 → 1.20.4 | 1.20.2 效果欄位格式、1.20.3 `grass` → `short_grass`；新指令與文字能力 | block ID 反向改名；effects 與文字需依 codec 轉換；不能因名稱看似一樣直接 retag |
| 1.20.4 → 1.20.6 | 1.20.5 `Count/tag` → `count/components`；`scute` → `turtle_scute`；狼／犰狳等內容與屬性 | item codec 反向還原 NBT；custom_data 視為不透明自訂資料；registry 報告的實驗 crafter／breeze 排除於一般 1.20.6 世界 |
| 1.20.6 → 1.21.1 | 試煉方塊／BE／實體、附魔轉為動態 registry、資料包目錄單數化 | registry 差集辨識 crafter、vault、breeze、mace 等；新附魔在舊版略過且列損失 |
| 1.21.1 → 1.21.4 | 1.21.2 各木材 boat／chest_boat 分拆 entity ID、attribute 去前綴；1.21.4 custom_model_data 複合結構、熔爐欄位改名及 lit_total_time、蒼白花園 | boat ID 合回加 Type；屬性回復；熔爐舊名只對 ≤1.21.1 還原；模型資料僅可表示的單一數值保留，其他列損失 |
| 1.21.4 → 1.21.8 | 1.21.5 text JSON → NBT；enchantments／dyed_color 簡化；equipment／drop_chances 統一；實體與 BE 欄位調整；1.21.6 locator／saddle 內容 | 名稱／lore／招牌轉回 JSON；裝備還原 HandItems／ArmorItems；potion entity 合回；新版生物／欄位依 target registry 處理 |
| 1.21.8 → 1.21.11 | 1.21.9 銅箱／銅傀儡／架子；1.21.11 gamerule registry、spawn 複合結構等 | gamerule ID 回舊名，反向布林需翻轉；無等價數值規則列損失；spawn 回 SpawnX/Y/Z/Angle |
| 1.21.11 → 26.1.2 | 26.1 維度／saved-data／玩家資料搬位；level 拆分、difficulty_settings；Java 25；原版不混淆 | layout 邊界為 **4790**；先把固定 saved-data 別名折回 LEVEL 供 DFU，再依目標拆分；降級還原位置及欄位 |
| 26.1.2 → 26.2 | sulfur 方塊／sulfur_cube、name_tag 等物品能力變化 | registry 差集與元件 codec；新內容用 YAML 替代／略過並記錄 |
| 26.2 → 26.3 | block state `Name/Properties` → `id/properties`、預設狀態可用字串；陶罐裝飾改完整 stack；新 biome／poplar／cushion／元件；招牌 `allow_op_features` 與 front/back/waxed 元件；熔爐／釀造台數值寬度與速度欄位；level `version_history` | 讀取 compact state 時補 registry defaults，repo state 仍完整；寫目標格式；陶罐裝飾降為四個 ID、裝飾元件列損失；version_history 為 runtime 欄位 |

主要格式依據：[1.20.5](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-20-5)、[1.21.5](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-5)、[1.21.11](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-11)、[26.2](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-2)、[26.3](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3) 官方發行說明，加上本機 jar 的 registry／DFU 實測。反向規則的支援程度應以 §8 驗收記錄為準；上表也列出必須補齊的 codec，不能由「已有 registry 名單」推論所有欄位已保留。

所有目標仍使用 Anvil region、section `block_states`／`biomes`、獨立 `entities/`；NBT 的異質 list 在磁碟仍以 compound wrapper 表示標準 NBT，不另外發明新的 binary tag。`poi/` 是衍生索引：WorldGit 不把 POI、光照、heightmap、LastUpdate、InhabitedTime 納入 hash；離線重組不保留舊 POI，遊戲重算。原生維度高度在本範圍保持主世界 -64..319、地獄／終界 0..255；以 section Y 保留實際高度，自訂維度需來源 registry，不能裁成固定 0..255。

26.1 起主世界地形亦在 `dimensions/minecraft/overworld/`，根目錄仍有 level.dat 與 `.worldgit/`。shared saved-data 移至 `data/<namespace>/`，dimension saved-data 在各維度目錄；maps 改 `data/minecraft/maps/<number>.dat`、`last_id.dat`。固定 game_rules/world_border/world_gen_settings 保留既有 #103/#111 的 repo 別名，實體 SavedData 路徑使用受限 base64 相對路徑 envelope。整世界遷移另處理 playerdata／advancements／stats ↔ players/{data,advancements,stats}：NBT 使用 PLAYER DFU／反向 item/equipment/respawn codec，JSON 使用 STATS／ADVANCEMENTS DFU；降級按 registry 排除舊版無法表示的統計／進度並記損失。玩家檔案仍不加入世界內容歷史，不能只搬地形而殘留會使舊版拒絕啟動的 players 目錄。

## 3. repo 版本與比較語意

commit 原本已有 WorldGit-DataVersion；新增 `WorldGit-Minecraft`、`WorldGit-VersionChange` trailer，後者是有大小上限的 base64 NBT，含 from/to、規則 fingerprint、損失種類／來源／替代／數量／座標樣本。commit ID 與原始 tree 不改寫；不更換 WG section envelope，因此舊 repo 保持可讀。舊 trailer 沒 Minecraft 時按 DV 推導；缺 DV 的主世界從 level.nbt 推導，無可靠來源時保留未知而拒絕跨版本轉換，不猜 4671。

同一 repo 允許任意已支援 DV 的 commit。跨版本 readonly diff／Hub compare／merge preview 轉到輸入中**最大 DV**，三方另含 base；這條路只升級，不隱藏降級損失。套用世界時依**目前世界 DV**轉換目標，較舊目標用原版 DFU，較新目標用反向引擎。規則與 DataPacks 不同的現有預檢仍保留。主世界缺完整 metadata、trailer DV 與 level.nbt 不一致、來源 registry 不明時明確拒絕；#28 的版本數字 blanket rejection 被替代，完整性預檢沒有取消。

不同版本的 physical section hash 可以不同。status 將 HEAD 投影到世界版本（含唯讀的降級損失提示），比較語意內容，版本本身另顯示「世界已由 X 升級到 Y；請 commit 記錄版本變更」。即使語意 diff 為零仍可建立版本 commit。比較投影快取以 tree ID、dimension、from/to、規則 revision 與 YAML 設定 hash 為鍵；在 `.work/versions/conversions/`，最多 2048 筆／64 MiB；快取命中仍重新檢查 loss confirmation 及 object 是否存在。快取不改 refs；Hub 預覽使用 MemoryObjects，不往 bare repo 寫暫時 objects。

`log --graph`、Hub commit 列表／圖／詳細頁標版本與升降級。版本 commit 是 HEAD 的新子 commit，保留原歷史；`upgrade` 不把全部祖先改寫。一般 merge 使用共同版本投影，避免整個世界因 schema 改變被列成幾千個建造衝突。

## 4. 原版升級

CLI 使用 **獨立 JVM + 平台 parent classloader 的 URLClassLoader** 載入使用者本機 target server bundle 的版本 jar 與 libraries，反射呼叫 DataFixers／References／NbtOps。舊版使用官方 mappings 找名稱；26.x 直接使用原名。共同 Java 21 程序可以呼叫 Java 25 worker；透過 `-Dworldgit.java25=/path/bin/java` 指定。NBT 以有長度上限的二進位 stdin/stdout 傳遞，stderr 進 `.work/versions/<version>/dfu.log`；取消／超時／close 都停止子程序。

Hub 的 Spring Boot 發行 jar 把 core 放在巢狀 jar，外層 `java.class.path` 不能直接啟動 core 類別。DfuWorker 僅依賴 JDK：由目前 classloader 讀取自有 bytecode，核對大小及內容 hash 後放入 `.work/versions/workers/<hash>/`，子 JVM 只使用這個 classpath，再隔離載入 Mojang runtime。CLI 與 Hub 使用同一入口，不引入 Spring Boot 依賴；快取損毀即重建（#194）。

不散布 Mojang jar，沿用 #14 的本機下載原則：從 Mojang manifest 找精確版、SHA-1 驗證 server 與 mappings，限制 HTTP／解壓大小，快取於 `.work/versions/`。本機可預先備好已驗證檔案供離線使用。worker 無需啟動 tick loop、不需替使用者接受伺服器 EULA。原版 `--forceUpgrade` 只用於獨立驗收基準，避免增量操作每次啟動整個世界。

tree converter 把正規化 chunk 重建為來源版 vanilla NBT，對 CHUNK、ENTITY_CHUNK、LEVEL 與已知 saved-data type 執行 DFU，再正規化成目標 tree。runtime／光照欄位仍排除，因此同輸入／規則／目標產物 hash 可重現。增量套用只寫 ApplyPlan 變動 chunk；目前首次版本 tree 投影需轉換其所有 tracked chunk，重複投影可用快取，不能宣稱首次是 O(受影響 chunk)。

```sh
wgit -w world init --with-dimensions all
wgit -w world upgrade --to 26.3 --dry-run
wgit -w world upgrade --to 26.3
wgit -w world show HEAD
```

整世界指令要求停服、所有既有維度已 init、乾淨世界且無 PARTIAL／MERGING。先轉換到 staging、預覽完整物理世界的損失（含 ignore／未追蹤實體），再組裝、搬資料、建立版本 commit；被忽略內容不加入歷史。來源與目標維度目錄不同時 repo 隨維度搬移，主世界 repo 仍在根。`version-state.yml` 與候選 refs 保存遷移操作資訊；每次搬移前先持久化，PREPARED 阻止新操作。停服後 `wgit migrate --recover-version` 復原所有移動與 HEAD，即使 level.dat 在 backup 仍可執行；越界／符號連結／兩份資料同時存在時拒絕覆寫。

26.1+ 原版 FileFixer 的 copy-on-write 檔案掃描會拒絕 Git 預設的唯讀 object（實測 `CowFSCreationException: missing write access`）。建議先用 CLI `upgrade` 完成物理遷移再開服。若交由 Minecraft 自動升級，停服時先把主世界與各維度的 `.worldgit` 移到世界外，待原版遷移完成再依新 layout 放回；主世界仍在根，地獄／終界 repo 隨各維度移入 `dimensions/minecraft/`。保留原版備份及 repo 備份，不能直接把所有 Git objects 改成可寫（#186）。驗收的外部升級案例遵循這個操作條件，原始拒絕證據留在 `multiversion/external-filefixer-readonly-rejection.log`。

Minecraft 外部升級可能只升 level.dat／已載入 chunk；OfflineSnapshotSource 檢查每個 chunk 的原 DV，對仍舊的 terrain／entity chunk 做 DFU 投影。若 chunk 比 level.dat 更新，拒絕混合損壞世界，不直接改數字。status 不會因這種延遲升級把所有 schema 差異當建造變更。

離線 chunk 版本檢查在每次 scan／capture 只讀一次目標 DV，原 DV 缺省值也使用同一數字；close／重新 scan 即失效。避免為每筆 terrain／entity NBT 重複讀取、解壓 level.dat，並防止目標值跨操作沿用。20,000 chunk 的初次 init 量測與熱路徑檢查發現此成本，修正及掃描間版本更新回歸見 #195。

線上任務 2／3 由 adapter 提供伺服器內的 NbtUpgrader，不下載或啟動離線 worker。WorldOperations.LiveAccess.dataFixer 與 platform-api VersionedSnapshots 定義共同入口：背景轉換、owner 顯示損失與確認、預檢後套用。metadata 與 ChunkSnapshot 必須一起轉換；禁止先把新資料交給舊 codec 才問玩家。

## 5. 反向規則、損失與設定

`versions/reverse.yml` 描述九個支援邊界、ID／一般欄位改名、components → NBT、預設替代。`<dv>.yml` 描述目標可用 ID、block properties/defaults、biome 等。registry 差集半自動產生新增內容清單；特別 codec（文字、裝備、boat、components、level）由可測試的小程式處理。原版 registry report 可能包含 feature flag 尚未啟用的內容，generator 明確濾掉一般 1.20.4／1.20.6 的實驗項目。動態 registry 另外從 jar data 讀取，不把空 report 誤當全部不支援。

預設新增 block → stone、item → barrier、biome → plains、entity／BE → skip。未知 custom ID 亦列損失，原始 custom_data 不進遞迴 ID 猜測。目標不存在的替代 ID 拒絕；有未知 block property 時列出失去的屬性及目標預設值。設定在 repo 的 `worldgit.yml`：

```yaml
conversion:
  fallbacks:
    block: minecraft:stone
    item: minecraft:barrier
    biome: minecraft:plains
    entity: skip
    block-entity: skip
  replacements:
    block:minecraft:copper_chest: minecraft:chest
    item:minecraft:mace: minecraft:iron_axe
```

替代方塊與 BE 要成對考量；把新箱子換成一般箱子但略過原 BE 會失去 inventory，不能以「方塊成功替代」宣稱保存箱子內容。只可表示的代表內容驗收包括普通箱子、sharpness、custom name 與自訂資料；其他 codec 需依 §8 增補證據。

```sh
wgit -w world downgrade --to 1.20.4 --dry-run
wgit -w world downgrade --to 1.20.4 --accept-loss
wgit -w world export release.zip --to 1.20.4 --accept-loss
```

dry-run 列完整聚合損失，不需接受；未提供 `--accept-loss` 的有損寫入拒絕，第一筆世界寫入前完成確認。一般跨版本 switch／restore／merge 同樣走確認；apply journal 記錄各來源 commit 的轉換報告，版本 commit 另有 trailer，show／Hub 詳細頁可查。普通 apply 的每個來源報告另存永久 `version-audit/<UUID>.yml`，以 `show --operation <UUID>` 查詢，不被下一次操作 journal 覆蓋。平台互動確認由 VersionedSnapshots.confirmLoss 接口銜接任務 2／3，token 應綁操作 ID、來源 snapshot、目標 DV、規則 fingerprint、損失 hash，世界或規則改變後失效。

26.3 招牌的 `allow_op_features=true` 可還原為舊版的隱含行為；false 在舊版無法表示禁用操作員文字／點擊事件的能力，列入損失並要求確認。sign_text_front/back、waxed 轉成舊版 BlockEntityTag 或 block_entity_data，嵌套 BE 使用同一 codec。熔爐／釀造台整數反向回 short／byte，溢位才限縮並記錄；新增速度、釀造總量及暫時無敵欄位不等價時亦列損失。規則見 reverse.yml，依 [26.3 官方說明](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)（#180）。

## 6. Hub 與發行

Hub Dto 提供 mcVersion/versionChange，詳細頁可查看損失清單；跨版本 compare／merge preview 先轉共同最大 DV，失敗回傳 data-version 問題與具體原因。3D 的 binary diff／實體標記與摘要使用相同共同版；chunk／tile／LOD 的 `target=<Minecraft>` 只允許升級，資源也選共同版。前／後純世界檢視保留各自版本（#182）。Mojang resources 對應**精確** DV，不再用 floor 版本猜測。release 頁與 ZIP HTTP header 顯示來源版本，ZIP 包含 worldgit-version.yml；CLI export 可以選 `--to`。Hub 此階段不提供有損目標版發布，仍可下載顯示版本的 release；完整降級預覽／確認 token 屬後續 UI 擴充。 唯讀投影仍受既有 DecodeBudget 與 MemoryObjects 的 8 MiB 候選物件上限約束，超限會明確拒絕；首次跨版投影目前處理整個 tracked tree，大型世界的分區投影／共享快取需後續擴充。

目前 Hub renderer 是 TileRenderer + deepslate，不內嵌 BlueMap。BlueMap 可作離線輸出的外部工具：[官方 5.27 release](https://github.com/BlueMap-Minecraft/BlueMap/releases/tag/v5.27) 的 CLI 支援 Minecraft 1.13.2–26.3、需 Java 25；Paper 版與 Fabric 版是另一個窄平台範圍，不能套用 CLI 範圍。舊世界可以直接渲染，不需要為了看地圖強制升級 repo；resource pack 必須對應世界版本。26.3 compact state 的自訂預設值需 [defaultBlockstates 資料](https://bluemap.bluecolored.de/wiki/customization/Mods.html)。

## 7. 任務 2／3 的平台方案

### Paper／Folia

沿用每版薄 adapter、共用 common／NmsBridge、shared source 的 Gradle 多模組結構。先新增 `paper/v1_20_1`、`v1_20_4`、`v1_20_6`、`v1_21_1`、`v1_21_4`、`v1_21_8`、`v26_1_2`、`v26_3`；每個目標各用精確 dev bundle 編譯。**不因 package 同名就共用一個 binary adapter**。1.20.5 前 Paper 執行名稱仍涉及 reobf，舊 adapter 用相應 paperweight production reobf；1.20.6 起 Mojang production。選擇 adapter 需用 version catalog，不用 startsWith 猜整個系列。

可先共用三個 source 家族：legacy-items（1.20.1/4）、components（1.20.6–1.21.4）、modern（1.21.8–26.3）；這是待 compile／真服驗證的**候選分組**。shared 僅保留確認一致的原始碼，其餘以 bridge 隔離。子任務①已將 Paper common 降到最老支援 API，把較新呼叫與後續移除的 descriptor 放 capability／bridge；後續版本仍須逐版編譯與真服驗收。

| 差異點 | 後續要求 |
|---|---|
| tickRateManager | 最舊 1.20.1 無此 API，1.20.6 已有；局部 chunk guard 仍不依賴全服 tick freeze，保留 #147 後策略 |
| item components／BE codec | 1.20.5 起使用 registry-aware BE 載入／存檔，保留完整 item components 與不透明 custom_data；跨 1.20.4／1.20.6 邊界驗證名稱、lore、附魔及真實損失，無元件物品省略空 patch（#212、#214） |
| scheduled ticks | 載入後未 ticking 的 pendingTicks 必須在 lock／replace 前 unpackTicks，使用 owner clock 並保留剩餘延遲、priority；逐版以真 chunk NBT 與原版 SavedTick 容器驗證（#170、#213） |
| chunk／ticket／IO | 分別驗證 getChunkNow、ticket 型別／位置、save flush barrier；Folia owner 必須在 region thread，不能在 background 讀活 chunk |
| EntityInLevelCallback | 目前 ChunkTickGuard 包裝 move/remove/changeSection 等回呼；各 jar 的介面與 Entity.remove reason 必須逐版編譯，1.20 舊可見性與 field 名不能反射沿用 |
| entity manager UUID | 保留 #158 的 known UUID + 未載入磁碟普查；較舊 ServerLevel manager 成員位置需 bridge，不能只 getEntity |
| Folia | RegionScheduler／GlobalRegionScheduler／EntityScheduler 與 ticket 實作逐版驗證；沒有 build 的 1.21.1／26.3 明確標不可驗收 |
| command／Brigadier | 基礎 command registry 可共用，Paper lifecycle command API 出現前走舊註冊路徑；不要在舊 server 載入不存在的 class |
| payload | 1.20.5 是 custom payload codec 邊界；Paper plugin message 共用 WorldGit protocol bytes，但 Fabric 接收 adapter 分開 |
| DFU | 用目標 server DataFixers + registry-aware NBT codec，dataVersion 從 SharedConstants/version 取得，不把 plugin 支援版本硬寫成世界版本 |
| 舊版實體載入 | DataVersion ≤4189 的 adapter 在把正規化實體交給原版載入器前呼叫 `VanillaEntityDefaults.materialize`；透過 `materialize(entity, DV, previousByUuid)` 傳入 owner 的 UUID／原始 NBT 表，對同 UUID／類型與乘客保留目前 Health。共用歷史轉換不把這些載入預設加入 hash |

建議任務 2 拆五次：① selector／DFU contract、1.20.1/4 reobf 與 Java21；② 1.20.6/1.21.1 components；③ 1.21.4/8 callback／設備／ticket；④ 26.1.2/26.3 saved-data／新 state codec／Java25；⑤ 全矩陣 Paper/Folia 線上 apply、owner 對抗、GUI loss token、效能。估計 12–20 工程日，依八種可下載 Folia 版本執行。

子任務①實作（2026-10-11，#196–#207）：common 使用 Paper API 1.20.1；`CommandSource`／`WireArgument` 是共用中性指令介面，1.20.1／1.20.4 使用 Bukkit registry，現代 Lifecycle 註冊放入 `paper/commands-modern`，由較新 adapter 編譯。selector 只接受 catalog 精確 ID 並核對 DV；版本相似但不在支援表內仍拒絕。舊 dev bundle 分別固定為 `1.20.1-R0.1-20230921.165944-178`、`1.20.4-R0.1-20241030.192207-176`，各自使用 paperweight reobf。正式插件 Java 21，26.2 adapter Java 25；`paper-plugin.yml` 走官方 Paper plugin loader，舊 Folia 不再經不支援 Java 21 bytecode 的 Bukkit ASM 轉換器。

現代 Lifecycle 註冊把原生命令樹映射到中性 dispatcher；Paper 命名空間別名及 execute 轉向按已解析根節點的範圍正規化輸入，再把補全範圍與錯誤游標投影回原字串，維持原 source／executor／location（#206）。

最老 API 編譯仍須處理後續移除的 descriptor（#205）：1.20.1 的 `RegionAccessor.spawn` callback 是 Bukkit Consumer，1.20.4 起為 Java Consumer。common 只傳中性 Java callback，由各版 `spawnConfigured` bridge 使用該版 API；BlockDisplay 與 TextDisplay 都在發布前設定不可見、不持久化及預覽標籤，保留玩家可見性與 owner 排程。common 的位元組碼檢查拒絕舊 Consumer；舊兩版 fixture 各用對應 API、Java 17 bytecode。

InventoryView 在舊版為 class、較新版為 interface（#207）；GUI 與編輯守衛用穩定的 InventoryEvent.getInventory 取得頂層 inventory，開選單與驗收點擊經版本 bridge。common 位元組碼禁止 InventoryView 引用；API 成員與 class／interface 的靜態核對另解析 JDK、官方 server libraries 清單所列 Adventure 版本及 SHA-256，避免把外部繼承方法誤判為移除。

bridge 提供目標伺服器 `DataFixers`／`NbtOps` 和實際 DV，擷取與寫入投影均使用同一份來源；尚未重存的 level.dat 不改變目標版。局部 tick guard 對舊兩版各自處理混淆欄位及 Folia owner，1.20.1／1.20.4 的 region chunk 集合分別使用 `getChunks`／`getTickingChunks`。server entity lookup 普查與跨維度磁碟 UUID 檢查保留；新增實體與乘客補原版預設，同 UUID 的 Health 仍取目前 owner 複本。

線上 switch／merge／reset 投影到伺服器 DV；升級自動使用 DFU。有損降級在任何世界寫入前顯示完整聚合清單，各來源、替代值、數量與座標樣本分別列出；玩家或 console 執行 `/wg confirm <token>` 才重試。五分鐘、單次 token 綁定執行者、原操作、來源 commit、target DV、規則／完整報告與世界／HEAD 狀態；確認重試及鎖後套用重新核對，權限撤回或內容改變即拒絕舊授權。唯讀 verify 與 dry-run 不授權寫入。線上 world-meta 還原的既有限制保持，設定／資料包不同仍須離線處理。

子任務②（2026-10-11，#208–#215）新增 `v1_20_6`／`v1_21_1`，精確 dev bundle 為 `1.20.6-R0.1-20241030.191541-126`／`1.21.1-R0.1-20250328.161643-128`，皆為 Mojang production 名稱、Java 21。Paper runtime 固定 build 151／133，Folia 1.20.6 固定 build 6；官方下載 manifest 與實際 jar checksum 留在 `.work/mv2-2/`。Folia 1.21.1 回 `version_not_found`，selector 在載入 adapter 前用 MiniMessage 語系訊息說明沒有官方 build、不可驗收，並停用插件。

components（1.20.6–1.21.4）仍是候選 source 家族。已確認的共用來源僅有 `components-shared` 的完整 NBT／registry-aware BE codec 與舊 Lifecycle 指令來源；兩個精確 bundle 都編譯，發行 jar 前比較共享 bytecode，一處不同即拒絕合併。1.20.6 仍為舊 Paper chunk system；1.21.1 已為 Moonrise，其 tick 集合保存 ChunkAndHolder 身分，guard 要保留原 wrapper。兩版各自使用已確認的 entity callback／UUID lookup、ticket、光照完成回呼、terrain／entity／POI 存檔與 flush。鎖與替換 scheduled ticks 前都先 unpackTicks（#170），保留剩餘延遲、priority 與 owner clock；不借用其他版的欄位或 freeze。1.21.4 已於子任務③逐 bundle 核對並加入這組共用來源。

1.20.6／1.21.1 的 Lifecycle 短名預設使用 redirect；child context 可能省略根 literal，按已解析節點範圍重建原操作尾段，再回投補全與錯誤游標（#211）。Brigadier 使用單參數 parse，source 的位置／執行者由該版 native handle 轉接；較新兩版保留原來源。

子任務③（2026-10-12，#216–#232）新增 `v1_21_4`／`v1_21_8`，精確 dev bundle 為 `1.21.4-R0.1-20250925.065901-228`／`1.21.8-R0.1-20250906.215025-55`，Mojang production 名稱、Java 21。Paper runtime build 232／60、Folia 各 build 6；manifest／checksum 在 `.work/mv2-3/artifact-proof.json`。Folia 兩版有官方 WorldEdit 7.4.2（Java 21），Paper 使用 FAWE 2.15.0，各官方發行的 Minecraft／平台範圍及 SHA-512 一併核對。

1.21.4 的 NBT／registry-aware BE 與 Lifecycle 指令來源加入 `components-shared`；發行前比對 1.20.6／1.21.1／1.21.4 三組完整 class，任何差異都拒絕合併。1.21.8 的 NBT text／equipment 屬 modern 家族，僅共享已確認的 `commands-modern`；與 1.21.11 的完整 Java 21 class 同樣必須相同。26.2 編譯器為 Java 25，lambda 名稱與 class body 不同，逐版編譯並另驗真服，沒有以略過 header 的比對宣稱 bytecode 相同（#217）。

1.21.4 使用 Moonrise 的 ChunkAndHolder，保留原 wrapper；1.21.8 的 tick 清單直接是 LevelChunk。1.21.8 的 ticket constructor 為 `(long, boolean, TicketUse.LOADING)`，與後續 FLAG_LOADING 版本不同；metadata 使用該版 `scheduleSave()` 的完成 future，region 存檔方法三參數，terrain／entity／POI flush、callback、UUID lookup 保持自己 adapter。鎖與替換都先展開 pending ticks，owner clock、剩餘延遲、priority 的原版容器斷言保留。Folia 1.21.4 的 MinecraftServer 在所有世界存檔與 MoonriseRegionFileIO.flush 完成後報 `All I/O tasks to complete`；1.21.8 仍報 `All RegionFile I/O tasks to complete`，由精確 adapter 接入關閉 commit 完成訊號（#222）。

反向 equipment 邊界補齊 Mob／ArmorStand 的固定陣列與 Mob 預設 drop chances，保留 body item／chance；pig／strider 回 Saddle 布林，horse 回 SaddleItem。無法表示的 saddle 元件、非預設 chance 與未知槽逐項列損失。非 LivingEntity 不加設備陣列，custom_data 不透明（#218）。attribute_modifiers wrapper 的 show_in_tooltip 保留，type 按目標 registry 轉換；written book 的 raw／filtered page 與名稱／lore／招牌回 JSON。非零 lit_total_time 降到 ≤3955 列重算損失，其餘熔爐欄位保留 short／溢位驗證；TNT minecart fuse 舊名邊界修為 3955。reverse revision 18、首輪 v15-intermediate-native-form 使舊轉換／統計快取失效（#219）；後續 #225／#226 再更新 boat 與 saved-data 的原生等價格式。

原版 Disabled 實驗包的投影補入 `redstone_experiments`／`minecart_improvements`，名單取目標 server available packs；Enabled 順序與啟用實驗不改，自訂 Disabled 政策與 Bukkit 資產保持原預檢。`paper-metadata-v2` 拒用舊投影快取，新增雙向與自訂政策回歸，真服首跑 metadata 拒絕證據保留（#223）。

真服 DFU 的拆分 boat ID 仍帶舊 Type，原生重存則省略；DV≥4189 的已知原版木材 ID／一致 Type 及乘客僅省略冗餘欄位，舊 boat、模組 namespace、不一致 Type、custom_data 保留。以 DFU 殘留形狀與 native 重存補回歸；正規化 v16-native-boat-form 拒用舊投影，版本字串在執行期讀取，避免中止後 incremental build 的舊 inline 常數（#225）。

saved-data 依 1.21.8 原生 codec 處理永久 forced ticket 的缺省 ticks_left、集合順序與空 tickets／raids。只對已知原版路徑及永久 forced 類型省略期限；座標、level、其他 ticket 有限期限、非空襲擊、next_id 與自訂資料完整保留。降回舊 Forced 時非預設 level 列損失；SavedData v5 與階段版本 `multiversion-canonical-v17-native-saved-data-form` 拒用舊投影／索引快取（#226）。

DFU 的舊 HandItems／ArmorItems 與 saddle 只在各槽完全等於 modern equipment 時省略；不一致資料、自訂 namespace 與 custom_data 保留。原生 BE 缺省空 components 與 pig 的 temperate variant 補在缺省欄位，已有內容不改，維持既有同版原生快照表示。正規化更新為 v18-native-equipment-form，原始 PARTIAL 及逐 tree／物理 NBT 比較留在 `.work/mv2-3/failures/dfu-equipment-native-form/`（#227）。

modern saddle=2.0 與舊版保證掉落等價；缺省 chance=0.085 或其他低於 1 的機率降到保證掉落時必須列損失，沒有設備的機率欄位省略也列損失。reverse revision 更新為 19，補缺省機率回歸並維持原生 2.0 的無損斷言（#228）。

1.21.8↔1.21.11 另確認 Bukkit 原生 marker 的 schema 邊界：舊 pack_format 與新同值 min_format／max_format major／minor 陣列雙向投影，只處理精確原生描述／schema 與無其他資產的空標記包；自訂範圍、欄位、描述及內容完整保留。1.21.11 bridge 使用實際格式94.1、enabled features、available packs 與活 DataPacks；現代 game_rules 補缺省值而不覆蓋已有值。fingerprint 更新 v3 含 minor；stale level.dat 先由目標 DFU 投影 LEVEL 再覆蓋活設定，避免舊 GameRules 混入新 game_rules，同版跳過DFU、較新磁碟DV拒絕，不觸發資料包重載或完整世界存檔（#229）。

world_border 自1.21.11已是compound，降到DV≤4440才改回舊欄位；舊 WorldBorder.Settings.write 的警告距離／時間使用double，反向轉換恢復相同數值的DoubleTag，避免純型別差異被嚴格預檢拒絕。1.21.11保留現代compound／整數，26.x storage layout 的拆分是另一個邊界。兩組型別／schema回歸保留完整設定與未知資料；最終正規化更新v19-native-border-form、reverse revision20，首跑拒絕與官方bytecode證據在 `.work/mv2-3/failures/native-border-warning-width/`（#230）。

1.21.4 的 Lifecycle 別名可提供 cloned worldgit 根literal，1.20.6／1.21.1則可能只留下redirect child。components 共用輸入投影同時辨識 canonical root／已知worldgit別名與redirect child，保留 source、cursor及補全offset；短worldgit、namespaced與execute補全都由真封包驗收，三bundle完整共享class比較仍保留（#231）。

Folia1.21.4 的 headless bot 驗收需按原生respawn保留旗標與實際維度改變保留既有正血量；Folia不重送相同health，Mineflayer的無條件dead狀態會使look／互動永遠等待。listener完成後才更新client，位置／chunk就緒才發player_loaded；死亡不套用、沒有改伺服器實體NBT。原生命名牌與before-init觸及繼承、追蹤斷言仍完整驗證，旗標／維度／health／ready證據保留（#232）。

### Fabric

沿用 `mc<version>` + shared source + logic 模組，新增八個目標模組；保留單人／dedicated 同一正確邏輯。1.20.1/4 的 Loom remap／Java21，1.20.6–1.21.11 Mojang mappings／remap，26.x unobfuscated Loom 路徑與 Java25 分開。暫不導入 Stonecutter，以免平台移植與 build system 大改同時發生；來源家族透過共享目錄或少量 adapter 提供。每一版的 mixin target／descriptor 要用 jar 驗證，require=1 保留 fail-fast，不能用 require=0 掩蓋注入失敗。

舊 networking 使用 Identifier／PacketByteBuf 註冊；1.20.5 起 CustomPayload/PacketCodec/PayloadTypeRegistry。拆開 Platform.send／register，protocol bytes 仍共用。1.21.5+ 的 entity 裝備與資料值、更後版本 ValueInput/ValueOutput 存取與 callback／entity manager mixin 需各版 bridge。保留 setPosRaw 邊界、跨維度 UUID 普查、terrain/entity/POI barrier、單人 integrated server 的 owner 運行方式，不能只測 dedicated。

依 [Fabric 26.3 官方開發說明](https://www.fabricmc.net/2026/09/15/263.html)，新版本 Loom／Loader 與 API 移除要逐項檢查；有 Fabric API 版本不代表 Adventure 平台 jar 可直接共用。舊版可能需要獨立 Adventure adapter／只用原版 Text 轉接 MiniMessage，禁止把目前 1.21.11 的 Adventure jar 塞進所有版本。

建議任務 3 拆五次：① legacy payload 1.20.1/4；② components 1.20.6/1.21.1；③ 1.21.4/8 mixin/callback；④ 26.1.2/26.3 ValueIO／state／DFU；⑤ 十版 dedicated + 單人、HUD／鬼影／loss confirmation、對抗與效能。估計 14–24 工程日，客戶端與 dedicated 一起移植，每次交付同時保留既有兩版回歸。

## 8. 驗收與限制

線上舊兩版 metadata 投影（#202）保留舊 layout 的 chunks／raids 等 saved-data；舊名 GameRules 也按目標 registry 列出不可表示的規則損失。伺服器 upgrader 在 DFU／反向 codec 後補目標原版缺省值，純原版 Disabled 實驗包名單依目標可用包重建，Enabled 完整保留。只有 `datapacks/bukkit` 沒有其他資產且 pack.mcmeta 恰為原版 Bukkit 自產 marker schema 時，才投影 pack_format；自訂 Disabled／marker／任何其他 Bukkit 包內容保持原值，差異仍由既有線上 metadata 預檢拒絕。這不授權在線重載資料包或改寫世界設定。

升版後未 dirty 的 saved-data 可繼續留著來源 DV（真服 chunks.dat 已確認）；擷取按檔案自身 DV 升級到目標，再套相同正規化，不改寫磁碟、不強制所有設定 dirty，也不略過強制載入清單。線上用目標伺服器 DFU、CLI 用目標 worker；已知 saved-data 比目標新時拒絕擷取（#203）。

快取加入 `NbtUpgrader.projectionFingerprint`，正規化更新為 `multiversion-canonical-v13-legacy-metadata`，避免離線原版 DFU 與平台載入預設互相沿用投影。唯讀投影與有損確認使用同一份 backend；同版仍直接回傳原 tree，不進入 DFU 或 metadata 投影。舊版同步 metadata 保存是 bridge capability；既有 1.21.11 不排程空保存，26.2 保留原 IO future。

子任務②真服 1.21.1→1.20.6 降級另修正原生 NBT 邊界（#214）：attributes 在 1.21.1 已為小寫，反向轉成 `Attributes`／`Name`／`Base` 的最後舊版為 DV3839；DV3955 保留小寫，同時套用目標 generic 屬性 ID。無元件 item stack 省略空 `components` patch，避免原版重存後的假差異；非空元件與 custom_data 保留。reverse revision 與正規化更新為 `multiversion-canonical-v14-components-native-form`，使舊轉換／Hub 統計快取失效；同版仍不做 DFU。首跑 PARTIAL 的原始／投影／物理 NBT 證據保留在 `.work/mv2-2/failures/1.21.1-downgrade/`，兩組 core 回歸及所有平台用修正後同一 jar 重驗。

跨維度 UUID／巢狀乘客 fixture 保持三維度離線 verify 零差異（#215）：地獄 B 用該維度玩家獨立 commit，另斷言 HEAD 改變；新兩版 NoAI 雞以原版 `IsChickenJockey` flag 停止蛋計時，仍完整追蹤 `EggLayTime`，Folia 經官方 Chicken API 在 chunk owner 設定並驗證旗標。測試副本的地獄遠端區域改用無流體／排程刻的合成區塊，隔離新生成熔岩的自然變動；正式 NBT 忽略規則與既有零差異斷言均未放寬。

Folia 關閉鉤子以 adapter 的 I/O 完成訊號為準（#204）。1.20.1／1.20.4 的 `stopPart2` 先完成全部維度存檔，再呼叫 `RegionFileIOThread.close(true)` 等待 flush，返回後才由 MinecraftServer logger 輸出 `Closing Thread Pool`；舊 adapter 僅匹配這個 logger 與精確訊息，不能把 `Flushing Chunk IO` 或 `Halted chunk systems` 當成完成。1.21.11／26.2 保留原訊號；session.lock 不是關閉完成依據。逐 jar bytecode 核對放 `.work/mv2-1/folia-minecraftserver-*.javap`／`folia-regionio-*.javap`，不進 repo；關閉 commit 與停服後 CLI 零差異仍由真服驗收。

任務 2 子任務①：舊兩版 Paper／Folia 的正式驗收與回歸證據集中於 `.work/mv2-1/`。Folia 1.20.x 的 WorldGit 原生場景全部要求通過；**官方 WorldEdit 沒有支援 Folia 1.20.x 的版本，未驗收**。保留 worldedit 測試並在結果的 skipped 欄明記原因，不使用第三方 fork，也不放寬 Paper 或較新 Folia 的斷言（#200）。Paper 1.20.1／1.20.4 使用官方 FAWE 2.8.4 release jar；該官方 jar 的 plugin.yml 自報 2.8.5-SNAPSHOT，驗收紀錄保留兩者。來源為 [FAWE 官方 release](https://github.com/IntellectualSites/FastAsyncWorldEdit/releases/tag/2.8.4) 與 [WorldEdit 官方版本紀錄](https://modrinth.com/plugin/worldedit/versions)；WorldEdit 7.4.1 才加入實驗 Folia 支援，其目標範圍不包含 1.20.x。

子任務②的 Paper 1.20.6／1.21.1 使用 [FAWE 官方 2.15.0 release](https://modrinth.com/plugin/fastasyncworldedit/version/MOe9fY3h) 的 Bukkit artifact，官方 metadata 宣告兩個目標版，下載後核對 SHA-512；實際整合測試另驗證 bulk attribution、biome 與狀態清除。Folia 1.20.6 沿用 #200 的 WorldEdit 缺口，明記未驗收；Folia 1.21.1 無官方 build，不能列成通過。Folia 1.20.6 的 stopPart2／RegionFileIOThread bytecode 同樣確認 flush 完成後才輸出 MinecraftServer 的 `Closing Thread Pool`，沿用 #204 的精確訊號；核對證據在 `.work/mv2-2/folia-1.20.6-*.javap`。

舊版 metadata 使用該版同步 saved-data saver，保存維度設定而不觸發完整 chunk 存檔，並取 live gamerules／DV／GameType／difficulty／DataPacks；26.2 保持原背景 IO future。不得省略 random_sequences 或 gamerule 以通過停服比對。合成平坦 fixture 按 DV 使用正確 spawn 格式並明確停用自然演化，正式插件仍追蹤真實內容變動；自動 commit 測試等所有維度的操作終態後才停服（#201）。首次 FAWE 新座標 API 與停服 metadata 的失敗證據保留於 `.work/mv2-1/failures/`，修正後重新驗收。

### 任務 2 子任務①完成驗收（2026-10-11）

基底 `ddf7731`，變更未提交；以下均以本次最終插件重跑。完整命令、exit code、耗時與產物 SHA-256 見 `.work/mv2-1/report.md`、`summary.json`、`validation.json`、`final-validation.json`。

| 命令／平台 | 結果 | 證據 |
|---|---|---|
| 完整 Gradle `build`（GRADLE_USER_HOME=.work/gradle-home、no-daemon、configure-on-demand、max-workers=1，外包 flock） | 454 JUnit，0 failure／error／skipped；`git diff --check` 通過 | `.work/mv2-1/build-final.log`、`unit-results.json` |
| `python3 paper/tools/acceptance.py paper 1.20.1`、`phase5.py paper 1.20.1` | 33／33、51／51 | `.work/mv2-1/acceptance-paper-1.20.1-results.json`、`.work/paper-phase5/paper-1.20.1-1791724276/results.json` |
| `python3 paper/tools/acceptance.py folia 1.20.1`、`phase5.py folia 1.20.1` | 30／30、52／52；WorldEdit 依 #200 明記未驗收 | `.work/mv2-1/acceptance-folia-1.20.1-results.json`、`.work/paper-phase5/folia-1.20.1-1791724854/results.json` |
| `python3 paper/tools/acceptance.py paper 1.20.4`、`phase5.py paper 1.20.4` | 33／33、51／51 | `.work/mv2-1/acceptance-paper-1.20.4-results.json`、`.work/paper-phase5/paper-1.20.4-1791725456/results.json` |
| `python3 paper/tools/acceptance.py folia 1.20.4`、`phase5.py folia 1.20.4` | 30／30、52／52；WorldEdit 依 #200 明記未驗收 | `.work/mv2-1/acceptance-folia-1.20.4-results.json`、`.work/paper-phase5/folia-1.20.4-1791726036/results.json` |
| `acceptance.py paper 26.2`、`phase5.py paper 26.2` | 33／33、54／54 | `.work/mv2-1/acceptance-paper-26.2-results.json`、`.work/paper-phase5/paper-26.2-1791723686/results.json` |
| `acceptance.py paper 1.21.11`、`phase5.py paper 1.21.11` | 33／33、54／54 | `.work/mv2-1/acceptance-paper-1.21.11-results.json`、`.work/paper-phase5/paper-1.21.11-1791723522/results.json` |
| common API 相容性靜態核對（1.20.4／1.21.11／26.2，官方 Adventure SHA-256） | 各 694 處 API 引用，0 缺漏／class-interface 問題；現代 Paper Phase5 另驗 execute／命名空間補全封包與 GUI | `.work/mv2-1/api-compatibility.json` |
| `phase5.py paper 1.20.1 --conflict`、`phase5.py folia 1.20.4 --disabled` | 別名衝突／停用兩組通過 | `.work/mv2-1/validation.json` |
| `python3 paper/tools/multiversion.py` | 35／35；自動升級、有損拒絕／確認、中文預覽、跨玩家拒絕／世界變更後 --force 操作的舊 token 失效並重列預覽、單次使用；switch／reset／merge verify 零差異 | `.work/mv2-1/online/results.json` |
| `phase4_regressions.py --case paper-1.21.11-phase2 --case folia-1.21.11-phase2` | 兩組通過 | `.work/mv2-1/phase2-regressions.log` |
| `acceptance.py folia 1.20.1 sigterm`、`folia 1.20.4 sigterm` | 兩組 JVM 自行結束、shutdown SUCCESS、commit +1／auto 標記、離線三維度全欄位零差異 | `.work/mv2-1/sigterm-validation.json`、`sigterm-folia-*-results.json` |

同機 Paper 26.2／Java 25、同 fixture、無玩家、三輪，status 及 switch 端到端 p50 秒數；基準為任務 1 保存的 `ddf7731` 插件，兩次 runner 分別持 bench.lock。原始樣本與完整 probe 保留於 `.work/perf/mv2-before-paper-26.2/`、`mv2-after-paper-26.2/`。

| 操作 | ddf7731 | 本次 | 變化 |
|---|---:|---:|---:|
| `status-noop` | 0.254 | 0.261 | +2.8% |
| `switch-noop` | 0.725 | 0.697 | -3.9% |
| `switch-1` | 1.456 | 1.480 | +1.7% |
| `switch-100` | 8.447 | 8.477 | +0.3% |

四項比值幾何平均 1.002；此三輪合成場景抽樣不代表大型實體／BE 世界的效能。

最終插件 SHA-256：`84762ef8782ee1e3e72be7bfc67182a23abfc61b9e8874aea464f6a568eb59fd`。中文終態、舊 Folia 關閉訊號與 spawn descriptor 的首跑失敗均保留於 `.work/mv2-1/failures/`，修正後完整重跑；不刪除測試、不放寬斷言。

子任務②接續新增 `paper/v1_20_6`／`v1_21_1`；結果見下方子任務②驗收紀錄。子任務③從 `paper/v1_21_4`／`v1_21_8` 精確 bundle、callback／設備／ticket 邊界與已確認的共享來源開始；每版編譯並跑官方 Paper／Folia，不能把候選家族當成已驗收 binary。

### 任務 2 子任務②完成驗收（2026-10-11）

基底 `361ae83`，工作樹未提交；新增 Paper 1.20.6／1.21.1 與 Folia 1.20.6，決策 #208–#215。全部命令、exit code、耗時、原始 console／chat 與完整修改清單見 `.work/mv2-2/report.md`、`summary.json` 及各 `*-receipt.json`。以下均使用 #214 修正後同一份最終插件。

| 命令／平台 | 結果 | 證據 |
|---|---|---|
| `GRADLE_USER_HOME=.work/gradle-home flock --close .work/bench.lock ./gradlew --no-daemon --configure-on-demand --max-workers=1 build` | 457 JUnit，0 failure／error／skipped；`git diff --check` 通過 | `.work/mv2-2/build-final.log`、`unit-results.json` |
| `acceptance.py paper 1.20.6`、`phase5.py paper 1.20.6` | 33／33、54／54；官方 FAWE 2.15.0 release | `.work/mv2-2/acceptance-paper-1.20.6-results.json`、`.work/paper-phase5/paper-1.20.6-1791735665/results.json` |
| `acceptance.py folia 1.20.6`、`phase5.py folia 1.20.6` | 30／30、55／55；WorldEdit 依 #200 明記未驗收 | `.work/mv2-2/acceptance-folia-1.20.6-results.json`、`.work/paper-phase5/folia-1.20.6-1791736255/results.json` |
| `acceptance.py paper 1.21.1`、`phase5.py paper 1.21.1` | 33／33、54／54；官方 FAWE 2.15.0 release | `.work/mv2-2/acceptance-paper-1.21.1-results.json`、`.work/paper-phase5/paper-1.21.1-1791736877/results.json` |
| `multiversion.py`：1.20.1↔1.20.4 | 37／37；自動升級、損失預覽／未確認不寫入／token 綁定、物品欄位保存及 switch／reset／merge verify 零差異 | `.work/mv2-2/online/1.20.1-to-1.20.4/results.json` |
| `multiversion.py`：1.20.4↔1.20.6 | 39／39；自動升級、損失預覽／未確認不寫入／token 綁定、物品欄位保存及 switch／reset／merge verify 零差異 | `.work/mv2-2/online/1.20.4-to-1.20.6/results.json` |
| `multiversion.py`：1.20.6↔1.21.1 | 37／37；自動升級、損失預覽／未確認不寫入／token 綁定、物品欄位保存及 switch／reset／merge verify 零差異 | `.work/mv2-2/online/1.20.6-to-1.21.1/results.json` |
| `components.py`：Paper 1.20.6／1.21.1、Folia 1.20.6 | 三組通過；真 NBT pending ticks 替換清除、lock unpack、20 owner ticks 後剩餘延遲 600、priority -1／1 | `.work/mv2-2/pending/*/results.json` |
| `acceptance.py <platform> <version> phase2-entities`：新增三平台 | 三組通過；巢狀乘客、UUID 與其他維度／未載入磁碟普查 | `.work/paper-phase2/entities-*/results.json` |
| `acceptance.py folia 1.20.6 sigterm` | 1／1；JVM 自行結束、shutdown SUCCESS、commit +1／auto、離線三維度零差異 | `.work/mv2-2/sigterm-folia-1.20.6-results.json` |
| `acceptance.py paper 1.20.1` | 33／33 | `.work/mv2-2/acceptance-paper-1.20.1-results.json` |
| `acceptance.py paper 1.20.4` | 33／33 | `.work/mv2-2/acceptance-paper-1.20.4-results.json` |
| `acceptance.py paper 1.21.11` | 33／33 | `.work/mv2-2/acceptance-paper-1.21.11-results.json` |
| `acceptance.py paper 26.2` | 33／33 | `.work/mv2-2/acceptance-paper-26.2-results.json` |
| `phase4_regressions.py --case paper-1.21.11-phase2 --case folia-1.21.11-phase2` | 兩組全綠 | `.work/mv2-2/phase2-regressions.log` |
| common API 相容性核對：1.20.4／1.20.6／1.21.1／1.21.11／26.2 | 各 API 引用皆無缺漏或 class／interface 問題；JDK／官方 Adventure SHA-256 一併核對 | `.work/mv2-2/api-compatibility.json` |

同機 Paper 26.2／Java 25、相同 fixture、無玩家、三輪，兩個 runner 分別持 bench.lock。基準 jar SHA-256 與子任務①發行產物一致；status／switch 端到端 p50 秒數如下。

| 操作 | 361ae83 | 本次 | 變化 |
|---|---:|---:|---:|
| `status-noop` | 0.254 | 0.255 | +0.0% |
| `switch-noop` | 0.703 | 0.704 | +0.1% |
| `switch-1` | 1.450 | 1.477 | +1.9% |
| `switch-100` | 8.448 | 8.501 | +0.6% |

四項 p50 比值幾何平均 1.007；原始樣本 `.work/perf/mv2-2-before-paper-26.2/`、`mv2-2-after-paper-26.2/`。合成場景與三輪抽樣不代表大型實體／BE 世界。

本輪抽樣持平；四項 p50 增幅均小於 1.9%，最大差距約 53 毫秒，未見明顯退步。

最終插件 SHA-256：`7e11761f9febf256126c3df53b21c3af4f07fff576303dc1d913960e83cde9a0`。舊 Lifecycle redirect、ANSI 比對、fixture 路徑／原版實驗包、spawnChunkRadius 設定差異與 #214 首跑 PARTIAL 均保留於 `.work/mv2-2/failures/`，修正後重驗，既有斷言全部保留。

Folia 1.21.1 沒有官方 build，不可驗收；Folia 1.20.6 的 WorldEdit 缺口按 #200 明記未驗收。只有已編譯且 bytecode 相同的 NBT／指令來源共享；子任務②完成時，1.21.4 尚未加入已確認家族，子任務③的確認與驗收見下文。世界設定／資料包差異仍保持線上拒絕；keep-spawn-loaded 遷移後的新 gamerule 差異先驗證拒絕，再明確採原版預設值建立相容 fixture。

子任務③新決策從 #216 起；先固定 `v1_21_4`／`v1_21_8` 的精確 bundle，確認 callback、設備 codec、tick 集合 wrapper、ticket 與 IO barrier，再擴充 selector、官方 Paper／Folia 矩陣與跨版本線上工具。

### 任務 2 子任務③完成驗收（2026-10-12）

基底 `9074d50`，工作樹未提交；新增 Paper／Folia 1.21.4、1.21.8，決策 #216–#232。前回合對話中斷後，序列 runner 繼續完成剩餘矩陣；接續回合逐份核對終態、退出碼、原始證據與最終插件 SHA，沒有依過時 pid 重新啟動 runner。完整修改清單、命令、耗時及清理見 `.work/mv2-3/report.md`、`summary.json`、`evidence-audit.json` 與各 `*-receipt.json`。

| 命令／平台 | 結果 | 證據 |
|---|---|---|
| `GRADLE_USER_HOME=.work/gradle-home flock --close .work/bench.lock ./gradlew --no-daemon --configure-on-demand --max-workers=1 build` | 480 JUnit，0 failure／error／skipped；`git diff --check` 通過 | `.work/mv2-3/build-final.log`、`unit-results.json` |
| `acceptance.py paper 1.21.4`、`phase5.py paper 1.21.4` | 33／33、55／55；官方 FAWE 2.15.0 | `.work/mv2-3/acceptance-paper-1.21.4-results.json`、`phase5-paper-1.21.4.log` |
| `acceptance.py folia 1.21.4`、`phase5.py folia 1.21.4` | 33／33、56／56；官方 WorldEdit 7.4.2，沒有略過整合 | `.work/mv2-3/acceptance-folia-1.21.4-results.json`、`phase5-folia-1.21.4.log` |
| `acceptance.py paper 1.21.8`、`phase5.py paper 1.21.8` | 33／33、55／55；官方 FAWE 2.15.0 | `.work/mv2-3/acceptance-paper-1.21.8-results.json`、`phase5-paper-1.21.8.log` |
| `acceptance.py folia 1.21.8`、`phase5.py folia 1.21.8` | 33／33、56／56；官方 WorldEdit 7.4.2，沒有略過整合 | `.work/mv2-3/acceptance-folia-1.21.8-results.json`、`phase5-folia-1.21.8.log` |
| `multiversion.py --pair 1.21.1:1.21.4`、`--pair 1.21.4:1.21.8`、`--pair 1.21.8:1.21.11` | 各 53／53；自動升級、中文損失預覽、未確認不寫入、token 綁定及 switch／reset／merge verify 零差異 | `.work/mv2-3/online/*/results.json` |
| `components.py <platform> <version>`：新增四平台 | 四組通過；pending ticks 替換清除、lock unpack、owner clock／delay 600／priority -1,1 | `.work/mv2-3/pending/*/results.json` |
| `acceptance.py <platform> <version> phase2-entities`：新增四平台 | 各 12／12；UUID 普查、巢狀乘客、跨維度及未載入磁碟實體 | `.work/paper-phase2/entities-*/results.json` |
| `acceptance.py folia 1.21.4 sigterm`、`folia 1.21.8 sigterm` | 各 1／1；JVM 自行結束、關閉 commit +1／auto／SUCCESS、停服後三維度零差異 | `.work/mv2-3/sigterm-folia-*-results.json` |
| `acceptance.py paper <version>`：1.20.1／1.20.6／1.21.1／1.21.11／26.2 | 五組各 33／33 | `.work/mv2-3/acceptance-paper-*-results.json` |
| `phase4_regressions.py --case paper-1.21.11-phase2 --case folia-1.21.11-phase2` | 兩組全綠 | `.work/mv2-3/phase2-regressions.log` |
| common API 與完整共享 class 比對 | API 引用與 class／interface 無缺漏；components 三 bundle、modern 兩個 Java 21 bundle 逐 byte 相同 | `.work/mv2-3/api-compatibility.json`、`shared-sources-proof.json` |

跨版讀回另核對 boat ID／Type、equipment／saddle、attributes、custom_model_data、JSON／NBT 名稱／lore／招牌、附魔與不透明 custom_data。真服首跑失敗及修正證據保留 `.work/mv2-3/failures/`，沒有刪測試或放寬斷言。最終快取契約為 `multiversion-canonical-v19-native-border-form`、`runtime-saved-data-v5-native-defaults`、reverse revision 20、`paper-metadata-v3`（含 marker minor format）。

同機 Paper 26.2／Java 25、相同 fixture、無玩家、三輪；基準 jar SHA 與 `9074d50` 發行產物一致。status／switch 端到端 p50 秒數不含 harness settle sleep。

| 操作 | 9074d50 | 本次 | 變化 |
|---|---:|---:|---:|
| `status-noop` | 0.254 | 0.255 | +0.39% |
| `switch-noop` | 0.703 | 0.702 | -0.04% |
| `switch-1` | 1.448 | 1.449 | +0.07% |
| `switch-100` | 8.422 | 8.399 | -0.27% |

四項 p50 比值幾何平均 1.000；此抽樣未見實質退步，不代表大型實體／BE 世界。原始樣本 `.work/perf/mv2-3-before-paper-26.2/`、`mv2-3-after-paper-26.2/`。

最終插件 SHA-256：`f7967c8a484c6a9fa5dd43f9033d6cea5e7c038c825b990be3e5390a41632946`；CLI：`514eae9dcffd0bcdf38d4d428b66a96c79614a41e56019e180385978ac1dcc12`。有損降級仍須確認；自訂資料包或受追蹤世界設定不同時保持線上拒絕，需停服用 CLI。Folia 1.21.1 與舊 Folia WorldEdit 的官方缺口保持原註記；本次兩版 Folia 都完成官方 WorldEdit 驗收。

子任務④新決策從 #233 起，建議先固定 `v26_1_2`／`v26_3` 的精確 bundle、Java 25 與官方 Paper／Folia build；核對 namespaced saved-data、ValueInput／ValueOutput、新 state codec、ticket／owner／UUID／IO，再逐版編譯與真服驗收。沒有官方 Folia build 時沿用 #210 的明確拒絕與未驗收註記。

轉換後的歷史 chunk 再按同一份 `.wgignore` 正規化；空 tick 清單省略，ID 改名後重新排序。舊版實體載入所需的 `Health` 等預設值只在物理實體化時補入，不進歷史雜湊。離線套用同 UUID、同類型的實體時保留目前生命值；新實體依 max_health 與原版預設值補足，並遞迴處理乘客。轉換與 Hub 統計快取的正規化版本同步更新（#179）。

2026-10-11 整合排程修正（#169–#171）後，多版本決策改為 #172–#195。升級與降級的 block_ticks／fluid_ticks 都按 `max(t,0)` 夾住剩餘延遲，正值與 priority 保留；整世界降級保留執行期欄位的 runtime 路徑同樣夾值，不能因略過歷史正規化而帶回負值。NBT 實體化在呼叫 DFU 或寫入物理世界前也夾值。跨 DV 投影後只差 ticks 不構成 diff／dirty／驗證差異，merge 不產生 tick 衝突；只有改寫方塊／BE 或 biome 的 chunk 才附帶目標排程，其餘維持目前世界排程。

轉換正規化版本升為 `multiversion-canonical-v12-ticks-clamp`，使舊 v11 轉換與 Hub 統計／merge preview 快取失效。capture fingerprint 保留離線 `normalize-v2-ticks-clamp:`／預設 `normalize-v2:` 前綴、DataVersion 與離線 registry fingerprint，再附上多版本正規化版本；SavedData.NORMALIZATION 仍由索引政策鍵納入。`MultiversionTicksTest` 驗證雙向投影、歷史／runtime 排程、跨版 diff／merge／套用、v11 舊快取拒用及兩種 capture fingerprint。

舊版（DV ≤4189）的原版文字欄位以 JSON 語意正規化：純文字物件與字串等價、object keys 排序；樣式、事件與 lore 保留，custom_data 不透明。原版 DFU 往返會將物品名稱 `{"text":"WorldGit sword"}` 改為 `"WorldGit sword"`，正規化避免這種序列化差異造成假改動；失效的文字 JSON 保持原值，不代替原版修復。

fixture 由 `fixtures.py` 啟動真原版十版生成，存 `.work/worlds/<version>/multiversion/world/`，包含普通方塊、chest/sign/furnace、附魔／名稱／custom_data、armor_stand、forest biome 與新版代表內容。腳本與規則進 repo，jar／mappings／世界不進 repo。`acceptance.py` 自帶 bench.lock，逐版 init、dry-run、確認拒絕、遷移、target 真服 forceload 與讀取箱子；跨版本矩陣證據在 `.work/mv1-recovery/multiversion/`；本輪 build 與平台回歸在 `.work/mv1-finish/`。不得以改 DataVersion 的合成世界當真服轉換證據。

上一回合已核對的驗收結果（2026-10-11；本次整合另見後文）：

| 項目 | 結果 | 證據 |
|---|---|---|
| `flock --close .work/bench.lock ./gradlew --no-daemon --configure-on-demand --max-workers=1 build` | 全綠；432 JUnit，0 failure/error/skipped | `.work/mv1-finish/build.log`、`unit-results.json` |
| `python3 tools/multiversion/acceptance-pure.py` | 9／9 來源版嚴格往返，所有正規化 component hash 一致；#195 後重跑 | `.work/mv1-finish/multiversion/pure-results.json` |
| `python3 tools/multiversion/acceptance.py` | 14／14 真服全綠；九組升級、五組降級，損失拒絕／箱子／舊版玩家與 runtime 狀態檢查通過 | `.work/mv1-recovery/matrix-results.json`、`multiversion/open-*.log`、`bot-*.log` |
| `python3 tools/multiversion/acceptance-dfu-targets.py` | 九個較新目標的 CHUNK／ENTITY_CHUNK／LEVEL 原版 DFU 入口通過；#194 後重跑 | `.work/mv1-finish/multiversion/dfu-targets.json` |
| `python3 tools/multiversion/acceptance-extra.py` | 49／49；全部來源的原版 forceUpgrade 對照、真服往返、外部升級、跨版操作與永久損失 audit 通過 | `.work/mv1-recovery/extra-results.json` |
| `python3 tools/multiversion/acceptance-extra.py --operations-only` | 13／13；#195 後重跑真服外部升級、延遲轉換 chunk、跨版套用、獨立建造合併、損失確認、設定與永久 audit | `.work/mv1-finish/operations/extra-results.json`、`operations-smoke.log` |
| `python3 tools/multiversion/platform-probe.py` | 舊兩版 Paper 載入 Java 21 bytecode；Java 21 CLI ↔ Java 25 DFU 通過 | `.work/mv1-recovery/multiversion/platform-java21.json` |
| `cd hub/web && npm test && npm run lint && npm run build` | 26／26、lint／typecheck／build 全綠 | `.work/mv1-recovery/web.log` |
| `python3 paper/tools/phase5.py paper 1.21.11` | 50／50，指令、GUI、owner 排程及進度回歸通過 | `.work/mv1-finish/paper-phase5-results.json`、`paper-phase5.log` |
| `python3 paper/tools/acceptance.py paper 1.21.11` | 32／32；三維度停服後離線 status 乾淨 | `.work/mv1-finish/paper-acceptance-results.json`、`paper-acceptance.log` |
| `python3 fabric/tools/accept-phase5.py 1.21.11 --mode dedicated` | 173／173，dedicated 與實際客戶端 GUI 回歸通過，結束後連接埠關閉 | `.work/mv1-finish/fabric-phase5-results.json`、`fabric-phase5.log` |
| `bash hub/scripts/phase5-acceptance.sh` | SQLite、獨立 PR、graph、SSE／poll、ZIP 與瀏覽器全綠 | `.work/mv1-finish/hub-phase5/`、`hub-phase5.log` |
| `python3 tools/multiversion/hub-acceptance.py` | 13／13；真發行 jar 的跨版摘要、binary diff、chunk、entity、tile／LOD、merge preview 與瀏覽器顏色／前後比較全綠 | `.work/mv1-finish/multiversion/hub/results.json`、`cross-version-color.png`、`hub-cross-version.log` |
| `python3 tools/performance/benchmark.py --label after-mv1 --jar .work/mv1-finish/artifacts/wgit.jar` | 1,296／20,000 chunk、rounds=3 全綠；與同機基準比對的 32 組操作整體持平 | `.work/perf/after-mv1/result.json`、`.work/mv1-finish/performance-comparison.json` |
| `python3 tools/performance/benchmark.py --label before-mv1 --jar .work/mv1-recovery/artifacts/before-mv1-metafix.jar` | 任務前含 metadata 修正的產物，同 fixture／Java 21／輪數全綠 | `.work/perf/before-mv1/result.json`、`.work/mv1-finish/performance-baseline.log` |

本次整合的指定重驗（2026-10-11，基於 `7751261`，變更未提交）：

| 命令 | 結果 | 證據 |
|---|---|---|
| `GRADLE_USER_HOME=.work/gradle-home flock /root/projects/ProjectCollection/WorldGit/.work/bench.lock ./gradlew --no-daemon --configure-on-demand --max-workers=1 build --rerun-tasks` | 84 個工作重新執行，全綠；442 JUnit，0 failure/error/skipped | `.work/mv1-integration/build.log`、`unit-results.json` |
| `python3 tools/multiversion/acceptance.py --case 1.20.1 26.3` | 原版 DFU 升級、目標真服、箱子／實體及 runtime 狀態保留通過 | `.work/mv1-integration/upgrade/acceptance-results.json`、`open-1.20.1-to-26.3.log` |
| `python3 tools/multiversion/acceptance.py --case 26.3 1.20.1` | 最長跨度降級、損失預覽與未確認拒絕、真玩家進出 chunk 與箱子內容通過 | `.work/mv1-integration/downgrade/acceptance-results.json`、`bot-26.3-to-1.20.1.log` |
| `python3 paper/tools/acceptance.py paper 26.2` | 32／32；三維度停服後離線 status 完全乾淨，定時／登出自動 commit 通過 | `.work/mv1-integration/paper-26.2-results.json`、`paper-26.2.log` |
| `git diff --check` | 通過；18 張驗收截圖已還原 | `.work/mv1-integration/summary.json` |

本次 CLI artifact SHA-256 為 `548a8753cc09aa7db037759e0df282fc5f3610c1d0a854dcb1905fcb50329a05`（`.work/mv1-integration/artifacts/wgit.jar`），與兩組遷移驗收固定使用同一產物；Paper 實際載入插件與該 CLI 的共用 core bytecode 亦核對一致。首跑新增測試的 fixture 漏設 biome，已補齊 fixture，保留原斷言並重跑完整 build；失敗紀錄保留在 `build-first-attempt.log`。Paper 首跑因測試用 `server.jar` 已清除而未啟動，已補回官方 build 133，核對官方 SHA-256 後重跑；啟動失敗紀錄保留在 `paper-26.2-first-attempt.log`，下載證據在 `paper-server.json`。

其餘驗收沿用上表上一回合結果，11 套證據逐項核對見 `.work/mv1-integration/historical-evidence-audit.json`。兩套效能矩陣亦沿用上一回合的固定產物，32 組比值重算為 0.999354，最大單項增幅 7.4%；本次未重跑效能矩陣，不把先前量測標為本次新 jar 的量測。決策映射、當前 HEAD／產物 hash、runner／鎖／連接埠清理與完整修改清單見 `.work/mv1-integration/summary.json`。

跨版本矩陣與完整原版對照的 CLI artifact SHA-256：`98467c0618f0e691c7c7db4ba82182ff10ff5bebd74a7135520c42dcca6d3a43`，位於 `.work/mv1-recovery/artifacts/wgit.jar`。#193–#195 後、整合排程修正前的完整 build CLI artifact 為 `7b7ae907d7bb16befd73f7b178ad72df492df67e17efa4b8b3568f6740b938bc`（`.work/mv1-finish/artifacts/wgit.jar`）。相對矩陣產物的差異限於 SavedData 舊維度襲擊時鐘別名、引用正規化 fingerprint 的類別、DFU worker 的啟動 classpath，以及離線 chunk 版本檢查的目標 DV 快取；轉換引擎與 worker 內容不變，差異核對見 `.work/mv1-finish/artifact-delta.json`。保留原矩陣，該回合產物另通過九版嚴格往返、十三項跨版操作、九個原版 DFU 入口、完整平台回歸及 Hub 真 jar 的跨版／瀏覽器驗收。各平台 jar 使用對應回歸紀錄中的獨立 hash，不以 CLI jar 的 hash 代替。

不能把 unit test 或某版啟動當成任意含 mod 世界的安全降級保證。反向資料是盡力轉換，未知 codec 必須列損失或明確拒絕；自訂資料包動態 registry／runtime 及自訂生成設定需要額外政策與驗收。玩家目錄搬移與 codec 已實作；真玩家登入／背包完整矩陣仍須額外 fixture 驗收。新增純轉換往返在任何目標開服前比對所有正規化 component，防止原版重存代替反向 codec 修復未知欄位。真服比對先在來源版本建立共同重存基準；1.21.11 creaking_heart 的原版控制組也會把到期 tick 重存，未凍結時倒數會隨 save 時序由 0 變 −1／−2。1.21.11 與後續來源的測試副本移遠出生點、停服前解除強制載入；每次完整啟動後先凍結 tick，再載入代表區域，避免開服時先推進排程。來源真服重存建立共同基準；1.21.11、26.1.2、26.2 已通過包含 tick 的嚴格真服往返。ticks 的全部欄位與雜湊斷言仍保留；這是測試條件控制（#185）；整合後的執行期排程語意依 #169，轉換與直接擷取均夾負值，嚴格排程欄位／雜湊斷言保持。原版直接 forceUpgrade 比較以正規化內容為準，時間／光照／POI 等衍生或未追蹤欄位不要求 binary 一致。

原版重開世界會重建 `DataPacks.Disabled` 的可用實驗包名單，例如 1.20.4 的 bundle/update_1_21 與 26.3 的 minecart_improvements/redstone_experiments 不同。既有 DataPacks 完整清單預檢維持原值；測試先確認不同清單的 restore 被拒絕，再用 `FixturePacks.java` 明確同步測試副本中未啟用的原版實驗包。工具要求 Enabled 恰為 vanilla，遇到未知 Disabled 包即失敗；不忽略欄位，也不改來源 fixture。跨版本套用仍要求使用者先提供一致的資料包設定。

回歸指令依序執行，重型 runner 同時僅一個：完整 Gradle build（外包 flock）、Paper phase5／acceptance、Fabric dedicated phase5、Hub phase5、CLI `benchmark.py --label after-mv1`。以上全部通過，不刪除／放寬既有測試斷言；命令、exit code 與各階段產物記錄在 `.work/mv1-finish/validation.json`，最終核對與清理證據在 `summary.json`。

同版本效能基準 CLI SHA-256 為 `279e4f8d121ec7f8fee29c94dbc8996adf103c71bc74cf6374a96fb10fb020cc`，不含跨版本引擎；與上一回合產物使用同一份合成 fixture、一個 solid section／chunk、Java 21、`-Xmx1G`、相同輪數。init／verify 各一次，commit／merge 三次，來回 switch 六次；下表為秒。首次量測發現每筆 NBT 兩次讀取 level.dat 的成本，原始紀錄保留於 `.work/perf/after-mv1-before-cache/`；#195 修正後重新跑完整平台回歸與矩陣。

| 世界大小／操作 | 任務前基準 | 上一回合 | 快取修正前 |
|---|---:|---:|---:|
| 1,296 init | 2.720 | 2.130 | 2.444 |
| 1,296 全量 verify | 1.545 | 1.541 | 1.803 |
| 20,000 init | 9.562 | 10.038 | 12.901 |
| 20,000 status no-op | 1.198 | 1.205 | 1.843 |
| 20,000 commit 1,000 | 3.000 | 3.222 | 3.253 |
| 20,000 switch 1,000 | 4.806 | 4.809 | 5.288 |
| 20,000 全量 verify | 4.772 | 4.808 | 7.557 |

上一回合兩套完整矩陣總時間為 440.54／440.56 秒，32 組 p50 比值的幾何平均為 0.999，未見系統性退化。最大單項增幅是 20,000 chunk 的 commit 1,000：+7.4%／0.222 秒；保留此差異與原始樣本，不宣稱每項時間都下降。合成 fixture 與三輪測量不能代替含大量實體、BE 或 mod 的世界效能驗收。

暫態規則與跨版本遷移一致（#159–#161、#172–#180）：歷史 metadata 轉換前後都套用同一組暫態過濾；原版 DFU 產生的 world_clocks／weather／wandering_trader 不進入升級 tree，舊歷史的這些條目也不會降成可追蹤的 Time／DayTime／天氣欄位。整世界升降級另以目前磁碟資料保留並轉換執行期狀態，轉換後再按目標版 capture 規則建立 commit；一般 switch／restore 保留目前世界的執行期狀態。26.x world_clocks 的 total_ticks 對應舊版 DayTime，level.dat 的 Time 獨立保留。raids 的舊 Tick／新 tick 與舊位置 random_sequences 同樣遵守暫態規則。轉換快取鍵包含 SavedData.NORMALIZATION 與 metadata codec 版本，避免沿用修正前的轉換結果。

舊版耐久物品的 `Damage:0` 在原版 DFU 升級時會成為省略的元件預設值。降回 1.20.1／1.20.4 時依原版 item report 的 `max_damage` 清單與目標 registry 交集還原零值；普通物品不加欄位，非零耐久保留（#181）。

1.20.6–1.21.4 的 BE component patch 在非空時才重存；反向 YAML `omit-empty-fields` 移除新版新增的空 map，保留非空 patch。九個來源版本先跑完全不開服的往返，再跑真服直接升級比對與重開往返（#183）。

依 [1.21.4 官方發行說明](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-4)，熔爐的 CookTime／CookTimeTotal／BurnTime 改名與 lit_total_time 在此版已存在。反向邊界為目標 ≤3955，1.21.4 保留新名與非零數值；這些數值的 short 還原、溢位損失檢查維持嚴格（#184）。

Hub 跨版本 wire／瀏覽器驗收使用真服 fixture 的獨立副本，保留 0..1／0..1 四個代表 chunk 的原始 NBT；`FixtureWindow.java` 只移除副本中自動生成的外圍地形，不改來源 fixture、DataVersion 或代表內容。原版升級與 CLI 矩陣仍使用完整原始世界，包含舊版自動生成的約兩千個 chunk。Hub 的聚合解析上限保持原值，不為測試放寬。

舊主世界 DragonFight 經 DFU 成為 `minecraft.the_end.saved.<encoded-path>.nbt`。主世界擷取保留此歷史別名；離線套用可解析尚未生成地形／repo 的原版終界路徑，只寫 saved-data。終界既有歷史保持可讀；分組套用兩份 DragonFight 目標不一致時預檢拒絕，缺少維度的 fallback 同樣檢查符號連結（#187）。

整世界遷移亦搬移未生成終界的 DragonFight：staging 重擷取的 metadata 必須和實際搬移結果一致，原檔備份及目的地搬移沿用同一 journal；降回舊 layout 後移出已折回 level.dat 的 namespaced 檔案，不建立終界地形／repo。純往返在升級後另要求完整 status 乾淨（#188）。status 與 dirty 預檢的 HEAD 投影讀取實際套用相同的本機 worldgit.yml，包含自訂替代規則；worldgit-repo.yml 僅保存追蹤政策，不能代替轉換設定（#189）。

原版外部升級只在載入時升級 chunk，磁碟可同時存在舊 terrain／entity storage。離線擷取使用目標 DFU 投影；writer 在任何部分 patch 前升級整筆原始 NBT，包含範圍外的 BE 與同筆未修改實體，再發布目標 DataVersion。否則尚未載入的 ominous banner 會保留舊 Patterns／CustomName 卻被標成 26.3；新增 writer 回歸測試及真服跨版操作驗收涵蓋此情境。同版本不呼叫 DFU（#190）。

跨版 merge 同時記錄 base／theirs 的多筆轉換 audit。每筆損失清單及座標樣本使用獨立集合，避免共用空 immutable List 使 SnakeYAML 輸出 alias；嚴格 reader 的 maxAliases=0、大小與深度上限保持原值。補多筆無損／共用非空損失樣本的 journal 寫讀測試（#191）。

26.x Overworld 的維度 saved-data 降回舊版時使用根目錄的歷史名稱，其他維度保持前綴。舊版主世界同樣擷取 raids／forced chunks 等 saved-data；ScheduledEvents 在舊版 level.dat 與新版 saved-data 之間完整轉換，擷取及套用的白名單一致。實際套用後要求 metadata 逐 blob 回讀相等，避免內容已寫入但 verifier 仍判為 PARTIAL；更新正規化 fingerprint 與轉換快取版本（#192）。

Paper 舊版地獄／終界使用 `data/raids_nether.dat`／`raids_end.dat`。兩者與 `raids.dat` 的 Tick／tick 都是暫態時鐘，襲擊內容及 next ID 仍完整追蹤；套用保留物理時鐘，新檔補零。模組 namespace 的同名檔不套此規則。Paper 回歸曾因終界時鐘 4511 → 4799 產生停服後假差異，修正與單元測試見 #193。
