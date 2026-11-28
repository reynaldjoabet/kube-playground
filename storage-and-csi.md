# Storage: PersistentVolumes, StorageClasses and CSI

How a PersistentVolumeClaim becomes a directory or disk inside a container: provisioning, binding,
volume topology, attach and mount, expansion and deletion. Run on the k3s + Calico lab cluster
(v1.34.1+k3s1) twice: with k3s's built-in `local-path` provisioner, and with the Kubernetes CSI
`hostpath` driver (csi-driver-host-path v1.17.0, hostpathplugin v1.16.1).

Labels: **(lab)** seen in the runs below; **(docs)** from the Kubernetes or CSI documentation.

## 0. Short answers

- **A PVC is a request, a PV is the volume, a StorageClass says how to make one.** A provisioner
  watches PVCs of its class, creates the storage and a PV, and the PV controller binds the two
  one-to-one **(docs)**.
- **`WaitForFirstConsumer` delays provisioning until a Pod is scheduled,** so the volume is made
  where the Pod will run. The scheduler writes `volume.kubernetes.io/selected-node` on the PVC;
  that's the provisioner's signal (§1).
- **So a Pod with `spec.nodeName` never gets such a volume.** `nodeName` skips the scheduler, the
  annotation is never written, and the claim stayed Pending (§1).
- **A local volume ties the Pod to its node.** The PV carries `nodeAffinity`; a later Pod that had
  to run elsewhere got "didn't match PersistentVolume's node affinity" (§2). Cloud disks do the
  same per zone.
- **CSI splits the work over gRPC calls:** the external-provisioner sidecar calls `CreateVolume`,
  the attacher handles `ControllerPublishVolume`, the kubelet calls `NodeStageVolume` and
  `NodePublishVolume` over a socket on the node. All of them appeared in the driver's log (§4).
- **Expansion is two steps:** `ControllerExpandVolume` (the storage) then `NodeExpandVolume` (the
  filesystem, by the kubelet); in between the PVC shows `FileSystemResizePending` (§5).
- **`reclaimPolicy: Delete` deletes the data with the PVC.** Both runs removed the volume within
  seconds of `kubectl delete pvc`. Use `Retain` for data you can't recreate.

## 1. local-path: binding waits for a Pod (lab)

```text
$ kubectl get sc local-path
NAME         PROVISIONER             BINDING                RECLAIM   DEFAULT
local-path   rancher.io/local-path   WaitForFirstConsumer   Delete    true

PVC data alone:
data   Pending   local-path
event WaitForFirstConsumer: waiting for first consumer to be created before binding
```

**First try: a Pod with `nodeName: cal-agent2`.** It never started, and the PVC stayed Pending
for minutes: nothing set `selected-node`, because the Pod was never scheduled.

**Second try: a Pod without `nodeName`** (the scheduler placed it on `cal-agent1`):

```text
events on the PVC:
  ExternalProvisioning   Waiting for a volume to be created either by the external provisioner
                         'rancher.io/local-path' or manually by the system administrator. …
  Provisioning           External provisioner is provisioning volume for claim "st/data"
  ProvisioningSucceeded  Successfully provisioned volume pvc-7e41b15f-…
annotations:
  volume.kubernetes.io/selected-node: cal-agent1
  pv.kubernetes.io/bind-completed: "yes"
the PV:
  path=/var/lib/rancher/k3s/storage/pvc-7e41b15f-…_st_data
  nodeAffinity: kubernetes.io/hostname in ["cal-agent1"]   reclaim=Delete
```

`local-path` isn't a CSI driver: it's an external provisioner that creates a directory on the
node (with a short-lived helper Pod) and a `hostPath`-style PV for it.

## 2. The volume pins the Pod (lab)

After writing `hello` to the volume and deleting the Pod:

```text
a Pod with nodeSelector kubernetes.io/hostname: cal-agent2
  0/3 nodes are available: 1 node(s) didn't match PersistentVolume's node affinity,
  2 node(s) didn't match Pod's node affinity/selector.
a Pod with no constraint
  scheduled on cal-agent1, reads: hello
```

The scheduler's `VolumeBinding` filter checks the PV's `nodeAffinity`. If `cal-agent1` were lost,
so would the data, and the Pod couldn't run anywhere: local storage suits caches and replicated
databases, not single copies.

## 3. Deleting the claim (lab)

```text
$ kubectl -n st delete pvc data
PV gone after 6 s
kube-system events: helper-pod-delete-pvc-7e41b15f-…  Pulled, Created, Started
$ ls -d /var/lib/rancher/k3s/storage/pvc-7e41b15f-*      (on cal-agent1)
ls: cannot access …: No such file or directory
```

## 4. A CSI driver, call by call (lab)

The hostpath driver runs as one Pod, `csi-hostpathplugin-0`, with the driver and its sidecars:

```text
csi-hostpathplugin-0 (8 containers, on cal-agent1)
├── hostpath                 the CSI driver: Identity, Controller and Node services on one socket
├── csi-provisioner          watches PVCs      → CreateVolume / DeleteVolume
├── csi-attacher             watches VolumeAttachments → ControllerPublishVolume
├── csi-resizer              watches PVC size  → ControllerExpandVolume
├── csi-snapshotter          watches VolumeSnapshotContents (unused here)
├── csi-external-health-monitor-controller
├── node-driver-registrar    registers the driver's socket with the kubelet
└── liveness-probe
```

