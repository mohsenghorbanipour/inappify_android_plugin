# Release checklist

Public distribution is SDK-only. Never publish integration applications, APKs,
credentials, signing files or local engineering notes.

## Prepare

1. Inspect Git status and preserve unrelated work.
2. Review [migration hints](MIGRATION.md), [changelog](../CHANGELOG.md) and
   [acceptance evidence](../V2_CONTRACT.md) against the actual code.
3. Set `VERSION_NAME` in `gradle.properties`; align dependency examples and
   the release tag. Replace the changelog's Unreleased marker only when releasing.
4. Audit the staged files, not only the working directory. Ignoring a file does
   not remove its prior commits or existing remote copies.

```sh
git status --short
git diff --check
git diff --cached --stat
bash scripts/check-publication.sh
```

The boundary script checks tracked/index paths. It is not an entropy scanner or
proof that history contains no secrets. Review source, fixtures, documentation,
binary archives and known private values separately, without printing secrets.

## Build from public sources

Use JDK 17, Android SDK 34 and the checked-in Gradle wrapper:

```sh
./gradlew --no-daemon \
  :sdk:testDebugUnitTest \
  :sdk:lintRelease \
  :sdk:assembleRelease \
  :sdk:assembleDebugAndroidTest \
  :sdk:publishReleasePublicationToMavenLocal
```

The default settings include only `:sdk`. A clean checkout must not require
private configuration, another repository or an integration application.

Inspect both the release AAR and release sources JAR. Confirm:

- SDK package names and release version are correct.
- No application classes, APKs, credentials or engineering notes are included.
- Debug-only unsafe tracing code is absent from the release artifact.
- The generated POM contains required runtime dependencies.
- Installation resolves transitively; a manually copied AAR alone is not a
  substitute for its dependency metadata.

Do not upload instrumentation APKs as consumer release assets.

## Compatibility and acceptance

Run the V1 API/behavior regressions. For binary assurance, compare matching
release variants and execute tests compiled against the actual V1 artifact
without recompiling them. Record the exact scope of the comparison.

Compile instrumentation separately from executing it. Execute only on an
isolated test installation, including API 21/22, 23 and a current Android API.
Never clear a real user's pending purchases to obtain a clean test run.

Complete the device/backend gates in the acceptance matrix. Do not report
unit-test success as a real purchase, delivery or device-upgrade success.

## History privacy is a separate approval gate

Removing files from a new release does not remove old branches, tags, PR refs,
source archives or external clones. Investigate those before promising removal.

Do not move a published version tag or rewrite public history as a routine
release step. Such changes can break pinned commit references, cached artifacts,
open pull requests and reproducible builds. History cleanup requires an explicit
owner decision, a private backup, coordinated branch/tag treatment and an
assessment of what external copies cannot be recalled.

## Publish after approval

- Stage an explicit allowlist of SDK, public documentation and build files.
- Review the complete staged diff and run the boundary check again.
- Commit and create a new immutable version tag; never reuse a published tag.
- Push only the reviewed branch/tag to the intended public remote. Do not use
  bulk pushes that can expose unrelated branches or backup history.
- Create release notes from the changelog, with migration and limitation links.
- Attach only the versioned release AAR and release sources JAR if distributing
  downloadable artifacts. Never attach a private test application.
- Verify CI, the GitHub release and actual JitPack dependency resolution.
  A successful local build or Git push does not prove JitPack availability.

If history cleanup or device acceptance is unresolved, report the release as
prepared/pending rather than published or certified.
