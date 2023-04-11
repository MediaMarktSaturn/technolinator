# Runtime Configuration

Technolinator is configured via the following parameter which can either be provided via ENV, or be put in a `.env` file in the apps working directory.

| Parameter                         | Default                                      | Description                                                                 |
|-----------------------------------|----------------------------------------------|-----------------------------------------------------------------------------|
| PORT                              | 8080                                         | Http port to listen to for GitHub Webhook events                            |
| QUARKUS_GITHUB_APP_APP_ID         |                                              | Created during app creation on GitHub                                       |
| QUARKUS_GITHUB_APP_WEBHOOK_SECRET |                                              | Created during app creation on GitHub                                       |
| QUARKUS_GITHUB_APP_PRIVATE_KEY    |                                              | Created during app creation on GitHub                                       |
| GITHUB_TOKEN                      |                                              | Optional. Raises GH api quota for cdxgen and enables `go mod` projects      |
| DTRACK_APIKEY                     |                                              | API key to access Dependency-Track                                          |
| DTRACK_URL                        |                                              | Baseurl of Dependency-Track                                                 |
| CDXGEN_FETCH_LICENSE              | true                                         | see [cdxgen](https://github.com/CycloneDX/cdxgen#environment-variables)     |
| CDXGEN_USE_GOSUM                  | true                                         | see [cdxgen](https://github.com/CycloneDX/cdxgen#environment-variables)     |
| ANALYSIS_RECURSIVE_DEFAULT        | true                                         | default value for the `analysis.recursvie` config                           |
| APP_CLEAN_WRAPPER_SCRIPTS         | true                                         | Remove wrapper scripts like gradlew or mvnw for not downloading these tools |
| APP_ANALYSIS_TIMEOUT              | 30M                                          | Maximal duration of an analysis before getting aborted                      |
| APP_ENABLED_REPOS                 |                                              | Comma separated list of repo names that should be analyzed; all if empty    |
| APP_PROCESS_LOGLEVEL              | INFO                                         | Log config for OS commands like 'cdxgen', set to 'DEBUG' to see its output  |
| SENSITIVE_ENV_VARS                | see [Dockerfile](src/main/docker/Dockerfile) | Comma separated list of env var names, that must not be logged              |
| ALLOWED_ENV_SUBSTITUTIONS         | see [Dockerfile](src/main/docker/Dockerfile) | Comma separated list of env var names, that can be used in repo config      |

## Observability

Technolinators process can best be followed using its log. Every push event received is noted, and the output of cdxgen is logged as well.
In addition, Technolinator provides Prometheus metrics about push events and analysis results.

There's a Grafana dashboard available in [here](_dashboards), that visualizes these metrics.
