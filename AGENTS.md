# Repository Guidelines

## Project Structure & Module Organization
- `app/` is the main Android application module.
- Source code lives in `app/src/main/java/cn/bincker/stream/sound/` (Kotlin + Jetpack Compose).
- UI resources and assets are in `app/src/main/res/` and `app/src/main/ic_launcher-playstore.png`.
- AndroidManifest is `app/src/main/AndroidManifest.xml`.
- Unit tests are in `app/src/test/`; instrumentation/UI tests are in `app/src/androidTest/`.
- Gradle and build config files are at the repo root (`build.gradle.kts`, `settings.gradle.kts`, `gradle/`).

## Build, Test, and Development Commands
Use the Gradle wrapper from the repo root:
- `./gradlew build` — build all variants and run tests.
- `./gradlew test` — run JVM unit tests (JUnit4).
- `./gradlew connectedAndroidTest` — run instrumentation tests on a device/emulator.
- `./gradlew assembleDebug` / `./gradlew assembleRelease` — build APKs.
- `./gradlew installDebug` — install debug APK to a connected device.
- `./gradlew clean` — remove build outputs.

## Coding Style & Naming Conventions
- Kotlin code follows Android Studio defaults; use 4-space indentation.
- Package namespace is `cn.bincker.stream.sound`; keep new classes under this hierarchy.
- Prefer `PascalCase` for classes/objects, `camelCase` for functions/fields, and `SCREAMING_SNAKE_CASE` for constants.
- No formatting or lint task is configured; keep edits consistent with nearby files.

## Language
- code comments use Chinese
- title and description use Chinese

## Implementation methods and plans
- You should focus more on adhering to best practices and the art of coding, rather than merely achieving the goal at any cost.
- When you realize that the demand is unreasonable, you should raise questions.
- When you have a better implementation plan, you should put forward your suggestions.

## Testing Guidelines
- Unit tests use `junit:junit:4.13.2` in `app/src/test`.
- Instrumentation tests use AndroidX Test + Espresso in `app/src/androidTest`.
- Test names should be descriptive and mirror the class under test (e.g., `AudioServiceTest`).

## Commit & Pull Request Guidelines
- Recent commits are short, action-oriented phrases (often Chinese), e.g., `添加连接重试`, `移除音频参数配置`.
- Keep commits focused and summarize the user-visible change.
- PRs should include: a concise description, key files touched, how you tested, and screenshots for UI changes.
- Link related issues or tasks when applicable.

## Configuration & Security Tips
- Runtime config is stored in `app_config.yaml` under app internal storage; avoid committing secrets.
- The app relies on UDP networking and cryptography (Ed25519/X25519); validate changes carefully.
