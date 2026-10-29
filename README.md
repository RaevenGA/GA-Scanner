# GA Scanner — Grand Archive TCG card scanner (Android)

Native Android app (Kotlin + Jetpack Compose) to scan Grand Archive cards, keep a collection,
build decks and check prices from configurable stores.

## Features

- **Card search** — live search of the official card database (`api.gatcg.com`, no key needed).
- **Card page** — art, stats, effect, all editions (set + collector number), owned count.
- **Scan** — free by default. Take a photo (or pick one from the gallery); Google ML Kit reads
  the card name and the footer (set code + collector number) on the phone, offline. The app looks
  the exact edition up in the database and also offers name matches, tolerant of OCR typos.
  Optional "Claude" mode (your own API key) handles glare, foils and odd angles better.
  Tap **+** to add straight to the collection; "Scan next card" keeps a fast loop.
- **Collection** — stored on the phone (Room). Quantities per edition and foil/non-foil, filter.
- **Decks** — Material deck / Main deck / Sideboard sections, quantities, rename, delete,
  share decklist as text.
- **Prices**
  - *TCGplayer market price* (USD) — real prices from [tcgcsv.com](https://tcgcsv.com), a free
    daily mirror of TCGplayer's catalog. The whole Grand Archive category is downloaded once per
    day and cached, so later lookups are instant. The row matching the selected edition is
    highlighted.
  - *Store links* — TCGplayer, Cardmarket, eBay (off by default) plus any shop you add.
  - *Display currency* — show prices in PLN, EUR, USD or GBP (ECB rates via frankfurter.app).
- **Settings** — API key, vision model, currency, enable/disable/edit/add stores.

## Build & run

1. Install **Android Studio** (Ladybug 2024.2 or newer).
2. *File → Open* this folder. Let Gradle sync (it downloads Gradle 8.11.1, AGP 8.7.3, Kotlin 2.1).
3. Connect a phone with USB debugging (or start an emulator) and press **Run**.
   Minimum Android 8.0 (API 26).

From the command line: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## Scanning modes

**On-device (default, free)** — no account, no key, no internet needed for the reading step
(the database lookup still needs internet). Uses Google ML Kit text recognition, whose model is
bundled in the APK (~4 MB). Tips: fill the frame with one card, avoid glare on foils, hold steady.
Matching logic lives in `scan/OnDeviceRecognizer.kt` (`CardTextParser`) and `ui/scan/ScanViewModel.kt`.

**Claude (optional)** — Settings → Card scanning → *Claude (API key)*. Create a key at
<https://console.anthropic.com>; each scan is one small paid request. The key is stored only on
the phone.

## Adding a store (e.g. a Polish shop)

Settings → **Add store**:

1. On the shop's website, search for any card, e.g. "Lorraine".
2. Copy the address of the results page, e.g. `https://shop.example.pl/szukaj?q=Lorraine`.
3. Replace the card name with `{name}` → `https://shop.example.pl/szukaj?q={name}`.

Placeholders: `{name}` card name · `{set}` set code · `{number}` collector number ·
`{query}` name + set code.

## Things to verify on first run

These couldn't be tested against the live services while writing the code:

- **Search parameter** — the app calls `GET /cards/search?name=…&page=…`. If search returns
  unrelated cards, check the parameter name in the API docs (<https://api-docs.gatcg.com>) and
  change it in `data/api/GatcgApi.kt`.
- **Cardmarket link** — uses Cardmarket's general search URL. I couldn't confirm that Cardmarket
  lists Grand Archive; if it doesn't, disable that source or edit its URL.
- **TCGplayer matching** — rows are matched by card name, then ranked by set and collector
  number. Unusual names (promos, alternate arts) may need tuning in `TcgCsvProvider.score()`.

## Project layout

```
app/src/main/java/com/gascanner/app/
  GAScannerApp.kt          app + dependency container
  MainActivity.kt          navigation + bottom bar
  data/api/                Grand Archive API client + models
  data/db/                 Room: collection, decks
  data/settings/           DataStore settings, price-source config
  data/prices/             TCGplayer (tcgcsv), store links, currency conversion
  scan/                    Claude vision recognizer, image prep
  ui/                      Compose screens: search, card, scan, collection, decks, settings
```

## Ideas for next steps

- Collection value total (prices are already cached locally).
- Import/export collection as CSV.
- Offline card database cache for search without internet.
- Deck legality / format checks using the API's `legality` field.
- Batch scanning several cards from one photo.
- Live auto-scan (read frames continuously, no shutter press).
- Image fingerprinting of card art to tell alternate-art printings apart without reading text.
