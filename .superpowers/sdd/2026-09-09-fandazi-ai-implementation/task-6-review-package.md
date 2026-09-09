# Task 6 Review Package

- Brief: `task-6-brief.md`
- Report: `task-6-report.md`
- Commits: `0c25bfc feat: add android shell and network foundation`, `b923666 fix: harden android auth shell`, `6c416cb fix: stabilize android auth routing`, plus the current Task 6 round 3 fix commit.
- Scope: `android-app/` XML/MVVM shell, auth, OkHttp, Glide, tests and wrapper.
- Verification: 8 XML files (main resources plus debug Manifest) parse successfully and whitespace checks pass. Gradle 8.13 and plugin cache paths are visible, but offline resolution still reports AGP/Kotlin artifacts unavailable; Android SDK environment variables are missing. Kotlin/assemble were not compiled and no APK is claimed.
- Focus: XML-only MVVM architecture, auth/session behavior, Token storage/injection, OkHttp retry/errors, Glide fallback, lifecycle/navigation, scope and honest build reporting.
- Fixes under review: debug-only cleartext policy, build-type API URL defaults, single-owner session routing with destination de-duplication, backend error-field mapping, submit race protection, and explicit encrypted-storage initialization/write/clear errors.
