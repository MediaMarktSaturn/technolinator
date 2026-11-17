# Getting started

This guide takes you from zero to the first analyzed repository: Technolinator running, connected to GitHub and Dependency-Track.

It covers the **minimal viable setup**. Everything else is optional and documented in the [runtime](Runtime_Config.md), [deployment](Deployment_Config.md) and [adoption](Adoption.md) docs.

```mermaid
flowchart LR
    GH[GitHub] -- webhook events --> T[Technolinator]
    T -- "SBOM upload" --> DT[Dependency-Track]
    T -- "commit status, PR comments" --> GH
```

## What you need

| Requirement                                                                 | Notes                                                                                                                      |
|-----------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------|
| A [Dependency-Track](https://dependencytrack.org) instance                  | Technolinator uploads SBOMs to it. See the [Helm chart](https://github.com/MediaMarktSaturn/helm-charts/tree/main/charts/dependency-track) or the [docker compose sample](docker-compose/docker-compose.sample.yml) |
| A GitHub user or organization, where you can register a **GitHub App**      | Technolinator is a GitHub App, see [step 2](#2-register-the-github-app) for who is allowed to do what                       |
| A place to run the container image                                          | Analysis runs the build tools of the analyzed projects, so plan for disc space, CPU and memory, see [Deployment](Deployment_Config.md) |
| An HTTPS endpoint for GitHub webhooks to reach Technolinator                | Not needed for local experiments, see [step 5](#5-let-github-reach-technolinator)                                          |

## 1. Dependency-Track API key

1. In Dependency-Track go to *Administration* → *Access Management* → *Teams* and create a team, e.g. `technolinator`.
2. Give it the permissions `BOM_UPLOAD`, `PROJECT_CREATION_UPLOAD`, `VIEW_PORTFOLIO` and `PORTFOLIO_MANAGEMENT`.
   (Derived from the API calls Technolinator makes: BOM upload and processing state, project create/lookup/list and project updates like tags and archiving. `PORTFOLIO_MANAGEMENT` is needed for the latter.)
3. Copy the team's API key, it's your `DTRACK_APIKEY`.

## 2. Register the GitHub App

Technolinator's own communication with GitHub (receiving events, reading repositories, writing commit statuses and pull request comments) runs as a GitHub App, so you have to register one. This has to be done once, the generic procedure is described in the [Quarkus GitHub App docs](https://docs.quarkiverse.io/quarkus-github-app/dev/register-github-app.html), the values for Technolinator are:

Go to *Settings* → *Developer settings* → *GitHub Apps* → *New GitHub App*, of your user or of your organization (the app is then owned by it).

| Field                         | Value                                                                                                                  |
|-------------------------------|------------------------------------------------------------------------------------------------------------------------|
| GitHub App name, Homepage URL | Free choice                                                                                                            |
| Webhook → Active              | checked                                                                                                                |
| Webhook URL                   | The public URL Technolinator is reachable at, e.g. `https://technolinator.example.com/`, see [step 5](#5-let-github-reach-technolinator). Can be changed later |
| Webhook secret                | A random string, e.g. generated using `openssl rand -hex 20`. It's your `QUARKUS_GITHUB_APP_WEBHOOK_SECRET`            |
| Repository permissions        | *Commit statuses*: Read and write, *Contents*: Read-only, *Issues*: Read and write, *Pull requests*: Read and write, *Metadata*: Read-only       |
| Subscribe to events           | *Pull request*, *Push*, *Repository* and *Release*                                                                     |
| Where can this app be installed | *Only on this account* is sufficient for a private deployment                                                        |

The permissions and events are the ones listed in [Adoption](Adoption.md#github-app-requirements). Mind that changing them later requires every installation to accept the change.

After creating the app:

1. Note the numeric **App ID** from the app's settings page, it's your `QUARKUS_GITHUB_APP_APP_ID`.
2. In the section *Private keys* click *Generate a private key*. The downloaded `.pem` file is your `QUARKUS_GITHUB_APP_PRIVATE_KEY`. Keep it safe, it can't be downloaded again.

### Install the app

An app does nothing, until it is installed: open the app's settings, *Install App*, pick the account and choose either *All repositories* or *Only select repositories*.
Technolinator only gets events of, and can only read, repositories that it's installed for.

Who can do what:

* **User account:** you are the owner and can register and install the app on your own repositories.
* **Organization, registering an org-owned app:** requires an organization owner or a member having the *GitHub App manager* role (granted by an owner).
* **Organization, installing:** requires an organization owner. Other members can *request* the installation, which an owner has to approve. When the app is already installed, an app manager can maintain its settings.
* If you may not install the app yourself, send the owner the link to the app and a note about the permissions it needs (see above).

## 3. Minimal configuration

The following values are required, everything else has a sensible default (all options: [Runtime configuration](Runtime_Config.md)):

| Parameter                          | Example                                                                  | Notes                                                                                                                   |
|------------------------------------|--------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| `QUARKUS_GITHUB_APP_APP_ID`        | `123456`                                                                 | Numeric app id                                                                                                          |
| `QUARKUS_GITHUB_APP_WEBHOOK_SECRET`| `5f1c0c8e...`                                                            | Exactly the secret configured on the GitHub App                                                                         |
| `QUARKUS_GITHUB_APP_PRIVATE_KEY`   | `"-----BEGIN RSA PRIVATE KEY-----\nMIIE...\n-----END RSA PRIVATE KEY-----"` | The content of the `.pem` file as a single line, newlines written as `\n` (see [sample.env](docker-compose/sample.env)) |
| `DTRACK_URL`                       | `https://dtrack.example.com`                                             | With scheme, without trailing slash. Use the address your users reach Dependency-Track at, it's linked from GitHub commit statuses |
| `DTRACK_APIKEY`                    | `odt_xxxxxxxx...`                                                        | The API key from [step 1](#1-dependency-track-api-key)                                                                  |

Technolinator derives the API address from `DTRACK_URL` (`${DTRACK_URL}/api/v1`). If it should reach Dependency-Track by another, e.g. cluster-internal, address, additionally set `DTRACK_API_URL=http://dtrack-apiserver:8080/api/v1`.

Some more examples for formats of frequently used options:

| Parameter                   | Example                | Notes                                                                                              |
|-----------------------------|------------------------|----------------------------------------------------------------------------------------------------|
| `APP_ENABLED_REPOS`         | `repo-a,repo-b`        | Comma separated repository names, without owner. Empty: all repositories the app is installed for. Recommended to limit while trying out |
| `APP_ANALYSIS_TIMEOUT`      | `60M`                  | Duration, `30S`, `60M`, `2H`                                                                       |
| `QUARKUS_LOG_CONSOLE_JSON`  | `false`                | Readable text logs instead of JSON, see [logging](#logging)                                        |

### Optional: GITHUB_TOKEN

All of Technolinator's own GitHub communication uses the authentication of the GitHub App installation, so a `GITHUB_TOKEN` is **not required**.
But the GitHub App authentication can't be handed over to the tools Technolinator runs, so providing a token is recommended in addition. It's used for

* cloning/fetching private dependencies, e.g. `go` modules from private repositories, and
* the GitHub API requests of cdxgen, e.g. fetching license information, which then run against the much higher quota of an authenticated user.

The token needs read access to the repositories only: the `repo` scope for a classic personal access token, or read-only *Contents* access for a fine-grained one.

### Where to put the configuration

Technolinator is a [Quarkus](https://quarkus.io) application, so any [Quarkus configuration source](https://quarkus.io/guides/config-reference) works. The most useful are:

* **Environment variables**, e.g. `docker run -e`, a Kubernetes `Secret`/`ConfigMap`, or `environment`/`env_file` of docker compose.
* **A `.env` file** in the working directory of the app, that's `/data` in the container image. It keeps secrets out of your compose/manifest files and is the easiest way to provide the multi-line private key.
* **Java system properties**, e.g. `-Dapp.analysis_timeout=30M` via `JAVA_OPTIONS`, and a `config/application.properties` file below the working directory.

If a parameter is given more than once, system properties win over environment variables, which win over `.env`, which wins over `config/application.properties`.
Mind to keep secrets away from your logs: the default `SENSITIVE_ENV_VARS` already covers the ones listed above.

## 4. Run it

```shell
# technolinator.env contains the values from step 3, readable for the app user (uid 201)
docker run -d --name technolinator -p 8080:8080 \
  -v "$PWD/technolinator.env:/data/.env:ro" \
  -v technolinator-data:/data \
  ghcr.io/mediamarktsaturn/technolinator:VERSION
```

Replace `VERSION` with the [latest release](https://github.com/MediaMarktSaturn/technolinator/releases) (without the leading `v`); `fat-VERSION` is the image containing even more SDKs, like Swift.
The volume keeps the build tool caches of analyses between restarts; it's optional, but saves a lot of time and traffic.

> `docker run --env-file` doesn't strip quotes or resolve `\n`, so it isn't suitable for the private key. Use the mounted `.env` file shown above, or `docker compose`'s `env_file`, which does.

**Docker compose:** if you don't have Dependency-Track yet, the [docker compose sample](docker-compose/docker-compose.sample.yml) runs the whole stack (Traefik with TLS, Dependency-Track, PostgreSQL and Technolinator). Copy it together with [sample.env](docker-compose/sample.env), adjust the domain and secrets and start `dtrack-apiserver` first, to create the API key ([step 1](#1-dependency-track-api-key)), put it to the env file and then start the rest.
The sample is a starting point, not a hardened production setup.

**Kubernetes:** see [Deployment configuration](Deployment_Config.md).

## 5. Let GitHub reach Technolinator

GitHub delivers events by calling the webhook URL of your GitHub App.

### Internet-exposed endpoint (regular setup)

Run Technolinator behind a reverse proxy/ingress with TLS, forwarding to port `8080` (`PORT`), and enter its public URL as *Webhook URL* of the GitHub App. **No webhook forwarding service like smee.io is needed in this setup.**

* The webhook is served on the root path `/` by default. Another path can be configured by `QUARKUS_GITHUB_APP_WEBHOOK_URL_PATH`, or stripped by the proxy, like the docker compose sample does for `/technohook`.
* Allow request bodies of about 5 MB, push payloads can get that big.
* Only route the webhook to the outside: `/q/*` (health, metrics), `/analyze` and `/housekeeping` shouldn't be reachable from the internet, see the ingress example in [Deployment configuration](Deployment_Config.md).

### Local setup without inbound connections

When your machine can't be reached from the internet, GitHub's events have to be forwarded to it:

* [smee.io](https://smee.io): create a channel, enter its URL as *Webhook URL* of the GitHub App and set `QUARKUS_GITHUB_APP_WEBHOOK_PROXY_URL` to it. The Quarkus GitHub App extension connects to smee itself, there's no smee client to install.
  Since smee doesn't forward payloads unmodified, the extension skips the webhook signature check, but only in dev mode. So this fits running Technolinator from source with `mvn quarkus:dev` (see [Contribution](Contribution.md)), not the container image.
* To try the **container image** locally, use a tunnel like [ngrok](https://ngrok.com) (with a named domain) or Cloudflare Tunnel and enter its public URL as *Webhook URL*.

## 6. Verify

1. Check the app is up: `curl http://localhost:8080/q/health/ready`.
2. In the GitHub App settings, tab *Advanced* → *Recent Deliveries* lists all webhook calls with their response. Use *Redeliver* to repeat one.
3. Push to the default branch of a repository the app is installed for. The log should tell:
   ```
   Ref refs/heads/main of repository https://api.github.com/repos/<owner>/<repo> eligible for analysis
   ...
   Handling completed for ref refs/heads/main of repository ...
   ```
4. Dependency-Track now has a project named like the repository, and the commit shows a *Supply Chain Security* status.
5. Open a pull request: a comment with the vulnerability report appears (pull requests of bots are ignored by default).

Only pushes to the **default branch** are analyzed and uploaded; other branches are ignored. Pull requests get a vulnerability report but aren't uploaded to Dependency-Track.
Per-repository behavior can be tuned by [`.github/technolinator.yml`](Repository_Config.md).

## Logging

Technolinator logs to the console, as JSON by default. Every push event received is noted, and the output of the analysis tools as well. Configure it like any Quarkus app, see the [Quarkus logging guide](https://quarkus.io/guides/logging):

| Goal                                   | Configuration                                                                                       |
|----------------------------------------|-----------------------------------------------------------------------------------------------------|
| Readable text instead of JSON          | `QUARKUS_LOG_CONSOLE_JSON=false`                                                                    |
| More verbose log                       | `QUARKUS_LOG_LEVEL=DEBUG`, or just for Technolinator: `QUARKUS_LOG_CATEGORY__COM_MEDIAMARKTSATURN_TECHNOLINATOR__LEVEL=DEBUG` |
| Output of cdxgen, grype and friends    | `APP_PROCESS_LOGLEVEL=DEBUG` and `CDXGEN_DEBUG=true`                                                |
| Log to a file                          | `QUARKUS_LOG_FILE_ENABLE=true` and `QUARKUS_LOG_FILE_PATH=/data/technolinator.log`, see the [file handler options](https://quarkus.io/guides/logging#quarkus-log-logging-log-config_quarkus.log.file-handlers-file-handlers) |

On container platforms we recommend collecting stdout with the log collector of your platform; for small setups e.g. [Grafana Loki](https://grafana.com/oss/loki/) is a lightweight option.
Metrics and a Grafana dashboard are described in [Runtime configuration](Runtime_Config.md#observability).

## Troubleshooting

| Symptom                                                              | Check                                                                                                                                         |
|----------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| Startup fails complaining about an unresolved/missing property       | One of the [minimal parameters](#3-minimal-configuration) isn't provided. The message names it. Check the `.env` file is mounted into the working directory `/data` and readable |
| Startup or authentication fails because of the private key           | The key must be the full PEM content, including the `-----BEGIN`/`-----END` lines, newlines written as `\n` on a single line, without stray quotes or spaces |
| No log output on a push                                              | *Recent Deliveries* of the GitHub App: wrong webhook URL/path, unreachable endpoint, or deliveries being rejected because the webhook secret differs from `QUARKUS_GITHUB_APP_WEBHOOK_SECRET` |
| Event arrives, but the repository isn't analyzed                     | Log says *excluded by global config*: `APP_ENABLED_REPOS`. *Disabled by repo config*: `enable: false` in `.github/technolinator.yml`. *not eligible*: push wasn't to the default branch |
| No event arrives for a repository                                    | The app isn't installed for that repository (*Only select repositories*), or the event (*Push*, *Pull request*, *Repository*, *Release*) isn't subscribed |
| Analysis runs, but the upload to Dependency-Track fails              | `DTRACK_URL`/`DTRACK_API_URL` reachable from within the container, API key valid and its team having the [permissions](#1-dependency-track-api-key) |
| No commit status                                                     | The app needs *Commit statuses: Read and write*; `APP_COMMIT_STATUS_WRITE_ENABLED` must not be `false`                                       |
| No pull request comment                                              | The app needs *Pull requests: Read and write*; check `APP_PULL_REQUESTS_ENABLED`, and that the author isn't a bot (`APP_PULL_REQUESTS_IGNORE_BOTS`) |
| Analyses are killed or time out                                      | Give the container more memory/CPU, see [Deployment](Deployment_Config.md), or raise `APP_ANALYSIS_TIMEOUT`                                   |
| Java stack traces are hard to read                                   | Switch to text logging with `QUARKUS_LOG_CONSOLE_JSON=false`                                                                                  |

## Where to go next

* [Repository specific configuration](Repository_Config.md): tune the analysis per repository
* [Runtime configuration](Runtime_Config.md): all parameters
* [Analysis](Analysis.md): [triggering an analysis manually](Analysis.md#manual-analysis) and [failure reporting](Analysis.md#failure-reporting)
* [Adoption](Adoption.md): private artifact repositories, JDK versions and other customization of the image
* [Housekeeping](Housekeeping.md): automatic cleanup of Dependency-Track
