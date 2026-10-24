# TapTapAccounting Release Regression Checklist

## Goal

Use this checklist before shipping a `release` build to verify that release-only steps such as R8 minification, resource shrinking, manifest merging, and native packaging do not remove or break user-facing functionality.

Recommended comparison method:

1. Install a fresh `debug` build and verify the same scenario.
2. Uninstall it or clear app data.
3. Install the target `release` build.
4. Repeat the exact same scenario and compare behavior.

## Test Scope

This checklist focuses on the project areas most sensitive to release/debug differences:

- Gson reflection and JSON-backed presets
- Room-backed backup/restore flows
- Overlay service and boot/service entry points
- Shizuku integration and reflective process launch
- Local ASR / JNI / native model loading
- AI chat, OCR, and image import flows
- Dynamic resource lookup paths

## Environment Setup

Before starting:

- Prepare one Android 13+ device if image/media permission flows matter.
- Prepare one device with Shizuku installed if white-list mode is used.
- Prepare a network connection for AI / model download cases.
- Keep one sample backup file, one sample CSV, one sample receipt image, and one short voice sample.
- Record the exact APK being tested:
  - `app/build/outputs/apk/debug/app-debug.apk`
  - `app/build/outputs/apk/release/app-release.apk`

## Smoke Checks

### 1. App Launch

- Precondition: fresh install.
- Steps: open app from launcher.
- Expected:
  - App launches without crash.
  - Main tabs and primary screens render normally.
  - No missing strings, blank cards, or obviously broken layouts.

### 2. Main Navigation

- Steps:
  - Open Home, Assets, Stats, Profile.
  - Open Settings, Currency Manager, Backup, AI Config, Chat.
- Expected:
  - Every page opens successfully.
  - No activity launch crash.
  - Back navigation behaves normally.

## Core Data Checks

### 3. Built-in Categories and Preset Assets

- Risk: release minification breaking Gson field names for preset JSON models.
- Steps:
  - Open add-category / built-in category related UI.
  - Open add-asset type picker.
  - Verify preset names and icons load.
- Expected:
  - Built-in categories are present.
  - Preset asset icons and names are not empty.
  - No blank list caused by JSON parse failure.

### 4. Basic CRUD

- Steps:
  - Create asset.
  - Create expense bill.
  - Create income bill.
  - Edit one bill.
  - Delete one bill.
- Expected:
  - Data persists after process restart.
  - Home/Stats/Assets pages refresh correctly.

## AI and OCR Checks

### 5. AI Text Accounting

- Precondition: valid AI endpoint, model, and API key.
- Steps:
  - In Chat or overlay AI input, submit one single-bill sentence.
  - Submit one multi-bill sentence.
- Expected:
  - Response returns successfully.
  - Parsed bill fields appear in UI.
  - No Retrofit/Gson runtime crash in release.

### 6. AI Image / Receipt Flow

- Risk: release manifest, picker flow, image handoff, OCR assets, or AI parse differences.
- Steps:
  - Trigger image import from main chat entry.
  - Trigger image import from overlay entry.
  - Select a receipt image.
- Expected:
  - Picker opens.
  - Image returns to app successfully.
  - OCR/AI analysis runs.
  - Overlay reappears after picker cancel or completion.

### 7. AI Model / Config Screen

- Steps:
  - Open AI config page.
  - Load model list.
  - Save config.
- Expected:
  - Model fetch succeeds.
  - Saved configuration survives app restart.

## Voice and Native Checks

### 8. Cloud ASR

- Precondition: AI speech model configured.
- Steps:
  - Record a short voice message.
  - Wait for speech-to-text conversion.
- Expected:
  - Recording succeeds.
  - Text result returns or a user-facing error is shown.
  - No release-only converter crash.

### 9. Local ASR Model Download / Import

- Risk: release package missing JNI libs or Sherpa classes.
- Steps:
  - From Profile, download local ASR model or import local archive.
  - Initialize local ASR.
  - Run one speech-to-text attempt.
- Expected:
  - Model download/import completes.
  - No `UnsatisfiedLinkError`, `NoClassDefFoundError`, or init crash.
  - Local transcription can run.

### 10. Voice Streaming

- Steps:
  - Start live voice input.
  - Speak briefly.
  - Stop recording.
- Expected:
  - Streaming starts and stops cleanly.
  - Final transcript or fallback path works.

## Overlay and Service Checks

### 11. Overlay Permission and Manual Open

- Steps:
  - Grant overlay permission if needed.
  - Open overlay manually from app.
  - Close it by cancel, outside touch, and save.
- Expected:
  - Overlay shows without crash.
  - Buttons and form inputs work.
  - Cancel/save behavior is correct.

