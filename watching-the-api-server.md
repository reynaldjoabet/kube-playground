# Watching the API server: how controllers like the CCM and Argo CD see changes

Controllers don't poll. A cloud controller manager (CCM), Argo CD, kube-proxy, CoreDNS and the scheduler all **list** a resource once, then hold a **watch** open. The watch is one long HTTP response that the API server keeps writing to whenever a matching object changes. This doc follows a change from etcd to a controller's code: the HTTP API, the API server's watch cache, client-go's informer machinery, and finally how the CCM's service controller and Argo CD's application controller use it.

---

## 0. Terms

| Term | Meaning |
|---|---|
| **list** | `GET /api/v1/namespaces/ns/configmaps`: returns every matching object plus the collection's `resourceVersion` at that moment. |
| **watch** | The same URL with `?watch=1`. The response never ends on its own: the server writes one JSON event per change until a timeout or an error. |
| **resourceVersion (RV)** | A string on every object and every list. It comes from etcd's revision counter, which goes up by one on every write anywhere in the cluster. Clients must treat it as opaque, but it orders changes. |
| **event** | One line of a watch: `{"type": "...", "object": {...}}`. The type is `ADDED`, `MODIFIED`, `DELETED`, `BOOKMARK` or `ERROR` (`apimachinery/pkg/watch/watch.go`). |
| **bookmark** | An event that carries no object, only a newer RV. It lets a client move its "resume from" point forward when nothing it watches has changed. |
| **watch cache** | The API server's in-memory copy of each resource type plus a ring buffer of recent events. Most watches are served from it, not from etcd. |
| **informer** | client-go's list-then-watch loop plus a local cache and callbacks. Nearly every Go controller uses one. |
| **reflector** | The part of an informer that does the HTTP list and watch calls. |
| **indexer / lister** | The informer's local cache, and the read API over it. Controllers read from here, not from the API server. |
| **workqueue** | A queue of object keys (`namespace/name`) that need work. It merges duplicate keys and retries failures with backoff. |
| **reconcile** | Look at the current state of one key and make the world match it. It's called "level-triggered" because it acts on the state, not on the event that triggered it. |

---

## 1. The whole path in one diagram

```
  writer (kubectl apply, a kubelet, another controller)
     |
     |  PUT/PATCH/POST/DELETE
     v
 +---------------------------------------------- kube-apiserver --------------+
 |                                                                            |
 |   validation, admission ----> etcd3 store ---- write ----> +--------+      |
 |                                                           |  etcd  |      |
 |                                                           +--------+      |
 |                                                               |           |
 |              ONE etcd watch per resource type (prefix watch)   |           |
 |              e.g. /registry/configmaps/   <-------------------+           |
 |                         |                                                  |
 |                         v                                                  |
 |   +------------------ Cacher (per resource type) -----------------+       |
 |   |  watchCache: current objects + ring buffer of recent events   |       |
 |   |  dispatchEvents(): for each event, for each matching watcher  |       |
 |   +----+---------------------+---------------------+--------------+       |
 |        |                     |                     |                      |
 |   cacheWatcher          cacheWatcher          cacheWatcher   (one per     |
 |   (CCM, services)       (Argo CD, cm)         (kubectl -w)    open watch) |
 +--------|---------------------|---------------------|----------------------+
          |  HTTP/1.1 or HTTP/2, chunked, one JSON event per line
          v                     v                     v
 +---- cloud-controller-manager ----+   +---- argocd-application-controller ----+
 |  informer(Service)               |   |  informer(Application, AppProject)    |
 |  informer(Node)                  |   |  cluster cache: one watch per         |
 |    -> handlers -> workqueue      |   |  resource kind in the cluster         |
 |    -> workers -> cloud API       |   |    -> map object to app -> queue      |
 +----------------------------------+   +---------------------------------------+
```

Three things to take from it:

1. The API server watches etcd **once per resource type**, not once per client (§3).
2. Every client gets its own stream, filtered by namespace, label selector and field selector.
3. Each client keeps its own copy of the objects it cares about and works from that copy (§4).

---

## 2. The HTTP API

### 2.1 A watch is a GET that doesn't end

The OpenAPI spec (`api/openapi-spec/swagger.json`) gives every list path the same query parameters: `watch`, `resourceVersion`, `resourceVersionMatch`, `allowWatchBookmarks`, `sendInitialEvents`, `timeoutSeconds`, `labelSelector`, `fieldSelector`, `limit`, `continue` and the new `shardSelector`. There are also old `/api/v1/watch/...` paths, but the spec marks them deprecated: "use the 'watch' parameter with a list operation instead."

The pattern is: list, take the list's RV, then watch from that RV. Run the watch in the background and make three changes:

```sh
RV=$(curl -s localhost:8011/api/v1/namespaces/watch-demo/configmaps \
     | python3 -c 'import json,sys;print(json.load(sys.stdin)["metadata"]["resourceVersion"])')
echo LIST RV=$RV

curl -sN "localhost:8011/api/v1/namespaces/watch-demo/configmaps?watch=1&resourceVersion=$RV&timeoutSeconds=8" > watch1.out &
sleep 1
kubectl -n watch-demo create configmap demo --from-literal=color=blue
kubectl -n watch-demo patch configmap demo -p '{"data":{"color":"green"}}'
kubectl -n watch-demo delete configmap demo
wait
```

Summarised (type, name, RV, data):

```
LIST RV=57960
ADDED    demo rv=57961 {'color': 'blue'}
MODIFIED demo rv=57962 {'color': 'green'}
DELETED  demo rv=57963 {'color': 'green'}
```

And the raw first line, cut short:

```
{"type":"ADDED","object":{"kind":"ConfigMap","apiVersion":"v1","metadata":{"name":"demo","namespace":"watch-demo","uid":"be720f48-...","resourceVersion":"57961",...
```

Things to notice:

