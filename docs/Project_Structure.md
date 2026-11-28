# Inside Technolinator

_to be updated_

## Process overview

```mermaid
sequenceDiagram

    GitHub -->> OnPushDispatcher: Send push event
    OnPushDispatcher ->> OnPushDispatcher: Check relevance

    opt Push on default branch

        OnPushDispatcher -->> GitHub: Commit status 'Pending'
        OnPushDispatcher ->>+ PushHandler: Start process

        PushHandler ->>+ RepositoryService: Fetch push-ref content
        RepositoryService -->>- PushHandler: LocalRepository

        PushHandler ->>+ CdxgenClient: Create SBOM using cdxgen
        CdxgenClient -->>- PushHandler: SBOMGenerationResult

        PushHandler ->>+ DependencyTrackClient: Upload SBOM
        DependencyTrackClient -->>- PushHandler: Project

        DependencyTrackClient -->> Dependency-Track: Upsert SBOM
        DependencyTrackClient -->> Dependency-Track: Patch Project

        PushHandler -->>- OnPushDispatcher: Project

        alt Process succeeded
            OnPushDispatcher -->> GitHub: Commit status 'OK'
            OnPushDispatcher -->> CommitStatusMetricsUpdater: Track commit status
        else Process failed
            OnPushDispatcher -->> GitHub: Commit status 'Error'
        end

    end

    loop Backoff of 5 minutes, doubled up to 4 hours, until current
        CommitStatusMetricsUpdater ->> Dependency-Track: Fetch metrics of the projects
    end
    CommitStatusMetricsUpdater -->> GitHub: Commit status 'OK' with risk score and vulnerabilities
```

The commit status of **pull-requests** tells about the vulnerabilities the analysis found, see [Commit status](Analysis.md#commit-status): `OnPullRequestDispatcher` writes `pending` before the analysis, and `success` or `failure` after it, from the `AnalysisOutcome` of the analysis handler, which sums up the numbers per severity stated by the vulnerability reports of the SBOMs.

## Manual analysis

Besides push events, an analysis can be started on demand with `POST /analyze?repository=owner/repo`, see [Analysis](Analysis.md#manual-analysis). `AnalysisEndpoint` hands the request to `AnalysisTrigger`, which looks up the repository with the installation of the GitHub app, reads its `technolinator.yml` and the head of the default branch, and then runs the very same steps as for a push event by calling `OnPushDispatcher` (`eligibility()` and `analyze()`), so everything from `PushHandler` on is shared.

```mermaid
sequenceDiagram

    User ->>+ AnalysisEndpoint: POST /analyze?repository=owner/repo
    AnalysisEndpoint ->>+ AnalysisTrigger: Trigger analysis

    AnalysisTrigger ->> GitHub: Find installation, fetch repository, default branch head and technolinator.yml
    AnalysisTrigger ->> OnPushDispatcher: Check relevance

    alt Repository not found, disabled, archived or analysis in progress
        AnalysisTrigger -->> AnalysisEndpoint: Rejected
        AnalysisEndpoint -->> User: 404, 409, 422 or 502
    else Eligible
        AnalysisTrigger -->> OnPushDispatcher: Analyze in background
        AnalysisTrigger -->>- AnalysisEndpoint: Started
        AnalysisEndpoint -->>- User: 202 Accepted

        OnPushDispatcher -->> GitHub: Commit status 'Pending'
        OnPushDispatcher ->> PushHandler: Start process as for a push event
        OnPushDispatcher -->> GitHub: Commit status 'OK' or 'Error'
    end
```

## Archiving

When a repository gets archived or deleted on GitHub, all of its Dependency-Track projects are archived (deactivated) as well.
A deleted repository can't be asked for its `technolinator.yml` anymore, so `enable: false` is not honoured in that case; `APP_ENABLED_REPOS` still applies.
Projects are matched by name and by the repositories VCS or website URL; children of multi-module projects are archived before their parents.
Projects lacking external references (e.g. not created by Technolinator) are matched by their exact name, and their multi-module children via the parent relation. Projects referencing a different repository are never archived.
The `repository` event requires the GitHub App to be subscribed to the *Repository* event.

```mermaid
sequenceDiagram

    GitHub -->> OnRepositoryDispatcher: Send repository event
    OnRepositoryDispatcher ->> OnRepositoryDispatcher: Check action archived or deleted and relevance

    OnRepositoryDispatcher ->>+ AnalysisProcessHandler: Archive projects
    AnalysisProcessHandler ->>+ DependencyTrackClient: Archive projects of repository

    DependencyTrackClient ->> Dependency-Track: List projects by name (paged)
    DependencyTrackClient ->> Dependency-Track: Patch child projects inactive
    DependencyTrackClient ->> Dependency-Track: Patch parent projects inactive

    DependencyTrackClient -->>- AnalysisProcessHandler: Number of archived projects
    AnalysisProcessHandler -->>- OnRepositoryDispatcher: Number of archived projects
```
