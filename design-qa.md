# Native glass UI implementation review

The existing Android application remains the implementation target. The selected blue-grey dusk / black Lincoln Corsair direction is implemented in app chrome, using shared native materials rather than a separate web prototype.

## Scope and behavior boundary

- Shared dark/light palette, glass card and button materials, dialog surfaces and native dialog actions.
- Home retains iPhone selection, connect / return to projection, disconnect, USB, Settings, update notice, permission and hotspot recovery actions.
- Decorative connecting ring runs only while visible; system animation disablement is respected.
- Main-screen rendered-frame counters control presentation of the preparation screen. A separate, noninteractive success badge fades for 200 ms over the actual first frame. Video geometry, opacity, existing media handling inside callbacks, decoder and protocol implementation remain unchanged.
- Settings retain all existing categories, search, scroll behavior, preferences, default values and Save / Apply and reconnect confirmation flows.
- Lincoln artwork applies to the existing Lincoln preset. Other vehicle presets retain their existing identity and use the neutral palette.
- Update, storage cleanup and installation bodies remain unchanged. Thirteen existing operational method bodies were compared byte for byte with the baseline.

## Visual evidence

Generated native screenshots live in `common/build/reports/glass-ui/`. Run `:common:testDebugUnitTest --tests '*GlassUiRegressionTest'` to regenerate them. These are Android native rendering captures, not design mockups or phone video recordings. The capture-only package version is labelled `UI preview`; production version metadata is untouched.

| Capture family | Window and font | Purpose |
| --- | --- | --- |
| landscape-zh | 800 × 480 dp, 1.0 | Home composition, complete initial home actions, connecting preparation, dark/light settings and choice dialog |
| short-wide-zh | 960 × 360 dp, xhdpi, 1.0 | Low-height car display, initial visibility of all home actions |
| expanded-en | 1200 × 720 dp, 1.3 | Expanded Settings rail and large English text |
| compact-zh | 320 × 600 dp, 1.3 | Narrow portrait and large Chinese text |
| compact-ar-rtl | 320 × 600 dp, RTL, 1.3 | Narrow right-to-left headers, settings and dialogs |

Dark and light Home, Settings overview, Display, About and native frame-rate choices are captured. The connecting preparation view is captured separately without starting a controller or a transport. The first-frame presenter is verified for reduced motion, duplicate callbacks, cancellation and reconnect during a fade.

Differences from the illustrative reference preserve real application state: an unpaired iPhone shows the existing setup warning, not a simulated connected state. Existing descriptions and category links remain available and can scroll. Short screens omit the decorative home headline and reduce the ring size so primary actions fit. Settings use a pre-softened background and static native highlights to avoid adding a live blur workload on older head units.

## Findings resolved

- Home footer buttons initially measured to zero height: fixed natural measurement and equal sibling heights; assertions now check actual height and initial visibility.
- Short landscape displays placed footer actions below the initial viewport: compact presentation now fits them in the initial view.
- Narrow RTL settings title used ellipsis: fit the title to the available header width.
- Portrait artwork highlights competed with connection instructions: use a reading veil and verify secondary-text contrast of at least 4.5:1 on a grid in both themes.
- Detached preparation screenshots captured before size changes settled: use completed measurement passes, matching Android traversal, and assert text fits its measured height.
- Older tests encoded the superseded two-column home and a fixed child index for the preparation title: updated to check the approved footer arrangement and the actual title view, preserving the compact/full and viewport-fit checks.

## Validation gate

The required full CI command passed for source commit `1f297f4b`: **3339 tests, zero failures/errors**, all three requested Lint tasks and all three debug assembly tasks. The final resolution matrix completed **594 page states across 9 windows × 2 font scales**, with zero reported ellipsis, clipping, bounds or scroll findings. Native preview capture covers dark/light, compact/expanded and Arabic RTL.

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```

`python3 scripts/check_public_tree.py` and `git diff --check` also pass. Local execution used the prepared Android environment, offline dependencies, one Gradle worker and a 1 GiB Gradle heap. Common tests ran in this full invocation. Gradle reused the successful Shared and Home test results because their tracked inputs were unchanged.

The resolution matrix covers 800×480, 1024×600 mdpi and xhdpi, 1280×720, 1920×1080, 1920×720, 320×600 portrait, 400×300 split window and Arabic RTL 1280×720. Every configuration runs at font scales 1.0 and 1.3, including all settings categories and update stages. It checks ellipsis, horizontal and vertical text clipping, parent bounds and scroll access.

No release version, application ID, signing configuration or release metadata is changed. No APK is published as part of this implementation. The rollback baseline remains `pre-ui-redesign-20261020`.

## Physical-device checks

Automated/native rendering validation does not measure real car GPU frame pacing or an iPhone session. Confirm ring smoothness, the first-frame handoff and touch/remote focus on the vehicle before publishing a release APK. A real CarPlay stream is deliberately not replaced with the illustrative phone screen from the design reference.
