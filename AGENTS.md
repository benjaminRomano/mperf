# Repository Guidelines

## Project Structure & Module Organization
- `src/main/kotlin` — Kotlin CLI commands, profilers, and adb helpers.
- `src/main/proto` — Perfetto config protos; run Gradle to regenerate outputs.
- `src/test/kotlin` — JUnit and integration helpers; mirror source packages.
- `build/` — generated artifacts and protobuf stubs; never edit directly.
- User config lives at `~/.mperf/config.yml` and is created on first run.

## Build, Test, and Development Commands
- `./gradlew build` — compile Kotlin, run checks, and regenerate protos.
- `./gradlew test` — execute the full JUnit 5 suite.
- `./gradlew ktlintCheck` / `ktlintFormat` — enforce or auto-fix style.
- `./gradlew run --args "start -p com.example.app"` — invoke the CLI locally.
- `./gradlew generateDocs` — refresh `docs/cli.md` after CLI flag changes.

## Coding Style & Naming Conventions
- Kotlin files use 4-space indentation; keep lines under ~120 characters.
- Packages stay under `com.bromano.mobile.perf`; classes PascalCase, members camelCase, tests `*Test`.
- Update `.proto` files in `src/main/proto`; never modify generated sources under `build/`.
- Do not use wildcard imports; explicitly list required dependencies.
- Favor expressive naming over comments and scope each change to the requested behavior.

## Testing Guidelines
- Place new tests in `src/test/kotlin` with mirrored package paths.
- Use JUnit 5 and Mockito; lean on `FakeShell` or other fakes for adb and filesystem I/O.
- Name test methods descriptively (e.g., `fun starts_trace_server_once()`); keep them isolated.
- Run `./gradlew test` before submitting and extend coverage for new CLI flows or adb helpers.

## Commit & Pull Request Guidelines
- Write concise, imperative commit messages (e.g., `Add simpleperf symfs flag`).
- Keep commits focused; include relevant docs and tests with behavior changes.
- Pull requests should summarize behavior shifts, list validation (build/test), and link issues.
- Attach trace samples or screenshots when altering viewer behavior or documentation.

## Security & Configuration Tips
- Do not hardcode device identifiers, secrets, or local-only paths.
- Ensure `adb`, `python3`, `tar`, and `gzip` are on PATH before running profilers.
- Prefer updating `~/.mperf/config.yml` for local overrides instead of patching defaults.
