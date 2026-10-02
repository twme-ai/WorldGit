# 09 — 路線圖、已決定事項與待討論決策

## 已決定（2026-09-30）

| # | 議題 | 決定 | 影響文件 |
|---|---|---|---|
| 1 | 儲存後端 | **JGit**，core 內保留 `ObjectStore` / `RefStore` 介面，之後視量測結果再評估 | [03](03-storage-backend.md) |
| 2 | 追蹤範圍 | **除了玩家以外全部預設追蹤**：所有生物（含自然刷出的怪物）、掉落物/經驗球/投射物等暫態實體、所有已生成的 chunk（下載即得完整世界）。生物移動的雜訊以正規化、UUID 全域比對、容許距離處理。「只存玩家改過的 chunk」為可選設定、預設關閉（#19） | [02](02-data-model.md) §4、§7、§8 |
| 2a | 排除規則 | **`.wgignore`**：類似 `.gitignore`、放在 repo 內被版本控制；可依座標範圍、實體類型/tag、NBT 欄位排除，支援 `!` 加回與後面優先。每個維度各有 repo 與規則，不再支援 `dimension` 規則（2026-10-01） | [02](02-data-model.md) §5 |
| 3 | 平台優先順序 | **插件、模組、CLI、網頁四端同等優先**；主要使用者是多人伺服器；Fabric 模組除了單人世界，也要作為 Paper 伺服器玩家的客戶端顯示端 | [08](08-architecture.md) §2、§6 |
| 4 | 支援範圍 | 長期越廣越好；**首發 Paper 與 Fabric 的 1.21.11 與 26.2** | [08](08-architecture.md) §5 |
| 5 | 網頁端 | 類 GitHub 的網頁檢視端是正式目標（原始筆記中「用不到」為筆誤） | [07](07-remote-hub.md) |
| 6 | Hub 部署 | **自架與公開服務都要**，同一套程式碼、多租戶設計 | [07](07-remote-hub.md) §4 |
| 7 | 切換時的玩家 | **不移動玩家**；切換後給短暫的窒息/摔落傷害保護；可在設定改成移到安全位置 | [05](05-switch-restore.md) §1 |
| 8 | Hub 技術棧 | 後端 **Java**（Spring Boot + JGit `GitServlet`）；前端**全新撰寫**，不沿用 BlockForge | [08](08-architecture.md) §3、[10](10-web-frontend.md) |
| 9 | Folia | **首發支援**，同一個插件 jar | [08](08-architecture.md) §7 |
| 10 | 專案名稱 | **WorldGit** | |
| 11 | CoreProtect | **不整合**。作者歸屬與 blame 只用 WorldGit 自己的追蹤（事件、WorldEdit/FAWE API） | [04](04-commit-and-status.md) §2 |
| 12 | 網頁前端參考 | 參考 BlueMap 等既有地圖/渲染專案，採「伺服器預先渲染 tile + 瀏覽器即時網格」混合架構；**依 Phase 0 實驗結果：遠景嵌入 BlueMap core，近景 / diff 用 deepslate 的模型層加自寫網格生成與繪製** | [10](10-web-frontend.md) §9 |
| 14 | Hub 的 Java 版本 | **Hub 用 Java 25**（BlueMap 5.x 需要）；core、插件、模組、CLI 維持 Java 21 相容 | [08](08-architecture.md) §3、`experiments/01-bluemap/REPORT.md` |
| 15 | diff 顯示色 | 四端共用一套色票：**新增綠、移除紅、修改黃、衝突紫**，並搭配不同呈現方式（實心外框 / 鬼影 / 虛線 / 閃爍）讓色盲也分得出；可換色盲色票或自訂（使用者建議，2026-09-30） | [06](06-diff-merge.md) §1.1、[10](10-web-frontend.md) |
| 16 | `.wgignore` 範本 | `init` 時可選**創造模式**與**生存模式**兩份範本：創造範本維持全部追蹤（只附註解範例）；生存範本預設排除自然刷出、會消失的生物與掉落物／經驗球／投射物（使用者決定，2026-10-01） | [02](02-data-model.md) §5.1 |
| 17 | 大世界託管 | 所有 pack **一律切成 < 100 MB**；小世界可直接放 GitHub 等一般 git 託管，大世界放自架服務（自架 WorldGit Hub、Gitea…）（使用者決定，2026-10-01，結束待討論 A） | [03](03-storage-backend.md)、[07](07-remote-hub.md) §2 |
| 18 | 每個維度一個 repo | 主世界、地獄、終界與自訂維度**各自是獨立的 repo**（一個維度＝一個世界＝一個 repo，也可以只用其中一個）；世界層級資料（`world-meta`）放在主世界 repo，主世界 repo 記錄其他維度 repo 的清單（使用者決定，2026-10-01） | [02](02-data-model.md) §2.1、[08](08-architecture.md) §4 |
| 19 | 只存玩家改過的 chunk | 做成**可設定的選項，預設關閉**：預設仍儲存所有變動（含自然生成的 chunk）；開啟後未被改過的自然地形不存，clone／切換時依種子重新生成（使用者決定，2026-10-01，補充 #2） | [02](02-data-model.md) §7.1 |
| 20 | Hub 打包 | Hub 提供 **OCI 容器映像**，Docker 與 Podman（含 rootless）都要能直接部署；附 compose 檔。Cloudflare 等雲端平台暫不考慮（使用者決定，2026-10-01） | [07](07-remote-hub.md) §4.1 |
| 21 | 設定檔格式 | **所有設定檔統一用 YAML**，不用 TOML：插件／模組／CLI 的本機設定 `worldgit.yml`、Hub 的 Spring Boot `application.yml`、repo 內的設定（例如 `track: modified-only`）。Paper 插件的 `config.yml` 與 Spring Boot 本來就是 YAML（使用者決定，2026-10-01） | [02](02-data-model.md) §5、[08](08-architecture.md) |
| 22 | 多語言 | **插件與模組支援多語言**，訊息用 **MiniMessage** 格式：共用的 `i18n` 模組放 YAML 語言檔（內建 `en_us`、`zh_tw`），依玩家客戶端語言顯示，找不到時退回 `en_us`；伺服器管理者可放覆寫檔只改部分訊息。Paper 直接用內建的 Adventure/MiniMessage；Fabric 用 Adventure 的 Fabric 平台（兩版皆可用時），否則自行把 MiniMessage 轉成原版 Text。diff 色彩用自訂標籤，讓色盲色票全面生效（使用者建議，2026-10-01） | [08](08-architecture.md) §1 |
| 23 | 專案網域 | 使用者已購入 **`worldgit.org`**（2026-10-01），與套件根 `org.worldgit` 一致。之後用於專案網站與文件；是否架設官方公開 Hub（例如 `hub.worldgit.org`）另行決定。因 Hub 可能公開上線，Phase 1 起 Hub 依公開網路服務的標準做安全審查。 | [07](07-remote-hub.md) |
| 24 | Folia 關閉前 commit 與無模組預覽 | **Folia 關閉前 commit 走離線路徑**：onDisable 沒有可用的 region 執行緒，改由 JVM 關閉鉤子等伺服器印出「All RegionFile I/O tasks to complete」（世界已存完）後，用 core 的離線掃描 commit（找不到訊號就放棄、不動世界）。**沒裝模組的玩家改用 `BlockDisplay` 發光描邊**（只對請求者可見、有數量上限與逾時、`/wg clear` 清除、不進實體快照）。（2026-10-01） | [11](11-phase1-progress.md) |
| 13 | 實作方式 | 程式碼由 **Codex（gpt-6.1-sol，透過 Codex 插件）**撰寫，包含需要網路與 Minecraft 伺服器的驗證（已開啟 Codex 寫入沙盒的網路存取）。先前的 Phase 0 實驗由 Sonnet 5.5 子代理執行。Codex 撞到用量限制時**等限制解除後交回 Codex 繼續，不改用 Sonnet**（2026-10-01 改定，取代同日「由 Sonnet 5.5 接手」的做法） | [CLAUDE.md](../CLAUDE.md) |
| 25 | Phase 2 apply／離線寫回 | 中性 ApplyPlan（壓縮 section／mask、biome、UUID entity、metadata），分層短路。RegionWriter 就地更新 sector，zlib 寫入／LZ4 等讀取，支援外部 mcc；光照／Heightmaps／POI 丟棄重建。（2026-10-01） | [02](02-data-model.md)、[05](05-switch-restore.md) §7 |
| 26 | 多維度 HEAD 與 PARTIAL | 同名分支全組同步；非分支 revision 全組 detached。全組預檢→journal→寫回→驗證→HEAD；refs 回復但部分世界不回滾，PARTIAL 以 force switch／reset 全量重套，阻擋新 commit。（2026-10-01） | [05](05-switch-restore.md) §7、[08](08-architecture.md) |
| 27 | stash／reset | stash 為各維度獨立 commit＋refs/worldgit/stash／stash.yml；pop 限原基底與乾淨工作區，成功才 drop。reset hard 無 revision 不移動 HEAD，有 revision 必須 force 並禁止用於已 push 歷史。（2026-10-01） | [02](02-data-model.md)、[05](05-switch-restore.md) §7 |
| 28 | DataFixer 與規則遷移限制 | 本次跨 DataVersion 明確拒絕（不改版本數字冒充升級）；目標 .wgignore 不同或 DataPacks 清單不同預檢拒絕。可靠快照升級與規則遷移留待後續。（2026-10-01） | [05](05-switch-restore.md) §7、[12](12-phase2-progress.md) |
| 29 | metadata／範圍／untracked | 全範圍還原已追蹤世界設定、地圖、記分板；玩家／時鐘／天氣保留。局部不動 metadata，方塊／BE 逐格裁切；biome sample 起點裁切、ticks／structures 保守以完整 chunk 套用。目標缺少的 chunk 預設保留並標 untracked，explicit commit 才重新追蹤。（2026-10-01） | [05](05-switch-restore.md) §7 |
| 30 | 平台批次／取消／保護 | 共用保守單批 coordinator；預設 8 section／5 ms／24 chunk，由實際 owner／region tick 共享預算。取消等待在途清理與光照／IO barrier 後 PARTIAL；三種傷害保護涵蓋全操作及結束後 10 秒，通知走玩家 owner。（2026-10-01） | [08](08-architecture.md)、[12](12-phase2-progress.md) |
| 31 | hash 的維度組配對 | WorldRepositories 每次 init／commit 對不變維度也記 refs/worldgit/groups/snapshot；hash 優先精確配對。舊歷史按入口 first-parent snapshot 祖先回溯，無法配對就拒絕，建議同步分支。（2026-10-01） | [02](02-data-model.md)、[05](05-switch-restore.md) §7 |
| 32 | 同秒提交時間精度 | 新 commit 以可選 WorldGit-Time trailer 保留 Instant 精度，秒數必須與 git committer 相符；舊 commit 退回 git 整秒時間。避免跨維度分組歷史把同秒的子提交排在初始提交之後。（2026-10-01） | [02](02-data-model.md)、[12](12-phase2-progress.md) |
| 33 | 世界分支與領先／落後 | 同名分支跨維度合併；預設分支取主世界 repo 的 symbolic HEAD（沒有時退回其他維度、main 或第一個分支）。分別揭露「宣告維度都有分支」與「head 同一 snapshot」。ahead／behind 先合併各維度可達 snapshot UUID 集合，再取差集；可指定任意分支作為基準。每維度／tip 最多走 20,000 個 commit，截斷時明示估算，不能當下限。（2026-10-01） | [10](10-web-frontend.md) §7.1、[12](12-phase2-progress.md) Hub |
| 34 | 任意 commit 的跨維度比較語意 | 分支解析為各維度當下 head；commit id／唯一前綴解析為該 commit 與其他分支可達歷史中**同 snapshot UUID**的 commit。不以時間猜測未變動維度當時的 head。未配對維度明示缺少端點、排除於統計，且不視為相同／整個維度被刪除。（2026-10-01） | [10](10-web-frontend.md) §7.1、`hub/README.md` |
| 35 | Hub 比較呈現與回應上限 | Phase 2 提供上色疊圖、只看變動及周圍一格、前／後切換（讀取實際 a／b 的方塊、實體、LOD 與資源，保留鏡頭）。JSON 回應最多 4 MiB；全維度 chunk 清單 2000 項、section 清單 6000 項，統計／範圍保持完整；每維度實體樣本 200、metadata 50。世界最多 32 維度／500 分支；3D 視窗仍最多 1024 chunk、wire 回應最多 16 MiB；沿用 DecodeBudget，超過預算／硬上限回 413。並排、分割滑桿、時間軸留後續。（2026-10-01） | [10](10-web-frontend.md) §7.1、[12](12-phase2-progress.md) Hub |
| 36 | Fabric revision preview | 沿用 protocol v2、原版 command 請求與 diff/status/clear 回應，hello 可選新增 `revision-preview`。方向為目前世界→目標 commit；清除保留跨維度／未完成批次的 id floor。（2026-10-01） | `protocol/README.md`、Fabric README、[12](12-phase2-progress.md) Fabric |
| 37 | Fabric 單人線上切換 | 以 `WorldOperations.live` 共用 core journal／HEAD；整合伺服器全程 freeze tick、攔截玩家編輯與 LevelChunk 方塊寫入，在 server owner 逐批套用並等待 entity IO／光照／送包／flush。全維度 UUID removal barrier 先於任何 spawn。（2026-10-01） | core README、Fabric README、[12](12-phase2-progress.md) Fabric |
| 38 | Fabric 線上預檢限制 | 目前線上不刪除 chunk；stash 若需刪除新增 chunk、或 metadata 需改寫未接上的 saved-data／設定，於全組寫入前拒絕，保留世界與 HEAD，改走離線 CLI。跨 DataVersion／規則不同仍沿用 core 拒絕。（2026-10-01） | Fabric README、[12](12-phase2-progress.md) Fabric |
| 39 | 線上編輯鎖與 tick | Paper／Folia 的世界組操作以事件／WE／FAWE 編輯鎖搭配 vanilla 全伺服器 tick freeze；以引用計數保存與恢復原 freeze／step 狀態，不移動玩家。任意第三方 NMS 寫入須配合 `isEditLocked(world)`。 | `paper/README.md`、[12](12-phase2-progress.md) Paper／Folia |
| 40 | Paper 光照完成 | Paper 兩版的 vanilla `waitForPendingTasks` 是不支援 stub；使用 Starlight 同 chunk queue 的完成回呼，在 ticket 釋放前等光照、刷新 chunk，再完成全組 terrain／entity／POI IO barrier。取消／失敗也清理已寫入部分。 | [12](12-phase2-progress.md) Paper／Folia |
| 41 | UUID 移除掃描與新增 chunk | 先在 flush 後的全維度磁碟實體資料篩出操作 UUID（含 passengers），再 ticket 載入其 owner 移除；與已載入來源共用全維度 remove→spawn barrier。套用期間產生且目標沒有的周邊 chunk，於驗證後補記 untracked。 | `paper/README.md`、[12](12-phase2-progress.md) Paper／Folia |
| 42 | 線上不能安全套用的內容 | 目前 Paper 在寫入前拒絕 chunk 刪除及有差異的 world-meta，改用 CLI 離線還原，避免 holder／saved-data 快取覆蓋；新增地形需刪 chunk 的 stash push／pop 同樣預檢拒絕。跨 DataVersion／規則遷移沿用 #28。 | `paper/README.md`、[12](12-phase2-progress.md) Paper／Folia |
| 43 | Phase 3 merge-base／維度組 | 入口 revision 先按 snapshot group refs 配對，各維度獨立找最佳共同祖先；來源分支缺少維度時保留 ours，無共同歷史以空 tree 作 base。criss-cross 多個最佳 base 明確拒絕，暫不遞迴合成虛擬 base。（2026-10-02） | [06](06-diff-merge.md)、[13](13-phase3-progress.md) |
| 44 | MERGING／abort／commit | 合併、revert、cherry-pick 要求全組乾淨（含 untracked），不自動 stash；世界組 `merge-state.bin`＋各 repo MERGE_HEAD，與 apply journal 共用鎖／驗證 barrier。衝突預設 ours，選擇與 resolved 分開；manual 以目前世界為準。abort 還原原 HEAD、規則及合併前內容；規則不同時另保存原先被排除的內容。成功 merge 全維度共用新 snapshot，不同 tip 兩個 parent，重複 parent 去重；revert／cherry-pick 單 parent。（2026-10-02） | [02](02-data-model.md)、[05](05-switch-restore.md)、[06](06-diff-merge.md) |
| 45 | 衝突分群／語意綁定 | 3D 曼哈頓距離，預設 k=1，可設 0..16；門／大型植物上下半、床頭尾、伸出活塞／活塞頭強制綁定，跨 chunk 同樣成立。只切換精確 atoms，包圍盒僅供顯示；同 UUID 的移動端點強制同區。BE 整個 canonical NBT 原子，實體兩邊都修改即衝突（含相同結果）。（2026-10-02） | [06](06-diff-merge.md)、[13](13-phase3-progress.md) |
| 46 | 鄰居形狀 | **合併結果的每一格都維持來源快照中儲存的方塊 state，離線與線上套用都不重算鄰居形狀、不觸發 updateShape／鄰居更新**（使用者決定，2026-10-02）。快照本來就保存遊戲當時算好的連接狀態；只有兩邊變動直接相鄰、正確組合兩邊都沒保存過時才可能不一致，這種格子由 core 列入報告的 `updateShapes` 提示清單（含跨 chunk 六鄰居），只供使用者檢查，平台不自動處理。完成報告保存在 last-merge-report.bin。紅石提示建議測試，不能保證電路邏輯。 | [06](06-diff-merge.md)、[08](08-architecture.md)、[13](13-phase3-progress.md) |
| 47 | Phase 3 規則合併 | `.wgignore` 用有序文字三方合併，重疊修改拒絕；合併後規則過濾三邊。重新納入內容時 ours 從目前世界 capture，base／theirs 不猜測當時沒有保存的資料。跨 DataVersion 仍依 #28 拒絕；DataPacks 差異仍預檢拒絕。Phase 2 switch／restore 的規則限制保持。（2026-10-02） | [02](02-data-model.md)、[05](05-switch-restore.md)、[06](06-diff-merge.md) |
| 48 | 合併 protocol 相容性 | 既有 v2 hello／diff／status／clear 不變；新增可選 worldgit:conflicts／worldgit:conflict_preview channel，各自 v1 envelope。只對宣告 merge-regions-v1 的 peer 發送，舊客戶端忽略未註冊 channel；最多 28,000 bytes／包、8 MiB／批、100,000 entries，含 BE 的預覽收齊才發布。（2026-10-02） | protocol README、[08](08-architecture.md)、[13](13-phase3-progress.md) |
| 49 | patch 與歷史語意 | revert：base=commit、theirs=parent；cherry-pick：base=parent、theirs=commit。snapshot 中未產生新 commit 的維度為零 patch；merge commit 的 patch 因缺少 mainline 選項先拒絕。不採 fast-forward：正常 merge 建立新整合快照，--no-commit 可先檢查；--strategy-option 只選衝突、保留自動合併內容。（2026-10-02） | [05](05-switch-restore.md)、[06](06-diff-merge.md)、[13](13-phase3-progress.md) |


