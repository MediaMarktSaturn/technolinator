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

## Repository specific configuration

Technolinator respects a configuration file in the default branch of repositories:
**`.github/technolinator.yml`**

with the following options:
```yaml
# whether Technolinator does analysis at all; default: true
enable: true
project:
    # desired name of the project in dependency-track; default depends on build system, for maven it's: "groupId:artifactId"
    name: awesomeProject
analysis:
    # the location targeted by cdxgen; default: repository root
    location: projectLocation
    # whether cdxgen should scan for projects recursively in 'location' or only 'location' itself
    recursive: true
```

The configuration file is optional and only necessary to override default behaviour.
