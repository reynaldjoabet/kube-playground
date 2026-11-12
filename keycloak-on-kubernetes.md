# Keycloak on Kubernetes: every use case, how it works, and why

Related notes in this repo:
[statefulsets-and-daemonsets.md](statefulsets-and-daemonsets.md) ·
[kubernetes-services.md](kubernetes-services.md) ·
[ingress-and-gateway-api.md](ingress-and-gateway-api.md) ·
[secrets-and-env.md](secrets-and-env.md) ·
[watching-the-api-server.md](watching-the-api-server.md)

---

## 0. The big picture

There are two ways to run Keycloak on Kubernetes:

1. **Plain manifests or Helm.** You write the StatefulSet, Services, probes and so on yourself. The server has Kubernetes-aware features (§9, §10, §15, §17), but nothing creates the objects for you.
2. **The Keycloak Operator.** You write one `Keycloak` custom resource (CR), and a controller in the cluster creates and keeps all the other objects in line with it. Most of this file is about this path, because that's where the Kubernetes logic lives.

```
 you write                         the operator creates and owns
 ─────────                         ─────────────────────────────
 Keycloak CR  (k8s.keycloak.org/v2beta1, short name "kc")
   │
   ├──► Secret          <name>-initial-admin     (only if you don't bring your own)
   ├──► StatefulSet     <name>                   (the Keycloak Pods)
   ├──► Service         <name>-service           (8080 / 8443 / 9000)
   ├──► Service         <name>-discovery         (headless, 7800, cluster discovery)
   ├──► Ingress         <name>-ingress           (on by default)
   ├──► NetworkPolicy   <name>-network-policy    (on by default)
   ├──► ServiceMonitor  <name>                   (only if metrics on + CRD present)
   └──► Job             <name>-update-job        (only for the Auto update strategy)

 KeycloakRealmImport CR ──► Secret <kc>-<realm>-realm  ──► Job <import-name>
 KeycloakOIDCClient CR  ──► (no k8s object)  ──► HTTP calls to Keycloak's admin API
 KeycloakSAMLClient CR  ──► (same)
```

**(code)** The list of dependents and their conditions is the `@Workflow` annotation on
`operator/src/main/java/org/keycloak/operator/controllers/KeycloakController.java`.
The names come from the `*_SUFFIX` constants in `operator/.../Constants.java`.

Every object carries the labels `app=keycloak`, `app.kubernetes.io/managed-by=keycloak-operator` and `app.kubernetes.io/instance=<cr-name>`.
The operator's informers filter on the first two (`DEFAULT_LABELS_AS_STRING`). That's how it watches only its own objects, not every Service in the cluster. See [watching-the-api-server.md](watching-the-api-server.md) for how informers and label selectors work.

### The reconcile loop in one diagram

```
 event (CR changed / owned object changed / timer)
   │
   ▼
 paused?  ── annotation operator.keycloak.org/pause=true ──► do nothing
   │ no
   ▼
 fill defaults (instances=1, OpenShift hostname) ──► patch CR, re-run
   │
   ▼
 build the desired StatefulSet (in memory)
   │
   ▼
 update logic: did the container change? rolling or recreate?   (§11)
   │
   ▼
 reconcile the dependents: Secret, StatefulSet, Services, Ingress, NetworkPolicy, ServiceMonitor
   │
   ▼
 write status: Ready / HasErrors / RollingUpdate, ready instances, warnings
   │
   ▼
 not ready ──► check again in 10 s   (RETRY_DURATION)
 ready + watching Secrets/ConfigMaps ──► check again in 60 s   (poll-interval-seconds)
```

**(code)** `KeycloakController.reconcile()`. The 10 s is `Constants.RETRY_DURATION`. The 60 s is `kc.operator.keycloak.poll-interval-seconds=60` in `operator/src/main/resources/application.properties`.

`generationAwareEventProcessing = false` is set on the controller "to allow for reactions to annotation changes". Normally a controller ignores updates that don't bump `metadata.generation`, and annotation edits don't bump it. Without this flag, the pause annotation would never be seen.

---

## 0.1 Are these all Kubernetes objects? Kind, resource, built-in vs custom

Yes. Anything with `apiVersion`, `kind` and `metadata` is a Kubernetes **object**: a record that the API server stores in etcd. A controller then works to make the real world match it.

```yaml
apiVersion: v1            # API group + version ("v1" = the core group, which has no name)
kind: Secret              # the type of object
metadata:                 # name, namespace, labels, annotations, ownerReferences
  name: example-kc-initial-admin
  namespace: keycloak
type: kubernetes.io/basic-auth
data: {...}               # most kinds use spec: (desired) and status: (observed) instead
```

### Built-in vs custom

The objects in this file fall into two groups.

**Built into Kubernetes.** Every cluster has these.

| kind | apiVersion | Who acts on it |
|---|---|---|
| Secret, ConfigMap, Service, Pod, ServiceAccount | `v1` | kubelet, kube-proxy, endpoints controller |
| StatefulSet, Deployment | `apps/v1` | StatefulSet / Deployment controller |
| Job | `batch/v1` | Job controller |
| Ingress, NetworkPolicy | `networking.k8s.io/v1` | the ingress controller / the CNI plugin (Kubernetes itself only stores them) |
| ClusterRole, ClusterRoleBinding | `rbac.authorization.k8s.io/v1` | the API server's authorizer |

**Custom.** These only exist after someone installs a CustomResourceDefinition (CRD).

| kind | apiVersion | Comes from | Who acts on it |
|---|---|---|---|
| Keycloak, KeycloakRealmImport | `k8s.keycloak.org/v2beta1` | Keycloak operator's CRDs | Keycloak operator |
| KeycloakOIDCClient | `k8s.keycloak.org/v2alpha1` | same | same |
| ServiceMonitor | `monitoring.coreos.com/v1` | Prometheus Operator's CRDs | Prometheus Operator |
| SecretProviderClass | `secrets-store.csi.x-k8s.io/v1` | CSI driver's CRDs ([secrets-and-env.md](secrets-and-env.md) §8.1) | CSI driver |

The API server stores both groups the same way. The difference is who acts on them.

A `kind: Keycloak` object in a cluster without the operator is just stored data that nothing acts on. A CRD that isn't installed is worse: the API server returns 404 for that kind. That's why the operator probes for the ServiceMonitor CRD before creating one (§15).

### Kind vs resource

- **Kind** is the type name written in YAML: `Secret`.
- **Resource** is the lowercase plural in the URL: `secrets`, as in `/api/v1/namespaces/<ns>/secrets`.

RBAC rules, `kubectl get` and the API all use the **resource**. That's why the operator's RBAC says `resources: pods/log`, not `kind: Pod`.

**(lab)** minikube v1.34.0:

```
$ kubectl api-resources       # NAME = resource, KIND = kind
NAME                        SHORTNAMES  APIVERSION                     NAMESPACED  KIND
configmaps                  cm          v1                             true        ConfigMap
namespaces                  ns          v1                             false       Namespace
nodes                       no          v1                             false       Node
pods                        po          v1                             true        Pod
secrets                                 v1                             true        Secret
serviceaccounts             sa          v1                             true        ServiceAccount
services                    svc         v1                             true        Service
customresourcedefinitions   crd,crds    apiextensions.k8s.io/v1        false       CustomResourceDefinition
deployments                 deploy      apps/v1                        true        Deployment
statefulsets                sts         apps/v1                        true        StatefulSet
tokenreviews                            authentication.k8s.io/v1       false       TokenReview
subjectaccessreviews                    authorization.k8s.io/v1        false       SubjectAccessReview
jobs                                    batch/v1                       true        Job
networkpolicies                         crd.projectcalico.org/v1       true        NetworkPolicy   ← Calico CRD
gateways                    gtw         gateway.networking.k8s.io/v1   true        Gateway         ← Gateway API CRD
httproutes                              gateway.networking.k8s.io/v1   true        HTTPRoute       ← Gateway API CRD
ingresses                   ing         networking.k8s.io/v1           true        Ingress
networkpolicies             netpol      networking.k8s.io/v1           true        NetworkPolicy   ← built-in
clusterrolebindings                     rbac.authorization.k8s.io/v1   false       ClusterRoleBinding
clusterroles                            rbac.authorization.k8s.io/v1   false       ClusterRole
total resources: 109   namespaced: 56   cluster-scoped: 53       (selected rows above)
```

