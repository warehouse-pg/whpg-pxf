# PXF GitHub Actions workflows

## `pxf-ci.yml` — the pull-request gate

[![PXF CI](https://github.com/warehouse-pg/whpg-pxf/actions/workflows/pxf-ci.yml/badge.svg)](https://github.com/warehouse-pg/whpg-pxf/actions/workflows/pxf-ci.yml)

Fast, hermetic verification for every pull request. All jobs run without
secrets and are safe for fork PRs. Release packaging, cluster-level
integration testing (databases, Hadoop stacks), and certification CI are
maintained by EDB outside this repository; nothing here builds, publishes,
or releases artifacts.

### Jobs

| Job | What it runs | Toolchain | Measured time (cold / warm cache) |
|---|---|---|---|
| `server-unit` | The full Java unit-test suite (`./gradlew test` from `server/`, ~1,840 tests) | Temurin JDK 8 (the build requires it) | 4m36s / ~3m40s |
| `cli-test` | The Go CLI Ginkgo suites, including the cluster-free end-to-end suite (`make -C cli test`) | Go (version from `cli/go.mod`) | 1m14s / ~25s |
| `automation-compile` | Proves the integration-test tree compiles and its dependencies resolve (`mvn test-compile` from `automation/`). A compile signal only — executing those tests needs a full database + Hadoop environment and happens in the EDB-maintained CI | JDK 8 (to build the PXF server jars the tree compiles against) + JDK 11 for maven | 2m53s / ~1m30s |
| `docs-static-check` | `.github/scripts/docs-linkcheck.bash`: static link/anchor integrity for the docs book and top-level markdown (cross-page links and anchors, in-page fragments, subnav targets, orphan pages) | bash | ~10s |

Times were measured on `ubuntu-latest` runners during the workflow's
trial (2026-09); `timeout-minutes` on each job is set to roughly twice
the cold-cache baseline, so a job that doubles its wall time fails
rather than silently absorbing the regression.

### Triggers

| Trigger | What runs |
|---|---|
| `pull_request` → `main`, `release-6.x` | All four jobs |
| `push` → `main`, `release-6.x` | All four jobs (not cancelled by newer pushes) |
| `push` → `ci/**` | All four jobs — **opt-in CI for feature branches**: push any branch named `ci/<something>` to get full CI without opening a PR |
| `schedule` (Mondays 03:00 UTC) | The weekly lane, see below |
| `workflow_dispatch` | All four jobs; optional `debug_enabled` input starts a [tmate](https://github.com/mxschmitt/action-tmate) session on failure for interactive debugging |

Concurrency: for every ref except `main` and `release-6.x`, a newer run
cancels an in-progress one.

### The weekly lane

Scheduled runs exist to catch rot that PR traffic doesn't: broken
dependency resolution, upstream URL/registry drift, "green only because
nobody opened a PR this month" — and they keep the gradle/maven/go
caches warm (GitHub evicts caches unused for ~7 days).

- The same four jobs run as a branch matrix over `main` **and**
  `release-6.x` (schedules only fire from the default branch, so the
  other branch is checked out explicitly).
- `server-unit` additionally runs a **JDK 11 test lane**: the build
  stays on JDK 8, but the tests execute on JDK 11 — the runtime the PXF
  server daemon actually uses (`./gradlew test -PtestJvm=...`).
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

`setup-java`/`setup-go` cache the gradle, maven, and go dependency
trees, keyed on the respective lockfiles/build files. Note for
`automation-compile`: the jsystem test-framework artifacts resolve from
`maven.top-q.co.il`, which is occasionally unavailable; the maven cache
(kept warm by the weekly lane) makes that a cold-cache-only risk. If a
run fails resolving `org.jsystemtest:*`, re-run it once the host is
reachable again.

### Reproducing the jobs locally

```bash
# server-unit
cd server && ./gradlew test

# cli-test (bootstraps ginkgo into cli/bin itself)
make -C cli test

# automation-compile (the tree compiles against a few PXF server jars)
cd server && ./gradlew jar
mkdir -p /tmp/pxf-lib
for jar in server/pxf-*/build/libs/pxf-*.jar; do
  name=$(basename "$jar"); cp "$jar" "/tmp/pxf-lib/${name%%-[0-9]*}.jar"
done
d=$(mktemp -d) && jar cf /tmp/pxf-lib/pxf-extras.jar -C "$d" .
cd automation && mvn -B test-compile -Dpxf.lib=/tmp/pxf-lib

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

### Policies

- **No auto-retries.** A test that flakes gets investigated and, if
  necessary, a tracked exclusion — not a silent rerun.
- **Secret-free.** PR jobs reference no secrets (fork PRs receive none
  anyway); the weekly issue step uses only the workflow's own token.
- Third-party actions are pinned by commit SHA; official `actions/*`
  are pinned by major version.
