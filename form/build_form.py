"""Fill the official contest form (section 02) from docs/proposal.html.

Section 01 (contact info) and 04 (signatures) stay blank for the applicant.
Run: python3 build_form.py  ->  out/POWERJ_접수양식_02.docx (merge into the hand-filled form separately)
"""
import re
import zipfile
from html import escape, unescape
from html.parser import HTMLParser
from pathlib import Path

here = Path(__file__).parent
SRC = here / "src/contest-application-consent.docx"
OUT = here / "out/POWERJ_접수양식_02.docx"  # section 02 only; merge into the hand-filled form separately
proposal = (here / "../docs/proposal.html").read_text(encoding="utf-8")
APP_NAME = "POWER J"
CELL_W = 9640  # box width minus cell margins (dxa)


def run(text, bold=False, code=False, size=20, color=None):
    rpr = ""
    if bold:
        rpr += "<w:b/><w:bCs/>"
    if code:
        rpr += '<w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/>'
    if color:
        rpr += f'<w:color w:val="{color}"/>'
    rpr += f'<w:sz w:val="{size}"/><w:szCs w:val="{size}"/>'
    return f'<w:r><w:rPr>{rpr}</w:rPr><w:t xml:space="preserve">{escape(text, quote=False)}</w:t></w:r>'


def para(runs, before=0, after=60, indent=0, keep=False):
    ppr = f'<w:spacing w:before="{before}" w:after="{after}" w:line="276" w:lineRule="auto"/>'
    if indent:
        ppr += f'<w:ind w:left="{indent}" w:hanging="200"/>'
    if keep:
        ppr = "<w:keepNext/>" + ppr
    return f"<w:p><w:pPr>{ppr}</w:pPr>{''.join(runs)}</w:p>"


