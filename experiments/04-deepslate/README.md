# 04-deepslate：用 deepslate 渲染測試世界的真實區塊（Phase 0 前端實驗 A）

結論與數據見 [REPORT.md](REPORT.md)。這個目錄是一個 Vite + TypeScript 小專案，Mojang 的材質/模型**不進 repo**，全部放在 `<repo>/.work/`（已 gitignore）。

## 目錄

| 路徑 | 內容 |
|---|---|
| `scripts/prep-assets.mjs` | 模擬 Hub 後端資源管線：從 client jar 解出的 assets 產生圖集、合併的 blockstates/models、flags、生物群系顏色表 |
| `src/world.ts` | 用 deepslate 的 `NbtRegion` 讀 `.mca`，轉成緊湊 section 資料 |
| `src/mesher.ts` | 自己的 section 網格生成器（呼叫 deepslate 的 `BlockDefinition` / `SpecialRenderers` 產生單格 quads；剔除、生物群系染色、diff 分類自己寫） |
| `src/worker.ts` | Web Worker：抓 region → 解析 → 網格 → Transferable 回傳；含 diff 與增量更新 |
| `src/viewer.ts` / `src/main.ts` | WebGL2 檢視器、UI、Canvas2D 疊圖（實體方框、告示牌文字標籤） |
| `src/stock.ts` | 對照組：完全用 deepslate 自己的 `StructureRenderer` |
| `public/diff-edits.json` | 模擬「另一個 commit」的方塊編輯（JSON 層面） |
| `scripts/bench-mesh.ts` `scripts/bench-node.ts` | Node 端基準（無 GL） |
| `scripts/bench-browser.mjs` `scripts/screenshot.mjs` | 無頭 Chrome 基準與截圖 |
| `bench-results.json` | 最近一次瀏覽器基準的原始輸出 |
| `screenshots/` | 截圖（PNG，各 < 500 KB） |

## 安裝

```bash
cd experiments/04-deepslate
npm install                       # deepslate 0.27.2、gl-matrix、vite、typescript、pngjs、playwright-core、tsx
```

需要 Node 20+（實測 v24）、`unzip`、`curl`；截圖需要 Chrome/Chromium 與 `xvfb-run`（實測 `/usr/bin/google-chrome` 154，無需 `playwright install`，腳本以 `executablePath` 指向系統 Chrome；可用環境變數 `CHROME=` 覆寫）。

## 下載 Mojang 資源（版本清單 → client jar → 解出 assets）

```bash
cd <repo>/.work/assets
curl -sL https://piston-meta.mojang.com/mc/game/version_manifest_v2.json -o manifest.json
# 對 1.21.11 與 26.2：從 manifest 找到該版本的 json，取 downloads.client.url 與 sha1，下載後驗證，再解出 assets 與 biome 資料
for v in 1.21.11 26.2; do
  vurl=$(python3 -c "import json;print(next(x['url'] for x in json.load(open('manifest.json'))['versions'] if x['id']=='$v'))")
  curl -sL "$vurl" -o $v.json
  url=$(python3 -c "import json;print(json.load(open('$v.json'))['downloads']['client']['url'])")
  sha=$(python3 -c "import json;print(json.load(open('$v.json'))['downloads']['client']['sha1'])")
  mkdir -p $v && curl -sL "$url" -o $v/client.jar && echo "$sha  $v/client.jar" | sha1sum -c
  (cd $v && unzip -qo client.jar 'assets/*' 'data/minecraft/worldgen/biome/*' version.json)
done
# 預處理（產物寫到 .work/assets/<v>/pack/，約 1 秒/版本）
cd <repo>/experiments/04-deepslate
node scripts/prep-assets.mjs 1.21.11
node scripts/prep-assets.mjs 26.2
```

（本次實驗的下載紀錄：`.work/assets/client-<v>.json`，兩版 sha1 皆驗證通過。）

## 啟動

```bash
npm run dev            # http://127.0.0.1:5174/ ，同時把 <repo>/.work 以 /work/ 提供給頁面
```

網址參數：`ver=1.21.11|26.2`、`size=scene|4x4|16x16`、`view=A|B|D`（D = diff）、`only=1`（只看變動）、
`cam=<預設名稱>` 或 `cam=x,y,z,yaw,pitch`、`labels=0`、`biome=<名稱>`（強制所有 biome，測染色用）、
`mode=stock`（對照組：原封不動的 deepslate `StructureRenderer`）、`w=`/`h=`（畫布大小）。
操作：拖曳轉向、WASD 移動（Shift 加速）。

## 基準與截圖

```bash
tsx scripts/bench-node.ts 1.21.11      # NBT/region 解析（Node）
tsx scripts/bench-mesh.ts 1.21.11      # + 網格生成（Node，無 GL）
# 以下需要 dev server 在跑
node scripts/bench-browser.mjs         # 無頭 Chrome，寫 bench-results.json
xvfb-run -a node scripts/screenshot.mjs            # 全部截圖 -> screenshots/
xvfb-run -a node scripts/screenshot.mjs 02-stairs-slabs   # 只截指定的
```

`npx vite build` 可量打包大小（輸出到 `dist/`，已 gitignore）。
