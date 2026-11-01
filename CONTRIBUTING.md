# Contributing

Contributions are welcome. This project is in alpha, and small, focused changes are the easiest
to review.

## Build and test

Requirements:

- JDK 21. Newer JDKs are not supported by the Android Gradle Plugin used here.
- An Android SDK with platform 37.
- An Android device or emulator running Android 8.0 (API 26) or newer, for instrumented tests.

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest :core:model:test   # JVM unit tests
./gradlew connectedDebugAndroidTest            # instrumented tests (needs a device or emulator)
./gradlew lint
```

Set `sdk.dir` in `local.properties` or set `ANDROID_HOME`. Gradle must run on JDK 21: set
`org.gradle.java.home` in `~/.gradle/gradle.properties`, or set `JAVA_HOME`.

Ink strokes and brushes use a native library, so tests that create them must be instrumented
tests.

## Modules and dependency rules

```
app                  Application, activities, navigation graph
core/model           Domain types and product limits (pure Kotlin, JVM tests)
core/designsystem    Theme, colours, icons and shared components
core/database        Room catalog: notes, nested folders, tags, FTS search index
core/document        Note bundle format and the atomic file store
core/ink             Pen catalog, brushes, stroke codec, erasers, lasso and transforms
core/richtext        Rich text engine: lists, checklists, pagination, selection, IME
core/export          Export to PDF, images, text, Markdown, HTML, DOCX and PPTX
core/sdocx           .sdocx reader, writer and mapper
core/security        Lock vault and cryptography
core/data            Repositories, import and export services, preferences
feature/*            Screens: notes list, editor, settings, lock, share
```

- Features depend on repositories in `core/data`. They must not use `core/database` or
  `core/document` directly.
- Versions live only in `gradle/libs.versions.toml`.
- Module setup goes through the convention plugins in `build-logic/convention`. Do not
  hand-configure SDK levels or Kotlin options in module build files.
- With the Android Gradle Plugin's built-in Kotlin support, do not apply
  `org.jetbrains.kotlin.android` in modules.
- A change to the note document or the stroke codec needs a format version bump and a reader for
  the old version.
- A Room schema change needs a migration, and the exported schema JSON is committed under
  `core/database/schemas/`.
- Pen families are identified on disk by the key in `BrushFamily.developerComment`. Keep that key
  when retuning a brush.
- Product limits (trash retention, folder depth and others) live in `core/model/Limits.kt`.

## Code style

- Follow the style of the surrounding code. Kotlin uses the official Kotlin style.
- Keep a change small and focused. Avoid unrelated refactoring in the same pull request.
- Prefer clear names and short functions. Add comments only where the logic is not obvious.
- Write your own implementation. Describe observed behaviour in issues and pull requests instead
  of copying anything from another application.

## Tests

- Add or update tests for behaviour changes.
- Run `./gradlew testDebugUnitTest :core:model:test` before opening a pull request.
- Tests that construct ink strokes or brushes must be instrumented tests.

## Submissions and copyright

Do not submit code, resources, strings or artwork copied from other apps. Describe observed
behaviour instead, and write the implementation yourself.
