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
        else Process failed
            OnPushDispatcher -->> GitHub: Commit status 'Failed'
        end

    end
```

## Archiving

When a repository gets archived on GitHub, all of its Dependency-Track projects are archived (deactivated) as well.
Projects are matched by name and by the repositories VCS or website URL; children of multi-module projects are archived before their parents.
Projects lacking external references (e.g. not created by Technolinator) are matched by their exact name, and their multi-module children via the parent relation. Projects referencing a different repository are never archived.
The `repository` event requires the GitHub App to be subscribed to the *Repository* event.

```mermaid
sequenceDiagram

    GitHub -->> OnRepositoryDispatcher: Send repository event
    OnRepositoryDispatcher ->> OnRepositoryDispatcher: Check action archived and relevance

    OnRepositoryDispatcher ->>+ AnalysisProcessHandler: Archive projects
    AnalysisProcessHandler ->>+ DependencyTrackClient: Archive projects of repository

    DependencyTrackClient ->> Dependency-Track: List projects by name (paged)
    DependencyTrackClient ->> Dependency-Track: Patch child projects inactive
    DependencyTrackClient ->> Dependency-Track: Patch parent projects inactive

    DependencyTrackClient -->>- AnalysisProcessHandler: Number of archived projects
    AnalysisProcessHandler -->>- OnRepositoryDispatcher: Number of archived projects
```
