# Scheduling: filters, taints, spreading and preemption

How kube-scheduler picks a node, what each placement setting does, and how to read the
`FailedScheduling` message. Run on the k3s + Calico lab cluster (v1.34.1+k3s1): three nodes,
`cal-server`, `cal-agent1` and `cal-agent2`, 8 allocatable CPUs each.

Labels: **(lab)** seen in the runs below; **(docs)** from the Kubernetes documentation.

## 0. Short answers

- **Scheduling is filter, then score, then bind.** Filters drop nodes that can't run the Pod
  (resources, selectors, taints, affinity, spread). Scores rank the rest. The scheduler writes
  the chosen node into `spec.nodeName` with a Binding; the kubelet there starts the Pod **(docs)**.
- **When nothing passes, the message counts nodes per reason.** "0/3 nodes are available: 1 node(s)
  had untolerated taint {dedicated: gpu}, 2 node(s) didn't match Pod's node affinity/selector"
  reads as three nodes, each filtered out by the reason listed (§1).
- **Requests are what count, not usage.** A Pod asking for 9 CPUs fits nowhere, even on idle
  8-CPU nodes; the nodes had 250–450m requested already (§1).
- **Taints repel, tolerations allow, selectors attract.** A toleration only lets a Pod onto a
  tainted node; it needs a selector or affinity to go there (§2). `NoExecute` also evicts running
  Pods, after their `tolerationSeconds` (§3).
- **Topology spread and anti-affinity are filters too.** Required anti-affinity left the 4th
  replica Pending on 3 nodes; spread with `maxSkew: 1` put 5 replicas 2/2/1 (§4).
- **Priority lets a Pod evict others to fit.** A priority-1000 Pod asking for 4 CPUs preempted one
  priority-10 Pod; that Pod's replacement stayed Pending (§5).

## 1. Reading FailedScheduling (lab)

```text
nodeSelector: {disk: ssd}  (no node has that label)
0/3 nodes are available: 3 node(s) didn't match Pod's node affinity/selector.
  preemption: 0/3 nodes are available: 3 Preemption is not helpful for scheduling.

requests: {cpu: 9}
0/3 nodes are available: 3 Insufficient cpu.
  preemption: 0/3 nodes are available: 3 Preemption is not helpful for scheduling.
```

- **The first part is the filter results,** grouped by reason. Each node is counted once, under
  the first filter it failed.
- **The second part is the preemption attempt.** "Not helpful" means even removing every
  lower-priority Pod wouldn't make the node fit (no node can ever offer 9 CPUs).
- **What "Insufficient cpu" compares:** the node's allocatable (8) minus the sum of the requests
  of the Pods already on it, against the new Pod's request. Before the lab, the nodes had
  450m, 250m and 250m requested (`kubectl describe node`, "Allocated resources"). Usage doesn't
  enter into it.

## 2. Taints and tolerations (lab)

```text
$ kubectl taint node cal-agent2 dedicated=gpu:NoSchedule
$ kubectl -n sched create deploy spread --image=busybox:1.36 --replicas=6 -- sleep infinity
      3 cal-agent1
      3 cal-server              ← nothing on cal-agent2
```

A Pod with a matching toleration and `nodeSelector: {kubernetes.io/hostname: cal-agent2}` ran on
`cal-agent2`. The same Pod without the toleration:

```text
0/3 nodes are available: 1 node(s) had untolerated taint {dedicated: gpu},
  2 node(s) didn't match Pod's node affinity/selector.
```

| Effect | New Pods without a toleration | Running Pods without a toleration |
|---|---|---|
| `PreferNoSchedule` | avoided if possible (a score) | stay |
| `NoSchedule` | not scheduled (a filter) | stay |
| `NoExecute` | not scheduled | evicted, after `tolerationSeconds` if the toleration sets it |

For dedicated nodes, combine both: a taint keeps other Pods off, and a selector or node affinity
keeps the dedicated Pods on.

## 3. NoExecute evicts running Pods (lab)

Two Pods on `cal-agent1`: `ne` tolerates `maint` with `tolerationSeconds: 10`, `ne0` has no
toleration. Then `kubectl taint node cal-agent1 maint=now:NoExecute`:

