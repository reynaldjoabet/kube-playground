# The container runtime: kubelet, CRI, containerd, the shim, runc and pause

What actually runs on a node when a Pod starts, looked at from the node itself. On the k3s + Calico
lab cluster (v1.34.1+k3s1), node `cal-agent1`: containerd 2.1.4-k3s2, runc 1.3.1, cgroup v2.

Labels: **(lab)** seen on the node; **(docs)** from the Kubernetes or containerd documentation.

## 0. Short answers

- **The chain is kubelet → CRI (gRPC) → containerd → one shim per Pod → runc → the processes.**
  The kubelet only speaks CRI, over `unix:///run/k3s/containerd/containerd.sock` here. containerd
  pulls images and prepares an OCI bundle; runc creates the namespaces and cgroups and starts the
  process, then exits. The shim stays as the processes' parent (§2).
- **A Pod is a sandbox: the `pause` process holds the shared namespaces.** The app containers
  joined pause's network, IPC and UTS namespaces, and got their own PID, mount and cgroup
  namespaces (§1).
- **That's why containers in a Pod share `localhost` and the Pod IP, but not files or processes.**
  `shareProcessNamespace: true` makes them share the PID namespace too **(docs)**.
- **A container dying is restarted inside the same sandbox,** keeping the Pod IP. **The `pause`
  process dying destroys the sandbox:** the kubelet made a new one, with a new IP, and restarted
  every container (§3).
- **`crictl` is the node-side `kubectl`** for this layer: `crictl pods`, `ps`, `inspect`,
  `inspectp`, `logs`, `images`, `stats` (§4).

## 1. One Pod, three processes (lab)

A Pod `two` with two busybox containers, `a` (`sleep 3600`) and `b` (`sleep 3601`):

```text
$ crictl pods --name two
POD ID              CREATED          STATE    NAME   NAMESPACE   ATTEMPT
65f37f5c9a961       4 minutes ago    Ready    two    rt          0
$ crictl ps --pod 65f37f5c9a961
CONTAINER           IMAGE            STATE    NAME
5dec3cdfcb0e0       b7c873bd97bdc    Running  b
3e614ffea368d       b7c873bd97bdc    Running  a
```

The namespaces of each process (`readlink /proc/<pid>/ns/*`, last four digits of the inode):

```text
pid       process       net    ipc    uts    pid    mnt    cgroup
2777716   /pause        2605   3146   3145   3147   3144   2829
2777766   sleep 3600    2605   3146   3145   3149   3148   3150
2777793   sleep 3601    2605   3146   3145   3152   3151   3153
                        └──── shared ────┘   └──── per container ───┘
```

The OCI spec containerd wrote for container `a` says the same, from the other side
(`config.json` in its bundle):

```text
"namespaces":[{"type":"pid"},
              {"type":"ipc","path":"/proc/2777716/ns/ipc"},
              {"type":"uts","path":"/proc/2777716/ns/uts"},
              {"type":"mount"},
              {"type":"network","path":"/proc/2777716/ns/net"},
              {"type":"cgroup"}]
"cgroupsPath":"/kubepods/besteffort/podc1690888-…/3e614ffea368d…"
```

A namespace with a `path` is joined (pause's); one without is created new. `pause` itself does
nothing but sleep and reap zombies, so the namespaces outlive any app container's restarts.

## 2. The process tree (lab)

```text
init (k3s)
└── containerd-shim-runc-v2 -namespace k8s.io -id 65f37f5c9a961…     one shim, for the whole Pod
    ├── /pause                                                       (the sandbox, id 65f37f5c9a961)
    ├── sleep 3600                                                   container a
    └── sleep 3601                                                   container b
```

- **The shim's parent is PID 1, not containerd.** containerd can restart, or be upgraded, without
  killing containers; it reconnects to the shims through their sockets **(docs)**.
- **runc isn't in the tree.** It ran once per container to set up namespaces, cgroups and the root
  filesystem, `exec`ed the process, and exited. Its state is kept in a directory:

```text
$ runc --root /run/containerd/runc/k8s.io state 3e614ffea368d…
  "pid": 2777766,
  "status": "running",
  "bundle": "/run/k3s/containerd/io.containerd.runtime.v2.task/k8s.io/3e614ffea368d…",
```

- **The bundle** holds `config.json` (the OCI runtime spec) and `rootfs`, the container's root
  filesystem: an overlayfs mount of the image layers, from
  `/var/lib/rancher/k3s/agent/containerd/io.containerd.snapshotter.v1.overlayfs`.

## 3. What a crash restarts (lab)

```text
before:                     IP=10.244.189.3  restarts a=0 b=0
kill -9 the sleep of a:     IP=10.244.189.3  restarts a=1 b=0     same sandbox
kill -9 the pause process:  event SandboxChanged "Pod sandbox changed, it will be killed and re-created."
                            events Killing "Stopping container a", "… b"
about 40 s later:           IP=10.244.189.8  restarts a=2 b=1     new sandbox, ATTEMPT 1
$ crictl pods --name two
9f1500de7cdee   14 seconds ago   Ready      two   rt   1
65f37f5c9a961   6 minutes ago    NotReady   two   rt   0
```

- **A container restart is cheap:** the network namespace, and so the IP, the Service endpoints
  and open connections to other containers in the Pod, stay.
- **A sandbox restart is a new Pod network-wise:** the CNI plugin is called again (Calico gave a
  new address), every container restarts, and `emptyDir` volumes survive (they belong to the Pod,
  not the sandbox) **(docs)**.

## 4. Looking at it yourself

| Want | Command on the node |
|---|---|
| Pods and their sandboxes | `crictl pods`, `crictl inspectp <pod-id>` |
| Containers of a Pod | `crictl ps --pod <pod-id>` (add `-a` for exited ones) |
| A container's PID | `crictl inspect -o go-template --template '{{.info.pid}}' <id>` |
| Logs when the API server is down | `crictl logs <id>` |
| Enter a container's namespaces | `nsenter -t <pid> -n ip addr` (network only), `-a` for all |
| CPU and memory per container | `crictl stats` |
| Low-level state | `runc --root /run/containerd/runc/k8s.io list` |

On k3s the CRI socket is `/run/k3s/containerd/containerd.sock`
(`/var/lib/rancher/k3s/agent/etc/crictl.yaml`); on most other distributions it's
`/run/containerd/containerd.sock`.

## 5. Other runtimes (docs)

- **CRI-O** replaces containerd with a runtime built only for CRI; the kubelet side is the same.
- **Other OCI runtimes** replace runc under containerd, selected per Pod with a `RuntimeClass`:
  `crun` (C, faster start), gVisor `runsc` (a user-space kernel), Kata Containers (a lightweight
  VM per Pod).
- **dockershim was removed in Kubernetes 1.24.** Docker-built images still run: they're OCI
  images. `cri-dockerd` exists for clusters that still want Docker Engine
  ([watching-the-api-server.md](watching-the-api-server.md) shows minikube using it).

## Related notes

- [resources-qos-cgroups.md](resources-qos-cgroups.md): the cgroup files under `cgroupsPath`.
- [calico-layer3.md](calico-layer3.md): what the CNI plugin does with the sandbox's network namespace.
- [bridge.md](bridge.md): veth pairs and namespaces in plain Linux.
- [pod-shutdown-and-probes.md](pod-shutdown-and-probes.md): SIGTERM, delivered to the container's PID 1.
