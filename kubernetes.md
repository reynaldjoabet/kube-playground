# Kubernetes Playground
## Node Components
- `kubelet` (kubelet): An agent that runs on each node in the cluster. It ensures that containers are running in a Pod according to the PodSpecs it receives from the API server.
- `kube-proxy` (kube-proxy): A network proxy that runs on each node, implementing part of the Kubernetes Service concept (managing iptables/IPVS rules).
- `Container Runtime` (containerd, CRI-O): The software that is responsible for running containers, accessed via the Container Runtime Interface (CRI).

## Pod and Workload Components
- `Pod`: The smallest deployable unit. A group of one or more containers that share storage and network resources.
- `ReplicaSet` / `Deployment`: Manages rolling updates and ensures a specific number of Pod replicas are running.
- `Service`: An abstract way to expose an application running on a set of Pods as a network service.
- `Ingress`: Manages external access to the services in a cluster, typically HTTP/HTTPS.
- `ConfigMaps` & `Secrets`: Ways to inject configuration and sensitive data into Pods.
- `Volumes` / `PersistentVolumeClaims (PVCs)`: How storage is abstracted and tied to Pods.

`kubectl run my-app --image=nginx`

```sh
+-----------------------------------------------------------------+
|                        User / kubectl                           |
+-----------------------------------------------------------------+
                                |
                                v (REST API Requests)
+-----------------------------------------------------------------+
|                   Control Plane  (The Brain)                    |
|                                                                 |
|   +------------------+       +------------------------------+   |
|   |       etcd       |<----->|                              |   |
|   +------------------+       |                              |   |
|                              |                              |   |
|   +------------------+       |        kube-apiserver        |   |
|   |  kube-scheduler  |<----->|                              |   |
|   +------------------+       |                              |   |
|                              |                              |   |
|   +------------------+       |                              |   |
|   | kube-controller- |<----->|                              |   |
|   |     manager      |       +------------------------------+   |
+-----------------------------------------------------------------+
                                ^
                                | (Instructions & Status)
                                v
+-----------------------------------------------------------------+
|                   Worker Nodes                  |
|                                                                 |
|  +-----------------------------+ +-----------------------------+|
|  | Node 1                      | | Node 2                      ||
|  |                             | |                             ||
|  |    +-------------------+    | |    +-------------------+    ||
|  |    |     kubelet       |    | |    |     kubelet       |    ||
|  |    +-------------------+    | |    +-------------------+    ||
|  |              |              | |              |              ||
|  |              v              | |              v              ||
|  |    +-------------------+    | |    +-------------------+    ||
|  |    | Container Runtime |    | |    | Container Runtime |    ||
|  |    +-------------------+    | |    +-------------------+    ||
|  |      |               |      | |      |               |      ||
|  |      v               v      | |      v               v      ||
|  |  +-------+       +-------+  | |  +-------+       +-------+  ||
|  |  |  Pod  |       |  Pod  |  | |  |  Pod  |       |  Pod  |  ||
|  |  +-------+       +-------+  | |  +-------+       +-------+  ||
|  |                             | |                             ||
|  |    +-------------------+    | |    +-------------------+    ||
|  |    |    kube-proxy     |    | |    |    kube-proxy     |    ||
|  |    +-------------------+    | |    +-------------------+    ||
|  +-----------------------------+ +-----------------------------+|
+-----------------------------------------------------------------+
```
- `Scheduling`: The `kube-scheduler` loop notices a new Pod in the API with no node assigned to it. It evaluates the available resources on Node 1 and Node 2, decides Node 1 is the best fit, and updates the `kube-apiserver` with that assignment.
- `Node Action`: The `kubelet` on Node 1 is constantly talking to the `kube-apiserver`. It sees that it has been assigned a new Pod.
- `Container Creation`: The `kubelet` tells its `Container Runtime` (containerd, CRI-O) to spin up the actual Nginx container.
- `Networking`: Concurrently, `kube-proxy` updates local network rules (like iptables) so traffic can route correctly to your new application. `kube-proxy `does absolutely nothing if you just create a Pod or a Deployment. It only cares about `Service` objects and their corresponding `Endpoints` (or `EndpointSlices`)
- `Reporting Status`: The `kubelet` reports back to the `kube-apiserver` that the container is running successfully, updating the Actual State in `etcd` to match your Desired State.

## Interfaces and Plugins
Kubernetes doesn't know how to run a container, how to route a network packet across nodes, or how to format an AWS EBS volume. It uses standard gRPC interfaces to talk to third-party plugins.

- `CRI (Container Runtime Interface)`: The kubelet uses CRI to talk to a runtime on the node (like containerd or CRI-O). When K8s says "run this pod", the CRI plugin actually pulls the image and sets up the Linux cgroups and namespaces.
  In the code: Look at `pkg/kubelet/cri/`.
