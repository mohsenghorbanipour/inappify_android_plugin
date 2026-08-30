# Contributing

Contributions are welcome through GitHub issues and pull requests.

## Development setup

1. Install JDK 17 and Android SDK 34.
2. Clone the repository and open its root in Android Studio.
3. Keep API keys, marketplace keys, and signing credentials in
   `local.properties`; never commit them.
4. Build with the included Gradle wrapper.

Before opening a pull request, run:

```shell
./gradlew \
  :sdk:testDebugUnitTest \
  :sdk:lintRelease \
  :sdk:assembleRelease \
  :sdk:publishReleasePublicationToMavenLocal \
  :app:assembleDebug \
  :sdk:assembleDebugAndroidTest
```

Changes to encryption or persistence should also run
`:sdk:connectedDebugAndroidTest` on API 21 or 22, API 23, and a current Android
API.

## Pull requests

- Keep public APIs typed, documented, and free of secret-bearing return values.
- Add tests for behavior changes and regressions.
- Preserve the `NONE` and `BAZAAR` purchase contracts.
- Do not add mutable global client, session, listener, billing, or credential
  state.
- Update `CHANGELOG.md` for user-visible changes.
- Use a new semantic version and tag for every published fix; do not move an
  existing release tag.

Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md).