### 12. Foreground Service Start / Stop

- Steps:
  - Enable flip service from main/profile.
  - Disable it again.
- Expected:
  - Foreground notification appears.
  - Service can stop cleanly.
  - No Android 12+/14+ foreground-service crash.

### 13. Quick Settings Tile / Quick Start

- Steps:
  - Add quick settings tile.
  - Tap tile.
  - Launch quick-start activity if used.
- Expected:
  - Overlay opens through the service.
  - No component-not-found or manifest issue.

### 14. Boot Restore Path

- Steps:
  - Enable flip-related settings.
  - Reboot device or simulate package replaced / restart flow if practical.
- Expected:
  - Boot receiver path works as designed.
  - Service restart behavior matches debug expectation.

## Shizuku Checks

### 15. Shizuku Permission Flow

- Precondition: Shizuku installed and running.
- Steps:
  - Enable white-list related behavior.
  - Request Shizuku permission.
  - Open app whitelist page.
- Expected:
  - Permission request works.
  - Status checks behave normally.
  - No reflection failure from `ShizukuHelper`.

### 16. Foreground App / White-list Logic

- Steps:
  - Add one app to whitelist.
  - Trigger overlay logic in allowed and disallowed apps.
- Expected:
  - Allowed app can trigger overlay.
  - Disallowed app does not trigger overlay.
  - No release-only shell/process failure.

## Backup / Restore Checks

### 17. Full Backup Export

- Steps:
  - Export backup with assets, categories, bills, rules, chat, and settings selected.
  - Export another backup with AI core enabled and PIN protection.
- Expected:
  - Backup file is created.
  - No crash while serializing Room/Gson models.

### 18. Full Restore

- Steps:
  - Clear app data or use a clean install.
  - Restore from backup.
  - Restore from PIN-protected backup.
- Expected:
  - Restore dialog shows correct modules.
  - PIN-protected settings can be decrypted with correct PIN.
  - Restored assets, bills, categories, chat, settings all appear correctly.

### 19. Shared Ledger vs Restore Gating

- Steps:
  - Join a shared ledger, then open restore with any encrypted backup that contains shared identity.
  - Overwrite restore with only `categories` checked.
  - Overwrite restore with `bills` checked.
  - Merge restore with only `categories` checked.
  - Same-device reconnect: keep the ledger connected, queue a few unsynced bills, then overwrite
    restore with `bills` checked and choose "用备份覆盖并重新连接".
  - Cross-device reconnect: restore the same backup on a clean install.
- Expected:
  - The category-only restore completes without any shared-ledger prompt and without asking the
    user to leave the shared ledger; bills keep their category links.
  - The bill restore on a device whose ledger is *not* in the backup shows the "共享账本正在同步"
    dialog instead of failing inside the transaction; choosing "只恢复本地数据" restores the ticked
    local modules only.
  - Same-device reconnect succeeds without a unique-index failure, keeps the ledger and member
    count unchanged, does not drop the unsynced bills, and uploads them on the next sync.
  - Cross-device reconnect keeps the original member seat and pulls the other member's bills.
  - Merge restore of categories is never blocked by an active shared ledger.
  - No shared-related dialog appears when bills and budgets are not part of the selection.

### 20. Category Budget Usage After Upgrade / Restore

- Steps:
  - On a database where bills carry a `categoryName` but no `categoryId` (legacy data), launch the
    app once and let the startup backfill finish.
  - Open the budget page and compare a category budget's "used" amount with the same category's
    amount on the stats page.
  - Set a fresh category budget, record a bill in that category, then reopen the budget page.
  - Overwrite restore with only `categories` checked, then again with only `bills` checked.
  - Import a CSV whose rows carry category names but no ids (any export from this app), then check a
    category budget for one of the imported categories.
  - Delete a bill, delete the category it belonged to, then restore that bill from the trash.
- Expected:
  - Bills whose leaf category name matches exactly one existing category get `categoryId` back-filled,
    and the category budget's used amount is no longer 0.
  - Bills with a blank category name (user picked "未分类") and bills whose category was deleted stay
    untouched.
  - Two same-name categories within the same income/expense type are never guessed.
  - Both restore paths leave bills linked without needing an extra app restart.
  - CSV-imported bills land with a `categoryId` whenever the category name matches exactly one
    existing category, so the budget's used amount is non-zero right after the import.
  - A bill restored from the trash after its category was deleted comes back with `categoryId`
    cleared instead of a dangling id, and shows up in the startup backfill's reach.
  - A bill written by a path that does not pick a category from the picker (multi-bill AI recognition
    saved without review, chat rewrite, recurring generation, refund) also lands with a `categoryId`,
    so it shows up in the category budget right away instead of only after a restart.
  - A **refund** bill whose original category is a **root category** (name like `退款：付费会员`, no
    `" - "` separator) also gets its `categoryId` back. This is the case the v1 backfill silently
    missed: `normalizeForStorage` keeps the `退款：` prefix on purpose, so the leaf lookup must strip
    it first — otherwise the leaf name is `退款：付费会员` and never matches `categories.name`.
