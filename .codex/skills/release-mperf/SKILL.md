---
name: release-mperf
description: Prepare, validate, publish, and verify an mperf GitHub release. Use when Codex is asked to cut, publish, retry, or audit an mperf release, create a release tag, or verify release JARs, checksums, and provenance attestations.
---

# Release mperf

Publish only from an up-to-date, clean `main` branch. Treat tag creation and pushing as external mutations: confirm the requested version before performing them.

## Prepare

1. Confirm `gh auth status` succeeds for `benjaminromano/mperf` and the user has permission to push tags.
2. Select a SemVer value without a leading `v`, such as `1.2.3` or `1.2.3-rc.1`.
3. Run `scripts/preflight.sh <version>` from this skill directory. Resolve every failure; do not bypass tests, lint, generated-doc checks, artifact smoke tests, or the clean-tree check.
4. Summarize user-visible changes since the previous `v*` tag and confirm the candidate version with the user if it was not explicitly supplied.

## Publish

Create and push one annotated tag. Do not create a GitHub Release manually; `.github/workflows/release.yml` owns release construction, checksum generation, provenance attestation, and publication.

```bash
git tag -a "v<VERSION>" -m "Release v<VERSION>"
git push origin "v<VERSION>"
gh run list --workflow Release --limit 1
gh run watch <RUN_ID> --exit-status
```

If the workflow fails, diagnose and fix the underlying problem. Delete or move a published tag only with explicit user approval.

## Verify

Verify the release after the workflow succeeds:

```bash
tmp_dir=$(mktemp -d)
gh release view "v<VERSION>" --json isDraft,isPrerelease,tagName,url
gh release download "v<VERSION>" --dir "$tmp_dir"
(cd "$tmp_dir" && shasum -a 256 -c "mperf-<VERSION>-all.jar.sha256")
gh attestation verify "$tmp_dir/mperf-<VERSION>-all.jar" --repo benjaminromano/mperf
java -jar "$tmp_dir/mperf-<VERSION>-all.jar" --help
rm -rf "$tmp_dir"
```

Report the release URL, workflow run, checksum result, attestation result, and smoke-test result. Prerelease versions containing `-` must be marked as prereleases; stable versions must be the latest release.
