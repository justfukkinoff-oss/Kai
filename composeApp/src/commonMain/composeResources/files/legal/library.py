import array
import base64
import hashlib
import json
import math
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from collections import Counter, defaultdict
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path("/root/.kai/library")
IDENT = re.compile(r"(?i)\b(?:\d{1,4}\s+(?:u\.s\.c\.?|u\.s\.|f\.(?:\s*supp\.)?(?:\s*[234]d)?|s\.\s*ct\.)\s*§*\s*\d+[a-z]*(?:\([a-z0-9]+\))*|\d{1,4}:\d{2}-[a-z]{1,5}-\d+|[a-z]{2,12}[-_]\d+[a-z0-9_-]*|[A-Z]{2,12}\d{4,}|\d{1,4}:\d{1,3}(?:[-–]\d{1,3})?)")
BATES = re.compile(r"(?im)^\s*(?:Bates(?:\s+(?:no\.?|number))?[: ]+)?([a-z][a-z0-9_-]{1,22}[-_ ]?\d{3,})(?:\s*)$")
ECF = re.compile(r"(?i)\b(?:ECF|Document|Doc\.)\s*(?:No\.\s*)?#?\s*(\d+(?:-\d+)?)")
EXHIBIT = re.compile(r"(?i)\b(?:Exhibit|Ex\.)\s+([a-z0-9-]+)")
NUMBERED = re.compile(r"^\s*(\d{1,3})\s+(\S.*)")
HEADING = re.compile(r"(?i)^(?:#{1,6}\s+|(?:[IVX]+|[A-Z]|\d+)[.)]\s+|(?:grounds? for relief|statement of|argument|findings|conclusion)\b)")
SPEAKER = re.compile(r"(?i)^\s*(Q[.:]|A[.:]|THE COURT:|(?:MR|MS|MRS)\.\s+[A-Z-]+:)")


def connect():
    ROOT.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(ROOT / "index.db", timeout=20)
    db.row_factory = sqlite3.Row
    db.executescript("""
      PRAGMA journal_mode=WAL;
      PRAGMA foreign_keys=ON;
      CREATE TABLE IF NOT EXISTS matters(id TEXT PRIMARY KEY, name TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS docs(
        id TEXT PRIMARY KEY, scope TEXT NOT NULL, title TEXT, origin TEXT, hash TEXT,
        doc_type TEXT, court TEXT, date TEXT, docket TEXT, description TEXT, UNIQUE(scope,hash));
      CREATE TABLE IF NOT EXISTS pages(
        doc TEXT REFERENCES docs(id) ON DELETE CASCADE, number INTEGER, raw TEXT, method TEXT,
        printed TEXT, bates TEXT, ecf TEXT, exhibit TEXT, PRIMARY KEY(doc,number));
      CREATE TABLE IF NOT EXISTS chunks(
        id INTEGER PRIMARY KEY, doc TEXT REFERENCES docs(id) ON DELETE CASCADE,
        page INTEGER, start INTEGER, end INTEGER, line_start INTEGER, line_end INTEGER,
        speaker TEXT, heading TEXT, text TEXT);
      CREATE TABLE IF NOT EXISTS keys(chunk INTEGER REFERENCES chunks(id) ON DELETE CASCADE, key TEXT,
        PRIMARY KEY(key,chunk));
      CREATE TABLE IF NOT EXISTS vectors(chunk INTEGER REFERENCES chunks(id) ON DELETE CASCADE,
        route TEXT, value BLOB, PRIMARY KEY(chunk,route));
      CREATE TABLE IF NOT EXISTS doc_sources(doc TEXT PRIMARY KEY REFERENCES docs(id) ON DELETE CASCADE,
        url TEXT NOT NULL, cache_id TEXT UNIQUE);
      CREATE INDEX IF NOT EXISTS chunks_by_doc ON chunks(doc,page);
      CREATE INDEX IF NOT EXISTS vectors_by_route ON vectors(route);
      CREATE VIRTUAL TABLE IF NOT EXISTS fts USING fts5(text,heading,tokenize='porter unicode61');
      CREATE TRIGGER IF NOT EXISTS fts_insert AFTER INSERT ON chunks BEGIN
        INSERT INTO fts(rowid,text,heading) VALUES(new.id,new.text,new.heading); END;
      CREATE TRIGGER IF NOT EXISTS fts_delete AFTER DELETE ON chunks BEGIN
        DELETE FROM fts WHERE rowid=old.id; END;
    """)
    return db


