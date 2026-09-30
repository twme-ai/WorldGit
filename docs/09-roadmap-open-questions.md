# 09 — 路線圖與待討論決策

## 路線圖（草案）

| 階段 | 目標 | 交付物 | 驗收標準 |
|---|---|---|---|
| **Phase 0：技術驗證** | 證明「正規化 + section 雜湊」可行 | core 的 anvil 讀取 + 正規化 + 雜湊原型；量測腳本 | 同一世界存檔兩次（沒人動）→ 雜湊 100% 相同；放一格方塊 → 恰好 1 個 section 變化；拿真實世界量測 repo 大小/耗時 |
| **Phase 1：離線 CLI** | 對關閉的世界做完整版本控制 | `wgit init/status/commit/log/diff/switch/restore/branch` | 任意切換分支後，用 MC 開啟世界內容正確；光照正確重算 |
| **Phase 2：Paper 插件（單人流程）** | 線上 commit、局部還原、原地切換 | `/wg` 指令、自動 commit、status 描邊、restore | 有玩家在線時 switch 不崩潰、不留下鬼方塊；TPS 影響可接受 |
| **Phase 3：合併** | 三方合併與衝突解決 | merge / merge --abort / 衝突區域 / 合併工具 / revert / cherry-pick | 兩人不同位置建築 → 零介入合併；同位置 → 衝突區域正確、可切換、abort 可完整退回 |
| **Phase 4：遠端與 Hub** | push/pull/clone、網頁檢視、PR | Hub MVP（commit 列表、3D diff、PR 合併、release 下載） | 單人玩家 clone 後可直接開世界 |
| **Phase 5：Fabric 模組** | 單人玩家與客戶端預覽 | 共用 core 的模組 + 鬼影渲染 | |
| 之後 | worktree、sparse clone、blame、hooks、自製儲存後端 | | |

說明：CLI 放第一是因為離線世界最好測試，core 的正確性在這裡驗證完，之後插件/模組只需處理「活的世界」這層。

## 待你決定的問題

1. **儲存後端**：同意先用 JGit（真 git），再視量測結果決定要不要自製？（[03](03-storage-backend.md)）
2. **預設追蹤範圍**：方塊 + block entity + 靜態實體，不追蹤生物與玩家資料——同意嗎？自然生成的地形要不要預設排除（只追蹤被玩家改過的 chunk）？（[02](02-data-model.md) §4、§6）
3. **優先平台**：Paper 插件先、Fabric 模組後，還是反過來？主要使用者是多人伺服器還是單人建築師？
4. **Folia 支援**：第一版就要支援，還是之後再說？（影響執行緒模型設計）
5. **切換時的安全策略**：預設「就地切換 + 移動卡住的玩家」，還是「切換時把世界內玩家傳出去」？
6. **Hub**：自架（開源給伺服器自己跑）、做成公開服務、還是兩者都要？初期能否直接用 GitHub/Gitea 當儲存？
7. **與 CoreProtect 的關係**：要不要讀 CoreProtect 資料來做更精細的作者歸屬 / blame？
8. **MC 版本範圍**：只支援 26.x，還是要涵蓋 1.20/1.21？
9. **專案名稱**：`WorldGit` 只是工作名稱。

## Phase 0 要驗證的技術風險

- [ ] 各種方塊/block entity 的正規化是否真的穩定（特別是箱子、告示牌、生怪磚、講台上的書）
- [ ] 刪除光照資料後寫回，MC 26.x 是否正確重算（`isLightOn=false` 的行為）
- [ ] POI 資料丟棄後是否會被正確重建
- [ ] 在 Paper 上替換已載入 chunk 的 section 後，客戶端更新與實體同步的正確做法
- [ ] 大世界的 init 耗時與 repo 大小；在 GitHub 上 push/clone 的實際表現
- [ ] 不同 MC 版本世界的 DataFixer 升級路徑能否在 core 之外（伺服器端）完成
