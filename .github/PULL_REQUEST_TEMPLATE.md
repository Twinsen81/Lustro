## Summary

<!-- What does this PR change, and why? -->

## Type of change

- [ ] Bug fix (non-breaking)
- [ ] New feature (non-breaking)
- [ ] Breaking change (public API or wire protocol)
- [ ] Documentation / tooling only

## Checklist

- [ ] Tests added or updated for the change
- [ ] `./gradlew apiCheck checkFacadeParity` passes (no unintended public API changes; `./gradlew apiDump` run and the baselines committed if intended)
- [ ] `./gradlew detekt` passes
- [ ] `CHANGELOG.md` updated under `[Unreleased]`
- [ ] Public API changes are documented with KDoc
- [ ] Wire-protocol or schema changes are reflected in `wire-protocol/` and tab OpenAPI fragments
- [ ] All commits are signed off (DCO): `git commit -s`

## Compatibility notes

<!-- Note any SemVer impact (major/minor/patch) or wire-protocol version bump. -->
