# Runtime JSON manga sources (experimental)

This fork can import standalone JSON source definitions without installing an APK per website.

Open **Browse → Sources → Import JSON sources** and choose a UTF-8 JSON file. Multiple rules can be imported as a JSON array or an object containing `sources` / `rules`.

The import is persisted in app-local preferences and the source list refreshes without restarting. Existing APK-based extensions continue to work. Reimporting the same `bookSourceUrl` replaces that rule.

## Supported subset

- `bookSourceName`, `bookSourceUrl`, `bookSourceLanguage`
- `ruleFindUrl`, `ruleFindList`, `ruleFindName`, `ruleFindCoverUrl`, `ruleFindNoteUrl`
- `ruleSearchUrl`, `ruleSearchList`, `ruleSearchName`, `ruleSearchCoverUrl`, `ruleSearchNoteUrl`
- `ruleBookName`, `ruleBookIntro`, `ruleChapterList`, `ruleChapterName`, `ruleChapterUrl`, `ruleBookContent`

Rules use JSoup CSS selectors, and `@href`, `@src`, `@text` suffixes or `text`, `href`, `src` shorthand. Query variables: `{{key}}`, `{{searchKey}}`, `{{page}}`.

Example:

```json
{
  "bookSourceName": "Example site",
  "bookSourceUrl": "https://example.org",
  "ruleSearchUrl": "https://example.org/search?q={{key}}",
  "ruleSearchList": "article.comic",
  "ruleSearchName": "h2@text",
  "ruleSearchNoteUrl": "a@href",
  "ruleSearchCoverUrl": "img@src",
  "ruleChapterList": "ul.chapters li",
  "ruleChapterName": "a@text",
  "ruleChapterUrl": "a@href",
  "ruleBookContent": "img.page@src"
}
```

*This example is illustrative: example.org does not serve manga.*

### Important limitations

- This is **not** a full PipiMiao interpreter. Rules using `@js:`, XPath, JSONPath, custom POST requests, challenge bypass, encrypted media, or nested transforms are not supported.
- Importing a rule registers a source but does not guarantee the underlying website is reachable.
- The initial tutorial-only PipiMiao rule with `bookSourceUrl: about:blank` is intentionally rejected because it is not a manga website.
- Import size capped at 2MB, 200 rules per file, 500 installed rules.
- Remove/update UI and import of the binary `ppcat://ruleLink` format are follow-up work. JSON needs to be decoded first.
- Treat imported rules as untrusted network configuration, especially if they target private/local addresses. No imported JavaScript is executed.
- Automated compilation and device tests are required before using a built APK.