- **Every event carries the whole object**, not a diff. A `DELETED` event carries the object as it was just before deletion (`meta/v1/watch.go`: "the state of the object immediately before deletion").
- **The RVs climb**: 57960 for the list, then 57961, 57962, 57963. Here they are consecutive because nothing else in the cluster wrote in between. Normally there are gaps, because the counter is shared by every object.
- **The response is chunked** and stays open:

  ```
  $ curl -sN -D - -o /dev/null "localhost:8011/api/v1/namespaces/watch-demo/configmaps?watch=1&timeoutSeconds=1"
  HTTP/1.1 200 OK
  Content-Type: application/json
  Transfer-Encoding: chunked
  ...
  ```

  The server flushes after each event (`apiserver/pkg/endpoints/handlers/watch.go`). The same handler also supports WebSocket upgrades, but client-go uses plain HTTP streaming.

### 2.2 Resuming: the resourceVersion is a bookmark into history

A watch from an old RV replays everything after it. Start a new watch from 57960, after all three changes have already happened:

```
$ curl -sN ".../configmaps?watch=1&resourceVersion=57960&timeoutSeconds=2"
ADDED demo 57961
MODIFIED demo 57962
DELETED demo 57963
```

The events come from the watch cache's ring buffer (§3). That replay is what lets a client reconnect after a dropped connection without missing anything.

The buffer is finite. Ask for an RV that has fallen out of it:

```
$ curl -sN ".../configmaps?watch=1&resourceVersion=1&timeoutSeconds=2"
{"type":"ERROR","object":{"kind":"Status","apiVersion":"v1","metadata":{},"status":"Failure","message":"too old resource version: 1 (57113)","reason":"Expired","code":410}}
```

- **410 Expired** (also called "Gone") means "I no longer have history that far back; 57113 is the oldest I have." The error arrives as an `ERROR` event inside a `200 OK` stream, not as an HTTP status.
- **The only fix is to list again**, which gives a fresh RV. Every client library has this rule built in (§4.3).

### 2.3 What the three ways of starting a watch return

| Request | What you get first |
|---|---|
| `watch=1&resourceVersion=<N>` | Only changes after N. |
| `watch=1` with no RV, or `resourceVersion=0` | A synthetic `ADDED` for every existing object, then changes. |
| `watch=1&sendInitialEvents=true&resourceVersionMatch=NotOlderThan` | The same synthetic `ADDED` events, then a `BOOKMARK` marking "initial state done". This is "watch-list" (streaming list), §2.5. |

A watch with no RV on the namespace, after creating configmaps `a` and `b`:

```
ADDED a 57977
ADDED b 57978
ADDED kube-root-ca.crt 57958
```

Notice the RVs here are out of order: `kube-root-ca.crt` was created earlier. These events describe current state, not history. client-go's reflector knows this and doesn't trust the RVs until the synthetic events end (the `propagateRVFromStart` comment in `client-go/tools/cache/reflector.go`).

### 2.4 Bookmarks

With `allowWatchBookmarks=true` the server may send a `BOOKMARK` with no object, only a newer RV:

```
$ curl -sN ".../configmaps?watch=1&resourceVersion=57963&allowWatchBookmarks=true&timeoutSeconds=8"
{"type":"BOOKMARK","object":{"kind":"ConfigMap","apiVersion":"v1","metadata":{"resourceVersion":"57970"}}}
{"type":"BOOKMARK","object":{"kind":"ConfigMap","apiVersion":"v1","metadata":{"resourceVersion":"57970"}}}
```

No configmap in `watch-demo` changed. Other objects in the cluster did, though, so the global counter moved from 57963 to 57970.

Why it matters: a watcher on a quiet resource otherwise keeps an old RV forever. When it reconnects, that RV may have fallen out of the buffer, which means a 410 and a full re-list. A bookmark moves its resume point forward for free.

When bookmarks are sent: the Cacher sends them about once a minute (`defaultBookmarkFrequency = time.Minute` in `apiserver/pkg/storage/cacher/cacher.go`) and just before a watch's timeout. That's why both of them here arrived near the 8-second deadline. The spec warns clients not to rely on any timing.

### 2.5 Watch-list: the initial list as a stream

A big list (all pods in a large cluster) is one huge JSON response that the server must build in memory. Watch-list replaces it with a stream:

```
$ curl -sN ".../configmaps?watch=1&sendInitialEvents=true&resourceVersionMatch=NotOlderThan&allowWatchBookmarks=true&timeoutSeconds=3"
ADDED a 57977
ADDED b 57978
ADDED kube-root-ca.crt 57958
BOOKMARK - 57978 {'k8s.io/initial-events-end': 'true'}
```

The bookmark with the `k8s.io/initial-events-end: "true"` annotation is the marker (`InitialEventsAnnotationKey` in `apimachinery/pkg/apis/meta/v1/types.go`). Everything before it is current state. After it, the same stream continues with live changes.

In client-go this is the `WatchListClient` feature gate: Beta and **on by default since 1.35** (`client-go/features/known_features.go`). If the server refuses, the reflector logs "Falling back to regular list" and does a normal paginated list (`reflector.go`, `ListAndWatchWithContext`).

### 2.6 Selectors: objects can enter and leave a watch

A watch with `labelSelector=team=x` only shows objects that match. An object that **starts** matching arrives as `ADDED`; one that **stops** matching arrives as `DELETED`, even though nothing was deleted:

```sh
curl -sN ".../configmaps?watch=1&labelSelector=team%3Dx&resourceVersion=$RV&timeoutSeconds=5" &
kubectl -n watch-demo label cm b team=x                 # b enters the selector
kubectl -n watch-demo annotate cm b note=hi             # b changes while matching
kubectl -n watch-demo label cm b team=y --overwrite     # b leaves the selector
```

```
ADDED    b rv=57987 labels= {'team': 'x'} ann= {}
MODIFIED b rv=57988 labels= {'team': 'x'} ann= {'note': 'hi'}
DELETED  b rv=57989 labels= {'team': 'x'} ann= {'note': 'hi'}
```

The `DELETED` event carries the object's last state that still matched (`team: x`), stamped with the RV of the change that made it stop matching. `cm b` still exists, at RV 57989.

This matters for anything that watches with a selector: from its point of view, the object is gone.

### 2.7 kubectl does the same thing

`kubectl get -w` is list-then-watch. With `-v=6` it prints its two requests:

```
$ kubectl -n watch-demo get cm -w -v=6
url="https://127.0.0.1:51565/api/v1/namespaces/watch-demo/configmaps?limit=500"
url="https://127.0.0.1:51565/api/v1/namespaces/watch-demo/configmaps?resourceVersion=57995&watch=true"
```

