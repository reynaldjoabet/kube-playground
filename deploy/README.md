# Deploying moneystack and lattice-oidc: Services, Ingress and Gateway

Two real apps from sibling repositories, wired up the way [ingress-and-gateway-api.md](../ingress-and-gateway-api.md) explains:

| App | Repository | Container port | Host | Probes |
|---|---|---|---|---|
| moneystack (http4s) | `../moneystack` | 8080 (`HTTP_PORT`) | `money.example.com` | `/health/live`, `/health/ready` (503 until Postgres answers) |
| lattice-oidc (Play) | `../lattice-oidc` | 9000 | `login.example.com` | `/health/live`, `/health/ready` |

## Layout

```
deploy/
├── platform/gateway.yaml          platform team: GatewayClass, the shared Gateway "public", HTTP→HTTPS route
├── moneystack/
│   ├── namespace.yaml             label gateway-access: public (allowed to attach to the Gateway)
│   ├── deployment.yaml            the Pods: label component=api, named port http → 8080
│   ├── service.yaml               ClusterIP: selector → EndpointSlice → Pod IPs
│   ├── postgres-service.yaml      ExternalName: "postgres" → a managed database's DNS name
│   ├── httproute.yaml             money.example.com → Service moneystack; /health blocked
│   ├── ingress.yaml               ALTERNATIVE: the same with an Ingress
│   ├── service-loadbalancer.yaml  ALTERNATIVE: its own cloud LB, no routing
│   └── kustomization.yaml         default = Gateway
├── lattice-oidc/
│   ├── namespace.yaml, deployment.yaml (trimmed from the app's own), service.yaml
│   ├── httproute.yaml             login.example.com → Service lattice; /metrics blocked
│   ├── ingress.yaml               ALTERNATIVE
│   └── kustomization.yaml
└── lab/kustomization.yaml         both apps with a stand-in image, for minikube
```

Who owns what:

| Object | Owner | Says |
|---|---|---|
| GatewayClass `envoy` | whoever installs Envoy Gateway | "Envoy Gateway implements Gateways of this class" |
| Gateway `infra/public` | platform team | ports 80/443, the `*.example.com` certificate, which namespaces may attach |
| HTTPRoute `moneystack`, `lattice` | each app team, in its own namespace | "my host and paths → my Service" |
| Service `moneystack`, `lattice` | each app team | "these labels = my Pods, port 80 → container port" |

## The whole picture

```
                         ONE cloud LB (from the Gateway's LoadBalancer Service)
 money.example.com ─┐               │
                    ├──► DNS → 203.0.113.10:443
 login.example.com ─┘               │
                                    ▼
             envoy-gateway-system: Service ① envoy-infra-public-… (LoadBalancer) → Envoy Pods
                                    │   TLS ends here (*.example.com)
                                    │   reads Host header:
                 ┌──────────────────┴───────────────────┐
     money.example.com                         login.example.com
     HTTPRoute moneystack/moneystack           HTTPRoute lattice/lattice
       /health* → rewrite /not-found             /metrics* → rewrite /not-found
       /        → Service moneystack:80          /         → Service lattice:80
                 │ lookup only                             │ lookup only
     EndpointSlice: 10.244.x.x:8080 ×2           EndpointSlice: 10.244.x.x:9000 ×2
                 │                                         │
                 ▼  Envoy → Pod IP directly                ▼
         moneystack Pods                             lattice Pods
             │
             │ in-cluster calls skip the Gateway:
             ├──► http://lattice.lattice.svc/api/introspection   ClusterIP → kube-proxy → lattice Pod
             └──► DB_HOST=postgres  → CoreDNS CNAME → moneystack-db.internal.example.com
```

The Services, by type:

| Service | Type | Carries traffic? |
|---|---|---|
| `envoy-infra-public-…` (created by Envoy Gateway) | LoadBalancer | yes: everything from outside, for both apps |
| `moneystack`, `lattice` | ClusterIP | not from the Gateway (Envoy uses them to look up Pod IPs). Yes for in-cluster callers. |
| `postgres` | ExternalName | no: it's a DNS alias (CNAME) |
| `moneystack-public` (alternative file) | LoadBalancer | yes, only if you choose that design: a second cloud LB just for moneystack |

