# Deployments and ReplicaSets

A **ReplicaSet** keeps N copies of one Pod template running. A **Deployment** manages ReplicaSets: one per version of the Pod template. A rollout means scaling the new ReplicaSet up and the old one down. You almost never create a ReplicaSet yourself. You create a Deployment, and it creates them.

---

## 0. Terms

| Term | Meaning |
|---|---|
| **Pod template** | `spec.template` in a Deployment or ReplicaSet: the Pod to stamp out (labels, containers, env, volumes...). |
| **selector** | `spec.selector`: which Pods count as "mine". It must match the template's labels. |
| **ownerReference** | A field in `metadata` that points to an object's parent. The garbage collector deletes children when the parent goes (unless you orphan them, §6). `controller: true` marks the one parent that manages the object. |
| **pod-template-hash** | A label the Deployment controller adds to each ReplicaSet and its Pods. It's a hash of the Pod template, so each version gets a unique value. |
| **revision** | The annotation `deployment.kubernetes.io/revision` on each ReplicaSet: which rollout it was. `kubectl rollout history` reads it. |
| **available** | A Pod that is Ready and has been for `minReadySeconds` (default 0). Rollout limits count available Pods. |
| **rollout** | Moving the Pods from the old template to the new one. |

---

## 1. The three layers

```
 Deployment web                       you write this
   spec.replicas: 4
   spec.selector: app=web
   spec.template: (containers, env VERSION=v1 ...)
   spec.strategy: RollingUpdate 25% / 25%
        |
        | owns (ownerReference, controller=true)
        | one ReplicaSet per distinct Pod template
        v
 ReplicaSet web-d858b6555             the Deployment controller writes this
   spec.replicas: 4                   <- set by the Deployment, not by you
   spec.selector: app=web, pod-template-hash=d858b6555
   spec.template: copy of the Deployment's, plus the hash label
        |
        | owns
        v
 Pod web-d858b6555-c29f9    Pod web-d858b6555-ckz4j   ...   the ReplicaSet controller writes these
   labels: app=web, pod-template-hash=d858b6555
```

There are two separate controllers inside kube-controller-manager, each watching the API server (see [watching-the-api-server.md](watching-the-api-server.md)):

| Controller | Watches | Writes | Its single job |
|---|---|---|---|
| **deployment** | Deployments, ReplicaSets, Pods | ReplicaSets (create, scale, delete old) | Decide **how many** Pods each version should have |
| **replicaset** | ReplicaSets, Pods | Pods (create, delete) | Make the number of Pods match `spec.replicas` |

The Deployment controller never creates a Pod. The ReplicaSet controller doesn't know rollouts exist.

### 1.0 Is a Deployment the minimum manifest?

No. Every manifest needs only four things: `apiVersion`, `kind`, `metadata.name`, and whatever that kind requires. The smallest object that **runs** something is a Pod, and the API server accepted this 5-line one:

```yaml
apiVersion: v1
kind: Pod
metadata: {name: tiny}
spec:
  containers: [{name: app, image: nginx}]
```

What each kind requires, tested with `kubectl apply --dry-run=server` on the lab:

| Manifest | Minimum | What happens when something is missing |
|---|---|---|
| ConfigMap | `apiVersion`, `kind`, `metadata.name`. Nothing else. | n/a: accepted with only a name. It doesn't run anything. |
| Pod | a name plus one container with `name` and `image` | no image: `spec.containers[0].image: Required value`. No containers: `spec.containers: Required value`. |
| Deployment | the Pod's needs, inside `template`, plus a `selector` that matches the template's labels | no selector: `spec.selector: Required value` and ``selector` does not match template `labels`` |

The minimal Deployment is about twice the size, because it wraps the Pod:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: {name: tiny}
spec:
  selector: {matchLabels: {app: tiny}}       ← which Pods are "mine"
  template:                                  ← the Pod, as a template
    metadata: {labels: {app: tiny}}          ← must match the selector
    spec: {containers: [{name: app, image: nginx}]}
```

**The API server fills in the rest.** The 5-line Pod came back from the dry run with 13 `spec` fields:

```
containers, dnsPolicy, enableServiceLinks, preemptionPolicy, priority, restartPolicy, schedulerName,
securityContext, serviceAccount, serviceAccountName, terminationGracePeriodSeconds, tolerations, volumes
```

The container also got `imagePullPolicy`, `resources`, `terminationMessagePath`, `terminationMessagePolicy` and `volumeMounts`. Small manifests work because of defaults. §1.6 shows the Deployment's.

**So why does everyone write Deployments? Because a bare Pod has no owner:**

```
 Pod alone:     delete it / node dies  →  gone. Nobody recreates it. Changing the image means delete + create.
 Deployment:    Deployment → ReplicaSet → Pods
                delete a Pod / node dies → the ReplicaSet makes a new one
                change the image          → rolling update, rollback possible
                replicas: 3               → three copies
```

A Deployment is the minimum for a **long-running app you want kept alive**, not the minimum Kubernetes accepts. Other workloads use other wrappers:

- **Jobs** run Pods to completion.
- **StatefulSets** give Pods stable names and storage ([statefulsets-and-daemonsets.md](statefulsets-and-daemonsets.md)).
- **DaemonSets** run one Pod per node.

To reach the app, you add a **Service** next to the Deployment ([kubernetes-services.md](kubernetes-services.md)).

### 1.1 Setup

```yaml
# web.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web
  namespace: wl-demo
spec:
  replicas: 4
  selector:
    matchLabels:
      app: web
  template:
    metadata:
      labels:
        app: web
    spec:
      containers:
      - name: web
        image: registry.k8s.io/e2e-test-images/agnhost:2.53
        args: ["netexec", "--http-port=8080"]
        env:
        - name: VERSION          # changing this is how we'll trigger rollouts
          value: "v1"
        readinessProbe:          # rollouts wait for Ready, so give them a real probe
          httpGet: {path: /, port: 8080}
          periodSeconds: 2
```

```sh
kubectl create ns wl-demo
kubectl apply -f web.yaml
kubectl -n wl-demo rollout status deploy/web
```

### 1.2 What `apply` does

**`kubectl apply`** sends the Deployment to the API server, which validates it, stores it in etcd and replies. kubectl exits there, before any ReplicaSet, Pod or container exists. Everything after that is done by other components, each watching the API server (see [watching-the-api-server.md](watching-the-api-server.md)) and reacting to the object the previous one wrote:

```
kubectl apply -f web.yaml
   |  POST/PATCH Deployment  ->  stored in etcd. kubectl exits here.
   v
deployment controller   (in kube-controller-manager)
   |  sees a new Deployment, creates ReplicaSet web-d858b6555 (replicas: 4)
   v
replicaset controller   (in kube-controller-manager)
   |  sees a ReplicaSet with 0 of 4 Pods, creates 4 Pod objects
   |  (no node yet, nothing is running)
   v
kube-scheduler
   |  sees Pods with no node, picks one for each, writes spec.nodeName
   v
kubelet on that node
   |  sees a Pod assigned to its node
   |  CRI (containerd/docker): pull the image, create the sandbox
   |  CNI (Calico): give it an IP
   |  start the container      <- containers are created only here
   |  readiness probe passes -> marks the Pod Ready in its status
   v
replicaset / deployment controllers
      count the ready Pods, update status.readyReplicas, availableReplicas ...
```

In this lab, `apply` returned at once, but the Pods sat in `ContainerCreating` for about two minutes, because Calico wasn't ready yet on the newly added node:

```
Warning  FailedCreatePodSandBox  pod/web-d858b6555-ckz4j  Failed to create pod sandbox: ...
  plugin type="calico" failed (add): stat /var/lib/calico/nodename: no such file or directory
