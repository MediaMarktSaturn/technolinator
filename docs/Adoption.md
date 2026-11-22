# Adopting for private use

For using Technolinator inside your organization with private artifact repositories we recommend to create a derived container image containing needed configuration like Maven or Gradle setting files and ENV.

This could look like in the following example:

![GitHub release (latest SemVer)](https://img.shields.io/github/v/release/MediaMarktSaturn/technolinator?label=latest%20version&sort=semver&style=flat-square)

```dockerfile
FROM ghcr.io/mediamarktsaturn/technolinator:VERSION
# or FROM ghcr.io/mediamarktsaturn/technolinator:fat-VERSION for the full-blown image

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

## Flutter and Android SDKs

The base image includes Flutter 3.44.1 in `$SDK_DIR/flutter/3.44.1` and an Android SDK at `$ANDROID_HOME` (also `$ANDROID_SDK_ROOT`). The Android SDK includes platform 36, build-tools 36.0.0, and NDK 28.2.13676358. A repository opts in using `flutter.version` and can list package directories; see [repository configuration](Repository_Config.md#flutter-projects).

Operators can add other stable Flutter SDK versions as complete, pre-cached SDKs at `$SDK_DIR/flutter/<version>` in a derived image, and install Android SDK packages required by their projects. Templates should be owned by root and not writable by the application user. Analyses get a separate writable copy of the selected Flutter SDK because Flutter updates its cache on each invocation; reserve several gigabytes of temporary storage per concurrent Flutter analysis. Android SDK packages not preinstalled in the read-only image must be added by an operator rather than downloaded into that SDK during analysis. SDK and public dependency downloads require network access, and the selected JDK must be compatible with the project's Android Gradle plugin. Installation and use of the Android SDK are subject to its licenses.

The Linux image prepares Flutter packages and Android builds; it does not provide Xcode or macOS/iOS build tooling. Dependencies that require those tools may not be resolvable in this environment.

## Config of env vars available for use in repo specific configuration

Any environment variable backed into the runtime can be referred to from the repository config.
Please mind to add sensitive env names (like GitHub token or artifact repository secrets) to the `SENSITIVE_ENV_VARS` to not having them outputted via logging, see the [Dockerfile](/src/main/docker/Dockerfile) for the defaults.

## GitHub App requirements

Technolinator needs to read repository contents by notification onPull and onPullRequest.
Therefor the following settings are required for the GitHub app installation:

### Permissions

* Commit statuses: Read and Write
* Contents: Read only
* Issues: Read and Write (status issue about failing analyses, see [failure reporting](Analysis.md#failure-reporting))
* Metadata: Read only
* Pull requests: Read and Write

### Event subscriptions

* Pull Request
* Push
* Repository
* Release (adds released tags to the Dependency-Track project)

For a step by step guide on registering and installing the app, see [Getting started](Getting_Started.md#2-register-the-github-app).