## 路線圖（四端並行）

每個階段都是一條**貫穿四端的垂直切片**：同一個功能在 CLI、插件、模組、網頁上同時完成，而不是一端做完再做下一端。core 是四端共用的基礎，永遠領先一步。

| 階段 | core | CLI | Paper 插件 | Fabric 模組 | 網頁 Hub |
|---|---|---|---|---|---|
| **Phase 0：技術驗證** | Anvil 讀取、正規化（方塊 + 生物）、section 雜湊、JGit 映射原型；1.21.11 與 26.2 存檔差異盤點 | 量測工具 | 線上 chunk 快照與 section 替換的 PoC（兩個版本，Paper 與 Folia） | 客戶端鬼影渲染 PoC；插件 ↔ 模組握手 PoC | 新的 3D 檢視器原型：從 JGit repo 讀出一個 commit 並顯示 |
| **Phase 1：存檔點與檢視** | commit、log、tree diff、方塊/實體 diff | `init` `status` `commit` `log` `diff` | `/wg commit` `status` `log`、自動 commit（登出/定時）、作者歸屬 | 單人世界的 commit/log；連上插件時顯示 status/diff 描邊 | repo 首頁、commit 列表、單一 commit 的 3D 檢視與 diff 上色 |
| **Phase 2：復原與切換** | 套用 patch、stash、DataFixer 升級流程 | `restore` `switch` `branch` `reset` | `/wg restore`（選取範圍）、原地 `switch`、玩家安全處理 | `/wg preview` 客戶端鬼影預覽另一個 commit；單人世界 switch/restore | 分支瀏覽、兩個 commit 的比較檢視 |
| **Phase 3：合併** | 三方合併、衝突分群、生物 UUID 合併、合併後鄰居形狀修正 | `merge` `merge --abort` `revert` `cherry-pick` | 合併中狀態、合併工具（ours/theirs/base 原地切換）、衝突 GUI | 衝突清單 UI、ours/theirs 疊圖預覽 | 衝突區域檢視與選擇 |
| **Phase 4：遠端協作** | push/pull 流程、權限 | `push` `pull` `clone`（產出可直接開的世界） | 遊戲內 `/wg push/pull/pr` | 同左（單人） | 帳號與權限、PR、座標留言、release 下載 |
| **之後** | 自製儲存後端（視量測）、sparse clone | | worktree 世界（Folia 除外）、更多版本 | 更多版本 | blame、hooks、俯視地圖 tile |