class Conv(HTMLParser):
    """Small HTML -> WordprocessingML converter for the tags proposal.html uses."""

    def __init__(self):
        super().__init__()
        self.out, self.runs, self.style = [], [], []
        self.block = None  # (kind, extra)
        self.table = None  # list of rows; row = list of (is_header, runs)
        self.cell = None
        self.skip = 0
        self.in_big = False
        self.href = None

    # inline state
    def _fmt(self):
        return dict(bold="b" in self.style or "strong" in self.style or "th" in self.style,
                    code="code" in self.style,
                    size=18 if self.table is not None else (18 if "note" in self.style else 20),
                    color="666666" if "note" in self.style else None)

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        cls = a.get("class", "")
        if tag == "h2" or (tag == "span" and "n" == cls):
            self.skip += 1
            self.style.append("skip")
            return
        if tag == "table":
            self.flush()
            self.table = []
        elif tag == "tr" and self.table is not None:
            self.table.append([])
        elif tag in ("td", "th") and self.table is not None:
            self.cell = []
            self.span = int(a.get("colspan", 1))
            self.style.append(tag)
            return
        elif tag in ("h3",):
            self.flush()
            self.block = ("h3",)
            self.style.append("b")
            return
        elif tag in ("p",) or (tag == "div" and cls in ("quote",)):
            self.flush()
            self.block = ("p", cls)
            if cls == "note":
                self.style.append("note")
                return
        elif tag == "li":
            self.flush()
            self.block = ("li",)
        elif tag == "div" and cls in ("card", "step"):
            self.flush()
            self.block = ("li",)
        elif tag == "div" and self.in_big:
            self.flush()
            self.block = ("li",)
        elif tag == "div" and cls == "big":
            self.flush()
            self.in_big = True
            self.style.append("big")
            return
        elif tag in ("small",) and self.block and self.block[0] == "li":
            self._add(" — ")
        elif tag == "span" and cls == "k":
            self.style.append("k")
            return
        elif tag == "a":
            self.href = a.get("href", "")
        elif tag == "span" and self.block and self.block[0] == "li" and not self.table:
            self._add(" — ")
        elif tag == "br":
            self._add(" ")
        self.style.append(tag)

    def handle_endtag(self, tag):
        if not self.style:
            return
        top = self.style.pop()
        if top == "skip":
            self.skip -= 1
            return
        if tag in ("td", "th") and self.table is not None:
            self.table[-1].append((tag == "th", self.cell, self.span))
            self.cell = None
        elif tag == "table":
            self.out.append(self._table())
            self.table = None
        elif top == "k":
            self._add(" · ")
        elif top == "big":
            self.in_big = False
        elif tag == "a" and (self.href or "").startswith("http"):
            self._add(f" ({self.href})")
        elif tag in ("h3", "p", "li") or (tag == "div" and self.block and self.block[0] in ("li", "p")):
            self.flush()

    def handle_data(self, data):
        if self.skip:
            return
        text = re.sub(r"\s+", " ", data)
        if text.strip() or (text and (self.cell or self.runs)):
            self._add(text)

    def _add(self, text):
        r = run(text, **self._fmt())
        (self.cell if self.cell is not None else self.runs).append(r)

    def flush(self):
        if not self.runs:
            return
        kind = self.block[0] if self.block else "p"
        if kind == "h3":
            self.out.append(para(self.runs, before=160, after=60, keep=True))
        elif kind == "li":
            self.out.append(para([run("• ")] + self.runs, indent=200))
        else:
            self.out.append(para(self.runs))
        self.runs, self.block = [], None

    def _table(self):
        rows = [r for r in self.table if r]
        ncol = max(sum(c[2] for c in r) for r in rows)
        lens = [1] * ncol
        for r in rows:
            if sum(x[2] for x in r) != len(r):
                continue
            for i, (_, c, _s) in enumerate(r):
                n = len(re.sub(r"<[^>]+>", "", "".join(c)))
                lens[i] = max(lens[i], min(n, 60))
        tot = sum(lens)
        widths = [max(1100, CELL_W * l // tot) for l in lens]
        widths[-1] += CELL_W - sum(widths)
        b = '<w:{0} w:val="single" w:sz="4" w:space="0" w:color="BFC7D0"/>'
        borders = "".join(b.format(s) for s in ("top", "left", "bottom", "right", "insideH", "insideV"))
        xml = (f'<w:tbl><w:tblPr><w:tblW w:w="{CELL_W}" w:type="dxa"/><w:tblBorders>{borders}</w:tblBorders>'
               '<w:tblLayout w:type="fixed"/><w:tblCellMar><w:left w:w="80" w:type="dxa"/><w:right w:w="80" w:type="dxa"/></w:tblCellMar></w:tblPr>'
               f'<w:tblGrid>{"".join(f"<w:gridCol w:w={chr(34)}{w}{chr(34)}/>" for w in widths)}</w:tblGrid>')
        for r in rows:
            xml += "<w:tr><w:trPr><w:cantSplit/></w:trPr>"
            col = 0
            for hdr, c, span in r:
                w = sum(widths[col:col + span])
                col += span
                shade = '<w:shd w:val="clear" w:color="auto" w:fill="EEF1F5"/>' if hdr else ""
                gs = f'<w:gridSpan w:val="{span}"/>' if span > 1 else ""
                xml += (f'<w:tc><w:tcPr><w:tcW w:w="{w}" w:type="dxa"/>{gs}{shade}</w:tcPr>'
                        f'{para(c or [run("")], after=0)}</w:tc>')
            xml += "</w:tr>"
        return xml + "</w:tbl>" + para([run("")], after=0)


def section(sid):
    m = re.search(rf'<section id="{sid}">(.*?)</section>', proposal, re.S)
    return m.group(1)


def convert(html):
    c = Conv()
    c.feed(html)
    c.flush()
    return "".join(c.out)


ax = re.sub(r'<h2><span class="n">부록</span>(.*?)</h2>', r"<h3>에뮬레이터 \1</h3>", section("ax"))
item7 = """
<ul>
<li><b>GitHub 저장소 (비공개):</b> https://github.com/nightshift-makers/powerj · APK: apk/POWERJ_release.apk · 심사 계정을 Collaborator로 초대합니다. 에뮬레이터 회귀 테스트 스크립트 tools/regress.sh 포함.</li>
<li><b>시연 영상:</b> [영상 링크 기입] · Pleos Connect 에뮬레이터에서 경주 2일 여행 시나리오를 보여 줍니다(동행 차는 Firebase로 재현). 계획 담기 → 날씨 점검 → 출발 전 점검 → 함께 이동 → 시동을 다시 켜고 이어서 안내 → 경비 정리 순서입니다.</li>
</ul>
"""

refs = re.findall(r'<li><a href="([^"]+)">([^<]+)</a></li>', re.search(r'<h2><span class="n">출처</span></h2>(.*?)</section>', proposal, re.S).group(1))
item7 += "<h3>출처</h3><ul>" + "".join(f"<li>{escape(unescape(t))}: {escape(u)}</li>" for u, t in refs) + "</ul>"

items = [
    convert(section("s1")),
    convert(section("s2")),
    convert(section("s3")),
    convert(section("s4")),
    convert(section("s5")) + convert(ax),
    convert(section("s6")),
    convert(item7),
    "",  # 8. 숏폼 링크: optional, applicant fills
]

with zipfile.ZipFile(SRC) as z:
    files = {n: z.read(n) for n in z.namelist()}
doc = files["word/document.xml"].decode("utf-8")

# replace each content-control paragraph (in document order) with generated content
sdt_paras = list(re.finditer(r"<w:p [^>]*>(?:(?!</w:p>).)*?<w:sdt>.*?</w:sdt></w:p>", doc, re.S))
assert len(sdt_paras) == 8, len(sdt_paras)
for m, body in reversed(list(zip(sdt_paras, items))):
    if body:
        doc = doc[:m.start()] + body + doc[m.end():]

# app name lines (01, 02, 04)
def fill_name(m):
    p = m.group(0)
    if "프로젝트명" not in p:
        return p
    p = re.sub(r"(<w:t[^>]*>[^<]*?프로젝트명:)\s*_*(</w:t>)", rf"\1 {APP_NAME}\2", p)
    return re.sub(r"<w:t>_+</w:t>", "<w:t></w:t>", p)

doc = re.sub(r"<w:p [^>]*>.*?</w:p>", fill_name, doc, flags=re.S)
assert len(re.findall(rf"프로젝트명:\s*{APP_NAME}", doc)) == 3
files["word/document.xml"] = doc.encode("utf-8")

OUT.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    for n, data in files.items():
        z.writestr(n, data)
print("wrote", OUT)
