"""Build an editable filing locally; no case facts or service details are generated."""

import re
import shutil
import subprocess
import uuid
import zipfile
from html import escape
from xml.etree import ElementTree as ET

NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"


def xml(text):
    return escape(re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", "", text), quote=False)


def run(text):
    return '<w:r><w:t xml:space="preserve">' + xml(text) + '</w:t></w:r>'


def paragraph(text, style="", align="", extra=""):
    props = (f'<w:pStyle w:val="{style}"/>' if style else "") + (f'<w:jc w:val="{align}"/>' if align else "")
    return "<w:p>" + (f"<w:pPr>{props}</w:pPr>" if props else "") + extra + run(text) + "</w:p>"


def field(instruction, fallback):
    return ('<w:r><w:fldChar w:fldCharType="begin" w:dirty="true"/></w:r>'
            f'<w:r><w:instrText xml:space="preserve"> {xml(instruction)} </w:instrText></w:r>'
            '<w:r><w:fldChar w:fldCharType="separate"/></w:r>' + fallback +
            '<w:r><w:fldChar w:fldCharType="end"/></w:r>')


def authorities(text, supplied):
    if supplied:
        return sorted(set(s.strip() for s in supplied.splitlines() if s.strip()), key=str.casefold)
    try:
        from eyecite import get_citations
        from eyecite.models import FullCaseCitation, FullLawCitation
        return sorted({c.matched_text() for c in get_citations(text) if isinstance(c, (FullCaseCitation, FullLawCitation))}, key=str.casefold)
    except Exception:
        return []


def export(r, destination, work):
    draft = (r.get("draft") or "").strip()
    title = (r.get("title") or "").strip()
    caption = (r.get("caption") or "").strip()
    if not draft or not title or not caption:
        raise ValueError("Draft, title, and caption are required")
    fmt = (r.get("format") or "docx").lower()
    if fmt not in ("docx", "pdf", "both"):
        raise ValueError("Choose docx, pdf, or both")
    destination.mkdir(parents=True, exist_ok=True)
    slug = re.sub(r"[^a-zA-Z0-9]+", "-", title).strip("-")[:64] or "filing"
    basename = slug + "-" + uuid.uuid4().hex[:8]
    docx = work / (basename + ".docx")
    warning = []
    entries = authorities(draft, (r.get("authorities") or "").strip())
    headings = [m[1] for line in draft.splitlines() if (m := re.fullmatch(r"#{1,3}\s+(.+)", line))]
    body = [paragraph(line, align="center") for line in caption.splitlines()]
    body += [paragraph(title, "Front", "center"), paragraph("Table of Contents", "Front")]
    body += ["<w:p>" + field('TOC \\o "1-3" \\h \\z \\u', run("Update fields in your document editor to calculate page numbers.")) + "</w:p>"]
    body += [paragraph(heading) for heading in headings]
    body += [paragraph("Table of Authorities", "Front"),
             "<w:p>" + field("TOA \\h \\c 1", run("Update fields to generate page references for marked authorities.")) + "</w:p>"]
    body += [paragraph(entry) for entry in entries]
    if not entries:
        warning.append("No table-of-authorities entries were extracted or supplied.")
    service = (r.get("service") or "").strip()
    if not service:
        warning.append("Certificate of service is unfilled; no service details were supplied.")
    body += ['<w:p><w:r><w:br w:type="page"/></w:r></w:p>']
    seen = set()
    for line in draft.splitlines():
        heading = re.fullmatch(r"(#{1,3})\s+(.+)", line)
        if heading:
            body.append(paragraph(heading[2], f"Heading{len(heading[1])}"))
            continue
        marks = []
        for entry in entries:
            if entry.casefold() in line.casefold():
                sanitized = entry.replace('"', "'").replace("\\", "/")
                marks.append(field('TA \\c 1 \\s "' + sanitized + '" \\l "' + sanitized + '"', ""))
                seen.add(entry)
        body.append(paragraph(line, extra="".join(marks)))
    if set(entries) - seen:
        warning.append("Some supplied authorities did not match the draft verbatim and need page references.")
    body += [paragraph("Certificate of Service", "Heading1")]
    body += [paragraph(line) for line in (service or "[Service details not provided]").splitlines()]
    body += ['<w:sectPr><w:footerReference w:type="default" r:id="rId3"/><w:pgSz w:w="12240" w:h="15840"/>'
             '<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720" w:gutter="0"/></w:sectPr>']
    font = '<w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:eastAsia="Times New Roman" w:cs="Times New Roman"/><w:sz w:val="26"/><w:szCs w:val="26"/>'
    styles = f'<w:styles xmlns:w="{NS}"><w:docDefaults><w:rPrDefault><w:rPr>{font}</w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:line="480" w:lineRule="auto" w:after="0"/></w:pPr></w:pPrDefault></w:docDefaults>'
    for i, name in enumerate(("Front", "Heading1", "Heading2", "Heading3")):
        styles += (f'<w:style w:type="paragraph" w:styleId="{name}"><w:name w:val="{name}"/>'
                   '<w:pPr><w:keepNext/>' + (f'<w:outlineLvl w:val="{i-1}"/>' if i else "") +
                   '</w:pPr><w:rPr><w:b/></w:rPr></w:style>')
    styles += "</w:styles>"
    pkg = "http://schemas.openxmlformats.org/package/2006"
    rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    types = "application/vnd.openxmlformats-officedocument.wordprocessingml."
    parts = {
        "[Content_Types].xml": f'<Types xmlns="{pkg}/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="{types}document.main+xml"/><Override PartName="/word/styles.xml" ContentType="{types}styles+xml"/><Override PartName="/word/settings.xml" ContentType="{types}settings+xml"/><Override PartName="/word/footer.xml" ContentType="{types}footer+xml"/></Types>',
        "_rels/.rels": f'<Relationships xmlns="{pkg}/relationships"><Relationship Id="rId1" Type="{rel}/officeDocument" Target="word/document.xml"/></Relationships>',
        "word/_rels/document.xml.rels": f'<Relationships xmlns="{pkg}/relationships"><Relationship Id="rId1" Type="{rel}/styles" Target="styles.xml"/><Relationship Id="rId2" Type="{rel}/settings" Target="settings.xml"/><Relationship Id="rId3" Type="{rel}/footer" Target="footer.xml"/></Relationships>',
        "word/document.xml": f'<w:document xmlns:w="{NS}" xmlns:r="{rel}"><w:body>{"".join(body)}</w:body></w:document>',
        "word/styles.xml": styles,
        "word/settings.xml": f'<w:settings xmlns:w="{NS}"><w:updateFields w:val="true"/></w:settings>',
        "word/footer.xml": f'<w:ftr xmlns:w="{NS}">' + paragraph("", align="center", extra=field("PAGE", run("1"))) + '</w:ftr>',
    }
    with zipfile.ZipFile(docx, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, part in parts.items():
            ET.fromstring(part)
            archive.writestr(name, '<?xml version="1.0" encoding="UTF-8"?>' + part)
    paths = []
    if fmt in ("pdf", "both"):
        office = shutil.which("libreoffice") or shutil.which("soffice")
        if office:
            try:
                subprocess.run([office, f"-env:UserInstallation={(work / 'lo').as_uri()}",
                                "--headless", "--convert-to", "pdf", "--outdir", str(work), str(docx)],
                               check=True, timeout=90, capture_output=True)
                pdf = docx.with_suffix(".pdf")
                if pdf.is_file():
                    paths.append(str(shutil.move(pdf, destination / pdf.name)))
            except (OSError, subprocess.SubprocessError):
                pass
        if not paths:
            warning.append("PDF conversion unavailable; retained editable DOCX. Install LibreOffice Writer in the Linux sandbox for PDF.")
        else:
            warning.append("Review PDF fonts, tables and pagination after conversion; availability depends on the sandbox's installed fonts.")
    if fmt != "pdf" or not paths:
        paths.append(str(shutil.move(docx, destination / docx.name)))
    return {"files": paths, "notes": warning, "table_fields": "Update TOC and TOA fields in the editor before filing."}
