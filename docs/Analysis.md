# Analysis

How analyses can be started on demand and how Technolinator tells about the ones that fail.
The parameters mentioned here are described in [Runtime configuration](Runtime_Config.md).

## Manual analysis

The analysis of a repository can be triggered on demand, e.g. to analyze a repository for the first time without waiting for a push, or to retry after a failure:

```shell
curl -X POST 'http://localhost:8080/analyze?repository=my-org/my-repo'
```

The analysis happens in the background on the head of the **default branch**, and behaves exactly like the one of a push to it: the repository's configuration (`.github/technolinator.yml`) is read from the default branch, `APP_ENABLED_REPOS` and `enable: false` of the repository config apply, and the outcome is reported via commit status, status issue and metrics (`kind="push"`). The GitHub app must be installed for the repository.

The parameter `repository` (`owner/repo`) is required exactly once. The response is immediate, `202` carries the analyzed `commit` and the `traceId` to find the analysis in the log:

| Status | Meaning                                                                                                                   |
|--------|---------------------------------------------------------------------------------------------------------------------------|
| `202`  | Analysis started                                                                                                          |
| `400`  | `repository` missing, given more than once, or not given as `owner/repo`                                                  |
| `404`  | Repository or its default branch not found, or the GitHub app isn't installed for it                                      |
| `409`  | Repository excluded or disabled by configuration, archived, or an analysis of it (or of the same commit by a push) is in progress |
| `422`  | The configuration file of the repository can't be read                                                                    |
| `502`  | GitHub couldn't be asked about the repository                                                                             |

> **The endpoint is not protected.** Anyone who can reach it can make Technolinator analyze (i.e. build) any repository it has access to. Don't expose it beyond the trusted network, e.g. by not routing `/analyze` through the ingress.

## Commit status

