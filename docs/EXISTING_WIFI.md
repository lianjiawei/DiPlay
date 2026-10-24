# Existing Wi-Fi / Same LAN

Connect the Android receiver to the external router in system Wi-Fi settings.
In Connection setup, choose **Existing Wi-Fi / Same LAN**, select or enter that
network's name, enter its WPA2 password, and save. Keep Bluetooth enabled and pair
the iPhone with the receiver. Saved changes apply at the next CarPlay connection;
saving settings does not interrupt a running session.

The Wi-Fi picker still discovers current, saved and nearby network names, with
manual entry and system settings as fallbacks. The receiver uses its existing
station connection; the CarPlay bootstrap does not join Wi-Fi, create a hotspot,
or change the default route. The authenticated iAP2 session sends the network
configuration (`0x5703`) and complete wireless StartSession parameters (`0x4301`)
to the selected iPhone.

## Credential handling

The settings dialog and projection quick menu use the same saved credentials.
Passwords remain masked, cancel preserves the saved values, and changing the name
clears the staged password. A readable live SSID must match the credential SSID
before bootstrap. A restricted/redacted system SSID can use the entered name.
Passwords and network names are excluded from settings backup/import and logs.

The experimental no-password selection from 1.1.3-rc1 has been removed. Existing
saved credentials are preserved on upgrade; the retired mode flag is ignored and
removed on save. A missing password opens setup from Home or shows a setup prompt
in projection, without starting a failing connection loop. Unfinished LAN settings
do not prevent USB or Wi-Fi Direct use.

Open and enterprise networks cannot use the WPA2 credential bootstrap. On modern
Android, a receiver reporting SAE remains accepted as in the original flow:
a mixed WPA2/WPA3 router can negotiate SAE with Android and still offer WPA2 to
the iPhone. This does not establish support for WPA3-only routers.

## Hardware evidence, 24 October 2026

The user tested 1.1.3-rc1 with a Huawei ELS-AN00 receiver (Android 12/API 31),
iPhone 17/iOS 27 as reported by the user, and the same external router used for
both methods. The supplied 07:45 diagnostic report records six credential-based
LAN sessions becoming active. Their first AirPlay TCP connections arrived 291,
141, 87, 91, 82 and 89 ms after StartSession. The subsequent password-free attempts
completed Bluetooth identification/authentication but accepted no AirPlay TCP
connection; one reached the first-TCP timeout.

Both paths read the active Wi-Fi and used the same channel 153. The evidence
localizes the regression to the reduced, password-free bootstrap, rather than
failure to discover the system Wi-Fi. It does not reveal iOS's internal rejection
reason or distinguish the significance of each omitted message/field. The app
therefore keeps only the working credential-based path. Successful startup is
not a claim of indefinite session stability or universal router/iOS compatibility.

## Compatibility and regression checks

The manager remains compatible with API 25, guards newer network APIs, ignores
cellular/VPN routes, and invalidates a session when its Wi-Fi link changes. Tests
cover credentials, saved-setting migration, live-SSID mismatch, redacted SSIDs,
network callbacks, address families, settings save/cancel, and unchanged USB/P2P
configuration. Device testing is still needed for new receiver/router combinations.

A target iPhone acting as the hotspot is a different topology from an external
router. The user previously demonstrated Wi-Fi Direct while that hotspot remained
on. Keep that tested option available; do not infer hotspot incompatibility merely
from missing Bonjour events or unavailable Android kernel counters.

## Builds

A source-only debug APK omits runtime accessory authentication. Use the external
runtime assets described in [BUILD.md](BUILD.md), following [SECURITY.md](../SECURITY.md).
Authentication inputs and Android signing material must remain outside Git. The
Android APK signing key is separate from the CarPlay accessory identity.
