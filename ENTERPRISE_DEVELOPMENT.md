<!--
  Copyright 2026 Datastrato Inc.
-->

# Enterprise Gravitino: Development and Merge Process

This document describes the basic development and merge rules specific to this
**enterprise** repository, which is a fork of
[apache/gravitino](https://github.com/apache/gravitino). It does not replace the
[Apache Gravitino contribution guide](CONTRIBUTING.md) — it only covers the additional
rules that apply because this repository tracks an upstream open-source (OSS) project.

## 1. OSS vs. Enterprise: Where Should a Change Go?

The guiding principle is: **default to OSS**. Only code that is genuinely
enterprise-only belongs in this repository.

1. **Enterprise-only features go here; everything else goes to OSS.**
   If a change is useful to the broader community and doesn't depend on
   proprietary/enterprise-only capabilities, it should be developed and merged in
   [apache/gravitino](https://github.com/apache/gravitino) first, then picked up by this
   repository through the regular [upstream sync](#2-syncing-oss-and-enterprise). Do not
   develop generally-useful changes directly against this repository — it creates
   permanent drift and duplicate maintenance work.

2. **If an enterprise feature requires changes to OSS (e.g., new APIs or
   interfaces), make those changes in OSS.**
   When building an enterprise-only feature requires extending a public API,
   interface, or extension point that only OSS owns, submit that groundwork
   (the API/interface change itself, not the enterprise implementation behind it) as a
   PR to `apache/gravitino`. Once merged upstream and synced back, build the
   enterprise-only logic on top of it in this repository.

3. **Implement enterprise features in their own module/package, separate from
   OSS code.**
   Prefer adding a new Gradle module (or, when a whole module is overkill, a clearly
   separated package such as `com.datastrato.gravitino.*`) for enterprise-only
   code instead of interleaving it into existing OSS modules/files. Keeping enterprise
   changes physically separate from OSS code minimizes merge conflicts during
   [upstream sync](#2-syncing-oss-and-enterprise) and makes it much easier to see, at a
   glance, what is enterprise-specific versus what tracks OSS.

4. **All enterprise configuration keys must be prefixed with `gravitino.datastrato.`.**
   This keeps enterprise-only configuration clearly namespaced and distinguishable from
   OSS configuration keys, and avoids future key collisions if OSS introduces a
   similarly named option during a sync.

5. **New enterprise-only files must use the Datastrato copyright-only header, not the
   full Apache License header.**
   Use `Copyright <year> Datastrato Inc.` only (see
   [`dev/headers/datastrato-apache-2.txt`](dev/headers/datastrato-apache-2.txt)).
   Do not add `This software is licensed under the Apache License version 2.`
   Stamp **the calendar year the file is generated** — do not copy
   a 2024 or 2025 header from an older file.
   `dev/headers/apply-datastrato-license-headers.sh apply <file...>` stamps
   **only the new files you pass**. Headerless files get a new header. Existing
   Datastrato headers keep their year and entity. Apache ASF headers are left
   unchanged. The script does not scan the repo.
   `check-year` rejects those paths if they have a stale year.

   Both license tasks run under `./gradlew check`
   ([`enterprise-licenses.gradle.kts`](enterprise-licenses.gradle.kts)):

   - `checkNewFileLicenseHeaders` fails a **newly added** Datastrato file that
     still has the Apache grant line. Copyright-only `Pvt Ltd` still passes this
     task.
   - `checkDatastratoLicenseHeaders` requires files under
     `datastratoLicenseCheckIncludes` to be copyright-only
     `Copyright YYYY Datastrato Inc.`. `Pvt Ltd`, the grant line, and a missing
     Datastrato copyright fail. The year is not rewritten. Add a glob only when
     you introduce a new enterprise module. Put a relative path in
     `datastratoLicenseHeaderWaivers` only for a recorded exception.
   - Apache Rat excludes Datastrato-headered files (the include list plus
     dynamic detection). Files found only by dynamic detection are not
     Inc.-format-checked.

6. **Enterprise-only features must be gated behind the Gravitino Enterprise license.**
   This is a separate concept from the source-file license header above — it's the
   runtime license key that unlocks enterprise functionality. See
   [Gravitino Enterprise License Management](docs-enterprise/license-management.md) for
   how the license key is issued, verified, and embedded in builds.

When in doubt about whether something is enterprise-only, ask in the team channel before
starting the work — it's much cheaper to decide up front than to move code between
repositories later.

## 2. Syncing OSS and Enterprise

### 2.1 Automatic sync via GitHub Actions

Upstream sync is automated by
[`.github/workflows/sync-upstream.yml`](.github/workflows/sync-upstream.yml):

- Runs weekly on a schedule (and can be triggered manually via
  `workflow_dispatch` for one or more branches).
- Merges the matching `apache/gravitino` branch into the same branch in this
  repository (e.g., OSS `main` → enterprise `main`, OSS `branch-1.3` → enterprise
  `branch-1.3`).
- Uses a **merge commit**, not a squash merge — this keeps upstream commit history and
  authorship intact and makes future syncs/cherry-picks easier to reason about. Do not
  switch the sync PR to squash merge when merging it.
- Opens a PR automatically. Clean merges can be merged directly after CI passes; merges
  with conflicts are labeled `upstream-conflict` and need a human to resolve the
  conflict markers before merging.

### 2.2 Manual cherry-picks

Outside of the scheduled/automatic sync, you can manually cherry-pick individual
upstream (or enterprise) commits between branches when you need a specific fix sooner
than the next sync — for example, picking a fix from `main` into `branch-1.3`. Use a
normal `git cherry-pick` and open a regular PR; there's no special tooling required for
this case.

## 3. Running CI

1. **Use the `run-ci` label to trigger CI. Don't add it until you actually need it.**
   Several CI workflows in this repository (e.g.,
   [`cloud-test.yml`](.github/workflows/cloud-test.yml)) only run on a pull request once
   the `run-ci` label is applied. These jobs run on self-hosted runners and consume
   shared CI capacity/cost, so avoid triggering them on every push — apply the label
   once the PR is reasonably ready for review or you specifically need CI signal, not on
   every intermediate commit.

2. **CI must pass before merging.** Regardless of when you choose to trigger it, apply
   the `run-ci` label and get a green run before the PR is merged — approval alone is
   not sufficient.

3. **New CI workflows should follow the existing self-hosted runner pattern.**
   If you're adding a new CI workflow, follow the pattern already used by workflows like
   `cloud-test.yml`: gate expensive jobs behind
   `if: contains(github.event.pull_request.labels.*.name, 'run-ci')` and run them on the
   existing self-hosted runners (e.g., `runs-on: gcp-arc-runners`) rather than
   `ubuntu-latest`/GitHub-hosted runners, unless the job is cheap enough that
   GitHub-hosted runners are clearly fine (as with the sync workflow itself).

## 4. File an Issue Before Opening a PR

Follow the same OSS convention used in `apache/gravitino`: open a GitHub issue
describing the change (using the appropriate template under
`.github/ISSUE_TEMPLATE/`) before starting work, then link it in the PR:

- PR title: `[#<issue>] <type>(<scope>): <subject>`, per the format documented in
  `.github/PULL_REQUEST_TEMPLATE`.
- PR description: fill in the `Fix: #<issue>` line.

Reserve the `[MINOR]` title prefix (no issue) for genuinely trivial changes, such as
typo fixes.

## 5. Dependency updates

Dependency version bumps for shared / OSS-tracked code are generally useful to the broader
community, so they follow the same **default to OSS** rule as other non-enterprise changes:

1. **Land shared dependency bumps in [apache/gravitino](https://github.com/apache/gravitino) first.**
   Do not open or merge Dependabot (or manual) version-update PRs in this repository to bump
   shared dependencies — that diverges enterprise `main` from OSS and creates duplicate
   maintenance work.
2. **Enterprise receives those shared bumps via [upstream sync](#2-syncing-oss-and-enterprise).**
   Once a bump is merged upstream, the weekly (or on-demand) sync brings it here.
3. **For enterprise-specific features, manage dependencies manually.** Dependencies that exist
   only for enterprise-only modules/features (and are not shared with OSS) are bumped here by
   intentional, reviewed PRs when needed — not by Dependabot. Prefer keeping enterprise-only
   dependency churn small and explicit.
4. **No Dependabot version-update PRs in this repo.** This repository deliberately does
   **not** ship a `.github/dependabot.yml`. Dependabot **security alerts** may stay enabled;
   only automated version-update PRs are disabled.
5. **Upstream sync may reintroduce `dependabot.yml` from OSS.** When that happens, drop it
   again before merging the sync PR (the sync workflow also removes the file automatically
   when present after a sync merge).

