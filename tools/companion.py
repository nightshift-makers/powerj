"""Plays the host car (the one that made the code) for tools/regress.sh, over Firebase REST.
Usage: python3 tools/companion.py create|change|get|delete CODE"""
import json, pathlib, sys, time, urllib.request as ur
root = pathlib.Path(__file__).resolve().parent.parent
keys = dict(l.split('=', 1) for l in (root / 'keys.properties').read_text().splitlines() if '=' in l and not l.startswith('#'))
fb = keys['firebase'].strip().rstrip('/')
cmd, code = sys.argv[1], sys.argv[2]
def call(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    return ur.urlopen(ur.Request(f'{fb}{path}', data=data, method=method, headers={'Content-Type': 'application/json'}), timeout=15).read()
now = lambda: int(time.time() * 1000)
if cmd == 'create':  # host makes a 3-stop Gyeongju trip, has reached 불국사 and is halfway to 첨성대
    stops = [dict(name=n, lat=la, lon=lo, day=1, stay=False) for n, la, lo in
             [('불국사', 35.7900, 129.3320), ('첨성대', 35.8347, 129.2190), ('황리단길', 35.8376, 129.2105)]]
    call('PUT', f'/trips/{code}/plan.json', dict(stops=stops, done=0, by='carB', at=now(), title='친구들과 경주', start=time.strftime('%Y-%m-%d'), days=1))
    call('PUT', f'/trips/{code}/cars/carB.json', dict(lat=35.81, lon=129.27, at=now(), p=0.5, done=1, host=True))
elif cmd == 'change':  # swap 첨성대 for 대릉원 and stand ~3 km from the emulator
    plan = json.loads(call('GET', f'/trips/{code}/plan.json'))
    plan['stops'] = [dict(s, name='대릉원', lat=35.8383, lon=129.2125) if s['name'] == '첨성대' else s for s in plan['stops']]
    plan.update(by='carB', at=int(time.time() * 1000))
    call('PUT', f'/trips/{code}/plan.json', plan)
    call('PUT', f'/trips/{code}/cars/carB.json', dict(lat=37.42, lon=127.13, at=now(), p=0.5, done=1, host=True))
elif cmd == 'get':  # the plan as the server has it, stop names only
    plan = json.loads(call('GET', f'/trips/{code}/plan.json'))
    print(','.join(s['name'] for s in plan['stops']) if plan else 'none'); sys.exit()
elif cmd == 'delete':
    call('DELETE', f'/trips/{code}.json')
print('ok')
