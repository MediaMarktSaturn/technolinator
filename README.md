# Technolinator

![dependencies](https://dtrack.mmst.eu/api/v1/badge/vulns/project/technolinator/main) ![policies](https://dtrack.mmst.eu/api/v1/badge/violations/project/technolinator/main)
[![Quality Gate Status](https://sonarqube.cloud.mmst.eu/api/project_badges/measure?project=technolinator%3Amain&metric=alert_status&token=squ_c20d5a134cfb4e85c6046de00451b6f4d21ee225)](https://sonarqube.cloud.mmst.eu/dashboard?id=technolinator%3Amain) [![Maintainability Rating](https://sonarqube.cloud.mmst.eu/api/project_badges/measure?project=technolinator%3Amain&metric=sqale_rating&token=squ_c20d5a134cfb4e85c6046de00451b6f4d21ee225)](https://sonarqube.cloud.mmst.eu/dashboard?id=technolinator%3Amain) [![Reliability Rating](https://sonarqube.cloud.mmst.eu/api/project_badges/measure?project=technolinator%3Amain&metric=reliability_rating&token=squ_c20d5a134cfb4e85c6046de00451b6f4d21ee225)](https://sonarqube.cloud.mmst.eu/dashboard?id=technolinator%3Amain) [![Security Rating](https://sonarqube.cloud.mmst.eu/api/project_badges/measure?project=technolinator%3Amain&metric=security_rating&token=squ_c20d5a134cfb4e85c6046de00451b6f4d21ee225)](https://sonarqube.cloud.mmst.eu/dashboard?id=technolinator%3Amain)

The MediaMarktSaturn GitHub app for SBOM creation and upload to Dependency-Track.

It wraps around [cdxgen](https://github.com/CycloneDX/cdxgen) which covers many programming languages and build systems.
It's build using [Quarkus](https://quarkus.io/) and handles GitHub webhooks by the [Quarkus GitHub App](https://quarkiverse.github.io/quarkiverse-docs/quarkus-github-app/dev/index.html).

## Overview

![](docs/img/overview.png)

## Documentation

* Using Technolinator
  * [Repository specific configuration](docs/Repository_Config.md)
* Operating Technolinator
  * [Runtime configuration](docs/Runtime_Config.md)
  * [Deployment configuration](docs/Deployment_Config.md)
  * [Adopting to your needs](docs/Adoption.md)
* Maintaining Technolinator
  * [Project structure](docs/Project_Structure.md)
  * [Contribution](docs/Contribution.md)

---

_This repository is published under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)_

**_get to know us 👉 [https://mms.tech](https://mms.tech) 👈_**
