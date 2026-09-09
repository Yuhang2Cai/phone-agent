# Task 6 Review Package

- Brief: `task-6-brief.md`
- Report: `task-6-report.md`
- Commits: `0c25bfc feat: add android shell and network foundation`, `b923666 fix: harden android auth shell`
- Scope: `android-app/` XML/MVVM shell, auth, OkHttp, Glide, tests and wrapper.
- Verification: 8 XML files (main resources plus debug Manifest) parse successfully and whitespace checks pass. Gradle/Kotlin/assemble were not compiled; wrapper download is blocked by the invalid proxy/network permission, offline plugin caches are missing, and Android SDK configuration is unavailable. No APK claim.
- Focus: XML-only MVVM architecture, auth/session behavior, Token storage/injection, OkHttp retry/errors, Glide fallback, lifecycle/navigation, scope and honest build reporting.
- Fixes under review: debug-only cleartext policy, build-type API URL defaults, single-owner session routing with destination de-duplication, backend error-field mapping, submit race protection, and explicit encrypted-storage initialization/write/clear errors.
