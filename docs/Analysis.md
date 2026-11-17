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

## Failure reporting

If an analysis fails for a reason the repository owners can act on (e.g. a broken build the SBOM generator can't cope with, an unresolvable dependency, a repeated timeout), Technolinator tells them on GitHub:

* **Default branch:** a single issue labelled `technolinator` acts as status dashboard. Its description contains the failing tool, a summary and the (secret-redacted) tool log, e.g. of cdxgen.
  * Only one status issue is open per repository. While the analysis keeps failing, the issue is updated instead of creating new ones.
  * Once the analysis of the default branch succeeds again, the issue is commented and closed. The next failure creates a new issue.
  * Closing the issue manually **mutes** the reporting for the repository until the next successful analysis of the default branch.
  * The issue is assigned to the users listed in the repository's `CODEOWNERS` (individual users only, teams can't be assigned).
  * Repositories with disabled issues are skipped.
* **Pull-requests:** the status is put into the one Technolinator comment of the pull-request, which is updated with every attempt. It is merged with the vulnerability report, so there is still only one comment. If the analysis succeeds later on, the comment is updated with a short note and stays. Logs are omitted for pull-requests from forks, unless `APP_FAILURE_REPORTING_INCLUDE_LOG_ON_FORK_PRS` is set.

Failures which the repository can't fix (checkout failures, Dependency-Track unavailable, unexpected errors) are not reported but only logged and counted in the metrics `analysis_failures`, `failure_report_actions` and `failure_report_errors`.

The feature is `on` by default and requires the GitHub App permissions *Issues: Read and write* and *Pull requests: Read and write*, see [Adoption](Adoption.md#github-app-requirements). Without the issue permission, status issues can't be written (the errors are logged and counted in `failure_report_errors`); set `APP_FAILURE_REPORTING_MODE=dry-run` to only log what would be reported, or `off` to disable it. Repositories can opt out in their [repository config](Repository_Config.md) via `reporting`.
