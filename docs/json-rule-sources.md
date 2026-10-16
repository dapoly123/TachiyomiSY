# Runtime JSON manga sources (experimental)

This fork can import standalone JSON source definitions without installing an APK per website.

Open **Browse → Sources → Import JSON sources** and choose a UTF-8 JSON file. Multiple rules can be imported as a JSON array or an object containing `sources` / `rules`.

Alternatively choose **Subscribe to JSON rule URL** and enter an HTTP(S) URL serving a JSON rule or rule array. HTTPS is recommended for subscriptions; HTTP should only be used for trusted local services. URLs are remembered and can be refreshed from **Manage imported JSON sources**. Individual imported rules can also be removed from that screen without uninstalling anything.

**PipiMiao support:** Select **Import PipiMiao share link** and paste a `ppcat://ruleLink?data=...` link. The built-in decoder reconstructs its rearranged ZIP/DEFLATE `.mh_rules` JSON payload and validates the CRC. The ordinary **Import JSON sources** file picker also accepts a UTF-8 JSON backup array with `[{"name":"...","url":"ppcat://ruleLink?data=..."}]` entries. These import without installing source APKs.

Decoded rules then pass through the same declarative rules engine as native JSON. Successfully decoding a source is **not** a guarantee that its website rules (especially `@js:`, JSONPath, XPath, encrypted requests, or dynamic URLs) are supported. A tutorial-only source with `about:blank` is rejected intentionally.

The import is persisted in app-local preferences and the source list refreshes without restarting. Existing APK-based extensions continue to work. Reimporting the same `bookSourceUrl` replaces that rule.

## Supported subset

- `bookSourceName`, `bookSourceUrl`, `bookSourceLanguage`, `bookSourceHeader` (JSON object)
- `ruleFindUrl`, `ruleFindList`, `ruleFindName`, `ruleFindCoverUrl`, `ruleFindNoteUrl`
- `ruleSearchUrl`, `ruleSearchList`, `ruleSearchName`, `ruleSearchCoverUrl`, `ruleSearchNoteUrl`
- `ruleBookName`, `ruleBookIntro`, `ruleBookAuthor`, `ruleBookCoverUrl`, `ruleChapterList`, `ruleChapterName`, `ruleChapterUrl`, `ruleBookContent`

Rules use JSoup CSS selectors, and `@href`, `@src`, `@text` suffixes or `text`, `href`, `src` shorthand. PipiMiao-style `tag.a`, `class.manList@tag.ul@tag.li`, and `||` fallbacks are also supported for HTML. JSON APIs support a non-executable JSONPath subset: `$.foo.bar`, `$.items[*]`, `$.[*]`, `$.items[0]`, `$.*`, and `||` fallbacks, including list arrays and simple URL interpolation like `{{$.id}}`. Query variables: `{{key}}`, `{{searchKey}}`, `{{keyword}}`, `{{page}}`, `{{page-1}}`, `{page}`. Links and images resolve relative to the page they came from.

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
- PipiMiao direct links and JSON backups are decoded; incompatible website-rule expressions remain unsupported.
- There is currently no automatic interval-based refresh of subscriptions, only manual refresh.
- No built-in pagination detection yet (each query returns the current page).
- JSONPath subset intentionally excludes predicates/filters, recursive descent, JavaScript post-processing, `@put` stateful transforms and script-generated chapter URLs. Importing rules containing these features does not make them fully usable.

### Emulator regression evidence (API 29)

- Imported plain JSON, a PipiMiao `ppcat://ruleLink` directly, and PipiMiao backup JSON.
- Searched `test` successfully using standard CSS (`Smoke Manga: test`), legacy PipiMiao selectors (`Smoke Manga: test`), and JSONPath API (`JSONPath Manga: test`).
- Verified the search-to-detail flow, 2 chapters, 2 reader images (HTTP 200), JSON subscription refresh, removing rules, retaining other rules on process restart, and automatic language enablement.
- Compiled with `./gradlew :app:assembleDebug --offline` on Offical using JDK 17, Gradle 9.7, Android SDK 37.0. Existing test/lint suites need uncached dependencies for Kotest/MockK/ktlint; those checks have not passed.
- Treat imported rules as untrusted network configuration, especially if they target private/local addresses. No imported JavaScript is executed.
- Automated compilation and device tests are required before using a built APK.