- Why this is manual: the project has no Room/Robolectric tests, so the SQL backfill cannot be
  asserted in unit tests. `UncategorizedBillRelinkerTest` covers the pure matching rules and
  `BillMutationServiceRelinkTest` covers the write-funnel guard, but the DB wiring itself is not
  reachable from JVM tests.

### 21. CSV Export / Import

- Steps:
  - Export bills to CSV.
  - Import the same CSV into a chosen book.
- Expected:
  - Export file is generated.
  - Import parses records successfully.
  - Imported bills appear in the selected or fallback book.

## Media and File Checks

### 22. Chat Media / Avatar / Background Paths

- Steps:
  - Set chat background or avatar if the feature is used.
  - Back up and restore chat media.
- Expected:
  - Restored file paths are still valid.
  - Images render after restore.

### 23. uCrop / Image Editing Path

- Steps:
  - Trigger any path that opens `UCropActivity`.
- Expected:
  - Crop page opens and returns successfully.

## Dynamic Resource Checks

### 24. Dynamic IDs and System Resource Lookup

- Risk: release resource shrinking with `getIdentifier`.
- Steps:
  - Open Home page with grouped bill list headers.
  - Observe icon container and header summary rendering.
  - Trigger any toast path using system transient notification lookup.
- Expected:
  - Header summary text appears when expected.
  - Transaction icon container is visible.
  - No null-view crash caused by missing shrunk resources.

### 25. Category Rename / Promote / Demote vs Stats Grouping

- Risk: `bills.categoryName` is a display-path snapshot while the stats page and insights group by
  that snapshot (they never look up `categoryId`). Any category-tree shape change can leave the
  snapshot stale and split one category into two rows on the stats page.
- Steps:
  - Record a few bills under a sub-category (e.g. `餐饮 - 早餐`), then rename the **top-level**
    parent (e.g. `餐饮` → `吃饭`).
  - Open stats for the current month and look at the category list.
  - Repeat with: promote the sub-category to top level; demote a top-level category under another;
    drag-reorder to move a sub-category under a different parent.
  - Also cover a refund bill in that category (its name carries a `退款：` prefix).
  - Also try a parent whose name is a **substring** of its children's names (e.g. parent `餐`
    with a child `三餐`) and rename the parent.
- Expected:
  - After every one of those operations the stats page shows a single row per real category, named
    with the **current** tree path — no leftover row under the old name.
  - Refund bills keep their `退款：` / `退款·` prefix after the rename.
  - Bills that only ever had a category name (no `categoryId`) also follow the rename when the
    renamed category is their ancestor.
  - The substring case renames **only the segment that changed**: `餐 - 三餐` under a parent rename
    to `新餐` becomes `新餐 - 三餐`, never `新餐 - 三新餐`.
- Why this is manual: no Room/Robolectric tests, so the `UPDATE bills SET categoryName …` statements
  cannot be asserted in JVM tests. `CategoryRepositoryTest` covers the pure path resolution that the
  resync feeds on, and `BillMutationServiceRelinkTest` covers the write-funnel alignment rules.
  The SQL itself is pinned by `tools/verify-category-sync-sql.py`, which runs the exact statements
  against an in-memory sqlite3 database — run it after touching any of those `CASE` expressions.

### 26. Recurring Pattern Category Link vs Rename

- Risk: `recurring_patterns` used to link to categories by name in two places — (a) the dedup
  signature (`getBySignature` / `patternSignature`) and (b) the edit dialog, which resolved the
  category id from the stored `categoryName`. Once the category is renamed that snapshot goes stale,
  so (a) can register the same recurring expense twice, and (b) silently wipes a valid `categoryId`.
- Steps:
  - Let the app detect (or manually create) a recurring pattern for a merchant, filed under a
    sub-category such as `餐饮 - 早餐`, and **confirm** it (status CONFIRMED).
  - Rename that category — or its parent — in category management.
  - Record a new bill matching the merchant, then let the recurring scan run again.
  - Open the recurring list and count the entries for that merchant.
  - Edit the pattern, change **only the amount**, save, then inspect its category.
