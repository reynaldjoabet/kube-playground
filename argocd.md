# Argo CD
Argo CD watches Git repositories containing Kubernetes manifests and automatically syncs the desired state (in Git) with the live state (in the cluster). If they drift apart, it can detect and optionally reconcile the difference.
```sh
Git Repo ──► Repo Server (render manifests)
                  │
                  ▼
         App Controller (diff desired vs. live)
                  │
            ┌─────┴─────┐
            ▼            ▼
        In Sync      Out of Sync ──► Sync (apply to cluster)
```
The entire system is built around the principle that Git is the single source of truth for your cluster's desired state.

Argo CD defines an `Application` Custom Resource Definition (CRD) in Kubernetes. Each `Application` CR specifies:
- `Source`: a Git repo + path (or Helm chart) containing the desired manifests
- `Destination`: the target cluster + namespace to deploy into
- `Sync policy`: manual or automatic
The `Application` Controller watches these `Application` CRs and reconciles their desired state (from Git) with the live cluster state. You manage them via `kubectl`, the `argocd` CLI, or the web UI — they're just regular Kubernetes resources under the `argoproj.io` API group.

The `ApplicationSet` CR (managed by the `ApplicationSet` Controller) is a higher-level abstraction that generates multiple `Application` CRs from templates

The most important one: the gitops-engine (ClusterCache) uses the Kubernetes discovery API to enumerate every available API resource on each managed cluster, then sets up dynamic watches on all of them. This is how Argo CD detects drift — it watches everything on the target clusters

Application — deploy one app
```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: guestbook
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/argoproj/argocd-example-apps.git
    targetRevision: HEAD
    path: guestbook
  destination:
    server: https://kubernetes.default.svc
    namespace: guestbook
```
ApplicationSet — generate many Applications from a template
```yaml
apiVersion: argoproj.io/v1alpha1
kind: ApplicationSet
metadata:
  name: guestbook
spec:
  goTemplate: true
  generators:
  - list:
      elements:
      - cluster: engineering-dev
        url: https://kubernetes.default.svc
      - cluster: engineering-prod
        url: https://kubernetes.default.svc
  template:
    metadata:
      name: '{{.cluster}}-guestbook'
    spec:
      project: default
      source:
        repoURL: https://github.com/argoproj/argo-cd.git
        targetRevision: HEAD
        path: applicationset/examples/list-generator/guestbook/{{.cluster}}
      destination:
        server: '{{.url}}'
        namespace: guestbook
```
This creates two `Application` CRs: `engineering-dev-guestbook` and `engineering-prod-guestbook`.    

AppProject — RBAC boundaries
```yaml
apiVersion: argoproj.io/v1alpha1
kind: AppProject
metadata:
  name: my-project
  namespace: argocd
spec:
  description: Example Project
  sourceRepos:
  - '*'
  destinations:
  - namespace: guestbook
    server: https://kubernetes.default.svc
  clusterResourceWhitelist:
  - group: ''
    kind: Namespace
  roles:
  - name: read-only
    policies:
    - p, proj:my-project:read-only, applications, get, my-project/*, allow
    groups:
    - my-oidc-group
```    

## Webhook
### API Server webhook (/api/webhook on argocd-server)
Receives push events and triggers Application refreshes when the changed repo/branch matches an app's source.

| Provider | Events |
|---|---|
| GitHub | Push |
| GitLab | Push, Tag |
| Bitbucket Cloud | RepoPush |
| Bitbucket Server | RepositoryReferenceChanged |
| Azure DevOps | GitPush |
| Gogs | Push |

### ApplicationSet Controller webhook (/api/webhook on port :7000)

| Provider | Events |
|---|---|
| GitHub | Push, PullRequest |
| GitLab | Push, Tag, MergeRequest |
| Azure DevOps | GitPush, PR Created/Updated/Merged |

Without webhooks, Argo CD still works — it polls Git repos on a timer. Webhooks just make it react faster.

Without Argo CD (manual):

```sh
Developer writes YAML → kubectl apply → cluster
                          ^^^^^^^^^^^
                          someone runs this manually
                          who? when? which version? no audit trail
```
With Argo CD (automated):     
```                     
Developer pushes YAML to Git → Argo CD sees the change → applies to cluster
                                ^^^^^^^^^^^^^^^^^^^^^^^^
                                automatic, auditable, reversible
```                                