Things to notice:

- **The same kind name can exist in two groups.** There are two `NetworkPolicy` kinds: the built-in one (`networking.k8s.io/v1`) and Calico's CRD (`crd.projectcalico.org/v1`). Only `apiVersion` tells them apart. Plain `kubectl get networkpolicies` picks the built-in one. Use `kubectl get networkpolicies.crd.projectcalico.org` for Calico's.
- **Short names** (`sts`, `svc`, `netpol`, `kc` for Keycloak) are just aliases that `kubectl` reads from the API.
- **Namespaced vs cluster-scoped.** Secret, StatefulSet and Keycloak live in a namespace. Node, Namespace, ClusterRole and CRDs themselves don't.

### Custom kinds in the lab

**(lab)** `kubectl get crd` lists only the custom kinds. This cluster has 44, all from things installed in earlier notes; none are from Keycloak:

```
$ kubectl get crd
NAME                                                    CREATED AT
backends.gateway.envoyproxy.io                          2026-11-01T03:57:33Z   ← Envoy Gateway
gatewayclasses.gateway.networking.k8s.io                2026-11-01T03:50:34Z   ← Gateway API
gateways.gateway.networking.k8s.io                      2026-11-01T03:50:37Z
httproutes.gateway.networking.k8s.io                    2026-11-01T03:57:29Z
ippools.crd.projectcalico.org                           2026-10-27T21:18:33Z   ← Calico CNI
networkpolicies.crd.projectcalico.org                   2026-10-27T21:18:34Z
clusternetworkpolicies.policy.networking.k8s.io         2026-10-27T21:18:34Z
...                                                     (44 in total)
```

A CRD's name is always `<resource>.<group>`. Once Keycloak's operator is installed, you'd see `keycloaks.k8s.keycloak.org`, `keycloakrealmimports.k8s.keycloak.org` and so on.

### Things in YAML that are not objects

**Fields inside an object.** They have no name in etcd, and you can't `kubectl get` them:

- containers, env vars, probes, volumes and `topologySpreadConstraints` (part of a Pod or StatefulSet `spec`);
- `type:` on a Secret;
- annotations such as `operator.keycloak.org/watched-secret-hash` (metadata).

**Subresources.** These are API endpoints attached to an object, not objects themselves. **(lab)**

```
$ kubectl get --raw /api/v1 | jq '[.resources[].name | select(startswith("pods/") or startswith("serviceaccounts/"))]'
["pods/attach","pods/binding","pods/ephemeralcontainers","pods/eviction","pods/exec",
 "pods/log","pods/portforward","pods/proxy","pods/resize","pods/status","serviceaccounts/token"]

$ kubectl get --raw /apis/apps/v1 | jq '[.resources[].name]'
["controllerrevisions","daemonsets","daemonsets/status","deployments","deployments/scale",
 "deployments/status","replicasets","replicasets/scale","replicasets/status",
 "statefulsets","statefulsets/scale","statefulsets/status"]
```

Each of these subresources ties to something in these notes:

- `pods/log` is what the Keycloak operator reads for its error messages (§18).
- `*/scale` is what `kubectl scale` calls. The Keycloak CRD gets one from `@SpecReplicas` (§16).
- `*/status` is why controllers update status separately from spec.
- `serviceaccounts/token` is the TokenRequest API behind projected tokens ([secrets-and-env.md](secrets-and-env.md) §7).

**Request-only kinds.** These have a `kind`, but the API server answers them and stores nothing. The only verb is `create`. **(lab)**

```
$ kubectl api-resources -o wide | grep -E '^(secrets|tokenreviews|subjectaccessreviews|bindings) '
bindings              v1                         true   Binding               create
secrets               v1                         true   Secret                create,delete,deletecollection,get,list,patch,update,watch
tokenreviews          authentication.k8s.io/v1   false  TokenReview           create
subjectaccessreviews  authorization.k8s.io/v1    false  SubjectAccessReview   create
```

A Secret supports all eight verbs, because it's stored. `TokenReview` and `SubjectAccessReview` only support `create`: you send a question and get an answer back, and nothing is saved. `Binding` is how the scheduler assigns a Pod to a node.

**List wrappers.** Asking for a collection returns a `<Kind>List`, which is also not stored. **(lab)**

```
$ kubectl get --raw /api/v1/namespaces/default/secrets | jq '{kind, items: (.items|length)}'
{"kind": "SecretList", "items": 0}
```

### Field docs from the API server

**(lab)**

```
$ kubectl explain secret.type
KIND:       Secret
VERSION:    v1

FIELD: type <string>

DESCRIPTION:
    Used to facilitate programmatic handling of secret data. More info:
    https://kubernetes.io/docs/concepts/configuration/secret/#secret-types
```

`kubectl explain` reads the cluster's OpenAPI schema, so it works for custom kinds too. Once the operator is installed, `kubectl explain keycloak.spec.update.strategy` would show the CRD's description. The Secret types themselves are covered in [secrets-and-env.md](secrets-and-env.md) §2, under "Secret types: one example of each".

---

## 1. Installing the operator

**What.** A Deployment named `keycloak-operator` that watches the Keycloak CRDs.

**How (docs, `docs/guides/operator/installation.adoc`).** There are three install paths:

- **OLM**, mainly on OpenShift. It supports manual approval of upgrades.
- **Plain kubectl**: apply the CRDs, then the operator manifest.
- **Helm**: the chart is generated by Quarkus (`quarkus.helm.*` in `application.properties`). It has a `crds.enabled` value so you can manage CRDs separately.

Scope is either **one namespace** (the default) or **cluster-wide**.

**(code)** In multi-namespace mode, the operator **clears `serviceAccountName`** on the Keycloak Pods (`createBaseDeployment`, the `isMultiNamespace` branch). It also warns if you try to set one through `podTemplate`. The reason (my reading, not stated in the code): in multi-namespace mode, a ServiceAccount name from one namespace could be pointed at an account with more rights in another namespace.

**Operator resources (code, `application.properties`):** requests 300m CPU / 450Mi, limits 700m / 450Mi.

**Extra RBAC (code, `operator/src/main/kubernetes/kubernetes.yml`):** a ClusterRole with only `get` on `config.openshift.io/ingresses`. That's used to read the OpenShift apps domain (§6). Namespace-level rights (StatefulSets, Services, Secrets and so on) come from `@RBACRule` annotations and the Quarkus Operator SDK generator. For example, `KeycloakController` declares `configmaps get/list/watch`, `pods list` and `pods/log get`.

**Why.** Keycloak needs about a dozen objects that must agree with each other: ports, labels, the Secret names in env, and probe paths that depend on TLS settings. Doing this by hand drifts. The operator also does work a static manifest can't, like checking update compatibility (§11) and restarting Pods when a Secret changes (§4).

---

## 2. Deploying Keycloak: why it's a StatefulSet

**What.** `kind: Keycloak` with `instances: N` gives you N Pods named `<name>-0` to `<name>-(N-1)`.

**How (code, `KeycloakDeploymentDependentResource.createBaseDeployment`).**

