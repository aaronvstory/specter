#!/usr/bin/env python3
"""Spawn (or attach to) an app under frida-server over the iproxy tunnel, run the tracer,
collect the JSON events, and write a trace artifact. SE2, tester container.

Usage: python run_trace.py <bundle_id> <out.json> [seconds] [--attach]
"""
import sys, json, time, frida

bundle = sys.argv[1]
outpath = sys.argv[2]
secs = int(sys.argv[3]) if len(sys.argv) > 3 and sys.argv[3].isdigit() else 45
attach = '--attach' in sys.argv

events = []
def on_message(msg, data):
    if msg.get('type') == 'send':
        events.append(msg['payload'])
    elif msg.get('type') == 'error':
        events.append({'kind': 'frida-error', 'value': msg.get('stack') or msg.get('description')})

# connect to device frida-server via the forwarded port (iproxy 27042 27042)
dev = frida.get_device_manager().add_remote_device('127.0.0.1:27042')
with open(sys.argv[0].rsplit('run_trace.py', 1)[0] + 'trace_cashapp.js', 'r', encoding='utf-8') as f:
    src = f.read()

if attach:
    # match by bundle id across running apps, else by process name
    pid = None
    for a in dev.enumerate_applications():
        if a.identifier == bundle and a.pid:
            pid = a.pid; break
    if pid is None:
        for p in dev.enumerate_processes():
            if p.name.lower() in (bundle.lower(), bundle.split('.')[-1].lower()):
                pid = p.pid; break
    if pid is None:
        raise SystemExit(f'no running process for {bundle}')
    session = dev.attach(pid)
    print(f'attached to pid {pid}', flush=True)
    script = session.create_script(src)
    script.on('message', on_message)
    script.load()
else:
    pid = dev.spawn([bundle])
    session = dev.attach(pid)
    script = session.create_script(src)
    script.on('message', on_message)
    script.load()
    dev.resume(pid)
    print(f'spawned {bundle} pid {pid}', flush=True)

t0 = time.time()
while time.time() - t0 < secs:
    time.sleep(1)
    if len(events) and int(time.time() - t0) % 5 == 0:
        print(f'  {int(time.time()-t0)}s: {len(events)} events', flush=True)

try:
    script.unload(); session.detach()
except Exception:
    pass

# summarize: distinct (kind,key,value) and which modules requested each
summary = {}
for e in events:
    if e.get('kind') in ('tracer-ready',):
        continue
    k = (e.get('kind'), e.get('key'), e.get('value'))
    s = summary.setdefault(str(k), {'kind': e.get('kind'), 'key': e.get('key'),
                                    'value': e.get('value'), 'count': 0, 'modules': set()})
    s['count'] += 1
    for m in e.get('modules') or []:
        s['modules'].add(m)
for s in summary.values():
    s['modules'] = sorted(s['modules'])

out = {'bundle': bundle, 'device': 'SE2 iPhone12,8 iOS16.3.1', 'seconds': secs,
       'total_events': len(events),
       'distinct': sorted(summary.values(), key=lambda x: (-x['count'], x['kind'] or '')),
       'raw': events}
with open(outpath, 'w', encoding='utf-8') as f:
    json.dump(out, f, indent=2, ensure_ascii=False)
print(f'wrote {outpath}: {len(events)} events, {len(summary)} distinct', flush=True)
