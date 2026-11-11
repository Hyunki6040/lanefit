"""Fill the official contest form from private/applicant.json and docs/submission/proposal.md.

    uv run -q --with python-docx python -I scripts/fill_form.py [--draft]

The original DOCX is only read. Output: private/LaneFit_접수양식.docx and .pdf.
Without --draft the script refuses to write while any {{...}} value is left.
--draft writes to private/preview/ so the layout can be checked early.

applicant.json: "" means "leave blank / drop that line"; "{{NEEDS_USER}}" means "not known yet".
"date": "" uses today's date. "consent" must be "동의" or "미동의"; the box is never ticked otherwise.
"""
import json
import re
import subprocess
import sys
import tempfile
from datetime import date
from pathlib import Path

from docx import Document
from docx.shared import Cm, Pt, Twips

ROOT = Path(__file__).resolve().parent.parent
FORM = ROOT / "docs/forms/contest-application-consent.docx"
PROPOSAL = ROOT / "docs/submission/proposal.md"
APPLICANT = ROOT / "private/applicant.json"
PLACEHOLDERS = {
    "REPO_URL": "repo_url", "COMMIT_HASH": "commit_hash", "APK_PATH": "apk_path",
    "DEMO_VIDEO_URL": "demo_video_url", "SHORTFORM_URL": "shortform_url",
}
BOX_MARGIN = Twips(110)  # the form's own cell margin (w:tcMar)
FONT_XCU = """<?xml version="1.0" encoding="UTF-8"?>
<oor:items xmlns:oor="http://openoffice.org/2001/registry">
<item oor:path="/org.openoffice.Office.Common/Font/Substitution"><prop oor:name="Replacement" oor:op="fuse"><value>true</value></prop></item>
<item oor:path="/org.openoffice.Office.Common/Font/Substitution/FontPairs"><node oor:name="_0" oor:op="replace"><prop oor:name="ReplaceFont" oor:op="fuse"><value>맑은 고딕</value></prop><prop oor:name="SubstituteFont" oor:op="fuse"><value>Apple SD Gothic Neo</value></prop><prop oor:name="Always" oor:op="fuse"><value>true</value></prop><prop oor:name="OnScreenOnly" oor:op="fuse"><value>false</value></prop></node></item>
</oor:items>
"""
SECTION_01_KEYS = [
    "unit", "division", "team_or_name", "app_name", "rep_name", "rep_role",
    "rep_email", "rep_phone", "headcount", "consent",
]


def known(v):
    return v not in ("", None) and "{{" not in str(v)


def fill(p, *values):
    """Replace each run of underscores in the paragraph with the next value. None keeps the blank."""
    vals = iter(values)
    cur, cont = None, False
    for r in p.runs:
        old = r.text

        def sub(m):
            nonlocal cur
            if not (m.start() == 0 and cont):  # a new blank, not the tail of the previous run's blank
                cur = next(vals, None)
                return m.group() if cur is None else f" {cur} "
            return m.group() if cur is None else ""

        new = re.sub(r"_+", sub, old)
        if old:
            cont = old.endswith("_")
        if new != old:
            r.text = new


def tick(p, label):
    """Turn the empty box in front of `label` into a filled one."""
    text = "".join(r.text for r in p.runs)
    i = text.rindex("□", 0, text.index(label))
    for r in p.runs:
        if i < len(r.text):
            r.text = r.text[:i] + "■" + r.text[i + 1:]
            return
        i -= len(r.text)


def blocks(lines):
    i = 0
    while i < len(lines):
        line = lines[i]
        i += 1
        if not line.strip():
            continue
        if line.startswith("|"):
            rows = [line]
            while i < len(lines) and lines[i].startswith("|"):
                rows.append(lines[i])
                i += 1
            cells = [[c.strip() for c in r.strip().strip("|").split("|")] for r in rows]
            yield "table", [r for r in cells if not set("".join(r)) <= set("-: ")]
        elif line.startswith("!["):
            yield "img", re.findall(r"!\[[^\]]*\]\(([^)]+)\)", line)
        elif line.startswith("### "):
            yield "h", line[4:]
        else:
            yield "p", "• " + line[2:] if line.startswith("- ") else line


