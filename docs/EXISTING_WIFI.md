# Existing Wi-Fi / Same LAN

Connect the Android receiver and iPhone to the same third-party router or portable
Wi-Fi in system settings. In DiPlay → Connection setup → Wireless, select
**Existing Wi-Fi / Same LAN**. DiPlay automatically reuses the head unit's connected
system Wi-Fi; no network password is required. Keep Bluetooth enabled and the iPhone
paired with the receiver, then start wireless CarPlay as usual.

Both clients must already be connected and able to
communicate; allow multicast DNS for service discovery. Prefer 5 GHz when supported.

The target iPhone acting as a Personal Hotspot is a different topology from two
clients on an external router. Internet access through that hotspot does not prove
wireless CarPlay compatibility. Lincoln's
[connection troubleshooting](https://www.fordservicecontent.com/Ford_Content/vdirsnet/OwnerManual/Home/Content?ProcUid=G2360145&Uid=G2361335&buildtype=web&countryCode=USA&div=l&languageCode=en&moidRef=G2358278&userMarket=CAN&vFilteringEnabled=False&variantid=11272)
notes that Personal Hotspot can interfere on some iPhone/iOS combinations. For a
controlled comparison, turn it off and join both devices to the same external Wi-Fi;
alternatively test the car hotspot or Wi-Fi Direct. Neither Android Wi-Fi association
nor successful Bluetooth authentication establishes that the AirPlay session started.

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

## Target iPhone as the hotspot host — precision.12-rc1 trial

Connect the receiver to the target iPhone's Personal Hotspot in Android settings,
pair that same iPhone over Bluetooth, and choose Existing Wi-Fi in DiPlay. No
hotspot password is stored or sent by this backend. This is an experimental
compatibility trial, not a hardware-validated claim that all iOS versions support it.

After authenticated Bluetooth StartSession, normal multicast discovery is supplemented
by bounded direct mDNS queries to the selected Wi-Fi's on-link IPv4 default gateway(s).
The gateway is only a candidate. PTR, SRV, TXT and A records must identify a
`_carplay-ctrl._tcp.local.` instance whose TXT `id` matches the selected Bluetooth
iPhone and whose advertised host has the gateway's address. Only then is a connect
request sent to the advertised port. There is no fixed iPhone IP, guessed control
port, subnet scan, or assumption that a router is the paired iPhone.

Direct queries use an ephemeral source port and follow RFC 6762 sections 5.5/6.7.
Split DNS-SD responses trigger bounded SRV/TXT/A follow-up queries; malformed,
truncated, stale/goodbye, mismatched-transaction and unrelated records are rejected.
Gateway discovery stops with the connection attempt. Both the direct query socket
and Existing Wi-Fi control sockets bind to the selected Android Network without
changing process routing. Gateway/link changes invalidate the old attempt.

Existing Wi-Fi StartSession now carries both actually served address families,
with receiver-local IPv6 scope identifiers removed from the wire. AP/P2P bootstrap
vectors remain unchanged. Existing Wi-Fi control probes use two shorter attempts
per endpoint, so an unreachable family does not consume the whole first-TCP window;
non-2xx responses are retried. Ordinary multicast discovery still runs independently.

Reports include gateway query/result stages and anonymous record/identity-match counts.
`no_verified_service` means no complete matching service was obtained in the lookup
window, not proof of a specific iOS restriction. iOS must expose the service on its
hotspot interface; Android cannot force that service to start. A successful unit or
loopback test cannot establish real iPhone hotspot compatibility or sustained playback.

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
0x4301 group carries the connected SSID and selected endpoint address without a
passphrase, channel or security provisioning fields. This preserves the network
identifier omitted by precision.10 while still disabling credential provisioning.
This is a compatibility candidate, not a confirmed fix for the first-TCP timeout.
System-managed Wi-Fi is represented explicitly;
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
The current password-free reuse path and the SSID/address-only 0x4301 group still need a
new head-unit/iPhone test. Unit tests can verify emitted messages and Android calls,
but do not prove that every iOS version accepts this connection sequence.
For a device check, confirm that both devices keep the existing Wi-Fi connection,
CarPlay starts, and the iPhone can use online services. For failures, save one diagnostic
report with the attempt time and the iPhone’s actual Wi-Fi connection. An absence of
Bonjour control-service events alone does not prove that CarPlay discovery failed.

### Interpreting a first-TCP timeout

A precision.10 report from 2026-10-22 showed successful iAP2 identification and MFi
authentication, a ready AirPlay listener, and a transmitted `0x4301` StartSession.
The subsequent snapshots consistently reported `tcpAccepted=0`, `sessionActive=false`,
and `waitingFor=WiFi_discovery_or_AirPlay_TCP`, ending in
`No AirPlay TCP after CarPlay StartSession`. Both IPv4 and IPv6 attempts had the same
outcome. The report was from an Android 12 handset running the receiver, not the
target Android 7.1/8.1 head unit.

The user confirmed that a separate phone/portable hotspot supplied this network.
The target iPhone was a Wi-Fi client, so its own Personal Hotspot is not an
explanation for this report. Client isolation/multicast behavior of the external
hotspot and the password-free bootstrap remain separate hypotheses to test.

This localizes the failure after Bluetooth authentication and before the first
AirPlay TCP connection. It does **not** distinguish a Personal Hotspot restriction,
discovery/reachability failure, or iOS rejection of the password-free bootstrap.
`wifiConfigs=0` is expected in this mode; it is not evidence that a password is
missing. No protocol fix or successful hardware connection is claimed from this
report. Retest on an external router, then compare car-hotspot/Wi-Fi Direct behavior
and export a fresh report. Never infer a specific cause merely from zero Bonjour
events or unavailable Android kernel counters.

The next test candidate includes the live SSID alongside the endpoint in `0x4301`.
It still omits passphrase, security and channel fields and never sends `0x5703`.
That change tests whether retaining the network identifier helps; this report alone
does not establish that the omitted SSID caused the timeout or that iOS accepts the
remaining partial group. If TCP still stays at zero, continue investigating external
hotspot isolation/discovery and bootstrap compatibility, rather than increasing the
timeout or describing the attempt as connected.

## Builds

A source-only debug APK omits runtime accessory authentication. Use the existing
`:mobile:assembleStandaloneDebug` task with the explicit external assets described in
[BUILD.md](BUILD.md), following [SECURITY.md](../SECURITY.md). Authentication inputs and
Android signing material must remain outside Git. The app signing key is separate
from the CarPlay accessory identity.
