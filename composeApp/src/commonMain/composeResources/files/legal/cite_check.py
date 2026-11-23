"""Local citation review. Only reporter citations go over the network."""

import argparse
import json
import os
import re
import sqlite3
import tempfile
import time
import unicodedata
from concurrent.futures import ThreadPoolExecutor, wait
from difflib import SequenceMatcher
from html.parser import HTMLParser
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


HOST = "https://www.courtlistener.com"
OPINION_PATH = re.compile(r"/api/rest/v4/opinions/([0-9]+)/")
PUBLIC_CITATION = re.compile(r"\d{1,8} [A-Za-z0-9. ]{1,30} \d{1,9}")
QUOTES = re.compile(r'“([^”\n]+)”|"([^"\n]+)"|((?:^>[^\n]+(?:\n|$))+)', re.MULTILINE)
RECORD_REFERENCES = re.compile(
    r"\b(?:ECF(?:\s+No\.)?\s*#?\s*\d+(?:-\d+)?(?:\s+at\s+\d+)?|"
    r"Bates\s+[A-Z0-9-]*\d[A-Z0-9-]*|"
    r"Ex(?:hibit|\.)\s+(?:[A-Z](?:-\d+)?|\d+[A-Z]?)|"
    r"Tr(?:anscript|\.)\s+\d+(?::\d+(?:[-–]\d+)?)?)\b",
    re.IGNORECASE,
)
TREATMENT = "subsequent treatment not verified"
TREATMENT_SIGNAL = re.compile(
    r"\b(?:overrul(?:e|ed|ing)|abrogat(?:e|ed|ing)|supersed(?:e|ed|ing)|"
    r"disapprov(?:e|ed|ing)|limited|no longer (?:controlling|good law))\b",
    re.IGNORECASE,
)


class ReviewError(Exception):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward the CourtListener token to another URL.


class OpinionText(HTMLParser):
    def __init__(self):
        super().__init__()
        self.parts = []

    def handle_data(self, data):
        self.parts.append(data)

    def handle_starttag(self, tag, attrs):
        if tag in {"p", "br", "div", "blockquote"}:
            self.parts.append(" ")


