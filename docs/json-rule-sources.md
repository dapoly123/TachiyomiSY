# Runtime JSON manga sources (experimental)

This fork can import standalone JSON source definitions without installing an APK per website.

Open **Browse → Sources → Import JSON sources** and choose a UTF-8 JSON file. Multiple rules can be imported as a JSON array or an object containing `sources` / `rules`.

Alternatively choose **Subscribe to JSON rule URL** and enter an HTTP(S) URL serving a JSON rule or rule array. HTTPS is recommended for subscriptions; HTTP should only be used for trusted local services. URLs are remembered and can be refreshed from **Manage imported JSON sources**. Individual imported rules can also be removed from that screen without uninstalling anything.

The import is persisted in app-local preferences and the source list refreshes without restarting. Existing APK-based extensions continue to work. Reimporting the same `bookSourceUrl` replaces that rule.

## Supported subset

- `bookSourceName`, `bookSourceUrl`, `bookSourceLanguage`, `bookSourceHeader` (JSON object)
- `ruleFindUrl`, `ruleFindList`, `ruleFindName`, `ruleFindCoverUrl`, `ruleFindNoteUrl`
- `ruleSearchUrl`, `ruleSearchList`, `ruleSearchName`, `ruleSearchCoverUrl`, `ruleSearchNoteUrl`
- `ruleBookName`, `ruleBookIntro`, `ruleBookAuthor`, `ruleBookCoverUrl`, `ruleChapterList`, `ruleChapterName`, `ruleChapterUrl`, `ruleBookContent`

Rules use JSoup CSS selectors, and `@href`, `@src`, `@text` suffixes or `text`, `href`, `src` shorthand. Query variables: `{{key}}`, `{{searchKey}}`, `{{keyword}}`, `{{page}}`, `{page}`. Links and images resolve relative to the page they came from.

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
- Binary/encoded `ppcat://ruleLink` imports are not supported. Decode to JSON first.
- There is currently no automatic interval-based refresh of subscriptions, only manual refresh.
- No built-in pagination detection yet (each query returns the current page).
- Treat imported rules as untrusted network configuration, especially if they target private/local addresses. No imported JavaScript is executed.
- Automated compilation and device tests are required before using a built APK.
