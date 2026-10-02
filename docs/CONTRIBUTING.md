# Contributing to PhairPlay

Thank you for your interest in contributing to PhairPlay! This document explains how to contribute and the coding standards we follow.

---

## Getting Started

1. **Fork** the repository on GitHub
2. **Clone** your fork locally
3. **Create a branch** for your change: `git checkout -b feature/your-feature-name`
4. Make your changes following the coding rules below
5. **Run tests**: `./gradlew test` (must pass)
6. **Run lint**: `./gradlew lint` (no new errors)
7. **Commit** with a clear message
8. **Push** to your fork and **open a Pull Request**

---

## Before You Start

- **Check existing Issues** — your idea may already be discussed
- **Open an Issue** before starting large features to discuss the approach
- **Read the spec docs** in `docs/spec/` to understand the requirements

---

## Coding Rules

These rules are enforced in code review. PRs that violate them will be asked to fix before merging.

### RULE 1 — File Size

No Kotlin source file may exceed **400 lines**.

If you need more than 400 lines for a class, split it into smaller classes following the Single Responsibility Principle. Each class should do one thing and do it well.

```kotlin
// BAD: One giant class doing everything
class AirPlayStuff { ... }  // 800 lines

// GOOD: Separate classes with clear responsibilities
class MdnsService { ... }   // registers mDNS
class RtspHandler { ... }   // handles RTSP protocol
class VideoDecoder { ... }  // decodes H.264
```

### RULE 2 — Comments

Every **class** must have a KDoc header comment explaining:
- **WHAT** it does (1 sentence)
- **WHY** it exists (1 sentence)
- **HOW** to use it (short example)

```kotlin
/**
 * MdnsService — Advertises PhairPlay as an AirPlay 2 receiver on the local network.
 *
 * WHY: For macOS to show PhairPlay in the AirPlay menu, the device must announce
 * itself using mDNS. Without this, macOS would never know PhairPlay exists.
 *
 * Example:
 *   val mdns = MdnsService(context)
 *   mdns.start()   // device appears in macOS AirPlay menu
 *   mdns.stop()    // device disappears from macOS AirPlay menu
 */
class MdnsService(private val context: Context) {
```

Every **non-trivial method** must have a comment explaining:
- What it does (for callers)
- Why it works the way it does (for future maintainers)
- Any security or performance considerations

**Don't** write obvious comments:
```kotlin
// BAD: obvious
// increment i by 1
i++

// GOOD: explains the "why" behind a protocol detail
// AirPlay uses CTR mode specifically because it supports arbitrary-length
// audio frames without padding, which is critical for real-time streaming
```

### RULE 3 — Tests

Every **public method** must have at least one unit test.
Every **AirPlay protocol step** must have a corresponding test.

Test files must be named `[ClassName]Test.kt` and placed in the matching test directory:
- Unit tests: `app/src/test/kotlin/com/phairplay/`
- Instrumented tests: `app/src/androidTest/kotlin/com/phairplay/`

Tests must be commented like production code. A fellow developer should understand what is being tested and why.

```kotlin
/**
 * Test: RECORD triggers the onStreamingStarted callback.
 *
 * WHY: The UI switch from WaitingScreen to StreamingScreen depends on this
 * callback being called when RECORD is received.
 */
@Test
fun `RECORD triggers onStreamingStarted callback`() {
```

### RULE 4 — Security

- All **network input must be validated** before processing (length checks, format checks)
- **No hardcoded** IP addresses, port numbers (except standard AirPlay ports), or secrets
- **No unnecessary Android permissions** — use only what is in the manifest
- **All exceptions must be caught and logged** — never silently swallowed
- **Buffer overflow prevention**: always check array lengths before indexing

### RULE 5 — Performance

- **Video decode**: Hardware MediaCodec only — never add a software fallback
- **Thread safety**: Network I/O on IO dispatcher, UI updates on Main dispatcher
- **No blocking calls on Main thread** — use coroutines for all async work
- **No memory leaks**: all resources (MediaCodec, AudioTrack, sockets) must be released in the appropriate lifecycle method

### RULE 6 — Build Flavors

Changes that affect platform-specific behavior must be implemented in the appropriate flavor directory:
- `app/src/main/`: Shared code
- `app/src/googletv/`: Google TV specific (API 29+; the only shipped flavor — Fire TV is not supported)

If you use an API that requires API level 26+, wrap it in a version check:
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    // API 26+ code
}
```

---

## Pull Request Checklist

Before submitting a PR, verify:

- [ ] Code follows all 6 coding rules above
- [ ] All public methods have unit tests
- [ ] `./gradlew test` passes with 0 failures
- [ ] `./gradlew lint` passes with no new errors
- [ ] No file exceeds 400 lines
- [ ] All classes have KDoc header comments
- [ ] PR description explains what changed and why

---

## Commit Message Style

Use conventional commits format:

```
type: short description (max 72 chars)

Longer explanation if needed (optional).
```

Types: `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `chore`

Examples:
```
feat: add NTP timing synchronization for A/V sync
fix: prevent crash when RTSP ANNOUNCE has empty SDP body
docs: add Downloader sideloading instructions to README
test: add unit tests for AES-128-CTR decryption
```

---

## Questions?

Open a GitHub Issue with the `question` label, or start a Discussion.