class CourtListener:
    def __init__(self, token, library, matches=None, matter=""):
        self.token = token
        self.library = library
        self.deadline = time.monotonic() + 22
        self.matches = matches or {}
        self.matter = matter

    def request(self, path, data=None):
        if not self.token:
            raise ReviewError("CourtListener token not configured")
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise ReviewError("lookup time limit reached")
        request = Request(HOST + path, data=data, headers={
            "Authorization": f"Token {self.token}", "Accept": "application/json",
        })
        try:
            with build_opener(NoRedirect()).open(request, timeout=min(5, remaining)) as response:
                raw = response.read(10_000_001)
            if len(raw) > 10_000_000:
                raise ReviewError("opinion response exceeded the size limit")
            return json.loads(raw)
        except HTTPError as exc:
            raise ReviewError(f"CourtListener HTTP {exc.code}") from exc
        except (URLError, TimeoutError, OSError, ValueError) as exc:
            raise ReviewError("CourtListener lookup unavailable") from exc

    def case(self, citation):
        if not PUBLIC_CITATION.fullmatch(citation):
            return {"reason": "unsupported citation format"}
        cite_file = self.library / "reporters" / (citation + ".json")
        cluster = self.matches.get(citation) or self.read_cache(cite_file)
        if cluster is None:
            results = self.request("/api/rest/v4/citation-lookup/", urlencode({"text": citation}).encode())
            if len(results) != 1 or results[0].get("status") != 200:
                return {"reason": "no unique CourtListener match"}
            clusters = results[0].get("clusters", [])
            if len(clusters) != 1:
                return {"reason": "ambiguous CourtListener match"}
            cluster = clusters[0]
            self.write_cache(cite_file, cluster)
        cluster_id = cluster.get("id")
        url = f"{HOST}/opinion/{cluster_id}/" if isinstance(cluster_id, int) else None
        path = cluster.get("absolute_url")
        if isinstance(path, str) and re.fullmatch(r"/opinion/[0-9]+/[A-Za-z0-9_-]+/", path):
            url = HOST + path
        return {"case": cluster.get("case_name"), "url": url,
                "opinions": cluster.get("sub_opinions") or [], "reason": None}

    def record(self, label, quote=None):
        index = Path("/root/.kai/library/index.db")
        if not self.matter or not index.is_file():
            return []
        match = re.fullmatch(r"(ECF(?:\s+No\.)?|Bates|Ex(?:hibit|\.)|Tr(?:anscript|\.))\s*#?\s*([\w-]+)(?:(?:\s+at\s+|:)(\d+(?:[-–]\d+)?))?", label, re.I)
        if not match:
            return []
        kind, tag, pin = match.groups()
        kind = kind.lower()
        column = "p.ecf" if kind.startswith("ecf") else "p.bates" if kind == "bates" else "p.exhibit"
        clauses = ["d.scope=?"]
        args = [self.matter]
        if kind.startswith("tr"):
            clauses.extend(["d.doc_type LIKE '%transcript%'", "p.printed=?"])
            args.append(tag)
            # A line citation needs line-aware confirmation, not simply a matching page.
            if pin:
                return []
        else:
            clauses.append(f"{column}=? COLLATE NOCASE")
            args.append(tag)
            if pin:
                clauses.append("p.printed=?")
                args.append(pin)
        try:
            with sqlite3.connect(f"file:{index}?mode=ro", uri=True, timeout=3) as db:
                rows = db.execute("SELECT d.title,p.number,p.method,p.raw FROM docs d JOIN pages p ON p.doc=d.id WHERE "
                                  + " AND ".join(clauses) + " LIMIT 50", args).fetchall()
                return [(row[0], row[1], row[2]) for row in rows if quote is None or quote in row[3]]
        except sqlite3.Error:
            return []

    def opinion(self, url):
        opinion_id = self.opinion_id(url)
        if opinion_id is None:
            return None
        path = f"/api/rest/v4/opinions/{opinion_id}/"
        saved = self.library / "opinions" / (opinion_id + ".json")
        opinion = self.read_cache(saved)
        if opinion is None:
            opinion = self.request(path)
            html = opinion.get("html_with_citations")
            if html:
                parser = OpinionText()
                parser.feed(html)
                full_text = "".join(parser.parts)
            else:
                full_text = opinion.get("plain_text", "")
            if not full_text.strip():
                return None
            cluster = urlsplit(opinion.get("cluster") or "")
            cluster_match = re.fullmatch(r"/api/rest/v4/clusters/([0-9]+)/", cluster.path)
            opinion = {"text": full_text, "type": opinion.get("type"), "source": HOST + path,
                       "link": f"{HOST}/opinion/{cluster_match[1]}/" if cluster_match else None}
            self.write_cache(saved, opinion)
        return opinion

    @staticmethod
    def opinion_id(url):
        if isinstance(url, int) and url > 0:
            return str(url)
        if not isinstance(url, str):
            return None
        uri = urlsplit(url)
        match = OPINION_PATH.fullmatch(uri.path)
        if match and (not uri.netloc or (uri.scheme == "https" and uri.netloc == "www.courtlistener.com")):
            return match[1]
        return None

    def treatment_leads(self, citation, match):
        """Return review leads, not a citator classification or a finding of good law."""
        cite_text = re.compile(r"\b" + r"\s+".join(map(re.escape, citation.split())) + r"\b", re.I)
        inspected, leads, seen = 0, [], set()
        for uri in match["opinions"][:2]:
            opinion_id = self.opinion_id(uri)
            if opinion_id is None:
                continue
            try:
                self.opinion(uri)  # Warm the local opinion cache even if the draft has no quotes.
            except ReviewError:
                pass
            graph = self.request(f"/api/rest/v4/opinions-cited/?cited_opinion={opinion_id}")
            for row in graph.get("results", [])[:5]:
                citing_id = self.opinion_id(row.get("citing_opinion"))
                if not citing_id or citing_id in seen:
                    continue
                seen.add(citing_id)
                opinion = self.opinion(int(citing_id))
                if not opinion:
                    continue
                inspected += 1
                text = normalized(opinion["text"])
                if any(TREATMENT_SIGNAL.search(text[max(0, cite.start() - 145):cite.end() + 75])
                       for cite in cite_text.finditer(text)):
                    leads.append((citing_id, opinion.get("link")))
        return inspected, leads

    @staticmethod
    def read_cache(file):
        try:
            return json.loads(file.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None

    @staticmethod
    def write_cache(file, payload):
        try:
            file.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=file.parent, delete=False) as tmp:
                tmp_name = tmp.name
                json.dump(payload, tmp, ensure_ascii=False)
            os.replace(tmp_name, file)
        except OSError:
            pass  # A read-only library must not turn a successful lookup into a failure.
        finally:
            if "tmp_name" in locals():
                try:
                    Path(tmp_name).unlink(missing_ok=True)
                except OSError:
                    pass