def result(**data):
    encoded = json.dumps(data, ensure_ascii=True)
    if len(sys.argv) > 2:
        Path(sys.argv[2]).write_text(encoded, encoding="utf-8")
    else:
        print("KAI_LIBRARY " + encoded, flush=True)


def progress(label):
    print("KAI_PROGRESS " + label, flush=True)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *unused):
        return None


def substitute(node, variables):
    if isinstance(node, list):
        return [substitute(value, variables) for value in node]
    if isinstance(node, dict):
        return {name: substitute(value, variables) for name, value in node.items()}
    if not isinstance(node, str):
        return node
    exact = re.fullmatch(r"\{\{(\w+)\}\}", node)
    if exact:
        if exact[1] not in variables:
            raise ValueError("Unknown request template variable")
        return variables[exact[1]]
    return re.sub(r"\{\{(\w+)\}\}", lambda m: str(variables.get(m[1], m[0])), node)


def response_value(data, route, default):
    path = route.get("responsePath", "").strip()
    current = data
    for field in (path or default).split(".") if (path or default) != "$" else []:
        current = current[int(field)] if isinstance(current, list) else current[field]
    return current


def post(route, suffix, body, variables=None):
    endpoint = route["url"].replace("{{model}}", urllib.parse.quote(route.get("model", ""), safe=""))
    parts = urllib.parse.urlsplit(endpoint)
    if parts.scheme != "https" and not (parts.scheme == "http" and parts.hostname in ("localhost", "127.0.0.1", "::1")):
        raise ValueError("Choose an HTTPS endpoint or localhost")
    if parts.username or parts.password:
        raise ValueError("Enter the credential separately from the endpoint")
    if parts.fragment:
        raise ValueError("API endpoint cannot contain a fragment")
    path = parts.path.rstrip("/")
    url = endpoint if route.get("exactUrl") else urllib.parse.urlunsplit(parts._replace(
        path=path if path.endswith("/" + suffix) else path + "/" + suffix))
    headers = {"Content-Type": "application/json"}
    if route.get("key"):
        headers["Authorization"] = "Bearer " + route["key"]
    for line in route.get("headers", "").splitlines():
        if not line.strip():
            continue
        name, separator, value = line.partition(":")
        name = name.strip()
        if not separator or not re.fullmatch(r"[!#$%&'*+.^_`|~0-9A-Za-z-]+", name) or name.lower() in ("host", "content-length", "transfer-encoding"):
            raise ValueError("Invalid API header")
        headers = {k: v for k, v in headers.items() if k.casefold() != name.casefold()}
        headers[name] = value.strip().replace("{{key}}", route.get("key", ""))
    if route.get("template"):
        body = substitute(json.loads(route["template"]), dict(variables or {}, model=route.get("model", ""), body=body))
    method = route.get("method", "POST")
    if method not in ("POST", "PUT", "PATCH"):
        raise ValueError("Unsupported HTTP method")
    request = urllib.request.Request(url, data=json.dumps(body).encode(), headers=headers, method=method)
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=35) as response:
            return json.loads(response.read(12_000_000))
    except urllib.error.HTTPError as exc:
        raise ValueError(f"Model endpoint returned HTTP {exc.code}") from None
    except (urllib.error.URLError, OSError):
        raise ValueError("Could not connect to model endpoint") from None


def normalized_vector(values):
    if not values or any(not math.isfinite(v) for v in values):
        raise ValueError("Invalid embedding")
    norm = math.sqrt(sum(x*x for x in values))
    if not norm:
        raise ValueError("Empty embedding")
    return array.array("f", (x/norm for x in values))


def embed(route, values, role):
    body = {"model": route["model"], "input": values, "encoding_format": "float"}
    if route.get("format") == "nvidia" or "nvidia.com" in (urllib.parse.urlsplit(route["url"]).hostname or ""):
        body.update(input_type=role, truncate="END")
    data = post(route, "embeddings", body, {"input": values, "input_type": role})
    rows = response_value(data, route, "data")
    if rows and isinstance(rows[0], (float, int)):
        rows = [rows]
    if rows and isinstance(rows[0], dict):
        pairs = sorted((row.get("index", i), row["embedding"]) for i, row in enumerate(rows))
    else:
        pairs = list(enumerate(rows))
    if [index for index, _ in pairs] != list(range(len(values))):
        raise ValueError("Embedding response did not cover every input")
    return [normalized_vector(vector) for _, vector in pairs]


