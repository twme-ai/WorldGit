"""section 線上替換 + bot 驗證 + 未載入 chunk IO。 python3 run_section.py <paper|folia> <ver>"""
import sys, json, time
from harness import *
plat, ver = sys.argv[1], sys.argv[2]
out = os.path.join(WORK, 'paper-poc', f'section-{plat}-{ver}.jsonl')
s = Server(plat, ver, default_plugins(ver), view=8)
log = []
def P(*a):
    line = ' '.join(str(x) for x in a); print(line); log.append(line)
def server_report(tag):
    return s.cmd(f'sec report 5 1 {tag}', 'sec_report')
def compare(tag, b):
    time.sleep(6)
    r = server_report(tag)
    bh = b.ask(f'hash 5 11 1 {tag}', 'hash')
    lights = []
    for l in r['lights']:
        x, y, z = [int(v) for v in l['pos'].split(',')]
        bl = b.ask(f'light {80 + x} {y} {16 + z}', 'light')
        lights.append({'pos': l['pos'], 'server_block': l['block'], 'bot_block': bl['light'], 'server_sky': l['sky'], 'bot_sky': bl['sky'], 'type': l['type'], 'bot_name': bl['name']})
    ok = r['nameHash'] == bh['hash']
    P(f'[{tag}] server nameHash={r["nameHash"]} bot hash={bh["hash"]} (nulls={bh["nulls"]}) -> {"MATCH" if ok else "MISMATCH"}; serverHash={r["hash"]} BE={r["beCount"]} {r["bePos"]} flag={r["flag"]}')
    for l in lights: P('   light', json.dumps(l))
    if not ok:
        s.cmd(f'sec dump 5 1 {tag}', 'sec_dump')
        sv = open(os.path.join(s.dir, 'plugins/WorldGitPoc', f'dump-{tag}.txt')).read().split(';')[:4096]
        bn = b.ask(f'names 5 11 1 {tag}', 'names')['names'].split(';')[:4096]
        diffs = [(i & 15, (i >> 8) & 15, (i >> 4) & 15, sv[i], bn[i]) for i in range(4096) if sv[i] != bn[i]]
        from collections import Counter
        P(f'   DIFF {len(diffs)} positions; by (server->bot):', dict(Counter((d[3], d[4]) for d in diffs).most_common(8)), 'by local y:', dict(Counter(d[1] for d in diffs)))
    return r, bh, lights, ok
try:
    s.start()
    b = connect_bot(s)
    s.send('gamemode creative WgBot'); s.send('tp WgBot 88 191 40')
    time.sleep(3)
    s.cmd('watch off -3 12 -3 5', 'watch')
    s.cmd('prep 16', 'prep_done', 120)
    time.sleep(2)
    s.cmd('sec prep 5 1', 'sec_prep')
    s.cmd('saveall', 'saveall'); time.sleep(5)
    r0, b0, l0, ok0 = compare('S0-initial', b)
    snap = s.cmd('sec snap S0 5 1', 'sec_snap'); P('snapshot', json.dumps(snap))
    mk = s.mark(); b.ask('pktreset', 'pktreset')
    a = s.cmd('sec apply P1 5 1 BLOCKS', 'sec_apply'); P('apply P1/BLOCKS', json.dumps(a))
    compare('P1-notify=BLOCKS', b)
    st = b.ask('pktstats', 'pktstats'); P('  bot pkt counts', json.dumps(st['counts']), {k: v for k, v in st['perChunk'].items() if k.endswith('5,1')})
    s.cmd('saveall', 'saveall'); time.sleep(3)
    d = s.cmd('sec disk 5 1', 'sec_disk'); P('  disk after P1', json.dumps(d))
    b.ask('pktreset', 'pktreset')
    a = s.cmd('sec apply S0 5 1 RESEND', 'sec_apply'); P('apply S0/RESEND', json.dumps(a))
    r1, b1, l1, ok1 = compare('S0-restored(RESEND)', b)
    P('  restored hash == original hash (server full hash incl. BE NBT):', r1['hash'] == r0['hash'], r1['hash'], r0['hash'])
    st = b.ask('pktstats', 'pktstats'); P('  bot pkt counts', json.dumps(st['counts']), {k: v for k, v in st['perChunk'].items() if k.endswith('5,1')})
    b.ask('pktreset', 'pktreset')
    a = s.cmd('sec apply P2 5 1 NONE', 'sec_apply'); P('apply P2/NONE (no notify)', json.dumps(a))
    r2, b2, l2, ok2 = compare('P2-notify=NONE', b)
    a = s.cmd('sec apply S0 5 1 BLOCKS', 'sec_apply'); P('apply S0/BLOCKS', json.dumps(a))
    r3, b3, l3, ok3 = compare('S0-restored(BLOCKS)', b)
    P('  restored hash == original:', r3['hash'] == r0['hash'])
    # 未載入 chunk IO
    m = s.mark()
    s.send('wgpoc io 9 -9 4 glass full'); s.wait(r'"kind":"io_loaded"|"kind":"error"|"kind":"io"', 60, m)
    for r in s.results(m):
        if r['kind'].startswith('io') or r['kind'] == 'error': P('IO', json.dumps(r, ensure_ascii=False)[:900])
    P('LOGERR', [l for l in s.errors_in_log(0) if not any(k in l for k in ('ROOT USER', '****', 'OFFLINE', 'no attempt', 'hackers', 'online-mode', 'EULA', 'latest build', 'behind', 'recommended', 'papermc.io', 'RISKS', 'MORE INFORMATION', 'madelinemiller'))][:20])
finally:
    open(out, 'w').write('\n'.join(log))
    s.stop()
