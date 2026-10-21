# Native UI fidelity review

Visual verdict: **passed for this UI-only correction**, with the reference/state differences listed below. This is a native Android implementation review, not a claim of pixel-identical artwork or a physical-vehicle sign-off.

## Reference and comparison method

The approved `docs/ui-redesign/v1/screens/` images remain immutable. The [eight-page comparison PDF](docs/ui-redesign/implementation/fidelity/design-vs-native.pdf) places each approved reference and its matching native Android capture together in the same review input. The [comparison index](docs/ui-redesign/implementation/fidelity/comparison-index.json) records source dimensions, native dimensions, focus crops and screenshot hashes. Images are fitted proportionally into equal comparison frames without stretching. Dialog references are cropped to their dialog bounds; their surrounding designed app background is excluded.

| Page | Approved reference | Native fixture |
| --- | --- | --- |
| Home, paired/idle | 01-home | 800×480 dp, xhdpi, Chinese, dark |
| Home, connecting | 02-connecting | Same viewport and paired phone, inactive mock session |
| Settings overview, connected | 04-settings-overview | 1200×720 dp, mdpi, Chinese, dark, connected fixture |
| Display | 05-display-settings | Same settings viewport, dark |
| Frame-rate choice | 06-choice-dialog | Native choice dialog; mock running session exposes the original Apply and reconnect action |
| About, offline | 07-about-update | Same settings viewport, no validated network |
| Update storage | 08-storage-dialog | Native scan of the actual empty fixture directory |
| Compact display | 09-compact-display | 324×540 dp, xxhdpi (972×1620 pixels), Chinese, dark |

All eight pairs were inspected together after the corrections. Additional [native evidence](docs/ui-redesign/implementation/fidelity/screenshots/) includes 1200×720 dp English at font scale 1.3, 320×600 dp Arabic RTL at font scale 1.3, a light-theme Home and About, and the separate host preparation screen. Native fixtures exclude system bars; hardware insets remain subject to the existing window handling.

## Findings and corrections

| Priority | Finding in earlier implementation | Final correction / evidence |
| --- | --- | --- |
| P1 | Generic gradients and a different vehicle composition weakened the selected design direction. | Replaced the Lincoln scene, isolated thin luminous ring and glass material layers; native text and controls remain separate. Home and connecting comparisons use the selected composition. |
| P1 | Android cloning of a button state drawable silently replaced the custom glass renderer with a plain gradient. | Preserved the drawable subclass through ConstantState; a native pixel comparison verifies that cloned stateful material matches the direct rendering. Dialog captures retain the glass material. |
| P1 | Dialog actions appeared to touch and left inadequate visual separation. | A shared 16 dp action gap, equal measured heights in horizontal rows and radius derived from actual height. The action container chooses horizontal or vertical layout from measured text width, preserving the original button objects and listeners. A complete run exposed zero-width actions when the framework stacked a narrow update dialog; the responsive container fixes this, and the 320 dp / 66-state targeted matrix now passes. Native assertions check actual button bounds, equal heights, minimum touch targets and no ellipsis across the capture matrix. |
| P1 | Narrow settings rows could overlap their value, description and help affordance. | Measure title/value widths, reserve the description/help gutter and allow natural row growth; recaptured large Chinese/English and Arabic RTL. |
| P1 | Portrait scenery could reduce secondary-text contrast. | Separate reading veils and translucent card treatment; native sampled contrast tests require at least 4.5:1 for Home and the settings surface in both themes. |
| P2 | The Home connecting state retained the idle headline and crowded primary/disconnect actions. | Uses the short connecting headline and rotating ring; disconnect occupies the left footer, settings stays right, and the existing USB action remains available below the hero. Original callbacks are retained. |
| P2 | Compact header repeated Settings and constrained the title. | Combined Back/Settings capsule and a flexible header; title/caption sizing grows with font scale. |
| P2 | Repeated helper copy made the hierarchy too dense. | Removed redundant quick-setting captions, appearance/picture captions, connected-state explanatory text and duplicate backup description. Kept reconnect, permission, cleanup and error information. Longer setting explanations remain available through their existing details interaction or long press. |
| P2 | About and update controls did not follow the intended hierarchy. | Native brand hero, equal-height update/history controls and a storage destination row; compact hero stacks vertically so Arabic/large text fits. |

