#!/usr/bin/env python3
"""在 .work/bot 套用固定 PrismarineJS 26.3 驗收依賴；不更改正式插件。
先安裝 npm 依賴。上游 data 的未完成元件 codec 不用於本驗收 fixture。
"""
import argparse, concurrent.futures, hashlib, json, subprocess, urllib.request
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
DATA_REPO = 'DallasCarraher/minecraft-data'
DATA_SHA = '924e26d6b3bb8f97a5d1e73d6190b09879c2e1e2'
PATCHES = [
    ('PrismarineJS/prismarine-physics', 145, 'b928638645a56a110a51da1f2080c6455b27984a', 'prismarine-physics'),
    ('PrismarineJS/node-minecraft-protocol', 1538, 'a1a33809c49553e544a7efd3d8bc03eb0867f4ab', 'minecraft-protocol'),
    ('PrismarineJS/prismarine-chunk', 334, '7382b4fef1c56290524665e7b9af58b7d1f34791', 'prismarine-chunk'),
    ('PrismarineJS/mineflayer', 4125, '93c8c160bd243716e75caad558f9492c5bc4e9be', 'mineflayer'),
    ('PrismarineJS/mineflayer', 4146, '651195051e539c7737397b1c1167c882848031f3', 'mineflayer'),
]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bot-dir', type=Path, default=ROOT / '.work/bot')
    args = parser.parse_args()
    bot = args.bot_dir.resolve()
    if not bot.is_relative_to(ROOT / '.work'):
        parser.error('bot-dir 必須在 .work/，不能更改其他應用的依賴')
    proof = []
    def get(url):
        request = urllib.request.Request(url, headers={'User-Agent': 'WorldGit acceptance'})
        data = urllib.request.urlopen(request, timeout=60).read()
        proof.append({'url': url, 'sha256': hashlib.sha256(data).hexdigest()})
        return data
    base = bot / 'node_modules/minecraft-data'
    if not (base / 'data.js').exists():
        parser.error('先在 .work/bot 安裝 mineflayer 與 minecraft-data')
    source = f'https://raw.githubusercontent.com/{DATA_REPO}/{DATA_SHA}/data/'
    paths = json.loads(get(source + 'dataPaths.json'))
    local = base / 'minecraft-data/data/dataPaths.json'
    installed = json.loads(local.read_text())
    row = paths['pc']['26.3']
    def fetch(entry):
        kind, directory = entry
        extension = '.yml' if kind == 'proto' else '.json'
        target = base / 'minecraft-data/data' / directory / (kind + extension)
        if directory != 'pc/26.3' and target.exists():
            return
        data = get(source + directory + '/' + kind + extension)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(fetch, row.items()))
    installed['pc']['26.3'] = row
    local.write_text(json.dumps(installed, indent=1))
    versions = base / 'minecraft-data/data/pc/common/protocolVersions.json'
    local_versions = json.loads(versions.read_text())
    new = next(v for v in json.loads(get(source + 'pc/common/protocolVersions.json')) if v['minecraftVersion'] == '26.3')
    assert new['version'] == 777 and new['dataVersion'] == 5023
    versions.write_text(json.dumps([new] + [v for v in local_versions if v['minecraftVersion'] != '26.3'], indent=1))
    # Digging 修正的固定 commit 由下方常數指定，不能默默套用之後的 PR 更新。
    for repo, number, expected, module in PATCHES:
        api = f'https://api.github.com/repos/{repo}/pulls/{number}'
        meta = json.loads(get(api))
        if meta['head']['sha'] != expected:
            raise RuntimeError(f'{repo}#{number} 的 head 已變；需明確核對固定上游來源')
        files = json.loads(get(api + '/files'))
        for entry in files:
            target = bot / 'node_modules' / module / entry['filename']
            if not target.resolve().is_relative_to(bot / 'node_modules' / module):
                raise RuntimeError('上游 patch 路徑超出模組')
            # 上游已加入 26.2 的 loader row；本地已有，26.3 在下方精確加入。
            if module == 'prismarine-chunk' and entry['filename'] == 'src/index.js':
                continue
            additions = [line[1:] for line in entry.get('patch', '').splitlines()
                         if line.startswith('+') and not line.startswith('+++') and line[1:].strip()]
            existing = target.read_text() if target.exists() else ''
            if additions and all(line in existing for line in additions):
                continue
            if entry['status'] == 'added':
                url = f"https://raw.githubusercontent.com/{meta['head']['repo']['full_name']}/{expected}/{entry['filename']}"
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(get(url))
            else:
                patch = '--- a/' + entry['filename'] + '\n+++ b/' + entry['filename'] + '\n' + entry['patch'] + '\n'
                command = ['patch', '--forward', '--fuzz=0', '-p1']
                destination = bot / 'node_modules' / module
                subprocess.run(command + ['--dry-run'], input=patch, text=True, cwd=destination, check=True)
                subprocess.run(command, input=patch, text=True, cwd=destination, check=True)
    for module, name in [('mineflayer', 'lib/version.js'), ('minecraft-protocol', 'src/version.js')]:
        file = bot / 'node_modules' / module / name
        value = file.read_text()
        if "'26.3'" not in value:
            if "'26.2'" not in value:
                raise RuntimeError('先安裝已有 26.2 支援的 bot 依賴')
            file.write_text(value.replace("'26.2'", "'26.2', '26.3'"))
    file = bot / 'node_modules/prismarine-chunk/src/index.js'
    value = file.read_text()
    if '26.3:' not in value:
        file.write_text(value.replace("26.2: require('./pc/1.18/chunk')", "26.2: require('./pc/1.18/chunk'),\n    26.3: require('./pc/1.18/chunk')"))
    # 26.3 rejects a second position packet before the real client tick ends.
    # Its expanded teleport confirmation already carries position/rotation;
    # let the next physics tick send movement, then send exactly one tick_end.
    physics = bot / 'node_modules/mineflayer/lib/plugins/physics.js'
    value = physics.read_text()
    marker = "if (bot.version === '26.3') bot._client.write('tick_end', {})"
    if marker not in value:
        replacements = [
            ("      updatePosition(now)\n    }", "      updatePosition(now)\n      " + marker + "\n    }"),
            ("        sendPacketPositionAndLook(delayedPos, delayedYaw, delayedPitch, delayedOnGround)",
             "        if (bot.version !== '26.3') sendPacketPositionAndLook(delayedPos, delayedYaw, delayedPitch, delayedOnGround)"),
            ("    sendPacketPositionAndLook(pos, newYaw, newPitch, bot.entity.onGround)",
             "    if (bot.version !== '26.3') sendPacketPositionAndLook(pos, newYaw, newPitch, bot.entity.onGround)"),
        ]
        for before, after in replacements:
            if value.count(before) != 1:
                raise RuntimeError('26.3 client tick patch context changed; verify the upstream physics source')
            value = value.replace(before, after)
        physics.write_text(value)
    proof.append({'local_rule': '26.3-native-client-tick-framing',
                  'sha256': hashlib.sha256(physics.read_bytes()).hexdigest()})
    # node-minecraft-protocol decodes 26.3 VecDelta as move.steps. Mineflayer's
    # legacy consumer still reads top-level dX/dY/dZ, producing NaN positions.
    entities = bot / 'node_modules/mineflayer/lib/plugins/entities.js'
    value = entities.read_text()
    marker = 'const movement = packet.move // WorldGit 26.3 decoded VecDelta'
    if marker not in value:
        before = "    if (bot.supportFeature('fixedPointDelta')) {"
        after = """    if (bot.version === '26.3') {
      const movement = packet.move // WorldGit 26.3 decoded VecDelta
      if (!movement || !Array.isArray(movement.steps)) throw new Error('Missing 26.3 VecDelta steps')
      for (const step of movement.steps) {
        if (![step.dX, step.dY, step.dZ].every(Number.isFinite)) throw new Error('Invalid 26.3 VecDelta')
        entity.position.translate(step.dX / 4096, step.dY / 4096, step.dZ / 4096)
      }
      entity.onGround = movement.onGround
    } else if (bot.supportFeature('fixedPointDelta')) {"""
        if value.count(before) != 2:
            raise RuntimeError('26.3 VecDelta consumer context changed; verify upstream entities source')
        value = value.replace(before, after)
        entities.write_text(value)
    marker = '// WorldGit 26.3 linear/stepped position sync'
    if marker not in value:
        before = '    entity.position.set(packet.x, packet.y, packet.z)\n    entity.velocity.set(packet.dx, packet.dy, packet.dz)'
        after = """    // WorldGit 26.3 linear/stepped position sync
    if (bot.version === '26.3') {
      if (!['linear', 'stepped'].includes(packet.positionType)) throw new Error('Unknown 26.3 position sync')
      const position = packet.positionType === 'stepped' ? packet.steps.at(-1) : packet
      if (position) {
        if (![position.x, position.y, position.z].every(Number.isFinite)) throw new Error('Invalid 26.3 position sync')
        entity.position.set(position.x, position.y, position.z)
      }
    } else {
      entity.position.set(packet.x, packet.y, packet.z)
      entity.velocity.set(packet.dx, packet.dy, packet.dz)
    }"""
        if value.count(before) != 1:
            raise RuntimeError('26.3 position sync context changed; verify upstream entities source')
        value = value.replace(before, after)
        entities.write_text(value)
    proof.append({'local_rule': '26.3-native-entity-delta-consumer',
                  'sha256': hashlib.sha256(entities.read_bytes()).hexdigest()})
    subprocess.run(['node', str(base / 'bin/generate_data.js')], check=True)
    (bot / '26.3-source-proof.json').write_text(json.dumps(proof, indent=2))
    print('26.3 bot 依賴就緒；來源 checksum 在 .work/bot/26.3-source-proof.json')

if __name__ == '__main__':
    main()