```sh
┌──────────────────────────────────────────────────────┐
│                    Git Repo                          │
│                                                      │
│  deployments/api.yaml          (Deployments)         │
│  services/api-svc.yaml         (Services → iptables) │
│  routes/api-route.yaml         (HTTPRoute → Gateway) │
│  rbac/team-a-role.yaml         (RBAC)                │
│  secrets/external-secret.yaml  (ExternalSecrets)     │
│  crds/postgres-cluster.yaml    (CRDs/Operators)      │
│  networkpolicies/deny-all.yaml (NetworkPolicies)     │
│  hpa/api-hpa.yaml              (Autoscaling)         │
│                                                      │
└──────────────────┬───────────────────────────────────┘
                   │ Argo CD watches (git poll or webhook)
                   ▼
┌──────────────────────────────────────────────────────┐
│              Argo CD (runs in cluster)               │
│                                                      │
│  1. Clones the repo                                  │
│  2. Renders manifests (plain YAML, Helm, Kustomize)  │
│  3. Compares desired state (git) vs actual (cluster) │
│  4. If different → syncs (applies the diff)          │
│                                                      │
└──────────────────┬───────────────────────────────────┘
                   │ kubectl apply (under the hood)
                   ▼
┌──────────────────────────────────────────────────────┐
│              Kubernetes API Server                   │
│                                                      │
│  → Admission webhooks fire                            │
│  → Objects stored in etcd                            │
│  → Controllers reconcile                             │
│  → kube-proxy writes iptables rules                  │
│  → kubelet starts pods                               │
│  → CNI assigns IPs                                   │
└──────────────────────────────────────────────────────┘
```
Argo CD runs as pods in `argocd` namespace with a ServiceAccount. That SA needs RBAC permissions to create/update resources:
```yaml
kind: ClusterRole
metadata:
  name: argocd-application-controller
rules:
  - apiGroups: ["*"]
    resources: ["*"]
    verbs: ["*"]        # needs broad access to manage everything
```
```sh
1. Developer opens PR:
   "Change api deployment from 3 → 5 replicas"

2. PR gets reviewed, approved, merged to main

3. Argo CD detects the change (polls every 3 min or git webhook)

4. Argo CD compares:
   Git:     replicas: 5
   Cluster: replicas: 3
   Status:  OUT OF SYNC

5. Argo CD applies the change (auto-sync or manual approval)

6. API server receives the update:
   → Admission webhooks validate it
   → etcd stores it
   → Deployment controller sees 5 desired, 3 actual
   → Creates 2 new pods
   → Scheduler places them on nodes
   → Kubelet starts containers via CRI
   → CNI assigns pod IPs
   → kube-proxy updates iptables (KUBE-SEP chains)
   → Service now load-balances across 5 pods

7. Argo CD checks again:
   Git:     replicas: 5
   Cluster: replicas: 5
   Status:  SYNCED ✓
```
Your YAML files in Git aren't always final. Sometimes they need processing before they become the actual Kubernetes manifests.

Plain YAML — no rendering needed:
```yaml
# git repo: deployments/api.yaml
# This is already the final manifest. Argo CD applies it as-is.
apiVersion: apps/v1
kind: Deployment
metadata:
  name: api
spec:
  replicas: 3
  template:
    spec:
      containers:
        - name: api
          image: myapp:v2.1
```
Helm — templates + values = final YAML:

Your repo doesn't have plain YAML. It has templates with variables:
```yaml
# git repo: charts/api/templates/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Values.name }}
spec:
  replicas: {{ .Values.replicas }}
  template:
    spec:
      containers:
        - name: {{ .Values.name }}
          image: {{ .Values.image }}:{{ .Values.tag }}
```
```yaml
# git repo: charts/api/values.yaml
name: api
replicas: 3
image: myapp
tag: v2.1
```
Argo CD renders (combines) them:
```sh
Template + Values → Final YAML
{{ .Values.replicas }}  →  3
{{ .Values.tag }}       →  v2.1
```
Result: the same plain YAML as above. But you only had to change tag: v2.1 → tag: v2.2 in one place, even if it's used in 10 different templates.

Kustomize — base + overlays = final YAML:

