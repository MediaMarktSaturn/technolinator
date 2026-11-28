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
prepare:
    # a bash script run in 'analysis.location' ahead of cdxgen, to prepare the repository for it (see below)
    script: |
        technolinator-flutter 3.44.1 .

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

### `prepare`

Some repositories can't be analysed on a plain checkout: sources have to be generated, or dependencies have to be resolved by a tool cdxgen doesn't run on its own (e.g. `flutter pub get`).
For that, a repository can provide a `prepare.script`, which Technolinator runs ahead of cdxgen:

```yaml
prepare:
    script: |
        bash ./tools/generate-sources.sh
        mvn -B -ntp -q generate-sources
```

* It's a bash script, run as `bash -euo pipefail` in the folder to analyse (`analysis.location`), so it stops at the first failing command.
  Files of the repository are checked out without executable permission, so call your own scripts via `bash script.sh`.
* It may run everything on the `PATH` of the Technolinator image, but is expected to work in its folder only. **There is no sandbox**: the script has the permissions of Technolinator, so it is up to the repository and its reviewers to keep it to that.
  Network isolation, if required, is up to the runtime environment as well.
* It's run once for each project, see `projects` above. The `prepare` of a project replaces the one of its parent, an empty script switches an inherited one off.
  If any project of a repository has a `prepare`, the projects of that repository are analysed one after another, as their scripts work in the same checkout.
* The environment is the one of cdxgen, including your `env` entries (with `${VAR}` resolved), but without the `GITHUB_TOKEN` and the secrets of Technolinator (variables named in `SENSITIVE_ENV_VARS` or looking like secrets).
  `HOME`, `TMPDIR` and the like point to a private scratch directory, available as `$TECHNOLINATOR_SCRATCH` and removed after the analysis. Downloads into `$HOME` are therefore not cached between analyses.
* To pass settings on to cdxgen and the build tools it starts, append `NAME=value` lines to the file `$TECHNOLINATOR_ENV`, e.g. `echo "MY_GENERATED_DIR=$PWD/gen" >> "$TECHNOLINATOR_ENV"`. Values are single-line, a later assignment wins, names starting with `TECHNOLINATOR_` are reserved.
* The script must not leave symbolic links pointing outside of the repository, nor files Technolinator can't read (both fail the analysis), and a `sbom.json` it creates is removed.
* Processes the script leaves running in the background (e.g. a Gradle daemon) are killed when it ends. Run things in the foreground, e.g. `./gradlew --no-daemon`.
* If the script or cdxgen fails, the analysis of the project fails, even if cdxgen created an SBOM: it wouldn't describe the prepared repository. The failure is reported like one of cdxgen, with `prepare` as the failing tool.
* The script is **not run** for pull-requests from forks, as their code is not trusted, and if the operator switched it off (`APP_PREPARE_ENABLED`, see [runtime configuration](Runtime_Config.md)). The analysis continues without it, and a failure mentions the skipped script.

#### Flutter projects

The image provides Flutter 3.44.1 and the Android SDK (platform 36, build-tools 36.0.0, NDK 28.2.13676358), and the helper `technolinator-flutter` to use them in a `prepare` script:

```yaml
prepare:
    script: |
        technolinator-flutter 3.44.1 . packages/app
jdk:
    version: 17 # select the JDK the Android Gradle plugin of the project needs
```

`technolinator-flutter <version> [directory...]` sets up the Flutter version, runs `flutter pub get` in each directory (default `.`), and hands `FLUTTER_ROOT`, `FLUTTER_SDK` and `PUB_CACHE` over to cdxgen, so its Gradle run for the Android part of the project works.
The directories are relative to `analysis.location`, must stay inside of it, and each must contain a `pubspec.yaml`. Use separate `projects` if packages need different Flutter versions.
Selecting a version that isn't installed fails the analysis rather than falling back.

To run Flutter on your own, e.g. for code generation, use `eval "$(technolinator-flutter --setup 3.44.1)"`: `flutter` is on the `PATH` afterwards and works against the same setup:

```yaml
prepare:
    script: |
        eval "$(technolinator-flutter --setup 3.44.1)"
        flutter pub get
        dart run build_runner build
```

`flutter pub get` can update `pubspec.lock` when the selected SDK is incompatible with the locked versions. The SBOM then reflects the newly resolved dependencies, so use a compatible SDK and commit a compatible lockfile for reproducible results.
The checkout must be able to resolve all dependencies from public package repositories. Android modules and their dependencies are not excluded.
See [Adoption](Adoption.md#flutter-and-android-sdks) for providing further versions and how the SDKs are shared between analyses.
