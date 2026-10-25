# CarPlay for Lincoln / Ford

[主說明](README.md) · [簡體中文](README.zh-CN.md)

這是為 Lincoln、Ford Android 車機調整的 CarPlay 接收器，支援 USB 與無線連線。安裝後的應用程式名稱是 **CarPlay**，正式版套件名稱是 `cn.manstep.phonemirrorBox`。

本分支以 [xcertplay](https://github.com/shilapi/xcertplay) / DiPlay 為基礎持續開發，但已獨立維護介面、車型支援、套件名稱、簽章、版本及更新頻道，不使用上游 DiPlay 的 APK 或更新來源。

> 這是個人維護的實驗專案，並非 Apple、Lincoln 或 Ford 官方產品。各車型、車機韌體及 iOS 版本的相容性可能不同，請停車後測試。

## 專案資訊

| 項目 | 目前內容 |
| --- | --- |
| 應用程式名稱 | CarPlay |
| 目前正式版 | 1.1.8 |
| Release 套件名稱 | `cn.manstep.phonemirrorBox` |
| Debug 套件名稱 | `cn.manstep.phonemirrorBox.hudtest` |
| 最低系統 | Android 7.1（API 25） |
| 主要硬體 | Lincoln/Ford Freescale MEK / i.MX8 Android 車機 |
| 開發分支 | `lincoln-0.2.20` |

## 下載與線上更新

- [GitHub 最新正式版](https://github.com/lianjiawei/DiPlay/releases/latest)
- [1.1.8 中國大陸鏡像 APK](https://weclaw-agent.cloud/diplay-updates/assets/v1.1.8/CarPlay-1.1.8.apk)
- [1.1.8 更新說明](docs/RELEASE-NOTES-1.1.8.md)
- [專案倉庫](https://github.com/lianjiawei/DiPlay)

應用程式內的更新位於 **設定 → 關於 → 檢查更新**。程式會先讀取 `https://weclaw-agent.cloud/diplay-updates/releases.json`，鏡像無法使用時再改用 `https://api.github.com/repos/lianjiawei/DiPlay/releases?per_page=30`。鏡像同時提供 APK 與 `SHA256SUMS.txt`，下載後仍會檢查檔案雜湊、套件名稱及 Android 簽章。

## Lincoln / Ford 調整

- 重新設計橫向主畫面及設定；待連線、連線中、已連線和重新連線共用同一主畫面。
- 主畫面可選 Lincoln 或 Ford Logo。
- 可選 Corsair（冒險家）、Nautilus（航海家）、Aviator（飛行家）或自訂背景。
- 支援目標車機的音訊關閉鍵、方向盤切歌鍵及中控切歌鍵。
- 相容原車儀錶可顯示下一轉向、距離、路名、1.1.8 新增的路口進度條，以及歌曲或歌詞文字。
- 針對目標 i.MX8 車機調整影片輸出、音訊行為和流暢模式。
- 不適用於 Lincoln/Ford 硬體的上游車型專用選項會在目標車機上隱藏。

## 其他主要功能

- USB、車機熱點、Wi-Fi Direct、現有 Wi-Fi / 同一區域網路 CarPlay。
- 解析度、影格率、圖示/文字大小、畫面、Dock、系統列及 CarPlay 日夜模式設定。
- 導航播報時降低媒體音量，並可調整降低幅度與恢復時間。
- Siri、通話、語音訊息與車機麥克風相容處理。
- 設定搜尋、分類設定、QR Code 備份/還原和本機診斷報告。
- 深色車機介面支援 1280×720、1920×720、超寬螢幕和手機橫向視窗。

## 安裝與升級

1. 將 APK 下載到 Android 車機，不要安裝在 iPhone。
2. 允許車機安裝未知來源應用程式。
3. 開啟 CarPlay，依指引允許藍牙、Wi-Fi/定位及麥克風權限。
4. 選擇連線方式，並在 iPhone 上接受 CarPlay。

基礎 CarPlay 不需要 root；依賴特定韌體的擴充功能會明確標示 ADB 或實驗性需求。

目前 1.1.x 正式版使用相同套件名稱與簽章，可直接覆蓋並保留設定。早期 `com.corsairplay` 套件、簽章不同的第三方同名 APK、Debug 與正式版不能互相覆蓋。若 Android 顯示簽章衝突，請先備份設定並確認舊 APK 來源；解除安裝會清除應用程式資料。

## 從原始碼開發

主要應用程式模組是 `:mobile`。開發環境需要 Android Studio、JDK 25、Android SDK Platform 37、Build-Tools 36.0.0、Platform-Tools 及 NDK 28.2.13676358。完整步驟請見[建置文件](docs/BUILD.md)。

```sh
./gradlew :mobile:assembleDebug
```

一般原始碼 APK 不包含 CarPlay 執行身分，只適合開發和介面測試。可獨立連接 iPhone 的建置必須從 Git 倉庫外提供執行身分；正式發布還需要本分支自己的 Android 簽章金鑰。私密金鑰、keystore 和本機設定都不會進入 Git。

## 診斷與回報

遇到問題時，開啟 **設定 → 診斷 → 儲存診斷報告**。報告通常儲存在 `Downloads/Lincoln`，不會自動上傳。回報前請移除熱點密碼和裝置識別資料，並提供車型、車機 Android/韌體、iPhone/iOS、連線方式、重現步驟及故障時間。

本倉庫目前未啟用 GitHub Issues；需要回報時，請從 [lianjiawei/DiPlay 專案首頁](https://github.com/lianjiawei/DiPlay)查看最新聯絡方式。

## 風險與授權

- 本專案未經 Apple 認證；公開 APK 內含實驗性 CarPlay 配件身分，取得 APK 的人可以擷取它。
- iOS、車機韌體或儀錶應用程式更新後，相容行為可能改變。
- 車機專用功能只在相應硬體與韌體存在時生效，不代表支援所有 Lincoln 或 Ford 車型。
- 駕駛途中請勿調整設定或查看診斷資料。

專案以 xcertplay 為基礎，主體遵循 GPL-3.0；部分介面及資料改編自 DiAuto，相關內容遵循 AGPL-3.0。散布 APK 或修改版時必須保留原有著作權、授權條款及對應原始碼。請參閱[中文授權說明](LICENSE-NOTICE.zh-CN.md)及[第三方聲明](docs/THIRD_PARTY_NOTICES.md)。

CarPlay 是 Apple Inc. 的商標；Lincoln 與 Ford 是各自權利人的商標。本專案與這些公司沒有從屬或認可關係。
