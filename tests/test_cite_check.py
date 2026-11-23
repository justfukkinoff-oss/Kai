import runpy
import tempfile
import unittest
from pathlib import Path


CHECKER = runpy.run_path(str(Path(__file__).resolve().parents[1] /
    "composeApp/src/commonMain/composeResources/files/legal/cite_check.py"))
HOST = CHECKER["HOST"]
CITE = "538 U.S. 84"
QUOTE = "Reasonable jurists could debate the issue."


class FixtureCourtListener(CHECKER["CourtListener"]):
    def __init__(self, library):
        super().__init__("test-token", library)
        self.requests = []

    def request(self, path, data=None):
        self.requests.append((path, data))
        if path == "/api/rest/v4/citation-lookup/":
            return [{"status": 200, "clusters": [{"id": 99, "sub_opinions": [81]}]}]
        if path == "/api/rest/v4/opinions/81/":
            return {"plain_text": QUOTE, "type": "010combined",
                    "cluster": HOST + "/api/rest/v4/clusters/99/"}
        if path == "/api/rest/v4/opinions-cited/?cited_opinion=81":
            return {"results": [{"citing_opinion": HOST + "/api/rest/v4/opinions/82/"}]}
        if path == "/api/rest/v4/opinions/82/":
            return {"plain_text": f"We limited Smith v. Doe, {CITE}, on another issue.",
                    "cluster": HOST + "/api/rest/v4/clusters/100/"}
        raise AssertionError(f"Unexpected request: {path}")


class CitationReviewTests(unittest.TestCase):
    def test_full_case_short_form_quote_treatment_and_record(self):
        text = (f'The court said “{QUOTE}” Smith v. Doe, {CITE}, 92 (2003). '
                'Id. at 93. 28 U.S.C. § 2254(d). See ECF No. 19-2 at 3.')
        with tempfile.TemporaryDirectory() as folder:
            client = FixtureCourtListener(Path(folder))
            result = CHECKER["review"](text, client)
            self.assertEqual(result["citations"], 3)
            issues = "\n".join(result["issues"])
            self.assertIn(f"text matched {CITE}", issues)
            self.assertIn("subsequent treatment not verified", issues)
            self.assertIn("possible negative-treatment language", issues)
            self.assertIn("statutory text/version not checked", issues)
            self.assertIn("record reference not verified", issues)
            self.assertEqual(result["sources"]["Quote 1"], HOST + "/opinion/99/")
            self.assertEqual([data for _, data in client.requests if data is not None],
                             [b"text=538+U.S.+84"])

    def test_missing_credentials_yield_report_not_exception(self):
        with tempfile.TemporaryDirectory() as folder:
            client = CHECKER["CourtListener"](None, Path(folder))
            report = CHECKER["review"](f"Smith v. Doe, {CITE} (2003).", client)
            self.assertIn("CourtListener token not configured", "\n".join(report["issues"]))

    def test_cached_case_still_resolves_without_network(self):
        with tempfile.TemporaryDirectory() as folder:
            library = Path(folder)
            fixture = FixtureCourtListener(library)
            fixture.case(CITE)
            offline = CHECKER["CourtListener"](None, library)
            self.assertEqual(offline.case(CITE)["url"], HOST + "/opinion/99/")
            self.assertIsNone(offline.opinion_id("https://example.org/api/rest/v4/opinions/81/"))

    def test_quote_cannot_borrow_case_from_next_paragraph(self):
        with tempfile.TemporaryDirectory() as folder:
            client = FixtureCourtListener(Path(folder))
            report = CHECKER["review"](f'“{QUOTE}”\n\nSmith v. Doe, {CITE} (2003).', client)
            self.assertIn("Quote 1: source attribution unresolved.", report["issues"])


if __name__ == "__main__":
    unittest.main()