def parse_citations(text):
    from eyecite import get_citations, resolve_citations
    from eyecite.models import FullCaseCitation, FullLawCitation

    citations = get_citations(text)
    grouped = {}
    for cluster in resolve_citations(citations).values():
        full = next((entry for entry in cluster if isinstance(entry, FullCaseCitation)), None)
        if full is None:
            continue
        fields = [full.groups.get(key) for key in ("volume", "reporter", "page")]
        if all(fields):
            for citation in cluster:
                grouped[id(citation)] = " ".join(fields)
    results = []
    for cite in citations:
        start, end = cite.span()
        full_start, _ = cite.full_span()
        results.append({
            "start": start, "full_start": full_start, "end": end, "label": text[start:end],
            "reporter": grouped.get(id(cite)),
            "pin": getattr(cite.metadata, "pin_cite", None),
            "statute": isinstance(cite, FullLawCitation),
        })
    return results


def normalized(value):
    value = unicodedata.normalize("NFKC", value)
    value = value.translate(str.maketrans({"’": "'", "‘": "'", "“": '"', "”": '"'}))
    return " ".join(value.split())


def quote_match(quote, opinion):
    quote, opinion = normalized(quote), normalized(opinion)
    if quote in opinion:
        return "exact_text"
    words = quote.split()
    if len(words) < 4:
        return "not_found"
    # Similarity is only a lead. Limit work to nearby word anchors for long opinions.
    fuzzy_quote = quote.casefold()
    fuzzy_text = opinion.casefold()
    anchors = sorted(set(re.findall(r"\w{4,}", words[0] + " " + words[1])))
    for anchor in anchors:
        for index, match in enumerate(re.finditer(r"\b" + re.escape(anchor) + r"\b", fuzzy_text)):
            if index >= 80:
                break
            for start in (match.start(), max(0, match.start() - len(words[0]) - 1)):
                sample = fuzzy_text[start:start + len(fuzzy_quote)]
                if SequenceMatcher(None, fuzzy_quote, sample, autojunk=False).ratio() >= 0.86:
                    return "similar_only"
    return "not_found"


