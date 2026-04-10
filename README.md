# Clan Promotion Tracker

A personal RuneLite plugin that combines a promotion sidebar workflow with Wise Old Man XP checks and in-game clan member highlighting.

## What It Does

- Reads your clan roster directly from RuneLite, including join dates and current rank titles.
- Pulls current XP from a configured Wise Old Man group.
- Calculates XP gained since clan join using WOM's lighter-weight gains endpoint, which already returns the start and end XP window for the requested date range.
- Reuses cached baselines and intentionally spreads new WOM gain lookups across refreshes, prioritizing members who are month-eligible now before lower-priority roster entries.
- Reuses the last successful WOM group membership snapshot for a short time so repeated refreshes do not keep re-requesting the same roster and XP payload.
- Uses the live Railway app ladder as the default promotion logic.
- Highlights ready members in the clan member list with a target-rank badge.
- Shows a sidebar list with member status, XP gained, months in clan, and next-rank candidate.
- Exports the full roster and promotion state to CSV by copying to the clipboard or saving a file.

## Wise Old Man Notes

- `Wise Old Man API key` is optional, but recommended if you want to hydrate a large clan faster.
- Without an API key, the plugin now limits how many uncached WOM gain windows it fetches in one refresh.
- The plugin also caches the last successful WOM group response briefly and falls back to that cached snapshot if the group endpoint is temporarily rate-limited.
- The plugin enforces an internal per-minute WOM request budget before hitting the API (12 requests/min without an API key, 80 requests/min with a key) to reduce 429 bursts.
- If Wise Old Man responds with `429`, the plugin enters a short cooldown and reuses cached data until the cooldown expires.

## Development

This project targets Java 11 and runs through the standard RuneLite external plugin test harness.

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-11.0.30.7-hotspot'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew test
.\gradlew run
```

## Data and Privacy

- This plugin sends clan member usernames and join-date-based Wise Old Man API queries to calculate promotion recommendations.
- No write actions are performed against Wise Old Man from this plugin.

## Attribution

This plugin adapts portions of logic and workflow from:

- [serverlat/clan-rank-up-notifier](https://github.com/serverlat/clan-rank-up-notifier)
- [homer2011/clan-rank-helper](https://github.com/homer2011/clan-rank-helper)

## Submission Metadata

- Plugin Hub internal id slug: `clan-promotion-tracker`
- Issue tracker: `https://github.com/nlobdell/clan-promotion-tracker/issues`
