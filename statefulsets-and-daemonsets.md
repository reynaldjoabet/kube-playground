# StatefulSets and DaemonSets

Unlike a Deployment ([deployments-and-replicasets.md](deployments-and-replicasets.md)), neither of these uses ReplicaSets. Both **own their Pods directly**, and both keep their version history in **ControllerRevision** objects.

- A **StatefulSet** runs N Pods that each have a fixed identity: name `db-0`, `db-1`, ..., their own DNS name and their own volume. They start, stop and update in order.
- A **DaemonSet** runs one Pod on every node, or on every node that matches a selector.

---

## 0. Terms

| Term | Meaning |
|---|---|
| **ordinal** | A StatefulSet Pod's number: `db-0` has ordinal 0. Also in the label `apps.kubernetes.io/pod-index`. |
| **headless Service** | A Service with `clusterIP: None`. Its DNS name returns the Pod IPs directly, and each StatefulSet Pod also gets its own name under it. |
| **volumeClaimTemplates** | A PVC template in a StatefulSet. Each Pod gets its own PVC, named `<template>-<pod>`, e.g. `data-db-1`. |
| **PVC / PV** | PersistentVolumeClaim (a request for storage) and PersistentVolume (the storage that satisfies it). |
| **ControllerRevision** | A small object holding a snapshot of a Pod template, with a `revision` number. StatefulSets and DaemonSets use it the way Deployments use old ReplicaSets. |
| **controller-revision-hash** | A label on each Pod naming the ControllerRevision it was created from. |
| **partition** | A StatefulSet update setting: only Pods with ordinal ≥ partition are updated. |
| **taint / toleration** | A taint on a node repels Pods; a toleration on a Pod lets it ignore that taint. |

---

## 1. How they differ from a Deployment

```
 Deployment                 StatefulSet                     DaemonSet
     |                           |                               |
     v                           |  owns directly                |  owns directly
 ReplicaSet (one per version)    v                               v
     |                       db-0  db-1  db-2               agent-xxxxx (node A)
     v                       |     |     |                  agent-yyyyy (node B)
 Pods (random names)         PVC   PVC   PVC
                             data-db-0 ...

 history: old ReplicaSets    history: ControllerRevisions   history: ControllerRevisions
```

| | Deployment | StatefulSet | DaemonSet |
|---|---|---|---|
| Pod owner | ReplicaSet | the StatefulSet | the DaemonSet |
| How many Pods | `replicas` | `replicas` | one per matching node |
| Pod names | random (`web-d858b6555-c29f9`) | fixed (`db-0`, `db-1`) | random (`agent-29l7l`) |
| Replaced Pod | new name, new identity | **same name, same PVC, same DNS** | new name, same node |
| Storage | shared or none | one PVC per Pod, kept after the Pod | usually hostPath on the node |
| Placement | scheduler picks | scheduler picks | the controller pins each Pod to a node |
| Update order | many at once (surge/unavailable) | one at a time, highest ordinal first | node by node, `maxUnavailable` at a time |
| History | old ReplicaSets | ControllerRevisions | ControllerRevisions |
| Typical use | stateless web/API | databases, Kafka, etcd, ZooKeeper | log agents, CNI (Calico), kube-proxy, node exporters |

Two of the "typical use" examples are already running in this cluster as DaemonSets:

```
$ kubectl -n kube-system get ds
NAME          DESIRED   CURRENT   READY   UP-TO-DATE   AVAILABLE   NODE SELECTOR            AGE
calico-node   2         2         2       2            2           kubernetes.io/os=linux   4d
kube-proxy    2         2         2       2            2           kubernetes.io/os=linux   4d
```

---

## 2. StatefulSet

### 2.1 The manifest