```yaml
# what the operator builds (trimmed)
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: example-kc
  annotations:
    operator.keycloak.org/migrating: "false"
spec:
  replicas: 1                          # spec.instances, defaulted to 1 by the controller
  serviceName: example-kc-discovery    # the headless discovery Service (§8)
  selector:
    matchLabels: {app: keycloak, app.kubernetes.io/managed-by: keycloak-operator, app.kubernetes.io/instance: example-kc}
  template:
    spec:
      restartPolicy: Always
      terminationGracePeriodSeconds: 30
      dnsPolicy: ClusterFirst
      topologySpreadConstraints: [zone maxSkew 1, hostname maxSkew 1, ScheduleAnyway]   # §13
      containers:
      - name: keycloak
        image: quay.io/keycloak/keycloak:nightly     # or spec.image / RELATED_IMAGE_KEYCLOAK
        imagePullPolicy: Always
        args: ["-Djgroups.bind.address=$(POD_IP)", "--verbose", "start"]   # + "--optimized" for custom images
        ports: [{name: https, 8443}, {name: http, 8080}, {name: management, 9000}]
        resources: {requests: {memory: 1700Mi}, limits: {memory: 2Gi}}   # operator defaults
        startupProbe:   {httpGet: {path: /health/started, port: 9000}, periodSeconds: 1,  failureThreshold: 600}
        readinessProbe: {httpGet: {path: /health/ready,   port: 9000}, periodSeconds: 10, failureThreshold: 3}
        livenessProbe:  {httpGet: {path: /health/live,    port: 9000}, periodSeconds: 10, failureThreshold: 3}
```

Points worth knowing, all **(code)**:

- **`--optimized` is added automatically when you set `spec.image`** (unless `startOptimized: false`). The assumption is that a custom image was pre-built with `kc.sh build`, so the build step can be skipped at startup. With the default image, there's no `--optimized`, and Keycloak runs its build step on every start.
- **`-Djgroups.bind.address=$(POD_IP)`** is always put first. The code comment says: "required for JGroups to form a cluster in IPv6 environments". `POD_IP` comes from the Downward API (`status.podIP`).
- **Probes only fill gaps.** Each probe is added only `if (!containerBuilder.hasReadinessProbe())` and so on, so a probe in `podTemplate` wins.
- **The startup probe allows 600 × 1 s = 10 minutes** before the kubelet gives up. That covers the first-boot database schema migration.

### Why a StatefulSet and not a Deployment

Keycloak itself keeps no data on disk; users and realms live in the database. So why use a StatefulSet? The code points to three reasons:

1. **Stable Pod names become cluster node names.** The operator sets `KC_CACHE_EMBEDDED_NODE_NAME` from `metadata.name` (Downward API). Infinispan, the embedded distributed cache, names each member by this. With a Deployment, each restart gives a random name, so the cluster sees a new member every time.
2. **Ordered, one-at-a-time rollouts.** A StatefulSet's default `RollingUpdate` replaces Pods one by one in reverse order (`-2`, then `-1`, then `-0`). Each Pod must be Ready before the next one goes. That gives the cache time to move data off a Pod before the next one stops (§10). See [statefulsets-and-daemonsets.md](statefulsets-and-daemonsets.md).
3. **`serviceName` gives each Pod a DNS name** through the headless discovery Service (§8).

The env-var comparison in `BaseUpdateLogic.envVars()` drops `POD_IP`, `KC_SPI_CACHE_EMBEDDED_DEFAULT_MACHINE_NAME` and `KC_CACHE_EMBEDDED_NODE_NAME` before comparing old and new containers. These differ per Pod by design, so they must not count as a change.

**(code)** In `desired()`, if an existing StatefulSet has the **old pre-v22 label selector**, the operator deletes it and creates a new one. `spec.selector` on a StatefulSet can't be changed in place, so delete-and-recreate is the only way.

---

## 3. Configuration: how CR fields become `KC_*` environment variables

Keycloak reads every option from `--option`, from `conf/keycloak.conf`, or from an env var `KC_<OPTION_IN_UPPER_SNAKE>`. The operator uses env vars only.

**How (code, `addEnvVars` and `getDefaultAndAdditionalEnvVars`, plus `KeycloakDistConfigurator`).**

```
 Keycloak CR field                       env var in the container
 ─────────────────                       ────────────────────────
 spec.hostname.hostname          ──►     KC_HOSTNAME
 spec.db.vendor / host / ...     ──►     KC_DB, KC_DB_URL_HOST, ...
 spec.db.passwordSecret          ──►     KC_DB_PASSWORD  valueFrom.secretKeyRef
 spec.http.tlsSecret             ──►     KC_HTTPS_CERTIFICATE_FILE=/mnt/certificates/tls.crt (+ mount)
 spec.proxy.headers              ──►     KC_PROXY_HEADERS
 spec.features.enabled           ──►     KC_FEATURES
 spec.additionalOptions[name=x]  ──►     KC_X  and  KCKEY_X=x
 (always)                        ──►     KC_HEALTH_ENABLED=true, KC_CACHE=ispn
 (always, Downward API)          ──►     POD_IP, KC_CACHE_EMBEDDED_NODE_NAME, KC_SPI_CACHE_EMBEDDED_DEFAULT_MACHINE_NAME
 (copied from operator's own env) ──►    HTTP_PROXY, HTTPS_PROXY, NO_PROXY
 (always)                        ──►     KC_TELEMETRY_SERVICE_NAME=<cr-name>,
                                         KC_TELEMETRY_RESOURCE_ATTRIBUTES=k8s.namespace.name=<ns>
```

**Priority when the same name appears twice (code):** the code comment says `unsupported, first class, additional, env`, and merges with `(e1, e2) -> e1`, so **the first one wins**:

1. `spec.unsupported.podTemplate` container env
2. First-class CR fields (`hostname`, `db` and so on)
3. `additionalOptions` and the defaults
4. `spec.env`

**`KCKEY_*` (code):** for each additional option, the operator also sets `KCKEY_<NAME>=<original-option-name>`. Env var names lose information: `KC_SPI_FOO_BAR` could be `spi-foo-bar` or `spi-foo.bar`. The extra variable tells Keycloak the exact option key.

**`HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY` are copied from the operator Pod (code, `COPY_ENV`).** On clusters behind a corporate egress proxy, Keycloak inherits the operator's proxy settings without you setting them twice.

**Why env vars and not a ConfigMap file?** Env vars can point at Secret keys with `secretKeyRef`, so passwords never appear in the CR or in a ConfigMap. The catch, shown in [secrets-and-env.md](secrets-and-env.md) §3, is that env vars are fixed at container start. That's why §4 exists.

**(code)** The operator also checks options. In `assumeFirstClassCitizens`, putting any option that has its own CR field (for example `db` or `hostname`) in `additionalOptions` gives the status warning "You need to specify these fields as the first-class citizen of the CR: …".

---

## 4. Secrets and ConfigMaps: how a changed Secret restarts Keycloak

**The problem.** You rotate the DB password in the Secret `keycloak-db`. The Pod's `KC_DB_PASSWORD` env var keeps the old value until the container restarts. This was shown in the lab in [secrets-and-env.md](secrets-and-env.md). Kubernetes doesn't restart Pods when a referenced Secret changes.

**How the operator solves it (code, `WatchedResources.java` plus `initialDesired`).**

```
 every reconcile (at least every 60 s while "watching"):
   1. collect every Secret/ConfigMap the Pod uses:
        secretKeyRef env vars, tlsSecret, truststore Secrets/ConfigMaps, cache ConfigMap
   2. GET each one from the API
   3. SHA-256 over their .data  ──► put on the POD TEMPLATE:
        operator.keycloak.org/watched-secret-hash: 3f9a…
        operator.keycloak.org/watched-configmap-hash: 81c2…
   4. if one is missing and not optional ──► annotate the StatefulSet
        operator.keycloak.org/missing-secrets: "keycloak-db"
      and show "The following Secrets are missing: keycloak-db" in CR status

 Secret value changes ──► hash changes ──► pod template changes
                     ──► StatefulSet controller does a rolling restart
```

This is the same "checksum annotation" trick Helm users write by hand (`checksum/config: {{ sha256sum ... }}`), automated and kept up to date.

**Why polling and not a watch?** The operator's Secret informer only sees Secrets with the `app=keycloak,...managed-by=keycloak-operator` labels (§0). Your `keycloak-db` Secret has no such label, so no event fires when it changes. Instead, `KeycloakController` reschedules itself every `pollIntervalSeconds` (60 s) while `isWatching()` is true. **Expect up to about 60 s** between a Secret change and the start of the rollout.

**(code) Exceptions to watching.** The comment says "we don't currently expect configmaps or watch the initial-admin". Only Secrets from `secretKeyRef` in env, the TLS Secret, truststores and the cache ConfigMap are hashed. Secrets you mount yourself through `podTemplate` aren't seen.