def fingerprint(route):
    if not any(route.get(key) for key in ("exactUrl", "template", "responsePath")) and route.get("method", "POST") == "POST":
        return hashlib.sha256((route["url"].rstrip("/") + "\n" + route.get("model", "") + "\n" + route.get("format", "") + "\nraw-v1").encode()).hexdigest()
    fields = ("url", "model", "format", "exactUrl", "method", "template", "responsePath")
    return hashlib.sha256(json.dumps({name: route.get(name, "") for name in fields}, sort_keys=True).encode()).hexdigest()


def dot(a, b):
    return sum(x*y for x, y in zip(a, b))


def rerank(route, query, passages):
    variables = {"query": query, "passages": passages, "top_n": len(passages)}
    if route.get("format") == "nvidia":
        body = {"model": route["model"], "query": {"text": query},
                "passages": [{"text": x} for x in passages], "truncate": "END"}
        rows = response_value(post(route, "reranking", body, variables), route, "rankings")
    else:
        rows = response_value(post(route, "rerank", {"model": route["model"], "query": query,
                              "documents": passages, "top_n": len(passages)}, variables), route, "results")
    return [(row.get("index", i), float(row.get("relevance_score", row.get("logit", row.get("score")))))
            if isinstance(row, dict) else (i, float(row)) for i, row in enumerate(rows)]


def run(args):
    return subprocess.run(args, check=True, capture_output=True, timeout=60).stdout


def zip_text(path):
    with zipfile.ZipFile(path) as doc:
        names = [r for r in doc.infolist() if not r.is_dir()]
        if sum(n.file_size for n in names) > 120_000_000:
            raise ValueError("Expanded document is too large")
        targets = [n for n in names if n.filename in ("word/document.xml", "word/footnotes.xml", "word/endnotes.xml")
                   or re.fullmatch(r"ppt/slides/slide\d+\.xml", n.filename)
                   or n.filename.lower().endswith((".xhtml", ".html", ".htm"))]
        for target in targets:
            text = ET.fromstring(doc.read(target))
            paras = [n for n in text.iter() if n.tag.split("}")[-1] == "p"]
            yield "\n".join("".join(p.itertext()) for p in paras) if paras else " ".join(text.itertext())


def vision(route, image, photo=False):
    suffix = image.suffix.lower()
    mime = {".jpg": "jpeg", ".jpeg": "jpeg", ".webp": "webp", ".tif": "tiff", ".tiff": "tiff"}.get(suffix, "png")
    data = base64.b64encode(image.read_bytes()).decode()
    cue = ("Describe observable contents of this photo. Do not infer identity or legal meaning."
           if photo else "Transcribe only. Preserve exact line breaks, line numbers, speakers, Bates labels, ECF headers and exhibit stamps; mark unclear text [illegible], never invent.")
    body = {"model": route["model"], "messages": [{"role": "user", "content": [
        {"type": "text", "text": cue}, {"type": "image_url", "image_url": {"url": f"data:image/{mime};base64,{data}"}}]}]}
    variables = {"prompt": cue, "image_base64": data, "mime_type": f"image/{mime}", "image_url": f"data:image/{mime};base64,{data}"}
    return response_value(post(route, "chat/completions", body, variables), route, "choices.0.message.content").strip()


def ocr_page(path, routes, photo=False):
    if photo:
        return (vision(routes["photo"], path, True), "photo-description") if routes.get("photo") else ("", "needs_photo_model")
    if routes.get("ocr"):
        try:
            raw, method = vision(routes["ocr"], path), "vision"
        except Exception:
            if not shutil.which("tesseract"):
                return "", "needs_ocr"
            raw, method = run(["tesseract", str(path), "stdout"]).decode("utf-8", "replace"), "tesseract-fallback"
    elif shutil.which("tesseract"):
        raw, method = run(["tesseract", str(path), "stdout"]).decode("utf-8", "replace"), "tesseract"
    else:
        return "", "needs_ocr"
    return raw, method


