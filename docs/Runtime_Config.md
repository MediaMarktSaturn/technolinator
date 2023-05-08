# Runtime Configuration

Technolinator is configured via the following parameter which can either be provided via ENV, or be put in a `.env` file in the apps working directory.

| Parameter                               | Default                                      | Description                                                                                                               |
|-----------------------------------------|----------------------------------------------|---------------------------------------------------------------------------------------------------------------------------|
| PORT                                    | 8080                                         | Http port to listen to for GitHub Webhook events                                                                          |
| QUARKUS_GITHUB_APP_APP_ID               |                                              | Created during app creation on GitHub                                                                                     |
| QUARKUS_GITHUB_APP_WEBHOOK_SECRET       |                                              | Created during app creation on GitHub                                                                                     |
| QUARKUS_GITHUB_APP_PRIVATE_KEY          |                                              | Created during app creation on GitHub                                                                                     |
| GITHUB_TOKEN                            |                                              | Optional. Raises GH api quota for cdxgen and enables `go mod` projects                                                    |
| DTRACK_APIKEY                           |                                              | API key to access Dependency-Track                                                                                        |
| DTRACK_URL                              |                                              | Baseurl of Dependency-Track                                                                                               |
| CDXGEN_USE_GOSUM                        | false                                        | see [cdxgen](https://github.com/CycloneDX/cdxgen#environment-variables)                                                   |
| CDXGEN_MAVEN_INCLUDE_TEST_SCOPE         | false                                        | Whether test scoped dependencies for maven projects should be respected                                                   |
| ANALYSIS_RECURSIVE_DEFAULT              | true                                         | default value for the `analysis.recursvie` config                                                                         |
| APP_CLEAN_WRAPPER_SCRIPTS               | false                                        | Remove wrapper scripts like gradlew or mvnw for not downloading these tools                                               |
| APP_ANALYSIS_TIMEOUT                    | 60M                                          | Maximal duration of an analysis before getting aborted                                                                    |
| APP_ENABLED_REPOS                       |                                              | Comma separated list of repo names that should be analyzed; all if empty                                                  |
| APP_PROCESS_LOGLEVEL                    | INFO                                         | Log config for OS commands like 'cdxgen', set to 'DEBUG' to see its output                                                |
| SENSITIVE_ENV_VARS                      | see [Dockerfile](src/main/docker/Dockerfile) | Comma separated list of env var names, that must not be logged                                                            |
| ALLOWED_ENV_SUBSTITUTIONS               | see [Dockerfile](src/main/docker/Dockerfile) | Comma separated list of env var names, that can be used in repo config                                                    |
| GRYPE_TEMPLATE                          | see [Dockerfile](src/main/docker/Dockerfile) | Template to be used by grype for vulnerability reports in pull-requests                                                   |
| APP_PULL_REQUESTS_IGNORE_BOTS           | true                                         | Whether pull-requests created by bots should be ignored                                                                   |
| APP_PULL_REQUESTS_ENABLED               | true                                         | Whether pull-request commenting should be enabled                                                                         |
| APP_PULL_REQUESTS_CONCURRENCY_LIMIT     | 3                                            | How many pull-requests of the same repository should be analyzed in parallel, exceeding ones are ignored. '0' = unlimited |
| APP_PUBLISH_REPO_METRICS                | true                                         | Publish metrics about the analyzed repositories like contained languages (acc. to GitHub API)                             |
| APP_PULL_REQUESTS_CDXGEN_FETCH_LICENSES | false                                        | Whether license information should be included in pull-request created sboms                                              |
| APP_ANALYSIS_CDXGEN_FETCH_LICENSES      | true                                         | Wheter license information should be included in default-branch analysis                                                  |
| GRYPE_CONFIG                            |                                              | Path to a [grype configuration](https://github.com/anchore/grype#configuration) file used in PR analysis                  |

## Observability

Technolinators process can best be followed using its log. Every push event received is noted, and the output of cdxgen is logged as well.
In addition, Technolinator provides Prometheus metrics about push events and analysis results.

There's a Grafana dashboard available in [here](_dashboards), that visualizes these metrics.