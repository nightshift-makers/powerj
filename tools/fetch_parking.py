"""Download the national parking standard data once and bundle it as app/src/main/assets/parking.tsv.
Needs data_go_kr in keys.properties and 활용신청 for 전국주차장정보표준데이터 (OpenAPI).
Run: python3 tools/fetch_parking.py"""
import json, pathlib, urllib.parse, urllib.request
root = pathlib.Path(__file__).resolve().parent.parent
key = dict(l.split('=', 1) for l in (root / 'keys.properties').read_text().splitlines() if '=' in l and not l.startswith('#'))['data_go_kr'].strip()
url = 'https://api.data.go.kr/openapi/tn_pubr_prkplce_info_api'
cols = ['prkplceNm', 'prkplceSe', 'latitude', 'longitude', 'basicTime', 'basicCharge', 'addUnitTime', 'addUnitCharge', 'dayCmmtkt', 'phoneNumber', 'parkingchrgeInfo']
rows, page = [], 1
while True:
    q = urllib.parse.urlencode({'serviceKey': key, 'pageNo': page, 'numOfRows': 1000, 'type': 'json'})
    data = json.load(urllib.request.urlopen(f'{url}?{q}', timeout=60))
    body = data.get('response', data)['body']  # this API has no 'response' wrapper
    items = body.get('items') or []
    if isinstance(items, dict): items = items.get('item', [])  # {'items': {'item': [...]}}
    rows += ['\t'.join(str(it.get(c, '') or '').replace('\t', ' ').strip() for c in cols) for it in items]
    print('page', page, len(rows), '/', body.get('totalCount'))
    if not items or len(rows) >= int(body.get('totalCount', 0)): break
    page += 1
out = root / 'app/src/main/assets/parking.tsv'
out.write_text('\n'.join(r for r in rows if r.split('\t')[2] and r.split('\t')[3]))
print('wrote', out, out.stat().st_size // 1024, 'KB')
