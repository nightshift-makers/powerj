"""Copy the applicant's hand-filled form (sections 01/04 kept) and replace section 02 items with out/POWERJ_접수양식_02.docx.
Never writes to the source file. Output: out/POWERJ_접수양식.docx"""
import re, sys, zipfile
if len(sys.argv) < 2: sys.exit('usage: python3 merge_form.py <hand-filled form .docx>')
src = sys.argv[1]
new = 'out/POWERJ_접수양식_02.docx'
out = sys.argv[2] if len(sys.argv) > 2 else 'out/POWERJ_접수양식.docx'

def load(p):
    with zipfile.ZipFile(p) as z: return {n: z.read(n) for n in z.namelist()}

def ptext(x): return ''.join(re.findall(r'<w:t[^>]*>([^<]*)</w:t>', x))

def item_table(t, label):
    for m in re.finditer(r'<w:p[ >].*?</w:p>', t, re.S):
        if ptext(m.group(0)).strip().startswith(label): break
    else: raise ValueError(label)
    s0 = t.index('<w:tbl>', m.end()); d = 0; k = s0
    while True:
        a = t.find('<w:tbl>', k); b = t.find('</w:tbl>', k)
        if a != -1 and a < b: d += 1; k = a + 7
        else:
            d -= 1; k = b + 8
            if d == 0: return s0, k

U, N = load(src), load(new)
u, n = U['word/document.xml'].decode(), N['word/document.xml'].decode()
for lab in ['1. 앱 한 줄 소개', '2. 기획 배경', '3. 앱 주요 기능', '4. 주요 사용 시나리오', '5. 개발 계획', '6. 기대효과', '7. 참고자료']:
    us, ue = item_table(u, lab); ns, ne = item_table(n, lab)
    u = u[:us] + n[ns:ne] + u[ue:]

def fix_name(m):  # app name lines: keep only "POWER J" even when the old name was split across runs
    para = m.group(0)
    if '프로젝트명' not in ptext(para): return para
    state = {'done': False}
    def sub(t):
        s = t.group(2)
        if state['done']:
            return t.group(1) + ('' if re.fullmatch(r'[\s.()A-Z가-힣]*', s) and s.strip() else s) + t.group(3)
        if '프로젝트명' in s and ':' in s:
            state['done'] = True
            return t.group(1) + s[:s.index(':') + 1] + ' POWER J' + t.group(3)
        return t.group(0)
    return re.sub(r'(<w:t[^>]*>)([^<]*)(</w:t>)', sub, para)
u = re.sub(r'<w:p[ >].*?</w:p>', fix_name, u, flags=re.S)

U['word/document.xml'] = u.encode()
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for k, v in U.items(): z.writestr(k, v)
names = [ptext(m.group(0)) for m in re.finditer(r'<w:p[ >].*?</w:p>', u, re.S) if '프로젝트명' in ptext(m.group(0))]
print('wrote', out, [x[x.index('프로젝트명'):] for x in names])