```yaml
# db.yaml
apiVersion: v1
kind: Service
metadata:
  name: db
  namespace: wl-demo
spec:
  clusterIP: None          # headless: DNS returns pod IPs, one name per pod
  selector:
    app: db
  ports:
  - port: 8080
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: db
  namespace: wl-demo
spec:
  serviceName: db          # the headless Service that gives pods their DNS names
  replicas: 3
  selector:
    matchLabels:
      app: db
  template:
    metadata:
      labels:
        app: db
    spec:
      containers:
      - name: db
        image: registry.k8s.io/e2e-test-images/agnhost:2.53
        args: ["netexec", "--http-port=8080"]
        env:
        - name: VERSION
          value: "v1"
        readinessProbe:
          httpGet: {path: /, port: 8080}
          periodSeconds: 2
        volumeMounts:
        - name: data
          mountPath: /data
  volumeClaimTemplates:      # one PVC per pod, created from this
  - metadata:
      name: data
    spec:
      accessModes: ["ReadWriteOnce"]
      resources:
        requests:
          storage: 16Mi
```

The cluster needs a StorageClass that can provision volumes. A PVC with no class to bind to stays `Pending` forever, and so does `db-0`, which blocks `db-1` and `db-2` too (§2.2). That's what happened here first. Fresh minikube had no StorageClass until:

```sh
minikube addons enable storage-provisioner
minikube addons enable default-storageclass
```

### 2.2 Pods start one at a time, in order

```sh
kubectl apply -f db.yaml
```

Polled every second (`name ready`, printed only on change):

```
  0s db-0 <none>
  2s db-0 false
 17s db-0 true
 19s db-0 true db-1 <none>
 28s db-0 true db-1 false
 93s db-0 true db-1 true
 95s db-0 true db-1 true db-2 <none>
100s db-0 true db-1 true db-2 false
108s db-0 true db-1 true db-2 true
```

`db-1` isn't even **created** until `db-0` is Ready, and `db-2` waits for `db-1`. That's the default `podManagementPolicy: OrderedReady` (`SetDefaults_StatefulSet`). It's what a database cluster wants: the first member comes up and the others join it.

`podManagementPolicy: Parallel` starts and stops them all at once. Identity and storage are still per Pod; only the ordering is dropped. Updates (§2.5) stay one at a time either way.

### 2.3 What each Pod gets

```
$ kubectl -n wl-demo get pods -l app=db -o custom-columns=...
NAME   IP               NODE           HOSTNAME   SUBDOMAIN   INDEX   REV            OWNER
db-0   10.244.59.153    minikube-m04   db-0       db          0       db-7f845d95b   StatefulSet
db-1   10.244.120.107   minikube       db-1       db          1       db-7f845d95b   StatefulSet
db-2   10.244.59.154    minikube-m04   db-2       db          2       db-7f845d95b   StatefulSet

$ kubectl -n wl-demo get pvc -o custom-columns=...
NAME        STATUS   VOLUME                                     OWNER
data-db-0   Bound    pvc-342e1f71-55e4-456b-b6f9-3eeac6518382   <none>
data-db-1   Bound    pvc-43d56455-8025-4b51-99db-277d4e92726c   <none>
data-db-2   Bound    pvc-d6884754-e2cd-43fe-b331-49d9c509964b   <none>
```

- **Owner** is the StatefulSet itself; there's no ReplicaSet in between.
- **`hostname` and `subdomain`** are set by the controller to the Pod name and `serviceName`. Together with the headless Service they give each Pod a DNS name.
- **The PVCs have no owner** by default. Deleting a Pod or scaling down doesn't touch them (§2.6).

DNS, from a Pod in the cluster:

```
$ nslookup db-1.db.wl-demo.svc.cluster.local
Name:    db-1.db.wl-demo.svc.cluster.local
Address: 10.244.120.107

$ nslookup db.wl-demo.svc.cluster.local
Name:    db.wl-demo.svc.cluster.local
Address: 10.244.59.153
Name:    db.wl-demo.svc.cluster.local
Address: 10.244.120.107
Name:    db.wl-demo.svc.cluster.local
Address: 10.244.59.154
```