```

The Deployment, the ReplicaSet and the Pod objects all existed during that time. Only the kubelet step was stuck.

**`kubectl rollout status`** changes nothing either. It opens a watch on that one Deployment and re-checks its `status` on every update. The checks are in `DeploymentStatusViewer.Status` (`staging/src/k8s.io/kubectl/pkg/polymorphichelpers/rollout_status.go`), and each failed check prints one of the messages you see while it waits:

| Check, in order | Message while it's not true yet |
|---|---|
| `metadata.generation <= status.observedGeneration` (the controller has seen your change) | `Waiting for deployment spec update to be observed...` |
| `Progressing` condition isn't `ProgressDeadlineExceeded` | otherwise it fails: `deployment "web" exceeded its progress deadline` |
| `status.updatedReplicas >= spec.replicas` | `0 out of 4 new replicas have been updated...` |
| `status.replicas <= status.updatedReplicas` (old Pods gone) | `1 old replicas are pending termination...` |
| `status.availableReplicas >= status.updatedReplicas` | `3 of 4 updated replicas are available...` |
| all of the above | `deployment "web" successfully rolled out`, exit 0 |

So:

- **Exit codes are its real use.** It exits 0 on success and 1 on `--timeout` or an exceeded progress deadline (§7), which is why CI pipelines run it after `apply`.
- **The rollout itself is the deployment controller.** It scales the new ReplicaSet up and the old one down within `maxSurge` and `maxUnavailable` (§3). You start a rollout by changing the Pod template (`kubectl apply`, `set image`, `set env`, `rollout restart`), and `rollout undo` changes the template back (§4).

### 1.3 A Pod object vs a running Pod

The diagram in §1.2 says the ReplicaSet controller "creates 4 Pod objects" and that nothing is running yet. "Pod" means two different things, and they're created at different times by different components:

- **The Pod object** is a record stored in the API server (in etcd): `metadata`, `spec` (containers, image, env, volumes...) and `status`. It describes what should run. Controllers create it, the scheduler writes `spec.nodeName` into it, and the kubelet writes `status` back into it. A record isn't a process; nothing executes it.
- **The running Pod** is what the kubelet builds on a node from that record. It's a sandbox (a network namespace that holds the Pod's IP, kept open by a tiny `pause` container) plus your containers, started through the container runtime.

When people say "a Pod" they usually mean both at once, because normally both exist and match.

#### Seeing both for one CoreDNS Pod

The record, from the API server:

```
$ kubectl -n kube-system get pod coredns-7c44b4957-vc6d7 -o wide
NAME                      READY   STATUS    RESTARTS       IP              NODE
coredns-7c44b4957-vc6d7   1/1     Running   2 (5h3m ago)   10.244.120.91   minikube
```

What actually runs on the node (minikube uses Docker as its runtime):

```
$ minikube ssh -- "docker ps --format '{{.Names}}  {{.Image}}' | grep coredns-7c44b4957-vc6d7"
k8s_coredns_coredns-7c44b4957-vc6d7_kube-system_46ea9894-..._2   138784d87c9c
k8s_POD_coredns-7c44b4957-vc6d7_kube-system_46ea9894-..._0       registry.k8s.io/pause:3.10.1
```

One Pod object corresponds to **two containers** on the node:

- **`k8s_POD_...`** is the `pause` sandbox. It owns the network namespace and the IP `10.244.120.91`.
- **`k8s_coredns_...`** is the actual CoreDNS container, which joins the sandbox's network.

The container names encode the container, Pod name, namespace, the Pod object's UID (`46ea9894-...`) and a restart count. The trailing `_2` matches `RESTARTS 2` in the kubectl output. The Pod object didn't change across those restarts; the kubelet just made new containers for it.

On a containerd node, `crictl pods` and `crictl ps` show the same two layers.

#### Is a Pod a container? No: Pod ⊇ containers ⊇ processes

A Pod is a wrapper around one or more containers. Most Pods hold just one, like CoreDNS above, which is why the two feel the same. The Envoy Gateway proxy Pod on the lab holds two (`envoy` and `shutdown-manager`):

```
$ kubectl -n envoy-gateway-system get pod envoy-infra-public-8d55c10f-76bf47b465-555jv \
    -o 'jsonpath={.spec.containers[*].name} {.status.podIP}'
envoy shutdown-manager 10.244.120.112

# on the node: three containers, then each one's network and mount namespace
$ minikube ssh -- "docker ps --filter name=envoy-infra-public-8d55c10f-76bf47b465-555jv ..."
k8s_shutdown-manager_…   pid=2256763  netns=net:[4026533347]  mnt=mnt:[4026534884]
k8s_envoy_…              pid=2256717  netns=net:[4026533347]  mnt=mnt:[4026534871]
k8s_POD_…  (pause)       pid=847329   netns=net:[4026533347]  mnt=mnt:[4026533342]
```

(The namespaces come from `readlink /proc/<pid>/ns/net` and `/ns/mnt` for each container's PID.)

All three share **one network namespace**, but each has **its own filesystem**:

```
 Pod envoy-infra-public-…-555jv      one Pod IP: 10.244.120.112, one node: minikube
 ┌──────────────────────────────────────────────────────────────────────────────────────┐
 │  network namespace net:[4026533347]  ← SHARED: same IP, same ports, localhost        │
 │                                                                                      │
 │  ┌─ container POD (pause) ─┐ ┌─ container envoy ───────┐ ┌─ container shutdown-manager ─┐
 │  │ image pause:3.10.1      │ │ process: envoy          │ │ process: envoy-gateway …     │
 │  │ holds the netns open    │ │                         │ │                              │
 │  │ mnt:[…3342]             │ │ mnt:[…4871]             │ │ mnt:[…4884]                  │
 │  └─────────────────────────┘ └─────────────────────────┘ └──────────────────────────────┘
 │         own filesystem             own filesystem              own filesystem        │
 └──────────────────────────────────────────────────────────────────────────────────────┘
```

| Level | What it is | What it gets | Example |
|---|---|---|---|
| **Pod** | the unit Kubernetes schedules, gives an IP, and deletes as a whole | one IP, one node, shared volumes | `envoy-infra-public-…-555jv` |
| **Container** | one image, run in isolation | its own filesystem from its image, its own resource limits, its own restarts | `envoy`, `shutdown-manager` |
| **Process** | an actual running program | — | `envoy`; or, in ingress-nginx, `/nginx-ingress-controller` plus nginx's master and workers |

What follows from the sharing:

- **Containers in one Pod talk over `localhost`**, and they can't both bind the same port.
- **To share files, they need a shared volume.** Their filesystems are separate.
- **They're always scheduled together.** That's why sidecars (proxies, log shippers) are extra containers in the Pod, not separate Pods.
- **The `pause` container is never shown by kubectl.** It holds the network namespace so an app container can crash and restart without the Pod losing its IP. There's one per Pod: the `pause ×27` in [watching-the-api-server.md](watching-the-api-server.md) §8.2.

Two ways of putting processes together are easy to mix up:

```
 ingress-nginx Pod:  1 container  ─ several processes   (/nginx-ingress-controller starts nginx itself)
 envoy Pod:          2 containers ─ 1 process each      (envoy, shutdown-manager)
                     both: one Pod, one IP
```

The ingress-nginx layout is from its Dockerfile (`CMD ["/nginx-ingress-controller"]`) and source, not a live `ps`: it isn't installed on the lab now.

#### They can exist without each other

| Situation | Pod object | Running containers |
|---|---|---|
| Just created by the ReplicaSet controller | exists, `Pending`, no `spec.nodeName`, no IP | none: no node has been chosen yet |
| Scheduled, image pulling or CNI failing (the Calico error in §1.2) | exists, `ContainerCreating` | sandbox maybe, app container not yet |
| Running | `Running`, `Ready` | sandbox + containers |
| Container crashes and restarts | **same object**, `RESTARTS` goes up | new container, same sandbox |
| Pod finished (a Job's Pod) | stays as `Completed` until deleted | stopped |
| Node loses its connection to the API server | goes `Unknown`/`Terminating` | may still be running on that cut-off node |

That's why the diagram says "creates 4 Pod objects (no node yet, nothing is running)". At that step only the four records exist:

1. The scheduler fills in `spec.nodeName` on each record.
2. The kubelet on that node, watching for records with its name, turns each record into real containers.
3. The kubelet then writes back into the record's `status` (IP, Ready, restarts). That's how `kubectl get pods` knows what's happening on the node.

The same split applies to every Kubernetes object. A Deployment or a Service is also just a record, which controllers, kube-proxy or the kubelet act on. Pods are just the case where the "real thing" is processes on a machine.

#### Where `containers:` lives in each kind

Not every manifest has a `containers:` section. It belongs to the **Pod spec**, so it only appears in a Pod, or in objects that carry a Pod template inside them. **(lab)** These paths come from `kubectl explain` on minikube v1.34.0:

| Kind | Where `containers` lives |
|---|---|
| **Pod** | `spec.containers` |
| Deployment | `spec.template.spec.containers` |
| ReplicaSet | `spec.template.spec.containers` |
| StatefulSet | `spec.template.spec.containers` |
| DaemonSet | `spec.template.spec.containers` |
| Job | `spec.template.spec.containers` |
| CronJob | `spec.jobTemplate.spec.template.spec.containers`, one level deeper because it makes Jobs, which make Pods |
| Service, ConfigMap, Secret, Ingress, … | **none**: `kubectl explain service.spec.containers` → `error: field "containers" does not exist` |

`spec.template` in a workload is a whole Pod inside another object, minus `apiVersion` and `kind`. Whatever you'd write under a Pod's `spec:` goes under `template.spec:`, and the controller stamps out Pods from it:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: {name: web}
spec:
  replicas: 2
  selector: {matchLabels: {app: web}}
  template:                    # ← a Pod, minus apiVersion/kind
    metadata: {labels: {app: web}}
    spec:                      # ← exactly what a Pod's spec: holds
      containers:              #   required
      - name: app
        image: nginx:1.27
        volumeMounts: [{name: s, mountPath: /etc/db, readOnly: true}]
      volumes:                 #   same level as containers
      - {name: s, secret: {secretName: demo-db}}
```