It lists (500 per page), then watches from the list's RV.

---

## 3. Inside the API server: one etcd watch, many clients

```
          etcd revision stream for prefix /registry/configmaps/
                                  |
                                  |  clientv3.Watch(WithRev(rev+1), WithPrevKV())
                                  v
 +----------------------------- Cacher (configmaps) -----------------------------+
 |                                                                               |
 |  watchCache                                                                   |
 |  +--------------------------+   +-------------------------------------------+ |
 |  | store: every configmap,   |   | history ring buffer: recent events        | |
 |  | current version          |   | [ ...57961 ADDED, 57962 MOD, 57963 DEL ]  | |
 |  +--------------------------+   | size 100 .. 102400 (grows/shrinks)        | |
 |                                 +-------------------------------------------+ |
 |                                                                               |
 |  dispatchEvents()  -- per event:                                              |
 |     1. try a non-blocking send to every interested watcher                    |
 |     2. watchers whose buffers were full: wait, up to a shared time budget     |
 |     3. still full when the budget runs out  ->  close that watcher            |
 +------+----------------------+-----------------------+-------------------------+
        |                      |                       |
   cacheWatcher           cacheWatcher            cacheWatcher
   input chan ----> filter (ns, labels, fields) ----> encode ----> HTTP stream
```

What each piece does, from source:

- **One etcd watch per type.**
  - `apiserver/pkg/storage/etcd3/watcher.go` opens it with `clientv3.WithRev(initialRev + 1)` and `WithPrevKV()`.
  - The previous value is what lets the API server build `DELETED` events and spot selector exits (§2.6): it compares the old and new object against each watcher's selector.
- **The ring buffer** is `watchCacheHistory` (`watch_cache_history.go`). It runs between `defaultLowerBoundCapacity = 100` and `defaultUpperBoundCapacity = 100 * 1024` events and is sized to hold at least `DefaultEventFreshDuration` of history: the one-minute bookmark interval plus 15 s. A watch from an RV older than the buffer's start gets the 410 from §2.2.
- **Slow clients are cut off.**
  - `dispatchEvent` first tries `nonblockingAdd` on every watcher, "which make faster watchers not be blocked by slower ones."
  - Watchers that are still blocked get a share of a time budget.
  - `cacheWatcher.add` then logs `Forcing ... watcher close due to unresponsiveness` and closes the stream (`cache_watcher.go`).
  - The client sees its watch end and reconnects from its last RV. If it fell too far behind, that reconnect gets a 410 and it lists again.
- **Encoding is shared.** The comment above `setCachingObjects` explains that each object is wrapped so its JSON or protobuf encoding is computed once and reused for every watcher getting the same event.

You can count open watches by resource on the API server's metrics endpoint. On this minikube (2 nodes, Calico):

```
$ kubectl get --raw /metrics | grep '^apiserver_longrunning_requests{' | grep 'verb="WATCH"'
11 resource="configmaps"      scope="resource"
10 resource="services"        scope="cluster"
 7 resource="endpointslices"  scope="cluster"
 7 resource="nodes"           scope="cluster"
 7 resource="namespaces"      scope="cluster"
 6 resource="pods"            scope="cluster"
 5 resource="ippools"         group="crd.projectcalico.org"
 ...
```

(Columns trimmed.) These are the kubelets, kube-proxy, CoreDNS, the scheduler, kube-controller-manager and Calico. Each process watches a resource type about once, however many controllers inside it use that type (§4.4).

---

## 4. Inside a controller: client-go's informer

Almost every Go controller uses `client-go/tools/cache`. The CCM does directly. Argo CD does for its own CRDs, and uses a lower-level variant for everything else (§6).

### 4.1 The pieces

```
                kube-apiserver
                  ^        |
       LIST (once)|        | WATCH stream (events)
                  |        v
 +------------------------------------------------------------- one informer --+
 |  Reflector                                                                   |
 |    ListAndWatch(): list or watch-list, then watch, remember last RV         |
 |        |                                                                    |
 |        | Add / Update / Delete / Replace / Sync deltas                      |
 |        v                                                                    |
 |  FIFO queue (RealFIFO: events kept in arrival order)                        |
 |        |                                                                    |
 |        | processDeltas(): popped one at a time, or in batches               |
 |        v                                                                    |
 |  Indexer (thread-safe map key -> object, plus indexes, e.g. by namespace)   |
 |        |  updated FIRST, so handlers and listers see the new state          |
 |        v                                                                    |
 |  sharedProcessor.distribute()                                               |
 |        |                       |                        |                   |
 |   processorListener      processorListener        processorListener         |
 |   (ring buffer, starts   (one per AddEventHandler call; a slow handler      |
 |    at 1024, grows)        never blocks the others)                          |
 +--------|-----------------------|------------------------|-------------------+
          v                       v                        v
     OnAdd/OnUpdate/OnDelete handlers: decide whether to care, then
     queue.Add("namespace/name")   <- a key, not the object
          |
          v
 +---------------- workqueue (rate limited) --------------------+
 |  dirty set: keys waiting      processing set: keys in work   |
 |  - adding a key that's already waiting does nothing          |
 |  - a key is never handed to two workers at once              |
 |  - failures: AddRateLimited -> exponential backoff           |
 +-----------------------------+--------------------------------+
                               |  Get() / Done() / Forget()
                               v
                 worker goroutines: syncX(key)
                   obj := lister.Get(key)       <- reads the Indexer, no API call
                   compare desired vs actual, call the cloud / git / API
                   write status back through the API server
```

Notes, from source:

- **Queue type.** The queue between the reflector and the indexer used to be `DeltaFIFO`. Since the `InOrderInformers` gate went GA (locked on in 1.36), `newQueueFIFO` in `controller.go` builds a `RealFIFO`, which keeps events in the order they arrived. `AtomicFIFO` and `UnlockWhileProcessingFIFO` (Beta, on by default in 1.36) refine how it locks.
- **Cache before handlers.** `processDeltas` updates the indexer and *then* calls the handlers. A handler reading the lister always sees the state the event describes, or something newer.
- **Each handler gets its own buffer.** `newProcessListener(..., initialBufferSize = 1024, ...)` gives every `AddEventHandler` registration a growable ring buffer, so a slow handler delays only itself.
- **Deletions you missed.** If the watch dropped and a delete happened in the gap, the next list notices the object is gone and calls `OnDelete` with a `DeletedFinalStateUnknown` wrapper (`delta_fifo.go`), so handlers must accept it. That's why the CCM uses `cache.DeletionHandlingMetaNamespaceKeyFunc` (§5).
- **The workqueue holds keys.** If a Service changes five times while a worker is busy, the key sits in the queue once and the worker then reads the newest state from the lister. That's level-triggered: the code reconciles "what is the state now", never "what did event #3 say".

### 4.2 The reflector's loop

```
 start
   |
   v
 [list] --- RV="0" first time (may be served from the watch cache)
   |        RV=""  after a 410 (consistent read from etcd)
   |        pages of 500 (client-go/tools/pager: defaultPageSize = 500)
   |        or watch-list (§2.5) when WatchListClient is on (default since 1.35)
   |
   |  store.Replace(all items), remember list RV
   v
 [watch from last RV] <-------------------------------+
   |  timeoutSeconds = random 300..600 s              |
   |  allowWatchBookmarks = true                      |
   |                                                  |
   |-- event --------> push to FIFO, last RV = event's RV
   |-- BOOKMARK -----> last RV = bookmark's RV (nothing pushed)
   |-- timeout / EOF ---------------------------------+  (no re-list: just watch again)
   |-- connection refused, 429 ------> wait, then watch again
   |
   |-- 410 Expired / too large RV
   v
 mark "last RV unavailable", return error
   |
   v
 backoff 0.8 s, doubling up to 30 s with jitter (reset after 2 min healthy)
   |
   +--> back to [list] with RV=""
```

From `reflector.go`:

- **Watch timeout.** `defaultMinWatchTimeout = 5 * time.Minute`, max `2 *` that. "We try to spread the load on apiserver by setting timeouts for watch requests." When the timeout ends a watch, the reflector simply watches again from its last RV. That isn't a re-list.
- **Backoff.** `defaultBackoffInit = 800ms`, `defaultBackoffMax = 30s`, factor 2, jitter 1.0, reset after `defaultBackoffReset = 2 * time.Minute`. The comment says this cuts traffic to "0.22 QPS" when the API server is unhealthy.
- **Re-list after a 410.** `relistResourceVersion()` returns `""` once `isLastSyncResourceVersionUnavailable` is set. That forces a quorum read from etcd, because the watch cache just said it can't serve that RV.

### 4.3 "Resync" is not "re-list"

Informers take a `resyncPeriod`. When it fires, the reflector calls `store.Resync()`, which **re-delivers every object already in the local cache to the handlers as an update** (old and new are the same object). There's no API call.

It exists so a controller gets another chance at objects whose earlier sync failed in a way it didn't retry, or whose external state (a cloud LB) drifted. Two settings you'll meet:

- **kube-controller-manager and CCM:** the informer factory's resync is `--min-resync-period` (default `12h`, `controller-manager/config/v1alpha1/defaults.go`) times a random factor in [1, 2): `ResyncPeriod()` in `cloud-provider/app/controllermanager.go`.
- **Per-handler overrides:** `AddEventHandlerWithResyncPeriod`. The CCM's service controller uses 30 s for Services and 100 s for Nodes (§5).

### 4.4 Shared informers: one watch per type per process

`informers.NewSharedInformerFactory(client, resync)` hands every controller in the process the **same** informer for a type. The node controller, node lifecycle controller, route controller and service controller in a CCM all call `.Core().V1().Nodes()`, and the process opens a single Node watch. That's why §3's metrics show a handful of watches per type, not one per controller.

`factory.Start()` starts the informers. `cache.WaitForCacheSync(..HasSynced)` blocks until the initial list has gone through, so a controller doesn't act on a half-filled cache. For example, it would otherwise think every load balancer is orphaned.

---

## 5. The CCM, concretely: the service controller

`cloud-provider/controllers/service/controller.go`, the generic code every CCM from `cloud-control-manager.md` runs unchanged.

```
  kubectl apply Service type=LoadBalancer
         |
         v
  apiserver ---- watch event ----> CCM Service informer
                                         |
                    AddFunc: wantsLoadBalancer(svc) || needsCleanup(svc) ?
                    UpdateFunc: needsUpdate(old, cur) || needsCleanup(cur) ?
                    (no DeleteFunc: deletion shows up as an update that sets
                     deletionTimestamp, and a finalizer keeps the object until
                     cleanup is done)
                                         |
                                  serviceQueue.Add("default/web")
                                         |
                           worker: syncService("default/web")
                                         |
                       lister.Get  ->  cloud.EnsureLoadBalancer(...)
                                         |                 |
                                   ok: Forget(key)     error: AddRateLimited(key)
                                         |               5 s, 10 s, 20 s ... 300 s
                                         v
                  PATCH status.loadBalancer.ingress  ---> apiserver
                                         |
                                         v
                  that PATCH is itself a watch event, back into the informer:
                  needsUpdate() sees no spec change, so the key is NOT queued again

  Node informer (same process, same shared watch as the node controllers)
     Add / Delete / Update where shouldSyncUpdatedNode() is true:
        only if the exclude-from-external-load-balancers label flipped
        or spec.providerID changed (NOT readiness, NOT heartbeats)
            |
            v
     nodeQueue  --> one node worker updates every LB's backend set;
                    Services whose update failed go back on serviceQueue
```

What the code sets:

- **Two queues**, `serviceQueue` and `nodeQueue`, both with `NewTypedItemExponentialFailureRateLimiter(minRetryDelay = 5s, maxRetryDelay = 300s)`.
- **Filtering happens in the handlers.**
  - The Service handlers skip anything that isn't, or wasn't, a load balancer.
  - The Node `UpdateFunc` calls `shouldSyncUpdatedNode`, which returns true only when `nodeIncludedPredicate` changes (the `node.kubernetes.io/exclude-from-external-load-balancers` label) or `spec.providerID` changes. Kubelet status heartbeats, and even a node going NotReady, don't touch the load balancers. The cloud LB's own health checks deal with dead nodes.
