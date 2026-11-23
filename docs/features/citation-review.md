# Citation review

**Last verified:** 2026-11-22 (local fixture tests; Android integration not yet verified)

Draft mode posts the response immediately, then adds a separate citation-review message in the same conversation. The checker never blocks drafting, editing, sending another message, or exporting. Tool-capable models can also call `cite_check` for text they need reviewed outside Draft mode.

## What the report covers

- **Case citations**: [eyecite](https://github.com/freelawproject/eyecite/) extracts citations and resolves short forms where possible. Cached reporter matches are used first; otherwise the [CourtListener Citation Lookup API](https://www.courtlistener.com/help/api/rest/v4/citation-lookup/) receives the reporter citation only, not the draft, quotes, exhibits, or record references.
- **Quotation text**: A quote with a uniquely attributed nearby reporter citation is checked against locally cached or retrieved [opinion text](https://www.courtlistener.com/help/api/rest/case-law/). A wording match does not verify the page, the opinion's role, or the proposition. Similar text is reported as a lead only.
- **Subsequent treatment**: The checker looks for possible negative language near the reporter citation in a limited sample of opinions from the [CourtListener forward citation graph](https://www.courtlistener.com/help/api/rest/citations/). This cannot establish current precedential status. No lead, an unavailable response, and an ambiguous citation are not findings of good law.
- **Statutes, pinpoints, and record references**: Recognized items without a matching version or a source document are reported as unresolved. The current checker does not independently verify these references or run automatically on arbitrary terminal exports.

## Sandbox setup

The Linux sandbox needs Python 3 and `eyecite` installed for its `python3` interpreter. Configure `COURTLISTENER_TOKEN` for sandbox processes to allow public-case lookups; without it, the checker can still parse and use already cached matches and opinions. The optional `KAI_LAW_LIBRARY` variable changes the local cache location from `~/.local/share/kai/law`. The cache contains public case metadata and opinion text, not draft text. A copy of the draft is staged briefly inside the sandbox and removed after the check on a best-effort basis.

## Key files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../tools/CitationCheckTool.kt` | Tool adapter and unresolved-item message |
| `composeApp/src/commonMain/.../data/RemoteDataRepository.kt` | Starts the check after saving the Draft reply |
| `composeApp/src/commonMain/composeResources/files/legal/cite_check.py` | Parsing, public-case lookup, quote matching, forward-citation leads |
| `tests/test_cite_check.py` | Offline tests with simulated CourtListener responses |