**Why it matters for Keycloak in particular.** A wrong DB password doesn't kill the Pod. The connection pool fails, and `/health/ready` (database check, §9) goes DOWN. With the hash, a corrected Secret actually reaches the Pods without someone remembering to `kubectl rollout restart`.

---

## 5. The first admin user (bootstrap admin)

**What.** A fresh Keycloak has no users, so you can't log in to create any. Keycloak solves this with "bootstrap admin" options: a temporary admin is created on first start.

**How (code, `KeycloakAdminSecretDependentResource` plus `configureBootstrapAdmin`).**

```
 spec.bootstrapAdmin.user.secret set?
   │
   ├─ yes ──► use your Secret (keys: username, password)
   │
   └─ no  ──► operator creates Secret <name>-initial-admin
                type: kubernetes.io/basic-auth
                username: temp-admin
                password: <random UUID without dashes>
   │
   ▼
 KC_BOOTSTRAP_ADMIN_USERNAME / KC_BOOTSTRAP_ADMIN_PASSWORD  ← secretKeyRef

 optional: spec.bootstrapAdmin.service.secret (keys: client-id, client-secret)
   ──► KC_BOOTSTRAP_ADMIN_CLIENT_ID / KC_BOOTSTRAP_ADMIN_CLIENT_SECRET
   (a service-account client instead of a human user; used by §12)
```

**Ordering (code, `ReconcilePrecondition`).** The StatefulSet isn't created until the generated Secret exists. The comment explains why: "to prevent the deployment from restarting". If the Pod started first, the `secretKeyRef` would fail (`CreateContainerConfigError`), and the Pod would restart once the Secret appeared. The SDK's built-in `dependsOn` isn't used, because when you bring your own Secret, the StatefulSet should be created immediately.

The generated Secret is `Creator` plus `GarbageCollected`, not updated. The operator creates it once and never rewrites it. It has an owner reference, so it's deleted with the CR.

**Why it matters (docs, `basic-deployment.adoc`).** "Change the default admin credentials and enable MFA … before going to production." Anyone who can `get secrets` in the namespace can read `temp-admin`'s password. See [secrets-and-env.md](secrets-and-env.md) §2 on Secret RBAC. Once a real admin exists, the temporary admin should be removed.

---

## 6. Getting traffic in: Service, Ingress, hostname, proxy headers

### 6.1 The Service

**(code, `KeycloakServiceDependentResource`):**

| Port name | Port | When |
|---|---|---|
| `http` | 8080 | No TLS Secret, **or** `http.httpEnabled: true` |
| `https` | 8443 | `http.tlsSecret` is set |
| `management` | 9000 | Always: health and metrics (§9) |

You can rename it with `http.serviceName` and set custom labels and annotations, for example for a cloud load balancer.

### 6.2 The Ingress (on by default)

**(code, `KeycloakIngressDependentResource`).** `isIngressEnabled` defaults to **true**. With no `ingress` block, you still get an Ingress.

```
 TLS on Keycloak (http.tlsSecret)?
   yes → annotations: nginx.ingress.kubernetes.io/backend-protocol: HTTPS
                       route.openshift.io/termination: passthrough
         backend port name: https
   no  → annotations: nginx.ingress.kubernetes.io/backend-protocol: HTTP
                       route.openshift.io/termination: edge
         backend port name: http

 rules[0].host = spec.hostname.hostname (if it's a URL, only the host part)
 tls[]         = only if ingress.tlsSecret AND a hostname are set
 path          = (none) with pathType ImplementationSpecific → everything
```

The code tries server-side apply first. If the API server returns **422** ("cannot set both port name & port number"), it retries with a client-side update. That handles old Ingresses that were written with a port number.