- **Handler resyncs**: `serviceSyncPeriod = 30 * time.Second`, `nodeSyncPeriod = 100 * time.Second`. A resync re-delivers from the local cache with old and new being the same object (§4.3), so `needsUpdate` and `shouldSyncUpdatedNode` both return false. Only Services that `needsCleanup` get queued that way. Retrying failures is the queues' job: `AddRateLimited` for Services, and the node worker re-queuing the Services it failed to update.
- **Wait for the caches first.** `Run` calls `cache.WaitForNamedCacheSyncWithContext(ctx, c.serviceListerSynced, c.nodeListerSynced)` before starting any worker.
- **Leader election.**
  - Only one CCM replica runs these loops. The others wait on a Lease object, renewing and checking it with `LeaseDuration 15s`, `RenewDeadline 10s` and `RetryPeriod 2s` (`component-base/config/v1alpha1/defaults.go`).
  - Leader election is itself built on get/update of a Lease. It isn't done with watches.
  - minikube runs its controller manager with `--leader-elect=false`, so this cluster has no such Lease.

---

## 6. Argo CD, concretely

Argo CD's application controller watches in two different ways.

### 6.1 Its own CRDs: ordinary informers

`controller/appcontroller.go`:

- `newApplicationInformerAndLister()` builds a `cache.NewSharedIndexInformer` over `Application`, with `cache.NamespaceIndex` plus Argo's own indexes, such as one for apps watching orphaned resources.
- `NewAppProjectInformer(...)` does the same for `AppProject`.
- The handlers feed rate-limited workqueues: `app_reconciliation_queue` (`appRefreshQueue`), `app_operation_processing_queue`, `project_reconciliation_queue` and others.

So far this is §4 exactly.

### 6.2 Everything it deploys: the cluster cache

Argo CD has to show and diff **every** resource an Application creates, of any kind, including CRDs it has never heard of. It can't compile in an informer per type, so `gitops-engine/pkg/cache` keeps a cluster-wide cache built from **one dynamic-client watch per API resource kind**:

```
 argocd-application-controller (one cluster cache per managed cluster)
 |
 |  sync():
 |   1. discovery: server version + every API resource (GetAPIResources)
 |   2. for each kind it may read (checks permissions: SelfSubjectAccessReview):
 |        list in pages of 500   (defaultListPageSize, at most
 |                                defaultListSemaphoreWeight = 50 lists at once)
 |        then watchEvents(kind) in its own goroutine
 |
 |  watchEvents(kind):                          one per kind (and per namespace
 |   +--------------------------------------+   if Argo CD is namespace-scoped)
 |   | RetryWatcher(resourceVersion, Watch) |
 |   |   restarts dropped watches from the  |
 |   |   last RV by itself; on 410 it stops |
 |   +------------------+-------------------+
 |                      |
 |      event ----------+-----> recordEvent -> update the Resource node in the
 |                      |                     cache, indexes, parent/child links
 |                      |
 |      CRD event ------+-----> start a watch for the new kind, or stop one
 |      APIService -----+-----> (re)check that group's watches
 |                      |
 |      10 min timer ---+                       (defaultWatchResyncTimeout)
 |      w.Done() (410) -+--> return error -> retry after 1 s with RV=""
 |                               -> list that kind again -> new watch
 |
 |  every 24 h: full sync, rediscover everything (defaultClusterResyncTimeout)
 |
 |  OnResourceUpdated(new, old):
 |     which app owns this?  getApp(): the tracking annotation
 |       argocd.argoproj.io/tracking-id (the default method), or the
 |       app.kubernetes.io/instance label, otherwise walk ownerReferences
 |       up to a parent that has one (Pod -> ReplicaSet -> Deployment)
 |     ---> handleObjectUpdated(app) ---> appRefreshQueue.Add("argocd/my-app")
 v
 app worker: compare live tree (from this cache) with desired manifests (from
 the repo server), set sync/health status, run auto-sync if enabled
```

From `gitops-engine/pkg/cache/cluster.go` and `legacy_engine.go`:

- **Plain watches, not informers.** Argo CD uses `watchutil.NewRetryWatcherWithContext` from `client-go/tools/watch`.
  - The RetryWatcher restarts a dropped watch from the last RV it saw.
  - On `410 Gone` it sends the error and stops, with the comment "Never retry RV too old errors".
  - Argo's loop sees `w.Done()`, returns an error, clears `resourceVersion`, and `RetryUntilSucceed` (every `watchResourcesRetryTimeout = 1s`) lists that kind again.
- **Periodic re-list per kind.** `defaultWatchResyncTimeout = 10 * time.Minute`. Unlike an informer resync (§4.3), this is a real re-list and a new watch. It's a guard against a watch that silently stopped delivering.
- **New CRDs are picked up live.** `handleCRDEvent` reads the CRD's served versions (`crdVersionsToAPIResources`) and starts or stops watches. Applying a CRD and then a CR of it in the same sync works without waiting for the 24 h full sync.
- **Events are batched.** With batch processing on, events are gathered and applied every `defaultEventProcessingInterval = 100ms` (`processEvents`). This cuts lock churn on busy clusters.
- **Cost.** One watch per kind per cluster means Argo CD holds dozens to hundreds of watches per cluster it manages, and keeps a copy of every object in memory. Argo CD's settings for excluding resource kinds and ignoring certain field updates exist to trim this. The "Ignoring change of object because none of the watched resource fields have changed" debug line in `controller/cache/cache.go` is that filter at work.

---

## 7. CCM and Argo CD side by side

| | Cloud controller manager | Argo CD application controller |
|---|---|---|
| Client | typed clientset + `SharedInformerFactory` | typed informers for Application/AppProject; dynamic client + RetryWatcher for everything else |
| What it watches | Services, Nodes (plus whatever the provider adds) | its CRDs, plus every resource kind it's allowed to read, in each managed cluster |
| Watches per type | one, shared by all controllers in the process | one per kind (per namespace if namespace-scoped) |
| Local copy | Indexer per type | Indexer for apps; gitops-engine's own resource tree with parent/child links |
| On 410 | reflector re-lists (RV="") after backoff | RetryWatcher stops; Argo re-lists that kind after 1 s |
| Periodic | informer resync from cache: 12–24 h factory, 30 s / 100 s for the service controller's handlers (which filter most of it out) | real re-list per kind every 10 min; full rediscovery every 24 h |
| Event → work | handler filter → key in `serviceQueue`/`nodeQueue` | resource → owning app (tracking annotation/label, ownerRefs) → `appRefreshQueue` |
| Retries | per-key exponential 5 s → 300 s | custom app rate limiter per queue |
| HA | leader election on a Lease; one active replica | sharded: each controller replica owns a set of clusters |

