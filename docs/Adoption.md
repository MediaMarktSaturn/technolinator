# Adopting for private use

For using Technolinator inside your organization with private artifact repositories we recommend to create a derived container image containing needed configuration like Maven or Gradle setting files and ENV.

This could look like in the following example:

![GitHub release (latest SemVer)](https://img.shields.io/github/v/release/MediaMarktSaturn/technolinator?label=latest%20version&sort=semver&style=flat-square)

```dockerfile
FROM ghcr.io/mediamarktsaturn/technolinator:VERSION

# app runs as user 201 in group 101, files should be read-only to it
COPY --chown=root:root --chmod=a-w assets/settings.xml ${MAVEN_HOME}/conf/settings.xml
# if you like to have a global config for grype. repository local `.grype.yaml` are respected as well, if not set via GRYPE_CONFIG env
COPY --chown=root:root --chmod=a-w assets/grype.yml $APP_DIR/grype.yml

ENV SENSITIVE_ENV_VARS="${SENSITIVE_ENV_VARS},ARTIFACTORY_USER,ARTIFACTORY_PASSWORD" \
    ALLOWED_ENV_SUBSTITUTIONS="ARTIFACTORY_USER,ARTIFACTORY_PASSWORD,ARTIFACTORY_URL" \
    ARTIFACTORY_URL="https://cloud.artifactory.com/artifactory" \
    DTRACK_URL="https://dependency-track.awesome.org" \
    GRYPE_CONFIG="$APP_DIR/grype.yml"
```

## Config of jdk.version

Different JDK installations can be provided to Technolinator by its own env.
Env vars of pattern `JAVA\d+_HOME` will be detected, and the `\d+` values can be used for `jdk.version`.

## Config of env vars available for use in repo specific configuration

Any environment variable backed into the runtime can be referred to from the repository config.
Please mind to add sensitive env names (like GitHub token or artifact repository secrets) to the `SENSITIVE_ENV_VARS` to not having them outputted via logging, see the [Dockerfile](src/main/docker/Dockerfile) for the defaults.

## GitHub App requirements

Technolinator needs to read repository contents by notification onPull and onPullRequest.
Therefor the following settings are required for the GitHub app installation:

### Permissions

* Commit statuses: Read and Write
* Contents: Read only
* Metadata: Read only
* Pull requests: Read and Write

### Event subscriptions

* Pull Request
* Push