def load_pages(path, routes, work, photo, force_ocr=False):
    extension = path.suffix.lower()
    if extension == ".pdf":
        if not shutil.which("pdftotext"):
            raise ValueError("Install PDF tools from Legal Library → Set up sandbox")
        texts = run(["pdftotext", "-layout", str(path), "-"]).decode("utf-8", "replace").split("\f")
        if texts and not texts[-1].strip():
            texts.pop()
        for page, raw in enumerate(texts, 1):
            if raw.strip() and not force_ocr:
                yield page, raw, "pdf-text"
                continue
            progress(f"OCR page {page}/{len(texts)}")
            image = work / f"page-{page}"
            png = image.with_suffix(".png")
            try:
                run(["pdftoppm", "-f", str(page), "-l", str(page), "-r", "180", "-singlefile", "-png", str(path), str(image)])
                transcription, method = ocr_page(png, routes)
                yield (page, transcription, method) if transcription.strip() else (page, raw, "pdf-text-ocr-unavailable" if raw.strip() else "needs_ocr")
            except (OSError, subprocess.SubprocessError, ValueError):
                progress(f"OCR unavailable for page {page}; continuing")
                yield page, raw, ("pdf-text-ocr-unavailable" if raw.strip() else "needs_ocr")
            finally:
                png.unlink(missing_ok=True)
    elif extension in (".jpg", ".jpeg", ".png", ".webp", ".tiff", ".tif"):
        yield (1, *ocr_page(path, routes, photo))
    elif extension in (".docx", ".pptx", ".epub"):
        for page, text in enumerate(zip_text(path), 1):
            yield page, text, "structured-text"
    else:
        for page, text in enumerate(path.read_bytes().decode("utf-8", "replace").split("\f"), 1):
            yield page, text, "text"


def units(raw, transcript):
    offset, start, length, quote = 0, 0, 0, False
    for line in raw.splitlines(keepends=True):
        if length and not quote and (not line.strip() or HEADING.match(line.strip()) or
                                      (transcript and NUMBERED.match(line))):
            yield start, offset
            start, length = offset, 0
        quote = (quote != bool(line.count('"') % 2)) if '“' not in line and '”' not in line else line.rfind("“") > line.rfind("”")
        offset += len(line)
        length += len(line)
        if length > 3000 and not quote and re.search(r"[.!?][”\"]?\s*$", line):
            yield start, offset
            start, length = offset, 0
    if offset > start:
        yield start, offset


def chunks(raw, route=None):
    transcript = len(re.findall(r"(?m)^\s*\d{1,3}\s+\S", raw)) > 3
    spans = [(s, e) for s, e in units(raw, transcript) if raw[s:e].strip()]
    vectors = None
    if route and 2 < len(spans) <= 40 and len(raw) <= 20000:
        try:
            vectors = embed(route, [raw[s:e].strip()[:2500] for s, e in spans], "passage")
        except Exception:
            pass  # Structure-first chunks still support lexical retrieval.
    start, end, heading, speaker, first_line, last_line = None, 0, "", "", None, None
    for i, (s, e) in enumerate(spans):
        text = raw[s:e].strip()
        is_heading = bool(HEADING.match(text.split("\n")[0])) and not transcript
        line = NUMBERED.match(text) if transcript else None
        line_no = int(line[1]) if line else None
        new_speaker = SPEAKER.match(line[2] if line else text)
        boundary = (is_heading or (new_speaker and speaker and new_speaker[1] != speaker) or
                    (vectors and i and len(vectors[i - 1]) == len(vectors[i]) and dot(vectors[i-1], vectors[i]) < .49))
        if start is not None and (e - start > 2100 or (boundary and (end - start >= 350 or is_heading or new_speaker))):
            yield start, end, heading, speaker, first_line, last_line
            start, first_line, last_line = None, None, None
        if start is None:
            start, first_line = s, line_no
        if is_heading:
            heading = text.split("\n", 1)[0][:160]
        if new_speaker:
            speaker = new_speaker[1]
        if line_no is not None:
            last_line = line_no
        end = e
    if start is not None:
        yield start, end, heading, speaker, first_line, last_line


def key(value):
    return re.sub(r"[\s.]+", "", value.casefold())