No unresolved blocking visual or layout findings were found in the reviewed native states. Shared horizontal action rows retain equal heights; text can wrap on narrow windows instead of shrinking the touch area. The 16 dp dialog gap is checked in both text directions.

## Recorded differences and review limits

- Per the user's latest instruction, the implementation intentionally contains less helper copy than the original generated design images. Existing category links, language, open-source credits, warnings and recovery actions remain accessible by scrolling.
- The selected static scene is recropped across viewports. Settings use a softened scene and reading veil rather than live GPU blur; the exact background blur, vehicle scale in portrait, platform radio glyph and theme icon differ from the illustration. These are visible in the comparisons rather than hidden by a whole-screen mockup.
- Values follow real preferences and fixture state: the quick auto-connect switch is disabled in capture setup; update checks use the current offline state; storage contains 0 files/0 B and its cleanup action is disabled. The reference contains illustrative file counts and an in-use file warning, which are not fabricated in the actual scan.
- Test-only version text is `UI preview`. Production version, application ID and signing configuration are unchanged.
- Reference 03 illustrates an iPhone video frame. The application always displays the real phone stream. The preparation screen is additional app chrome, not a substitute for that stream. Existing first-frame presentation tests cover cancellation, repeated callbacks and reduced motion; real car GPU pacing, touch/remote focus and live iPhone handoff still require physical-device verification before an APK release.

## Behavior boundary

The [boundary record](docs/ui-redesign/implementation/fidelity/behavior-boundary.json) compares 14 operational method bodies byte for byte against `9caa2b0efdba951fa29dc03836736868031eba88`, including connection, projection entry, phone selection, quick-setting persistence, update download/history, storage cleanup and installation. The TextureView/gesture setup is also unchanged. No shared protocol/decoder code or build metadata changed. Dialog styling preserves native adapters, checked positions, button objects and original Save/Apply/Dismiss listeners. A presentation-only action container owns responsive button measurement; it does not replace download/install handlers.

The rollback tag remains `pre-ui-redesign-20261020`. This task commits source and comparison material; it does not publish an APK.

## Final verification

Required full CI **passed** for source commit [`fb871e6a`](https://github.com/lianjiawei/DiPlay/commit/fb871e6ae65681a76134ac92e4ee6adfe9558842): **3344 tests, zero failures/errors/skips** (Shared 1609, Common 1729, Home 6), all three requested Lint tasks and all three debug assemblies. The full invocation completed in 11m 39s. The [machine-readable record](docs/ui-redesign/implementation/fidelity/validation.json) preserves the counts and source commit.

The final resolution matrix covered **594 page states across 9 windows × 2 font scales**, with **zero findings** for ellipsis, text clipping, parent bounds and scroll access. Windows: 800×480, 1024×600 mdpi/xhdpi, 1280×720, 1920×1080, 1920×720, 320×600 portrait, 400×300 split window and Arabic RTL 1280×720; font scales 1.0 and 1.3. Lint has no Fatal/Error findings; the existing warning counts remain 9/7/1 for Mobile/Home/Maphost.

The original v1 reference hashes match their archived manifest. The eight paired native screenshot hashes match the final source captures. Source changes were frozen during this successful full invocation; subsequent changes contain only documentation and capture archives.

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```

Local execution uses the prepared Android SDK, offline dependencies, one Gradle worker and a 1 GiB Gradle heap. `python3 scripts/check_public_tree.py` and `git diff --check` are also required. Native captures are generated by `GlassUiRegressionTest`; the broader matrix is `UiResolutionRegressionTest`.