Technolinator tells about its analyses by a [commit status](https://docs.github.com/en/rest/commits/statuses) named *Supply Chain Security*. GitHub knows the states `pending`, `success`, `failure` and `error`; Technolinator uses `error` for failed analyses of the default branch (as before), and `failure` for pull-requests.
Writing commit statuses can be switched off with `APP_COMMIT_STATUS_WRITE_ENABLED`.

### Default branch

The status of the head commit tells about the SBOM only: `success` (*SBOM available*, linking to its Dependency-Track project, or to the search for the projects of the repository, if there are several; *no SBOM available* if there is nothing to analyse) or `error` if the analysis failed or timed out. A `pending` status (*SBOM creation running*) is written before, if `APP_USE_PENDING_COMMIT_STATUS` is set.

Dependency-Track analyses the SBOM after it was uploaded, so it doesn't know the vulnerabilities by then. Therefore Technolinator asks it for the metrics of the repository's projects in the background after a successful analysis, and writes the status again once they are there, adding the **risk score** and the **number of vulnerabilities per severity**, as Dependency-Track calculated them:

```
SBOM available, risk score 42, vulnerabilities: 1 critical, 2 high, 3 medium, 4 low
```

The state stays `success`: Dependency-Track's findings don't fail the status of the default branch.

* The first attempt is made after 5 minutes (`APP_COMMIT_STATUS_DTRACK_METRICS_INITIAL_DELAY`), every further one after twice the time of the one before, but never later than after 4 hours (`..._MAX_DELAY`): 5, 10, 20, 40, 80, 160, 240, 240, ... minutes. After 24 hours (`..._MAX_AGE`) the attempts end.
* The metrics are used as soon as Dependency-Track calculated them after it processed the last BOM of every project of the repository, which is when it has analysed the vulnerabilities of the new SBOM (or any time later, if its metrics were refreshed meanwhile).
* A newer analysis of the repository replaces the attempts of the one before. The attempts are kept in memory only, so they end with a restart of Technolinator. The [housekeeping](Housekeeping.md#action-update-commit-status) catches up on those, and on metrics changed since.
* They can be switched off with `APP_COMMIT_STATUS_DTRACK_METRICS_ENABLED=false`.

### Pull-requests

The head commit of a pull-request gets a status telling about the vulnerabilities the analysis found, which are determined anyway for the pull-request comment, and linking to the projects of the repository in Dependency-Track:

| State     | Description                                                                            | When                                                                              |
|-----------|----------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------|
| `pending` | `SBOM creation running, vulnerability status unknown`                                  | The analysis starts                                                               |
| `success` | `No vulnerabilities of severity high or above (0 critical, 0 high, 3 medium, 4 low)`   | There are no vulnerabilities at or above the threshold                            |
| `failure` | `Vulnerabilities of severity high or above found: 1 critical, 2 high, 0 medium, 3 low` | There are vulnerabilities at or above the threshold                               |
| `failure` | `Analysis failed, vulnerability status unknown`                                        | The SBOM creation or the vulnerability analysis failed                            |
| `failure` | `Analysis timed out, vulnerability status unknown`                                     | The analysis took longer than `APP_ANALYSIS_TIMEOUT`                              |

The threshold is set globally via `APP_COMMIT_STATUS_VULNERABILITY_THRESHOLD` (`app.commit_status.vulnerability_threshold`): `critical`, `high` (default), `medium` or `low` is the lowest severity making the status fail. With `none`, pull-requests don't get a commit status at all, and neither do they if pull-requests aren't analysed (`APP_PULL_REQUESTS_ENABLED`) or `APP_COMMIT_STATUS_WRITE_ENABLED` is off.

The vulnerabilities are those the analyzer of `APP_PULL_REQUESTS_ANALYZER` (`depscan` by default) publishes, so they are the ones shown in the pull-request comment; Dependency-Track may know more or other ones. They are counted per severity (critical, high, medium, low), findings without a severity (*unspecified*) never reach the threshold. For that, the report templates (`depscan.j2`, `grype.tmpl`) state the numbers in an invisible comment at the start of the report of each project, `<!-- technolinator-severities critical=1 high=2 medium=3 low=4 unspecified=5 -->`. **Custom templates need to do so as well**, otherwise the vulnerability status is *unknown* as soon as there are findings.

## Environment of cdxgen

cdxgen and the build tools it starts (Maven, Gradle, npm, ...) run on the code of the analysed repository, so they don't get Technolinators whole environment: secrets like the GitHub app key or the Dependency-Track api key are not handed down.
They get

* the variables Technolinator sets for cdxgen itself (`GITHUB_TOKEN`, `MVN_ARGS`, `GRADLE_ARGS`, `JAVA_HOME`, ...),
* the `env` entries of the [repository config](Repository_Config.md), with `${VAR}` substitutions resolved from the variables allowed by `ALLOWED_ENV_SUBSTITUTIONS`,
* a minimal set of inherited variables the tools need to work: `PATH`, `HOME`, `USER`, `LANG`/`LC_*`, `TZ`, temp dirs, the tool locations (`JAVA*_HOME`, `MAVEN_*`, `GRADLE_*`, `NODE_HOME`, `GO*`, `SDK_DIR`, ...), proxy and CA settings (`HTTP(S)_PROXY`, `NO_PROXY`, `SSL_CERT_*`), `GIT_ASKPASS` and `CDXGEN_*`,
* everything listed in `APP_CDXGEN_INHERITED_ENV` (comma separated names, a trailing `*` makes a prefix), e.g. `GITHUB_USER` if a build reads it.

Builds needing further values should get them via the `env` of the repository config.

The `prepare` script of a repository (see [repository config](Repository_Config.md#prepare)) gets the same environment, except for the `GITHUB_TOKEN`, the variables listed in `SENSITIVE_ENV_VARS`, and inherited variables whose names look like secrets (containing `TOKEN`, `SECRET`, `PASSWORD`, `PASSWD`, `PASSPHRASE`, `APIKEY`, `API_KEY`, `PRIVATE_KEY`, `ACCESS_KEY` or `CREDENTIAL`, case-insensitive). Variables explicitly configured by the repository via `env` are kept. `HOME`, `TMPDIR` and the XDG directories point to a private scratch directory of the analysis. The script runs without a sandbox, with the permissions of Technolinator.

## Failure reporting

If an analysis fails for a reason the repository owners can act on (e.g. a broken build the SBOM generator can't cope with, an unresolvable dependency, a repeated timeout), Technolinator tells them on GitHub:

* **Default branch:** a single issue labelled `technolinator` acts as status dashboard. Its description contains the failing tool, a summary and the (secret-redacted) tool log, e.g. of cdxgen.
  * Only one status issue is open per repository. While the analysis keeps failing, the issue is updated instead of creating new ones.
  * Once the analysis of the default branch succeeds again, the issue is commented and closed. The next failure creates a new issue.
  * Closing the issue manually **mutes** the reporting for the repository until the next successful analysis of the default branch.
  * The issue is assigned to the users listed in the repository's `CODEOWNERS` (individual users only, teams can't be assigned).
  * Repositories with disabled issues are skipped.
* **Pull-requests:** the status is put into the one Technolinator comment of the pull-request, which is updated with every attempt. It is merged with the vulnerability report, so there is still only one comment. If the analysis succeeds later on, the comment is updated with a short note and stays. Logs are omitted for pull-requests from forks, unless `APP_FAILURE_REPORTING_INCLUDE_LOG_ON_FORK_PRS` is set.

Repositories analysed as several projects (see `projects` in the [repository config](Repository_Config.md)) fail partially: the report names each failing project and its folder (`Module`), and states how many of the projects were analysed successfully. Only the failing projects are affected and shown in detail.

The tool log is condensed before it is shown: cdxgen's environment audit tables and long dependency trees (printed by build tools in debug mode) are cut out, so the actual error stays visible.

Failures which the repository can't fix (checkout failures, Dependency-Track unavailable, unexpected errors) are not reported but only logged and counted in the metrics `analysis_failures`, `failure_report_actions` and `failure_report_errors`.

The feature is `on` by default and requires the GitHub App permissions *Issues: Read and write* and *Pull requests: Read and write*, see [Adoption](Adoption.md#github-app-requirements). Without the issue permission, status issues can't be written (the errors are logged and counted in `failure_report_errors`); set `APP_FAILURE_REPORTING_MODE=dry-run` to only log what would be reported, or `off` to disable it. Repositories can opt out in their [repository config](Repository_Config.md) via `reporting`.
