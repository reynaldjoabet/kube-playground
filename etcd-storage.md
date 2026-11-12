# How etcd stores Kubernetes objects: memory, disk and everything in between

Related notes: [secrets-and-env.md](secrets-and-env.md) §2 and §5 (Secrets in etcd, encryption at rest) ·
[watching-the-api-server.md](watching-the-api-server.md) (watches and informers) ·
[keycloak-on-kubernetes.md](keycloak-on-kubernetes.md) §0.1 (kinds and resources)

---

## 0. The short version

```
 kubectl apply ──► kube-apiserver ─────────────────────────────────────────► etcd
                    1. validate, default, admission                         (the only
                    2. encode: protobuf "k8s\0…" (built-ins) or JSON (CRs)    stateful
                    3. optional: encrypt  "k8s:enc:…"                         part)
                    4. Txn: IF mod_revision(key) == expected
                            THEN put /registry/<resource>/<ns>/<name>
                    5. etcd revision ──► metadata.resourceVersion

 inside etcd:
   RAM:  raft log (recent entries), B-tree index  key → [revisions],
         write buffer, watchers, leases
   DISK: WAL (every write, fsync'd) + raft .snap + bbolt "db" file
         (bbolt: key = revision, value = {key, value, create/mod rev, version, lease};
          the file is mmap'd, so the OS page cache holds the hot parts)
```

Three facts explain most behaviour:

1. **etcd never overwrites.** Every write makes a new *revision*. The old value stays until **compaction**. The API server compacts every 5 minutes.
2. **The index is in memory; the data is in a memory-mapped file.** etcd keeps a B-tree from each key to its revisions in RAM. The values live in bbolt on disk and are read through `mmap`.
3. **`resourceVersion` is just etcd's `mod_revision`.** It isn't stored in the object. It's filled in on every read, and it's what makes optimistic locking and watches work.

---

## 1. etcd's layers, from the top

```
            gRPC API  (Range, Put, DeleteRange, Txn, Watch, Lease, Compact)
                 │
 ┌───────────────▼──────────────────────────────────────────────────────────┐
 │ RAFT  (agree on the order of writes across members)                      │
 │   RAM : raft.MemoryStorage = recent log entries                          │
 │   DISK: member/wal/*.wal  (append + fsync BEFORE a write is acknowledged)│
 │         member/snap/*.snap (raft snapshot; lets the WAL be trimmed)      │
 └───────────────┬──────────────────────────────────────────────────────────┘
                 │ committed entries are "applied" in order
 ┌───────────────▼──────────────────────────────────────────────────────────┐
 │ MVCC  (multi-version key/value store)                                    │
 │   RAM : treeIndex = B-tree (degree 32) of keyIndex                       │
 │         keyIndex = key → generations → [revision, revision, …]           │
 │         watchableStore = watcher groups (synced / unsynced / victims)    │
 │         lessor = lease → keys, expiry heap                               │
 └───────────────┬──────────────────────────────────────────────────────────┘
                 │ get/put by REVISION, not by key
 ┌───────────────▼──────────────────────────────────────────────────────────┐
 │ BACKEND  (bbolt, a B+tree in one file: member/snap/db)                    │
 │   buckets: key, meta, lease, alarm, cluster, members, auth, …            │
 │   "key" bucket: 17-byte revision → mvccpb.KeyValue                       │
 │   RAM : txReadBuffer (writes not yet committed to bbolt)                 │
 │         + the mmap'd file pages the OS keeps in the page cache            │
 │   commit to bbolt every 100 ms or every 10,000 writes                    │
 └──────────────────────────────────────────────────────────────────────────┘
```

### 1.1 Raft and the WAL: durability first

A write is acknowledged only after it's in the **write-ahead log (WAL)** on a majority of members, and the WAL is fsync'd. The raft library keeps recent entries in a `raft.MemoryStorage` **(code, `server/etcdserver/raft.go`: `raftStorage *raft.MemoryStorage`)**.

Every `SnapshotCount` applied entries (default **10,000**, `DefaultSnapshotCount` in `server/etcdserver/server.go`), etcd writes a `.snap` file, so old WAL entries and old in-memory raft entries can be released. A slow follower can still catch up from the last **5,000** entries (`DefaultSnapshotCatchUpEntries`). In 3.6 the `--snapshot-count` flag is **deprecated**, to be removed in 3.7 **(code, `server/etcdmain/config.go`)**. minikube still passes `--snapshot-count=10000`.

**(lab)** The files on the minikube node:

```
$ minikube ssh -- sudo ls -la /var/lib/minikube/etcd/member/{snap,wal}
member/snap:
   7141  0000000000000006-000000000008169d.snap      ← raft snapshots: <term>-<index>.snap
   7141  0000000000000006-0000000000083dae.snap         (small: membership/state, not the data)
   ...
33587200 db                                          ← the bbolt file: ALL the key/value data
member/wal:
64004832  0000000000000006-0000000000046166.wal       ← 64 MB segments: <seq>-<raft index>.wal
64000736  0000000000000007-000000000004ec02.wal
...
62500976  000000000000000a-000000000007e363.wal       ← current segment
64000000  000000000000000a-000000000007e363.wal.broken
64000000  0.tmp, 1.tmp                                ← pre-allocated next segments
```

