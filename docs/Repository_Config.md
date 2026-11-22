# Repository specific configuration

Technolinator respects a configuration file in repositories:
**`.github/technolinator.yml`**

It is read from the default branch, except for pull-requests, which use the file of their own head commit.
This way, configuration changes can be tried out in a pull-request before they are merged. If the file of the head commit can't be read, the one of the default branch applies.

with the following options:
```yaml
# whether Technolinator does analysis at all; default: true
enable: true
# whether Technolinator shall comment vulnerability reports to pull-requests; default: true
enablePullRequestReport: true
reporting:
    # whether a failing analysis of the default branch is reported in a status issue; default: true
    issues: true
    # whether a failing analysis of a pull-request is reported in its Technolinator comment; default: true
    pullRequestComments: true
project:
    # desired name of the project in dependency-track; default is the GitHub repository name
    name: awesomeProject
analysis:
    # the location targeted by cdxgen, relative to and within the repository; default: repository root
    location: projectLocation
    # whether cdxgen should scan for projects recursively in 'location' or only 'location' itself; default: true
    recursive: false
    # include only 'required' scoped dependencies to created BOM
    requiredScopeOnly: false
    # create sbom with evidence (slows down the process)
    evidence: false
    # Generate formulation section using git metadata.
    formulation: false
    # folders and files within 'analysis.location' to remove before the analysis (e.g. non-production stuff, samples, test fixtures);
    # must stay inside the location: paths containing '..' or starting with '/', '~' or '$' fail the analysis
    excludes:
        - subfolder1
        - just/another/path/below/projectLocation
gradle:
    # list of arguments to be provided to cdxgen as GRADLE_ARGS; env vars notated with ${ENV_VAR} will be resolved (see below)
    args:
        - -PyourProprietary=property
maven:
    # list of arguments to be provided to cdxgen as MVN_ARGS (after '-B -ntp'); env vars notated with ${ENV_VAR} will be resolved (see below)
    args:
        - -Pall
env:
    # additional env parameter for cdxgen, e.g. settings of build tools; env vars notated with ${ENV_VAR} will be resolved (see below)
    THIS_IS: just another value
jdk:
    # select JDK version used by cdxgen on JVM based projects (see below)
    version: 21
flutter:
    # Opt in to Flutter preparation and select an installed stable SDK version.
    version: 3.44.1
    # Paths to Flutter packages relative to analysis.location; defaults to ["."].
    directories:
        - .

# to split up repositories in multiple dependency-track projects, you can recursively configure subprojects.
# structure is the same like for the single-project config, each distinct project will result in one dependency-track project.
# subprojects inherit the settings of their parents (and the root project): 'location' and 'name' are appended, 'args' are added, other values are overridden
# minimal required structure is shown below
projects: []
# - project:
#     name: first-sub-project
#   analysis:
#     location: projects/sub_project_1
```

The configuration file is optional and only necessary to override default behavior.

## Fixing a failed SBOM creation

If the SBOM can't be created, Technolinator tells so in a status issue (default branch) or its pull-request comment, including the log of the failing tool.
Technolinator calls [cdxgen](https://github.com/CycloneDX/cdxgen), which runs the build tools of your repository (Maven, Gradle, npm, ...) on a plain checkout:
**no credentials, only public package repositories, and a JDK which may differ from yours.**
What works on a clean machine in that setup works for Technolinator.

| What the log tells | What helps |
|---|---|
| `Unsupported class file major version`, `invalid target release`, `requires Java 21` | Select the JDK of your build via `jdk.version` |
| Dependencies, plugins or parent POMs can't be resolved; `401`/`403` | Make them available publicly. Otherwise exclude the module via `analysis.excludes`, or ask the Technolinator operators to provide a central repository for `maven.args`/`gradle.args`/`env` via `${ENV_VAR}` |
| The build needs a profile or property that enables modules | `maven.args` (e.g. `-Pall`) or `gradle.args` (e.g. `-PmyProperty=value`) |
| Failure in samples, test fixtures, documentation or other non-production folders | `analysis.excludes` |
| Only one module of a monorepo fails | Exclude it, or split the repository into `projects`, each with its own `analysis.location`: failures are then reported per project |
| Out of memory, timeouts, very large repository | Narrow the scope via `analysis.location` and `analysis.excludes`, or split into `projects`. For Gradle, `env: GRADLE_MULTI_THREADED: "false"` reduces memory demand |
| npm, pnpm, yarn, pip, go or cargo can't install | Commit a lock file that is consistent with the manifest, so a frozen install works on a clean checkout |

To reproduce a failure, clone the repository fresh and run the `cdxgen` command shown in the report, with the JDK of `jdk.version`.
A single failing step is enough to fail the analysis of that project; the next successful analysis closes the status issue and updates the pull-request comment automatically.
Only as a last resort, `enable: false` switches Technolinator off for the repository.

## ${Parameter}

ENV available to the Technolinators runtime, and listed in ENV var `ALLOWED_ENV_SUBSTITUTIONS` can be referred to from the repository configuration.
This is an effective way of central configuration for things like artifact repository urls and its access credentials.
Please see the [adoption documentation](Adoption.md) for details.

### `jdk.version`

The JDK versions available for selection need to be provided and configured to the Technolinator runtime.
Please see the [adoption documentation](Adoption.md) for details, in the default runtime container, there's JDK 25 (default), JDK 21 and JDK 17 built in.
Requesting a version that isn't available fails the analysis with an explanatory error instead of silently falling back.

### Flutter projects

Flutter preparation is opt-in. Set `flutter.version` to an installed SDK version and, for a monorepo, list each Flutter package directory under `flutter.directories`. These paths are relative to `analysis.location`; they must remain inside it and each must contain a `pubspec.yaml`. Use separate `projects` if different packages require different Flutter versions. A child project's `flutter` configuration replaces its parent's.

Before cdxgen runs, Technolinator runs `flutter --suppress-analytics pub get` in each directory and configures `flutter.sdk` in its `android/local.properties` when an Android directory exists. Other existing properties are retained. The selected Flutter SDK is also available to Gradle via `FLUTTER_ROOT` and `FLUTTER_SDK`, and the Android SDK via `ANDROID_HOME` and `ANDROID_SDK_ROOT`. The checkout must be able to resolve all dependencies from the configured package repositories; a failed preparation or cdxgen command fails the analysis, even if a partial SBOM exists. Android modules and their dependencies are not excluded.

`flutter pub get` can update `pubspec.lock` when the selected SDK is incompatible with locked versions. The SBOM then reflects the newly resolved dependencies, so use a compatible SDK and commit a compatible lockfile for reproducible results.

When any configured project uses Flutter, analyses within the same checkout run sequentially to avoid conflicts over generated files. The original `android/local.properties`, if present, is restored after each analysis.

The standard image provides Flutter 3.44.1 and Android SDK platform 36, build-tools 36.0.0, and NDK 28.2.13676358. Other Flutter versions or Android SDK packages must be provisioned by the operator; selecting an unavailable Flutter version fails rather than falling back. Select an appropriate `jdk.version` for the project's Android Gradle plugin. The Flutter SDK is copied to a temporary writable directory for each analysis, requiring several gigabytes of free temporary storage and additional startup time. See [Adoption](Adoption.md#flutter-and-android-sdks) for provisioning and isolation details.
