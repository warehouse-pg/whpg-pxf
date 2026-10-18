# PXF GitHub Actions workflows

## `pxf-ci.yml` — the pull-request gate

[![PXF CI](https://github.com/warehouse-pg/whpg-pxf/actions/workflows/pxf-ci.yml/badge.svg)](https://github.com/warehouse-pg/whpg-pxf/actions/workflows/pxf-ci.yml)

Fast, hermetic verification for every pull request. All jobs run without
secrets and are safe for fork PRs. Cluster-level integration testing
(databases, Hadoop stacks) is outside the scope of this workflow, and
nothing here publishes or releases artifacts — build outputs are
transient to each job.

### Jobs

| Job | What it runs | Toolchain | Measured time (cold / warm cache) |
|---|---|---|---|
| `server-unit` | The full Java unit-test suite (`./gradlew test` from `server/`) | Temurin JDK 8 (the build requires it) | 4m36s / ~3m40s |
| `cli-test` | The Go CLI Ginkgo suites, including the cluster-free end-to-end suite (`make -C cli test`) | Go (version from `cli/go.mod`) | 1m14s / ~25s |
| `automation-compile` | Proves the integration-test tree compiles and its dependencies resolve (`mvn test-compile` from `automation/`). A compile signal only — executing those tests needs a full database + Hadoop environment, so they are not run here | JDK 8 (to build the PXF server jars the tree compiles against) + JDK 11 for maven | 2m53s / ~1m30s |
| `docs-static-check` | `.github/scripts/docs-linkcheck.bash`: static link/anchor integrity for the docs book and top-level markdown (cross-page links and anchors, in-page fragments, subnav targets, orphan pages) | bash | ~10s |

Times were measured on `ubuntu-latest` runners during the workflow's
trial (2026-09). `timeout-minutes` on each job is set with generous
headroom over the cold-cache baseline (so suite growth doesn't turn the
timeout into a bottleneck) while still bounding what a hung job can
burn; watch the measured times above for drift.

### Triggers

| Trigger | What runs |
|---|---|
| `pull_request` → `main`, `release-6.x` | All four jobs (each branch runs its own copy of this workflow) |
| `push` → `main`, `release-6.x` | All four jobs (not cancelled by newer pushes) |
| `push` → `ci/**` | All four jobs — **opt-in CI for feature branches**: push any branch named `ci/<something>` to get full CI without opening a PR |
| `schedule` (Mondays 03:00 UTC) | The weekly lane, see below |
| `workflow_dispatch` | All four jobs; optional `debug_enabled` input starts a [tmate](https://github.com/mxschmitt/action-tmate) session on failure for interactive debugging |

Concurrency: for every ref except `main` and `release-6.x`, a newer run
cancels an in-progress one.

### Which copy of this workflow runs?

This workflow exists on both `main` and `release-6.x`. Event-driven
triggers always use the copy on the branch involved; only the weekly
schedule is centralized, because GitHub evaluates `schedule:` triggers
solely on the repository's default branch — a schedule block on any
other branch is inert.

```
EVENT                          WHICH COPY RUNS?
─────────────────────────────  ─────────────────────────────────────
PR → main                      main's copy          ← self-contained
push to main                   main's copy          ← self-contained
PR → release-6.x               release-6.x's copy   ← self-contained
push to release-6.x            release-6.x's copy   ← self-contained

Monday 03:00 UTC (schedule)    main's copy — the only option there is
                                 ├── leg: checkout main        → test it
                                 └── leg: checkout release-6.x → test it
```

That is why enabling or excluding a weekly leg for release-6.x is an
edit to main's copy of this file, even though the sources being tested
are release-6.x's.

### The weekly lane

Scheduled runs exist to catch rot that PR traffic doesn't: broken
dependency resolution, upstream URL/registry drift, "green only because
nobody opened a PR this month" — and they keep the gradle/maven/go
caches warm (GitHub evicts caches unused for ~7 days).

- The same four jobs run as a branch matrix over `main` **and**
  `release-6.x` (schedules only fire from the default branch, so the
  other branch is checked out explicitly).
- `server-unit` additionally runs a **JDK 11 test lane**: the build
  stays on JDK 8, but the tests execute on JDK 11 (`./gradlew test
  -PtestJvm=...`). PXF supports running on Java 8 or Java 11 (see the
  docs' "Installing Java for PXF" page); every other test execution
  happens on JDK 8, so without this lane the Java 11 runtime would
  never be exercised by tests at all.
- On any failure, the run opens (or comments on) a GitHub issue labeled
  `ci-weekly-failure` — scheduled failures block nobody's PR and would
  otherwise go unnoticed.

All four jobs (and the JDK 11 lane) run against both branches — the
prerequisites (the `testJvm` hook, a pom that resolves from public
repositories, and the sweep script) exist on both branches.

### Caches

The maven and go dependency trees are cached by `setup-java`/`setup-go`,
keyed on the respective lockfiles/build files. The gradle cache uses
explicit cache steps with a **single writer**: the JDK 8 leg of
`server-unit` (which warms the fullest dependency set, build + test)
restores and saves; everything else — the weekly JDK 11 leg and
`automation-compile` — restores the same key read-only, since their
gradle needs are a subset — cache keys are immutable once saved, so a
faster-finishing job or matrix leg must never pin a half-warmed cache.
Note that
`pull_request` runs can only restore caches created in the target
branch's scope, so a PR may start cold even when branch pushes were
warm.

Note for `automation-compile`: the jsystem test-framework artifacts
(`org.jsystemtest:*`) were never published to Maven Central, and their
only public host (`maven.top-q.co.il`) intermittently blocks CI
providers — so the job seeds a mirrored copy from this repository's
`jsystem-deps-*` release into `~/.m2` before maven runs; resolution
never depends on that host. Local developers hitting the same
resolution failure can do the same:

```bash
gh release download jsystem-deps-6.0.01 -R warehouse-pg/whpg-pxf -p 'jsystem-m2-*.tar.gz' -D /tmp
tar xzf /tmp/jsystem-m2-6.0.01.tar.gz -C ~/.m2/repository
```

### Reproducing the jobs locally

All commands run from the repository root (each recipe uses a subshell,
so nothing changes your working directory), with `JDK8_HOME` and
`JDK11_HOME` set to local JDK installations — the server build needs
JDK 8 (it does not compile on newer JDKs) and the maven recipe runs on
JDK 11:

```bash
# server-unit (JDK 8)
(cd server && JAVA_HOME=$JDK8_HOME ./gradlew test)

# cli-test (bootstraps ginkgo into cli/bin itself)
make -C cli test

# automation-compile (the tree compiles against a few PXF server jars)
(cd server && JAVA_HOME=$JDK8_HOME ./gradlew jar)
mkdir -p /tmp/pxf-lib
for jar in server/pxf-*/build/libs/pxf-*.jar; do
  name=$(basename "$jar"); cp "$jar" "/tmp/pxf-lib/${name%%-[0-9]*}.jar"
done
d=$(mktemp -d) && jar cf /tmp/pxf-lib/pxf-extras.jar -C "$d" .
(cd automation && JAVA_HOME=$JDK11_HOME mvn -B test-compile -Dpxf.lib=/tmp/pxf-lib)

# docs-static-check
bash .github/scripts/docs-linkcheck.bash
```

### Debugging a red run

1. Read the job summary (the Actions run page) — each job posts its
   result there, `server-unit` with test totals.
2. `server-unit` failures upload the JUnit XML as a run artifact
   (`server-unit-test-results-*`).
3. For interactive debugging, re-run via **Run workflow** (the
   `workflow_dispatch` trigger) with `debug_enabled` checked: on
   failure the job opens a tmate session and prints the SSH string in
   the log.

## The DB-extensions lane — advisory checks for the C extensions

[![PXF DB Extensions CI](https://github.com/warehouse-pg/whpg-pxf/actions/workflows/pxf-db-extensions-ci.yml/badge.svg)](https://github.com/warehouse-pg/whpg-pxf/actions/workflows/pxf-db-extensions-ci.yml)
[![PXF DB Extensions weekly](https://github.com/warehouse-pg/whpg-pxf/actions/workflows/pxf-db-extensions-weekly.yml/badge.svg)](https://github.com/warehouse-pg/whpg-pxf/actions/workflows/pxf-db-extensions-weekly.yml)

Compiles `external-table/` and `fdw/` against real WarehousePG headers
and runs the extensions' own pg_regress suites in a demo cluster.
**Advisory lane**: it is
path-filtered to the C surface, so it does not report on most PRs and
must never be added to required status checks (a required check that
never reports blocks merges). If it should ever become requirable, the
way to do it is an `if: always()` aggregator job that reports on every
PR — not by requiring these path-filtered jobs directly.

Everything is secret-free and fork-PR-safe: pull requests get a
read-only token, cache access on PRs is restore-only, and the only
write permission (issues) is granted by a stub whose events a pull
request can never raise. No job needs a privileged container.

### Files — one lane, two stubs per branch

The lane is a **reusable workflow that exists on `main` only**; every
branch reaches it through short stubs that pin it `@main`. A fix to the
lane lands once and takes effect on both branches at their next run.

| File | Where | Role |
|---|---|---|
| `pxf-db-extensions-lane.yml` | `main` only | The jobs (`plan`, `checks`, `upstream-canary`, `watcher-selftest`, `pin-freshness`, `failure-issue`), the version pins (as `workflow_call` inputs with defaults) and the cache discipline. `on: workflow_call` only — it never triggers by itself |
| `pxf-db-extensions-ci.yml` | `main` and `release-6.x` | Stub: `pull_request` (path-filtered) and `push` to `ci/**`. Token `contents: read` |
| `pxf-db-extensions-weekly.yml` | `main` and `release-6.x` | Stub: Monday `schedule` and `workflow_dispatch`. Token `contents: read` + `issues: write`. The `schedule:` block is inert off the default branch, so the weekly run checks out both branches itself |
| `.github/actions/whpg-tree-provenance/` | `main` only | Composite action: unpack a built tree and assert its `whpg-build.{ref,sha,gp-major}` before use. The lane references it `warehouse-pg/whpg-pxf/.github/actions/whpg-tree-provenance@main` (a relative `./.github/actions/...` would resolve from the triggering branch's checkout) |
| `.github/scripts/*.bash` | both branches | The build, check and watcher scripts. **Deliberately per-branch**: the lane runs them from the checkout of the branch under test, so a PR that edits a script still tests its own edit (the one self-testing property a PR keeps, below), and a branch can carry a script fix the other branch does not need yet. The scripts have been byte-identical across branches since the WHPG 6 axis landed; keep it so unless a branch has a reason — a script change lands on `main` first and reaches `release-6.x` as a cherry-pick of the squash, as before |

```
EVENT                                 STUB FROM            LANE + ACTION FROM   SCRIPTS + SOURCES FROM
────────────────────────────────────  ───────────────────  ───────────────────  ─────────────────────────
PR → main                             merge ref (main)     main                 merge ref
PR → release-6.x                      merge ref (6.x)      main                 merge ref
push to ci/<name>                     ci/<name>            main (unless re-pointed, below)  ci/<name>
Run workflow on <ref>                 <ref>                main                 <ref>
Monday 04:30 UTC (schedule)           main (only option)   main
                                        ├── checks leg: checkout main        → main's scripts, main's sources
                                        └── checks leg: checkout release-6.x → release-6.x's scripts and sources
```

The stubs pass no pins, so the lane's input defaults are the single
source of truth. If `release-6.x` ever has to pin a different
WarehousePG than `main` (an older 7.x tag, say), it passes `with:
whpg7-tag: … whpg7-sha: …` in its stubs instead of forking the lane.

### Testing a change to the lane — the trade-off

A `pull_request` run cannot test its own edit to the lane or the
composite action: the stub's `@main` reference resolves to the **real
`main`**, never to the PR's merge ref. A PR that edits
`pxf-db-extensions-lane.yml` therefore runs main's current lane against
the PR's sources and scripts — a useful smoke, but not a test of the
edit. (This is the price of one copy; the alternative, a local-path
`workflow_call`, resolves from the caller's branch and would need the
full file on every branch again.) Scripts are the exception: they are
checked out per branch, so a script edit IS tested by its PR.

A push to a `ci/**` branch is the way to test a lane change:

1. On `ci/<name>`, re-point every same-repo reference at the branch —
   the `uses:` line in both stubs and the two composite-action
   references in the lane — and push:
   ```bash
   git ls-files .github/workflows .github/actions | xargs sed -i 's|warehouse-pg/whpg-pxf/\(.*\)@main|warehouse-pg/whpg-pxf/\1@ci/<name>|'
   ```
   The `push: ci/**` trigger in `pxf-db-extensions-ci.yml` runs the PR
   path (`plan` + `checks` + `watcher-selftest`). To run the weekly
   path (canary, pin watcher in dry-run, everything else) dispatch
   `pxf-db-extensions-weekly.yml` on the branch; a dispatch-only file
   has no Actions id until it has run once from a branch, so the first
   trial also needs a temporary `push: branches: ['ci/<name>']` trigger
   in the weekly stub (remove it with the re-point).
2. Before merge, restore `@main` everywhere (reverse the `sed`). The
   `watcher-selftest` job enforces this on `pull_request`: it fails the
   PR if any `warehouse-pg/whpg-pxf/.github/...@<ref>` reference in the
   workflow or action YAML is not `@main`. A merged re-point would leave
   the stub following a branch about to be deleted.
3. The schedule path cannot be trial-proven from a branch (cron fires
   from the default branch's real file only). Its first proof is the
   first Monday after merge — read that run.

Cache note for trials: a `ci/**` run never saves the cache, and a
change to `build-whpg.bash` changes the cache key, so such a trial
cold-builds both majors (~10 min each, in parallel). After merge the
first `main` run (a dispatch, or Monday) rebuilds once and saves.

### Permissions — why two stubs

`pin-freshness` and `failure-issue` need `issues: write`. A called
workflow's jobs can only **downgrade** the token the calling job passes
— a called job that declares more than its caller granted fails the
run at startup — and `pull_request` runs must stay read-only. So:

- the two issue-writing jobs declare **no** `permissions:` block and
  inherit the caller's grant;
- every other lane job pins itself to `contents: read`, so even on the
  weekly path the build/test legs never hold a writable token;
- the `pull_request`/`push` stub grants `contents: read` (the writers
  never run on its events — their `if:` is `schedule || dispatch`);
- the `schedule`/`dispatch` stub grants `contents: read` +
  `issues: write`.

A `workflow_dispatch` is on the weekly stub, so a dispatch always has
the watcher's grant; the watcher still only mutates issues from runs on
`main` (dry-run elsewhere), as before.

### What this lane tests — precisely

Two things, both about the database-resident extensions themselves,
each against BOTH pinned database majors (WHPG 7 and WHPG 6):

1. **They compile** — `pxf.so` and `pxf_fdw.so` build against the
   pinned WarehousePG's real headers, catching `cdb/*.h` and API drift
   at PR time instead of at package-build time.
2. **They install and enforce their SQL contract** — `CREATE
   EXTENSION` succeeds, and the DDL option validators accept or reject
   options at each catalog level with the exact expected message. This
   is almost entirely negative testing: across the six suites, ~188
   statements carry 134 asserted ERROR lines (version-noise WARNINGs
   and NOTICEs are matchignored, not asserted — see the goldens note
   under "The version pins"). Coverage is deliberately lopsided — the
   four fdw suites are dense validator coverage (133 assertions over
   wrapper/server/user-mapping/foreign-table DDL); the two
   external-table suites (`setup`, `pxfinvalid`) are an
   install-plus-reject-invalid-profile smoke
   (1 assertion), because external-table's substantive suite is the
   excluded data-path test below. Describe this lane as "dense fdw
   validator coverage plus a compile gate and install smoke for both
   extensions", not "6 suites", which would imply even coverage.

**The boundary — no ambiguity:** no PXF server is ever started here,
and no external data is ever read or written. The moment a test needs
the PXF service, it is out of scope for this lane — that is exactly
why the `pxf` suite is excluded (below). This lane answers "do the
extensions compile against this WarehousePG, install into it, and
enforce their DDL/option contract"; it says nothing about whether a
SELECT through PXF returns rows.

### Jobs

| Job | What it runs | Measured time |
|---|---|---|
| `plan` | Turns the pin inputs into the leg matrices (`ref × major` for `checks`, one per major for `pin-freshness`) and validates them (40-hex SHAs, non-empty tags, a JSON ref list). The single source of the major list and the pins; pure expression work | seconds |
| `checks` (×2 per ref: whpg7, whpg6) | One **self-contained** job per leg: restore the pinned WarehousePG tree from the Actions cache (or **build it from source in-run on a miss**), assert provenance, `make -C external-table && make -C fdw` against it (header/API drift fails with its own step name), then demo cluster (no mirrors, single segment) + the full suite set — dense fdw validator coverage (`wrapper`/`server`/`user_mapping`/`foreign_table`) plus the external-table install smoke (`setup`, `pxfinvalid`). The expected files are major-neutral: gpdiff `start_matchignore` blocks absorb the known per-major noise. No `needs:` edge between majors, so one major's infrastructure failure cannot grey out the other's signal | ~4m on a cache hit; a cold build is around ten minutes per major, whpg6 longer (it also builds the Xerces 3.1 its ORCA needs, and GP 6's `gpinitsystem` is slower) |
| `upstream-canary` | Weekly: builds WarehousePG at its `main` branch (7.x line) and runs the same checks — early warning that upstream changes broke the PXF C layer | ~9m (skipped rebuild when upstream hasn't moved) |
| `watcher-selftest` | Fixture tests for the pin watcher's decision table and the suite-derivation filter; on `pull_request`, the `@main` tripwire above | seconds |
| `pin-freshness` (×2) | Weekly, one leg per pinned major: stale (newer `<major>.x-WHPG` exists), moved (annotated tags peeled first), or deleted. Files/updates/auto-closes one issue PER MAJOR, all labeled `ci-db-extensions-pin` (the issue-body marker carries the major, so the legs never touch each other's issues). Decision logic self-tests against fixtures first | seconds |
| `failure-issue` | On a scheduled run's failure, opens or updates a GitHub issue labeled `ci-db-extensions-failure` | seconds |

A cache-miss run (evicted cache or a fresh version pin) takes ~15
minutes — **a rare slow run is by design**: the alternative (skipping
when the cache is cold) would silently drop coverage, and PR runs
cannot refill the cache (see Caches below), so slow-but-tested always
wins.

Two external-table suites are deliberately not run — `pxf` and
`pxfpartition` — because they query external tables through the
built-in Demo connectors and need a running PXF service, which is
outside this lane's scope. The suite list is derived from the Makefile's
`REGRESS` line minus that named exclusion list
(`run-db-extension-checks-gpadmin.bash`), so a suite added upstream runs
here by default and, if it turns out to need a service, fails loudly
rather than being skipped without anyone noticing. The eight
external-table C mock tests are also not run: they link against built
backend objects and must sit inside a configured, compiled WarehousePG
source tree (their Makefile assumes a `gpcontrib/pxf` layout), which
this lane has only transiently during an in-run source build on a
cache miss — coverage that depends on cache state would make runs
non-comparable.

### The version pins

The lane builds and tests against ONE pinned WarehousePG version PER
DATABASE MAJOR. The pins are the **`workflow_call` input defaults** at
the top of `pxf-db-extensions-lane.yml` on `main`:

- `whpg7-tag` / `whpg7-sha` — the WHPG 7 pin (e.g. `7.6.0-WHPG`)
- `whpg6-tag` / `whpg6-sha` — the WHPG 6 pin (e.g. `6.27.6-WHPG`)
- `build-image-digest` — the digest of
  `ghcr.io/warehouse-pg/whpg-rocky8-build` every job runs in (both
  majors use the same digest — warehouse-pg's own 6.x CI builds in the
  same image family). Written once: the input feeds every
  `container.image` line and every cache key

Each SHA is asserted at build time and by every consumer of the built
tree (the `whpg-tree-provenance` action). The WHPG 6 build has its own
bring-up inside `build-whpg.bash`, mirroring warehouse-pg's 6.x CI: the
whole tree is built against Python 2, and the distro's Xerces-C 3.2 is
replaced with the 3.1 build that WHPG 6's ORCA requires (its gporca
compiles as `-std=gnu++98`, and 3.2 typedefs `XMLCh` to the C++11
`char16_t`; WHPG 7 is unaffected because its gporca compiles as
`-std=c++14`). The 3.1 library is then vendored into the install tree,
because check steps unpack that tree in a container that has only 3.2
— the same reason warehouse-pg's own 6.x release build vendors it. Note
that `config/orca.m4` cannot catch the mismatch: it probes for
`strnicmp` in `libxerces-c` and calls that the patched build, but stock
3.2.5 exports that symbol too. The configure flag set is ONE list
shared by both majors, with the two flags WHPG 6 does not know
(`--with-quicklz`, `--with-llvm`) appended for 7 only. The only
deviation from their CI is the PL/Python-on-3 rebuild, skipped because
this lane runs no PL/Python suites.

**Bumping a pin** is a deliberate PR against `main` that updates the
pin pair (and the image digest when it moved) together in the lane's
input defaults — the stubs on either branch need no change:
resolve the new tag's COMMIT SHA — use
`gh api repos/warehouse-pg/warehouse-pg/commits/<tag> --jq .sha`, which
returns the commit for lightweight AND annotated tags alike (the
`git/refs/tags/<tag>` endpoint returns the tag *object* SHA for an
annotated tag — recording that would loudly fail build-whpg.bash's
rev-parse assert on the next cache miss and trip a false retag alert
from the pin watcher),
resolve the image digest (`docker buildx imagetools inspect
ghcr.io/warehouse-pg/whpg-rocky8-build`), and update the defaults. The
first run after the bump rebuilds from source (the cache key contains
the SHA, so the old cache simply stops matching); the next scheduled
run re-saves the cache. If the image was rebuilt upstream and the old
digest's layers were garbage-collected, jobs fail at container start
with a pull error — bump the digest.

A pin bump can also legitimately drift the pg_regress goldens.
Known NOISE lines whose presence or position differs by major (the
resource-queue NOTICE on CREATE ROLE; the zero-column CREATE warning,
which every major emits but in a different position relative to the
validator error) are absorbed by
`start_matchignore` blocks in the suites' sql/expected files, so one
expected file serves every supported major. If `checks` still
goes red after a bump, read `regression.diffs` from the failed run's
artifact: a NEW noise line gets a matchignore entry in the same PR as
the bump; anything touching rows or error text is a real regression —
never matchignore those (the error messages ARE the assertions).

**The pin watcher** (`pin-freshness` job) checks the pin weekly and
files an issue labeled `ci-db-extensions-pin` when action is needed:

- *stale* — a newer `<major>.x-WHPG` tag exists: run the bump procedure
  above at your convenience; close the issue via the bump PR
  (`Closes #N`) or let the watcher auto-close it once the pin catches
  up.
- *moved* — the pinned tag no longer resolves to the pinned commit:
  verify upstream re-cut the tag legitimately before bumping the SHA;
  treat as a supply-chain signal until explained.
- *deleted* — the pinned tag is gone: re-pin promptly; the next cache
  miss cannot rebuild.

It keeps at most one open issue per major (state tracked in an HTML
comment in the issue body), never edits the pin itself, and only
mutates issues from runs on `main` — a dispatch on any other ref echoes
its decision to the step summary instead. Its decision table lives in
`.github/scripts/pin-freshness-decide.bash` with fixture tests
(`test-pin-freshness-decide.bash`) that run before every live
decision.

*Considered and not done — per-major issue labels*
(`ci-db-extensions-pin-whpg6` / `-whpg7`): the lookup is already
structural through the `<!-- pin-state: whpg<major>:… -->` body marker,
which also carries the reported state the label could not. Two more
labels would add a label-migration step for any open issue, a second
place the major is encoded, and no new capability. Revisit only if the
body-marker lookup ever has to be replaced.

### Triggers

| Trigger (stub) | What runs |
|---|---|
| `pull_request` → `main`, `release-6.x`, touching `fdw/**`, `external-table/**`, `api_version`, or the lane's own files (`pxf-db-extensions-ci.yml`) | `plan` → `checks` [whpg7, whpg6] on the merge ref, `watcher-selftest` (incl. the `@main` tripwire) |
| `push` → `ci/**` (`pxf-db-extensions-ci.yml`) | Same (no path filter — a `ci/**` push is an explicit request) |
| `schedule` (Mondays 04:30 UTC, `pxf-db-extensions-weekly.yml`) | The same checks as a rot-check over **both branches** (`main`, `release-6.x` — passed as the lane's `refs` input; schedules fire only from the default branch, so this is release-6.x's weekly coverage) and **both majors**: four `checks` legs. Plus `upstream-canary` and `pin-freshness`; failures feed `failure-issue`. Path filters don't apply to schedules |
| `workflow_dispatch` (`pxf-db-extensions-weekly.yml`, on any ref) | Everything incl. the canary and the pin watcher (dry-run off `main`), on the dispatched ref; optional `debug_enabled` tmate input |

### Caches

Three steady cache entries, all small (~80 MB each): the pinned-tag
install trees per major (`whpg7-el8-<pin-sha>-<script-hash>-<digest>`,
`whpg6-el8-…`) and the canary's (`whpg-el8-main-<sha>-…`), plus the
whpg6 toolchain entry (`whpg6-toolchain-el8-xerces312-<digest>`, keyed
on the image digest only so it survives pin bumps).
Keys are exact-match with no `restore-keys` — a stale or wrong-version
tree can never be silently reused, and keys self-invalidate on pin
bumps. **Only runs on `main` save the cache** (the weekly schedule is
the steady writer); PR runs restore only, so a topic-branch run can
never pin a cache PRs would miss. The key shapes are unchanged by the
reusable-workflow restructure, so entries saved before it still hit.
Every delivered tree carries provenance files (`whpg-build.ref`,
`.sha`, `.gp-major`) that the `whpg-tree-provenance` action asserts
before use.

There is no `push: main` trigger, so a merge does not warm the cache
by itself. After merging a lane or script change or a pin bump, run
`pxf-db-extensions-weekly.yml` once via **Run workflow** on `main` to
populate both `whpg{6,7}-el8-…` entries; otherwise every C-touching PR
cold-builds (~15 min end-to-end, both majors in parallel) until the
next Monday schedule saves the entries.

The canary writes a new entry each time upstream `main` moves; old
entries age out via the 7-day eviction, so about one or two are alive
at any time. If the canary cadence is ever increased or given a ref
matrix, revisit that math.

*Considered and not done — a per-major script hash in the key*
(hashing only the WHPG 6 or WHPG 7 part of `build-whpg.bash`, or
splitting the script, so a 7-only recipe edit does not rebuild the 6
tree): the recipe is now one shared flag list plus a 6-only bring-up
block, so most edits legitimately touch both majors; a split-key scheme
would need a stable way to hash "the relevant section" that a reviewer
can trust, and the cost it saves is one ~10-minute parallel rebuild on
the next `main` run after a rare recipe edit. Not worth a second
hashing rule. Revisit if recipe edits become frequent.

### Reproducing locally

The jobs run two scripts that work anywhere the build container runs —
`.github/scripts/build-whpg.bash` (build + install WarehousePG) and
`.github/scripts/run-db-extension-checks.bash` (demo cluster + installchecks).
On an x86_64 linux host with docker, from the repository root:

To reproduce a CI run locally, take the three pins from the lane's
`workflow_call` input defaults (`pxf-db-extensions-lane.yml` on
`main`) — the single source of truth, so they are not copied here — and
pass them in. With the digest on the image and `WHPG_SHA` set, the run
is byte-for-byte what CI executed: `build-whpg.bash` fetches that exact
commit and asserts it, and the container is the exact image.

```bash
# From pxf-db-extensions-lane.yml inputs: -> whpg7-tag + whpg7-sha (or whpg6-tag + whpg6-sha), build-image-digest
WHPG_TAG=<whpg7-tag>; WHPG_SHA=<whpg7-sha>; DIGEST=<build-image-digest>
docker run --rm -it --platform linux/amd64 \
  -v "$PWD:/pxf" -w /pxf \
  --hostname cdw --shm-size=2gb \
  "ghcr.io/warehouse-pg/whpg-rocky8-build@${DIGEST}" \
  bash -c "WHPG_REF=${WHPG_TAG} WHPG_SHA=${WHPG_SHA} bash .github/scripts/build-whpg.bash \
           && WHPG_REF=${WHPG_TAG} PXF_SRC=/pxf bash .github/scripts/run-db-extension-checks.bash"
```

Dropping `@${DIGEST}` (bare tag) and `WHPG_SHA` gives a convenient
approximation that can drift if either tag is ever moved.

For the WHPG 6 leg, take `whpg6-tag` and `whpg6-sha` instead. Nothing
else changes: `build-whpg.bash` derives the major from the ref and does
the WHPG 6 bring-up itself (Python 2, the Xerces-C 3.1 build and its
vendoring into the tree), and the check script's `userdel gpadmin`
guard is keyed on the major it finds in the tree.

The WarehousePG source build takes ~10 minutes on CI-class hardware
(much longer under emulation on arm64 hosts). The invocation mirrors
the `checks` job's build + installcheck steps — the scripts themselves
are exercised by every CI run of this lane.

### Debugging a red run

1. The job summaries show the pin, whether the cache hit, and per-job
   status; `plan` prints the leg matrix it emitted.
2. `checks` failures upload `regression.diffs` and the per-test result
   files as a run artifact (`installcheck-results*`).
3. Re-run `pxf-db-extensions-weekly.yml` via **Run workflow** with
   `debug_enabled` checked for a tmate session on failure.
4. A red weekly run opens/updates the `ci-db-extensions-failure` issue; a red
   `upstream-canary` with green `checks` means upstream
   WarehousePG `main` changed something the PXF C layer depends on —
   that is the canary doing its job, not a PXF regression. This reading
   holds for the **scheduled** run, which checks out `main`. A canary
   red on a `workflow_dispatch` from another branch tests *that
   branch's* fixtures against upstream head, so it reads as "this
   branch vs upstream" instead.
5. A run that fails at startup with "workflow was not found" or a
   permissions error on the `db-extensions` job is a stub/lane contract
   problem, not a test failure: the `@main` lane file or action was
   renamed, or a called job asked for more than its stub grants (see
   "Permissions — why two stubs").
