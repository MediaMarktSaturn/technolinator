# Technolinator

![dependencies](https://dtrack.mmst.eu/api/v1/badge/vulns/project/technolinator/main) ![policies](https://dtrack.mmst.eu/api/v1/badge/violations/project/technolinator/main)
[![Quality Gate Status](https://sonarqube.cloud.mmst.eu/api/project_badges/measure?project=technolinator%3Amain&metric=alert_status&token=squ_c20d5a134cfb4e85c6046de00451b6f4d21ee225)](https://sonarqube.cloud.mmst.eu/dashboard?id=technolinator%3Amain) [![Maintainability Rating](https://sonarqube.cloud.mmst.eu/api/project_badges/measure?project=technolinator%3Amain&metric=sqale_rating&token=squ_c20d5a134cfb4e85c6046de00451b6f4d21ee225)](https://sonarqube.cloud.mmst.eu/dashboard?id=technolinator%3Amain) [![Reliability Rating](https://sonarqube.cloud.mmst.eu/api/project_badges/measure?project=technolinator%3Amain&metric=reliability_rating&token=squ_c20d5a134cfb4e85c6046de00451b6f4d21ee225)](https://sonarqube.cloud.mmst.eu/dashboard?id=technolinator%3Amain) [![Security Rating](https://sonarqube.cloud.mmst.eu/api/project_badges/measure?project=technolinator%3Amain&metric=security_rating&token=squ_c20d5a134cfb4e85c6046de00451b6f4d21ee225)](https://sonarqube.cloud.mmst.eu/dashboard?id=technolinator%3Amain)

The MediaMarktSaturn GitHub Bot.

## Functionality

* OnPush to default branch:
  * Create and upload sbom

## Runtime

ENV configuration:

| Parameter                         | Default                | Description                                                             |
|-----------------------------------|------------------------|-------------------------------------------------------------------------|
| QUARKUS_GITHUB_APP_APP_ID         |                        | Created during app creation on GitHub                                   |
| QUARKUS_GITHUB_APP_WEBHOOK_SECRET |                        | Created during app creation on GitHub                                   |
| QUARKUS_GITHUB_APP_PRIVATE_KEY    |                        | Created during app creation on GitHub                                   |
| GITHUB_TOKEN                      |                        | Optional. Raises GH api quota for cdxgen and enables `go mod` projects  |
| DTRACK_APIKEY                     |                        | API key to access Dependency-Track                                      |
| ARTIFACTORY_USER                  |                        | User for accessing internal repos                                       |
| ARTIFACTORY_PASSWORD              |                        | PW for accessing internal repos                                         |
| DTRACK_URL                        | https://dtrack.mmst.eu | Baseurl of Dependency-Track                                             |
| CDXGEN_FETCH_LICENSE              | true                   | see [cdxgen](https://github.com/AppThreat/cdxgen#environment-variables) |
| CDXGEN_USE_GOSUM                  | true                   | see [cdxgen](https://github.com/AppThreat/cdxgen#environment-variables) |
| ANALYSIS_RECURSIVE_DEFAULT        | true                   | default value for the `analysis.recursvie` config                       |

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
    # whether cdxgen should scan for projects recursively in 'location' or only 'location' itself; default: false
    recursive: false
```

The configuration file is optional and only necessary to override default behaviour.