That's why `volumes`, `volumeMounts`, `env` and `securityContext` work the same way in a Deployment as in a bare Pod (see [secrets-and-env.md](secrets-and-env.md) §3).

**(lab)** `kubectl explain pod.spec` lists `containers` as the only **required** field. These sit at the same level:

| Field | What it holds |
|---|---|
| `containers` | the main containers; they start together and run for the Pod's lifetime |
| `initContainers` | run to completion, one after another, before the main containers start |
| `ephemeralContainers` | added to a running Pod for debugging (`kubectl debug`) through the `pods/ephemeralcontainers` subresource; can't be set at creation (docs; not tested) |
| `volumes` | what the containers can mount; each container picks with its own `volumeMounts` |

### 1.4 What got created

```
$ kubectl -n wl-demo get deploy,rs,pods --show-labels
NAME                  READY   UP-TO-DATE   AVAILABLE   SELECTOR   LABELS
deployment.apps/web   4/4     4            4           app=web    <none>

NAME                            DESIRED   CURRENT   READY   SELECTOR                              LABELS
replicaset.apps/web-d858b6555   4         4         4       app=web,pod-template-hash=d858b6555   app=web,pod-template-hash=d858b6555

NAME                      READY   STATUS    LABELS
pod/web-d858b6555-c29f9   1/1     Running   app=web,pod-template-hash=d858b6555
pod/web-d858b6555-ckz4j   1/1     Running   app=web,pod-template-hash=d858b6555
pod/web-d858b6555-kh9lc   1/1     Running   app=web,pod-template-hash=d858b6555
pod/web-d858b6555-w57k7   1/1     Running   app=web,pod-template-hash=d858b6555
```

(Columns trimmed.) The names read top to bottom:

- `web`: the Deployment.
- `web-d858b6555`: the Deployment name plus the template hash.
- `web-d858b6555-c29f9`: the ReplicaSet name plus 5 random characters.

The ownership chain, read from `metadata.ownerReferences`:

```
$ kubectl -n wl-demo get pod web-d858b6555-c29f9 -o jsonpath='{range .metadata.ownerReferences[*]}{.kind}/{.name} controller={.controller}{"\n"}{end}'
ReplicaSet/web-d858b6555 controller=true

$ kubectl -n wl-demo get rs web-d858b6555 -o jsonpath='{range .metadata.ownerReferences[*]}{.kind}/{.name} controller={.controller}{"\n"}{end}'
Deployment/web controller=true
```

The Pod points to the ReplicaSet, and the ReplicaSet points to the Deployment. Nothing points from a Pod straight to a Deployment.

### 1.5 Why the hash label exists

The Deployment's selector is only `app=web`. If two ReplicaSets (v1 and v2) both selected `app=web`, each would count the other's Pods as its own and delete them. So the Deployment controller adds `pod-template-hash` to each ReplicaSet's selector **and** template:

```
$ kubectl -n wl-demo get rs web-d858b6555 -o json | jq '{selector: .spec.selector, templateLabels: .spec.template.metadata.labels, annotations: .metadata.annotations}'
selector:        {'matchLabels': {'app': 'web', 'pod-template-hash': 'd858b6555'}}
template labels: {'app': 'web', 'pod-template-hash': 'd858b6555'}
annotations:     {'deployment.kubernetes.io/desired-replicas': '4',
                  'deployment.kubernetes.io/max-replicas': '5',
                  'deployment.kubernetes.io/revision': '1'}
```

How the hash is computed is in `ComputeHash` (`pkg/controller/controller_utils.go`): an FNV-32a hash of the whole Pod template, plus `status.collisionCount` if there ever was a name collision. The result is then encoded into safe characters. **Same template means same hash means same ReplicaSet**, which is how rollbacks find the old ReplicaSet (§4).

The annotations:

- **`revision`**: which rollout this ReplicaSet belongs to.
- **`desired-replicas`**: the Deployment's `replicas` at the time.
- **`max-replicas`**: `replicas + maxSurge`, so 4 + 1 = 5.

All three are defined in `pkg/controller/deployment/util/deployment_util.go`.

### 1.6 Defaults you got without asking

```
$ kubectl -n wl-demo get deploy web -o jsonpath='{.spec.strategy}{"\n"}revisionHistoryLimit={.spec.revisionHistoryLimit} progressDeadlineSeconds={.spec.progressDeadlineSeconds}{"\n"}'
{"rollingUpdate":{"maxSurge":"25%","maxUnavailable":"25%"},"type":"RollingUpdate"}
revisionHistoryLimit=10 progressDeadlineSeconds=600
```

These match `SetDefaults_Deployment` in `pkg/apis/apps/v1/defaults.go`: `maxUnavailable` 25%, `maxSurge` 25%, keep 10 old ReplicaSets, 600 s to make progress.

### 1.7 Reading a Pod's name: why Pods share a hash, and where the last part comes from

```
 coredns   -   7c44b4957      -   9kdz8
 ───┬───       ────┬────          ──┬──
    │              │                └─ random, 5 chars: added by the API server (generateName)
    │              └─ pod-template-hash: hash of the Deployment's Pod template (§1.5)
    └─ the Deployment's name
```

Two different components produce the two suffixes:

```
 Deployment coredns
   │ Deployment controller: hash(spec.template) = 7c44b4957
   ▼
 ReplicaSet coredns-7c44b4957           ← full name chosen by the Deployment controller
   │ ReplicaSet controller creates each Pod with
   │   metadata.generateName: coredns-7c44b4957-      (no name)
   ▼
 API server, when storing: name = generateName + 5 random characters
   ├─► Pod coredns-7c44b4957-9kdz8
   └─► Pod coredns-7c44b4957-qs9bk
```

So **Pods share the hash because they share a template**, and **they differ only in the last part because the API server picks it**. The ReplicaSet never chooses a Pod's full name. The random characters skip vowels and look-alike characters, so they never spell words (from apimachinery's `rand.String`; not tested here).

#### Examples, one step at a time

Run on the lab (namespace `names-demo`, deleted afterwards). Pods still terminating are left out.

**1. Create: one template, one hash.**

```
$ kubectl -n names-demo create deployment web --image=registry.k8s.io/e2e-test-images/agnhost:2.53 --replicas=2 -- /agnhost netexec
replicaset.apps/web-c77759ff9
pod/web-c77759ff9-4jp5s
pod/web-c77759ff9-dml68
```

**2. Scale to 3: the template didn't change, so the hash stays.** One new Pod with a new random suffix:

```
$ kubectl -n names-demo scale deploy/web --replicas=3
replicaset.apps/web-c77759ff9
pod/web-c77759ff9-4jp5s
pod/web-c77759ff9-dml68
pod/web-c77759ff9-r28n7      ← new
```

**3. Change the template (an env var): new hash, new ReplicaSet, all-new Pod names.**

```
$ kubectl -n names-demo set env deploy/web COLOR=blue
RS               REPLICAS   REVISION
web-5fb7cbf474   3          2          ← new template
web-c77759ff9    0          1          ← kept at 0 for rollback
pod/web-5fb7cbf474-q9jhw
pod/web-5fb7cbf474-qc7fw
pod/web-5fb7cbf474-xnq2k
```

**4. Roll back: the template is the original again, so the hash is the original again.** The old ReplicaSet is reused and scaled back up. The Pods are new, so they get new random suffixes:

```
$ kubectl -n names-demo rollout undo deploy/web
RS               REPLICAS   REVISION
web-5fb7cbf474   0          2
web-c77759ff9    3          3          ← same name as step 1, revision bumped
pod/web-c77759ff9-6p8gx
pod/web-c77759ff9-fwhfr
pod/web-c77759ff9-ldsdm
```

**5. Delete a Pod: the ReplicaSet replaces it with the same hash and a new suffix.**

```
$ kubectl -n names-demo delete pod web-c77759ff9-6p8gx
pod/web-c77759ff9-fwhfr
pod/web-c77759ff9-gbjsj      ← replacement
pod/web-c77759ff9-ldsdm

$ kubectl -n names-demo get pod -l app=web -o jsonpath='{.items[0].metadata.generateName}'
web-c77759ff9-
```

**6. `generateName` works on any object, and `kubectl apply` refuses it.**

