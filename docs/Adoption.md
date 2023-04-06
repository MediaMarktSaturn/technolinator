# Adopting for private use

For using Technolinator inside your organization with private artifact repositories we recommend to create a derived container image containing needed configuration like Maven or Gradle setting files and ENV.

This could look like:

```dockerfile
FROM ghcr.io/mediamarktsaturn/technolinator:1.29.5

# app runs as user 201 in group 101
COPY --chown=root:root --chmod=a-w assets/settings.xml ${MAVEN_HOME}/conf/settings.xml

ENV SENSITIVE_ENV_VARS="${SENSITIVE_ENV_VARS},ARTIFACTORY_USER,ARTIFACTORY_PASSWORD" \
    ARTIFACTORY_URL="https://cloud.artifactory.com/artifactory" \
    DTRACK_URL="https://dependency-track.awesome.org"
```

## Config of jdk.version

Different JDK installations can be provided to Technolinator by its own env.
Env vars of pattern `JAVA\d+_HOME` will be detected, and the `\d+` values can be used for `jdk.version`.

## Config of env vars available for use in repo specific configuration

Any environment variable backed into the runtime can be referred to from the repository config.
Please mind to add sensitive env names (like GitHub token or artifact repository secrets) to the `SENSITIVE_ENV_VARS` to not having them outputted via logging, see the [Dockerfile](src/main/docker/Dockerfile) for the defaults.