You have a base that's shared, and overlays that customize it per environment:
```sh
git repo:
  base/
    deployment.yaml       ← shared template (replicas: 1)
    service.yaml
    kustomization.yaml
  overlays/
    dev/
      kustomization.yaml  ← "use base, but set replicas: 1"
    prod/
      kustomization.yaml  ← "use base, but set replicas: 10, add resource limits"
```
```yaml
# base/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: api
spec:
  replicas: 1
  template:
    spec:
      containers:
        - name: api
          image: myapp:v2.1
```
```yaml
# overlays/prod/kustomization.yaml
resources:
  - ../../base
patches:
  - patch: |
      - op: replace
        path: /spec/replicas
        value: 10
    target:
      kind: Deployment
      name: api
```
Argo CD renders: base + prod overlay → final YAML with replicas: 10
`"Rendering" just means converting your source format into plain Kubernetes YAML:`
```sh
Plain YAML:   file.yaml                      → apply directly
Helm:         template + values              → render → plain YAML → apply
Kustomize:    base + overlay                 → render → plain YAML → apply
```
Argo CD supports all three. It figures out which format your repo uses, renders it, compares the result to what's in the cluster, and syncs the diff.

#### Kubernetes (etcd) — stores the actual cluster state:
- Running deployments, pods, services, etc.
- This is the source of truth for "what IS running"

#### Argo CD — stores its own metadata about applications:
- Which git repo to watch
- Which path in the repo
- Sync status (synced / out-of-sync)
- Sync history (when, who, what changed)
- Health status

Argo CD stores this as Kubernetes custom resources (CRDs) — so it actually all ends up in etcd too

Argo CD itself is stateless — if you delete the Argo CD pods and restart them, they read everything back from the CRDs in etcd and the git repos. Nothing is lost.

Kubernetes is the state store. Argo CD has no separate database.
- `Application`, `ApplicationSet`, and `AppProject` CRs are stored in etcd (via the Kubernetes API), just like any other K8s resource.
- Cluster credentials and repo credentials are stored as Kubernetes Secrets in the `Argo CD` namespace.
- Configuration lives in ConfigMaps (`argocd-cm`, `argocd-rbac-cm`, `argocd-notifications-cm`).
- Application sync status, health, history, and conditions are all written back to the `Application` CR's .status field in Kubernetes

Kubernetes watches use HTTP/2 long-lived streaming connections (not WebSockets). Specifically:
- The client makes a GET request to the API server with `?watch=true` (e.g., GET `/api/v1/pods?watch=true`)
- The API server holds the connection open and streams chunked JSON responses — one JSON object per event (`ADDED`, `MODIFIED`, `DELETED`)
- The connection stays open indefinitely until it's broken or times out
- Under the hood, the Kubernetes API server watches etcd (which itself uses gRPC streaming) and fans out change events to all watching clients.

`etcd (gRPC stream) → K8s API server → HTTP/2 chunked stream → Argo CD gitops-engine`

```sh
Git side (polled every ~3 min, or instantly via webhooks):
  Pull latest from Git → render manifests → "desired state"

Cluster side (real-time via HTTP/2 watch streams):
  Kubernetes API → streams changes as they happen → "actual state"

Diff runs whenever either side changes:
  Compare desired vs. actual

  If same     → SYNCED ✓ (do nothing)
  If different → OUT OF SYNC
                   → auto-sync enabled:  apply the diff automatically
                   → manual sync:        show the diff, wait for human approval
```

### Helm
Without Helm, deploying a typical app requires many YAML files:
```sh
deployment.yaml
service.yaml
configmap.yaml
secret.yaml
ingress.yaml
hpa.yaml
serviceaccount.yaml
role.yaml
rolebinding.yaml
networkpolicy.yaml
```
With Helm, you can templatize all of that and just have one `Chart.yaml` and one `values.yaml`:

Now multiply that by 3 environments (dev, staging, prod) — the only difference is a few values (replicas, image tag, domain). You end up copy-pasting YAML everywhere.

A Helm Chart is a template + values:
```sh
my-app/
├── Chart.yaml            # metadata (name, version)
├── values.yaml           # default values
└── templates/
    ├── deployment.yaml   # template with {{ .Values.xxx }}
    ├── service.yaml
    ├── configmap.yaml
    ├── ingress.yaml
    └── _helpers.tpl      # reusable template snippets
```
Argo CD can render Helm charts by running `helm template` under the hood. It takes the templates + values, renders them into plain YAML, and then applies that to the cluster. This way you can manage complex apps with many resources without having to maintain dozens of YAML files. You just change the values and let Helm generate the final manifests.