```
$ printf 'apiVersion: v1\nkind: ConfigMap\nmetadata: {generateName: demo-}\n' | kubectl create -f - -o name   # ×3
configmap/demo-6q8w9
configmap/demo-znbpf
configmap/demo-fcxn7

$ ... | kubectl apply -f -
error: from demo-: cannot use generate name with apply
```

`apply` needs a fixed name to compare against the next time. A name the server makes up is different on every run.

**7. A cluster that has been changed a lot keeps one ReplicaSet per template.** CoreDNS on the lab, after the template edits in [core-dns.md](core-dns.md):

```
$ kubectl -n kube-system get rs -l k8s-app=kube-dns -o name
replicaset.apps/coredns-66bc5c9577
replicaset.apps/coredns-7c44b4957      ← current: both CoreDNS Pods are coredns-7c44b4957-…
replicaset.apps/coredns-7c9c4b6648
replicaset.apps/coredns-7fb8d4ff4c
replicaset.apps/coredns-df7cdfcb5
```

#### Other owners name Pods differently

The same `names-demo` namespace, with a DaemonSet `agent` and a StatefulSet `db`, plus static Pods from `kube-system`:

```
POD                       GENERATENAME   REVISION-HASH label   OWNER
agent-lmfxd               agent-         8694ccfb8             DaemonSet
db-0                      db-            db-847756954d         StatefulSet
db-1                      db-            db-847756954d         StatefulSet
kube-proxy-6btzn          kube-proxy-                          DaemonSet
kube-apiserver-minikube   <none>                               Node
```

| Owner | Name pattern | Where the version hash lives |
|---|---|---|
| Deployment → ReplicaSet | `<deployment>-<pod-template-hash>-<random>` | in the name, and the `pod-template-hash` label |
| DaemonSet | `<daemonset>-<random>` | only in the `controller-revision-hash` label, from a ControllerRevision `agent-8694ccfb8` |
| StatefulSet | `<statefulset>-<ordinal>`: `db-0`, `db-1`. Fixed, nothing random | the `controller-revision-hash` label (`db-847756954d`). The name stays the same across versions |
| Static Pod (kubelet) | `<manifest name>-<node name>` | none; the kubelet names it from the file |

The StatefulSet Pods have `generateName: db-` set, but they also have a fixed `name`, and a fixed name wins. That stable name is what gives `db-0` the same DNS name and volume across restarts ([statefulsets-and-daemonsets.md](statefulsets-and-daemonsets.md)).

**Don't parse Pod names in scripts.** Select by labels instead: `-l app=web`, or `-l pod-template-hash=c77759ff9` for one version. The hash changes with every template change, and the suffix changes with every Pod.

---

## 2. Who does what: scaling, self-healing, and editing the wrong object

### 2.1 Scaling a Deployment scales its ReplicaSet

```
$ kubectl -n wl-demo scale deploy web --replicas=6
$ kubectl -n wl-demo get rs
NAME            DESIRED   REV
web-d858b6555   6         1
```

There's no new ReplicaSet, because `replicas` isn't part of the Pod template. The Deployment controller only changed `web-d858b6555`'s `spec.replicas`. The event is recorded on the Deployment:

```
Normal   ScalingReplicaSet   deployment/web   Scaled up replica set web-d858b6555 from 4 to 6
```

### 2.2 A deleted Pod is replaced by the ReplicaSet

```
$ kubectl -n wl-demo delete pod web-d858b6555-c29f9
$ kubectl -n wl-demo get events --field-selector involvedObject.kind=ReplicaSet
Normal   SuccessfulCreate   replicaset/web-d858b6555   Created pod: web-d858b6555-jhvk9
```

The event is on the **ReplicaSet**, not the Deployment. The Deployment controller didn't take part; from its point of view nothing changed (the ReplicaSet still wants 6).

### 2.3 Don't edit a ReplicaSet a Deployment owns

Scale the ReplicaSet directly to 1:

```
$ kubectl -n wl-demo scale rs web-d858b6555 --replicas=1
$ kubectl -n wl-demo get rs web-d858b6555 -o jsonpath='{.spec.replicas}'   # 2 s later
6
```

The Deployment controller put it back to 6:

```
Normal   ScalingReplicaSet   deployment/web   Scaled up replica set web-d858b6555 from 1 to 6
```

**But the ReplicaSet controller had already acted on the "1"**, and Pods died:

```
$ kubectl -n wl-demo get events --field-selector involvedObject.kind=ReplicaSet
Normal   SuccessfulDelete   replicaset/web-d858b6555   Deleted pod: web-d858b6555-ckz4j
Normal   SuccessfulDelete   replicaset/web-d858b6555   Deleted pod: web-d858b6555-mkqrh
Normal   SuccessfulDelete   replicaset/web-d858b6555   Deleted pod: web-d858b6555-vxn8z
...
Normal   SuccessfulCreate   replicaset/web-d858b6555   Created pod: web-d858b6555-9sdlk
...
$ kubectl -n wl-demo get deploy web
NAME   READY   UP-TO-DATE   AVAILABLE
web    1/4     4            1
```

#### Why it goes wrong: two controllers, two opinions

A Deployment's ReplicaSet has **two** controllers reading it. Each acts on whatever value is in the API server *at the moment it looks*:

```
 time   ReplicaSet spec.replicas      replicaset controller            deployment controller
 ----   ------------------------      ---------------------            ---------------------
  t0    6                             6 Pods, 6 wanted: nothing to do  RS should be 6: fine
  t1    1   <- your kubectl scale
  t2    1                             sees "1", has 6 Pods:
                                      DELETES 5 Pods right away
  t3    1                                                              sees RS at 1, but the
                                                                       Deployment says 6:
                                                                       writes 6 back
  t4    6                             sees "6", has 1 Pod:
                                      creates 5 new Pods
  t5    6                             new Pods start, become Ready
                                      (here: tens of seconds; on a
                                      slow node: minutes)
```

The ReplicaSet controller isn't wrong: `replicas: 1` is an order and it carried it out. The Deployment controller isn't wrong either: it owns that number and corrected it. The damage is in the gap between t2 and t5. Pods were deleted at once, and new ones take as long as image pull, startup and the readiness probe need. During that gap the app ran on 1 Pod.

There's no "edit it and the Deployment will keep it" path. You can't win the race; the Deployment always writes its value back:

- **Pausing the Deployment doesn't stop it.** `kubectl rollout pause` only pauses *rollouts*, not scaling. Tested here with `replicas: 3`:

  ```
  $ kubectl -n wl-demo rollout pause deploy/web
  $ kubectl -n wl-demo scale rs web-d858b6555 --replicas=1
  $ kubectl -n wl-demo get rs web-d858b6555 -o jsonpath='{.spec.replicas}'    # 3 s later
  3
  Normal   ScalingReplicaSet   deployment/web   Scaled up replica set web-d858b6555 from 1 to 3
  ```

  In `pkg/controller/deployment/deployment_controller.go`, `syncDeployment` sends a paused Deployment to `dc.sync()`, which still scales its ReplicaSets to match.

#### What each kind of direct edit does to an owned ReplicaSet

| Edit on the owned ReplicaSet | What happens | Harm |
|---|---|---|
| `spec.replicas` (`kubectl scale rs`) | the ReplicaSet acts on it at once, then the Deployment reverts it | Pods deleted, or extra Pods created, until it settles (tested above) |
| `spec.template` (image, env...) | the Deployment no longer finds a ReplicaSet whose template matches its own, so it starts a **new rollout** away from your edit (tested, see below) | your change never reaches a running Pod, and every Pod is replaced anyway |
| `metadata.labels`/`annotations` on the ReplicaSet | mostly left alone. The Deployment rewrites only its own `deployment.kubernetes.io/*` annotations | none, unless you remove the labels the Deployment's selector matches (it then releases the ReplicaSet) |
| `spec.selector` | rejected: a ReplicaSet's selector is immutable in `apps/v1` | none |
| **deleting a Pod it owns** | the ReplicaSet makes a replacement (§2.2) | **none: this is fine** and is the normal way to restart one Pod |

The template row, tested. The Deployment's template has `VERSION=v2`, and its current ReplicaSet is `web-bddf975f6` with 4 Pods. Edit that ReplicaSet's template directly:

```
$ kubectl -n wl-demo get deploy web -o jsonpath='{.status.collisionCount}'
                                                   <- unset
$ kubectl -n wl-demo patch rs web-bddf975f6 --type=json \
    -p '[{"op":"replace","path":"/spec/template/spec/containers/0/env/0/value","value":"hand-edited"}]'

6 s later:
$ kubectl -n wl-demo get deploy web -o jsonpath='{.status.collisionCount}'
1
NAME             DESIRED   REVISION   VERSION
web-8589d7dfd9   1         3          v2              <- new ReplicaSet, Deployment's own template
web-bddf975f6    3         2          hand-edited     <- your edit, now the "old" one
web-d858b6555    0         1          v1
Normal  ScalingReplicaSet  deployment/web  Scaled up replica set web-8589d7dfd9 from 0 to 1

after the rollout:
web-8589d7dfd9   4         3          v2
web-bddf975f6    0         2          hand-edited
```

