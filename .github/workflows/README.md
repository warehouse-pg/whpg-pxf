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
| `server-unit` | The full Java unit-test suite (`./gradlew test` from `server/`, ~1,840 tests) | Temurin JDK 8 (the build requires it) | 4m36s / ~3m40s |
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
| `pull_request` → `main`, `release-6.x` | All four jobs. (PRs targeting `release-6.x` run the gate once this workflow is present on that branch.) |
| `push` → `main`, `release-6.x` | All four jobs (not cancelled by newer pushes) |
| `push` → `ci/**` | All four jobs — **opt-in CI for feature branches**: push any branch named `ci/<something>` to get full CI without opening a PR |
| `schedule` (Mondays 03:00 UTC) | The weekly lane, see below |
| `workflow_dispatch` | All four jobs; optional `debug_enabled` input starts a [tmate](https://github.com/mxschmitt/action-tmate) session on failure for interactive debugging |

Concurrency: for every ref except `main` and `release-6.x`, a newer run
cancels an in-progress one.

> Note for this branch (`release-6.x`): the weekly schedule only fires
> from the repository's default branch, so the schedule trigger in this
> branch's copy of the workflow is inert — main's weekly lane checks out
> and tests `release-6.x` explicitly.

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

Current matrix exclusions, each with the reason in the workflow file:

| Excluded | Why | Unblocks when |
|---|---|---|
| JDK 11 lane on `release-6.x` | that branch lacks the `testJvm` hook in `server/build.gradle`; the leg would silently run on JDK 8 | the hook is backported |
| `automation-compile` on `release-6.x` | that branch's pom resolves the jsystem artifacts through a retired, credentialed artifact registry | its pom resolves from public repositories the way main's does |
| `docs-static-check` on `release-6.x` | the sweep script ships on main | the script is backported |

### Caches

The maven and go dependency trees are cached by `setup-java`/`setup-go`,
keyed on the respective lockfiles/build files. The gradle cache uses
explicit cache steps with a **single writer**: `server-unit` (which
warms the fullest dependency set, build + test) both restores and
saves; `automation-compile` restores the same key read-only, since its
gradle needs are a subset — cache keys are immutable once saved, so a
faster-finishing job must never pin a half-warmed cache. Note that
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