WAL segments are pre-allocated at 64 MB, so their size says nothing about how much data you have. The `.wal.broken` file is from an earlier unclean stop: the Mac slept with minikube running. etcd sets a damaged tail segment aside and repairs it **(not tested: the exact cause wasn't checked)**.

### 1.2 The in-memory index: `treeIndex` and `keyIndex`

**(code, `server/storage/mvcc/index.go`, `key_index.go`):**

```go
type treeIndex struct {
    sync.RWMutex
    tree *btree.BTreeG[*keyIndex]       // btree.NewG(32, ...) → a B-tree of degree 32
}
type keyIndex struct {
    key         []byte
    modified    Revision                // last write
    generations []generation            // a new generation starts after each delete
}
type generation struct {
    ver     int64                       // how many writes in this generation
    created Revision
    revs    []Revision                  // every revision of this key still kept
}
```

The comment in `key_index.go` walks through an example. For key `foo`: `put(1.0); put(2.0); tombstone(3.0); put(4.0); tombstone(5.0)` gives

```
key: "foo"   modified: 5
generations:
    {empty}                  ← current: key doesn't exist now
    {4.0, 5.0(t)}            ← created at 4, deleted at 5
    {1.0, 2.0, 3.0(t)}       ← created at 1, updated at 2, deleted at 3
```

So the index answers "what was key K at revision R?" without touching the disk. It finds the generation and the newest revision ≤ R, then reads **that revision** from bbolt.

The index is **not saved**. On startup, `store.restore()` scans the whole `key` bucket in bbolt and rebuilds it **(code, `kvstore.go` → `restoreIntoIndex`)**. That's one reason a big etcd takes a while to start.

### 1.3 Revisions: one global counter

A **revision** is `{Main, Sub}`:

- **Main** goes up by one for every write transaction in the whole cluster.
- **Sub** counts changes inside one transaction.

On disk it's **17 bytes**: 8-byte big-endian main, `_`, 8-byte big-endian sub. A delete adds an 18th byte, `t` (tombstone) **(code, `revision.go`: `revBytesLen = 8 + 1 + 8`, `markTombstone = 't'`)**.

Because revisions are big-endian, **bbolt's byte order is time order**. Reading "everything after revision R" (which watches need) is a range scan.

Every key has three numbers **(code, `kvstore_txn.go` `put()` builds `mvccpb.KeyValue`)**:

| Field | Meaning |
|---|---|
| `create_revision` | main revision when this generation of the key was created |
| `mod_revision` | main revision of the last write; **this is Kubernetes' `resourceVersion`** |
| `version` | number of writes since creation; reset to 0 by a delete |

### 1.4 The bbolt file: `member/snap/db`

bbolt is a single-file B+tree with **buckets** **(code, `server/storage/schema/bucket.go`)**:

| Bucket | Holds |
|---|---|
| `key` | **all key/value data**: revision bytes → `mvccpb.KeyValue{key, create_revision, mod_revision, version, value, lease}` |
| `meta` | consistent index, scheduled/finished compaction revision, storage version |
| `lease` | leases (ID, TTL) |
| `alarm`, `cluster`, `members`, `members_removed` | cluster state |
| `auth`, `authUsers`, `authRoles` | etcd's own users/RBAC (not Kubernetes RBAC) |

How the memory works:

- **mmap.** bbolt maps the file into the process's address space. etcd asks for a **10 GiB** region (`InitialMmapSize = 10 * 1024 * 1024 * 1024`, `server/storage/backend/backend.go`). That's *virtual* address space: only pages actually read are loaded, and the OS can drop them again. Reads are memory reads through the page cache, not `read()` system calls.
- **Batched commits.** Writes go into a backend transaction that's committed every **100 ms** or **10,000** operations (`defaultBatchInterval`, `defaultBatchLimit`). Between commits, readers see new data through the in-memory **`txReadBuffer`** (`tx_buffer.go`). Durability doesn't depend on this commit, because the WAL already has the write.
- **The file never shrinks by itself.** Space from compacted revisions becomes free pages *inside* the file, and new writes reuse them. Only `etcdctl defrag` rewrites the file smaller.
- **Quota.** The default backend quota is **2 GiB** (`DefaultQuotaBytes`, `server/storage/quota.go`). When the file reaches it, etcd raises a `NOSPACE` alarm and refuses writes, so the cluster becomes read-only.

### 1.5 Memory in the lab

**(lab)** `etcdctl endpoint status`, etcd's metrics endpoint, and `/proc/<etcd pid>`:

```
DB SIZE 30 MB, IN USE 7.7 MB (75% not in use), RAFT INDEX 578802     ← endpoint status

etcd_mvcc_db_total_size_in_bytes         29,642,752   (≈ 29.6 MB, the db file)
etcd_mvcc_db_total_size_in_use_in_bytes   4,399,104   (≈ 4.4 MB actually used)
etcd_debugging_mvcc_keys_total                  794   (live keys in treeIndex)
etcd_debugging_mvcc_watcher_total               110   (watchers held in memory)
etcd_server_quota_backend_bytes       2,147,483,648   (2 GiB default quota)
go_memstats_heap_inuse_bytes             40,583,168   (≈ 40.6 MB Go heap)
process_resident_memory_bytes            88,154,112   (≈ 88 MB RSS)

/proc/<etcd>/status:  VmSize 11,739,456 kB   ← 11.2 GiB virtual: mostly the 10 GiB mmap
                      VmRSS      86,676 kB   ← what's really in RAM
                      RssAnon    62,556 kB   ← heap: index, raft log, buffers, watchers
                      RssFile    24,120 kB   ← file-backed pages (binary + db)
db mapping in smaps:  Size 10,485,760 kB (10 GiB)   Rss 6,788 kB (6.8 MB resident)
```

What this shows:

- **11 GiB virtual, 87 MB real.** The huge `VmSize` is the reserved mmap, not memory use.
- **The db file is 29.6 MB, but only 4.4 MB is live data.** The rest is free pages left by compaction (§4.4). It would only shrink with `defrag`.
- **Only 6.8 MB of the db is resident.** The OS keeps the pages that are actually read.
- **The raft index (578,802) and the MVCC revision (≈461,800) are different counters.** Raft entries include things that don't create a key revision, such as lease grants, compactions and membership changes **(not tested in detail)**.

---

## 2. How Kubernetes uses etcd: keys

The API server writes every object to **one key**:

```
/registry/<resource>/<namespace>/<name>                  namespaced, built-in group
/registry/<resource>/<name>                              cluster-scoped, built-in group
/registry/<group>/<resource>/<namespace>/<name>          CRDs and some API groups
```

`/registry` is the default `--etcd-prefix`. A Kubernetes object is never split across several keys, and several objects never share one.

### 2.1 Every key prefix in the lab cluster

**(lab)** `etcdctl get / --prefix --keys-only`, grouped. That's **765 keys** in total, 764 under `/registry` plus `compact_rev_key`:

| Prefix | Keys | What it is |
|---|---|---|
| `/registry/events` | 144 | Events. Expire through etcd leases (§5) |
| `/registry/clusterroles` | 78 | RBAC ClusterRoles |
| `/registry/clusterrolebindings` | 66 | RBAC ClusterRoleBindings |
| `/registry/serviceaccounts` | 61 | ServiceAccounts (one `default` per namespace, plus controllers' own) |
| `/registry/services/specs` | 30 | **Services**: note the extra `specs` level |
| `/registry/services/endpoints` | 28 | **Endpoints** (the old API), stored next to Services |
| `/registry/endpointslices` | 28 | EndpointSlices |
| `/registry/pods` | 28 | Pods |
| `/registry/apiextensions.k8s.io/customresourcedefinitions` | 44 | **CRDs** themselves |
| `/registry/crd.projectcalico.org/...` | 35 | Calico custom resources |
| `/registry/apiregistration.k8s.io/apiservices` | 29 | APIService objects: one per API group/version the API server serves |
| `/registry/configmaps` | 25 | ConfigMaps (incl. `kube-root-ca.crt` in every namespace) |
| `/registry/ipaddresses` | 23 | IPAddress objects: one per allocated Service ClusterIP |
| `/registry/replicasets` | 18 | ReplicaSets, including old ones kept for rollback |
| `/registry/roles`, `/registry/rolebindings` | 17 + 17 | namespaced RBAC |
| `/registry/namespaces` | 14 | Namespaces |
| `/registry/deployments` | 13 | Deployments |
| `/registry/gateway.networking.k8s.io/...` | 11 | Gateway API custom resources (GatewayClass, Gateway, HTTPRoute, ReferenceGrant) |
| `/registry/flowschemas`, `/registry/prioritylevelconfigurations` | 11 + 8 | API Priority and Fairness settings |
| `/registry/secrets` | 8 | Secrets |
| `/registry/leases` | 4 | coordination Leases: node heartbeat, leader election, API server identity |
| `/registry/controllerrevisions` | 3 | StatefulSet / DaemonSet revision history |
| `/registry/persistentvolumes` | 3 | PVs |
| `/registry/daemonsets`, `/registry/priorityclasses` | 2 + 2 | |
| `/registry/statefulsets`, `poddisruptionbudgets`, `storageclasses`, `csinodes`, `ingressclasses`, `mutatingwebhookconfigurations`, `validatingwebhookconfigurations`, `validatingadmissionpolicies`, `validatingadmissionpolicybindings`, `servicecidrs` | 1 each | |
| `/registry/ingress` | 1 | **Ingress**: stored as `ingress`, not `ingresses` |
| `/registry/minions` | 1 | **Nodes**: stored as `minions`, an old name for nodes |
| `/registry/masterleases/192.168.49.2` | 1 | each API server's IP, with a **15 s** lease, used to build the `kubernetes` Service's endpoints |
| `/registry/ranges/servicenodeports` | 1 | a bitmap of which NodePorts are taken |
| `compact_rev_key` | 1 | **not under `/registry`**: the API server's compaction bookkeeping (§4.4) |

Things to notice:

- **The etcd key name doesn't always match the resource name.** `minions` holds Nodes, `services/specs` holds Services, `services/endpoints` holds Endpoints, and `ingress` holds Ingresses. These are historical names that were never changed, because changing them would need a data migration. RBAC and `kubectl` use the resource names; only the etcd path is odd.
- **Built-in groups leave the group out of the path** (`/registry/deployments`, not `/registry/apps/deployments`). **CRDs include it** (`/registry/gateway.networking.k8s.io/httproutes/...`).
- **Plenty of keys are made by controllers, not by you.** One `kubectl create ns` gives a Namespace, a `default` ServiceAccount and a `kube-root-ca.crt` ConfigMap.

### 2.2 One `kubectl create deployment`: which keys appear

**(lab)** In the empty namespace `etcd-demo`, seven commands:

```
kubectl create ns etcd-demo
kubectl -n etcd-demo create configmap app-config --from-literal=color=blue
kubectl -n etcd-demo create secret generic app-secret --from-literal=password=fake-pass-v1
kubectl -n etcd-demo create deployment web --image=nginx:1.27 --replicas=2
kubectl -n etcd-demo expose deployment web --port=80
kubectl -n etcd-demo create serviceaccount app-sa
kubectl apply -f httproute.yaml   # HTTPRoute demo-route → web:80
```

These produced **27 keys**:

```
/registry/namespaces/etcd-demo                                        ← you
/registry/serviceaccounts/etcd-demo/default                           ← ServiceAccount controller
/registry/configmaps/etcd-demo/kube-root-ca.crt                       ← root-CA publisher controller
/registry/configmaps/etcd-demo/app-config                             ← you
/registry/secrets/etcd-demo/app-secret                                ← you
/registry/deployments/etcd-demo/web                                   ← you
/registry/replicasets/etcd-demo/web-66d47686b4                        ← Deployment controller
/registry/pods/etcd-demo/web-66d47686b4-2vq9l                         ← ReplicaSet controller
/registry/pods/etcd-demo/web-66d47686b4-wkqws                         ← ReplicaSet controller
/registry/services/specs/etcd-demo/web                                ← you (kubectl expose)
/registry/services/endpoints/etcd-demo/web                            ← Endpoints controller
/registry/endpointslices/etcd-demo/web-7s4z5                          ← EndpointSlice controller
/registry/serviceaccounts/etcd-demo/app-sa                            ← you
/registry/gateway.networking.k8s.io/httproutes/etcd-demo/demo-route   ← you
/registry/events/etcd-demo/web.18e5d49297ff4270                       ← Deployment controller
/registry/events/etcd-demo/web-66d47686b4.18e5d4929883a24b            ← ReplicaSet controller
/registry/events/etcd-demo/web-66d47686b4-2vq9l.18e5d492994138f6      ← scheduler / kubelet
... 12 more Events (Scheduled, Pulling, Pulled, Created, Started per Pod)
```

`kubectl delete ns etcd-demo` removed **all 27**. The namespace controller deletes everything inside the namespace before deleting the Namespace key itself.

---

## 3. How Kubernetes uses etcd: values

### 3.1 Three encodings

| What | Stored as | How to tell |
|---|---|---|
| Built-in kinds (Pod, Secret, Deployment …) | **protobuf**, wrapped in `runtime.Unknown` | first 4 bytes `6b 38 73 00` = `k8s\0` |
| Custom resources **and CRDs themselves** | **JSON** | first byte `{` |
| Anything, if encryption at rest is on | encrypted | prefix `k8s:enc:<provider>:v1:<keyname>:` ([secrets-and-env.md](secrets-and-env.md) §5) |

**(code)** The magic number is `protoEncodingPrefix = []byte{0x6b, 0x38, 0x73, 0x00}` in `apimachinery/pkg/runtime/serializer/protobuf/protobuf.go`. Encryption is the `transformer.TransformToStorage` and `TransformFromStorage` calls in `etcd3/store.go`. Those calls run on every write and read, between the encoder and etcd.

**(lab)** The first 40 bytes of the demo Secret in etcd:

```
$ etcdctl get /registry/secrets/etcd-demo/app-secret --print-value-only | head -c 40 | xxd
00000000: 6b38 7300 0a0c 0a02 7631 1206 5365 6372  k8s.....v1..Secr
00000010: 6574 12dc 010a b701 0a0a 6170 702d 7365  et........app-se
00000020: 6372 6574 1200 1a09                      cret....
```

Reading the bytes:

- `6b 38 73 00` is the magic.
- `0a 0c` is field 1 (TypeMeta), 12 bytes long: `0a 02 "v1"` (apiVersion) and `12 06 "Secret"` (kind).
- `12 dc 01` is field 2 (raw), 220 bytes: the Secret itself.

### 3.2 The full structure of a stored Secret

**(lab)** Decoded with a small protobuf reader. These are protobuf field numbers from `k8s.io/api/core/v1/generated.proto`:

```
runtime.Unknown
├─ 1 typeMeta: apiVersion "v1", kind "Secret"
└─ 2 raw (the Secret, 220 bytes)
   ├─ 1 metadata (ObjectMeta, 183 bytes)
   │   ├─ 1 name               'app-secret'
   │   ├─ 3 namespace          'etcd-demo'
   │   ├─ 5 uid                'ef350fcc-89e3-4656-8bae-ce204ff38ec6'
   │   ├─ 6 resourceVersion    ''          ← EMPTY: not stored (§4.1)
   │   ├─ 8 creationTimestamp  {seconds: 1794073752}
   │   └─ 17 managedFields     {manager 'kubectl-create', operation 'Update',
   │                            fieldsV1 '{"f:data":{".":{},"f:password":{}},"f:type":{}}'}
   ├─ 2 data                   {'password': 'fake-pass-v1'}   ← PLAIN TEXT, not even base64
   └─ 3 type                   'Opaque'
```

This confirms [secrets-and-env.md](secrets-and-env.md) §2 at the byte level. Base64 is only the JSON form the API returns. etcd holds the raw bytes, and without encryption at rest they're readable.

### 3.3 Every demo object, decoded

**(lab)** `etcdctl get <key> -w json`, decoded:

| Key | Encoding | Size | create_rev | mod_rev | version | lease | managedFields |
|---|---|---|---|---|---|---|---|
| `namespaces/etcd-demo` | protobuf `v1 Namespace` | 294 B | 461648 | 461648 | 1 | 0 | 1 (125 B) |
| `configmaps/etcd-demo/app-config` | protobuf `v1 ConfigMap` | 214 B | 461651 | 461651 | 1 | 0 | 1 (86 B) |
| `secrets/etcd-demo/app-secret` | protobuf `v1 Secret` | 245 B | 461652 | 461652 | 1 | 0 | 1 (101 B) |
| `serviceaccounts/etcd-demo/app-sa` | protobuf `v1 ServiceAccount` | **109 B** | 461675 | 461675 | 1 | 0 | 0 |
| `services/specs/etcd-demo/web` | protobuf `v1 Service` | 550 B | 461672 | 461672 | 1 | 0 | 1 (307 B) |
| `services/endpoints/etcd-demo/web` | protobuf `v1 Endpoints` | 743 B | 461673 | 461737 | 3 | 0 | 1 (250 B) |
| `endpointslices/etcd-demo/web-7s4z5` | protobuf `discovery.k8s.io/v1 EndpointSlice` | 1,098 B | 461674 | 461738 | 3 | 0 | 1 (446 B) |
| `deployments/etcd-demo/web` | protobuf `apps/v1 Deployment` | 1,920 B | 461653 | 461739 | **6** | 0 | 2 (1,261 B) |
| `replicasets/etcd-demo/web-66d47686b4` | protobuf `apps/v1 ReplicaSet` | 1,686 B | 461654 | 461736 | 5 | 0 | 2 (1,032 B) |
| `pods/etcd-demo/web-…-2vq9l` | protobuf `v1 Pod` | 3,312 B | 461657 | 461726 | 5 | 0 | 3 (1,693 B) |
| `events/etcd-demo/web.18e5…` | protobuf `v1 Event` | 603 B | 461656 | 461656 | 1 | **8128049235254434026** | 1 (241 B) |
| `gateway.networking.k8s.io/httproutes/etcd-demo/demo-route` | **JSON** `gateway.networking.k8s.io/v1 HTTPRoute` | 1,002 B | 461676 | 461676 | 1 | 0 | n/a |
| `leases/kube-node-lease/minikube` | protobuf `coordination.k8s.io/v1 Lease` | 471 B | **230** | 461750 | **22,784** | 0 | 1 (250 B) |
| `minions/minikube` | protobuf `v1 Node` | 6,756 B | **28** | 461451 | 1,323 | 0 | 6 (2,007 B) |
| `apiextensions.k8s.io/customresourcedefinitions/httproutes.gateway.networking.k8s.io` | **JSON**, stored as `apiextensions.k8s.io/v1beta1` | **152,934 B** | 232459 | 232644 | 4 | 0 | n/a |

What the numbers say:

- **`version` counts writes.**
  - The Deployment was written 6 times while rolling out: created, then status updates as Pods came up.
  - The node's heartbeat **Lease** has been written **22,784 times** since revision 230. The kubelet renews it about every 10 s **(not tested)**, and 22,784 × 10 s ≈ 63 h, which fits the ≈68 h uptime in §4.4.
  - The Node object itself was written only 1,323 times, because the kubelet updates Node status less often. That was the point of moving heartbeats to small Lease objects.
- **`managedFields` is often half the object.** On the Pod, 1,693 of 3,312 bytes are server-side-apply bookkeeping. On the Deployment it's 1,261 of 1,920. This is why `kubectl get -o yaml` hides it by default, and why etcd use grows with the number of field managers.
- **Custom resources are JSON.** The HTTPRoute value starts with `{"apiVersion":"gateway.networking.k8s.io/v1",...`. Protobuf needs generated Go types, and a CRD's schema is only known at runtime.
- **The HTTPRoute CRD is the biggest single value in the cluster: 153 KB**, because it embeds the full OpenAPI schema. It's **stored as `apiextensions.k8s.io/v1beta1`** even though you created and read it as `v1`. The stored version is the API server's internal storage version; the API converts on every read **(lab: the stored `apiVersion` field)**.
- **Only the Event has a lease** (§5). Everything else lives until it's deleted.

---

## 4. Revisions in action: `resourceVersion`, history, conflicts, watch, compaction

### 4.1 `resourceVersion` is `mod_revision`

**(code, `etcd3/store.go`):** reads call `s.decoder.Decode(data, out, getResp.KV.ModRevision)`, and the state carries `state.Meta.ResourceVersion = uint64(state.Rev)` where `state.Rev = kv.ModRevision`. On create, setting a `resourceVersion` is an error (`ErrResourceVersionSetOnCreate`).

**(lab)** After two edits to the demo ConfigMap:

```
$ kubectl -n etcd-demo get cm app-config -o jsonpath='{.metadata.resourceVersion}'
461786
$ etcdctl get /registry/configmaps/etcd-demo/app-config -w json      (decoded)
create_revision 461651
mod_revision    461786      ← same number
version         3
```

And the stored bytes have field 6, `resourceVersion`, **empty** (§3.2). The API server adds it on the way out.

That's why `resourceVersion` values from different objects can be compared ("which happened later?"): they're all positions on etcd's single global revision counter. It's still meant to be treated as an opaque string, and the API docs say not to do arithmetic on it.

### 4.2 Optimistic locking: compare-and-swap on `mod_revision`

**(code)** The API server never does a blind `put`. It uses etcd transactions **(`client/v3/kubernetes/client.go`, `OptimisticPut`)**:

```go
txn := k.KV.Txn(ctx).If(
    clientv3.Compare(clientv3.ModRevision(key), "=", expectedRevision),
).Then(
    clientv3.OpPut(key, string(value), clientv3.WithLease(opts.LeaseID)),
)
```

- **Create** calls `OptimisticPut(ctx, key, data, 0, ...)`. `mod_revision == 0` means the key doesn't exist, so a second create of the same name fails with `AlreadyExists`.
- **Update** compares against the revision it read. If someone wrote in between, the `If` is false, and the API server returns **409 Conflict** (`NewResourceVersionConflictsError`).
- **Delete** works the same way, through `OptimisticDelete`.

**(lab)**

```
$ kubectl get cm lock-test -o yaml > stale.yaml           # resourceVersion: "461844"
$ kubectl patch cm lock-test -p '{"data":{"v":"2"}}'      # someone else writes → 461845
$ kubectl replace -f stale.yaml                           # still says 461844
Error from server (Conflict): ... Operation cannot be fulfilled on configmaps "lock-test":
the object has been modified; please apply your changes to the latest version and try again
```

This is the error controllers retry on. `kubectl apply` and `patch` avoid it by sending a *patch*, which the API server applies to the latest version inside its own retry loop (`GuaranteedUpdate`).

### 4.3 No change, no write

**(code, `etcd3/store.go` `updateOnce`):** `if !origState.Stale && bytes.Equal(data, origState.Data) { ... }`. If the newly encoded bytes equal the stored bytes, the API server **skips the etcd write**.

**(lab)**

```
$ kubectl patch cm lock-test --type merge -p '{"data":{"v":"2"}}'    # v is already "2"
configmap/lock-test patched (no change)
mod_revision,version before: 461845 2   after: 461845 2              ← nothing written
```

That matters at scale. Controllers that "update" objects every loop with identical content cost etcd nothing and trigger no watch events.

### 4.4 History and compaction

Every write creates a new revision, and the old value stays readable **(lab)**:

```
# the ConfigMap went blue → green → red, then was deleted
at rev 461786 -> mod_revision 461786, version 3      (red)
at rev 461785 -> mod_revision 461785, version 2      (green)
at rev 461784 -> mod_revision 461651, version 1      (blue, created at 461651)

$ etcdctl get /registry/configmaps/etcd-demo/app-config            # after kubectl delete
(nothing)
$ etcdctl get /registry/configmaps/etcd-demo/app-config --rev=461786
... color red ...                                                   ← a deleted object, still readable
```

A **deleted** object can still be read from history until compaction. That includes deleted Secrets. Rotating a password by overwriting the Secret leaves the old one in etcd for up to about 5 minutes, and in any backup taken in that window.

**Compaction** throws away revisions older than a point. In the API server, this is configured by:

- `DefaultCompactInterval = 5 * time.Minute` (`storagebackend/config.go`);
- the flag `--etcd-compaction-interval`;
- the logic in `etcd3/compact.go`.

The API server keeps a special key, `compact_rev_key`, outside `/registry`:

- its **value** is the last revision it compacted to;
- its **version** is used as a logical clock, so that with several API servers only one compacts per interval. Each one does a `Txn` comparing `Version(compact_rev_key)`.

**(lab)**

```
$ etcdctl get compact_rev_key        (decoded)
value (last compacted rev): 461396 | version (times compacted): 818 | current rev: 461799

$ etcdctl get /registry/minions/minikube --rev=1000
Error: etcdserver: mvcc: required revision has been compacted
```

818 compactions × 5 min ≈ 68 hours of API server uptime across restarts.

Compaction is one interval behind. Each run compacts to the revision recorded on the *previous* run. The code comment in `compact.go` says: "after compaction, it will only contain keys set during last interval". So roughly the last 5–10 minutes of history stay available. **(code; the window wasn't measured.)**

In memory, compaction trims each `keyIndex` (`keyIndex.compact`, §1.2), and in bbolt it deletes the old revision keys. The file stays the same size: the lab's db is 29.6 MB with 4.4 MB in use.

### 4.5 Watch: history read as a stream

A watch is "tell me every change after revision R". etcd answers it by:

- reading the stored revisions after R from bbolt (old events);
- then sending new ones as they're applied.

Watchers live in memory, in `watchableStore` (`synced` and `unsynced` groups). The lab had **110** of them (`etcd_debugging_mvcc_watcher_total`), mostly the API server's.

**(lab)** `etcdctl watch /registry/configmaps/etcd-demo/app-config --prev-kv` during the edits:

```
PUT     mod_revision 461785  version 2  value [green]  prev [blue]
PUT     mod_revision 461786  version 3  value [red]    prev [green]
DELETE  mod_revision 461789  version 0  value []       prev [red]
```

If a client asks to watch from a compacted revision, etcd refuses. Kubernetes turns that into **`410 Gone`** ("too old resource version"), and informers then re-list. See [watching-the-api-server.md](watching-the-api-server.md).

### 4.6 The API server's own in-memory copy: the watch cache

Clients don't watch etcd directly. **Each API server holds one etcd watch per resource type**, keeps the current objects plus a window of recent events **in its own memory**, and serves all `kubectl get`, list and watch calls from that cache **(not tested in code here; it's the `cacher` package)**.

**(lab)** `kubectl get --raw /metrics`:

```
apiserver_storage_objects{resource="pods"}         30
apiserver_storage_objects{resource="events"}      157
apiserver_storage_objects{resource="secrets"}       9
apiserver_watch_cache_resource_version{resource="configmaps"}  461830   ← the revision the
apiserver_watch_cache_resource_version{resource="pods"}        461826     cache has caught up to
apiserver_watch_cache_resource_version{resource="secrets"}     461827
```

So a Kubernetes object is in memory in up to **three places**:

1. etcd's index, which holds only the key and its revisions;
2. etcd's page cache, which holds the value bytes;
3. every API server's watch cache, which holds the decoded object.

That's a large part of why big clusters need large control-plane memory.

---

## 5. TTLs: how Events expire (etcd leases)

Kubernetes has almost no expiring objects. The main exception is **Events**, which are created with a TTL equal to `--event-ttl`, default **1 h** (`EventTTL: 1 * time.Hour`, `pkg/controlplane/apiserver/options/options.go`).

**(code, `etcd3/store.go` `Create`):** if `ttl != 0`, the API server gets an etcd **lease** and attaches it with `clientv3.WithLease`. When the lease expires, etcd deletes every key attached to it.

Asking etcd for a new lease per Event would be costly. The **lease manager** reuses one lease for all writes in a window (`lease_manager.go`):

- `defaultLeaseReuseDurationSeconds = 60`;
- `defaultLeaseMaxObjectCount = 1000`;
- the requested TTL is padded so a reused lease never expires early.

**(lab)** Every lease in etcd:

```
lease 70cca1b71839dcb6  TTL 15s    remaining 7s     keys 1    /registry/masterleases/
lease 70cca1ac1183fe00  TTL 3660s  remaining 2979s  keys 4    /registry/events/
lease 70cca1b71839c17a  TTL 3660s  remaining 2981s  keys 138  /registry/events/
lease 70cca1b71839c1ac  TTL 3660s  remaining 2981s  keys 1    /registry/events/
lease 70cca1b71839cb66  TTL 3660s  remaining 3067s  keys 1    /registry/events/
lease 70cca1b71839d8ea  TTL 3660s  remaining 3548s  keys 13   /registry/events/   ← the demo Pods' events
```

- **3,660 s = 3,600 s event TTL + 60 s reuse window.** **138 Events share one lease**, so they'll all disappear together.
- The **masterleases** key has a **15 s** lease. The API server refreshes it to say "I'm alive at 192.168.49.2". If it stops, its IP drops out of the `kubernetes` Service's endpoints.
- The **coordination `Lease` objects** (`/registry/leases/...`, used for node heartbeats and leader election) have **no** etcd lease (`lease: 0`). They're ordinary keys that are rewritten, which is why the node Lease has `version 22,784`. Two different things share the name "lease".

---

## 6. Following one write end to end

`kubectl -n etcd-demo patch configmap app-config -p '{"data":{"color":"green"}}'`:

```
kube-apiserver
  1. authn / authz (RBAC on resource "configmaps", verb "patch")
  2. GuaranteedUpdate: read current object (watch cache or etcd), apply the patch
  3. admission (mutating, then validating), validation
  4. encode → protobuf "k8s\0" + Unknown{TypeMeta, raw}         (§3)
  5. bytes equal to the stored bytes? → stop, "no change"        (§4.3)
  6. transformer: encrypt if EncryptionConfiguration says so     (secrets-and-env.md §5)
  7. Txn IF mod_revision(/registry/configmaps/etcd-demo/app-config) == 461651
         THEN put(value)                                         (§4.2)
etcd leader
  8. raft: append entry to WAL, fsync; replicate to followers; wait for a majority
  9. apply: main revision 461784 → 461785
       treeIndex:  keyIndex(app-config).generations[last].revs += {461785, 0}   (RAM)
       backend:    put key=00 00 00 00 00 07 0b d9 '_' 00…00  value=KeyValue{...,version:2}  (buffer → bbolt ≤100 ms)
       watchers:   send PUT event to everyone watching this key or prefix
 10. reply: header.revision 461785
kube-apiserver
 11. resourceVersion = "461785"; watch cache updated from its own etcd watch
 12. response to kubectl; informers in controllers receive the event
```

In step 9, the bbolt key is the 17-byte encoding of revision `{461785, 0}`: 461785 = `0x070bd9` as 8 big-endian bytes, then `_`, then sub = 0 as 8 bytes. The revisions and versions match the lab run in §4.4. The step list itself is from the code, not traced live.

---

## 7. Practical consequences

| Fact | Consequence |
|---|---|
| Values are stored unencrypted by default | Anyone with the `db` file or a snapshot has every Secret. Turn on encryption at rest ([secrets-and-env.md](secrets-and-env.md) §5) and protect backups. |
| Old revisions stay until compaction | A deleted or rotated Secret stays readable for minutes, and forever in any backup taken in that window. |
| The db file never shrinks without `defrag` | After deleting lots of objects, `DB SIZE` stays high (here 29.6 MB vs 4.4 MB used). Run `etcdctl defrag` per member, one at a time, because it blocks that member. |
| 2 GiB default quota | Hitting it puts the cluster in read-only mode (`NOSPACE` alarm). Watch `etcd_mvcc_db_total_size_in_bytes`. |
| 1.5 MiB max request (etcd default) and 1 MiB Secret/ConfigMap limit | Big CRDs (153 KB here) and huge ConfigMaps eat into this. The Kubernetes limits keep objects well under etcd's limit **(etcd limit: not tested)**. |
| `managedFields` can be half an object | Many field managers on hot objects make etcd and every watch cache bigger. |
| Every write is a WAL fsync | etcd's speed is your disk's fsync latency (`etcd_disk_wal_fsync_duration_seconds`). Use SSDs; don't share the disk with noisy neighbours. |
| Heartbeats moved to Lease objects | Node status writes dropped. The small Lease (471 B) takes the 10-second writes instead of the 6.7 KB Node. |
| No-op updates are skipped | Writing the same content again is free. Controllers rely on this. |
| Index rebuilt at start | Restarting a large etcd takes time. Startup time grows with the number of keys and revisions kept. |

---

## 8. Commands used

```bash
# etcdctl inside the minikube etcd Pod (certs from the Pod's own flags)
kubectl -n kube-system exec etcd-minikube -- etcdctl --endpoints=https://127.0.0.1:2379 \
  --cacert=/var/lib/minikube/certs/etcd/ca.crt \
  --cert=/var/lib/minikube/certs/etcd/server.crt \
  --key=/var/lib/minikube/certs/etcd/server.key  <command>

endpoint status -w table                       # db size, in use, raft index, leader
get / --prefix --keys-only                     # every key under /
get "" --from-key --keys-only                  # every key, including compact_rev_key
get <key> -w json                              # create/mod revision, version, lease, base64 value
get <key> --rev=<n>                            # value at an older revision
watch <key> --prev-kv -w json                  # live changes with old values
lease list ; lease timetolive <id> --keys      # leases and their keys

# memory and disk, from the node
minikube ssh -- "curl -s http://127.0.0.1:2381/metrics"          # etcd metrics
minikube ssh -- 'sudo grep -E "VmRSS|RssAnon|RssFile|VmSize" /proc/$(pgrep -x etcd)/status'
minikube ssh -- 'sudo ls -la /var/lib/minikube/etcd/member/{snap,wal}'

# API server side
kubectl get --raw /metrics | grep -E 'apiserver_storage_objects|apiserver_watch_cache_resource_version'
```