```text
+0s   ne0 gone (deleted at once)          ne still running
+11s  ne  gone                            after its 10 s
events (reason TaintManagerEviction): "Marking for deletion Pod sched/ne0", "… sched/ne",
  and the three `spread` replicas on cal-agent1
```

The same mechanism handles failed nodes: the node lifecycle controller adds
`node.kubernetes.io/unreachable:NoExecute` or `not-ready:NoExecute`, and every Pod gets a default
toleration of 300 s for them, so Pods leave a dead node after about 5 minutes **(docs)**.

## 4. Spreading (lab)

**Topology spread,** 5 replicas, `maxSkew: 1` over `kubernetes.io/hostname`,
`whenUnsatisfiable: DoNotSchedule`:

```text
      2 cal-agent1
      2 cal-agent2
      1 cal-server
```

`maxSkew` is the largest allowed difference between the most and least loaded domain. With
`ScheduleAnyway` it becomes a score instead of a filter. `topologyKey: topology.kubernetes.io/zone`
spreads over zones the same way.

**Required pod anti-affinity,** 4 replicas, "no two on the same hostname":

```text
      1 cal-agent1   Running
      1 cal-agent2   Running
      1 cal-server   Running
      1 <none>       Pending
0/3 nodes are available: 3 node(s) didn't match pod anti-affinity rules.
  preemption: … 3 No preemption victims found for incoming pod.
```

Anti-affinity is all or nothing per node; spread allows controlled imbalance. The `preferred…`
form of anti-affinity is a score.

## 5. Priority and preemption (lab)

```yaml
PriorityClass low: value 10      filler: 6 replicas × 3 CPU, priority low, spread over the nodes
PriorityClass high: value 1000   urgent: 1 Pod, 4 CPU, priority high
```

After the fillers, each node had about 6.3 of 8 CPUs requested, so no node had 4 free:

```text
FailedScheduling  urgent   0/3 nodes are available: 3 Insufficient cpu.
Preempted         filler-…-cbwx7   Preempted by pod 2d9a04e1-… on node cal-server
Scheduled         urgent   Successfully assigned sched/urgent to cal-server

filler: 5 Running, 1 Pending — the ReplicaSet's replacement for the evicted one:
  0/3 nodes are available: 3 Insufficient cpu.
  preemption: … 1 No preemption victims found for incoming pod, 2 Insufficient cpu.
```

- **The scheduler picked the victim,** deleted it (graceful, here with grace 0), set
  `status.nominatedNodeName` on `urgent`, and scheduled it on the next attempt **(docs)**.
- **The replacement can't preempt back:** same priority as the other fillers, and `urgent` is
  higher.
- **PodDisruptionBudgets are respected on a best-effort basis** by preemption, unlike evictions,
  which they block **(docs)**.
- **`system-cluster-critical` and `system-node-critical`** are the built-in classes for cluster
  add-ons. Give important workloads a class too, or they're preemptable by any Pod that has one.

## 6. What else filters and scores (docs)

| Plugin | Filter or score | What it looks at |
|---|---|---|
| `NodeResourcesFit` | both | requests vs allocatable; the score prefers less allocated nodes by default |
| `NodeAffinity` | both | `nodeSelector`, required and preferred node affinity |
| `TaintToleration` | both | taints and tolerations |
| `InterPodAffinity` | both | pod affinity and anti-affinity |
| `PodTopologySpread` | both | `topologySpreadConstraints`, plus a default soft spread over hostname and zone |
| `VolumeBinding`, `VolumeZone` | filter | PVCs: bound volumes must be reachable from the node |
| `ImageLocality` | score | nodes that already have the image |
| `NodePorts`, `NodeUnschedulable` | filter | host ports in use, cordoned nodes |

`kubectl cordon` sets `spec.unschedulable`, which `NodeUnschedulable` filters on; `kubectl drain`
cordons and then evicts ([pod-shutdown-and-probes.md](pod-shutdown-and-probes.md) §5).

## Related notes

- [resources-qos-cgroups.md](resources-qos-cgroups.md): what requests and limits do on the node.
- [storage-and-csi.md](storage-and-csi.md): `WaitForFirstConsumer` and volume topology.
- [autoscaling.md](autoscaling.md): what happens when Pods stay Pending for lack of nodes.
- [statefulsets-and-daemonsets.md](statefulsets-and-daemonsets.md): DaemonSets and node selection.
