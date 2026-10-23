# 车型背景素材

默认冒险家继续使用原有 `corsair_scene.png` / `corsair_scene_soft.png`，两文件未改动。

新增素材通过内置 imagegen 生成，属于装饰性插画背景，并非车型实拍。以原冒险家背景为编辑目标，保留场景与构图，仅替换车型及相应倒影。车型选择不会改变配对身份、车型硬件参数或 CarPlay 传输。

- 航海家：`common/src/main/res/drawable-nodpi/nautilus_scene.png`
- 飞行家：`common/src/main/res/drawable-nodpi/aviator_scene.png`
- 对应柔焦背景：同目录下的 `nautilus_scene_soft.png`、`aviator_scene_soft.png`。

外形参考：[林肯官方车型目录](https://www.lincoln.com/suv-crossover-models/)、[林肯中国飞行家页面](https://www.lincoln.com.cn/vehicle/suvs-2024aviator/)。仅用于车型特征核对，未打包第三方产品图片。

## 最终提示词

### nautilus

Use case: precise-object-edit. Asset: Android head-unit wallpaper. Input image is the EDIT TARGET. Replace ONLY the black SUV with the requested Lincoln vehicle, keeping every other part of the image unchanged: exact same camera, framing, canvas aspect ratio, dusk sky, mountains, lake, town lights, building, paving, lighting direction, color grade and scene perspective. Keep the replacement parked in the same right-side position, same front three-quarter orientation, facing camera/right, with the entire vehicle visible. Preserve the empty left half for UI text. Match realistic black paint, high-quality sharp automotive photography and wet-paving reflections. Correct only the vehicle and its immediate reflection/contact shadow to fit the new model. No captions, UI, badges with model names, watermark, borders, split panels or collage. Deliver one full scene at the same dimensions and aspect ratio as the reference, not a crop. Vehicle: a black 2024–2026 China-market Lincoln Nautilus (林肯航海家), clearly the midsize Nautilus rather than a Corsair: modern wider body, long slim headlights with the continuous horizontal front light bar across the broad patterned grille through the Lincoln emblem, sleek flush door handles and Nautilus proportions. Retain original background exactly.

### aviator

Use case: precise-object-edit. Asset: Android head-unit wallpaper. Input image is the EDIT TARGET. Replace ONLY the black SUV with the requested Lincoln vehicle, keeping every other part of the image unchanged: exact same camera, framing, canvas aspect ratio, dusk sky, mountains, lake, town lights, building, paving, lighting direction, color grade and scene perspective. Keep the replacement parked in the same right-side position, same front three-quarter orientation, facing camera/right, with the entire vehicle visible. Preserve the empty left half for UI text. Match realistic black paint, high-quality sharp automotive photography and wet-paving reflections. Correct only the vehicle and its immediate reflection/contact shadow to fit the new model. No captions, UI, badges with model names, watermark, borders, split panels or collage. Deliver one full scene at the same dimensions and aspect ratio as the reference, not a crop. Vehicle: a black 2024–2026 China-market Lincoln Aviator (林肯飞行家), clearly the larger three-row Aviator rather than a Corsair or Navigator: longer elegant body, sculpted hood, broad starburst mesh Lincoln grille, slim swept LED headlamps, tall upright side profile and long roof with characteristic tapering rear roofline, chrome fender detail and correct Aviator proportions. Do not substitute a Navigator. Retain original background exactly.

### 两张柔焦背景

Use case: precise-object-edit. Make a soft-focus version of this exact image for the settings-page background. Apply a uniform subtle Gaussian-style blur equivalent to a 7-pixel radius at the original resolution. Preserve the entire original image composition, the exact vehicle model and its identity, colors, exposure, sky, lake, building, camera and dimensions. Do not redraw or change any subject or object. No text, borders or overlays. One blurred full-scene image, same aspect ratio.