- **`db-1.db.wl-demo.svc.cluster.local`** always means "member 1", whatever its IP is right now. Cluster members put these names in their config (e.g. `--initial-cluster=db-0.db...,db-1.db...`).
- **`db.wl-demo.svc.cluster.local`** returns all members' IPs, since the Service is headless.

### 2.4 A replaced Pod keeps its identity

Write a file to `db-1`'s volume, then delete the Pod:

```
$ kubectl -n wl-demo exec db-1 -- sh -c 'echo hello-from-db-1 > /data/f; hostname'
db-1
$ kubectl -n wl-demo delete pod db-1
$ kubectl -n wl-demo wait --for=condition=Ready pod/db-1

old ip=10.244.120.107 uid=016a09c2-5f29-4efd-ae4d-03b518a6913f
new ip=10.244.120.108 uid=9193dfc5-b46a-4068-9cb5-961760e1ec18 node=minikube

$ kubectl -n wl-demo exec db-1 -- cat /data/f
hello-from-db-1
```

| | Before | After |
|---|---|---|
| name, hostname, DNS name | `db-1` | `db-1` |
| PVC | `data-db-1` | `data-db-1`, same data |
| UID | `016a09c2...` | `9193dfc5...`: it's a new Pod |
| IP | `10.244.120.107` | `10.244.120.108`: clients must use the DNS name, not the IP |

A Deployment would have created a Pod with a new random name and no tie to any particular volume.

### 2.5 Updates: highest ordinal first, and partitions

Every template change creates a ControllerRevision. Each Pod's `controller-revision-hash` label says which one it runs:

```
$ kubectl -n wl-demo get controllerrevisions -o custom-columns=NAME:.metadata.name,REVISION:.revision,OWNER:.metadata.ownerReferences[0].kind
NAME           REVISION   OWNER
db-7f845d95b   1          StatefulSet
```

**A full rolling update** (`kubectl set env sts/db VERSION=v2`):

```
  0s db-0 v1 true  db-1 v1 true  db-2 v1 false        <- db-2 terminating
 10s db-0 v1 true  db-1 v1 true                       <- db-2 gone
 12s db-0 v1 true  db-1 v1 true  db-2 v2 <none>       <- db-2 recreated with v2
 29s db-0 v1 true  db-1 v1 true  db-2 v2 true
 49s db-0 v1 true  db-1 v1 false db-2 v2 true         <- only now db-1
 72s db-0 v1 true  db-1 v2 <none> db-2 v2 true
118s db-0 v1 true  db-1 v2 true  db-2 v2 true
127s db-0 v1 false db-1 v2 true  db-2 v2 true         <- finally db-0
136s db-0 v2 <none> db-1 v2 true db-2 v2 true
151s db-0 v2 true  db-1 v2 true  db-2 v2 true
```

- **Reverse order**: 2, then 1, then 0.
- **Delete then create.** There's no surge, because a second `db-1` can't exist at the same time; the name and the PVC belong to one Pod.
- **Wait for Ready** before moving to the next Pod.

`maxUnavailable` for StatefulSets (update several Pods at once) is behind the `MaxUnavailableStatefulSet` gate. It's Beta and on by default from 1.37 (`pkg/features/kube_features.go`), so on this 1.34 cluster it's off.

**A partitioned update** updates only ordinals ≥ `partition`. It's a canary:

```sh
kubectl -n wl-demo patch sts db -p '{"spec":{"updateStrategy":{"rollingUpdate":{"partition":2}}}}'
kubectl -n wl-demo set env sts/db VERSION=v3
```

```
NAME   REV             VERSION   READY
db-0   db-cf56b99b9    v2        true
db-1   db-cf56b99b9    v2        true
db-2   db-6ccb99c678   v3        true

currentRevision=db-cf56b99b9 updateRevision=db-6ccb99c678 updatedReplicas=1 currentReplicas=2

NAME            REVISION
db-7f845d95b    1
db-cf56b99b9    2
db-6ccb99c678   3
```

