# WorldGit

> 把 git 的「平行時空」模型搬進 Minecraft 世界：存檔點（commit）、分支（branch）、切換（switch）、合併（merge）、雲端同步（push / pull）。

**目前狀態：設計討論階段，尚未實作任何程式碼。** 本 repo 只放思考成果與規劃，所有結論都還可以推翻。

## 要解決的三個核心問題

| # | 玩家的痛點 | git 對應 | 本專案的解法（摘要） |
|---|---|---|---|
| 1 | 蓋爛了想回去，但備份一堆世界資料夾，越開越亂 | `switch` / `checkout` / `restore` | **只有一個「活的世界」**（working tree），切換分支是「原地換掉有差異的區塊」，不是再開一個世界。需要並排比較時才開系統託管的暫時世界（worktree），用完自動回收。 |
| 2 | 兩個人各自蓋，合併很痛苦、合併後又難復原 | `merge` / `diff` | 以 **chunk section（16×16×16）為單位做三方合併**，只有兩邊都改到同一格方塊才算衝突；衝突以「區域」為單位呈現，在遊戲內可一鍵切換「主線 / 分支 / 原本 / 自己改」，網頁端有 3D 預覽。合併本身是一個 commit，隨時可退。 |
| 3 | 想要明確的存檔點 | `commit` | 手動 `/wg commit`、登出自動 commit、定時 commit。**只存有變動的 section**（內容定址 + 去重），所以每次 commit 都很便宜，不是整個世界複製一份。 |
| 4 | 換電腦 / 給別人接手 | `push` / `pull` / `clone` | 網頁端（類 GitHub 的 Hub）＋ 可部分 clone（只拉某個區域）。 |

## 已決定的方向（2026-09-30）

- 儲存後端用 **JGit**（真正的 git 物件庫）
- **除了玩家以外全部預設追蹤**（所有生物、掉落物等實體、所有已生成的地形），不想追蹤的用 `.wgignore` 排除
- **Paper 插件、Fabric 模組、CLI、網頁 Hub 四端同等優先**，主要使用者是多人伺服器；Fabric 模組同時是 Paper 伺服器玩家的客戶端顯示端
- 首發支援 **Paper / Fabric 的 1.21.11 與 26.2**，長期越廣越好
- 網頁 Hub **自架與公開服務都要**；後端 Java，前端全新撰寫
- **Folia 首發就支援**
- 切換分支時**不移動玩家**，只給短暫的傷害保護

詳見 [docs/09-roadmap-open-questions.md](docs/09-roadmap-open-questions.md)。

## 文件索引

| 文件 | 內容 |
|---|---|
| [docs/00-original-notes.md](docs/00-original-notes.md) | 原始構想筆記（原文） |
| [docs/01-concept-mapping.md](docs/01-concept-mapping.md) | **每個 git 行為在 Minecraft 裡代表什麼**（完整對照表） |
| [docs/02-data-model.md](docs/02-data-model.md) | 世界要怎麼變成 git 物件：粒度、正規化、雜湊、哪些資料要追蹤 |
| [docs/03-storage-backend.md](docs/03-storage-backend.md) | 儲存後端：直接用 JGit（真的 git）還是自製物件庫 |
| [docs/04-commit-and-status.md](docs/04-commit-and-status.md) | commit / status / 自動存檔點 / 變更追蹤 |
| [docs/05-switch-restore.md](docs/05-switch-restore.md) | 在「活的世界」上切換時間點、局部還原、worktree |
| [docs/06-diff-merge.md](docs/06-diff-merge.md) | diff 與三方合併、衝突區域、遊戲內與網頁的衝突解決介面 |
| [docs/07-remote-hub.md](docs/07-remote-hub.md) | push / pull / clone 與網頁端 Hub（PR、預覽、下載） |
| [docs/08-architecture.md](docs/08-architecture.md) | 四個端（core / 插件 / 模組 / CLI / 網頁）的切分與技術選型 |
| [docs/09-roadmap-open-questions.md](docs/09-roadmap-open-questions.md) | 分階段路線圖與**待討論的決策清單** |
| [docs/10-web-frontend.md](docs/10-web-frontend.md) | 網頁前端（全新撰寫）與 3D 世界檢視器的設計 |

## 一句話架構

```
                ┌──────────── worldgit-core (純 JVM，無 MC 依賴) ────────────┐
                │ Anvil/NBT 解析 · 正規化 · 物件庫 · diff · 三方合併 · 傳輸協定 │
                └───────┬───────────────┬───────────────┬───────────────┬──────┘
                        │               │               │               │
                 Paper 插件  ◀───▶ Fabric 模組        CLI (wgit)      Hub 伺服器
               (多人伺服器、線上    (客戶端鬼影預覽/    (離線世界、      (網頁、PR、
                commit/切換/合併)   單人存檔完整功能)   腳本/CI)        3D diff 檢視)
```
