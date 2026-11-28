# Day 2: etcd backup and restore, upgrades and version skew

Backing up and restoring the cluster's state, and upgrading without breaking the rules about
which component versions may talk to which. The backup and restore were run on a single-node k3s
v1.34.1+k3s1 server with embedded etcd (`--cluster-init`), in Docker; the upgrade parts come from
the documentation.

Labels: **(lab)** seen in the run below; **(docs)** from the Kubernetes, etcd or k3s documentation.

## 0. Short answers

- **Everything the API server knows is in etcd,** so an etcd snapshot is the cluster backup.
  Volumes, container images and anything outside the cluster are not in it
  ([etcd-storage.md](etcd-storage.md) shows what the keys look like).
- **A snapshot is one consistent file.** `k3s etcd-snapshot save` wrote 794 656 bytes to
  `server/db/snapshots/` and recorded it as an `ETCDSnapshotFile` object (§1). Plain etcd:
  `etcdctl snapshot save`.
- **A restore rewinds the whole cluster.** Objects created after the snapshot vanished, deleted
  ones came back with their old `resourceVersion` (§2). Restart every client that caches watches
  afterwards: controllers have seen newer versions than the restored ones **(docs)**.
- **Restoring creates a new one-member etcd cluster** from the file (`--cluster-reset` in k3s,
  `etcdutl snapshot restore` plus a fresh data directory upstream). Other members are rejoined
  from scratch.
- **Upgrade the control plane first, one minor version at a time, then the kubelets.** The
  kubelet may be up to three minor versions older than the API server, never newer (§3).

## 1. Taking a snapshot (lab)

```text
$ kubectl create configmap before --from-literal=k=v1          resourceVersion 387
$ k3s etcd-snapshot save --name snap1
level=info msg="Snapshot snap1-etcdlab-1795699938 saved."
$ k3s etcd-snapshot ls
Name                     Location                                                 Size   Created
snap1-etcdlab-1795699938 file:///var/lib/rancher/k3s/server/db/snapshots/snap1-…  794656 2026-11-26T13:32:18Z
$ kubectl get etcdsnapshotfiles
local-snap1-etcdlab-1795699938-1308e6   file:///var/lib/rancher/k3s/server/db/snapshots/…   794656
```

Then, after the snapshot: `kubectl create configmap after`, `kubectl delete configmap before`.

- **Where etcd runs in k3s:** inside the k3s process, configured by
  `server/db/etcd/config` (client URL `https://127.0.0.1:2379`, peers on 2380, metrics on
  `http://127.0.0.1:2381`, mutual TLS with certificates under `server/tls/etcd/`).
- **Scheduled snapshots:** k3s takes one every 12 hours and keeps 5 by default
  (`--etcd-snapshot-schedule-cron`, `--etcd-snapshot-retention`), and can upload them to S3
  **(docs)**. A snapshot on the same disk as etcd doesn't survive losing that disk: copy it off.
- **The snapshot holds Secrets.** If encryption at rest isn't on, they're in it in base64; protect
  the file like the cluster's credentials ([secrets-and-env.md](secrets-and-env.md) §5).

## 2. Restoring it (lab)

```text
$ docker stop etcdlab
$ k3s server --cluster-reset \
    --cluster-reset-restore-path=/var/lib/rancher/k3s/server/db/snapshots/snap1-etcdlab-1795699938
msg="restored snapshot" path=…/snap1-etcdlab-1795699938
msg="Starting etcd for new cluster, cluster-reset=true"
msg="kvstore restored" current-rev=407
msg="Cluster reset: backing up certificates directory to /var/lib/rancher/k3s/server/tls-1795699951"
msg="Managed etcd cluster membership has been reset, restart without --cluster-reset flag now.
     Backup and delete ${datadir}/server/db on each peer etcd server and rejoin the nodes"
$ docker start etcdlab
$ kubectl get cm
before    1     39s                     ← back, as it was
                                        ← "after" is gone
before rv=387 data={"k":"v1"}          ← the same resourceVersion as before the snapshot
$ kubectl create cm probe               new object rv=441
$ ls server/db/
etcd   etcd-old-1795699946   snapshots  ← the previous data directory, moved aside
```

- **`current-rev=407`:** the snapshot's revision. New writes continued above it (441).
- **Why restart clients:** a controller that had seen `after` (revision ~410) holds a watch from a
  revision the restored etcd reissued for different changes. Kubernetes' guidance is to restart
  the API servers, then the controllers, scheduler and kubelets, after a restore **(docs)**.
- **Upstream etcd** without k3s: `etcdutl snapshot restore snap.db --data-dir /new/dir`, then start
  etcd with that directory and a new `--initial-cluster` **(docs)**.

## 3. Version skew (docs)

| Component | Allowed versions, relative to the newest kube-apiserver |
|---|---|
| kube-apiserver (HA) | the newest, or one minor older during an upgrade |
| kube-controller-manager, kube-scheduler, cloud-controller-manager | same, or one minor older |
| kubelet, kube-proxy | same, or up to three minor older; never newer |
| kubectl | one minor older, same, or one newer |

Two consequences:

- **Upgrade one minor version at a time** (1.33 → 1.34 → 1.35), control plane before nodes. A
  node group two versions behind can wait for the control plane to finish; it has to be upgraded
  before the gap reaches four.
- **Removed APIs break on the control plane upgrade, not later.** Manifests using an API version
  that the new release no longer serves fail to apply. Check before upgrading (the API server's
  `apiserver_requested_deprecated_apis` metric, or tools such as `pluto` or `kubent`).

## 4. Upgrading (docs)

**kubeadm:**

```text
control plane node 1:  kubeadm upgrade plan
                       kubeadm upgrade apply v1.35.x      (static Pod manifests, certificates, CoreDNS, kube-proxy)
other control planes:  kubeadm upgrade node
every node:            kubectl drain <node> --ignore-daemonsets
                       upgrade the kubelet and kubectl packages, systemctl restart kubelet
                       kubectl uncordon <node>
```

`kubeadm upgrade apply` also renews the control plane certificates, which otherwise expire after
a year ([cloud-kubernetes.md](cloud-kubernetes.md) has `kubeadm certs check-expiration`).

**k3s:** replace the binary and restart, servers first, or use the system-upgrade-controller with
`Plan` objects. **Managed clusters** (EKS, AKS, GKE): the provider upgrades the control plane on
request; node groups are upgraded by replacing nodes, which drains them with evictions, so
PodDisruptionBudgets apply ([pod-shutdown-and-probes.md](pod-shutdown-and-probes.md) §5).

**Before any upgrade:** take an etcd snapshot (§1), read the changelog's "Urgent upgrade notes",
and check deprecated API use.

## Related notes

- [etcd-storage.md](etcd-storage.md): how objects are stored in etcd.
- [control-plane-manifests.md](control-plane-manifests.md): the static Pods kubeadm upgrades.
- [watching-the-api-server.md](watching-the-api-server.md): the watches that need restarting after a restore.
- [scheduling.md](scheduling.md): cordon and drain.