How the source explains it. `FindNewReplicaSet` (`pkg/controller/deployment/util/deployment_util.go`) picks the ReplicaSet whose template `EqualIgnoreHash`-matches the Deployment's. After your edit none matches, so `getNewReplicaSet` (`sync.go`) creates one:

1. It hashes the Deployment's unchanged template. That gives the same name, `web-bddf975f6`, so the create fails with `AlreadyExists`.
2. The existing object's template isn't equal, so it treats this as a hash collision: it increments `status.collisionCount` (to 1, as above) and requeues.
3. The next attempt hashes with the new count. That gives a different name, `web-8589d7dfd9`, and the ReplicaSet is created.
4. A normal rolling update then moves all the Pods from your edited ReplicaSet to the new one.

The wasteful part: the 4 running Pods were still v2. A template edit never touches existing Pods (case 1 below), so `hand-edited` never ran anywhere. Yet the Deployment replaced all 4 with new v2 Pods.

#### When editing a ReplicaSet directly does make sense

The rule isn't really "never touch ReplicaSets". It's **edit the object at the top of the ownership chain**: whatever has no controller above it, because nothing will overwrite it. Check with:

```sh
kubectl -n wl-demo get rs web-d858b6555 -o jsonpath='{.metadata.ownerReferences}'
# empty                 -> you are the controller; editing the ReplicaSet is the right thing
# kind: Deployment      -> edit the Deployment
# kind: Rollout (Argo)  -> edit the Rollout
```

That gives three legitimate cases.

**1. A bare or orphaned ReplicaSet.** With no owner there's no second controller, so your edit stays. Tested by orphaning the Deployment (§6), then scaling and editing the ReplicaSet that was left:

```
$ kubectl -n wl-demo delete deploy web --cascade=orphan
$ kubectl -n wl-demo scale rs web-d858b6555 --replicas=2
$ kubectl -n wl-demo get rs web-d858b6555 -o jsonpath='spec.replicas={.spec.replicas} owner={.metadata.ownerReferences}'
spec.replicas=2 owner=                      <- stays at 2, nobody reverts it
```

What a ReplicaSet can't do is roll out. Change its template (VERSION v1 → v9) and nothing happens to the running Pods:

```
$ kubectl -n wl-demo patch rs web-d858b6555 --type=json \
    -p '[{"op":"replace","path":"/spec/template/spec/containers/0/env/0/value","value":"v9"}]'
$ kubectl -n wl-demo get pods -l app=web -o custom-columns=NAME:.metadata.name,VERSION:.spec.containers[0].env[0].value
web-d858b6555-6n8lr   v1
web-d858b6555-txb46   v1
web-d858b6555-xmddb   v1                    <- unchanged
```

Only Pods it creates afterwards get the new template. Scaled from 2 to 3:

```
web-d858b6555-txb46   v1        2026-11-01T03:02:34Z
web-d858b6555-v5fbf   v9        2026-11-01T03:05:34Z    <- new Pod, new template
web-d858b6555-xmddb   v1        2026-11-01T03:01:14Z
```

Now one ReplicaSet runs two versions, and its name and `pod-template-hash` (`d858b6555`) no longer describe what's in it. Finishing the change means deleting the old Pods yourself, one by one. That's exactly the job a Deployment does for you, so this case is for when you have a reason not to use one.

**2. Your own controller owns the ReplicaSets.** Tools like Argo Rollouts don't use Deployments. They create and scale ReplicaSets themselves to do canary and blue-green rollouts: put 10% of Pods on the new ReplicaSet, check metrics, then shift more. For them, writing `replicaset/scale` is the whole point. Note that for you, the same rule still applies one level up: edit the `Rollout`, not its ReplicaSets.

**3. Changing a Deployment's immutable fields without downtime.** Orphan the ReplicaSets, recreate the Deployment, and let it adopt them (§6). You aren't editing the ReplicaSet there; you're briefly making it ownerless.

#### Why the API allows it at all

Ownership in Kubernetes is bookkeeping, not a lock:

- **`ReplicaSet` is a full public API type** (`apps/v1`), usable alone (case 1) and by other controllers (case 2). The API can't offer "scale a ReplicaSet" to them and refuse it to you without some extra rule about who may write.
- **`kubectl scale` is generic.** It works on anything with a `/scale` subresource (Deployments, ReplicaSets, StatefulSets, custom resources) and checks nothing about owners. The HorizontalPodAutoscaler uses the same subresource.
- **`ownerReferences` are read by the garbage collector** (delete children with the parent) and by controllers (recognise and adopt their objects). The API server doesn't use them to accept or reject writes.
- **Controllers are designed to correct, not to prevent.** Anyone may write, and each controller keeps pushing back towards its desired state. That's what makes the system recover from crashes and mistakes on its own. The cost is the gap shown in the timeline above.

#### Blocking it on purpose

If you want "edit the Deployment, not the ReplicaSet" to be enforced rather than a convention, there are two options. Both were run here, on a 2-replica `web` Deployment whose ReplicaSet was `web-d858b6555`.

**RBAC: don't grant write access to ReplicaSets.** The deployment controller authenticates as its own service account (`--use-service-account-credentials=true` on this cluster's kube-controller-manager), so it keeps working whatever your users can do:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: app-deployer
  namespace: wl-demo
rules:
- apiGroups: ["apps"]
  resources: ["deployments", "deployments/scale"]
  verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
- apiGroups: ["apps"]
  resources: ["replicasets"]                    # read-only: no update/patch, no replicasets/scale
  verbs: ["get", "list", "watch"]
- apiGroups: [""]
  resources: ["pods"]
  verbs: ["get", "list", "watch", "delete"]     # deleting a Pod to restart it is fine
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: app-deployer-alice
  namespace: wl-demo
subjects:
- kind: User
  name: alice
  apiGroup: rbac.authorization.k8s.io
roleRef:
  kind: Role
  name: app-deployer
  apiGroup: rbac.authorization.k8s.io
```

Check it without logging in as alice: `--as` makes the API server evaluate the request as that user (impersonation, which the cluster admin is allowed to do):

```
$ kubectl -n wl-demo auth can-i patch deployments --as=alice
yes
$ kubectl -n wl-demo auth can-i patch deployments --subresource=scale --as=alice
yes
$ kubectl -n wl-demo auth can-i patch replicasets --as=alice
no
$ kubectl -n wl-demo auth can-i patch replicasets --subresource=scale --as=alice
no
$ kubectl -n wl-demo auth can-i delete pods --as=alice
yes
$ kubectl -n wl-demo auth can-i patch replicasets --as=system:serviceaccount:kube-system:deployment-controller
yes
```

Then actually try:

```
$ kubectl -n wl-demo scale rs web-d858b6555 --replicas=1 --as=alice
Error from server (Forbidden): replicasets.apps "web-d858b6555" is forbidden: User "alice" cannot patch resource "replicasets/scale" in API group "apps" in the namespace "wl-demo"

$ kubectl -n wl-demo patch rs web-d858b6555 -p '{"spec":{"replicas":1}}' --as=alice
Error from server (Forbidden): replicasets.apps "web-d858b6555" is forbidden: User "alice" cannot patch resource "replicasets" in API group "apps" in the namespace "wl-demo"

$ kubectl -n wl-demo scale deploy web --replicas=3 --as=alice
deployment.apps/web scaled
$ kubectl -n wl-demo get rs web-d858b6555 -o jsonpath='{.spec.replicas}'
3                                  <- the deployment controller wrote it, with its own permissions

$ kubectl -n wl-demo delete pod web-d858b6555-6ts9q --as=alice
pod "web-d858b6555-6ts9q" deleted from wl-demo namespace
```

Both ways of changing the ReplicaSet (`kubectl scale`, which goes through `replicasets/scale`, and a plain `patch`) are refused. Scaling the Deployment works, and so does deleting a Pod. The ReplicaSet still changes, because the deployment controller makes that write under its own service account, not alice's.

RBAC is the simplest option and covers most needs. It has two limits:

- **It can't tell owned ReplicaSets from bare ones.** Alice can't edit *any* ReplicaSet in that namespace.
- **It doesn't restrict cluster admins.** Anyone in `system:masters` (like the default minikube user) bypasses RBAC entirely.

**An admission policy**, for "only owned ReplicaSets are protected". A `ValidatingAdmissionPolicy` (GA since 1.30) runs a CEL expression on every matching request:

```yaml
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicy
metadata:
  name: no-editing-owned-replicasets