---

## 8. Does everything go through a watch?

The pattern in this doc applies to nearly everything you create:

```
kubectl apply ─► API server stores it ─► watch event ─► whichever controller watches that kind
                                                        (ReplicaSet controller, CCM, ingress-nginx,
                                                         Envoy Gateway, Argo CD, ...)
```

The API server never calls a controller. Each controller keeps its own watch open and reacts. If no controller is running, the object just waits. When a controller starts, it lists everything and catches up (§4.2). The Ingress test in [ingress-and-gateway-api.md](ingress-and-gateway-api.md) §2.1 shows this: with no ingress controller installed, the Ingress was stored and then sat there with no address, no status and no events.

There are four places where a request doesn't follow this pattern:

```
kubectl apply / create
   │
   ├─(A) BEFORE storing: admission
   │       mutating and validating webhooks, ValidatingAdmissionPolicy
   │       synchronous, inside the request; can change or reject the object
   │       (ingress-nginx's webhook; the replicaset policy test in deployments-and-replicasets.md §2.3)
   │
   ├─(B) INSIDE the API server: work done in the request itself, no controller
   │       Service: ClusterIP (and nodePort) allocated before the response returns
   │       defaults filled in; uid, resourceVersion, creationTimestamp set
   │
   ├─(C) NOT STORED: the API server answers and forgets
   │       SelfSubjectReview, TokenReview, SubjectAccessReview  (create only)
   │       pods/exec, pods/log      streamed through to the kubelet
   │       pods/eviction            PodDisruptionBudget checked, Pod deleted, all in the request
   │       pods/binding             the scheduler's "put this Pod on node X", written onto the Pod
   │
   └─(D) STORED, but no controller acts on it: plain data
           ConfigMap, Secret: read by whoever uses them (kubelet volume mounts, apps)
           nothing "reconciles" them
```

Checked on the lab:

```
$ kubectl -n shop create service clusterip sync-demo --tcp=80:8080 -o jsonpath='{.spec.clusterIP}'
10.104.8.114                       <- (B) already in the create response; no controller involved

$ kubectl auth whoami -o jsonpath='{.kind} {.status.userInfo.username}'
SelfSubjectReview minikube-user    <- (C) answered...
$ kubectl get selfsubjectreviews
Error from server (MethodNotAllowed): the server does not allow this method on the requested resource
                                   <- ...but never stored, so there's nothing to list or watch

$ kubectl api-resources -o wide | grep review
selfsubjectreviews          authentication.k8s.io/v1   SelfSubjectReview          create
tokenreviews                authentication.k8s.io/v1   TokenReview                create
localsubjectaccessreviews   authorization.k8s.io/v1    LocalSubjectAccessReview   create
selfsubjectaccessreviews    authorization.k8s.io/v1    SelfSubjectAccessReview    create
subjectaccessreviews        authorization.k8s.io/v1    SubjectAccessReview        create

$ kubectl get --raw /api/v1     # verbs of some Pod subresources
pods/binding  ['create']
pods/eviction ['create']
pods/exec     ['create', 'get']
pods/log      ['get']
```

Without `list` or `watch` verbs, there's nothing for an informer to follow. These are **actions**, not objects.

Two more variations, not exercised in the lab:

- **Aggregated APIs**, for example metrics-server behind `kubectl top`. The API server forwards the request to another server, so nothing is stored in etcd. `kubectl get apiservices` lists only `Local` groups on this cluster.
- **Static Pods** run the other way. The kubelet starts them from files on the node, then *creates* a mirror Pod object in the API server, so the object follows the container instead of causing it.

**The rule:** stored objects that describe what you want are carried out by controllers watching them. Validation and allocation happen inside the request. Actions (reviews, exec, logs, eviction, binding) are answered on the spot. Plain data (ConfigMaps, Secrets) is read by whoever uses it.

### 8.1 Who dials whom: controllers connect in, the API server rarely connects out

"The API server never calls a controller" is also true at the TCP level. A controller is just a client: it dials the API server, lists, and keeps a watch open. The API server keeps no list of controllers. It only has the connections they opened.

All established connections to the API server's port 8443 on the lab (`ss` on the node), grouped by who opened them:

```
 clients that DIAL IN to kube-apiserver :8443                  conns
 ──────────────────────────────────────────────────────────    ─────
 kube-controller-manager (~30 controllers inside one process)     2
 kube-scheduler                                                   2
 kubelet                                                          1
 kube-proxy                                                       1
 calico-node (host network, via 10.96.0.1 → tunl0 10.244.120.64)  3
 calico-kube-controllers   Pod 10.244.120.117                     1
 CoreDNS ×2                Pods .87, .88                          1 each
 envoy-gateway             Pod .79                                1
 kubectl / minikube from the Mac (192.168.49.1)                   1
```

- **Connections are per process, not per controller.** kube-controller-manager runs about 30 controllers over 2 connections. Their watches run as HTTP/2 streams over those few connections, and shared informers already merge the watches into one per type (§4.4).

  ```
   kube-controller-manager  (one process, --controllers=*)
   ┌──────────────────────────────────────────────────────────────────────┐
   │  Deployment ctrl   ReplicaSet ctrl   EndpointSlice ctrl   Job ctrl … │  ~30 control loops
   │        │                 │                  │               │        │
   │        └──── shared informers: ONE watch per resource type ──┘       │  Pods watched once,
   │                         │  (Pods, ReplicaSets, Services, …)          │  used by many loops
   │                one client-go transport (HTTP/2)                      │
   └─────────────────────────┬────────────────────────────────────────────┘
                             │  2 TCP connections, each carrying many watch streams
                             ▼
                      kube-apiserver :8443
  ```

  | Level | Count | Why |
  |---|---|---|
  | Control loops (e.g. the ReplicaSet controller) | many, with **no connection of their own** | they read from the shared informers inside their process |
  | Watches | **one per resource type per process** | the shared informer factory merges every loop's interest in, say, Pods into one watch |
  | TCP connections | **a few per process** | HTTP/2 runs many watches as streams over one connection |

  On the lab, the API server had **258 open WATCH requests over 15 TCP connections** (the sum of `apiserver_longrunning_requests{verb="WATCH"}` from `/metrics`, against `ss`). The metric doesn't say which client holds each watch, so the 258 can't be split by process. Separate programs (CoreDNS, Envoy Gateway, a CCM, Argo CD, kubectl) each have their own connection because each is its own process. A CCM is like kube-controller-manager: its service, node and route controllers share one process.
