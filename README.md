# Technolinator

**The GitHub app for pull-request vulnerability analysis as well as SBOM creation and upload to Dependency-Track.**

![dependencies](https://dtrack.mmst.eu/api/v1/badge/vulns/project/technolinator/main) ![policies](https://dtrack.mmst.eu/api/v1/badge/violations/project/technolinator/main)
[![Quality Gate Status](https://sonarqube.cloud.mmst.eu/api/project_badges/measure?project=technolinator%3Amain&metric=alert_status&token=squ_c20d5a134cfb4e85c6046de00451b6f4d21ee225)](https://sonarqube.cloud.mmst.eu/dashboard?id=technolinator%3Amain) [![Maintainability Rating](https://sonarqube.cloud.mmst.eu/api/project_badges/measure?project=technolinator%3Amain&metric=sqale_rating&token=squ_c20d5a134cfb4e85c6046de00451b6f4d21ee225)](https://sonarqube.cloud.mmst.eu/dashboard?id=technolinator%3Amain) [![Reliability Rating](https://sonarqube.cloud.mmst.eu/api/project_badges/measure?project=technolinator%3Amain&metric=reliability_rating&token=squ_c20d5a134cfb4e85c6046de00451b6f4d21ee225)](https://sonarqube.cloud.mmst.eu/dashboard?id=technolinator%3Amain) [![Security Rating](https://sonarqube.cloud.mmst.eu/api/project_badges/measure?project=technolinator%3Amain&metric=security_rating&token=squ_c20d5a134cfb4e85c6046de00451b6f4d21ee225)](https://sonarqube.cloud.mmst.eu/dashboard?id=technolinator%3Amain)

🚢 ![GitHub Release](https://img.shields.io/github/v/release/MediaMarktSaturn/technolinator?sort=semver&style=flat-square&label=ghcr.io%2Fmediamarktsaturn%2Ftechnolinator%3AVERSION)

It wraps around
* [![](https://img.shields.io/badge/dynamic/yaml?url=https%3A%2F%2Fraw.githubusercontent.com%2FMediaMarktSaturn%2Ftechnolinator%2Fmain%2F.github%2Fworkflows%2Fci.yml&query=%24.env.CDXGEN_VERSION&style=flat-square&label=cdxgen)](https://github.com/CycloneDX/cdxgen) which covers many programming languages and build systems for SBOM creation
* [![](https://img.shields.io/badge/dynamic/yaml?url=https%3A%2F%2Fraw.githubusercontent.com%2FMediaMarktSaturn%2Ftechnolinator%2Fmain%2F.github%2Fworkflows%2Fci.yml&query=%24.env.SBOMQS_VERSION&style=flat-square&label=sbomqs)](https://github.com/interlynk-io/sbomqs) for rating the quality of a sbom
* [![](https://img.shields.io/badge/dynamic/yaml?url=https%3A%2F%2Fraw.githubusercontent.com%2FMediaMarktSaturn%2Ftechnolinator%2Fmain%2F.github%2Fworkflows%2Fci.yml&query=%24.env.DEPSCAN_VERSION&style=flat-square&label=depscan)](https://github.com/owasp-dep-scan/dep-scan) for creation of vulnerability reports in pull-requests
* or optional [![](https://img.shields.io/badge/dynamic/yaml?url=https%3A%2F%2Fraw.githubusercontent.com%2FMediaMarktSaturn%2Ftechnolinator%2Fmain%2F.github%2Fworkflows%2Fci.yml&query=%24.env.GRYPE_VERSION&style=flat-square&label=grype)](https://github.com/anchore/grype) as alternative to depscan

It's built using [![](https://img.shields.io/badge/dynamic/xml?url=https%3A%2F%2Fraw.githubusercontent.com%2FMediaMarktSaturn%2Ftechnolinator%2Fmain%2Fpom.xml&query=%2F%2F*%5Blocal-name()%20%3D%20'quarkus.platform.version'%5D%2Ftext()&style=flat-square&label=Quarkus)](https://quarkus.io/) with GitHub integration handled by [![](https://img.shields.io/badge/dynamic/xml?url=https%3A%2F%2Fraw.githubusercontent.com%2FMediaMarktSaturn%2Ftechnolinator%2Fmain%2Fpom.xml&query=%2F%2F*%5Blocal-name()%20%3D%20'quarkus-github-app.version'%5D%2Ftext()&style=flat-square&label=Quarkiverse%20GitHub%20App)](https://quarkiverse.github.io/quarkiverse-docs/quarkus-github-app/dev/index.html).

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