Chart.yaml — the package metadata:
```yaml
apiVersion: v2
name: my-api
version: 1.0.0           # chart version (the package)
appVersion: "2.1.0"      # your app version
description: My API service
dependencies:
  - name: postgresql      # pulls in another chart as a dependency
    version: "12.x.x"
    repository: https://charts.bitnami.com/bitnami
```

values.yaml — the defaults:
```yaml
replicaCount: 2
image:
  repository: myregistry.azurecr.io/api
  tag: "2.1.0"
  pullPolicy: IfNotPresent

service:
  type: ClusterIP
  port: 80

ingress:
  enabled: true
  hostname: api.example.com

resources:
  limits:
    cpu: 500m
    memory: 256Mi
  requests:
    cpu: 100m
    memory: 128Mi

env:
  LOG_LEVEL: info
  DB_HOST: postgres.default.svc
```
templates/deployment.yaml — the template:
```yaml
{{- define "my-api.fullname" -}}
{{ .Release.Name }}-{{ .Chart.Name }}
{{- end }}

{{- define "my-api.labels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end }}

{{- define "my-api.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
```
Per-environment values files:
```yaml
# values-dev.yaml
replicaCount: 1
image:
  tag: "latest"
env:
  LOG_LEVEL: debug

# values-prod.yaml
replicaCount: 10
image:
  tag: "2.1.0"
resources:
  limits:
    cpu: 2000m
    memory: 1Gi
env:
  LOG_LEVEL: warn
```

```sh
# See what it would generate (render without applying)
helm template my-release ./my-app -f values-prod.yaml

# Install (first time)
helm install my-release ./my-app -f values-prod.yaml -n production

# Upgrade (change values or chart version)
helm upgrade my-release ./my-app -f values-prod.yaml -n production

# Rollback
helm rollback my-release 1 -n production    # roll back to revision 1

# See history
helm history my-release -n production

# Uninstall
helm uninstall my-release -n production

# Pull a public chart
helm repo add bitnami https://charts.bitnami.com/bitnami
helm install my-postgres bitnami/postgresql -f my-postgres-values.yaml
```

```yaml
# templates/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ include "my-app.fullname" . }}
spec:
  type: {{ .Values.service.type }}
  ports:
    - port: {{ .Values.service.port }}
      targetPort: 80

# templates/hpa.yaml
{{- if .Values.autoscaling.enabled }}       ← conditionally include
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ include "my-app.fullname" . }}
spec:
  minReplicas: {{ .Values.autoscaling.minReplicas }}
  maxReplicas: {{ .Values.autoscaling.maxReplicas }}
{{- end }}
```
```yaml
# values.yaml
service:
  type: ClusterIP
  port: 80

autoscaling:
  enabled: false        # dev: no HPA
  minReplicas: 2
  maxReplicas: 10
```
```yaml
# values-prod.yaml
autoscaling:
  enabled: true         # prod: yes HPA
  minReplicas: 5
  maxReplicas: 50
```

```sh
1. LIST:  "Give me all pods"           → gets full snapshot
2. WATCH: "Tell me when anything changes" → long-lived HTTP connection, streams events
```

| Component | Watches | Why |
|---|---|---|
| kubelet | Pods (on its node) | Start/stop containers |
| kube-scheduler | Pods (unscheduled) | Assign pod to node |
| kube-proxy | Services + Endpoints | Update iptables rules |
| deployment-controller | Deployments + ReplicaSets | Create/scale ReplicaSets |
| replicaset-controller | ReplicaSets + Pods | Create/delete pods |
| endpoint-controller | Services + Pods | Update Endpoints |
| hpa-controller | HPA + metrics | Scale deployments |
| node-controller | Nodes | Detect dead nodes |
| job-controller | Jobs + Pods | Run/track batch jobs |
| cronjob-controller | CronJobs | Create Jobs on schedule |
| namespace-controller | Namespaces | Clean up on deletion |
| serviceaccount-controller | Namespaces | Create default SA |
| pv-controller | PVCs + PVs | Bind volumes |
| ingress-controller | Ingress/HTTPRoute | Reconfigure nginx/envoy |
| cert-manager | Certificates + Secrets | Issue/renew TLS certs |
| external-secrets | ExternalSecrets | Sync secrets from vault |
| argo-cd | Applications (CRD) | Sync git → cluster |
| prometheus-operator | ServiceMonitors | Configure scrape targets |
| karpenter | Pods (unschedulable) + Nodes | Provision/remove nodes |
| any CRD operator | Its custom resources | Reconcile custom logic |

`Every controller in Kubernetes is a watch loop`