def render(cell, body, styles):
    """Write a markdown subset (### headings, bullets, tables, image rows) into a form box."""

    # The box ships as one unsplittable row holding a one-space text control with a fixed line height:
    # that pushes a long answer to a new page, indents the first line and would clip pictures.
    for el in cell._tc.xpath("../w:trPr/w:cantSplit | ./w:p/w:sdt | ./w:p/w:pPr"):
        el.getparent().remove(el)

    def par():
        last = cell.paragraphs[-1]
        # Reuse the empty paragraph Word requires after a table, and the box's own first paragraph.
        if last._p is cell._tc[-1] and not last.text and not last.runs:
            return last
        return cell.add_paragraph()

    items = list(blocks(body.splitlines()))
    for n, (kind, v) in enumerate(items):
        if kind == "table":
            t = cell.add_table(len(v), len(v[0]))
            t.style = styles["Table Grid"]
            t.autofit = False
            # Columns share the box's inner width in proportion to their longest text, within limits.
            weight = [min(max(len(row[c]) for row in v), 40) + 6 for c in range(len(v[0]))]
            inner = cell.width - 2 * BOX_MARGIN
            for r, row in enumerate(v):
                for c, text in enumerate(row):
                    tc = t.cell(r, c)
                    tc.width = int(inner * weight[c] / sum(weight))
                    p = tc.paragraphs[0]
                    p.paragraph_format.space_after = Pt(0)
                    p.paragraph_format.line_spacing = 1.0
                    run = p.add_run(text)
                    run.font.size = Pt(9)
                    run.bold = r == 0
        elif kind == "img":
            p = par()
            for n, src in enumerate(v):
                if n:
                    p.add_run(" ")
                p.add_run().add_picture(str((PROPOSAL.parent / src).resolve()), width=Cm(7.8))
        else:
            p = par()
            if kind == "h" and p._p.getprevious() is not None:
                p.paragraph_format.space_before = Pt(8)
            if v.startswith("• "):
                p.paragraph_format.space_after = Pt(2)
            # No heading stranded at a page bottom. Not on the box's first paragraph and not before a
            # table: LibreOffice reads either as "keep the whole box together" and pushes it a page down.
            p.paragraph_format.keep_with_next = kind == "h" and n > 0 and items[n + 1][0] != "table"
            p.add_run(v).bold = kind == "h"


