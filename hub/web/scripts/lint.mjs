// 前端安全 lint：拒絕 HTML injection/eval sink 與舊的瀏覽器 token 儲存。
import fs from 'node:fs'
import path from 'node:path'
let failures = 0
function check(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const file = path.join(dir, entry.name)
    if (entry.isDirectory()) check(file)
    else if (/\.[tj]s$/.test(file)) {
      const source = fs.readFileSync(file, 'utf8')
      for (const pattern of [/\.innerHTML\s*=/, /\.insertAdjacentHTML\s*\(/, /\beval\s*\(/, /new\s+Function\s*\(/, /localStorage\.setItem\(\s*['"]worldgit\.token/]) {
        if (pattern.test(source)) { process.stderr.write(`${file}: 不允許 ${pattern}\n`); failures++ }
      }
    }
  }
}
check('src')
if (failures) process.exit(1)
process.stdout.write('前端安全 lint 通過\n')
