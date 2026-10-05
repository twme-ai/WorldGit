"""Phase 5 驗收共用：嚴格讀取結果 envelope 與世界內的獨立 repo。"""
import json
from pathlib import Path
import subprocess
import re
import time

DIMENSIONS = ('minecraft:overworld', 'minecraft:the_nether', 'minecraft:the_end')


def cli_data(output):
    envelope = json.loads(output)
    assert isinstance(envelope, dict) and 'result' in envelope and 'data' in envelope, envelope
    assert envelope['result']['status'] in ('SUCCESS', 'NO_OP', 'PARTIAL', 'FAILED', 'CANCELLED'), envelope
    return envelope['data']


def repository(world, dimension='minecraft:overworld', version=None, paper=False):
    world = Path(world)
    dimension = dimension.replace('.', ':', 1) if ':' not in dimension else dimension
    if dimension == 'minecraft:overworld':
        return world / '.worldgit'
    namespace, path = dimension.split(':', 1)
    if version == '1.21.11' and namespace == 'minecraft' and path in ('the_nether', 'the_end'):
        if paper:
            world = world.parent / (world.name + ('_nether' if path == 'the_nether' else '_the_end'))
        return world / ('DIM-1' if path == 'the_nether' else 'DIM1') / '.worldgit'
    return world / 'dimensions' / namespace / path / '.worldgit'


def heads(world, version, paper=False):
    # 明確驗三個維度，缺少 repo 必須失敗；不以 glob 的偶然數量替代。
    return {dimension: subprocess.check_output([
        'git', '--git-dir', str(repository(world, dimension, version, paper)), 'rev-parse', 'HEAD'
    ], text=True).strip() for dimension in DIMENSIONS}


def hub_heads(work, owner, slug, branch='main'):
    return {dimension: subprocess.check_output([
        'git', '--git-dir', str(Path(work) / 'hub-data/repos' / owner / slug / (dimension.replace(':', '.') + '.git')),
        'rev-parse', 'refs/heads/' + branch
    ], text=True).strip() for dimension in DIMENSIONS}


def prepare_all_entities(world):
    """舊驗收保留全實體範圍；明確設定 all，不改新 creative 的預設。"""
    world = Path(world)
    if not (world / 'level.dat').exists() and (world / 'world/level.dat').exists():
        world = world / 'world'
    locations = {world / '.worldgit'}
    for version in ('1.21.11', '26.2'):
        for dimension in DIMENSIONS[1:]:
            for paper in (False, True):
                path = repository(world, dimension, version, paper)
                if path.parent.is_dir():locations.add(path)
    for path in locations:
        assert not (path / 'HEAD').exists(), 'fixture 已初始化：' + str(path)
        path.mkdir(parents=True, exist_ok=True)
        (path / 'worldgit-repo.yml').write_text('track: all\nentities: all\n')


def verify_all(cli, world, revisions=None):
    result = {dimension: cli(world, '--dimension', dimension, 'verify', *([revisions[dimension]] if revisions and dimension in revisions else [])) for dimension in DIMENSIONS}
    for dimension, value in result.items():
        assert value['state'] == 'COMPLETE' and set(value['dimensions']) == {dimension}, value
        assert all(value['dimensions'][dimension][key] == 0 for key in (
            'chunks', 'sections', 'biomeSections', 'entityPuts', 'entityRemoves', 'chunkDeletes', 'metaFiles'
        )), value
    return result


def player_command(bot, command, pattern, timeout=900):
    since = len(bot.lines)
    bot.ask('chat /' + command, 'chat_sent')
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        with bot.lock:events = list(bot.lines[since:])
        output = '\n'.join(event['t'] for event in events if event.get('ev') == 'chat')
        if re.search(pattern + '|Error:|錯誤：|失敗|PARTIAL', output):
            if any(word in output for word in ('Error:', '錯誤：', '失敗', 'PARTIAL')):raise RuntimeError(command + '\n' + output)
            return output
        time.sleep(.1)
    raise TimeoutError(command + '\n' + output)


def complete_verification_batch(output):
    result = cli_data(output)
    assert set(result) == set(DIMENSIONS), result
    for dimension, item in result.items():
        assert item['result']['status'] in ('SUCCESS', 'NO_OP'), item
        value = item['data']
        assert value['state'] == 'COMPLETE' and set(value['dimensions']) == {dimension}, value
        assert all(value['dimensions'][dimension][key] == 0 for key in (
            'chunks', 'sections', 'biomeSections', 'entityPuts', 'entityRemoves', 'chunkDeletes', 'metaFiles'
        )), value
    return True