def embed_missing(db, doc, route):
    if not route:
        return "No embedding model selected"
    tag = fingerprint(route)
    pending = db.execute("""SELECT c.id,c.heading,c.text FROM chunks c WHERE doc=?
        AND NOT EXISTS(SELECT 1 FROM vectors v WHERE v.chunk=c.id AND v.route=?) ORDER BY id""", (doc, tag)).fetchall()
    for offset in range(0, len(pending), 12):
        batch = pending[offset:offset+12]
        progress(f"Embedding {offset+1}–{offset+len(batch)} of {len(pending)} passages")
        values = embed(route, [(row["heading"] + "\n" + row["text"])[:5000] for row in batch], "passage")
        with db:
            db.executemany("INSERT OR REPLACE INTO vectors VALUES(?,?,?)",
                           [(r["id"], tag, v.tobytes()) for r, v in zip(batch, values)])
    return "Embedding complete"


def ingest(db, request, scope, routes, work):
    src = Path(request["path"])
    if not src.is_file() or src.stat().st_size > 80_000_000:
        raise ValueError("Choose a readable file of at most 80 MB")
    title = request.get("title") or src.name
    digest_builder = hashlib.sha256()
    with src.open("rb") as stream:
        for block in iter(lambda: stream.read(256 * 1024), b""):
            digest_builder.update(block)
    digest = digest_builder.hexdigest()
    identifier = hashlib.sha256((scope + "\n" + digest).encode()).hexdigest()[:32]
    suffix = Path(title).suffix.lower()
    if not re.fullmatch(r"\.[a-z0-9]{1,8}", suffix):
        suffix = ".txt"
    original = ROOT / "originals" / (identifier + suffix)
    original.parent.mkdir(exist_ok=True)
    existing = db.execute("SELECT * FROM docs WHERE id=?", (identifier,)).fetchone()
    if existing and not (request.get("reindex") or request.get("force_ocr")):
        message = embed_missing(db, identifier, routes.get("embedding")) if request.get("embed", True) else "Already indexed"
        return {"document": identifier, "title": existing["title"], "path": existing["origin"], "status": message, "duplicate": True}
    if not original.exists():
        shutil.copyfile(src, original)
    pages = list(load_pages(original, routes, work, bool(request.get("photo")) or bool(existing and
        db.execute("SELECT 1 FROM pages WHERE doc=? AND method IN ('photo-description','needs_photo_model')", (identifier,)).fetchone()),
        bool(request.get("force_ocr"))))
    if not pages:
        raise ValueError("No readable pages")
    progress("Building keyword and citation index")
    with db:
        db.execute("DELETE FROM docs WHERE id=?", (identifier,))
        db.execute("INSERT INTO docs VALUES (?,?,?,?,?,?,?,?,?,?)",
                   (identifier, scope, title, str(original), digest, request.get("doc_type"), request.get("court"),
                    request.get("date"), request.get("docket"), request.get("description")))
        total = 0
        for page, raw, method in pages:
            bates, ecf, exhibit = BATES.search(raw), ECF.search(raw), EXHIBIT.search(raw)
            printed = re.search(r"(?im)^\s*(?:page\s+)?(\d{1,5})\s*$", raw)
            val = lambda match: match[1].strip() if match else ""
            db.execute("INSERT INTO pages VALUES(?,?,?,?,?,?,?,?)", (identifier,page,raw,method,val(printed),val(bates),val(ecf),val(exhibit)))
            indexed = raw
            cleanup = routes.get("cleanup") if method.startswith(("vision", "tesseract")) else None
            if cleanup and raw.strip() and len(raw) < 14000:
                try:
                    cue = "Correct only obvious OCR errors. Return one line per original line, unchanged order and count. Preserve source identifiers and mark unclear words [illegible]."
                    cleaned = response_value(post(cleanup, "chat/completions", {"model": cleanup["model"], "messages": [
                        {"role": "system", "content": cue},
                        {"role": "user", "content": raw},
                    ]}, {"text": raw, "prompt": cue}), cleanup, "choices.0.message.content")
                    original_lines, cleaned_lines = raw.splitlines(keepends=True), cleaned.splitlines()
                    if len(original_lines) == len(cleaned_lines):
                        # Preserve raw offsets and line positions. Extra predicted characters are not taken as evidence.
                        indexed = "".join(fixed[:len(line.rstrip('\r\n'))].ljust(len(line.rstrip('\r\n'))) + line[len(line.rstrip('\r\n')):]
                                          for line, fixed in zip(original_lines, cleaned_lines))
                except Exception:
                    progress(f"OCR cleanup unavailable for page {page}; retained raw transcription")
            for start, end, heading, speaker, first, last in chunks(raw, routes.get("embedding") if request.get("embed", True) else None):
                text = raw[start:end]
                cid = db.execute("INSERT INTO chunks(doc,page,start,end,line_start,line_end,speaker,heading,text) VALUES(?,?,?,?,?,?,?,?,?)",
                                 (identifier,page,start,end,first,last,speaker,heading,text)).lastrowid
                values = [x[0] for x in IDENT.finditer(text)] + [val(bates),val(ecf),val(exhibit),request.get("docket","")]
                db.executemany("INSERT OR IGNORE INTO keys VALUES(?,?)", [(cid,key(x)) for x in values if x])
                if indexed != raw:
                    db.execute("UPDATE fts SET text=? WHERE rowid=?", (text + "\n" + indexed[start:end], cid))
                total += 1
    status = "Keyword index ready"
    if request.get("embed", True):
        try:
            status = embed_missing(db, identifier, routes.get("embedding"))
        except Exception as exc:
            status = f"Keyword index ready; embedding incomplete ({exc})"
    return {"document": identifier, "title": title, "pages": len(pages), "passages": total, "path": str(original), "status": status,
            "needs_ocr": [n for n, raw, _ in pages if not raw.strip()]}


