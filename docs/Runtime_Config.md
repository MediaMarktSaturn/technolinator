# Runtime Configuration

Technolinator is available as container image: `ghcr.io/mediamarktsaturn/technolinator:VERSION` ![GitHub release (latest SemVer)](https://img.shields.io/github/v/release/MediaMarktSaturn/technolinator?label=latest%20version&sort=semver&style=flat-square)
There is in addition a container image tagged with `fat-VERSION` containing even or SDKs (like Swift).

You can run it by providing the minimal configuration values as listed below. Please have a look to the [adoption doc](./Adoption.md) as well to fine tune the image to your needs.

Technolinator is configured via the following parameter which can either be provided via ENV, or be put in a `.env` file in the apps working directory.

| Parameter                               | Default                                       | Description                                                                                                                                |
|-----------------------------------------|-----------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| PORT                                    | 8080                                          | Http port to listen to for GitHub Webhook events                                                                                           |
| QUARKUS_GITHUB_APP_APP_ID               |                                               | Created during app creation on GitHub                                                                                                      |
| QUARKUS_GITHUB_APP_WEBHOOK_SECRET       |                                               | Created during app creation on GitHub                                                                                                      |
| QUARKUS_GITHUB_APP_PRIVATE_KEY          |                                               | Created during app creation on GitHub                                                                                                      |
| GITHUB_TOKEN                            |                                               | Optional. Raises GH api quota for cdxgen and enables `go mod` projects                                                                     |
| DTRACK_APIKEY                           |                                               | API key to access Dependency-Track                                                                                                         |
| DTRACK_URL                              |                                               | Baseurl of Dependency-Track                                                                                                                |
| CDXGEN_USE_GOSUM                        | false                                         | see [cdxgen](https://github.com/CycloneDX/cdxgen#environment-variables)                                                                    |
| CDXGEN_REQUIRED_SCOPE_ONLY_DEFAULT      | false                                         | Only include _required_ scope to created BOM (exclude test scope)                                                                          |
| CDXGEN_EVIDENCE_DEFAULT                 | false                                         | Create sbom with evidence (slows down the process)                                                                                         |
| CDXGEN_FORMULATION_DEFAULT              | false                                         | Generate formulation section using git metadata.                                                                                           |
| ANALYSIS_RECURSIVE_DEFAULT              | true                                          | default value for the `analysis.recursvie` config                                                                                          |
| APP_CLEAN_WRAPPER_SCRIPTS               | false                                         | Remove wrapper scripts like gradlew or mvnw for not downloading these tools                                                                |
| APP_ANALYSIS_TIMEOUT                    | 60M                                           | Maximal duration of an analysis before getting aborted                                                                                     |
| APP_ENABLED_REPOS                       |                                               | Comma separated list of repo names that should be analyzed; all if empty                                                                   |
| APP_PROCESS_LOGLEVEL                    | INFO                                          | Log config for OS commands like 'cdxgen', set to 'DEBUG' to see its output⚠                                                                |
| SENSITIVE_ENV_VARS                      | see [Dockerfile](/src/main/docker/Dockerfile) | Comma separated list of env var names, that must not be logged                                                                             |
| ALLOWED_ENV_SUBSTITUTIONS               | see [Dockerfile](/src/main/docker/Dockerfile) | Comma separated list of env var names, that can be used in repo config                                                                     |
| GRYPE_TEMPLATE                          | see [Dockerfile](/src/main/docker/Dockerfile) | Template to be used by grype for vulnerability reports in pull-requests                                                                    |
| DEPSCAN_TEMPLATE                        | see [Dockerfile](/src/main/docker/Dockerfile) | Template to be used by depscan for vulnerability reports in pull-requests                                                                  |
| APP_PULL_REQUESTS_IGNORE_BOTS           | true                                          | Whether pull-requests created by bots should be ignored                                                                                    |
| APP_PULL_REQUESTS_ENABLED               | true                                          | Whether pull-request commenting should be enabled                                                                                          |
| APP_PULL_REQUESTS_ANALYZER              | `depscan`                                     | Which analyzer and report creator to use in pull-request; Options: grype, depscan                                                          |
| APP_PUBLISH_REPO_METRICS                | true                                          | Publish metrics about the analyzed repositories like contained languages (acc. to GitHub API)                                              |
| APP_PULL_REQUESTS_CDXGEN_FETCH_LICENSES | false                                         | Whether license information should be included in pull-request created sboms                                                               |
| APP_ANALYSIS_CDXGEN_FETCH_LICENSES      | true                                          | Wheter license information should be included in default-branch analysis                                                                   |
| GRYPE_CONFIG                            |                                               | Path to a [grype configuration](https://github.com/anchore/grype#configuration) file used in PR analysis                                   |
| APP_USE_PENDING_COMMIT_STATUS           | false                                         | Wehther a PENDING commit status should be announced when analysing the default branch                                                      |
| APP_COMMIT_STATUS_WRITE_ENABLED         | true                                          | Whether commit status in the repository should be updated (the app requires commit writes permission in this case)                         |
| APP_ALWAYS_USE_VERSION_OR_COMMIT_HASH   | false                                         | Always populate the `version` field in Dependency Track with either a tag name or commit hash instead of the branch. :warning: very noisy! |
| CDXGEN_DEBUG                            | false                                         | Set to `true` for debug output of cdxgen command                                                                                           |

## Observability

Technolinator's process can best be followed using its log. Every push event received is noted, and the output of cdxgen is logged as well.
In addition, Technolinator provides Prometheus metrics about push events and analysis results.

There's a Grafana dashboard available in [here](/_dashboards), that visualizes these metrics.