spec:
  failurePolicy: Fail
  matchConstraints:
    resourceRules:
    - apiGroups: ["apps"]
      apiVersions: ["v1"]
      operations: ["UPDATE"]
      resources: ["replicasets"]
  validations:
  - expression: >
      request.userInfo.username == "system:serviceaccount:kube-system:deployment-controller" ||
      request.userInfo.username == "system:serviceaccount:kube-system:replicaset-controller" ||
      request.userInfo.username == "system:serviceaccount:kube-system:generic-garbage-collector" ||
      !has(oldObject.metadata.ownerReferences) ||
      !oldObject.metadata.ownerReferences.exists(r, r.kind == "Deployment")
    message: "This ReplicaSet is managed by a Deployment; change the Deployment instead."
---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicyBinding
metadata:
  name: no-editing-owned-replicasets
spec:
  policyName: no-editing-owned-replicasets
  validationActions: ["Deny"]
  matchResources:
    namespaceSelector:
      matchLabels:
        kubernetes.io/metadata.name: wl-demo     # try it on one namespace first
```

Run as `minikube-user`, a member of `system:masters`. Unlike RBAC, admission policies apply to admins too:

```
$ kubectl -n wl-demo patch rs web-d858b6555 -p '{"spec":{"replicas":1}}'
The replicasets "web-d858b6555" is invalid: : ValidatingAdmissionPolicy 'no-editing-owned-replicasets' with binding 'no-editing-owned-replicasets' denied request: This ReplicaSet is managed by a Deployment; change the Deployment instead.

$ kubectl -n wl-demo label rs web-d858b6555 note=x
The replicasets "web-d858b6555" is invalid: : ValidatingAdmissionPolicy 'no-editing-owned-replicasets' ... denied request: This ReplicaSet is managed by a Deployment; change the Deployment instead.

$ kubectl -n wl-demo scale rs web-d858b6555 --replicas=1
replicaset.apps/web-d858b6555 scaled                 <- NOT blocked, see below
$ kubectl -n wl-demo get rs web-d858b6555 -o jsonpath='{.spec.replicas}'
3                                                    <- and the Deployment reverted it, as in the race above
```

The controllers keep working under the policy:

```
$ kubectl -n wl-demo scale deploy web --replicas=4
NAME            DESIRED   READY
web-d858b6555   4         1                          <- deployment controller's update allowed

