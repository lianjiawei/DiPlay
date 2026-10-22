# 个人版发布签名

## precision.10 起的新签名

自 2026-10-22 的 `precision.10`（versionCode 65）起，个人版使用新的 Android APK 发布签名。
包名继续为 `cn.manstep.phonemirrorBox`，最低 Android 版本继续为 7.1（API 25）。

| 版本 | Android 签名证书 SHA-256 |
| --- | --- |
| precision.9（旧个人签名） | `b742c10e5d21d038bf2f2f4bf725a0ed63713bcad2535ab44e3255493b5592d0` |
| precision.10 起 | `f91fee2f5331241e265fbbfeca6c60ef27201b9e19c55b91ec4b137c3af5eae0` |

旧签名的私钥未迁移到当前开发环境；本次不提供由旧签名授权的密钥轮换链。
旧签名安装不能直接覆盖为新签名安装，包括在 Android 7.1、8.1 车机上使用应用内更新。

## 首次换装

1. 先记下需要保留的连接、画面和音频设置。
2. 从车机系统设置卸载旧个人版 DiPlay/CorsairPlay。卸载会清除应用设置。
3. 手动安装 `CorsairPlay-precision.10.apk`，重新授权并设置连接方式。
4. 后续版本继续使用新签名，即可正常覆盖升级并保留设置。

更换的是 Android APK 签名。CarPlay 运行认证资源仍沿用 `precision.9`，其内容不因本次 Android 签名变更而修改。

## 开发与备份

发布构建继续使用 [构建文档](BUILD.md) 中的 `ANDROID_KEYSTORE_*`、`ANDROID_KEY_ALIAS` 和 `ANDROID_KEY_PASSWORD` 环境变量。
新 keystore 和密码配置保存在开发机的私有目录中，不进入 Git、源码包或公开 Release 附件。
开发者应将 keystore 及对应密码配置备份到自己的其他设备，后续构建固定沿用同一份密钥。

发布前使用 `apksigner verify --verbose --print-certs` 核对上述证书指纹，并验证 APK 的版本、包名、最低 API 和校验和。
关于签名匹配与密钥保管，参见 [Android 官方签名说明](https://developer.android.com/studio/publish/app-signing)。