- **Only `db-2` moved to v3.**
- **The status names both revisions**: `currentRevision` (what most Pods run) and `updateRevision` (the target).
- **Lowering the partition to 0 finishes the rollout.** Raising it again stops one.

The partition also decides which template a **recreated** Pod gets (§2.6).

`updateStrategy: {type: OnDelete}` does nothing on its own: a Pod picks up the new template only when you delete it. Some operators use this to control the order themselves.

### 2.6 Scaling down keeps the volumes

```
$ kubectl -n wl-demo scale sts db --replicas=1
statefulset/db   SuccessfulDelete   delete Pod db-2 in StatefulSet db successful
statefulset/db   SuccessfulDelete   delete Pod db-1 in StatefulSet db successful

$ kubectl -n wl-demo get pods -l app=db --no-headers
db-0   1/1   Running   0     2m29s
$ kubectl -n wl-demo get pvc --no-headers
data-db-0   Bound   ...
data-db-1   Bound   ...
data-db-2   Bound   ...
```

Pods go from the highest ordinal down, one at a time, and all three PVCs remain. Scale back up:

```
$ kubectl -n wl-demo scale sts db --replicas=3
NAME   VERSION   READY
db-0   v2        true
db-1   v2        true        <- recreated at v2: ordinal 1 < partition 2
db-2   v3        true        <- recreated at v3: ordinal 2 >= partition 2

$ kubectl -n wl-demo exec db-1 -- cat /data/f
hello-from-db-1              <- same PVC, same data, through two Pod replacements
$ kubectl -n wl-demo get pod db-1 -o jsonpath='{range .spec.volumes[?(@.persistentVolumeClaim)]}{.name} -> {.persistentVolumeClaim.claimName}{"\n"}{end}'
data -> data-db-1
```

The defaults:

```
$ kubectl -n wl-demo get sts db -o jsonpath='{.spec.persistentVolumeClaimRetentionPolicy}{"\n"}{.spec.podManagementPolicy}'
{"whenDeleted":"Retain","whenScaled":"Retain"}
OrderedReady
```

`persistentVolumeClaimRetentionPolicy` can be set to `Delete` for `whenDeleted` (the StatefulSet is deleted) and/or `whenScaled` (scale down). With `Delete`, the controller makes the StatefulSet (or the Pod) the PVC's owner, so the garbage collector removes it. With `Retain`, you delete leftover PVCs yourself, and a PVC left over from a previous StatefulSet with the same name gets reattached.

### 2.7 Rollback

`kubectl rollout history sts/db` and `kubectl rollout undo sts/db` work. They read the ControllerRevisions:

```
$ kubectl -n wl-demo rollout history sts/db
REVISION  CHANGE-CAUSE
1         <none>
2         <none>
3         <none>
```

Rolling back only changes the template. **It doesn't touch data**: if v3 changed the on-disk format, v2 has to cope with it. That's the main risk in updating stateful apps.

---

## 3. DaemonSet

### 3.1 The manifest

```yaml
# agent.yaml
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: agent
  namespace: wl-demo
spec:
  selector:
    matchLabels:
      app: agent
  template:
    metadata:
      labels:
        app: agent
    spec:
      containers:
      - name: agent
        image: registry.k8s.io/e2e-test-images/agnhost:2.53
        args: ["netexec", "--http-port=8080"]
        env:
        - name: VERSION
          value: "v1"
```

There's no `replicas`. The node count is the replica count.

```
$ kubectl -n wl-demo get ds agent
NAME    DESIRED   CURRENT   READY   UP-TO-DATE   AVAILABLE   NODE SELECTOR
agent   2         2         2       2            2           <none>

$ kubectl -n wl-demo get pods -l app=agent -o custom-columns=...
NAME          NODE           OWNER       REV
agent-29l7l   minikube-m04   DaemonSet   7bb456cc78
agent-5ml82   minikube       DaemonSet   7bb456cc78
```