def locate(request):
    where, params = ["d.scope=?"], [request["scope"]]
    for name, column in (("document","d.id"),("doc_type","d.doc_type"),("court","d.court"),
                         ("docket","d.docket"),("bates","p.bates"),("ecf","p.ecf"),("exhibit","p.exhibit")):
        if request.get(name):
            where.append(f"{column}=? COLLATE NOCASE")
            params.append(request[name])
    for name, clause in (("date_from","d.date>=?"),("date_to","d.date<=?"),("page_from","p.number>=?"),
                         ("page_to","p.number<=?"),("line_from","c.line_end>=?"),("line_to","c.line_start<=?")):
        if request.get(name) is not None:
            where.append(clause)
            params.append(request[name])
    return " AND ".join(where), params


def sync_public_opinions(db, work):
    cache = Path(os.environ.get("KAI_LAW_LIBRARY", Path.home() / ".local/share/kai/law"))
    opinions = cache / "opinions"
    if not opinions.is_dir():
        return
    linked = {row[0] for row in db.execute("SELECT cache_id FROM doc_sources WHERE cache_id IS NOT NULL")}
    candidates = [(p.stem, p) for p in opinions.glob("*.json") if p.stem.isdigit() and p.stem not in linked]
    if not candidates:
        return
    titles = {}
    for file in (cache / "reporters").glob("*.json"):
        try:
            item = json.loads(file.read_text(encoding="utf-8"))
            for opinion in item.get("sub_opinions") or []:
                found = re.search(r"(?:^|/)opinions/(\d+)/?$", str(opinion))
                identifier = found[1] if found else str(opinion)
                titles[identifier] = f"{item.get('case_name') or 'CourtListener opinion'}, {file.stem}"
        except (ValueError, OSError, TypeError, AttributeError):
            continue
    completed = 0
    for identifier, file in sorted(candidates, key=lambda pair: (pair[0] not in titles, pair[0])):
        if completed >= 8:
            break
        try:
            item = json.loads(file.read_text(encoding="utf-8"))
            link = item.get("link") or ""
            if not re.fullmatch(r"https://www\.courtlistener\.com/opinion/\d+/", link) or not item.get("text", "").strip():
                continue
            text = file_temp = work / "public-opinion.txt"
            text.write_text(item["text"], encoding="utf-8")
            response = ingest(db, {"path": str(file_temp), "title": titles.get(identifier, f"CourtListener opinion {identifier}") + ".txt",
                                   "doc_type": "opinion", "embed": False}, "law", {}, work)
            with db:
                db.execute("INSERT OR IGNORE INTO doc_sources VALUES(?,?,?)", (response["document"], link, identifier))
            completed += 1
        except (OSError, ValueError, TypeError, AttributeError):
            continue