### 各階段的驗收標準

- **Phase 0**：同一世界存兩次（沒人動）→ 方塊雜湊 100% 相同；生物在容許範圍內走動 → 不算變動；放一格方塊 → 恰好 1 個 section 變化；拿真實伺服器世界量測 repo 大小與耗時。
- **Phase 1**：四端看到的是同一份歷史：插件 commit → CLI `log` 看得到 → 網頁上看得到 3D 差異 → 裝了模組的玩家在遊戲內看到同樣的描邊。
- **Phase 2**：有玩家在線時 switch 不崩潰、不留下鬼影方塊、生物不重複；TPS 影響可接受；光照正確重算。
- **Phase 3**：兩人在不同位置建築 → 零介入合併；同位置 → 衝突區域正確、可切換，`merge --abort` 可完整退回。
- **Phase 4**：單人玩家 clone 後可直接開世界；網頁上合併的 PR，伺服器 `pull` 後內容一致。

## 待討論的決策

目前沒有。

（A「大世界放在 GitHub 等一般 git 託管的方式」已於 2026-10-01 決定：① 切 pack、② 大世界自架、③ 改為「每個維度一個 repo」、④ 改為可設定且預設關閉，見決定 #17–#19。）

## Phase 0 要驗證的技術風險

- [x] 各種方塊/block entity 的正規化是否穩定（箱子、告示牌、生怪磚、講台上的書）（見 `experiments/02-core-proto/REPORT.md`）：兩版重寫 697 個 chunk 後假 diff 為 0
- [x] 生物正規化：哪些欄位要忽略、容許距離設多少才不會有雜訊又不會漏掉真正的變動（見 `experiments/02-core-proto/REPORT.md`）：黏性 2 格，忽略清單見報告 §1.4；長時間漂移尚未測
- [x] 預設全部追蹤（含掉落物、自然刷怪）時，一般生存伺服器的 `status` 雜訊量與每次 commit 的大小（見 `experiments/06-survival-scale/REPORT.md`）
- [x] 追蹤所有已生成 chunk 時，大型伺服器（數萬 chunk）的初次 `init` 大小與耗時（見 `experiments/06-survival-scale/REPORT.md`）：2 萬 chunk 112 秒、110 MB
- [x] 刪除光照資料後寫回，1.21.11 與 26.2 是否都能正確重算（見 `experiments/02-core-proto/REPORT.md`）：Paper 兩版皆與原本相同；Fabric/原版伺服器未測
- [x] POI 資料丟棄後是否會被正確重建（見 `experiments/02-core-proto/REPORT.md`）：寫回時必須刪除 POI 檔中的對應紀錄才會重建
- [x] 在 Paper 上替換已載入 chunk 的 section 後，客戶端更新與實體同步的正確做法（兩個版本）（見 `experiments/03-paper-poc/REPORT.md`）：四平台成功；section 內實體與客戶端光照尚未驗證
- [x] 1.21.11 與 26.2 的存檔目錄結構與 chunk/實體 NBT 差異（見 `experiments/00-env/REPORT.md`）
- [~] 大世界的 init 耗時與 repo 大小；在 GitHub/Gitea 上 push/clone 的實際表現（本機 smart HTTP 已測；Gitea、真實網路、GitHub 未測；託管方式見決定 #17）
- [x] chunk unsaved 旗標 + region 時間戳能否抓到所有變動（含 FAWE），以及誤報量（見 `experiments/03-paper-poc/REPORT.md`）：方塊/BE 成立，需讀原始欄位、處理同秒碰撞；實體沒有旗標，另行處理
- [x] FAWE 的 `EditSessionEvent` 在各種模式下是否都能看到逐格變動（見 `experiments/03-paper-poc/REPORT.md`）：bulk 模式看不到逐格，只有 Region 與每 chunk 寫入數；需設 `extent.allowed-plugins`；`//regen`、筆刷未測
- [x] **前端實驗 A**（見 `experiments/04-deepslate/REPORT.md`）：用 deepslate 渲染 WorldGit 正規化後的一個 section（含多種非完整方塊），評估正確性、效能與介接成本
- [x] **前端實驗 B**（見 `experiments/01-bluemap/REPORT.md`）：評估 BlueMap 核心能否以 WorldGit 的 tree 為輸入產生 tile（不經過實際的世界資料夾）
- [x] Fabric 客戶端鬼影 diff 顯示與插件↔模組握手，1.21.11 與 26.2（見 `experiments/05-fabric-poc/REPORT.md`）：兩版真正客戶端截圖驗證；大量格數需分塊裁切與包圍盒
- [x] 新 3D 檢視器在大範圍（數千 chunk）下的效能：網格生成速度、記憶體、瀏覽器端幀率（見 `experiments/07-viewer-scale/REPORT.md`）：完整細節只能做相機附近，其餘 LOD + 串流；幾何可壓到 50 MB 以下，整體工作集約 128–160 MiB；真 GPU 幀率未測
- [x] Folia 在 1.21.11 / 26.2 的釋出狀態，以及 region 排程下 switch 大量 chunk 的效能（見 `experiments/03-paper-poc/`、`experiments/08-folia-switch/REPORT.md`）：1,008 chunk 的 A → B → A 三平台全部正確；Folia 約 Paper 的 2.9 倍；預設每 region 每 tick 8 section / 5 ms

## Phase 1 第一個實作任務（2026-10-01）

已建立 Java 21 的 core、platform-api、protocol、CLI 正式 monorepo；Paper/Fabric/Hub 留待接續任務，因此「四端同一份歷史」的整體驗收尚未完成。共用 API、測試與量測見 [11 — Phase 1 進度](11-phase1-progress.md)。`modified-only` 本次只記錄設定，功能尚未生效；其餘限制逐項列在報告，不視為已完成。

真實 Paper 的地獄重寫新增發現：structures `References` 的 long[] 是集合，LongSet 序列化順序會交替改變；正式正規化已補數值排序，並新增回歸測試。這是 #18 每維度 repo 的正式驗證發現，不變更追蹤範圍決策。
