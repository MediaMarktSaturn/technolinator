# Deployment Configuration

The Technolinator app has a significant resource demand, as the native build systems of all supported languages and frameworks are used by cdxgen.
So it builds up local repository caches demanding disc space and when running multiple analysis in parallel, CPU and memory are stressed as well.

We're running Technolinator on Kubernetes (alongside with Dependency-Track), therefore our generic application Helm Chart can be used.
Please read an example deployment configuration using [FluxCD](https://fluxcd.io):

For guidance on how to register a GitHub app, just follow the [Quarkus GitHub App docs](https://quarkiverse.github.io/quarkiverse-docs/quarkus-github-app/dev/register-github-app.html).

Btw: There's also a nice Helm chart for hosting Dependency-Track on Kubernetes available [here](https://github.com/MediaMarktSaturn/helm-charts/tree/main/charts/dependency-track).

If Kubernetes isn't the right thing for you and you'd like something simpler, there's also a sample [docker compose file](/docs/docker-compose/docker-compose.sample.yml) and an accompanying [sample.env](/docs/docker-compose/sample.env) available.

## HelmRepository

```yaml
---
apiVersion: source.toolkit.fluxcd.io/v1beta2
kind: HelmRepository
metadata:
    name: chart-repo
    namespace: default
spec:
    interval: 120m
    url: https://helm-charts.mms.tech
```

## HelmRelease

We're providing a Helm chart for common application deployments [here](https://github.com/MediaMarktSaturn/helm-charts/tree/main/charts/application), that can easily be used to run Technolinator on Kubernetes:

```yaml
---
apiVersion: helm.toolkit.fluxcd.io/v2beta2
kind: HelmRelease
metadata:
  name: technolinator
  namespace: app
spec:
  chart:
    spec:
      chart: application
      version: "~1"
      sourceRef:
        kind: HelmRepository
        name: chart-repo
        namespace: default
      interval: 60m
  interval: 10m
  values:
    image:
      repository: our.private/container/registry/technolinator
      tag: 1.48.7 # {"$imagepolicy": "app:technolinator:tag"}
      tagSemverRange: "~1"
    secretEnvFrom:
      - technolinator-config
    resources:
      requests:
        cpu: "1"
        memory: 6Gi
      limits:
        cpu: "4"
        memory: 10Gi
    container:
      port: 8080
    livenessProbe:
      path: /q/health/live
    readinessProbe:
      path: /q/health/ready
    monitoring:
      serviceMonitor: true
      metricsPath: /q/metrics
    configEnvFrom: # Optional
      - enabled-repos
    podSecurityContext:
      runAsUser: 201
      runAsGroup: 101
      fsGroup: 101
      fsGroupChangePolicy: Always
    volumeMounts: # Optional, without PVC it's transient cached on the pods file system
      - name: data
        pvcName: technolinator-data
        mountPath: /data
    configuration: # Optional to override defaults
      APP_ANALYSIS_TIMEOUT: 60M
      APP_PROCESS_LOGLEVEL: DEBUG
```

## ConfigMap

```yaml
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: enabled-repos
  namespace: app
data:
  APP_ENABLED_REPOS: >-
    here-you-can-list,
    all-repositories,
    you-like-to-include,
    in-the-analysis,
    or-just-leave-empty,
    for-analyzing-everything,
    thats-configured-for-GH-events,
```
## Secret

```yaml
---
apiVersion: v1
kind: Secret
metadata:
    name: technolinator-config
    namespace: app
data:
    QUARKUS_GITHUB_APP_APP_ID: ENC[AES256_GCM,data:lQ8fW6+qmQU=,iv:c9eh+Cc6t0CGe7tmNevC4fyyFVRVjAVA==,type:str]
    DTRACK_APIKEY: ENC[AES256_GCM,data:rHQKbtUbhNvBxIf3IhSJ6yLGjYT/COT00=,tag:V/rl8TJIDqHt3OXlmmXVbA==,type:str]
    ARTIFACTORY_PASSWORD: ENC[AES256_GCM,data:X3Ii3/8QIuARWvtNotSdtTBw9wJFqZAzc30Zoo7I/cVA3zpl0uYhfBscQ6itVZBclrjj5NGcRLIefg==,iv:Mskc6EimFceNVese24dapP57OUwdfa8p+S8uo2fylos=,tag:sm+TvQWp/SVQHojRHErSuQ==,type:str]
    ARTIFACTORY_USER: ENC[AES256_GCM,data:lBKLUCIC4D8D//occ8YPA7UX7D8RchSw32fkGQOsF09DR84YDdwoTw==,iv:bfBpIvs5fM/toadN/pLkUEg82FYKYizQJ09Rw5/l7c=,tag:E42aOyCujl/tNPyT6wF+DA==,type:str]
    GITHUB_TOKEN: ENC[AES256_GCM,data:0wfQRRrvjkNFYyuhi0wupSzuywJMcECF9uR7xDcanMdHm22qPhQqBKoW2NwzI=vwbopm/oxA==,type:str]
    QUARKUS_GITHUB_APP_PRIVATE_KEY: ENC[AES256_GCM,data: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,iv:hGHVTqqq41gbzmrVdV8uVtvA8aj8Y0OgOcB3i9S62lc=,tag:MGMytt6jE/EGYlYxRlxqLQ==,type:str]
    QUARKUS_GITHUB_APP_WEBHOOK_SECRET: ENC[AES256_GCM,data:znmefpuxLiJvqRp4I0pAV+D5E9kRJGDT7kr+tYPW/zJmTMvU1dtDxQ=,iv:sY7PesATxG1UCNkj0r4yH+TTY=,tag:kjaw1UnCVZ1WNccumUQ==,type:str]
sops:
  ...
```

## PVC

```yaml
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: technolinator-data
  namespace: app
spec:
  accessModes:
    - ReadWriteOnce # Only possible, when all app instances run on the same node, this example uses a single replica.
  storageClassName: regional-storage
  resources:
    requests:
      storage: 50Gi
```

## Ingress

```yaml
---
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: technolinator
  namespace: app
  annotations:
    nginx.ingress.kubernetes.io/proxy-body-size: "5m" # GitHub push payloads become that big.
    # access is secured by the GitHub app library, but Quarkus info endpoint would be accessible
    nginx.ingress.kubernetes.io/server-snippet: |-
      location /q {
        deny all;
      }
spec:
  ingressClassName: nginx
  rules:
    - host: github-events.technolinator.awesome.domain
      http:
        paths:
          - path: "/"
            pathType: Prefix
            backend:
              service:
                name: technolinator
                port:
                  number: 80
```