- `CNI (Container Network Interface)`: When a pod starts, the kubelet calls a CNI plugin (like Calico, Cilium, or Flannel). The plugin gives the pod an IP address and wires up the virtual ethernet interfaces (veth pairs) so it can talk to the rest of the cluster.
- `CSI (Container Storage Interface)`: When a Pod needs a Persistent Volume, the CSI driver talks to the cloud provider (AWS, GCP, NetApp), creates the disk, mounts it to the Node, and then the kubelet bind-mounts it into the container

```sh
+-------------------------------------------------------------+
|                          Kubelet                            |
|                                                             |
|   +---------------+   +---------------+   +-------------+   |
|   |      CRI      |   |      CNI      |   |     CSI     |   |
|   | (gRPC Client) |   | (Exec/Binary) |   |(gRPC Client)|   |
|   +---------------+   +---------------+   +-------------+   |
+-----------|-------------------|-------------------|---------+
            |                   |                   |
            v                   v                   v
      +------------+      +-----------+       +-----------+
      | containerd |      |  Calico   |       | AWS EBS   |
      |   CRI-O    |      |  Cilium   |       | CSI Driver|
      +------------+      +-----------+       +-----------+
      (Runs config)       (IP & Routes)       (Disk Mount)
```      

```sh
BEFORE (Kubernetes < 1.24):
┌─────────────────────────────────────────────────────────┐
│  Node                                                   │
│                                                         │
│  ┌──────────┐    ┌─────────────┐    ┌────────────────┐  │
│  │          │    │             │    │                │  │
│  │  kubelet │───→│ dockershim  │───→│  Docker daemon │  │
│  │          │    │ (built into │    │                │  │
│  └──────────┘    │  kubelet)   │    └───────┬────────┘  │
│                  └─────────────┘            │           │
│                                            ▼            │
│                                    ┌──────────────┐     │
│                                    │  containerd  │     │
│                                    └──────┬───────┘     │
│                                           │             │
│                          ┌────────────────┼─────────┐   │
│                          ▼                ▼         ▼   │
│                     ┌─────────┐ ┌─────────┐ ┌─────────┐ │
│                     │Container│ │Container│ │Container│ │
│                     │   A     │ │   B     │ │   C     │ │
│                     └─────────┘ └─────────┘ └─────────┘ │
│                                                         │
│         kubelet → dockershim → Docker → containerd      │
│                   ^^^^^^^^^^^   ^^^^^^                  │
│                   two unnecessary layers                │
└─────────────────────────────────────────────────────────┘

AFTER (Kubernetes >= 1.24 — EKS, AKS, GKE):
┌─────────────────────────────────────────────────────────┐
│  Node                                                   │
│                                                         │
│  ┌──────────┐    CRI (gRPC)    ┌──────────────┐         │
│  │          │─────────────────→│  containerd  │         │
│  │  kubelet │                  │              │         │
│  │          │                  └──────┬───────┘         │
│  └──────────┘                        │                  │
│                          ┌───────────┼──────────┐       │
│                          ▼           ▼          ▼       │
│                     ┌─────────┐ ┌─────────┐ ┌─────────┐ │
│                     │Container│ │Container│ │Container│ │
│                     │   A     │ │   B     │ │   C     │ │
│                     └─────────┘ └─────────┘ └─────────┘ │
│                                                         │
│         kubelet → containerd (direct, via CRI)          │
│                   no middleman                          │
└─────────────────────────────────────────────────────────┘

ALTERNATIVE (some clusters use CRI-O instead):
┌─────────────────────────────────────────────────────────┐
│  Node                                                   │
│                                                         │
│  ┌──────────┐    CRI (gRPC)    ┌──────────────┐         │
│  │          │─────────────────→│    CRI-O     │         │
│  │  kubelet │                  │  (lighter,   │         │
│  │          │                  │  K8s-only)   │         │
│  └──────────┘                  └──────┬───────┘         │
│                          ┌───────────┼──────────┐       │
│                          ▼           ▼          ▼       │
│                     ┌─────────┐ ┌─────────┐ ┌─────────┐ │
│                     │Container│ │Container│ │Container│ │
│                     │   A     │ │   B     │ │   C     │ │
│                     └─────────┘ └─────────┘ └─────────┘ │
│                                                         │
│  Used by: OpenShift (Red Hat)                           │
└─────────────────────────────────────────────────────────┘
```

## More

This file is the architecture overview. The rest of the Kubernetes notes are split into:

- [`kube-networking.md`](kube-networking.md) — Services, ClusterIP, kube-proxy, iptables/NAT/conntrack deep dive, LoadBalancer
- [`rbac-and-identity.md`](rbac-and-identity.md) — ServiceAccounts, RBAC, OIDC, IRSA, Workload Identity, TLS, secrets, kubelogin
- [`cloud-kubernetes.md`](cloud-kubernetes.md) — CNI internals (Azure CNI, AWS VPC CNI), workloads, AKS/EKS specifics, Ingress/Gateway API, kubeadm, kubeconfig