The sidecars are generic, from the Kubernetes CSI project; only `hostpath` is driver-specific.
A cloud driver (EBS, Azure Disk, PD) has the same shape, usually split into a controller
Deployment and a node DaemonSet ([secrets-and-env.md](secrets-and-env.md) draws the EBS one).

**StorageClass and PVC:**

```yaml
provisioner: hostpath.csi.k8s.io     volumeBindingMode: Immediate     allowVolumeExpansion: true
```

```text
c1    Bound   pvc-9a211deb-…   1Gi   RWO   csi-hostpath-sc         (5 s after creation: Immediate)
PV:   csi.driver=hostpath.csi.k8s.io  volumeHandle=2b675cbb-e99c-11f1-…
      nodeAffinity topology.hostpath.csi/node=["cal-agent1"]       (from the driver's topology)
```

**The calls the driver logged,** for create, use, expand and delete:

| Step | gRPC call | Made by |
|---|---|---|
| PVC created | `Controller/CreateVolume` | csi-provisioner |
| Pod scheduled | (no `ControllerPublishVolume`) | csi-attacher: "CSI driver does not support ControllerPublishUnpublish, using trivial handler", then "Marked as attached" |
| Pod starting on the node | `Node/NodeStageVolume`, then `Node/NodePublishVolume` | the kubelet |
| PVC resized | `Controller/ControllerExpandVolume`, then `Node/NodeExpandVolume` | csi-resizer, then the kubelet |
| Pod and PVC deleted | `Node/NodeUnpublishVolume`, `Controller/DeleteVolume` | the kubelet, csi-provisioner |

- **Stage and publish are two mounts.** `NodeStageVolume` mounts the device once per node (a
  global path); `NodePublishVolume` bind-mounts it into each Pod's directory. A disk used by two
  Pods on one node is staged once **(docs)**.
- **A `VolumeAttachment` object is created even when the driver has nothing to attach:**

```text
csi-2d8c4f23…   hostpath.csi.k8s.io   pvc-9a211deb-…   cal-agent1   true
```

  For a cloud disk this is where the disk is attached to the VM (`ControllerPublishVolume`), and
  why a disk can be stuck "attached to another node" after that node dies.

**On the node:**

```text
/var/lib/kubelet/plugins_registry/hostpath.csi.k8s.io-reg.sock   registrar → kubelet
/var/lib/kubelet/plugins/csi-hostpath/csi.sock                   kubelet → driver (CSI gRPC)
/var/lib/kubelet/pods/<pod uid>/volumes/kubernetes.io~csi/pvc-9a211deb-…/
├── mount                    where NodePublishVolume mounted it; bind-mounted into the container
└── vol_data.json            {"driverName":"hostpath.csi.k8s.io","nodeName":"cal-agent1",
                              "volumeHandle":"2b675cbb-…","attachmentID":"csi-2d8c4f23…", …}
```

## 5. Expansion (lab)

```text
$ kubectl -n st patch pvc c1 -p '{"spec":{"resources":{"requests":{"storage":"2Gi"}}}}'
events: ExternalExpanding, Resizing "External resizer is resizing volume …",
        FileSystemResizeRequired "Require file system resize of volume on node"
spec=2Gi status=1Gi conditions=Resizing FileSystemResizePending
… about 12 s later
FileSystemResizeSuccessful   MountVolume.NodeExpandVolume succeeded for volume "pvc-9a211deb-…" cal-agent1
status=2Gi
```

The Pod kept running: online expansion. The class needs `allowVolumeExpansion: true`; shrinking
isn't supported **(docs)**.

## 6. Access modes and the rest (docs)

| Access mode | Meaning |
|---|---|
| `ReadWriteOnce` | mounted read-write by one **node** (several Pods on it can share it) |
| `ReadWriteOncePod` | one Pod only |
| `ReadOnlyMany` | read-only by many nodes |
| `ReadWriteMany` | read-write by many nodes: NFS, CephFS, Azure Files, EFS; not block disks |

- **StatefulSets** create one PVC per replica from `volumeClaimTemplates` (`data-web-0`, …). Scaling
  down keeps the PVCs, unless `persistentVolumeClaimRetentionPolicy` says otherwise.
- **Snapshots** (`VolumeSnapshot`) need the snapshot CRDs, the snapshot controller and a driver
  that supports them; this lab installed only the CRDs.
- **Ephemeral volumes:** `emptyDir` lives as long as the Pod; a generic ephemeral volume is a PVC
  created and deleted with the Pod.

## Related notes

- [scheduling.md](scheduling.md): the filters that read volume topology.
- [statefulsets-and-daemonsets.md](statefulsets-and-daemonsets.md): per-replica volumes.
- [secrets-and-env.md](secrets-and-env.md): the Secrets Store CSI driver and the EBS CSI layout.
- [container-runtime.md](container-runtime.md): the kubelet side of a Pod start.
