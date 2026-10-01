# 真實世界的小型 fixture

來源是本機 `.work/worlds/{1.21.11,26.2}/baseline` 的唯讀擷取，不需要下載遊戲或啟動伺服器。每版三個維度各取三個 full chunk：主世界包含 `(0,0)`、`(1,1)` 與一個負座標 chunk，另保留有資料的 entity region 與必要世界 metadata。

兩版世界 fixture 合計 214,677 bytes（約 210 KiB），低於 2 MB。region 是重新建立的小檔，不含原世界的其他 chunk；另保留 baseline 的極小 bukkit datapack metadata，供離線 tag loader 解析啟用清單。1.21.11 保留 Paper 三資料夾與 DIM-1/DIM1，26.2 保留 `dimensions/minecraft/<dimension>/`。

開發時可用 `./gradlew :core:extractFixtures` 從本機 baseline 重新擷取；CI 只讀已提交的資料，不執行擷取。此 task 只替換這兩個版本的 fixture 子目錄，原 baseline 不會被修改。