## Gateway, Ingress or LoadBalancer: the same app three ways

| | `httproute.yaml` (default) | `ingress.yaml` | `service-loadbalancer.yaml` |
|---|---|---|---|
| Cloud LBs for both apps | 1 (the Gateway's) | 1 (the ingress controller's) | 2, one per app |
| Created when | the Gateway is created | the controller is installed | each Service is created |
| TLS certificate | one, in `infra`, owned by the platform | a copy in **each** app namespace | the app or a provider annotation |
| Block `/health` or `/metrics` publicly | `URLRewrite`, a typed field | no field; needs a controller annotation | impossible: every path reaches the app |
| HTTP→HTTPS | one platform route for all hosts | `ssl-redirect` annotation per Ingress (ingress-nginx only) | no |
| Who may claim `login.example.com` | only namespaces the Gateway allows | anyone who can create an Ingress | n/a |

## Kustomize and Helm

This folder uses **Kustomize** (`kustomization.yaml`, `kubectl apply -k`). Envoy Gateway on the lab was installed with **Helm**. They have the same goal, not writing YAML by hand for every variation, but they're separate tools and take opposite approaches:

```
 KUSTOMIZE: patch plain YAML                      HELM: fill in templates, then track an install
 ──────────────────────────                       ─────────────────────────────────────────────
 base/  (real, valid YAML)                        chart/
   deployment.yaml  image: moneystack:dev           templates/deployment.yaml
 overlay/kustomization.yaml                           image: {{ .Values.image.repository }}:{{ .Values.image.tag }}
   resources: [../base]                             values.yaml   image: {repository: …, tag: …}
   images: [{name: moneystack, newName: …}]
   patches: [replicas: 2, remove probes …]        helm install eg … --set …
        │                                                │ render templates with values
        ▼ kubectl kustomize / apply -k                   ▼ apply, then STORE a release record in the cluster
 plain YAML → kubectl apply                       YAML in the cluster + Secret sh.helm.release.v1.<name>.v<N>
 (nothing remembered afterwards)                  (helm upgrade / rollback / uninstall use that record)
```

| | Kustomize | Helm |
|---|---|---|
| On the lab | [lab/kustomization.yaml](lab/kustomization.yaml): swaps the images to agnhost, sets `replicas: 2`, removes probes, env and volumes | Envoy Gateway: release `eg`, chart `gateway-helm-v1.9.3` |
| Comes with | **built into kubectl** (`kubectl apply -k`; Kustomize v5.8.1 in the lab's kubectl) | a separate CLI (`helm` v4.3.1 on this machine) |
| Input | ordinary, valid YAML plus patches | Go templates plus `values.yaml`, which aren't valid YAML until rendered |
| Customising | an overlay per environment patches the base | `--set` / `-f values-prod.yaml`, limited to what the chart exposes |
| Remembers installs? | no: it only renders YAML, and `kubectl apply` does the rest | yes: every install or upgrade is stored as a Secret revision, so `helm rollback` works |
| Sharing | a Git directory | a versioned **chart** in a repo or OCI registry; how most third-party software ships (Envoy Gateway, ingress-nginx, cert-manager) |
| Typical use | your own apps, per-environment differences | installing other people's software; your own apps when you want packaging |

What Helm leaves in the cluster (checked on the lab, 3 Nov 2026):

```
$ kubectl get secret -A -l owner=helm
NAMESPACE              SECRET                     RELEASE   VERSION   STATUS
envoy-gateway-system   sh.helm.release.v1.eg.v1   eg        1         deployed

$ kubectl -n envoy-gateway-system get deploy envoy-gateway -o jsonpath='…'
helm.sh/chart=gateway-helm-v1.9.3  app.kubernetes.io/managed-by=Helm  meta.helm.sh/release-name=eg
```

Kustomize leaves nothing comparable. `kubectl kustomize lab` just prints YAML. Here that's the two Deployments with `replicas: 2`, `args: [netexec, …]` and `image: registry.k8s.io/e2e-test-images/agnhost:2.53`.

**They're often combined** (from the tools' docs; not tried here):
- **Kustomize can run Helm:** a `helmCharts:` field in `kustomization.yaml` renders a chart that Kustomize then patches. It needs `kustomize build --enable-helm`.
- **Helm can run Kustomize after rendering:** `helm install --post-renderer`, for changes the chart doesn't expose as values.
- **Argo CD supports both**, and decides which to use from what's in the Git directory ([watching-the-api-server.md](../watching-the-api-server.md) §6).

