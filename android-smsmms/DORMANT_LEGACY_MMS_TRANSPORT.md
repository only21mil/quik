# Dormant legacy MMS transport

QUIK sends and downloads MMS through Android's `SmsManager`. The platform MMS
service owns carrier network selection, the active APN, and MMSC HTTP policy.
QUIK therefore does not request `INTERNET` or `CHANGE_NETWORK_STATE`, and it
does not opt the application into cleartext traffic.

The inherited `service_alt` package still contains an old OkHttp transport.
`DownloadRequest.persist` is retained because QUIK uses its PDU persistence
code after `SmsManager.downloadMultimediaMessage` finishes. Removing the whole
package would mix a networking change with a rewrite of that parser and
persistence path.

The dormant transport has these build boundaries:

- `PushReceiver` always calls the platform `DownloadManager` wrapper.
- The inherited generic `Transaction` class always selects its platform branch
  on supported devices.
- `TransactionService` is not an application component.
- `SendRequest` and `MmsRequestManager` are excluded from compilation.
- The remaining transport classes and `DownloadRequest` constructor are
  package-private. Only `DownloadRequest.persist` remains a public entry point.

`MmsPlatformBoundarySourceContractTest` pins these constraints. A future move
away from `SmsManager` must add behavioral network, TLS, API 23, and failure
tests before changing the boundary or adding network permissions.

Pre-Lollipop transaction and HTTP helpers remain because the PDU parser classes
still share types with them. They have no registered component or production
caller, and QUIK's minimum supported API is 23.
