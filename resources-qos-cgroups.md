# Resources, QoS classes and cgroups

What `resources.requests` and `resources.limits` turn into on the node: QoS classes, cgroup v2
files, CPU throttling, OOM kills and the kernel's OOM scores. Run on the k3s + Calico lab cluster
(v1.34.1+k3s1, containerd 2.1.4, cgroup v2 with the `cgroupfs` driver), on node `cal-agent1` with
8 CPUs and 8 GiB of memory.

Labels: **(lab)** seen on the node; **(docs)** from the Kubernetes documentation.

## 0. Short answers

- **Requests are for the scheduler and for sharing; limits are hard caps.** The scheduler places a
  Pod where the requests fit. On the node, a CPU request becomes a `cpu.weight` (a share when CPUs
  are contended), a CPU limit becomes `cpu.max` (a quota per 100 ms), and a memory limit becomes
  `memory.max`. A memory request isn't enforced by the kernel at all here.
- **The QoS class comes from the shape of the resources** (§1). Guaranteed: every container has
  CPU and memory limits equal to its requests. BestEffort: nothing set. Burstable: anything else.
- **The class decides where the Pod's cgroup sits and its OOM score.** Guaranteed Pods sit directly
  under `kubepods/` with `oom_score_adj` −997; BestEffort under `kubepods/besteffort/` with 1000;
  Burstable under `kubepods/burstable/`, scored by memory request (992 for 64 Mi on 8 GiB).
- **A CPU limit throttles, even on an idle node.** A busy loop limited to 200m got exactly 20 ms
  of every 100 ms period; all 100 periods in 10 s were throttled (§3).
- **Going over a memory limit kills the process.** The kernel's OOM killer, scoped to the
  container's cgroup, killed it: status `OOMKilled`, exit code 137 (§4).
- **Node pressure is different from limits.** When the node runs short, the kubelet evicts Pods,
  BestEffort first, then Burstable Pods over their requests **(docs)**.

## 1. Three Pods, three classes (lab)

```yaml
besteffort:  (no resources)
burstable:   requests: {cpu: 100m, memory: 64Mi}   limits: {memory: 128Mi}
guaranteed:  requests: {cpu: 500m, memory: 128Mi}  limits: {cpu: 500m, memory: 128Mi}
```

```text
$ kubectl -n qos get pods -o custom-columns=NAME:.metadata.name,QOS:.status.qosClass
NAME         QOS
besteffort   BestEffort
burstable    Burstable
guaranteed   Guaranteed
```

The rule **(docs)**:

| Class | Condition |
|---|---|
| Guaranteed | every container has CPU and memory limits, and requests equal to them (a request left out defaults to the limit) |
| BestEffort | no container has any CPU or memory request or limit |
| Burstable | everything else, including "memory set, CPU not" |

The `hog` Pod of §4 has memory request = limit but no CPU: it's Burstable.

## 2. The cgroup tree (lab)

```text
/sys/fs/cgroup/kubepods/                         cpu.weight=313  memory.max=8319770624 (the node)
├── besteffort/                                  cpu.weight=1
│   └── pod76bcbb57-…/                           cpu.weight=1    cpu.max=max     memory.max=max
├── burstable/                                   cpu.weight=22   (the sum of its Pods' requests)
│   └── podafa4bab0-…/                           cpu.weight=4    cpu.max=max     memory.max=128 Mi
└── pode514a6f8-…/ (Guaranteed)                  cpu.weight=20   cpu.max=50000 …  memory.max=128 Mi
```

Each Pod directory holds one directory per container, plus one for the `pause` container that
holds the Pod's namespaces. The values on the container of each Pod:

| Pod | `cpu.weight` | `cpu.max` | `memory.max` | `oom_score_adj` of the process |
|---|---|---|---|---|
| besteffort | 1 | `max 100000` | `max` | 1000 |
| burstable | 4 | `max 100000` | 134217728 | 992 |
| guaranteed | 20 | `50000 100000` | 134217728 | −997 |
| (each Pod's `pause`) | 1 | `max 100000` | `max` | −998 |

Where the numbers come from:

- **`cpu.weight` from the CPU request.** The kubelet converts millicores to cgroup v1 shares
  (`m × 1024 / 1000`, minimum 2), and the runtime maps shares to cgroup v2 weight with
  `1 + (shares − 2) × 9999 / 262142`. 100m → 102 shares → weight 4; 500m → 512 → 20; the node's
  8 CPUs → 8192 → 313. All three match the files.
- **`cpu.max` from the CPU limit.** `quota period`: 500m is 50 ms of CPU per 100 ms period. No
  limit is `max`.
- **`memory.max` from the memory limit,** in bytes. `kubepods/` itself is capped at the node's
  memory (8 124 776 KiB), since nothing is reserved for the system on this node.
- **`memory.min` and `memory.low` are 0.** A memory request would only become `memory.min` with
  the MemoryQoS feature gate, which is off **(docs)**. So the request matters to the scheduler and
  the OOM score, not to the kernel's reclaim.
- **`oom_score_adj` from the class.** Guaranteed −997 and BestEffort 1000 are fixed. Burstable is
  `1000 − 1000 × memory request / node memory`: `1000 − 1000 × 64 Mi / 8 124 776 Ki` = 992. The
  higher the number, the earlier the kernel picks the process when the whole node runs out.

## 3. A CPU limit throttles (lab)

A busy loop (`while :; do :; done`) with `limits.cpu: 200m`, on a node with 8 idle CPUs. Its
`cpu.stat` read twice, 10 s apart:

```text
cpu.max                  20000 100000
usage_usec               +2 003 725      2.0 s of CPU in 10 s: exactly 200m
nr_periods               +100            one per 100 ms
nr_throttled             +100            every period hit the quota
throttled_usec           +5 422 260
```

```text
$ kubectl top pod -n qos spin
spin   201m         0Mi
```

The CFS bandwidth controller gives the cgroup 20 ms of CPU per 100 ms period. The loop used it in
the first 20 ms and waited out the rest, every period, while seven CPUs stood idle. For a
latency-sensitive service the same thing shows up as requests that take up to 80 ms longer when
they arrive after the quota is spent. That's why many teams set CPU requests but no CPU limits:
the request still guarantees a share when the node is busy.

## 4. A memory limit kills (lab)

```yaml
command: [python3, -c, "import time; b = bytearray(200 * 1024 * 1024); print('allocated'); time.sleep(60)"]
resources: {requests: {memory: 64Mi}, limits: {memory: 64Mi}}
```

```text
$ kubectl -n qos get pod hog -o jsonpath=…
Failed OOMKilled exit=137
$ kubectl -n qos logs hog
(nothing: "allocated" was never printed)
```

- **The kernel killed it, not Kubernetes.** The allocation pushed the container's cgroup past
  `memory.max`; reclaim couldn't free enough, so the kernel's OOM killer killed a process in that
  cgroup. 137 is 128 + 9 (SIGKILL).
- **The kubelet only reports it.** It reads the reason from the runtime and sets `OOMKilled`. With
  `restartPolicy: Always` the container would restart with back-off, which is the usual
  `CrashLoopBackOff` after `OOMKilled`.
- **Memory can't be throttled the way CPU is.** Past the limit, the only options are reclaiming
  page cache or killing.

## 5. When the node runs short: eviction (docs)

Limits protect Pods from each other. Node pressure is handled by the kubelet:

- **Eviction thresholds.** By default the kubelet evicts when `memory.available` < 100 Mi or disk
  space runs low (`nodefs.available` < 10%, and others). It marks the node with a condition such as
  `MemoryPressure`, and the scheduler stops placing BestEffort Pods there.
- **Order.** Pods whose usage exceeds their requests go first, ranked by priority and then by how
  far over they are; BestEffort Pods have no requests, so any usage counts. Guaranteed Pods, and
  Burstable Pods under their requests, are evicted last.
- **If the kubelet is too slow,** the kernel's OOM killer acts on the whole node, and the
  `oom_score_adj` values of §2 decide who dies.

## 6. Practical settings

| Goal | Setting |
|---|---|
| Predictable placement, no surprises | requests on every container, close to real usage |
| No CPU throttling | CPU request, no CPU limit |
| Protect the node from a leak | a memory limit (memory can't be shared back) |
| Highest protection under pressure | Guaranteed: limits = requests for CPU and memory |
| Default for a namespace | a `LimitRange` (default requests and limits) and a `ResourceQuota` (totals) |

## Related notes

- [scheduling.md](scheduling.md): how requests decide where a Pod can go, and preemption.
- [container-runtime.md](container-runtime.md): the `pause` container and the cgroups the runtime creates.
- [autoscaling.md](autoscaling.md): HPA, which works in percent of the CPU request.
- [pod-security-context.md](pod-security-context.md): the other per-container settings.