- **Pods dial `10.96.0.1:443`**, the `kubernetes` Service, which kube-proxy rewrites to `192.168.49.2:8443`. They get that IP from the `KUBERNETES_SERVICE_HOST` environment variable, so finding the API server needs no DNS ([core-dns.md](core-dns.md) §9).

The API server dials out only to serve a request that's in progress, never to push work to a controller:

| The API server dials | When | Seen on the lab |
|---|---|---|
| etcd :2379 | All the time; it's where every object is stored (§3) | 115 connections |
| kubelet :10250 | `kubectl logs`, `exec`, `attach`, `port-forward`, node proxy: (C) above | 0 before, **2 while** a `logs -f` and an `exec` were running |
| an admission webhook's Service | When an object it covers is created or updated: (A) above | `envoy-gateway-topology-injector` → Service `envoy-gateway` |
| an aggregated API's Service | Requests to that API group, e.g. `metrics.k8s.io` | none installed |
| a CRD conversion webhook | Reading or writing a CRD in a different version than the one stored | not checked |
| authn/authz webhooks, KMS | Only if configured on the API server | not configured on minikube |

```
                ┌──────── dial IN, then list + watch (the normal case) ────────┐
 controller-manager, scheduler, kubelet, kube-proxy, CoreDNS, CCM, operators, ingress/Gateway controllers
                                       │
                                       ▼
                               kube-apiserver ───────► etcd   (storage, always)
                                       │
                ┌──── dial OUT, only during a request ──────────────────────────┐
                ▼                      ▼                        ▼
       kubelet :10250        admission webhook Svc     aggregated API Svc
       (logs/exec/pf)        (on create/update)        (e.g. metrics.k8s.io)
```

Even "the controller reacted immediately" is a watch event coming back down a connection the controller opened.

**kubectl is just another client.** It's a client-go program, like most controllers. It reads `~/.kube/config` for the address and credentials, then makes plain HTTPS requests:

```
$ kubectl get ns kube-system -v=6
Config loaded from file:  /Users/username/.kube/config
"Response" verb="GET" url="https://127.0.0.1:61431/api/v1/namespaces/kube-system" status="200 OK"
```

(`127.0.0.1:61431` is minikube's Docker port-forward to the API server's `:8443`.)

It doesn't share connections with controllers. A connection belongs to one process. These are the connections from the Mac (`192.168.49.1`), counted on the API server's side:

```
before                        1   ← the `minikube tunnel` process (it watches Services; lsof shows "minikube")
during kubectl get pods -A -w 2   ← kubectl opened its own
after Ctrl-C                  1   ← and it closed with the process
after a one-shot kubectl get  1   ← opened, one GET, closed before it could be counted
```

A one-shot `kubectl get` opens a connection and closes it when the process exits. `-w` and `logs -f` keep one open while they run. Controllers keep theirs open for days.

What differs between clients is the identity, not the treatment. Every request goes through the same authentication → authorization (RBAC) → admission path:

| Client | Identity on the lab | Its permissions come from |
|---|---|---|
| kubectl | `minikube-user`, group `system:masters` | that group skips RBAC entirely: full access |
| kube-controller-manager | client cert `CN=system:kube-controller-manager` | RBAC role `system:kube-controller-manager` |
| kube-scheduler, kubelet, kube-proxy | their own certs (`system:kube-scheduler`, `system:node:<name>`, …) | their own roles; the kubelet is also limited by the Node authorizer |
| CoreDNS, Envoy Gateway, Argo CD | a ServiceAccount token mounted into the Pod | the RBAC their install created |

```
 kubectl ──┐  own TLS connection, cert = minikube-user
 CCM ──────┤  own connection, SA token
 CoreDNS ──┼──► kube-apiserver :8443 ──► authn ─► RBAC ─► admission ─► etcd
 kcm ──────┤  own connection, cert = system:kube-controller-manager
 kubelet ──┘  own connection, cert = system:node:<name>
```

`kubectl get -w` is the same list-then-watch a controller does (§2.7). It prints the events instead of reconciling them.

Checked on the lab: the `-v=6` output, the connection counts, the `minikube-user` identity (`kubectl auth whoami`), and the controller-manager's certificate subject (from `/etc/kubernetes/controller-manager.conf`). The other rows' identities are the standard kubeadm names and weren't inspected here.

How it was measured (inside `minikube ssh`):

```sh
sudo ss -tnpH state established '( dport = :8443 )'     # host-network clients, with process names
sudo ss -tnH  state established '( sport = :8443 )'     # API server's side: peer IPs, incl. Pods
sudo ss -tnpH state established '( dport = :10250 )' | grep kube-apiserver   # run during kubectl logs -f / exec
kubectl get validatingwebhookconfigurations,mutatingwebhookconfigurations
```

### 8.2 Every process in the lab cluster, and who it talks to

`ps` on the minikube node, grouped. A "cluster" is just these processes. Only some of them talk to the API server, and the API server is the only one that talks to etcd.