def review(text, client, parse=parse_citations):
    try:
        citations = parse(text)
    except ImportError:
        return {"issues": ["Citation check unavailable: install eyecite in the Linux sandbox."]}
    except Exception:
        return {"issues": ["Citation check could not parse this draft."]}
    issues = []
    reporters = list(dict.fromkeys(cite["reporter"] for cite in citations if cite["reporter"]))
    matches = {}
    if reporters:
        pool = ThreadPoolExecutor(max_workers=min(5, len(reporters)))
        futures = {pool.submit(client.case, identifier): identifier for identifier in reporters}
        done, pending = wait(futures, timeout=15)
        for future in done:
            try:
                matches[futures[future]] = future.result()
            except ReviewError as exc:
                matches[futures[future]] = {"reason": str(exc)}
        for future in pending:
            future.cancel()
        pool.shutdown(wait=False, cancel_futures=True)

    by_case = set()
    sources = {}
    for cite in citations:
        label = cite["reporter"] or cite["label"]
        if label in by_case:
            continue
        by_case.add(label)
        if cite["statute"]:
            issues.append(f"{label}: statutory text/version not checked.")
        elif cite["reporter"]:
            match = matches.get(label, {"reason": "lookup not completed"})
            if match["reason"]:
                issues.append(f"{label}: {match['reason']}.")
            else:
                sources[label] = match["url"]
                issues.append(f"{label}: {TREATMENT}.")
        else:
            issues.append(f"{label}: reference not resolved to an authority.")

    for cite in citations:
        if cite["pin"]:
            issues.append(f"{cite['label']} ({cite['pin']}): pinpoint not independently verified.")

    for record in RECORD_REFERENCES.finditer(text):
        matches_for_record = client.record(record.group()) if hasattr(client, "record") else []
        if len(matches_for_record) == 1:
            title, page, method = matches_for_record[0]
            issues.append(f"{record.group()}: identifier located in {title}, source page {page} ({method}); relevance not verified.")
        else:
            issues.append(f"{record.group()}: record reference not verified against a unique source page.")

    for number, match in enumerate(QUOTES.finditer(text), 1):
        quote = next(part for part in match.groups() if part is not None)
        quote = re.sub(r"^>\s?", "", quote, flags=re.MULTILINE).strip()
        if not quote:
            continue
        # Candidates must follow the quote within the citation sentence, not another paragraph.
        paragraph_end = text.find("\n\n", match.end())
        boundary = min(match.end() + 220, paragraph_end if paragraph_end >= 0 else len(text))
        following = [
            c for c in citations if match.end() <= c["start"] <= boundary
            and not re.search(r'[.!?](?:\s|"|“)+[A-Z]',
                              text[match.end():max(match.end(), c["full_start"])])
        ]
        relevant = {c["reporter"] for c in following}
        if len(relevant) != 1 or None in relevant:
            tail = text[match.end():boundary]
            refs = list(RECORD_REFERENCES.finditer(tail))
            if len(refs) == 1 and hasattr(client, "record") and not re.search(r'[.!?](?:\s|")+\w', tail[:refs[0].start()]):
                record_hits = client.record(refs[0].group(), quote)
                if len(record_hits) == 1:
                    title, page, method = record_hits[0]
                    issues.append(f"Quote {number}: exact source-text match in {title}, source page {page} ({method}); compare image if OCR.")
                    continue
            issues.append(f"Quote {number}: source attribution unresolved.")
            continue
        cite = next(iter(relevant))
        opinions = matches.get(cite, {}).get("opinions", [])
        best = "not_found"
        quote_label = f"Quote {number}"
        for uri in opinions:
            try:
                opinion = client.opinion(uri)
            except ReviewError:
                continue
            if not opinion:
                continue
            result = quote_match(quote, opinion["text"])
            if result == "exact_text":
                best = "exact_text"
                sources[quote_label] = matches[cite].get("url")
                break
            if result == "similar_only":
                best = result
                sources[quote_label] = matches[cite].get("url")
        if best == "exact_text":
            issues.append(f"Quote {number}: text matched {cite}; pinpoint and opinion role not verified.")
        elif best == "similar_only":
            issues.append(f"Quote {number}: similar wording found in {cite}; exact quote not verified.")
        else:
            issues.append(f"Quote {number}: not verified against {cite}.")

    verified = [(cite, match) for cite, match in matches.items() if not match["reason"]]
    if verified:
        with ThreadPoolExecutor(max_workers=min(4, len(verified))) as pool:
            pending = [(cite, pool.submit(client.treatment_leads, cite, match)) for cite, match in verified]
            for cite, future in pending:
                try:
                    count, leads = future.result()
                except ReviewError:
                    continue
                if count:
                    prior = f"{cite}: {TREATMENT}."
                    if prior in issues:
                        issues[issues.index(prior)] = f"{cite}: {TREATMENT}; scanned {count} later opinion(s)."
                for opinion_id, url in leads:
                    label = f"{cite} (citing opinion {opinion_id})"
                    sources[label] = url
                    issues.insert(0, f"{label}: possible negative-treatment language near the citation; review the opinion.")

    return {"issues": list(dict.fromkeys(issues)), "sources": sources, "citations": len(citations)}


def main():
    cli = argparse.ArgumentParser()
    cli.add_argument("draft", type=Path)
    cli.add_argument("--reporters", action="store_true")
    cli.add_argument("--options", type=Path)
    opts = cli.parse_args()
    library = Path(os.environ.get("KAI_LAW_LIBRARY", Path.home() / ".local/share/kai/law"))
    draft = opts.draft.read_text(encoding="utf-8")
    if opts.reporters:
        try:
            reporters = list(dict.fromkeys(c["reporter"] for c in parse_citations(draft) if c["reporter"]))
        except (ImportError, ValueError):
            reporters = []
        print("KAI_CITE_REPORTERS " + json.dumps(reporters))
        return
    options = {}
    if opts.options:
        options = json.loads(opts.options.read_text(encoding="utf-8"))
        opts.options.unlink(missing_ok=True)
    result = review(draft, CourtListener(options.get("token") or os.environ.get("COURTLISTENER_TOKEN"), library,
                                        options.get("mcp"), options.get("matter", "")))
    print("KAI_CITE_CHECK_RESULT " + json.dumps(result, ensure_ascii=True))


if __name__ == "__main__":
    main()
