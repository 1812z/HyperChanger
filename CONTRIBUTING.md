# Contributing to HyperChanger

Contributions to HyperChanger are accepted under the Apache License 2.0. By submitting a contribution, you agree that it is licensed under the terms in [LICENSE](LICENSE); historical MIT attribution is documented in [NOTICE](NOTICE).

Thank you for contributing to HyperChanger. These guidelines keep changes reviewable and releases dependable.

## Getting Started

1. Fork the repository on GitHub.
2. Clone your fork locally:

   ```bash
   git clone https://github.com/YOUR_USERNAME/HyperChanger.git
   cd HyperChanger
   ```

3. Create a branch for one focused change:

   ```bash
   git checkout -b feat/your-feature-name
   ```

## Development Setup

- Android Studio Narwhal (2025.1) or later
- JDK 17+
- Android SDK 37
- A compatible test device or emulator where applicable

Open the project in Android Studio, allow Gradle sync to complete, and verify your change with:

```bash
./gradlew assembleDebug
```

## Making Changes

- Keep each commit focused on one logical change.
- Use concise English commit messages, for example:

  ```text
  feat: add lock screen shortcut option
  fix: avoid System UI restart loop
  docs: clarify LSPosed setup
  ```

- Follow the existing Kotlin and Java style. The project uses the Kotlin official code style.
- Preserve compatibility with the declared minimum SDK unless the change explicitly updates support requirements.
- Do not include APKs, keystores, generated build directories, device logs, or personal configuration files in a pull request.

## Submitting a Pull Request

1. Build and test the branch on every relevant target scope.
2. Push the branch to your fork:

   ```bash
   git push origin feat/your-feature-name
   ```

3. Open a pull request against this repository's `main` branch.
4. Describe what changed, how it was tested, and any compatibility impact. Include screenshots for UI changes and relevant Logcat output for hook-related fixes.

Open an issue before implementing large behavior changes, new hook targets, or API changes so the approach can be discussed first.

## Reporting Issues

Include the following in a bug report:

- Device model, Android version, and HyperOS version
- HyperChanger version and LSPosed version
- Enabled module scopes and relevant settings
- Clear reproduction steps
- Expected and actual behavior
- Relevant Logcat output with sensitive information removed

## Code of Conduct

Keep discussion respectful, constructive, and focused on the project.