def main():
    draft = "--draft" in sys.argv
    a = json.loads(APPLICANT.read_text(encoding="utf-8"))
    d = Document(str(FORM))
    members, *boxes, consent = d.tables
    assert len(boxes) == 8, "form layout changed: expected 8 answer boxes"

    def pars(prefix):
        found = [p for p in d.paragraphs if p.text.strip().startswith(prefix)]
        assert found, f"form layout changed: no paragraph starts with {prefix!r}"
        return found

    def val(key):
        return str(a[key]) if a.get(key) not in ("", None) else None

    when = date.fromisoformat(a["date"]) if a.get("date") else date.today()

    # 01 참가신청서
    if known(a["unit"]):
        tick(pars("참가 단위")[0], a["unit"])
    if known(a["division"]):
        tick(pars("참가 부문")[0], a["division"])
    fill(pars("팀명(개인 참가 시 성명)")[0], val("team_or_name"))
    for p in pars("앱명 / 프로젝트명"):  # 01, 02 and 04 all carry this line
        fill(p, val("app_name"))
    fill(pars("대표자 성명")[0], val("rep_name"), val("rep_role"))
    fill(pars("대표자 소속")[0], val("rep_affiliation"))
    fill(pars("대표자 이메일")[0], val("rep_email"))
    fill(pars("대표자 휴대폰번호")[0], val("rep_phone"))
    fill(pars("참가 인원")[0], val("headcount"))
    fill(pars("작성일")[0], when.year, when.month, when.day)
    for cell, key in zip(members.rows[1].cells, ["rep_name", "rep_affiliation", "rep_phone", "rep_email", "rep_role"]):
        cell.text = val(key) or ""
    team = a.get("members", [])
    assert a["headcount"] == 1 + len(team) <= 5, "headcount must equal the representative plus members (form has 5 rows)"
    for row, m in zip(members.rows[2:], team):
        for cell, key in zip(row.cells, ["name", "affiliation", "phone", "email", "role"]):
            cell.text = m.get(key) or ""

    # 02 앱 프로젝트 소개
    md = PROPOSAL.read_text(encoding="utf-8")
    for name, key in PLACEHOLDERS.items():
        token = "{{" + name + "}}"
        if a.get(key) == "":
            md = "\n".join(line for line in md.splitlines() if token not in line)
        elif known(a.get(key)):
            md = md.replace(token, str(a[key]))
    parts = re.split(r"^## (\d)\. .*$", md, flags=re.M)
    sections = dict(zip(parts[1::2], parts[2::2]))
    assert sorted(sections) == list("12345678"), f"proposal.md needs '## 1.'..'## 8.', got {sorted(sections)}"
    for n, box in enumerate(boxes, 1):
        render(box.cell(0, 0), sections[str(n)].strip() or "해당 없음", d.styles)

    # 04 동의 확인 및 서명 (대표자 행)
    row = consent.rows[1].cells
    row[1].text = val("rep_name") or ""
    row[1].paragraphs[0].alignment = row[0].paragraphs[0].alignment
    if a.get("consent") in ("동의", "미동의"):
        tick(row[2].paragraphs[0], a["consent"])
        row[4].text = when.isoformat()
    sig = Path(a.get("signature_png") or "")
    if a.get("signature_png") and sig.is_file():
        row[3].paragraphs[0].add_run().add_picture(str(sig), width=Cm(2.5))
    # Each member decides and signs for themselves; a box is ticked only for an explicit 동의 / 미동의.
    for r, m in zip(consent.rows[2:], team):
        cells = r.cells
        cells[1].text = m.get("name") or ""
        cells[1].paragraphs[0].alignment = row[0].paragraphs[0].alignment
        if m.get("consent") in ("동의", "미동의"):
            tick(cells[2].paragraphs[0], m["consent"])
            cells[4].text = when.isoformat()
        msig = Path(m.get("signature_png") or "")
        if m.get("signature_png") and msig.is_file():
            cells[3].paragraphs[0].add_run().add_picture(str(msig), width=Cm(2.5))
    if a["unit"] == "팀":
        fill(pars("팀명(팀 참가 시)")[0], val("team_or_name"))

    # self-check
    sec2 = "\n".join(c.text + "".join(t2c.text for t in c.tables for r in t.rows for t2c in r.cells)
                     for c in (b.cell(0, 0) for b in boxes))
    for n, box in enumerate(boxes, 1):
        c = box.cell(0, 0)
        assert c.text.strip() or c.tables, f"box {n} is empty"
    for key in ("rep_name", "rep_email", "rep_phone", "rep_affiliation"):
        assert not known(a.get(key)) or str(a[key]) not in sec2, f"{key} appears in section 02 (form forbids it)"
    assert "@" not in sec2, "an email-like string appears in section 02"
    everything = "\n".join(p.text for p in d.paragraphs) + "".join(
        c.text for t in d.tables for r in t.rows for c in r.cells)
    decided = [x.get("consent") in ("동의", "미동의") for x in [a, *team]]
    expected_ticks = sum(known(a[k]) for k in ("unit", "division")) + sum(decided)
    assert everything.count("■") == expected_ticks, "wrong number of ticked boxes"
    for m in team:
        for key in ("name", "email", "phone"):
            assert not known(m.get(key)) or m[key] not in sec2, f"member {key} appears in section 02 (form forbids it)"

    missing_01 = [k for k in SECTION_01_KEYS if not known(a.get(k))]
    missing_01 += [f"members[{n}].{k}" for n, m in enumerate(team) for k in ("name", "email", "phone", "role", "consent")
                   if not known(m.get(k))]
    missing_02 = sorted(set(re.findall(r"\{\{\w+\}\}", sec2)))
    if a.get("consent") not in ("동의", "미동의"):
        print("동의 여부 미기재: applicant.json 의 consent 를 본인이 직접 정해야 함")
    if not (a.get("signature_png") and sig.is_file()):
        print("서명 이미지 없음: signature_png 를 넣거나, 출력물에 직접 서명")
    if missing_01:
        print("01 참가신청서 미입력:", ", ".join(missing_01))
    if missing_02:
        print("02 작품 소개에 남은 자리표시:", ", ".join(missing_02))
    if (missing_01 or missing_02) and not draft:
        sys.exit("미입력 값이 남아 최종본을 쓰지 않음. 값을 채우거나 --draft 로 미리보기만 생성")

    out_dir = ROOT / ("private/preview" if draft else "private")
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / ("LaneFit_접수양식_draft.docx" if draft else "LaneFit_접수양식.docx")
    d.save(str(out))
    # The form names 맑은 고딕, which macOS lacks; without a substitute LibreOffice mixes serif fallbacks.
    # ponytail: throwaway profile with one font pair; on a machine that has 맑은 고딕, delete FONT_XCU.
    with tempfile.TemporaryDirectory() as profile:
        (Path(profile) / "user").mkdir()
        (Path(profile) / "user/registrymodifications.xcu").write_text(FONT_XCU, encoding="utf-8")
        subprocess.run(["soffice", f"-env:UserInstallation=file://{profile}", "--headless", "--convert-to", "pdf",
                        "--outdir", str(out_dir), str(out)], check=True, stdout=subprocess.DEVNULL)
    print("wrote", out.relative_to(ROOT), "and", out.with_suffix(".pdf").relative_to(ROOT))


if __name__ == "__main__":
    main()
