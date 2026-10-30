# GA Scanner — Grand Archive TCG card scanner (Android)

Free native Android app (Kotlin + Jetpack Compose) to scan Grand Archive cards, keep a collection,
build decks, manage want-to-sell / want-to-buy lists and check prices in shops.

## Features (v0.2)

- **Offline card database** — the whole official database (`api.gatcg.com`) is downloaded on first
  launch; every later launch only fetches cards changed since the last check. Search, card pages and
  scanning then work without internet. Settings → *Offline card database* shows the status.
- **Scanning** (free, on the phone, Google ML Kit):
  - *Live* — point the camera at a card; it's recognised continuously, with a vibration when a card
    locks on. *Auto-add* adds each new card to the chosen list; take the card out of view to scan the
    same card again, or tap **+** for another copy.
  - *Photo* — one card per photo, with result alternatives.
  - *Several* — one photo of several cards laid out with their bottom edge visible; tick the ones to add.
  - *Art matching* — when only the name is legible, the printing is chosen by comparing the artwork
    with the database images (64-bit difference hash, cached on the phone).
  - Scanned cards go to *Collection*, *Sell list* or *Buy list* (choose under the camera).
  - Optional *Claude* mode (your own Anthropic API key) for Photo / Several.
- **Card search & browse** — by name, rules-text keyword (e.g. *taunt*, *floating memory*) or champion
  (e.g. *Arisanna*); browse a whole set in collector-number order; filter by element and card type;
  *Not owned* shows only cards you don't have, with one-tap **add to Want to buy**.
- **Flashlight** button on the scan screen for darker rooms.
- **Collection** — Owned / Want to sell / Want to buy tabs; quantities per edition and foil;
  list view or card images 2 or 3 per row; total value; CSV import & export.
- **Want to sell / Want to buy** — asking or max price and a note per card; *Share* sends a
  ready-to-post text (Messenger, Discord, Facebook groups) with prices and total.
- **Decks** — Material / Main / Sideboard / Boons; list or image grid; legality check for Standard,
  Pantheon and Draft (official rules + banlists); deck value; share decklist.
- **Prices**
  - *Default shop* — shown first on card pages and used for collection, list and deck values.
  - *TCGplayer market price* (USD) via [tcgcsv.com](https://tcgcsv.com), cached daily.
  - *Caduko* (EUR) — live prices in the app (WooCommerce), matched to the exact set, number and
    foil / non-foil using the shop's product codes.
  - *Zarc* (PLN) — search link. The shop is behind Cloudflare bot protection, so no app can read its
    prices automatically.
  - *Cardmarket* — search link (Grand Archive singles are expected there soon), TCGplayer, eBay.
  - Any other shop: Settings → *Add store*.
  - Display currency PLN / EUR / USD / GBP (ECB rates via frankfurter.app).
- **Languages** — English, Polski, Deutsch, Français, Español (Settings → Language). Card names and
  rules text stay in English, as they come from the official database.

## CSV format

Export writes `name,set,number,foil,quantity,set_name,edition_uuid,card_slug`.
Import accepts any CSV with a header row and at least a `name` column (also `set`, `number`,
`foil`, `quantity`, `price`, `note`; English or Polish column names; comma or semicolon). Cards are
matched by edition id, then set + number, then name.

## Adding a store

Settings → **Add store**: search for any card on the shop's website, copy the results address and
replace the card name with `{name}`. Placeholders: `{name}` · `{set}` · `{number}` · `{query}`
(name + set). Turn on *Show live prices in the app* for Shopify / WooCommerce shops.

## Notes and limits

- The deck legality check follows the published rules. Standard: main deck ≥ 60, max 4 copies,
  material deck ≤ 12 unique cards with a level 0 champion, sideboard ≤ 15 cards / 15 points.
  Pantheon: main deck ≥ 60 with one copy of each card, material deck exactly 12 with a level 0
  champion, one Lesser and one Greater Boon, no sideboard. Draft: main deck ≥ 30. Banned / limited cards come from the database's `legality` field.
- Live shop prices match listings by card name, then set and number; listings with unusual titles
  may not be matched.
- Your collection, decks and lists stay on the phone; export a CSV to back them up.

## Project layout

```
app/src/main/java/com/gascanner/app/
  GAScannerApp.kt     app + dependency container
  MainActivity.kt     navigation, language
  i18n/               UI text: Strings.kt (English) + Pl / De / Fr / Es
  data/api/           Grand Archive API client + models
  data/cache/         offline card database, price cache, art hashes
  data/db/            collection, decks, deck rules
  data/lists/         want to sell / want to buy
  data/csv/           CSV import / export
  data/prices/        TCGplayer, shops (Shopify / WooCommerce), currency
  data/settings/      settings, stores
  scan/               on-device OCR, Claude, art matching, card matching
  ui/                 screens
```
