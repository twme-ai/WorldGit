# 07：大範圍網頁 3D 檢視器

以 04 的 Vite／TypeScript 原型複製擴充；保留其 `world.ts`、`resources.ts` 與模型快取 mesher（`base-mesher.ts`），新增 greedy meshing、16 B 頂點（先做 24 B，後以矩形索引壓到 16 B；對照見 REPORT 4.1）、worker 池、視錐裁切、高度圖 LOD、GPU 預算與相機串流。04 本身沒有修改。結論與量測口徑見 [REPORT.md](REPORT.md)，原始結果為 [bench-results.json](bench-results.json)。

## 前置條件與一鍵重跑

需要 Node 24、`flock`、`/usr/bin/google-chrome`（`CHROME` 可覆寫）、既有 `.work/worlds/26.2/baseline`。資源使用 04 已準備的 `.work/assets/26.2/pack`；沒有 pack 時，腳本使用 `.work/assets/26.2/assets` 重新處理，輸出只寫 `.work/viewer-scale/pack`。資源下載與 SHA1 驗證方式沿用 04 README；不把 Mojang 原檔加入實驗目錄。

```bash
cd /root/projects/ProjectCollection/WorldGit
bash experiments/07-viewer-scale/scripts/run.sh
```

腳本複製 04 的已安裝依賴到 `.work/viewer-scale/node_modules`，沒有現成依賴時才 `npm ci`。快取環境使用專案的 `.work/npm-cache`、`.work/ms-playwright`；Chrome profile／暫存與本實驗產物使用 `.work/viewer-scale`。不需要下載 Playwright browser，也不使用 Xvfb。

流程：型別檢查 → **持全域鎖**轉換資料 → 幾何／codec 檢查 → production build → **持全域鎖**完整瀏覽器網格／串流／飛越／截圖／GL 後端量測。等待其他實驗釋鎖時沒有時間上限。所有瀏覽器量測都在同一把鎖內；完成或例外時關閉 Chromium 與 5187 的 Vite preview。每個量測完成就更新 JSON，便於追查失敗。

## 手動操作

```bash
cd experiments/07-viewer-scale
# 首次先執行 run.sh 或單獨準備資料。
node_modules/.bin/vite --host 127.0.0.1 --port 5187 --strictPort
```

開啟 `http://127.0.0.1:5187/?dataset=10000&workers=2`。可拖曳平移、WASD 移動、滾輪改高度，按鈕切換近景、遠景、diff 與包圍盒。

| URL 參數 | 預設／選項 |
|---|---|
| `dataset` | `10000`；可選 `baseline`、`2000`、`5000`、`10000`、`window256` |
| `workers` | 2；基準測試 1／2／3 |
| `full` | 128；相機附近完整細節 chunk 上限 |
| `budget` | 96 MiB；GPU 頂點／索引／atlas／估算 framebuffer 合計預算 |
| `mode=bench` | 只初始化，透過 `window.__api.allMesh()` 生成全範圍網格並累計，逐批丟棄 |
| `animate=0` | 關閉自動 RAF；Playwright 基準用，仍可用 API 移動與繪製 |
| `w`、`h` | 畫布 1280×644；頁面為 1280×720 |

`window256` 固定顯示真實 baseline 的 chunk `[-8,7]×[-8,7]` 全部完整 section，不套用完整細節 chunk 上限；供驗證 50 MB 目標。其餘資料集保留全範圍 LOD，完整 mesh 隨相機位置置換。`budget` 是明確可計算的 GPU 配置預算；完整瀏覽器記憶體另包含 JS heap、worker 與 ArrayBuffer，請看報告。

16 B 對照重跑（結果檔與截圖另存，不覆蓋主結果）：

```bash
W=$PWD/../../.work   # 必須是絕對路徑，Chrome 的 singleton socket 路徑過長會失敗
flock $W/bench.lock env BENCH_LOCK_HELD=1 TMPDIR=$W/viewer-scale \
  OUT=bench-results-16b.json SHOT=screenshots-16b VB=16 \
  DATASETS=baseline,2000 WORKERS=1,3 DISPLAYS=window256,10000 node scripts/bench-browser.mjs
python3 scripts/report.py   # 重新產生 REPORT.md
```

