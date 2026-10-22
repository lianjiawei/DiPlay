# Existing Wi-Fi / Same LAN

Connect the Android receiver and iPhone to the same third-party router or portable
Wi-Fi in system settings. In DiPlay → Connection setup → Wireless, select
**Existing Wi-Fi / Same LAN**. DiPlay automatically reuses the head unit's connected
system Wi-Fi; no network password is required. Keep Bluetooth enabled and the iPhone
paired with the receiver, then start wireless CarPlay as usual.

Both clients must already be connected and able to
communicate; allow multicast DNS for service discovery. Prefer 5 GHz when supported.

DiPlay attaches to the existing connection without creating an AP, joining another
network or changing the default route. The router supplies Internet access.
The connected system SSID takes priority over an old saved name. The manager tries station
WifiInfo when the network capabilities snapshot hides that name; if both snapshots
hide it, a manually entered name is used as a fallback. A blank name selects automatic
detection; it no longer prevents choosing this mode. Precise location
permission and enabled system location can make network details readable, but are
not tied to optional GPS reporting. When automatic startup has neither a readable
name nor a manual fallback, it requests location access to read the name.

**Choose system Wi-Fi** lists the current connection, system-saved networks when
Android exposes them, and the latest system scan results, with duplicate names
removed. Reading the list does not switch networks. On Android 7.1–9, selecting
another network opens a confirmation where a WPA2 password can be entered; a saved
network can use its existing system credentials. **Enter network manually** retains
SSID/password entry for hidden or missing networks. The explicit **Connect Wi-Fi**
action asks Android to connect the head unit, and may interrupt an active CarPlay
session. It does not configure the iPhone. Newer Android versions and unsupported
security types use system Wi-Fi settings to connect. A request being accepted is
not proof that association succeeded; wait until Android reports the connection.
Passwords entered here are passed to Android's Wi-Fi configuration, not retained
as CarPlay credentials or sent to the iPhone. Android does not expose a saved
network's plaintext password to ordinary apps.

## Implementation

`ExistingWifiManager` implements the wireless backend contract. It selects the single
non-VPN Wi-Fi Network, obtains its interface and addresses from LinkProperties, and
reads channel information from WifiInfo. Internet validation is not required for LAN
communication. Ambiguous Wi-Fi connections fail with a configuration error.
The live network name follows the system connection.

The bootstrap endpoint prefers a scoped link-local IPv6 address, falling back to IPv4.
Existing Wi-Fi also retains the other usable address family for discovery and TCP.
Each family has a JmDNS registry and a listener bound to the same selected interface
and port. Port fallback is shared and partially bound listeners are cleaned up.
Discovery probes use a matching source family and local IPv6 interface scope.
JmDNS `getInetAddress()` identifies the registry family; deprecated `getInterface()`
can return another address of the same Android interface. TXT feature bits share the
same source as AirPlay `/info`.

The Bluetooth/iAP2 identification and authentication sequence is retained. In this
mode Wi-Fi credential sharing (0x5702/0x5703) is not advertised or sent. The wireless
0x4301 group carries the selected endpoint address without SSID, passphrase, channel
or security provisioning fields. System-managed Wi-Fi is represented explicitly;
an unknown password is never described as an open network. The P2P and car
hotspot backends retain their existing single-address defaults and bootstrap behavior.

Network loss or a change to either selected address restarts the wireless session.
Closing the backend unregisters its callback without disconnecting Wi-Fi. Saved
automatic car-hotspot startup applies only to car-hotspot mode.

Related work: [PR #22](https://github.com/shihabal3amri/DiPlay/pull/22) also implements
external Wi-Fi within an Android 7 port. This feature uses the current architecture
without importing that port or its system network modifications.

## Validation

Tests cover station selection, redacted network information, manual configuration,
address scope and family selection, callback cleanup, settings persistence, dual-family
publication/listening, port fallback, mode transitions and bootstrap encoding. Existing
P2P and hotspot tests run alongside them.

The contributor previously reported a successful clean install of the credential-sharing implementation on a
2022 BYD Han DM-i with DiLink 4.0 / Android 10 (API 29), firmware
21.1.21.2401160.1: the previous test app was uninstalled, the standalone test APK was
installed and configured normally, and Same LAN entered CarPlay with no issues noticed
during use. This validates that earlier setup; it does not establish compatibility with every
receiver, iOS version or router. Open and WPA2/WPA3 mixed networks have not been
separately validated on hardware.
The current password-free reuse path and the address-only 0x4301 group still need a
new head-unit/iPhone test. Unit tests can verify emitted messages and Android calls,
but do not prove that every iOS version accepts this connection sequence.
For a device check, confirm that both devices keep the existing Wi-Fi connection,
CarPlay starts, and the iPhone can use online services. For failures, save one diagnostic
report with the attempt time and the iPhone’s actual Wi-Fi connection. An absence of
Bonjour control-service events alone does not prove that CarPlay discovery failed.

## Builds

A source-only debug APK omits runtime accessory authentication. Use the existing
`:mobile:assembleStandaloneDebug` task with the explicit external assets described in
[BUILD.md](BUILD.md), following [SECURITY.md](../SECURITY.md). Authentication inputs and
Android signing material must remain outside Git. The app signing key is separate
from the CarPlay accessory identity.
