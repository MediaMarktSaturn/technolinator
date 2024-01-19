# Repository specific configuration

Technolinator respects a configuration file in the default branch of repositories:
**`.github/technolinator.yml`**

with the following options:
```yaml
# whether Technolinator does analysis at all; default: true
enable: true
# whether Technolinator shall comment vulnerability reports to pull-requests
enablePullRequestReport: true
project:
    # desired name of the project in dependency-track; default is the GitHub repository name
    name: awesomeProject
analysis:
    # the location targeted by cdxgen; default: repository root
    location: projectLocation
    # whether cdxgen should scan for projects recursively in 'location' or only 'location' itself; default: true
    recursive: false
    # include only 'required' scoped dependencies to created BOM
    requiredScopeOnly: false
    # folders within 'analysis.location' to exclude from created sbom (e.g. non-production stuff)
    excludes:
        - subfolder1
        - just/another/path/below/projectLocation
gradle:
    # list of arguments to be provided to cdxgen as GRADLE_ARGS; env vars notated with ${ENV_VAR} will be resolved (see below)
    args:
        - -PyourProprietary=property
maven:
    # list of arguments to be provided to cdxgen as MVN_ARGS; env vars notated with ${ENV_VAR} will be resolved (see below)
    args:
        - -Pall
env:
    # additional env parameter for cdxgen; env vars notated with ${ENV_VAR} will be resolved (see below)
    THIS_IS: just another value
jdk:
    # select JDK version used by cdxgen on JVM based projects (see below)
    version: 20

# to split up repositories in multiple dependency-track projects, you can recursively configure subprojects.
# structure is the same like for the single-project config, each distinct project will result in one dependency-track project.
# subprojects inherit the settings of their parents (and the root project)
# minimal required structure is shown below
projects: []
# - project:
#     name: first-sub-project
#   analysis:
#     location: projects/sub_project_1
```

The configuration file is optional and only necessary to override default behavior.

## ${Parameter}

ENV available to the Technolinators runtime, and listed in ENV var `ALLOWED_ENV_SUBSTITUTIONS` can be referred to from the repository configuration.
This is an effective way of central configuration for things like artifact repository urls and its access credentials.
Please see the [adoption documentation](Adoption.md) for details.

### `jdk.version`

The JDK versions available for selection need to be provided and configured to the Technolinator runtime.
Please see the [adoption documentation](Adoption.md) for details, in the default runtime container, there's JDK 20 (default) and JDK 17 built in.
