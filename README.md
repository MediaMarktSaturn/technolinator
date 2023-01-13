# Technolinator

The MediaMarktSaturn GitHub Bot.

## Functionality

* OnPush to default branch:
  * Create and upload sbom

## Runtime

ENV configuration:

| Parameter                         | Default                |
|-----------------------------------|------------------------|
| QUARKUS_GITHUB_APP_APP_ID         |                        |
| QUARKUS_GITHUB_APP_WEBHOOK_SECRET |                        |
| QUARKUS_GITHUB_APP_PRIVATE_KEY    |                        |
| GITHUB_TOKEN                      |                        |
| DTRACK_APIKEY                     |                        |
| ARTIFACTORY_USER                  |                        |
| ARTIFACTORY_PASSWORD              |                        |
| DTRACK_URL                        | https://dtrack.mmst.eu |
| CDXGEN_FETCH_LICENSE              | true                   |
| CDXGEN_USE_GOSUM                  | true                   |