def search(db, request, routes):
    query = request.get("query", "").strip()
    if not query:
        raise ValueError("Enter a search query")
    top = min(12, max(1, int(request.get("limit", 6))))
    scope, values = locate(request)
    join = " FROM chunks c JOIN docs d ON d.id=c.doc JOIN pages p ON p.doc=c.doc AND p.number=c.page "
    mode = request.get("mode", "hybrid")
    ranks, notes, rerank_used = {}, [], False
    words = re.findall(r"\w+", query, re.UNICODE)[:30]
    needle = [key(x[0]) for x in IDENT.finditer(query)]
    if request.get("bates"):
        needle.append(key(request["bates"]))
    if needle and mode in ("hybrid", "exact", "citation"):
        marks = ",".join("?" for _ in needle)
        ranks["identifier"] = [r[0] for r in db.execute(f"SELECT DISTINCT c.id {join} JOIN keys k ON k.chunk=c.id WHERE {scope} AND k.key IN ({marks}) LIMIT 100", values+needle)]
    phrases = re.findall(r'["“]([^"”]+)["”]', query) or ([query] if mode == "exact" else [])
    if phrases and mode in ("hybrid", "exact"):
        # Verify actual substring against original pages including chunk overlaps.
        exact = set()
        for phrase in phrases:
            phrase = phrase.casefold()
            for row in db.execute(f"SELECT c.id,c.start,c.end,p.raw {join} WHERE {scope} AND instr(lower(p.raw),lower(?))>0 LIMIT 800", values+[phrase]):
                if phrase in row["raw"][row["start"]:row["end"]].casefold():
                    exact.add(row["id"])
        ranks["exact"] = sorted(exact)
    if words and mode in ("hybrid", "keyword"):
        expr = " OR ".join(f'"{word}"' for word in words)
        ranks["bm25"] = [r[0] for r in db.execute(f"SELECT c.id {join} JOIN fts ON fts.rowid=c.id WHERE {scope} AND fts MATCH ? ORDER BY bm25(fts,1.0,0.4),c.id LIMIT 100", values+[expr])]
    if mode in ("hybrid", "semantic"):
        route = routes.get("embedding")
        if route:
            try:
                query_vector = embed(route,[query],"query")[0]
                scored = []
                for row in db.execute(f"SELECT c.id,v.value {join} JOIN vectors v ON v.chunk=c.id WHERE {scope} AND v.route=?", values+[fingerprint(route)]):
                    v = array.array("f")
                    v.frombytes(row["value"])
                    if len(v) == len(query_vector):
                        scored.append((dot(query_vector,v),row["id"]))
                ranks["semantic"] = [cid for _,cid in sorted(scored,reverse=True)[:100]]
                if not scored:
                    notes.append("No compatible embeddings in this scope")
            except Exception as exc:
                notes.append(f"Semantic channel unavailable: {exc}")
        else:
            notes.append("No embedding model selected")
    fused, channels = defaultdict(float), defaultdict(list)
    for name, ranking in ranks.items():
        for ordinal, identifier in enumerate(ranking, 1):
            fused[identifier] += (1.5 if name in ("exact","identifier") else 1.0)/(60+ordinal)
            channels[identifier].append(name)
    ordered = sorted(fused,key=lambda cid:(not any(x in channels[cid] for x in ("exact","identifier")),-fused[cid],cid))[:36]
    fields = "c.*,d.title,d.origin,d.doc_type,d.court,d.docket,p.printed,p.bates,p.ecf,p.exhibit,p.method,(SELECT url FROM doc_sources WHERE doc=d.id) AS source_url"
    rows = {}
    if ordered:
        marks = ",".join("?" for _ in ordered)
        rows = {r["id"]:dict(r) for r in db.execute(f"SELECT {fields} {join} WHERE {scope} AND c.id IN ({marks})", values+ordered)}
    if len(ordered)>1 and mode in ("hybrid","semantic","keyword"):
        if routes.get("rerank"):
            try:
                scores = {ordered[i]:score for i,score in rerank(routes["rerank"],query,[rows[i]["text"][:3200] for i in ordered]) if 0<=i<len(ordered) and math.isfinite(score)}
                if scores:
                    ordered.sort(key=lambda i:(not any(x in channels[i] for x in ("exact","identifier")),-scores.get(i,-math.inf),-fused[i],i))
                    rerank_used = True
            except Exception as exc:
                notes.append(f"Cross-encoder unavailable: {exc}")
        else:
            notes.append("No cross-encoder selected")
    hits = []
    for i in ordered[:top]:
        row = rows[i]
        row.update(matched_by=channels[i],score=fused[i],text=row["text"][:1800])
        hits.append(row)
    return {"hits":hits,"notes":notes,"channels":{k:len(v) for k,v in ranks.items()},"reranked":rerank_used}