**OpenShift (code, `generateOpenshiftHostname`).** With `ingressClassName: openshift-default` and no hostname, the operator reads `config.openshift.io/Ingress cluster` (that's what the ClusterRole is for) and writes `<ingress-name>-<namespace>.<appsDomain>` back into the CR.

### 6.3 Three ways to terminate TLS (docs, `basic-deployment.adoc`)

```
 passthrough:  client ══TLS══════════════════════════► Keycloak (8443)
               + mTLS works (the client cert reaches Keycloak)
               − Keycloak can't see the client IP (no PROXY protocol on the default ingress)

 edge:         client ══TLS══► ingress ──plain HTTP──► Keycloak (8080)

 reencrypt:    client ══TLS══► ingress ══TLS════════► Keycloak (8443)
```

See [ingress-and-gateway-api.md](ingress-and-gateway-api.md) for these modes in general.

### 6.4 Hostname and proxy headers: why both matter

Keycloak writes its own URL into tokens (`iss`) and redirects. Behind an ingress, the Pod sees `http://10.244.0.7:8080`, but users see `https://sso.example.com`. Two settings fix this:

- **`hostname`** pins the public URL (`KC_HOSTNAME`). Tokens get a stable `iss`, no matter which Pod or Service served the request.
- **`proxy.headers: xforwarded | forwarded`** tells Keycloak to trust `X-Forwarded-*` or `Forwarded` for the client IP and scheme. If unset, the operator falls back to the legacy `proxy=passthrough`, which logs deprecation warnings **(docs)**.

**The security catch (docs).** Once Keycloak trusts forwarded headers, **any Pod that can reach port 8080 directly can fake its client IP**, which defeats brute-force detection and IP-based policies. The guide says to:

- restrict the HTTP port with the NetworkPolicy (§7) to the ingress controller's namespace;
- stop users in the namespace from creating their own Ingress or Route, which could bypass the trusted one.

"Parsing proxy headers therefore conflicts with direct backchannel access."

**`hostname-admin` (docs).** If you set a separate admin hostname, the operator does **not** create a second Ingress for it, and the default Ingress doesn't block `/admin`. Restricting admin access is left to you.

---

## 7. NetworkPolicy (on by default)

**(code, `KeycloakNetworkPolicyDependentResource`; `NetworkPolicySpec.isNetworkPolicyEnabled` defaults to `true`).**

```yaml
kind: NetworkPolicy
metadata: {name: example-kc-network-policy}
spec:
  podSelector: {matchLabels: <instance labels>}
  policyTypes: [Ingress]            # only incoming traffic is restricted
  ingress:
  - ports: [{port: 8080}]           # or 8443; "from" = networkPolicy.http(s) rules, empty = anyone
  - ports: [{port: 7800}, {port: 57800}]   # JGroups data + failure detection
    from: [{podSelector: <same instance labels>}]   # ONLY other Keycloak Pods of this CR
  - ports: [{port: 9000}]           # management; "from" = networkPolicy.management rules
```

**Why each rule exists:**

- **The JGroups ports allow only peers.** Port 7800 carries cache data, including session state. An attacker who can reach it could join the cluster or read cache traffic. The rule allows only Pods with the same instance labels. It's added only when `CRDUtils.isJGroupEnabled` is true (the cache is `ispn`).
- **The HTTP rule** is where you pin traffic to the ingress controller, closing the header-spoofing hole from §6.4.
- **The management rule** lets you limit `/metrics` and `/health` to Prometheus and the kubelet.

Probes come from the kubelet on the node. Most CNI plugins let host-to-Pod traffic through NetworkPolicy. **(not tested)** whether that holds for your CNI if you set `management` rules.

An empty `from` list allows all sources, so with no rules set, the policy only closes ports Keycloak doesn't use. It's `policyTypes: [Ingress]` only, so egress (to the database, IdPs, SMTP) isn't restricted.

---

## 8. Clustering: how Keycloak Pods find each other

Keycloak keeps sessions, login failures and action tokens in **Infinispan**, a cache embedded in each Pod and spread across Pods. Pods must find each other to form one cache cluster. Otherwise every Pod has its own sessions, and a user logged in on Pod 0 looks logged out on Pod 1.

### 8.1 The discovery Service

**(code, `KeycloakDiscoveryServiceDependentResource`):**

```yaml
kind: Service
metadata: {name: example-kc-discovery}
spec:
  clusterIP: None                  # headless: DNS returns every Pod IP, no virtual IP
  publishNotReadyAddresses: true   # include Pods that aren't Ready yet
  ports: [{name: tcp, port: 7800}]
  selector: <instance labels>
```

- **Why headless.** A ClusterIP would load-balance to *one* Pod. Discovery needs *all* Pod IPs. See [kubernetes-services.md](kubernetes-services.md) for headless Services.
- **Why `publishNotReadyAddresses: true`.** This avoids a chicken-and-egg problem. A Keycloak Pod isn't Ready until it has joined the cluster and the caches are up (the readiness check includes "Cluster", §9). Its peers can't find it until it's in DNS. If DNS showed only Ready Pods, a new Pod could never be found, and on a cold start no Pod would ever become Ready.
- It's also the StatefulSet's `serviceName`, which gives Pods DNS names like `example-kc-0.example-kc-discovery.<ns>.svc`.

### 8.2 Which discovery mechanism is actually used (`jdbc-ping`)

**(docs, `server/caching.adoc`; code, `CachingOptions.Stack`).** The **default stack is `jdbc-ping`**: each node writes its address into a table in the **Keycloak database**, and reads the others from it.

The `kubernetes` stack (JGroups `DNS_PING`, which queries the headless Service) still exists, but is **deprecated**: "Use 'jdbc-ping' instead by leaving it unset".

```
 jdbc-ping (default)                           kubernetes / DNS_PING (deprecated)
 ───────────────────                           ──────────────────────────────────
 Pod-0 ──INSERT my IP:7800──► DB table          Pod-0 ──DNS A query──► <name>-discovery
 Pod-1 ──SELECT members ────► DB table                ◄── 10.0.0.5, 10.0.0.6, 10.0.0.7
 Pod-1 ──TCP 7800──► Pod-0                      Pod-0 ──TCP 7800──► each IP
```

Why the database won, in my reading: Keycloak needs the DB anyway, so it works the same on Kubernetes, VMs and bare metal, and it doesn't depend on DNS caching or `publishNotReadyAddresses`. The discovery Service is still created, and still useful for per-Pod DNS names.

**(docs) Cluster name collision.** Two separate Keycloak deployments sharing **one database** will **merge into one cluster**, because both register in the same table under the default cluster name `ISPN`. Set `cache-embedded-cluster-name` to keep them apart.

### 8.3 Ports and identity

| Port | What | Source |
|---|---|---|
| 7800 | JGroups unicast data (`jgroups.bind.port`) | (docs, code `KEYCLOAK_JGROUPS_DATA_PORT`) |
| 57800 | `FD_SOCK2` failure detection (7800 + offset 50000) | (docs, code `KEYCLOAK_JGROUPS_FD_PORT`) |

Failure detection works by noticing an abrupt socket close. When a Pod is killed, the others see the socket drop on 57800 and suspect it right away, without waiting for heartbeat timeouts.

**Machine name = node name (code).** `KC_SPI_CACHE_EMBEDDED_DEFAULT_MACHINE_NAME` comes from `spec.nodeName`. Infinispan uses the machine name to avoid putting both copies of an entry (`num_owners=2`) on the same machine. If two Keycloak Pods land on one node and that node dies, both copies would be lost. The code comment on why it's `nodeName` and not `hostIP`: "Using spec.nodeName to avoid exposing the IP addresses in the logs."

**Site name isn't set (docs).** Zone awareness can't be configured automatically, because "its value is not available via the Downward API". The Downward API can't read node labels such as `topology.kubernetes.io/zone`.

### 8.4 Custom cache config

`spec.cache.configMapFile: {name, key}` **(code, `configureCache`)** mounts the ConfigMap at `/opt/keycloak/conf/cache`, sets `cache-config-file=cache/<key>`, and adds it to the hashed ConfigMaps (§4). Editing the cache XML then triggers a rolling restart.

---

## 9. Health probes and the management port

**(code)** Probes go to **port 9000** (the management interface), not to 8080/8443. The only exceptions are when you turn on `legacy-observability-interface`, or set `http-management-health-enabled=false` (then they go to the main port). The scheme follows TLS: with a TLS Secret, the probes use HTTPS on 9000, unless `http-management-scheme=http`. Paths respect `http-management-relative-path` or `http-relative-path`.

**Why a separate port.** Health and metrics shouldn't be reachable through the public Ingress. The Ingress points at port name `http`/`https` only, and 9000 can be firewalled separately (§7).

| Probe | Path | Default timing (code) | What it means |
|---|---|---|---|
| startup | `/health/started` | every 1 s, 600 failures = 10 min | Server finished booting, including DB migrations. Liveness waits for this. |
| readiness | `/health/ready` | every 10 s, 3 failures | Can take traffic now. Fails → removed from Service endpoints, **not** restarted. |
| liveness | `/health/live` | every 10 s, 3 failures | Process is alive. Fails → kubelet restarts the container. |

**(docs, `observability/health.adoc`) What `/health/ready` checks:**

| Check | Notes |
|---|---|
| Database | Connection pool status. Needs `metrics-enabled`. |
| Cluster | Network partitions. |
| Graceful shutdown | Goes DOWN as soon as shutdown starts (§10). |
| Keycloak initialized | Server initialization finished. |

So a database outage makes Pods **not ready**, but doesn't restart them. That's the right behaviour: restarting won't fix the database, and a crash loop would only add load.

The Keycloak image has no `curl`. The docs give a bash `/dev/tcp` one-liner for exec probes, if you need one.

---

## 10. Graceful shutdown: SIGTERM, `exec`, and the shutdown delay

When a Pod is deleted (rollout, scale-down, node drain), Kubernetes does two things **at the same time**:

- sends SIGTERM to the container;
- removes the Pod from Service endpoints, which takes a moment to reach every kube-proxy and ingress controller.

```
 t=0   Pod deleted ──► SIGTERM to Keycloak ─┐
       endpoints controller removes Pod     │  (race: LB may still send requests)
                                            ▼
 0 … shutdown-delay (default 1s)   PRE-SHUTDOWN
       /health/ready → DOWN ("Graceful shutdown" check)
       still serving requests; keepalive connections drain
                                            ▼
 … shutdown-timeout (default 10s)  wait for in-flight HTTP requests,
       then use the remaining time for caches to rebalance (move data off this node)
                                            ▼
       "Keycloak stopped in …s"   ← log line that proves it was graceful
 t=30s terminationGracePeriodSeconds (operator default) → SIGKILL if still running
```

**(docs, `server/reverseproxy.adoc`, "Graceful HTTP shutdown")** for the phases and defaults. **(code)** for the 30 s grace period.

**Why each piece matters:**

- **The pre-shutdown delay** covers the race. Without it, Keycloak would stop accepting connections while the load balancer still sends it traffic, and users would get 502s. For TLS passthrough through HAProxy, the docs say to set `--shutdown-delay=30s` to match the load balancer's health-check detection time. In that case, raise `terminationGracePeriodSeconds` too, so delay + timeout fit inside it **(not tested)**.
- **Cache rebalancing** during shutdown is why a hard kill loses data. With `num_owners=2`, killing two Pods at once before rebalancing can drop sessions (§13).
- **Custom entrypoints must `exec` (docs, `server/containers.adoc`).** "Without `exec`, the shell script remains PID 1 in the container and blocks signals like `SIGTERM` from reaching Keycloak." This is the same PID 1 behaviour seen in the lab in [secrets-and-env.md](secrets-and-env.md): `kill 1` did nothing, because PID 1 ignores signals it has no handler for. A shell wrapper that doesn't `exec` means Keycloak never gets SIGTERM. It then gets SIGKILLed at 30 s with no graceful shutdown.

**(docs, `operator/rolling-updates.adoc`) Prerequisites for rolling updates:** check for the "Keycloak stopped in" log line, and check that the load balancer honours readiness.

---

## 11. Updates: rolling vs recreate, and the update-compatibility Job

**The problem.** A rolling update runs old and new Keycloak versions **side by side**, in the same Infinispan cluster, against the same database. That's only safe if the cache format and DB schema are compatible. A major upgrade usually isn't, so all Pods must stop first (recreate).

**How the operator decides (code, `update/impl/*`).**

```
 desired container (image, args, env) == current?  ── yes ──► nothing to decide
   │ no                     (also skipped if update-hash annotation matches)
   ▼
 spec.update.strategy
   │
   ├─ RecreateOnImageChange (DEFAULT)
   │     image changed?  yes → RECREATE      no → ROLLING
   │
   ├─ Explicit
   │     spec.update.revision == StatefulSet's update-revision annotation?
   │        yes → ROLLING     no / missing → RECREATE
   │
   └─ Auto
         create Job <name>-update-job:
           initContainer "actual"  = OLD image/config:  kc.sh update-compatibility metadata --file /mnt/.../updates.json
           container     "desired" = NEW image/config:  kc.sh update-compatibility check    --file /mnt/.../updates.json
           (shared emptyDir, backoffLimit 0, TTL 30 min, activeDeadlineSeconds 300)
         exit code of "desired":
           0 → ROLLING   ("Compatible changes detected.")
           3 → RECREATE  ("Incompatible changes detected.")
           4 → RECREATE  ("Feature 'rolling-update' not enabled.")
           1/2/other/crash → RECREATE (play safe)
```

**What RECREATE looks like (code, `handleRecreateUpdate`):**

1. Desired StatefulSet = **old image** plus `replicas: 0` plus annotation `migrating=true`. All Pods stop, with status "Performing Keycloak update, scaling down the deployment".
2. Next reconcile: `status.replicas == 0`, so apply the **new** desired state with the real replica count. New Pods start, and the first one migrates the DB schema.

The old image is kept during the scale-down on purpose. Otherwise the StatefulSet controller would start rolling Pods to the new image while also scaling down.

**What ROLLING looks like.** The operator just writes the new StatefulSet. Kubernetes' own `RollingUpdate` replaces Pods one at a time (§2).

**The update hash (code, `keycloakHash`).** This is a SHA-256 of the CR spec **with these fields removed**: instances, probes, resources, scheduling, networkPolicy, ingress, imagePullSecrets, import and serviceMonitor. Changing only those fields never triggers the compatibility Job. They don't affect what runs inside the JVM's cache and schema.

**Why the Job copies the Pod spec.** The check must run with the *exact* image, env, Secrets, volumes and DB access of the real Pods. The Job takes the desired Pod template and removes probes, lifecycle hooks and `kube-api*` mounts. The old container's volumes and pull secrets are merged in so both containers can start.

**(docs) Status.** The condition `RecreateUpdateUsed` is `False` (rolling), `True` (recreate, with the reason in `message`), or `Unknown` (no update yet).

**(docs) Warning.** With `Explicit`, an automatic operator upgrade through OLM can change the image while `revision` stays the same. The operator then attempts a rolling update across versions that may not support it.

---

## 12. Realm import as a Kubernetes Job

**What.** `KeycloakRealmImport` lets you declare a realm (clients, roles, users and so on) in YAML.

**How (code, `KeycloakRealmImportController`, `...SecretDependentResource`, `...JobDependentResource`).**

```
 KeycloakRealmImport (spec.keycloakCRName, spec.realm: {...})
   │
   ├─ wait until the Keycloak StatefulSet has ≥1 ready replica
   │
   ├─► Secret <kc>-<realm>-realm   data: <realm>-realm.json = spec.realm as JSON
   │
   └─► Job <import-name>   (template copied from the Keycloak StatefulSet's Pod template)
         command: /opt/keycloak/bin/kc.sh --verbose import
                    --file=/mnt/realm-import/<realm>-realm.json --override=false
         env: KC_CACHE=local   (KC_CACHE_STACK and KC_HEALTH_ENABLED removed)
         + placeholders: env vars from Secrets (spec.placeholders)
         probes removed, restartPolicy Never
```

**Why each detail:**

- **The realm JSON goes into a Secret, not a ConfigMap.** Realm exports contain client secrets and sometimes user credentials.
- **It copies the server's Pod template.** The import needs the same image (with custom providers), the same DB env and Secrets, and the same truststores.
- **`KC_CACHE=local`.** The import Pod mustn't join the Infinispan cluster of the live Pods. It only writes to the DB.
- **`--override=false`.** If the realm already exists, it isn't overwritten. This makes the CR a "create once" tool, **not** a sync tool.
- **It waits for a ready replica.** That way, the first server Pod has already run the DB schema migration before the import touches the DB.
- **The Job is created once and never recreated (code).** The logs say "Job already executed - not recreating". Editing the CR later does nothing for an existing Job. You'd delete the CR (or Job) and apply it again.

**Placeholders (docs).** `${ENV_KEY}` in the realm JSON is replaced from env vars filled from `spec.placeholders.<ENV_KEY>.secret`. You can commit the realm YAML to Git without the secrets in it. **Security (docs):** "Placeholder replacement gives access to all environment variables even sensitive ones". Anyone who can write this CR can read, for example, `KC_DB_PASSWORD` into a realm attribute. Treat the CR as namespace-admin only.

---

## 13. Clients as Kubernetes resources (`KeycloakOIDCClient`, `KeycloakSAMLClient`)

**What.** You declare a Keycloak client next to the app that uses it, in the app's namespace, as a CR. This is GitOps for clients.

**How (code, `KeycloakClientBaseController`, `KeycloakOIDCClientController`).** Unlike realm import, this talks to the **running server's admin REST API**, not to the DB:

```
 KeycloakOIDCClient my-app  (spec.keycloakCRName, spec.realm, spec.client)
   │
   ├─ Keycloak CR has feature client-admin-api:v2?   no → status HasErrors
   ├─ ≥1 ready replica?                              no → error, retry in 10 s
   │
   ├─ read Secret <kc>-admin  (client-id, client-secret)   ← you create this
   ├─ client-credentials token from realm "master"
   │    at  http(s)://<kc>-service.<ns>.svc:<port>/<relative-path>
   │    (HTTPS: trusts the cert in the Keycloak TLS Secret)
   │
   ├─ hash(desired client) == status.hash?  yes → skip   (no API call)
   └─ PUT admin/api/<realm>/clients/<cr-name>  (create or update)
        status.uuid = server's client UUID

 delete CR ──► finalizer (Cleaner) ──► DELETE client on server (404 is fine) ──► CR removed
```

Points, all **(code)** unless marked:

- **The client ID is the CR name** (`rep.setClientId(resource.getMetadata().getName())`).
- **It uses the Service's DNS name** (`<svc>.<ns>.svc`). The comment says this "assumes the operator and the keycloak instance are in the same cluster".
- **Client secrets from Kubernetes Secrets.** `auth.secretRef` reads a Secret that **must carry the label `operator.keycloak.org/kind: KeycloakOIDCClient`**. Without it, the operator treats the Secret as not found. This opt-in label stops a CR author from pointing `secretRef` at an unrelated Secret, such as the DB password, and copying it into a client. With a `secretRef`, the controller polls every 60 s, so a rotated Secret reaches the server within about a minute.
- **The finalizer** means deleting the CR deletes the client on the server. If the Keycloak CR is already gone, the finalizer simply lets go.

**Why.** Before this, clients were made with realm import (create-once only, §12) or by clicking in the admin console. The CR gives create, update, delete and drift correction from Git.

**Security (docs, `managing-clients.adoc`).** "Anyone with the ability to create or edit KeycloakOIDCClient or KeycloakSAMLClient CRs will have the full ability to create or edit clients that the admin credentials provide."

---

## 14. Truststores: trusting internal CAs and the cluster CA

**Problem.** Keycloak makes outgoing HTTPS calls: to the database (TLS), external IdPs, LDAP, SMTP, webhooks and the Kubernetes API (§17). Inside a cluster, these often use certificates from a private CA that the JVM doesn't trust.

**How (code).**

- `spec.truststores.<name>.secret` or `.configMap` is mounted at `/opt/keycloak/conf/truststores/secret-<name>` or `configmap-<name>` (`addTruststores`). Keycloak loads PEM and unencrypted PKCS12 files from `conf/truststores` and its subdirectories **(docs, `server/keycloak-truststore.adoc`)**. These are also hashed (§4), so updating a CA bundle triggers a rolling restart.
- **The cluster's own CA is trusted automatically (code, `services/.../truststore/TruststoreBuilder.java`; option `truststore-kubernetes-enabled`, default `true`).** Keycloak adds:
  - `/var/run/secrets/kubernetes.io/serviceaccount/ca.crt`: the API server's CA;
  - `/var/run/secrets/kubernetes.io/serviceaccount/service-ca.crt`: the OpenShift service-serving CA.
- If you set `spec.automountServiceAccountToken: false`, the operator also sets `KC_TRUSTSTORE_KUBERNETES_ENABLED=false` **(code)**. Those files won't exist, so there's nothing to load.

**Why it matters.** On OpenShift, any Service can get a cert signed by the service CA. Keycloak trusts it automatically, so a DB or IdP inside the cluster with a service-CA cert just works.

---

## 15. Metrics, ServiceMonitor and tracing

**(code, `KeycloakServiceMonitorDependentResource`)** creates a Prometheus Operator `ServiceMonitor` **only if all of these are true**:

1. `serviceMonitor.enabled` (default true);
2. `metrics-enabled=true` in the Keycloak options. Otherwise the status warns: "A ServiceMonitor will not be created because `metrics-enabled` is not true".
3. The ServiceMonitor CRD exists. The operator checks by opening a watch: a 404 means it's not installed. **The result is cached for the operator's lifetime.** If you install Prometheus Operator later, "the operator will need to be restarted".

```yaml
kind: ServiceMonitor
spec:
  namespaceSelector: {matchNames: [<ns>]}
  selector: {matchLabels: <instance labels>}
  scrapeProtocols: [OpenMetricsText1.0.0]   # needed for exemplars (trace IDs on metrics)
  endpoints:
  - port: management                        # 9000
    path: /metrics
    scheme: https|http
    interval: 30s
    tlsConfig: {insecureSkipVerify: true}   # Keycloak's cert is usually for the public hostname
```

**Tracing and telemetry (code).** The operator always sets `KC_TELEMETRY_SERVICE_NAME=<cr-name>` and adds `k8s.namespace.name=<ns>` to `KC_TELEMETRY_RESOURCE_ATTRIBUTES`. Spans and metrics from two Keycloaks with the same name in different namespaces stay apart. This follows the OpenTelemetry Kubernetes semantic conventions, as the code comment notes.

---

## 16. Scaling, scheduling and high availability

### 16.1 Scaling

**(code)** `KeycloakSpec.instances` is marked `@SpecReplicas`. `KeycloakStatus` has `@StatusReplicas instances` and `@LabelSelector selector`. Together, these generate the CRD's **`scale` subresource**, so:

```bash
kubectl scale kc/example-kc --replicas=3      # works on the CR itself
```

A HorizontalPodAutoscaler can target `kind: Keycloak` directly **(not tested)**. The HPA finds Pods through `status.selector` to read their metrics. If you scaled the StatefulSet directly, the operator would scale it back to `spec.instances` on the next reconcile.

### 16.2 Scheduling (code, `handleScheduling`)

`spec.scheduling` (priorityClassName, affinity, tolerations, topologySpreadConstraints) applies to the Pods. If you set no spread constraints, the operator adds two defaults:

```yaml
topologySpreadConstraints:
- {maxSkew: 1, topologyKey: topology.kubernetes.io/zone, whenUnsatisfiable: ScheduleAnyway, ...}
- {maxSkew: 1, topologyKey: kubernetes.io/hostname,      whenUnsatisfiable: ScheduleAnyway, ...}
```

The update Job and the import Job reuse their own `scheduling` block. If they have none, they take the Keycloak CR's scheduling.

### 16.3 Why the spread matters (docs, `high-availability/single-cluster/concepts.adoc`)

Session-like caches (`authenticationSessions`, `loginFailures`, `actionTokens`) keep **`num_owners=2`** copies. If two nodes holding both copies fail together, those entries are lost. `ScheduleAnyway` is a *preference*: two Pods can still share a node or zone. The guide's options:

- `whenUnsatisfiable: DoNotSchedule` gives a strict spread, but Pods may stay Pending.
- Required anti-affinity caps instances at the number of nodes or zones.

**The database must also survive a zone failure.** Keycloak is only as available as its DB. The guides use **CloudNativePG** (`single-cluster/deploy-cnpg*.adoc`) or **AWS Aurora**. The operator **does not manage the database** **(docs)**.

### 16.4 Multi-cluster (docs, brief)

`high-availability/multi-cluster*` covers two Kubernetes clusters (sites) with an external Infinispan cross-site setup and a global load balancer, for example AWS Global Accelerator with a fencing Lambda. That's beyond these notes. The key Kubernetes point is that each site runs its own operator and Keycloak CR, and the sites are linked through Infinispan and a replicated DB, not through Kubernetes.

---

## 17. Workloads logging in with their Kubernetes ServiceAccount (no client secret)

This is the server-side use case most closely tied to [secrets-and-env.md](secrets-and-env.md) §7, "secret zero". It's on by default (`kubernetes-service-accounts` and `client-auth-federated` are `Type.DEFAULT` in `common/.../Profile.java`).

**Problem.** An app in the cluster needs an access token from Keycloak (`client_credentials`). Normally that needs a **client secret**, which then has to be stored, rotated and kept out of Git.

**Solution.** The Pod already has a signed identity: its **projected ServiceAccount token** (a JWT with `iss`, `sub=system:serviceaccount:<ns>:<sa>`, `aud` and `exp`). Keycloak can verify that JWT against the cluster's public keys and accept it *as* the client credential.

```
   Pod (sa: billing)                                  Keycloak realm "test"
   ─────────────────                                  ─────────────────────
 kubelet mounts projected token
   aud = https://sso.example.com/realms/test
   exp ≤ 3600 s
        │
        │ POST /realms/test/protocol/openid-connect/token
        │   grant_type=client_credentials
        │   client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer
        │   client_assertion=<the SA token>
        ▼
                                           FederatedJWTClientAuthenticator ("Signed JWT - Federated")
                                             1. find the IdP whose issuer == token iss
                                                and the client whose federated subject == token sub
                                             2. KubernetesIdentityProvider.verifyClientAssertion:
                                                 - signature: JWKS from <issuer>/.well-known/openid-configuration
                                                   (Keycloak sends ITS OWN SA token if iss matches)
                                                 - aud == this realm's issuer URL (only one audience allowed)
                                                 - exp − iat ≤ 3600 s; reuse allowed
        ◄──────────── access_token ──────────────
```

**(code)** `services/src/main/java/org/keycloak/broker/kubernetes/*`, `authentication/authenticators/client/FederatedJWTClientAuthenticator.java` and `FederatedJWTClientValidator.java`. **(docs)** `server_admin/topics/identity-broker/kubernetes.adoc`.

Details from the code:

- **Default issuer** is `https://kubernetes.default.svc.cluster.local` (`KubernetesConstants`). On managed clusters (EKS, AKS, GKE), the issuer is a public URL, for example an S3 or OIDC endpoint. You either type it in, or set **Issuer discovery URL**. Keycloak then fetches `<url>/.well-known/openid-configuration` **when the IdP is saved**, and stores both `issuer` and `jwks_uri`.
- **How Keycloak reaches a private JWKS.** In-cluster discovery (`https://kubernetes.default.svc`) requires auth. Keycloak reads `/var/run/secrets/kubernetes.io/serviceaccount/token` and sends it **only if that token's own `iss` equals the configured issuer** (`KubernetesUtils.getServiceAccountToken(issuer)`). So it never leaks its own token to a third-party URL. This is why §14's automatic trust of `ca.crt` matters: that call is HTTPS to the API server.
  - This works around the gap found in the lab ([secrets-and-env.md](secrets-and-env.md) §7): minikube's `jwks_uri` is private, so cloud providers can't federate with it. Keycloak running *inside* the same cluster can reach it.
- **Audience = realm issuer URL**, one audience only (`isMultipleAudienceAllowed() == false`). A token minted for another service (a different `aud`) is refused. That's why the Pod needs a *projected* token with a custom `audience`, not the default one, whose `aud` is the API server.
- **Max lifetime 3600 s.** The comment says "Kubernetes defaults to 1 hour".
- **Reuse is permitted** (`reusePermitted = true`): the same token can be used many times until it expires. The docs warn to keep the expiry short and protect the token in transit.
- **Subject = namespace + SA name.** The docs: give **each client its own ServiceAccount**, and **never use `default`**, which every Pod in the namespace shares.

**Why this matters.** There's no client secret to create, store in a Secret, rotate or leak. The kubelet rotates the token, and Kubernetes RBAC on "who can run Pods as SA `billing`" becomes the access control. It's the same idea as IRSA or Workload Identity in [secrets-and-env.md](secrets-and-env.md), with Keycloak in the role of the cloud's STS.

---

## 18. Day-2 controls and guardrails

| Control | How | Why |
|---|---|---|
| **Pause reconciliation** | Annotate the CR `operator.keycloak.org/pause: "true"`. The operator adds `paused: "true"` and does nothing. Remove it to resume. **(code)** | Debug or hand-edit objects without the operator reverting them. |
| **`spec.unsupported.podTemplate`** | Merged under the operator's Pod spec. Forced fields win: container name, image, resources and imagePullSecrets produce status warnings if you set them there. **(code, `validatePodTemplate`)** | Escape hatch for sidecars, extra volumes and so on. "Unsupported" because the Auto update Job may misjudge compatibility from it **(docs)**. |
| **Errors surfaced in status** | If a Pod is in `CrashLoopBackOff` or `*Err*`, the operator reads `pods/log?previous=true&tailLines=200`, keeps the last 2,000 characters, and puts them in the CR status. **(code, `checkForPodErrors`)** | `kubectl get kc -o yaml` shows *why* Keycloak won't start, without `kubectl logs`. That's why the operator needs the `pods/log` RBAC. |
| **`automountServiceAccountToken`** | Defaults to true. Set false to drop the token, which also disables §14's cluster-CA trust. **(code)** | Least privilege, if the server never talks to the API server. It breaks §17's in-cluster discovery. |
| **Resources** | Default requests 1700Mi, limits 2Gi memory; no CPU defaults. **(code, `application.properties`)** | Sizing guidance is in `high-availability/*/concepts-memory-and-cpu-sizing.adoc`. |

**Who may edit the CRs (docs, `basic-deployment.adoc`).** "Anyone with the ability to create or edit the CRs used by the operator … should be a namespace-level admin." The reasons:

- `spec.image` decides what code runs with all the Secrets in env.
- `podTemplate` can launch arbitrary workloads with the operator's permissions.
- Realm-import placeholders can read any env var (§12).
- Client CRs act with the admin credentials (§13).

This is the same "create-Pod = read-Secret" RBAC hole shown in the lab in [secrets-and-env.md](secrets-and-env.md), one level up.

---

## 19. Summary: use case → Kubernetes feature → why

| # | Use case | Kubernetes feature used | Why it's needed |
|---|---|---|---|
| 1 | Install operator | Deployment, CRDs, RBAC, OLM/Helm | One controller keeps about 10 objects consistent |
| 2 | Run Keycloak | **StatefulSet** | Stable Pod names = cache node names; ordered rollouts |
| 3 | Configure | Env vars, `secretKeyRef`, Downward API | No passwords in CR; per-Pod IP, name, node |
| 4 | Pick up Secret changes | Pod-template **hash annotation** + 60 s poll | Env vars don't update; Kubernetes doesn't restart on Secret change |
| 5 | First admin | Generated `basic-auth` **Secret** | Empty server has no user to log in with |
| 6 | Expose | **Service**, **Ingress**, hostname, proxy headers | Correct public URL in tokens; TLS mode choice |
| 7 | Lock down | **NetworkPolicy** (on by default) | Stop header spoofing; JGroups only from peers |
| 8 | Cluster the cache | **Headless Service** + `publishNotReadyAddresses`; `jdbc-ping` | Shared sessions across Pods; no chicken-and-egg on start |
| 9 | Health | **startup/readiness/liveness** on port **9000** | 10 min for migrations; DB down → not ready, not restarted |
| 10 | Clean stop | SIGTERM, `terminationGracePeriodSeconds`, readiness DOWN | No 502s; caches rebalance before exit |
| 11 | Upgrade | StatefulSet rolling update, or scale-to-0; **Job** compatibility check | Mixed versions must share cache and DB safely |
| 12 | Seed realms | **Job** + Secret | Declarative realm, created once, never overwritten |
| 13 | Manage clients | CR + **finalizer** → admin API | GitOps for clients, cleanup on delete |
| 14 | Trust CAs | Secret/ConfigMap mounts; SA `ca.crt` / `service-ca.crt` | Private CAs inside the cluster |
| 15 | Monitor | **ServiceMonitor** (if CRD present) | Prometheus scrapes management port |
| 16 | Scale and HA | **scale subresource**, topology spread | `kubectl scale kc`, HPA; spread cache owners |
| 17 | Workload login without secrets | **Projected SA token** + issuer JWKS | No client secret: Kubernetes identity is the credential |
| 18 | Operate safely | Pause annotation, status with Pod logs, RBAC guidance | Debugging; CR editors are effectively admins |

---

## Sources

All paths are relative to the root of the Keycloak repository.

- Operator:
  - `operator/src/main/java/org/keycloak/operator/Constants.java`
  - `operator/src/main/java/org/keycloak/operator/Config.java`
  - `operator/src/main/java/org/keycloak/operator/Utils.java`
  - `operator/src/main/resources/application.properties`
  - `operator/src/main/kubernetes/kubernetes.yml`
- Operator controllers (`operator/src/main/java/org/keycloak/operator/controllers/`):
  - `KeycloakController`, `KeycloakDeploymentDependentResource`, `KeycloakServiceDependentResource`, `KeycloakDiscoveryServiceDependentResource`
  - `KeycloakIngressDependentResource`, `KeycloakNetworkPolicyDependentResource`, `KeycloakAdminSecretDependentResource`, `WatchedResources`
  - `KeycloakDistConfigurator`, `KeycloakUpdateJobDependentResource`, `KeycloakRealmImport*`, `KeycloakServiceMonitorDependentResource`
  - `KeycloakClientBaseController`, `KeycloakOIDCClientController`
- Update logic: `operator/src/main/java/org/keycloak/operator/update/` (`UpdateLogicFactory`, `impl/BaseUpdateLogic`, `impl/AutoUpdateLogic`, `impl/RecreateOnImageChangeUpdateLogic`, `impl/ExplicitUpdateLogic`)
- CRD classes: `operator/src/main/java/org/keycloak/operator/crds/v2beta1/deployment/` (`Keycloak`, `KeycloakSpec`, `KeycloakStatus`, `spec/UpdateSpec`, `spec/NetworkPolicySpec`, `spec/BootstrapAdminSpec`, `spec/ServiceMonitorSpec`)
- Server:
  - `services/src/main/java/org/keycloak/broker/kubernetes/*`
  - `services/src/main/java/org/keycloak/authentication/authenticators/client/FederatedJWTClient{Authenticator,Validator}.java`
  - `services/src/main/java/org/keycloak/truststore/TruststoreBuilder.java`
  - `quarkus/config-api/src/main/java/org/keycloak/config/{TruststoreOptions,CachingOptions}.java`
  - `common/src/main/java/org/keycloak/common/Profile.java`
- Guides:
  - `docs/guides/operator/{installation,basic-deployment,advanced-configuration,rolling-updates,realm-import,managing-clients}.adoc`
  - `docs/guides/server/{caching,containers,reverseproxy,haproxy-passthrough}.adoc`
  - `docs/guides/observability/health.adoc`
  - `docs/guides/high-availability/single-cluster/concepts.adoc`
  - `docs/documentation/server_admin/topics/identity-broker/kubernetes.adoc`
  - `docs/documentation/server_admin/topics/clients/oidc/con-confidential-client-credentials.adoc`