Add a node and the DaemonSet creates a Pod on it. Remove one and its Pod goes with it.

### 3.2 How each Pod is pinned to its node

The DaemonSet controller decides placement and writes it into each Pod. The scheduler still binds the Pod, but there's only one node it can choose:

```
$ kubectl -n wl-demo get pod agent-29l7l -o jsonpath='{.spec.affinity}'
{"nodeAffinity":{"requiredDuringSchedulingIgnoredDuringExecution":{"nodeSelectorTerms":[{"matchFields":[{"key":"metadata.name","operator":"In","values":["minikube-m04"]}]}]}}}

$ kubectl -n wl-demo get events --field-selector involvedObject.name=agent-29l7l | grep -i schedul
Normal   Scheduled   pod/agent-29l7l   Successfully assigned wl-demo/agent-29l7l to minikube-m04
```

The function that writes this is `ReplaceDaemonSetPodNodeNameNodeAffinity` (`pkg/controller/daemon/util/daemonset_util.go`): a required node affinity on `metadata.name In [<node>]`. Going through the scheduler, instead of setting `spec.nodeName` directly, means resource checks and preemption still apply.

It also adds tolerations so the agent keeps running on nodes that other Pods are kept off:

```
$ kubectl -n wl-demo get pod agent-29l7l -o jsonpath='{range .spec.tolerations[*]}{.key} {.operator} {.effect}{"\n"}{end}'
node.kubernetes.io/not-ready Exists NoExecute
node.kubernetes.io/unreachable Exists NoExecute
node.kubernetes.io/disk-pressure Exists NoSchedule
node.kubernetes.io/memory-pressure Exists NoSchedule
node.kubernetes.io/pid-pressure Exists NoSchedule
node.kubernetes.io/unschedulable Exists NoSchedule
```

That list matches `AddOrUpdateDaemonPodTolerations`, which also adds `network-unavailable` for Pods using `hostNetwork`. It's why a CNI agent like `calico-node` can start on a node that isn't Ready yet: the node isn't Ready *because* the CNI isn't running.

The `unschedulable` toleration means **cordoning doesn't keep DaemonSet Pods off a node**. Cordon `minikube-m04` and delete its agent Pod:

```
$ kubectl cordon minikube-m04
$ kubectl -n wl-demo delete pod <agent pod on minikube-m04>
$ kubectl -n wl-demo get pods -l app=agent
NAME          NODE           PHASE
agent-4mb27   minikube-m04   Pending      <- created and bound to the cordoned node, container starting
agent-qnrj5   minikube       Running
```

That's also why `kubectl drain` needs `--ignore-daemonsets`: draining can't evict them for good, so it skips them.

Custom taints you add (e.g. `dedicated=gpu:NoSchedule`) aren't tolerated automatically. Add the toleration to the template if the agent should run there.

### 3.3 Only some nodes: nodeSelector

```sh
kubectl label node minikube-m04 role=edge
kubectl -n wl-demo patch ds agent -p '{"spec":{"template":{"spec":{"nodeSelector":{"role":"edge"}}}}}'
```

```
$ kubectl -n wl-demo get ds agent
NAME    DESIRED   CURRENT   READY   UP-TO-DATE   AVAILABLE   NODE SELECTOR
agent   1         1         1       1            1           role=edge

$ kubectl -n wl-demo get pods -l app=agent -o custom-columns=NAME:.metadata.name,NODE:.spec.nodeName
NAME          NODE
agent-rbz9s   minikube-m04

daemonset/agent   SuccessfulDelete   Deleted pod: agent-4mb27
daemonset/agent   SuccessfulCreate   Created pod: agent-rbz9s
```

