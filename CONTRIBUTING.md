# Contributing to DotCtrl

Thanks for helping improve DotCtrl. Bug reports, fixes, tests, documentation, and UI improvements are welcome. See the [Chinese README](./README.md) or [English README](./README_EN.md) for the app's features and safety model.

## Issues and proposals

Search existing issues and pull requests before opening a new one. For a bug report, include the DotCtrl version, Android version, device model, permission state, steps to reproduce, and expected and actual behavior. Attach screenshots or relevant logs with private information removed when useful.

For substantial features or architectural changes, open an issue to discuss the approach before starting implementation. Changes should preserve explicit user control, offline core functionality, local configuration storage, and safe stopping of gestures.

## Development

Requirements:

- Git and JDK 17.
- Android SDK Platform 35, Build Tools 35.0.0, and Platform Tools.
- An Android 8.0+ (API 26+) emulator or device for manual testing.

Fork the repository on GitHub, then clone your fork and create a branch from `main`. Replace `YOUR_USERNAME` with your GitHub username:

```bash
git clone https://github.com/YOUR_USERNAME/DotCtrl.git
cd DotCtrl
git switch -c my-change
```

Set `JAVA_HOME` to JDK 17. Specify the Android SDK path through `ANDROID_HOME` or `sdk.dir` in a local `local.properties` file. Use the checked-in Gradle wrapper; a separate Gradle installation is not required. On Windows, use `gradlew.bat` instead of `./gradlew`.

Build a debug APK:

```bash
./gradlew --no-daemon --no-configuration-cache :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Debug builds do not require release signing credentials. Release signing and APK verification are documented in [the release guide](./docs/RELEASING.md).

Project layout:

- [`app/`](./app/): Android UI, accessibility service, overlays, local storage, updates, and Android tests.
- [`core/`](./core/): Pure Kotlin models, script execution, JSON serialization, and core tests.
- [`docs/`](./docs/): Project assets and release documentation.
- [`.github/workflows/`](./.github/workflows/): CI checks and release automation.

Follow the existing Kotlin and Compose style. Keep Android-specific behavior in `app` and reusable execution logic in `core`.

## Validation

Before opening a pull request with code changes, run the same checks as the [Checks workflow](./.github/workflows/checks.yml):

```bash
./gradlew --no-daemon --no-configuration-cache :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
git diff --check
```

This runs core tests, Android unit tests (including Robolectric), Android lint, and a debug build. Add or update tests for changed behavior, including regression coverage for bug fixes.

For documentation-only changes, check links, command examples, and formatting, and run `git diff --check`. State in the pull request that Android checks were skipped because only documentation changed.

For changes to gestures, permissions, overlays, or UI, also test the affected behavior on an Android 8.0+ emulator and a physical device. Cover permission setup and revocation, configured gestures in an authorized test app, immediate stop, orientation changes, app switching, and lock-screen stopping as applicable. Include the Android versions and results in the pull request. The [release guide](./docs/RELEASING.md) describes the gesture test activity and update APK device tests for changes in those areas.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/) with a **required scope**:

```text
type(scope): imperative summary
```

- **English is required** for every commit title, including the scope and summary.
- **A scope is required**. Use a short, lower-case affected area, such as `app`, `core`, `clicking`, `script`, `overlay`, `display`, `updates`, `docs`, or `repo`.
- **The entire commit message must be a single title line**. Do not add a body, footers, trailers, or a second `-m` argument.
- Keep the entire title under 72 characters. Write the summary in lower case, start with an imperative verb such as `add`, `fix`, `remove`, or `update`, and do not end with a period.
- Keep one logical change in one commit. Avoid mixing unrelated fixes, refactors, and documentation.
- Mark an incompatible change with `!` before the colon, for example `feat(script)!: change the script export format`. Put the explanation and migration notes in the pull request description.

Common types:

| Type | Use for |
| --- | --- |
| `feat` | User-visible functionality |
| `fix` | Bug, regression, or compatibility fixes |
| `refactor` | Code restructuring without behavior changes |
| `ui` | Visual or interaction changes |
| `perf` | Performance improvements |
| `test` | Test additions or updates |
| `docs` | Documentation changes |
| `build` | Gradle, dependencies, tooling, or packaging |
| `ci` | CI workflows and automation |
| `chore` | Maintenance that fits no other type |
| `revert` | Reverting an earlier commit |

Examples:

```text
feat(script): add normalized coordinate export
fix(overlay): stop the runner when orientation changes
ui(workbench): move script actions into the bottom bar
test(core): cover script cancellation during a delay
docs(repo): document open-source contribution workflow
```

Use the same convention for pull request titles and squash-merge commit messages. Put rationale, issue references, and validation details in the pull request description.

## Pull requests

- Open pull requests against `main` from a focused branch in your fork.
- Explain the problem, the resulting behavior, and any compatibility impact. Link related issues.
- List automated checks and manual testing results. Explain any checks you could not run.
- Include screenshots or a short recording for visible UI changes.
- Update user-facing documentation when setup or behavior changes. Keep the Chinese and English READMEs and UI strings consistent when affected.
- Do not include credentials, signing keys, `local.properties`, `keystore.properties`, private device configuration exports, logs, or generated build output. Use sanitized fixtures when tests need sample data.

Report security-sensitive issues privately to the maintainer rather than posting exploit details, credentials, or private data in a public issue or pull request. Use GitHub's private vulnerability reporting option if it is available for this repository.

## License

By contributing, you agree that your contribution is licensed under the project's [GNU Affero General Public License v3.0](./LICENSE) (`AGPL-3.0-only`).