- Expected:
  - The recurring list shows **one** entry for that merchant, labelled with the **current** tree path.
  - A due-prompt for that pattern shows the current category name.
  - Saving the edit keeps the original `categoryId` (the category must not become unset), and the
    stored name refreshes to the current tree path.
  - Transfer / repayment patterns never acquire a category.
- Why this is manual: no Room/Robolectric tests, so the `UPDATE recurring_patterns …` statements and
  `getBySignature` cannot be asserted in JVM tests. `RecurringPatternCategoryLinkTest` covers the
  pure signature and the id-resolution rule the dialog feeds on.

### 27. Duplicate Category Merge (其他 / 其它)

- Risk: `category.json` ships 「其他」 and 「其它」 as two entries for the **same icon**, while
  `default_category.json` only seeds 「其它」. A ledger that ends up with both has two categories
  meaning exactly the same thing, each holding its own bills — they show as two rows in stats and
  two lines in the category budget.
- Steps:
  - Create both 「其他」 and 「其它」 as top-level categories of the **same type** (expense), file a
    few bills under each, and leave at least one bill that has only the name (no `categoryId`).
  - Delete a bill filed under 「其它」 so it lands in the recycle bin; also create a recurring
    pattern pointing at 「其它」.
  - Relaunch the app so the one-time merge runs.
  - Open category management, stats, and the category budget.
- Expected:
  - Only 「其他」 remains; 「其它」 is gone from category management.
  - Every bill that was under 「其它」 now shows under 「其他」 — stats shows a single row.
  - The recycled bill and the recurring pattern follow the same move; neither is left dangling.
  - Nothing happens when only one of the two exists (the common case).
  - If 「其它」 had sub-categories, it is **left alone** and a line is written to the log instead.
- Why this is manual: no Room/Robolectric tests, so the `UPDATE … SET categoryId = …` statements and
  the delete cannot be asserted in JVM tests. `DuplicateCategoryMergerTest` covers the decision rules
  (which pair, which direction, when to give up).

## Native Packaging Checks

### 28. Native Library 16 KB Page-Size Alignment

- Risk: every `.so` reached through `app/libs/sherpa-onnx.aar` and the LiteRT/TensorFlow Lite
  prebuilt used to declare only 4 KB aligned `PT_LOAD` segments. On a device booted with a 16 KB
  page size the package manager then runs the app in "16 KB backward compatibility mode" and shows
  the system `Android 应用兼容性` dialog on first launch, listing
  `lib/arm64-v8a/libtensorflowlite_jni.so`, `libsherpa-onnx-jni.so`, `libsherpa-onnx-c-api.so` …
  with `LOAD 区段未对齐`. Google Play requires 16 KB support for apps targeting Android 15
  (API 35)+, and from 2027-02-01 updates without it cannot be released.
- Steps:
  - Build the APK, then run `python tools/check-native-16kb.py app/build/outputs/apk/release/app-release.apk`.
  - Confirm the ZIP half separately with `zipalign -c -P 16 -v 4 app-release.apk`.
  - Install on a 16 KB device or emulator (`adb shell getconf PAGE_SIZE` must print `16384`) and
    launch it.
- Expected:
  - The checker reports 0 non-aligned libraries.
  - No `Android 应用兼容性` dialog on first launch.
  - Local ASR still initializes and transcribes, since the AAR upgrade also bumps the bundled
    onnxruntime build.
- Pinned versions: sherpa-onnx AAR v1.13.8 (16 KB support landed in k2-fsa/sherpa-onnx#2520,
  Aug 2025) and `com.google.ai.edge.litert:litert:1.4.2`. `org.tensorflow:tensorflow-lite` stays
  unaligned through its newest release 2.17.0, so it must not be reintroduced.

## Release Sign-off

Mark each item:

- PASS
- FAIL
- NOT TESTED

Release can be considered safe only if:

- All smoke checks pass.
- At least one AI text flow passes.
- At least one image/OCR flow passes.
- Overlay/service flow passes.
- Backup export + restore passes.
- If local ASR is a supported feature, one local ASR run passes.
- If Shizuku is a supported feature for your release audience, one Shizuku flow passes.

## Known Build-Level Protections Already Verified

These were already checked in the current codebase:

- Release APK builds successfully.
- Debug APK builds successfully.
- Key native libraries are packaged in release.
- Every packaged arm64-v8a library is 16 KB aligned: `python tools/check-native-16kb.py <apk>` reports 0 non-aligned (last verified on `app-debug.apk`; re-run after any native dependency bump).
- `@raw/assets`, `@raw/category`, and `@raw/default_category` remain reachable in release.
- Sensitive classes used by reflection/Gson such as `BuiltInCategory` and `SiliconFlowApi` are preserved appropriately for release behavior.