注意：目前 `src/` 已是 16 B 版，`run.sh` 完整重跑會得到 16 B 全矩陣（寫入 `bench-results.json`）；`bench-results.json` 是 24 B 版本的完整結果，`report.py` 的 24 B 欄位依賴它，重跑前先備份。

單獨量測（先 build；腳本自行啟動 production preview，不要同時留著 dev server）：

```bash
node_modules/.bin/vite build
flock ../../.work/bench.lock env BENCH_LOCK_HELD=1 \
  TMPDIR="$PWD/../../.work/viewer-scale" \
  node scripts/bench-browser.mjs
```

加 `SMOKE=1` 只跑 626 chunk／1 worker 和 256 chunk 顯示驗證；會覆蓋結果與截圖，因此正式結果請先備份。個別重跑可用 `DATASETS=baseline,2000,5000,10000`、`WORKERS=1,2,3`、`DISPLAYS=window256,baseline,2000,5000,10000` 選擇組合。

## 程式與產物

| 路徑 | 內容 |
|---|---|
| `scripts/prepare.ts` | 讀取 26.2 overworld Anvil 的全部 full chunk，轉換、建立平鋪索引、產生高度圖與平均貼圖顏色 |
| `src/codec.ts` | 02 section v1 的未壓縮位元布局、BE 正規化、chunk 封套；64 格 biome sidecar |
| `src/scale-mesher.ts` | 驗證完整立方體的六面模型，依 state／方向／biome 合併同材質面，包含鄰接 chunk／section 剔除；圖集 alpha 判定 state 遮擋（含 double slab） |
| `src/packed.ts` | 16 B／頂點：fixed point 位置、UV、atlas 矩形索引（`Rects` 表，部分矩形取最小包含矩形）、色彩、light/kind/repeat flags；主執行緒不載入 deepslate。24 B 舊版原始碼備份在 `.work/viewer-scale/src-24b-backup/`（不進 Git） |
| `src/worker.ts`、`src/pool.ts` | 1–3 worker 池，來源與解碼資料各 64 chunk 上限，每批只有需求 chunk 與四鄰居 |
| `src/lod.ts`、`src/viewer.ts`、`src/main.ts` | 4×4 高度圖、WebGL2 AABB 視錐裁切、按距離置換、GPU 釋放、合成 diff 示範 |
| `scripts/texture-metadata.mjs` | 從圖集 alpha 產生完整不透明貼圖 UV 表，避免沿用 block-name 旗標誤判 double slab |
| `scripts/rect-debug.ts` | 診斷：網格化前 60 chunk，印出 atlas 矩形表的部分矩形數與未解析數 |
| `scripts/report.py` | 由 `bench-results.json`（24 B 完整矩陣）與 `bench-results-16b.json`（16 B 部分重跑）產生 `REPORT.md`；兩個 JSON 都完成才能執行 |
| `scripts/check.ts` | 實心跨 section 剔除、半磚 fallback、626 chunk 位元組精確 round-trip |
| `scripts/bench-browser.mjs` | Playwright；CDP 量 main／各 worker heap 與 backing storage；同步 GPU 幀時間、相機飛越、真實頁面 PNG |
| `.work/viewer-scale/` | pack、626 個 chunk blob、dataset 索引、tops、私有依賴、build、log、Chrome 暫存（不進 Git） |
| `screenshots/`、`screenshots-16b/` | 各四張 Playwright `page.screenshot()` 截圖（24 B／16 B 版）；不是生成圖 |
| `bench-results.json`、`bench-results-16b.json` | 24 B 完整矩陣／16 B 部分重跑的原始結果 |

合成資料僅平移，不鏡射；每 626 chunk 循環一次，25×27 chunk tile，橫向排四個 tile；末 tile 截到指定數量。沒有生成新的地形。diff 是明確標示的合成 fixture，測顏色、鬼影、不同線型與大範圍包圍盒成本，沒有宣稱從兩個真實 commit 算出差異。
