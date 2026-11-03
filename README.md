# GA Scanner

A free Android app for **Grand Archive TCG** players: scan cards with the camera, keep your
collection, build decks, manage trade lists and check prices.

**[Download the latest version →](https://github.com/RaevenGA/GA-Scanner/releases)**
(open the newest release and install the `.apk` file; Android will ask you to allow installing apps
from your browser or file manager). The app tells you with a pop-up when a new version is out.

Languages: English · Polski · Deutsch · Français · Español

---

## Features

### Scanning
- **Free and on the phone**: no account or API key needed. Text recognition runs on the device (Google ML Kit).
- Reads the **card name** and the **bottom line** of the card, e.g. `PRD • EN-040 C`: set code,
  1st / Alter edition, collector number and **rarity**. This identifies the exact printing.
- **Three modes**
  - **Live**: point the camera at cards one after another. A vibration confirms each card.
    - With **Auto-add** on, every card is added automatically. Swapping in an identical card is noticed, too.
    - If a card was first recognised by name only and the bottom line later shows another printing, the add is corrected automatically.
    - **Undo** removes the last card added.
  - **Photo**: one card per photo, with alternatives to choose from.
  - **Several**: one photo of several cards laid out with their bottom edge visible.
- **Add to**: Collection, Want to sell, Want to buy, or a **deck**. In a deck, champions and regalia go to the material deck automatically.
- **Foil** switch for scanning a pile of foils (the camera can't reliably tell foils apart).
  Printings that only exist as foil (collector rarities CSR / CUR / CPR, promos and others) are always saved as foil, and non-foil-only ones as non-foil.
- **Flashlight** button for darker rooms.
- **Artwork matching**: when only the name is readable, the printing is chosen by comparing the artwork with the database images.
- Optional **Claude** mode (your own Anthropic API key) for Photo and Several.

### Cards
- **Offline card database**: the whole official database is downloaded on first start and kept up to date automatically.
- **Offline card images** (optional, about 200 MB) so pictures show without internet too.
- **Search** by name, by a keyword from the rules text (*taunt*, *floating memory*) or by champion (*Arisanna*).
- **Filters**: set, element, class (Warrior, Ranger, Tamer…), type and subtype (Human, Beast, Sword, Spell…).
- **Not owned** shows only cards you don't have yet; one tap adds a card to *Want to buy*.
- **Browse a whole set** in collector-number order. List or card images, 2 or 3 per row.

### Collection, trade lists and decks
- **Collection** with quantities per printing and foil, total value, and CSV import and export.
- **Want to sell / Want to buy** lists with your own price and a note per card. **Share** creates a
  ready-to-post text for Messenger, Discord or Facebook groups.
- **Check someone's list**: paste another player's list and see at a glance:
  - ✅ cards on your list in the same version;
  - 🔄 cards on your list in another version;
  - 📚 cards that aren't on your list but are in your collection.
- **Edit** any card (✎ or long-press): change the printing, foil, quantity and deck section.
- **Decks** with Material, Main, Sideboard and Boons sections, a deck value and decklist sharing.
- **Legality check** for **Standard**, **Pantheon** and **Draft**, including banned and limited cards.

### Prices
- **Default shop**, used for all values.
- **Caduko** (EUR): live prices, matched to the exact set, number, rarity and foil.
- **TCGplayer** market price (USD), updated daily. Collector printings (CSR, CUR…) get their own price, not the common one's.
- **Zarc** (PLN), **Cardmarket**, TCGplayer and eBay as search links. You can add any shop yourself.
- Prices shown in PLN, EUR, USD or GBP (European Central Bank rates).

---

## Tips
- **Scanning**
  - Keep the whole card, including the black border at the bottom, inside the frame. The set code and number are printed there in small white text.
  - Use the flashlight in dim light, and tilt foils slightly to avoid glare.
- **Sharing lists**: lists shared from GA Scanner can be pasted straight into *Check someone's list*.
  Common formats like `2x Crescent Glaive - AMB 010` or `Crescent Glaive x3` work too.
- **Backup**: your collection, decks and lists stay on the phone. Use *Export CSV* in Collection to make a backup.

## CSV format
Export: `name,set,number,foil,quantity,set_name,edition_uuid,card_slug`.

Import accepts any CSV with a header row and at least a `name` column. It also reads `set`, `number`, `foil`, `quantity`, `price` and `note`. Column names can be English or Polish, separated by commas or semicolons.

## Deck rules used
| Format | Main deck | Material deck | Other |
|---|---|---|---|
| Standard | ≥ 60, max 4 copies | ≤ 12, one copy each, Lv 0 champion | Sideboard ≤ 15 cards / 15 points |
| Pantheon | ≥ 60, one copy each | exactly 12, Lv 0 champion | 1 Lesser + 1 Greater Boon, no sideboard |
| Draft | ≥ 30 | no limit | — |

Banned and limited cards come from the official card database.

---

## For developers

Native Android, Kotlin + Jetpack Compose, minimum Android 8.0. Every push builds the APK and runs the
unit tests on GitHub Actions. Compiler errors, warnings and failed tests are shown on the run's
summary page.

```
app/src/main/java/com/gascanner/app/
  data/api/       Grand Archive API client          data/prices/    TCGplayer, shops, currency
  data/cache/     offline card database             data/images/    offline card images
  data/db/        collection, decks, deck rules     data/compare/   list cross-check
  data/lists/     want to sell / want to buy        data/update/    GitHub release check
  data/csv/       CSV import / export               data/settings/  settings, stores
  scan/           on-device OCR, footer parser, card matching, art matching
  i18n/           UI text (English + Polish, German, French, Spanish)
  ui/             screens
```

Card data and images: official Grand Archive database ([api.gatcg.com](https://api.gatcg.com)).
Grand Archive is © Weebs of the Shore. GA Scanner is an unofficial fan project.
