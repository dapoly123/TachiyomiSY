# JSON-source end-to-end smoke test

This offline fixture deliberately uses an HTTP endpoint so the Android emulator can reach the WSL host through `10.0.2.2`. It does not contact any manga website.

1. Start `python3 test-fixtures/json-source-smoke/server.py` from the repository root on the WSL Android emulator host.
2. Confirm emulator network access: `printf 'GET /source.json HTTP/1.0\r\n\r\n' | adb shell toybox nc -w 3 10.0.2.2 18765` should respond `HTTP/1.0 200 OK`.
3. Build and install the fork's **debug** APK on Android API 29 or later.
4. In Browse → Sources, choose *Subscribe to JSON rule URL* and enter `http://10.0.2.2:18765/source.json`. **Note:** subscriptions deliberately require HTTPS, so for this HTTP-only local fixture instead open the same `/source.json` URL on the WSL host, save its JSON, transfer it to the emulator Downloads folder, and use *Import JSON sources*.
5. Verify *JSON Source Local Smoke Fixture* appears in the Sources list and search for `test`.
6. Expected: one result `Smoke Manga: test`; tapping it shows `Smoke Manga`, description, two chapters, and each chapter has two images.
7. Remove the fixture source from *Manage imported JSON sources* and confirm it disappears without uninstalling the TachiyomiSY APK.

This fixture tests CSS extraction, relative links, lazy-loaded covers, chapter URL handling, image pages, persistence and deletion. It does **not** establish compatibility with encrypted PipiMiao `ppcat://` links, JS rules, private APIs, Cloudflare challenges or dynamic-site script execution.