$ kubectl -n wl-demo set env deploy/web VERSION=v2
$ kubectl -n wl-demo rollout status deploy/web
deployment "web" successfully rolled out
NAME            DESIRED   READY    OWNER
web-bddf975f6   4         4        Deployment         <- created (CREATE isn't matched)
web-d858b6555   0         <none>   Deployment         <- scaled down by the controller (allowed)
```

A ReplicaSet with no owner isn't affected:

```
$ kubectl -n wl-demo label rs bare note=x            # a ReplicaSet created by hand, no ownerReferences
replicaset.apps/bare labeled
```

What these results show:

- **It doesn't catch `kubectl scale`, even if you add the subresource.** Scaling goes through `replicasets/scale`, which `resources: ["replicasets"]` doesn't match. Adding `"replicasets/scale"` to the rule doesn't help: in that request, `oldObject` is an `autoscaling/v1` `Scale` object, which has no `ownerReferences`, so the expression passes. Tested:

  ```
  resources=["replicasets","replicasets/scale"]
  $ kubectl -n wl-demo scale rs web-bddf975f6 --replicas=1
  replicaset.apps/web-bddf975f6 scaled               <- still allowed
  ```

  Blocking it needs RBAC on `replicasets/scale` (above), or a webhook that looks up the parent ReplicaSet. Use the policy and RBAC together: the policy covers admins on the main resource, RBAC covers scale for everyone else.
- **It blocks every update of an owned ReplicaSet**, including harmless label and annotation changes (the `label` above). That's stricter than the table earlier in this section needs. Narrow it by comparing only `object.spec` with `oldObject.spec` if you want metadata edits allowed.
- **The allow-list must include every controller that legitimately updates ReplicaSets.** The deployment controller scaled and rolled out without problems. The ReplicaSet controller writes `status` through the `replicasets/status` subresource, which this rule doesn't match. The garbage collector updates `ownerReferences` and finalizers on the main resource, so it's on the list. Leave one out and you break that component. That's why the binding is scoped to one namespace.
- **The usernames depend on the cluster.** This cluster runs kube-controller-manager with `--use-service-account-credentials=true`, so each controller has its own service account. With `false`, they all appear as `system:kube-controller-manager`.
- **`failurePolicy: Fail`** means that if the policy itself errors, the request is denied. That's safer than allowing it, but mistakes in the policy show up quickly as blocked writes.

### 2.4 A ReplicaSet adopts any matching Pod

The ReplicaSet counts every Pod in the namespace that matches its selector and has no other controller. Create a bare Pod that carries its labels:

```sh
kubectl -n wl-demo run stray3 --image=registry.k8s.io/e2e-test-images/agnhost:2.53 \
  --labels="app=web,pod-template-hash=d858b6555" -- pause
```

Poll its owner every 0.25 s:

```
   1 / del=
  11 ReplicaSet/web-d858b6555 del=2026-10-31T20:28:29Z
```

Within a quarter of a second the ReplicaSet **adopted** it (wrote itself into `ownerReferences`). It now had 5 Pods for `replicas: 4`, so it **deleted one**, and it picked the stray. The order it deletes in is in `ActivePodsWithRanks.Less` (`pkg/controller/controller_utils.go`):

1. unscheduled before scheduled
2. Pending before Running
3. not ready before ready
4. lower `controller.kubernetes.io/pod-deletion-cost` first
5. Pods sharing a node with more of their siblings first
6. then the most recently started

The stray had only just started, so it lost. The Pod's events end with `Killing ... Stopping container stray3`.

Labels are the only link between a ReplicaSet and its Pods. Two workloads whose selectors overlap will fight over Pods.

---

## 3. A rollout, second by second

Change anything in the Pod template. An env var is enough:

```sh
kubectl -n wl-demo set env deploy/web VERSION=v2
```

Poll both ReplicaSets every second (`desired/ready`, printed only when something changed):

```
  0s web-d858b6555=4/4
  3s web-bddf975f6=1/  web-d858b6555=3/4
  6s web-bddf975f6=2/  web-d858b6555=3/3
 22s web-bddf975f6=2/1 web-d858b6555=2/3
 25s web-bddf975f6=3/1 web-d858b6555=2/3
 28s web-bddf975f6=3/1 web-d858b6555=2/2
 49s web-bddf975f6=3/2 web-d858b6555=1/2
 53s web-bddf975f6=4/2 web-d858b6555=1/2
 55s web-bddf975f6=4/2 web-d858b6555=1/1
117s web-bddf975f6=4/3 web-d858b6555=0/1
121s web-bddf975f6=4/3 web-d858b6555=0/
132s web-bddf975f6=4/4 web-d858b6555=0/
```

(This cluster is slow to start containers. On a normal one the whole rollout takes seconds.)

### 3.1 The two limits

With `replicas: 4`:

| Setting | Value | Rounds | Means |
|---|---|---|---|
| `maxSurge: 25%` | 4 × 0.25 = 1 | **up** | at most 4 + 1 = **5 Pods** in total |
| `maxUnavailable: 25%` | 4 × 0.25 = 1 | **down** | at least 4 − 1 = **3 Pods** available |

Check them against the trace:

- **At 3 s** the controller used both budgets at once: new up to 1 (total 5 = the surge limit), old down to 3 (exactly the minimum available).
- **At 6 s** old was 3/3 ready and new was 2 not ready. Total desired: 5. Available: 3. Both limits hold.
- **From then on** each step waits for a new Pod to become ready before scaling old down by one, which frees room to scale new up by one.
- **The old ReplicaSet ends at 0 but isn't deleted.** It's kept for rollback (§4).

How the rounding comes out for other sizes:

| replicas | maxSurge 25% (round up) | maxUnavailable 25% (round down) | Pods during rollout |
|---|---|---|---|
| 1 | 1 | 0 | 1 → 2 → 1, never 0 available |
| 3 | 1 | 0 | at most 4 total, never below 3 |
| 4 | 1 | 1 | at most 5 total, at least 3 available |
| 10 | 3 | 2 | at most 13 total, at least 8 available |

Setting both to 0 is rejected by validation, since the rollout could never move.

### 3.2 The other strategy: Recreate

`strategy: {type: Recreate}` scales the old ReplicaSet to 0, waits for those Pods to be gone, then scales the new one up. You get downtime, but two versions never run at once. Use it when the app can't tolerate that, for example when both versions would write the same database schema.

---

## 4. History and rollback

Each ReplicaSet the Deployment owns is a saved version of the template.

```
$ kubectl -n wl-demo annotate deploy web kubernetes.io/change-cause="VERSION=v2"
$ kubectl -n wl-demo rollout history deploy/web
REVISION  CHANGE-CAUSE
1         <none>
2         VERSION=v2

$ kubectl -n wl-demo get rs -o custom-columns=...
NAME            DESIRED   REVISION   HISTORY   VERSION
web-bddf975f6   4         2          <none>    v2
web-d858b6555   0         1          <none>    v1
```

Roll back:

```
$ kubectl -n wl-demo rollout undo deploy/web
deployment.apps/web rolled back

NAME            DESIRED   REVISION   HISTORY   VERSION
web-bddf975f6   0         2          <none>    v2
web-d858b6555   4         3          1         v1

$ kubectl -n wl-demo rollout history deploy/web
REVISION  CHANGE-CAUSE
2         VERSION=v2
3         <none>
```

What happened:

1. **The rollback copied revision 1's template back into the Deployment.** That's all `rollout undo` does; it's an ordinary template change.
2. **The template hashes to `d858b6555` again**, so the controller found the existing ReplicaSet instead of making a new one. It's an ordinary rollout, just towards an old ReplicaSet.
3. **That ReplicaSet was renumbered from 1 to 3**, and its old number was recorded in `deployment.kubernetes.io/revision-history: 1`. A revision number means "the order rollouts happened in", so revision 1 no longer exists.

`kubectl rollout undo --to-revision=N` picks a specific one. The warning kubectl printed matters if you use `kubectl apply`: undo doesn't update the `last-applied-configuration` annotation, so the next apply of your old file may undo the undo. With GitOps (Argo CD, Flux), roll back in git instead.

### 4.1 How many old ReplicaSets are kept

`revisionHistoryLimit: 10`. Once there are more than 10 old ReplicaSets at 0 replicas, the oldest are deleted. Set it to 0 and rollback becomes impossible.

---

## 5. What makes a new ReplicaSet, and what doesn't

Starting from a settled Deployment, one change at a time:

```
replicas 4->5                          -> 2 ReplicaSets, newest: web-bddf975f6
label on the Deployment itself         -> 2 ReplicaSets, newest: web-bddf975f6
annotation on the Deployment itself    -> 2 ReplicaSets, newest: web-bddf975f6
strategy maxSurge 25% -> 2             -> 2 ReplicaSets, newest: web-bddf975f6
annotation in the pod template         -> 3 ReplicaSets, newest: web-8b54f9c4
```

| Change | New ReplicaSet + rollout? |
|---|---|
| `spec.replicas` | no, the current ReplicaSet is resized |
| Deployment `metadata.labels` / `annotations` | no |
| `spec.strategy`, `minReadySeconds`, `revisionHistoryLimit`, `progressDeadlineSeconds` | no |
| **anything under `spec.template`**: image, env, args, resources, probes, volumes, *and template labels or annotations* | **yes** |

The last row explains a common pattern: putting a hash of a ConfigMap in a template annotation (`checksum/config: ...`) forces a rolling restart when the config changes. `kubectl rollout restart` does the same thing. It sets `kubectl.kubernetes.io/restartedAt` in the template.

---

## 6. Orphaning and adoption

Delete the Deployment but keep its children:

```
$ kubectl -n wl-demo delete deploy web --cascade=orphan
$ kubectl -n wl-demo get rs -o custom-columns=NAME:.metadata.name,DESIRED:.spec.replicas,OWNER:.metadata.ownerReferences[0].name,REVISION:...
NAME            DESIRED   OWNER    REVISION
web-8b54f9c4    5         <none>   4
web-bddf975f6   0         <none>   2
web-d858b6555   0         <none>   3
```

The ReplicaSets lost their owner, and the 5 Pods kept running. Now re-apply the original `web.yaml`, which has `VERSION=v1` and no template annotation:

```
3 s later:
NAME            DESIRED   OWNER   REVISION
web-8b54f9c4    3         web     4
web-bddf975f6   0         web     2
web-d858b6555   2         web     5

settled:
web-8b54f9c4    0         web     4
web-bddf975f6   0         web     2
web-d858b6555   4         web     5
```

1. **The new Deployment adopted all three ReplicaSets**, because their labels match its selector `app=web`.
2. **Its template hashes to `d858b6555`**, so that ReplicaSet became the "new" one (revision 5) and the others were scaled down through a normal rolling update.

This is how you change a Deployment's immutable fields (like `spec.selector`) without downtime. Delete with `--cascade=orphan`, then recreate. Adoption only works if the new selector still matches the old ReplicaSets' labels.

Without `--cascade=orphan` (the default is `background`), the garbage collector follows the ownerReferences down and deletes the ReplicaSets, then their Pods.

---

## 7. A rollout that can't finish

Give the Deployment an image that doesn't exist and a short deadline:

```sh
kubectl -n wl-demo patch deploy web -p '{"spec":{"progressDeadlineSeconds":30}}'
kubectl -n wl-demo set image deploy/web web=registry.k8s.io/e2e-test-images/agnhost:9.99-doesnotexist
```

After 45 s:

```
NAME             DESIRED   READY    IMAGE
web-567b95544d   2         <none>   registry.k8s.io/e2e-test-images/agnhost:9.99-doesnotexist
web-d858b6555    3         3        registry.k8s.io/e2e-test-images/agnhost:2.53

NAME                   STATUS
web-567b95544d-kwzk7   ErrImagePull
web-567b95544d-ptt5z   ErrImagePull
web-d858b6555-9xdr8    <none>        # running
...

$ kubectl -n wl-demo get deploy web -o jsonpath='{range .status.conditions[*]}{.type}={.status} {.reason}: {.message}{"\n"}{end}'
Available=True MinimumReplicasAvailable: Deployment has minimum availability.
Progressing=False ProgressDeadlineExceeded: ReplicaSet "web-567b95544d" has timed out progressing.

$ kubectl -n wl-demo rollout status deploy/web --timeout=5s; echo "exit=$?"
error: deployment "web" exceeded its progress deadline
exit=1
```

- **The limits protected the app.** The old ReplicaSet went down to 3 (`maxUnavailable` 1) and stopped there. The new one went up to 2 (total 5 = `maxSurge` limit) and stopped, because neither Pod ever became ready. Three v1 Pods kept serving the whole time.
- **The deadline doesn't roll back.** It only sets `Progressing=False` with reason `ProgressDeadlineExceeded`. That makes `kubectl rollout status` exit non-zero, so a CI pipeline fails. Rolling back is up to you (or your CD tool):

```
$ kubectl -n wl-demo rollout undo deploy/web
$ kubectl -n wl-demo rollout status deploy/web
deployment "web" successfully rolled out
```

---

## 8. When you'd use a bare ReplicaSet

Practically never. A ReplicaSet can't roll out a change: editing its template only affects Pods created after the edit. Existing Pods keep the old template until something deletes them. A Deployment adds the rollout, history and rollback on top. A ReplicaSet on its own only makes sense if you're writing your own rollout controller. Argo Rollouts, for example, manages ReplicaSets directly to do canary and blue-green rollouts.

### 8.1 Do we write bare Pod manifests?

You almost never write bare Pod manifests for real applications. You write the Pod spec inside a workload's `template`, and the controller creates the Pods.

**(lab)** minikube v1.34.0, throwaway namespace `pod-demo` (deleted afterwards).

**1. Nobody replaces a bare Pod.** I deleted one Pod from `kubectl run bare` and one from a Deployment:

```
before:                                  after deleting both:
bare                       <none>        managed-867b47dd4c-sbf4c   ReplicaSet   ← new Pod, new name
managed-867b47dd4c-g8xrs   ReplicaSet    (bare is simply gone)
```

The Deployment's Pod has an owner (`ReplicaSet`) that brings it back (§2.2). The bare Pod has no owner, so nothing recreates it. The same happens if its node dies.

**2. You can barely change a Pod once it exists.** Changing an env var on the bare Pod:

```
The Pod "bare" is invalid: spec: Forbidden: pod updates may not change fields other than
`spec.containers[*].image`, `spec.initContainers[*].image`, `spec.activeDeadlineSeconds`,
`spec.tolerations` (only additions to existing tolerations) ...
```

- **Image** is one of the few fields you can change. The same Pod (same UID) restarted its container with `nginx:1.27-alpine` (`restarts=1`). There's no gradual rollout and no rollback.
- On the Deployment, `kubectl set env deployment/managed COLOR=red` did a proper rollout. It created a new ReplicaSet (`managed-5b8ddb4ff` with 1 Pod) and scaled the old one (`managed-867b47dd4c`) to 0, which stays around for rollback (§4).

#### So which do you write?

| You want | Write |
|---|---|
| A long-running, stateless app | **Deployment** |
| Stable names and storage per Pod (databases, Keycloak) | **StatefulSet** |
| One Pod on every node (log agents, CNI) | **DaemonSet** |
| A task that runs to completion | **Job** |
| A task on a schedule | **CronJob** |

All of these contain the Pod spec under `spec.template.spec`. That's where `containers`, `volumes` and `env` go (see "Where `containers:` lives in each kind" in §1.3).

#### When bare Pod manifests *are* written

- **Debugging and one-off tests:** `kubectl run`, a throwaway busybox Pod, or `kubectl debug`. Most lab Pods in these notes are like this.
- **Static Pods:** **(lab)** minikube's control plane is defined by bare Pod manifests on the node, in `/etc/kubernetes/manifests/`: `etcd.yaml`, `kube-apiserver.yaml`, `kube-controller-manager.yaml` and `kube-scheduler.yaml`. The **kubelet** runs these straight from disk; the API server only gets a read-only "mirror":

  ```
  NAME                      OWNER   OWNERNAME   SOURCE
  etcd-minikube             Node    minikube    file
  kube-apiserver-minikube   Node    minikube    file
  ```

  The mirror's owner is the **Node**, and `kubernetes.io/config.source: file` says it came from disk. To change or remove a static Pod, you edit or remove the file on the node. Deleting the mirror through the API isn't enough **(not tested; only a server dry-run delete was run)**.
- **Controllers and operators** create Pods through the API. You don't write those manifests either; the ReplicaSet, Job or an operator does. Examples are Tekton or Jenkins build Pods.

**Rule of thumb:** if you'd care when the Pod disappears, don't write a bare Pod. Write the workload that owns it.

### 8.2 How the system Pods themselves are created

Nobody writes the API server's Pod by hand. On a kubeadm-style cluster like minikube, the setup tool **writes** the control plane's Pod manifests to disk once, and the **kubelet** runs them directly. Everything else is created through the API like normal workloads.

#### Two kinds of system Pods

**(lab)** Every Pod in `kube-system` and what owns it:

```
POD                                OWNER        OWNER-NAME                     SOURCE  MIRROR
etcd-minikube                      Node         minikube                       file    e3a36fac…   ┐
kube-apiserver-minikube            Node         minikube                       file    8312b4cd…   │ static Pods:
kube-controller-manager-minikube   Node         minikube                       file    3b51c824…   │ run by the kubelet
kube-scheduler-minikube            Node         minikube                       file    dc6cf0a7…   ┘ from files on disk
coredns-7c44b4957-khgpl            ReplicaSet   coredns-7c44b4957              —       —           ┐
kube-proxy-6btzn                   DaemonSet    kube-proxy                     —       —           │ normal workloads,
calico-node-n5bxx                  DaemonSet    calico-node                    —       —           │ created through
calico-kube-controllers-…          ReplicaSet   calico-kube-controllers-…      —       —           ┘ the API
```

#### 1. The control plane: static Pods

**(lab)** The kubelet's config tells it to watch a folder:

```
$ sudo grep staticPodPath /var/lib/kubelet/config.yaml
staticPodPath: /etc/kubernetes/manifests

$ sudo ls -la /etc/kubernetes/manifests/
-rw------- 1 root root 2655 Oct 27 21:18 etcd.yaml
-rw------- 1 root root 4171 Oct 27 21:18 kube-apiserver.yaml
-rw------- 1 root root 3483 Oct 27 21:18 kube-controller-manager.yaml
-rw------- 1 root root 1727 Oct 27 21:18 kube-scheduler.yaml
```

All four have the same timestamp: the moment the cluster was created, when kubeadm wrote them. Each one is an ordinary Pod manifest. The full YAML of all four, with every flag explained, is in [control-plane-manifests.md](control-plane-manifests.md):

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: kube-apiserver
  namespace: kube-system
  labels: {component: kube-apiserver, tier: control-plane}
spec:
  containers:
  - command:
    - kube-apiserver
    - --advertise-address=192.168.49.2
    - --authorization-mode=Node,RBAC
    - --etcd-cafile=/var/lib/minikube/certs/etcd/ca.crt
    ...
```

**Why files instead of the API?** It's a chicken-and-egg problem. Creating a Pod "normally" means sending it to the **API server** and storing it in **etcd**, then the **scheduler** places it. But those *are* the things being started. So the kubelet reads the files and starts the containers itself, with no API server, scheduler or etcd needed.

Once the API server is up, the kubelet also creates a read-only **mirror Pod** for each file, so `kubectl get pods` can show them. That's what the `OWNER: Node`, `SOURCE: file` and `MIRROR: <hash>` columns mean.

To change a static Pod (for example, to add an API server flag), you edit the **file on the node**, and the kubelet restarts it. Editing the mirror through `kubectl` doesn't work **(not tested)**. That's how you'd turn on the `ImageVolume` gate or encryption at rest on a kubeadm cluster.

#### 2. Everything else: normal objects through the API

After the control plane is up, kubeadm creates the rest the normal way: CoreDNS as a **Deployment** and kube-proxy as a **DaemonSet**. The CNI (Calico here) comes from a manifest applied with `kubectl apply`. Their Pods are owned by ReplicaSets or DaemonSets, so they self-heal like any app (§8.1).

---

## 9. Where Argo CD fits in

Argo CD doesn't edit images or versions, and it never touches ReplicaSets or Pods. It keeps the cluster's objects matching what's in git. The change itself, such as a new image tag, is a commit made by a person, a CI pipeline, or the separate Argo CD Image Updater. Argo CD then:

1. renders the manifests (plain YAML, Helm, Kustomize, ...)
2. compares them with the live objects it holds in its cluster cache (see §6.2 of [watching-the-api-server.md](watching-the-api-server.md))
3. applies whatever differs: every changed field of every object, not just the image

Once the Deployment in the API server has the new template, Argo CD's part is over. From there it's the same chain as `kubectl apply` in §1.2.

```
 you / CI / Argo CD Image Updater
   |  git commit: image: web:1.4.2 -> web:1.5.0   (or a Helm values / Kustomize change)
   v
 git repo
   |
   v
 Argo CD repo-server        renders the manifests (plain YAML, Helm, Kustomize, ...)
   |
   v
 Argo CD application controller
   |  diff: rendered manifests vs live objects (from its cluster cache)
   |  OutOfSync -> sync (manual, or automatic)
   |  apply the changed objects, like kubectl apply / server-side apply
   v
 API server: Deployment web now has image web:1.5.0      <- Argo CD's part ends here
   |
   v
 deployment controller -> new ReplicaSet -> Pods ...     <- the same chain as §1.2
```

Argo CD follows the §2.3 rule: it edits the top-level owner (the Deployment) and only *reads* the ReplicaSets and Pods. It uses them to draw its resource tree and to work out health. A stuck rollout's `Progressing=False` (§7), for example, shows up as `Degraded`.

Because git is the source of truth, two things change:

- **Manual edits get undone.** With `selfHeal` enabled, a `kubectl set image`, or a `kubectl scale` when the manifest sets `replicas`, is reverted on the next sync. If an HPA manages the replica count, leave `replicas` out of the manifest, or tell Argo CD to ignore that field.
- **Rollback happens in git.** Revert the commit, or use Argo CD's history to re-sync an older revision, rather than `kubectl rollout undo` (§4).

---

## 10. Summary

```
 you ----> Deployment ------------------------------------------------+
            | "how many Pods of each version?"                        |
            |  (deployment controller)                                | history = the old
            |                                                         | ReplicaSets at 0
            +--> ReplicaSet (hash of template v2)  replicas: 4  <-- new
            +--> ReplicaSet (hash of template v1)  replicas: 0  <-- old, kept
                     | "are there exactly N matching Pods?"
                     |  (replicaset controller)
                     v
                   Pods  (labels: app=web, pod-template-hash=...)
```

| Question | Answer |
|---|---|
| Who creates Pods? | The ReplicaSet controller. |
| Who decides how many Pods each version has? | The Deployment controller, by setting ReplicaSets' `spec.replicas`. |
| What triggers a new ReplicaSet? | Any change under `spec.template`. |
| How does rollback work? | It copies the old template back; the same hash finds the old ReplicaSet, which is scaled up again. |
| What links a ReplicaSet to its Pods? | The label selector (with `pod-template-hash`), plus the ownerReference written on adoption. |
| Can I edit an owned ReplicaSet? | You can, but the Deployment reverts it and the ReplicaSet may delete Pods meanwhile (§2.3). |

### Clean up

```sh
kubectl delete ns wl-demo
```
