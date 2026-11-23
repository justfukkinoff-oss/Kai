"""Local citation review. Only reporter citations go over the network."""

import argparse
import json
import os
import re
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
TREATMENT = "subsequent treatment not verified"


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
    def __init__(self, token, library):
        self.token = token
        self.library = library
        self.deadline = time.monotonic() + 22

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
        cluster = self.read_cache(cite_file)
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

    def opinion(self, url):
        uri = urlsplit(url)
        match = OPINION_PATH.fullmatch(uri.path)
        if not match or (uri.netloc and (uri.scheme != "https" or uri.netloc != "www.courtlistener.com")):
            return None
        saved = self.library / "opinions" / (match[1] + ".json")
        opinion = self.read_cache(saved)
        if opinion is None:
            opinion = self.request(uri.path)
            html = opinion.get("html_with_citations")
            if html:
                parser = OpinionText()
                parser.feed(html)
                full_text = "".join(parser.parts)
            else:
                full_text = opinion.get("plain_text", "")
            if not full_text.strip():
                return None
            opinion = {"text": full_text, "type": opinion.get("type"), "source": HOST + uri.path}
            self.write_cache(saved, opinion)
        return opinion

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
                Path(tmp_name).unlink(missing_ok=True)


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
        results.append({
            "start": start, "end": end, "label": text[start:end],
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

    for number, match in enumerate(QUOTES.finditer(text), 1):
        quote = next(part for part in match.groups() if part is not None)
        quote = re.sub(r"^>\s?", "", quote, flags=re.MULTILINE).strip()
        if not quote:
            continue
        # Candidates must follow the quote within the citation sentence, not another paragraph.
        paragraph_end = text.find("\n\n", match.end())
        boundary = min(match.end() + 220, paragraph_end if paragraph_end >= 0 else len(text))
        following = [c for c in citations if match.end() <= c["start"] <= boundary
                     and not re.search(r'[.!?](?:\s|"|“)+[A-Z]', text[match.end():c["start"]])]
        relevant = {c["reporter"] for c in following}
        if len(relevant) != 1 or None in relevant:
            issues.append(f"Quote {number}: source attribution unresolved.")
            continue
        cite = next(iter(relevant))
        opinions = matches.get(cite, {}).get("opinions", [])
        best = "not_found"
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
                break
            if result == "similar_only":
                best = result
        if best == "exact_text":
            issues.append(f"Quote {number}: text matched {cite}; pinpoint and opinion role not verified.")
        elif best == "similar_only":
            issues.append(f"Quote {number}: similar wording found in {cite}; exact quote not verified.")
        else:
            issues.append(f"Quote {number}: not verified against {cite}.")

    return {"issues": list(dict.fromkeys(issues)), "sources": sources, "citations": len(citations)}


def main():
    cli = argparse.ArgumentParser()
    cli.add_argument("draft", type=Path)
    opts = cli.parse_args()
    library = Path(os.environ.get("KAI_LAW_LIBRARY", Path.home() / ".local/share/kai/law"))
    result = review(opts.draft.read_text(encoding="utf-8"),
                    CourtListener(os.environ.get("COURTLISTENER_TOKEN"), library))
    print("KAI_CITE_CHECK_RESULT " + json.dumps(result, ensure_ascii=True))


if __name__ == "__main__":
    main()
