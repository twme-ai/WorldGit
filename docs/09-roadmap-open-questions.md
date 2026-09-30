# 09 — 路線圖、已決定事項與待討論決策

## 已決定（2026-09-30）

| # | 議題 | 決定 | 影響文件 |
|---|---|---|---|
| 1 | 儲存後端 | **JGit**，core 內保留 `ObjectStore` / `RefStore` 介面，之後視量測結果再評估 | [03](03-storage-backend.md) |
| 2 | 追蹤範圍 | **除了玩家以外全部預設追蹤**：所有生物（含自然刷出的怪物）、掉落物/經驗球/投射物等暫態實體、所有已生成的 chunk（下載即得完整世界）。生物移動的雜訊以正規化、UUID 全域比對、容許距離處理 | [02](02-data-model.md) §4、§7、§8 |
| 2a | 排除規則 | **`.wgignore`**：類似 `.gitignore`、放在 repo 內被版本控制；可依維度、座標範圍、實體類型、NBT 欄位排除，支援 `!` 加回 | [02](02-data-model.md) §5 |
| 3 | 平台優先順序 | **插件、模組、CLI、網頁四端同等優先**；主要使用者是多人伺服器；Fabric 模組除了單人世界，也要作為 Paper 伺服器玩家的客戶端顯示端 | [08](08-architecture.md) §2、§6 |
| 4 | 支援範圍 | 長期越廣越好；**首發 Paper 與 Fabric 的 1.21.11 與 26.2** | [08](08-architecture.md) §5 |
| 5 | 網頁端 | 類 GitHub 的網頁檢視端是正式目標（原始筆記中「用不到」為筆誤） | [07](07-remote-hub.md) |
| 6 | Hub 部署 | **自架與公開服務都要**，同一套程式碼、多租戶設計 | [07](07-remote-hub.md) §4 |

## 路線圖（四端並行）

每個階段都是一條**貫穿四端的垂直切片**：同一個功能在 CLI、插件、模組、網頁上同時完成，而不是一端做完再做下一端。core 是四端共用的基礎，永遠領先一步。

| 階段 | core | CLI | Paper 插件 | Fabric 模組 | 網頁 Hub |
|---|---|---|---|---|---|
| **Phase 0：技術驗證** | Anvil 讀取、正規化（方塊 + 生物）、section 雜湊、JGit 映射原型；1.21.11 與 26.2 存檔差異盤點 | 量測工具 | 線上 chunk 快照與 section 替換的 PoC（兩個版本） | 客戶端鬼影渲染 PoC；插件 ↔ 模組握手 PoC | 從 JGit repo 讀出一個 commit，用 BlockForge 渲染器顯示 |
| **Phase 1：存檔點與檢視** | commit、log、tree diff、方塊/實體 diff | `init` `status` `commit` `log` `diff` | `/wg commit` `status` `log`、自動 commit（登出/定時）、作者歸屬 | 單人世界的 commit/log；連上插件時顯示 status/diff 描邊 | repo 首頁、commit 列表、單一 commit 的 3D 檢視與 diff 上色 |
| **Phase 2：復原與切換** | 套用 patch、stash、DataFixer 升級流程 | `restore` `switch` `branch` `reset` | `/wg restore`（選取範圍）、原地 `switch`、玩家安全處理 | `/wg preview` 客戶端鬼影預覽另一個 commit；單人世界 switch/restore | 分支瀏覽、兩個 commit 的比較檢視 |
| **Phase 3：合併** | 三方合併、衝突分群、生物 UUID 合併、合併後鄰居形狀修正 | `merge` `merge --abort` `revert` `cherry-pick` | 合併中狀態、合併工具（ours/theirs/base 原地切換）、衝突 GUI | 衝突清單 UI、ours/theirs 疊圖預覽 | 衝突區域檢視與選擇 |
| **Phase 4：遠端協作** | push/pull 流程、權限 | `push` `pull` `clone`（產出可直接開的世界） | 遊戲內 `/wg push/pull/pr` | 同左（單人） | 帳號與權限、PR、座標留言、release 下載 |
| **之後** | 自製儲存後端（視量測）、sparse clone | | worktree 世界、Folia、更多版本 | 更多版本 | blame、hooks、俯視地圖 tile |

### 各階段的驗收標準

- **Phase 0**：同一世界存兩次（沒人動）→ 方塊雜湊 100% 相同；生物在容許範圍內走動 → 不算變動；放一格方塊 → 恰好 1 個 section 變化；拿真實伺服器世界量測 repo 大小與耗時。
- **Phase 1**：四端看到的是同一份歷史：插件 commit → CLI `log` 看得到 → 網頁上看得到 3D 差異 → 裝了模組的玩家在遊戲內看到同樣的描邊。
- **Phase 2**：有玩家在線時 switch 不崩潰、不留下鬼影方塊、生物不重複；TPS 影響可接受；光照正確重算。
- **Phase 3**：兩人在不同位置建築 → 零介入合併；同位置 → 衝突區域正確、可切換，`merge --abort` 可完整退回。
- **Phase 4**：單人玩家 clone 後可直接開世界；網頁上合併的 PR，伺服器 `pull` 後內容一致。

## 待討論的決策

1. **切換時的安全策略**：預設「原地切換 + 把卡在方塊裡的玩家移出」，還是「切換時把世界內的玩家傳出去」？
2. **Hub 技術棧**：後端 Kotlin/Ktor（直接重用 core），前端沿用 BlockForge 的渲染器——同意嗎？
3. **與 CoreProtect 的關係**：要不要讀 CoreProtect 資料來做更精細的作者歸屬 / blame？
4. **Folia**：列在「之後」，還是首發就要？
5. **專案名稱**：`WorldGit` 只是工作名稱。

## Phase 0 要驗證的技術風險

- [ ] 各種方塊/block entity 的正規化是否穩定（箱子、告示牌、生怪磚、講台上的書）
- [ ] 生物正規化：哪些欄位要忽略、容許距離設多少才不會有雜訊又不會漏掉真正的變動
- [ ] 預設全部追蹤（含掉落物、自然刷怪）時，一般生存伺服器的 `status` 雜訊量與每次 commit 的大小
- [ ] 追蹤所有已生成 chunk 時，大型伺服器（數萬 chunk）的初次 `init` 大小與耗時
- [ ] 刪除光照資料後寫回，1.21.11 與 26.2 是否都能正確重算
- [ ] POI 資料丟棄後是否會被正確重建
- [ ] 在 Paper 上替換已載入 chunk 的 section 後，客戶端更新與實體同步的正確做法（兩個版本）
- [ ] 1.21.11 與 26.2 的存檔目錄結構與 chunk/實體 NBT 差異
- [ ] 大世界的 init 耗時與 repo 大小；在 GitHub/Gitea 上 push/clone 的實際表現
- [ ] BlockForge 渲染器對 1.21.11 資源的支援程度
