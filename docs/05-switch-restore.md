# 05 — 切換時間點、局部還原、worktree

原始筆記的痛點：「按照這個方法開世界，世界會越來越多而且很龐雜；在自己世界下切換會簡單很多。」

## 核心原則：一個 repo 只有一個活的世界

- 分支、commit 都**不是**世界資料夾，只是歷史裡的指標。
- `switch` = 把活的世界原地改成目標快照。只有需要「同時存在兩個版本」時才開 worktree 世界，而且由系統託管、自動回收。

## 1. switch / checkout 的流程

```
前置：工作區乾淨？ ── 否 ──> 提示 commit / 自動 stash / --force 丟棄
  │是
  ▼
計算 HEAD tree 與 target tree 的差異 → 只得到「需要改寫的 section 清單」
  │ （兩個相近的分支通常只差幾十個 chunk，所以切換很快）
  ▼
鎖定世界的編輯（事件層攔截放置/破壞，或把世界設為唯讀/冒險模式）
  ▼
逐 chunk 套用：
   已載入 → 在主執行緒（Folia：region 執行緒）直接替換 section 內容、
            刪除/生成 block entity 與被追蹤實體，送出 chunk 更新封包給附近玩家
   未載入 → 透過伺服器的 chunk IO 讀出 → 替換 → 寫回（不直接碰 .mca）
  ▼
光照重算（標記受影響 section 的光照為過期）
  ▼
移動 HEAD、解鎖、廣播「已切換到 castle-v2 @ a1b2c3」
```

### 需要特別處理的情況

| 情況 | 處理 |
|---|---|
| 玩家站在會變成實心方塊的位置 | 切換後把玩家往上移到安全位置（或切換前傳送到出生點） |
| 目標快照中不存在的 chunk（分支上沒生成過） | 兩種策略：刪除該 chunk（讓遊戲重新生成） / 保留現狀（標記為 untracked）——預設保留 |
| 大量變動（數千 chunk） | 分批、每 tick 限額，顯示進度條（bossbar）；可選「切換期間踢出玩家」模式 |
| 切換到 MC 舊版本的 commit | 先經過 DataFixer 升級再套用 |
| 活塞正在推、水正在流、紅石時鐘 | 切換期間暫停該世界的 tick（`/tick freeze` 類似機制），套用完再恢復 |

## 2. restore：局部還原（最常用的「復原」）

```
/wg restore <commit|branch> --selection      # 用 WorldEdit 選取範圍
/wg restore HEAD~3 --chunks here 3            # 以自己為中心 3 chunk 半徑
```

- 流程同 switch，但只套用範圍內的 section；範圍邊界落在 section 中間時做方塊級裁切。
- **不移動 HEAD**，所以結果是「未存檔的變動」，玩家看過滿意再 commit。
- 這是給單人最直覺的「悔棋」：不用理解分支就能用。

## 3. reset --hard 與 revert

- `reset --hard`：= `restore HEAD` 全範圍（丟掉未存檔變動）。若加上 commit 參數則會移動分支指標（改寫歷史），僅限管理員且不可對已 push 的歷史做。
- `revert <commit>`：計算該 commit 的反向 diff，對目前世界做三方合併；乾淨就直接生成新 commit，有衝突走 [06](06-diff-merge.md) 的流程。多人伺服器上推薦用 revert 而不是 reset。

## 4. worktree：真的需要兩個世界同時存在時

使用情境：
- 想**並排比較**兩個版本（在 A 世界看一眼，/wg tp 到 B 世界的同座標看一眼）
- 多人伺服器上，**不同人同時在不同分支上工作**（因為一個活世界同時只能站在一個分支上）

設計：
- 名稱固定為 `<world>@<branch>`，由插件用自己的方式建立/載入（不需要 Multiverse）
- 不出現在一般世界列表；`/wg worktree list` 可見
- 分支被合併或刪除時提示回收；閒置 N 天自動卸載（資料仍在 repo，隨時可重建）
- worktree 世界也是 working tree：在裡面 commit 就是 commit 到那個分支
- 建立方式：不用複製整個世界資料夾，而是從 repo 物件「長出」一個世界（只寫入被追蹤的 chunk；未追蹤的地形用相同種子生成）

## 5. 唯讀預覽（不改變任何世界）

`/wg preview <commit>`：在目前世界中，對自己**一個人**顯示該 commit 與現況的差異方塊（客戶端假方塊封包 / display entity 鬼影），不改變伺服器上的任何東西。

- 插件端：用假方塊封包（只有該玩家看得到），或半透明 display entity 描出「會出現/會消失」的方塊。
- 模組端：客戶端直接渲染半透明鬼影方塊，效果最好。

這可以取代很多「想看一眼舊版本長怎樣」而去開 worktree 的需求。