```
 ┌─ outside Kubernetes: host services, not Pods ─────────────────────────────────────────────────────┐
 │  dockerd ─ containerd ─ containerd-shim ×75 (one per container)    the container runtime          │
 │  cri-dockerd        CRI adapter: kubelet ⇄ Docker over a unix socket                              │
 │  kubelet            the node agent; starts everything below                                       │
 └───────────────────────────────────────────────────────────────────────────────────────────────────┘
          │ kubelet reads /etc/kubernetes/manifests/*.yaml  →  STATIC Pods (control plane)
          ▼
 ┌─ control plane (static Pods, hostNetwork) ────────────────────────────────────────────────────────┐
 │  etcd                      storage; only the API server talks to it                               │
 │  kube-apiserver            the hub; everything below dials it                                     │
 │  kube-controller-manager   ~30 control loops (Deployment, ReplicaSet, EndpointSlice, Node, …)     │
 │  kube-scheduler            picks a node for each new Pod (writes pods/binding)                     │
 └───────────────────────────────────────────────────────────────────────────────────────────────────┘
          │ kube-controller-manager / scheduler create the rest as ordinary Pods (DaemonSets, Deployments)
          ▼
 ┌─ node add-ons (DaemonSets, hostNetwork) ──────┐   ┌─ cluster add-ons (Deployments, Pod network) ─────┐
 │  kube-proxy     Services → iptables rules     │   │  coredns ×2               cluster DNS            │
 │  calico-node    Pod network, policy           │   │  calico-kube-controllers  Calico's own GC loops  │
 │    + bird, bird6 BGP daemons (no API access)  │   │  envoy-gateway            Gateway API controller │
 └───────────────────────────────────────────────┘   │  envoy (+ shutdown-manager) the Gateway's proxy  │
                                                     └──────────────────────────────────────────────────┘
 ┌─ workloads ─────────────────────────────────────────────────────────────────────────────────────────┐
 │  agnhost ×16 (the lab's apps: moneystack, lattice, shop, svc-demo, dns-demo, search)                 │
 │  pause ×27   one per Pod: holds the Pod's network namespace while app containers come and go       │
 └─────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

Who each one talks to:

| Process | Runs as | Talks to the API server? | Other connections |
|---|---|---|---|
| dockerd, containerd, containerd-shim | host service | no | kubelet → cri-dockerd → dockerd → containerd, over unix sockets |
| cri-dockerd | host service | no | the CRI socket kubelet uses |
| kubelet | host service | **yes**: watches its Pods, writes Pod status, Node heartbeat (Lease) | runtime over the CRI socket; the API server dials *it* on :10250 for logs/exec |
| etcd | static Pod | no; it's dialled **by** the API server | 115 connections from the API server |
| kube-apiserver | static Pod | n/a: it's the server | dials etcd always; kubelet / webhooks / aggregated APIs when needed (§8.1) |
| kube-controller-manager | static Pod | **yes**, 2 connections, as `system:kube-controller-manager` | none |
| kube-scheduler | static Pod | **yes**, 2 connections | none |
| kube-proxy | DaemonSet | **yes**: watches Services, EndpointSlices | writes iptables rules in the node's kernel |
| calico-node | DaemonSet | **yes**, 3 connections (via `10.96.0.1`, source `tunl0`) | programs routes; bird/bird6 speak BGP to other nodes (none here) |
| calico-kube-controllers | Deployment | **yes**, 1 connection | none |
| coredns ×2 | Deployment | **yes**, 1 connection each | the node's resolver for outside names ([core-dns.md](core-dns.md)) |
| envoy-gateway | Deployment | **yes**, 1 connection | serves xDS on Service `envoy-gateway` :18000; the API server calls its webhook |
| envoy (Gateway proxy) | Deployment made by envoy-gateway | **no** | 1 connection to `10.109.166.162:18000` (xDS from envoy-gateway); app Pods |
| shutdown-manager | sidecar in the envoy Pod | no | drains envoy on Pod termination |
| agnhost (apps) | Deployments / StatefulSet | no (they don't use their ServiceAccount token) | each other, via Services |
| pause | one per Pod | no | none; it only holds the network namespace |

Two things this shows:

- **Not every process in a Pod is a controller.** Envoy is in the data path but never talks to the API server. Its controller (`envoy-gateway`) watches the API and pushes config to it over xDS. The ingress-nginx Pod is different: the controller and nginx run in one Pod, and only the controller part watches.
- **The control plane is ordinary processes too.** On kubeadm-style clusters (minikube included), etcd, the API server, controller-manager and scheduler are static Pods: the kubelet runs them from files, before any API server exists (§8, "Static Pods"). Managed clusters (EKS, GKE, AKS) run these on the provider's machines, so `ps` on a worker node shows only the bottom part of the diagram.

How it was gathered:

```sh
minikube ssh -- "ps -eo comm= | sort | uniq -c | sort -rn"                      # process names and counts
minikube ssh -- "sudo ls /etc/kubernetes/manifests"                              # the static Pods
kubectl get pod -A -o 'custom-columns=POD:.metadata.name,CONTAINERS:.spec.containers[*].name'
minikube ssh -- 'P=$(pgrep -x envoy); sudo nsenter -t $P -n ss -tn'              # Envoy's own netns: only :18000
```

Not checked: which init starts the host services (`ps` shows both `systemd` and `runsvdir` in the minikube container), and per-connection counts for the agnhost Pods beyond the API server's peer list, where none of them appeared.

---

## 9. Try it yourself

```sh
kubectl create ns watch-demo
kubectl proxy --port=8011 &

# list, then watch from the list's RV
RV=$(curl -s localhost:8011/api/v1/namespaces/watch-demo/configmaps | jq -r .metadata.resourceVersion)
curl -sN "localhost:8011/api/v1/namespaces/watch-demo/configmaps?watch=1&resourceVersion=$RV" &
kubectl -n watch-demo create configmap x --from-literal=a=1   # ADDED
kubectl -n watch-demo patch configmap x -p '{"data":{"a":"2"}}'   # MODIFIED
kubectl -n watch-demo delete configmap x                     # DELETED

# history too old: 410 inside a 200 stream
curl -sN "localhost:8011/api/v1/namespaces/watch-demo/configmaps?watch=1&resourceVersion=1"

# streaming initial list, ending with the initial-events-end bookmark
curl -sN "localhost:8011/api/v1/namespaces/watch-demo/configmaps?watch=1&sendInitialEvents=true&resourceVersionMatch=NotOlderThan&allowWatchBookmarks=true&timeoutSeconds=3"

# who is watching what
kubectl get --raw /metrics | grep '^apiserver_longrunning_requests{' | grep 'verb="WATCH"'

# clean up
kubectl delete ns watch-demo; kill %1
```
