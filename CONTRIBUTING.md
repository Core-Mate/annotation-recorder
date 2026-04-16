# Contributing

## Workflow
1. Open an issue describing the problem/feature.
2. Fork and create a focused branch.
3. Keep pull requests small and reviewable.
4. Ensure CI passes.

## Local checks
```bash
./gradlew clean assembleDebug
```

## Coding standards
- Kotlin style: idiomatic and readable
- Avoid unrelated refactors in feature PRs
- Add/update docs when behavior changes

## Commit guidance
- Use clear, imperative commit messages
- Reference issue IDs when applicable

## Pull request checklist
- [ ] Build passes locally
- [ ] Behavior tested on device/emulator
- [ ] Docs updated (if needed)
- [ ] No secrets/private paths added
