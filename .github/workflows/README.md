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

## `pxf-db-extensions-ci.yml` — advisory checks for the C extensions

[![PXF DB Extensions CI](https://github.com/warehouse-pg/whpg-pxf/actions/workflows/pxf-db-extensions-ci.yml/badge.svg)](https://github.com/warehouse-pg/whpg-pxf/actions/workflows/pxf-db-extensions-ci.yml)

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
write permission (issues) lives in jobs that never trigger on pull
requests. No job needs a privileged container.

### What this lane tests — precisely

Two things, both about the database-resident extensions themselves:

1. **They compile** — `pxf.so` and `pxf_fdw.so` build against the
   pinned WarehousePG's real headers, catching `cdb/*.h` and API drift
   at PR time instead of at package-build time.
2. **They install and enforce their SQL contract** — `CREATE
   EXTENSION` succeeds, and the DDL option validators accept or reject
   options at each catalog level with the exact expected message. This
   is almost entirely negative testing: across the six suites, ~188
   statements carry 137 asserted ERROR/WARNING/NOTICE lines. Coverage
   is deliberately lopsided — the four fdw suites are dense validator
   coverage (136 assertions over wrapper/server/user-mapping/
   foreign-table DDL); the two external-table suites (`setup`,
   `pxfinvalid`) are an install-plus-reject-invalid-profile smoke
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
| `whpg-prepare` | Provides the installed WarehousePG tree the run tests against: restored from the Actions cache, or **built from source in-run on a cache miss** | ~1m on cache hit / ~10m on miss |
| `compile` | `make -C external-table && make -C fdw` against the delivered tree — catches header/API drift | ~1.5m |
| `installcheck` | Demo cluster (no mirrors, single segment) + the extensions' own pg_regress suites — dense fdw validator coverage (`wrapper`/`server`/`user_mapping`/`foreign_table`) plus the external-table install smoke (`setup`, `pxfinvalid`); see "What this lane tests" above | ~1.5m |
| `upstream-canary` | Weekly: builds WarehousePG at its `main` branch and runs the same checks — early warning that upstream changes broke the PXF C layer | ~9m (skipped rebuild when upstream hasn't moved) |
| `pin-freshness` | Weekly: checks the WHPG pin against upstream tags — stale (newer `7.x-WHPG` exists), moved (pinned tag no longer at the pinned commit; annotated tags peeled first), or deleted. Files/updates/auto-closes one issue labeled `ci-db-extensions-pin`. Its decision logic self-tests against fixtures first | seconds |
| `failure-issue` | On a scheduled run's failure, opens or updates a GitHub issue labeled `ci-db-extensions-failure` | seconds |

A cache-hit PR run totals **about 4 minutes** end to end. A cache-miss
run (evicted cache or a fresh version pin) takes ~15 minutes — **a rare
slow run is by design**: the alternative (skipping when the cache is
cold) would silently drop coverage, and PR runs cannot refill the cache
(see Caches below), so slow-but-tested always wins.

The external-table suite's `pxf` test is deliberately not run: it
queries external tables through the built-in Demo connectors and needs
a running PXF service, which is outside this lane's scope. The eight
external-table C mock tests are also not run: they link against built
backend objects and must sit inside a configured, compiled WarehousePG
source tree (their Makefile assumes a `gpcontrib/pxf` layout), which
this lane has only transiently inside `whpg-prepare` on a cache miss —
coverage that depends on cache state would make runs non-comparable.

### The version pin

The lane builds and tests against ONE WarehousePG version, pinned in
`pxf-db-extensions-ci.yml`:

- `WHPG_TAG` — the tag to build (e.g. `7.6.0-WHPG`)
- `WHPG_TAG_SHA` — that tag's commit (asserted at build time and by
  every consumer of the built tree)
- the `container.image` digest of `ghcr.io/warehouse-pg/whpg-rocky8-build`
  (all jobs use the same digest)

**Bumping the pin** is a deliberate PR that updates all three together:
resolve the new tag's COMMIT SHA — use
`gh api repos/warehouse-pg/warehouse-pg/commits/<tag> --jq .sha`, which
returns the commit for lightweight AND annotated tags alike (the
`git/refs/tags/<tag>` endpoint returns the tag *object* SHA for an
annotated tag — recording that would loudly fail build-whpg.bash's
rev-parse assert on the next cache miss and trip a false retag alert
from the pin watcher),
resolve the image digest (`docker buildx imagetools inspect
ghcr.io/warehouse-pg/whpg-rocky8-build`), and update every
`container.image` line plus the `env` block in `pxf-db-extensions-ci.yml`. The
first run after the bump rebuilds from source (the cache key contains
the SHA, so the old cache simply stops matching); the next scheduled
run re-saves the cache. If the image was rebuilt upstream and the old
digest's layers were garbage-collected, jobs fail at container start
with a pull error — bump the digest.

A pin bump can also legitimately drift the pg_regress goldens: the
expected outputs under `fdw/expected/` and `external-table/expected/`
are pinned to the **pinned server version's** output (WARNINGs and
NOTICEs come and go across WHPG releases — that drift is exactly what
kept the fdw suites red for years). If `installcheck` goes red after
a bump, read `regression.diffs` from the failed run's artifact: pure
output drift gets the goldens reconciled in the same PR as the bump;
anything touching rows or errors is a real regression.

**The pin watcher** (`pin-freshness` job) checks the pin weekly and
files an issue labeled `ci-db-extensions-pin` when action is needed:

- *stale* — a newer `7.x-WHPG` tag exists: run the bump procedure
  above at your convenience; close the issue via the bump PR
  (`Closes #N`) or let the watcher auto-close it once the pin catches
  up.
- *moved* — the pinned tag no longer resolves to the pinned commit:
  verify upstream re-cut the tag legitimately before bumping the SHA;
  treat as a supply-chain signal until explained.
- *deleted* — the pinned tag is gone: re-pin promptly; the next cache
  miss cannot rebuild.

It keeps at most one open issue (state tracked in an HTML comment in
the issue body), never edits the pin itself, and only mutates issues
from runs on `main` — a dispatch on any other ref echoes its decision
to the step summary instead. Its decision table lives in
`.github/scripts/pin-freshness-decide.bash` with fixture tests
(`test-pin-freshness-decide.bash`) that run before every live
decision.

### Triggers

| Trigger | What runs |
|---|---|
| `pull_request` → `main`, `release-6.x`, touching `fdw/**`, `external-table/**`, `api_version`, or the lane's own files | `whpg-prepare` → `compile` → `installcheck` |
| `push` → `ci/**` | Same (no path filter — a `ci/**` push is an explicit request) |
| `schedule` (Mondays 04:30 UTC) | The same checks as a rot-check (path filters don't apply to schedules) plus `upstream-canary` and `pin-freshness`; failures feed `failure-issue` |
| `workflow_dispatch` | Everything incl. the canary and the pin watcher; optional `debug_enabled` tmate input |

The `release-6.x` trigger entry is pre-wired but inert for PRs cut
from that branch: a `pull_request` run uses the workflow file from the
merge ref (the PR head merged into the base), and a head cut from
release-6.x does not carry this file until the backport lands. A PR
whose head does carry it — notably the backport PR itself — will run
the lane, which is expected and self-validating for the backport.

### Caches

Two cache entries, both small (~80 MB each): the pinned-tag install
tree (`whpg-el8-<pin-sha>`) and the canary's (`whpg-el8-main-<sha>`).
Keys are exact-match with no `restore-keys` — a stale or wrong-version
tree can never be silently reused, and keys self-invalidate on pin
bumps. **Only runs on `main` save the cache** (the weekly schedule is
the steady writer); PR runs restore only, so a topic-branch run can
never pin a cache PRs would miss. Check jobs receive the tree as a
same-run artifact (`whpg-install-el8`), never via the cache directly.
Every delivered tree carries provenance files (`whpg-build.ref`,
`.sha`, `.gp-major`) that consumers assert before use.

There is no `push: main` trigger, so a merge does not warm the cache
by itself. After merging the lane or a pin bump, run the workflow once
via `workflow_dispatch` on `main` to populate `whpg-el8-<pin-sha>`;
otherwise every C-touching PR cold-builds (~15 min end-to-end) until
the next Monday schedule saves the entry.

The canary writes a new entry each time upstream `main` moves; old
entries age out via the 7-day eviction, so about one or two are alive
at any time. If the canary cadence is ever increased or given a ref
matrix, revisit that math.

### Reproducing locally

The jobs run two scripts that work anywhere the build container runs —
`.github/scripts/build-whpg.bash` (build + install WarehousePG) and
`.github/scripts/run-db-extension-checks.bash` (demo cluster + installchecks).
On an x86_64 linux host with docker, from the repository root:

To reproduce CI byte-for-byte, take the pins from the workflow itself
(single source of truth — do not copy them into scripts or docs): use
the image digest from any `container.image` line in
`pxf-db-extensions-ci.yml` in place of the bare tag below, and
additionally export `WHPG_SHA=<WHPG_TAG_SHA from the env block>` —
`build-whpg.bash` asserts the checked-out commit against it when set.
The bare-tag form below is the convenient variant and can drift if the
image tag or the source tag is ever moved.

```bash
docker run --rm -it --platform linux/amd64 \
  -v "$PWD:/pxf" -w /pxf \
  --hostname cdw --shm-size=2gb \
  ghcr.io/warehouse-pg/whpg-rocky8-build \
  bash -c 'WHPG_REF=7.6.0-WHPG bash .github/scripts/build-whpg.bash \
           && WHPG_REF=7.6.0-WHPG PXF_SRC=/pxf bash .github/scripts/run-db-extension-checks.bash'
```

The WarehousePG source build takes ~10 minutes on CI-class hardware
(much longer under emulation on arm64 hosts). The invocation mirrors
the `whpg-prepare` + `installcheck` job steps — the scripts
themselves are exercised by every CI run of this lane.

### Debugging a red run

1. The job summaries show the pin, whether the cache hit, and per-job
   status.
2. `installcheck` failures upload `regression.diffs` and the per-test
   result files as a run artifact (`installcheck-results*`).
3. Re-run via **Run workflow** with `debug_enabled` checked for a tmate
   session on failure.
4. A red weekly run opens/updates the `ci-db-extensions-failure` issue; a red
   `upstream-canary` with a green `installcheck` means upstream
   WarehousePG `main` changed something the PXF C layer depends on —
   that is the canary doing its job, not a PXF regression.