A common split is Helm for third-party software, like Envoy Gateway here, and Kustomize for your own apps, like this folder.

## Running it on the lab

The real apps can't start on minikube. moneystack has no image yet (the repo doesn't use sbt-native-packager) and needs Postgres. lattice-oidc needs Authlete, Postgres and Redis. Routing doesn't depend on what the Pods run, so [lab/kustomization.yaml](lab/kustomization.yaml) keeps everything that matters for it: labels, named ports 8080/9000, Services and HTTPRoutes. It swaps the containers for `agnhost netexec` on the same ports and drops the probes, env and config volumes.

```sh
kubectl apply -k deploy/lab
kubectl -n envoy-gateway-system port-forward svc/envoy-infra-public-8d55c10f 8443:443 &
curl -k --resolve money.example.com:8443:127.0.0.1 https://money.example.com:8443/hostname
curl -k --resolve login.example.com:8443:127.0.0.1 https://login.example.com:8443/hostname
kubectl delete -k deploy/lab
```

## What was checked (2026-11-02, minikube, Envoy Gateway v1.9.3)

- **`kubectl kustomize`** renders `moneystack/`, `lattice-oidc/` and `lab/`.
- **`deploy/platform/gateway.yaml`:** `kubectl diff` against the lab's Gateway shows no difference.
- **Server-side dry run** of `moneystack/ingress.yaml`, `moneystack/service-loadbalancer.yaml` and `lattice-oidc/ingress.yaml`: accepted. They weren't applied, because the lab has no ingress controller or CCM.
- **`kubectl apply -k deploy/lab`:** both rollouts completed, and both HTTPRoutes `Accepted=True ResolvedRefs=True`.
- **EndpointSlices hold the Pod IPs on the container ports:**
  - `moneystack-5nm4v  8080  10.244.120.96,10.244.120.108`
  - `lattice-sqt7r     9000  10.244.120.102,10.244.120.104`
- **Through the one Gateway:**
  ```
  money.example.com/hostname ×8 →  5 moneystack-5f7cc868f8-hm7hg, 3 moneystack-5f7cc868f8-jg8tp
  login.example.com/hostname ×8 →  6 lattice-d598669cb-6ccjz,     2 lattice-d598669cb-nzmgw
  other.example.com             →  404 (no route for that host)
  http://money.example.com/budget/wallets → 301 https://money.example.com/budget/wallets  (platform's to-https)
  ```
- **The blocked paths, from Envoy's access log:** the request went upstream as `/not-found`, on rule 0. netexec answers any unknown path with the time, so the response itself doesn't show the rewrite.
  ```
  money.example.com  → /not-found  httproute/moneystack/moneystack/rule/0/...  upstream 10.244.120.96:8080
  login.example.com  → /not-found  httproute/lattice/lattice/rule/0/...        upstream 10.244.120.104:9000
  /accountant/ledger → unchanged   httproute/moneystack/moneystack/rule/1/...  upstream 10.244.120.108:8080
  ```
  The upstream is always a Pod IP on the container port, never a ClusterIP.
- **ExternalName:** `nslookup postgres.moneystack.svc.cluster.local` → `canonical name = moneystack-db.internal.example.com`.
- **In-cluster call:** from a moneystack Pod, a TCP connect to `lattice.lattice.svc:80` succeeded, through the ClusterIP and kube-proxy, with no Gateway involved.

Not checked: the real images, the probes against the real apps, the Ingress files under a running ingress controller, and the LoadBalancer alternative under a CCM.