def dispatch(db, r, work):
    action, scope, routes = r["action"], r.get("scope",""), r.get("routes",{})
    if action == "exports":
        return {"files": [{"name": f.name, "path": str(f)} for f in sorted((ROOT / "exports").glob("*")) if f.is_file()]}
    if action == "matters":
        return {"matters":[dict(row) for row in db.execute("SELECT * FROM matters ORDER BY name")]}
    if action == "matter_create":
        if not re.fullmatch(r"[0-9a-f-]{36}", scope):
            raise ValueError("Invalid matter ID")
        with db:
            db.execute("INSERT INTO matters VALUES(?,?)",(scope,r["name"][:120]))
        return {"id":scope}
    if scope not in ("law","code") and not db.execute("SELECT 1 FROM matters WHERE id=?",(scope,)).fetchone():
        raise ValueError("Choose a matter in Legal Library and bind this chat")
    if action == "ingest":
        return ingest(db,r,scope,routes,work)
    if action == "list":
        if scope == "law":
            sync_public_opinions(db, work)
        tag = fingerprint(routes["embedding"]) if routes.get("embedding") else ""
        return {"documents":[dict(row) for row in db.execute("""SELECT d.*, (SELECT count(*) FROM chunks c WHERE c.doc=d.id) passages,
            (SELECT count(*) FROM pages p WHERE p.doc=d.id AND length(trim(p.raw))=0) needs_ocr,
            (SELECT count(*) FROM chunks c JOIN vectors v ON c.id=v.chunk WHERE c.doc=d.id AND v.route=?) embedded,
            (SELECT url FROM doc_sources WHERE doc=d.id) source_url
            FROM docs d WHERE d.scope=? ORDER BY d.title""",(tag,scope))]}
    if action == "search":
        if scope == "law":
            sync_public_opinions(db, work)
        return search(db,r,routes)
    if action in ("read","quote","delete","reembed","reindex"):
        doc = db.execute("SELECT * FROM docs WHERE id=? AND scope=?",(r.get("document"),scope)).fetchone()
        if not doc:
            raise ValueError("Document not found in this collection")
        if action == "delete":
            with db:
                db.execute("DELETE FROM docs WHERE id=?",(doc["id"],))
            Path(doc["origin"]).unlink(missing_ok=True)
            return {"removed":doc["id"]}
        if action == "reembed":
            return {"status":embed_missing(db,doc["id"],routes.get("embedding"))}
        if action == "reindex":
            return ingest(db,dict(r,**{k:doc[k] for k in ("title","doc_type","court","date","docket","description")},path=doc["origin"],reindex=True),scope,routes,work)
        page = max(1,int(r.get("page",1)))
        row = db.execute("SELECT * FROM pages WHERE doc=? AND number=?",(doc["id"],page)).fetchone()
        if not row:
            raise ValueError("Page not found")
        info = dict(row)
        raw, offset = info.pop("raw"), max(0,int(r.get("offset",0)))
        info.update(path=doc["origin"],title=doc["title"])
        source = db.execute("SELECT url FROM doc_sources WHERE doc=?", (doc["id"],)).fetchone()
        if source:
            info["source_url"] = source[0]
        if action == "quote":
            quote = r.get("query","").strip()
            hit = raw.find(quote) if quote else -1
            return dict(info,exact=hit>=0,offset=hit,end=hit+len(quote) if hit>=0 else None)
        return dict(info,text=raw[offset:offset+8000],next_offset=offset+8000 if len(raw)>offset+8000 else None)
    raise ValueError("Unknown library operation")


if __name__ == "__main__":
    try:
        request_file = Path(sys.argv[1])
        payload = json.loads(request_file.read_text())
        request_file.unlink(missing_ok=True)
        if payload["action"] == "export":
            from filing import export
            result(**export(payload, ROOT / "exports", request_file.parent))
        else:
            result(**dispatch(connect(),payload,request_file.parent))
    except Exception as exc:
        result(error=f"{type(exc).__name__}: {exc}")
