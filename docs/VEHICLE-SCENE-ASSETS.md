# 车型背景素材

**冒险家保留 1.1.0 原图和原柔焦图，仍为默认选项**。按用户最终选择，不采用本轮冒险家重绘稿。航海家、飞行家以 **2022 年中国市场车型** 为外形参考，替换 1.1.0 中较新款的生成图。

湖景、建筑、黄昏配色、车辆位置及界面布局沿用既有设计。仅替换车与对应倒影、接地阴影，设置页同步使用配套柔焦版本。车型选择及其存储值保持不变，不改变配对、连接或车辆硬件参数。

航海家、飞行家及其配套柔焦素材通过 **内置 imagegen** 编辑生成，属于装饰性生成背景，并非官方车型实拍。实车照片用于核对年代、格栅、车灯及车身比例，未作为独立产品照片打包。

## 外形参考

| 车型 | 2022 款识别重点 | 参考 |
| --- | --- | --- |
| 冒险家 | 保留用户喜欢的原版风格，未替换 | 仓库 1.1.0 原素材 |
| 航海家 | 第一代国产车型的圆角格栅、较宽大矩形主灯、下方独立灯条，保留外露门把手 | [2022 年 2.7T 车型上市实拍](https://www.autohome.com.cn/news/202204/1244600.html) |
| 飞行家 | 改款前星辉格栅、双透镜主灯与下方独立灯组、长车身及涡扇式轮圈 | [2022 款上市报道与厂商图片](https://www.autohome.com.cn/news/202205/1247808.html) |

两款新图的图片身份参考来自上述 2022 款页面；旧的 2024–2026 提示词已被以下最终提示词取代。页面图片地址及本地参考文件保存在工作区 `design/vehicle-2022/reference/`，不打包进 APK。

车型设置在「车辆」中以单行「车型背景 · 当前车型」展示，不显示重复的「车辆外观」卡片标题。设置行仅在后面还有可见内容时绘制内部分隔线，单行卡片和卡片最后一项不带额外横线。

## 自定义背景

第四个选项「自定义」通过系统图片选择器导入一张图片，用于首页、设置与连接页。首次选择时显示「选择图片」；已有图片时可保存选择或「更换图片」。切回三个预设不会删除自定义图片，取消选择或导入失败也不会覆盖原图。背景设置不触发 CarPlay 重连。

导入在工作线程上进行：源文件上限 40 MiB，按采样限制解码后的长边为 2880 像素、总像素为 400 万，修正 EXIF 方向、展平透明背景并去除源元数据。图片复制到应用的 no-backup 私有目录，另存长边不超过 480 像素、经过两轮小半径盒式模糊的柔焦版本用于阅读界面，沿用现有居中裁切及文字遮罩。模糊计算仅在导入时执行，不依赖新版系统图形接口。后续不依赖源文件或图库授权；替换以新文件 ID 更新，避免旧图片缓存。

图片不随设置备份导出；恢复设置但缺少本地图片时回到默认冒险家。三个内置车型的原图和柔焦图均保持不变。

## 项目素材

全部位于 `common/src/main/res/drawable-nodpi/`：

| 车型 | 首页 | 设置页 |
| --- | --- | --- |
| 冒险家（默认） | `corsair_scene.png` | `corsair_scene_soft.png` |
| 航海家 | `nautilus_scene.png` | `nautilus_scene_soft.png` |
| 飞行家 | `aviator_scene.png` | `aviator_scene_soft.png` |

原始生成文件保留在 `/home/ubuntu/.codex/generated_images/01a16234-2c65-7221-a3a5-9a094926bf59/`；工作区副本在 `design/vehicle-2022/assets/`。

## 最终提示词

最终采用的首页两图：输入 1 为修改前 `corsair_scene.png` 场景；输入 2 分别为 2022 年航海家上市实拍、2022 款飞行家厂商图片。柔焦图以对应已完成首页图作为唯一编辑目标。下方冒险家提示词仅保留为弃稿记录，最终项目使用原图。

### corsair / sharp（弃稿，未采用）

生成原件：`/home/ubuntu/.codex/generated_images/01a16234-2c65-7221-a3a5-9a094926bf59/exec-f5bc9f0b-36c5-4793-b761-b4b7b0b5ccb5.png`

```text
Use case: precise-object-edit.
Asset: production wallpaper for an Android Lincoln head unit.
Input image 1 is the EDIT TARGET and defines the entire scene, camera and output framing. Input image 2 is the MODEL IDENTITY REFERENCE, an actual China-market 2022 Lincoln Corsair (冒险家), pre-facelift.
Replace ONLY the SUV in image 1 and its immediate contact shadow/reflection with a faithful black 2022 China-market Lincoln Corsair matching image 2's real bodywork geometry. This is an accuracy correction, not a redesign. Copy the reference car's compact proportions, relatively small chrome-surrounded rounded-rectangular star-pattern grille, the precise slim swept single lamp housings and their separate short horizontal daytime running lamps BELOW the main headlights, conventional exposed door handles, side fender badge, roofline and normal OEM multi-spoke wheels. The 2022 car has NO full-width front light strip, no gigantic modern grille. Do not retain the inaccurate headlight/grille design from image 1, and do not invent ornate headlights or oversized wheels. Use standard Chinese-market chrome trim rather than a blackout sport package.
Keep the same front three-quarter orientation as image 1 with the visible side extending to the LEFT and nose pointing toward the RIGHT/camera. Keep entire car within the existing vehicle footprint in the RIGHT half, with roof and wheel contact at the same scene heights; preserve blank left area for interface. Photorealistic, calm premium black automotive photograph with natural dusk reflections and fine details, not a cartoon or futuristic concept.
Invariant: keep the lake, mountain silhouettes, dusk clouds, town lights, glass building and its posts, paving and horizon in exactly the original positions. No change to camera, crop, color grade or brightness. Only car and associated shadow/reflection change. Do not transfer the reference's dealership background, number plate text, stickers or watermark. Do not add interface, captions, borders, logos outside the vehicle or model-name text. One full-scene image, 1620 by 971 pixels or same exact aspect ratio as image 1.
```

### corsair / soft（弃稿，未采用）

生成原件：`/home/ubuntu/.codex/generated_images/01a16234-2c65-7221-a3a5-9a094926bf59/exec-df290716-27bb-4f68-8c01-2fa0e43bc228.png`

```text
Use case: precise-object-edit. Create the settings soft-focus companion of this exact wallpaper. Apply ONLY a uniform subtle optical/Gaussian-style blur, about 7 pixels at the original width. Retain the identical 2022 Lincoln Corsair, its silhouette, every grille, lamp, handle and wheel, and all background pixels' placement, color, exposure and framing. No redesign, regeneration, changes to car or scenery, no added text, no crop. Same dimensions and aspect ratio as source. One soft full-scene wallpaper.
```

### nautilus / sharp

生成原件：`/home/ubuntu/.codex/generated_images/01a16234-2c65-7221-a3a5-9a094926bf59/exec-187913c8-9f74-4530-9870-0c67a037013d.png`

```text
Use case: precise-object-edit. Asset: production wallpaper for an Android Lincoln head unit.
Input image 1 is the EDIT TARGET defining the entire scene, camera and full framing. Image 2 is ONLY a real 2022 China-market car MODEL IDENTITY REFERENCE; do not copy its background, camera angle, text or watermark.
Replace ONLY the SUV and its immediate contact shadow/reflection in image 1 with a faithfully accurate black car matching image 2. This is a real model-year identity correction, NOT a concept design.
Preserve image 1's front three-quarter camera orientation: visible side extends LEFT, nose points RIGHT and towards camera. Regardless of the reference photograph's direction or overhead view, show the replacement from exactly the original scene's viewpoint. Entire vehicle stays in the RIGHT-half vehicle footprint, roof and wheels at the same scene heights; preserve negative left space for interface.
Keep every other part of image 1 unchanged: exact clouds, mountains, lake, town lights, building, posts, paving, camera framing, horizon, color grading and brightness. Photorealistic premium BLACK automotive photography at dusk, credible reflections. Correct the associated reflection and contact shadow to match. Standard chrome luxury trim and correct factory wheels.
Do not add UI, borders, text captions, number plate writing, dealer stickers, people or logos outside the car. Do not transfer any reference background or watermark. One full scene, 1620 by 971 pixels or same exact aspect ratio.
Vehicle specification:
The car MUST be the 2022 China-market Lincoln Nautilus 航海家, the FIRST-generation pre-2023 replacement model, matching the reference photo exactly. Rounded midsize two-row body (4849mm long, 2850mm wheelbase), tall compact hood, rounded chrome-framed star-pattern grille, the reference's relatively LARGE rounded rectangular headlamp housings with dual lenses and upper/outer LED outline, separate slim horizontal light strips below the main lamp, traditional protruding door handles, chrome fender badge and window surround. Match the actual headlamp housing and bumper shapes in the reference; do not substitute the slimmer Corsair headlight assembly. The classic grille has no horizontal bar bisecting it, and no continuous front light strip. NO 2023–2026 new-generation Nautilus, NO flush handles, NO narrow light-bar front, NO Lincoln Z styling. The reference is the source of truth for the bodywork.
```

### nautilus / soft

生成原件：`/home/ubuntu/.codex/generated_images/01a16234-2c65-7221-a3a5-9a094926bf59/exec-ba073d47-c73f-41c6-9ec7-8c2cf874d353.png`

```text
Use case: precise-object-edit. Create the settings soft-focus companion of this exact 2022 Lincoln nautilus wallpaper. Apply ONLY a uniform subtle optical/Gaussian-style blur, about 7 pixels at the original width. Retain the identical vehicle, its silhouette and length, every grille, lamp, handle and wheel, and all background pixels' placement, color, exposure and framing. No redesign or change to car or scenery. No text, no crop. Same dimensions and aspect ratio as source. One soft full-scene wallpaper.
```

### aviator / sharp

生成原件：`/home/ubuntu/.codex/generated_images/01a16234-2c65-7221-a3a5-9a094926bf59/exec-381de68a-c52a-4f76-8df7-4c309ebc0459.png`

```text
Use case: precise-object-edit. Asset: production wallpaper for an Android Lincoln head unit.
Input image 1 is the EDIT TARGET defining the entire scene, camera and full framing. Image 2 is ONLY a real 2022 China-market car MODEL IDENTITY REFERENCE; do not copy its background, camera angle, text or watermark.
Replace ONLY the SUV and its immediate contact shadow/reflection in image 1 with a faithfully accurate black car matching image 2. This is a real model-year identity correction, NOT a concept design.
Preserve image 1's front three-quarter camera orientation: visible side extends LEFT, nose points RIGHT and towards camera. Regardless of the reference photograph's direction or overhead view, show the replacement from exactly the original scene's viewpoint. Entire vehicle stays in the RIGHT-half vehicle footprint, roof and wheels at the same scene heights; preserve negative left space for interface.
Keep every other part of image 1 unchanged: exact clouds, mountains, lake, town lights, building, posts, paving, camera framing, horizon, color grading and brightness. Photorealistic premium BLACK automotive photography at dusk, credible reflections. Correct the associated reflection and contact shadow to match. Standard chrome luxury trim and correct factory wheels.
Do not add UI, borders, text captions, number plate writing, dealer stickers, people or logos outside the car. Do not transfer any reference background or watermark. One full scene, 1620 by 971 pixels or same exact aspect ratio.
Vehicle specification:
The car MUST be the 2022 China-market Lincoln Aviator 飞行家, the second-generation PRE-FACELIFT original 2020–2022 design shown in reference 2. Long three-row body (5080mm long, 3025mm wheelbase), rear-wheel-drive proportions with long sculpted hood, chrome rounded rectangular rectangular-pattern star grille, relatively short broad rectangular dual-projector headlamp housings with a subtle downward outer LED corner, separate narrow horizontal daytime strip below main lamp, small multi-element lower fog lamp units at the outer bumper, a strong long chrome-accented fender spear, traditional exposed handles, elegant long floating roof and OEM turbine multi-spoke wheels. Preserve the distinctive longer cabin and upright rear quarter rather than drawing another Corsair. Keep standard chrome trim. NO giant 2025 facelift grille, NO light strip across grille, NO thin modern eyebrow headlamps, NO Navigator, no inflated cartoon SUV. Accurately follow the model bodywork of the real reference photo but retain the edit target's low front three-quarter viewpoint, not the reference's overhead camera.
```

### aviator / soft

生成原件：`/home/ubuntu/.codex/generated_images/01a16234-2c65-7221-a3a5-9a094926bf59/exec-f6050a0a-36cf-4407-af9b-157a0a27dc3e.png`

```text
Use case: precise-object-edit. Create the settings soft-focus companion of this exact 2022 Lincoln aviator wallpaper. Apply ONLY a uniform subtle optical/Gaussian-style blur, about 7 pixels at the original width. Retain the identical vehicle, its silhouette and length, every grille, lamp, handle and wheel, and all background pixels' placement, color, exposure and framing. No redesign or change to car or scenery. No text, no crop. Same dimensions and aspect ratio as source. One soft full-scene wallpaper.
```
