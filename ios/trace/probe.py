#!/usr/bin/env python3
"""Spawn app under frida, run the ID probe once, print the identifiers. arg: label for the container."""
import sys, time, frida, json
label = sys.argv[1] if len(sys.argv) > 1 else 'container'
bundle = sys.argv[2] if len(sys.argv) > 2 else 'com.squareup.cash'
rows = []
def on_msg(m, d):
    if m.get('type') == 'send':
        rows.append(m['payload'])
    elif m.get('type') == 'error':
        print('ERR', m.get('stack') or m.get('description'))
base = sys.argv[0].rsplit('probe.py',1)[0]
src = open(base+'probe_ids.js', encoding='utf-8').read()
dev = frida.get_device_manager().add_remote_device('127.0.0.1:27042')
pid = dev.spawn([bundle]); sess = dev.attach(pid)
s = sess.create_script(src); s.on('message', on_msg); s.load(); dev.resume(pid)
t0 = time.time()
while time.time()-t0 < 12 and not any(r.get('k')=='__done__' for r in rows):
    time.sleep(0.5)
try: s.unload(); sess.detach()
except Exception: pass
print(f'\n===== IDENTIFIERS as Cash App sees them — [{label}] =====')
for r in rows:
    if r.get('k') != '__done__':
        print(f"  {r['k']:32} = {r['v']}")
json.dump({'label':label,'ids':rows}, open(base+f'ids_{label}.json','w'), indent=2)