`DESIRED` dropped to 1. The Pod on `minikube` was deleted, since that node no longer matches. It stayed listed for its 30 s termination grace period. Labelling another node `role=edge` would add a Pod there.

### 3.4 Updates: node by node

Defaults (`SetDefaults_DaemonSet`):

```
$ kubectl -n wl-demo get ds agent -o jsonpath='{.spec.updateStrategy}'
{"rollingUpdate":{"maxSurge":0,"maxUnavailable":1},"type":"RollingUpdate"}
```

`kubectl set env ds/agent VERSION=v2`, polled per node:

```
  0s minikube-m04:v1/true minikube:v1/true
  3s minikube-m04:v1/true minikube:v1/true (terminating)    <- one node at a time
 34s minikube-m04:v1/true minikube:v1/false (terminating) minikube:v2/<none>
 37s minikube-m04:v1/true minikube:v2/false
 99s minikube-m04:v1/true (terminating) minikube:v2/true   <- next node only after Ready
120s minikube-m04:v2/false minikube:v2/true
138s minikube-m04:v2/true minikube:v2/true
```

- **`maxSurge: 0` means delete first, then create.** Each node is briefly without an agent. That's the safe choice for Pods that bind a host port or own a node-level resource, where two copies would conflict.
- **`maxUnavailable: 1`** means one node at a time.
- **`maxSurge: 1`** (with `maxUnavailable: 0`) starts the new Pod next to the old one on each node. There's no gap, but both run for a moment.
- **`updateStrategy: {type: OnDelete}`** works as for StatefulSets.

### 3.5 History

The same ControllerRevision mechanism as StatefulSets, one revision per template change:

```
$ kubectl -n wl-demo get controllerrevisions -l app=agent -o custom-columns=NAME:.metadata.name,REVISION:.revision
NAME               REVISION
agent-7bb456cc78   1          <- v1
agent-865886b56f   2          <- VERSION=v2
agent-75fbfcd885   3          <- nodeSelector role=edge
```

What a ControllerRevision holds:

```
$ kubectl -n wl-demo get controllerrevision agent-865886b56f -o json | jq -c .data
{"spec": {"template": {"$patch": "replace", "metadata": {"labels": {"app": "agent"}}, "spec": {"containers": [{"args": ["netexec", "--http-port=8080"], "env": [{"name": "VERSION", "value": "v2"}], ...
```

It's just the template, stored as a patch that replaces the whole thing. `kubectl rollout undo ds/agent` applies it back. Unlike an old ReplicaSet, a ControllerRevision runs nothing; it's pure history. `revisionHistoryLimit` defaults to 10 for both StatefulSets and DaemonSets.

---

## 4. Which one to use

```
 Does every node need one copy (log shipper, CNI, node exporter, kube-proxy)?
   yes ----------------------------------------------------------> DaemonSet
   no
    |
 Does each replica need its own name, its own disk that survives
 rescheduling, or a fixed startup order (database, broker, quorum)?
   yes ----------------------------------------------------------> StatefulSet
   no  ----------------------------------------------------------> Deployment
```

| Question | Deployment | StatefulSet | DaemonSet |
|---|---|---|---|
| Uses ReplicaSets? | yes | **no** | **no** |
| History stored in | old ReplicaSets | ControllerRevisions | ControllerRevisions |
| Pod identity survives replacement? | no | yes (name, DNS, PVC) | no (but stays on the same node) |
| Two versions of one Pod at once during update? | yes (surge) | no | only with `maxSurge > 0` |
| Who picks the node? | scheduler | scheduler | DaemonSet controller (via node affinity), scheduler binds |
| Scale down removes | the cheapest-to-lose Pods (see deletion ranking) | the highest ordinal | Pods on nodes that stopped matching |

### Clean up

```sh
kubectl delete ns wl-demo                      # PVCs are in the namespace, so they go too
kubectl label node minikube-m04 role-
```
