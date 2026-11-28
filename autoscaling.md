# Autoscaling: HPA, VPA, Cluster Autoscaler and Karpenter

What scales what, and on which signal. The HorizontalPodAutoscaler was run on the k3s + Calico lab
cluster (v1.34.1+k3s1) with its bundled metrics-server v0.8.0; the node and vertical autoscalers
need a cloud or a node provisioner, so they come from the documentation.

Labels: **(lab)** seen in the run below; **(docs)** from the Kubernetes or project documentation.

## 0. Short answers

- **Three different things get scaled.** HPA changes the number of Pods; VPA changes each Pod's
  requests; Cluster Autoscaler and Karpenter change the number of nodes. Node autoscalers react to
  Pending Pods, not to CPU usage **(docs)**.
- **HPA's CPU target is a percentage of the request.** `averageUtilization: 50` with
  `requests.cpu: 100m` means 50m per Pod on average. No request, no CPU-based HPA (§1).
- **The rule is `desired = ceil(current × current metric / target)`.** With 6 Pods at 21% against
  50%, that's 3; at 4%, it's 1. Changes within 10% of the target are ignored **(docs)**; the lab's
  numbers follow the rule (§2).
- **Scale-up is fast, scale-down is held back.** Up happened within 30 s of the load starting.
  Down waited for the stabilization window (60 s in the lab; 300 s by default), taking the highest
  recommendation within it, so it stepped 6 → 3 → 1 (§2).
- **Metrics lag.** CPU stayed at 120–130% for about 45 s after the load stopped: metrics-server
  scrapes every 15 s and reports a rate over a window, and the HPA reads it every 15 s.
- **New Pods have no metrics yet.** The first event was `FailedGetResourceMetric … did not receive
  metrics for targeted pods (pods might be unready)`; the HPA leaves such Pods out of the average.

## 1. The lab

```yaml
Deployment burn: 1 replica, a Python HTTP server that burns CPU on each request,
                 requests: {cpu: 100m, memory: 64Mi}, no limit
Service burn:    port 80 → 8080
Deployment load: 0 replicas of a busybox loop: wget http://burn.hpa, sleep 0.1
HPA:
  scaleTargetRef: Deployment burn
  minReplicas: 1, maxReplicas: 6
  metrics: [{type: Resource, resource: {name: cpu, target: {type: Utilization, averageUtilization: 50}}}]
  behavior: {scaleDown: {stabilizationWindowSeconds: 60}}
```

Idle: `cpu: 1%/50%` (1m of 100m). Then `load` scaled to 3 for about 3 minutes, then to 0, with
`kubectl get hpa` every 15 s.

## 2. The run (lab)

```text
  +0s  load on   cpu: 1%/50%     replicas=1
 +16s            cpu: 2%/50%     replicas=1
 +32s            cpu: 498%/50%   replicas=3     SuccessfulRescale New size: 3
 +47s            cpu: 124%/50%   replicas=6     SuccessfulRescale New size: 6 (the maximum)
 +63s            cpu: 80%/50%    replicas=6
 …               69–81%                          capped at maxReplicas: 6 Pods can't reach 50%
+187s  load off  cpu: 123%/50%   replicas=6
+219s            cpu: 128%/50%                   metrics still describe the last window
+249s            cpu: 21%/50%                    recommendation ceil(6 × 21/50) = 3
+264s            cpu: 4%/50%                     recommendation ceil(6 × 4/50) = 1
+310s            cpu: 1%/50%     replicas=3     SuccessfulRescale New size: 3 "All metrics below target"
+325s            cpu: 1%/50%     replicas=1     SuccessfulRescale New size: 1
```

- **498% with one Pod** asks for `ceil(1 × 498/50)` = 10 Pods. The HPA chose 3 at its first
  evaluation; the metric it read then was probably lower (the load had just started), and scale-up
  is also limited per 15 s by the default policy (100% or 4 Pods, whichever is more) **(docs)**.
- **At 6 Pods the target couldn't be met.** 70–80% per Pod was the load spread over the maximum.
  `maxReplicas` is a cost guard; set it from capacity, not from hope.
- **The step down to 3 came from the window.** Scale-down uses the highest recommendation of the
  last `stabilizationWindowSeconds`. At +310 s the window still held the "3" from +249 s; 15 s later
  only "1"s were left. With the default 300 s window, the Pods would have stayed for five minutes
  after the load stopped, which is the point: load that dips and returns doesn't cause churn.

Conditions after the run:

```text
AbleToScale     True    ReadyForNewScale    recommended size matches current size
ScalingActive   True    ValidMetricFound    the HPA was able to successfully calculate a replica
                                            count from cpu resource utilization (percentage of request)
ScalingLimited  False   DesiredWithinRange  the desired count is within the acceptable range
```

`ScalingLimited: True` is the condition to alert on: it means the HPA wanted more (or fewer) Pods
than the limits allowed.

## 3. The HPA in practice (docs)

- **It writes `replicas` through the `/scale` subresource** of the Deployment. Don't also set
  `replicas` in the manifest you apply with GitOps, or each sync fights the HPA (leave it out).
- **Other metrics:** `type: Pods` (a per-Pod custom metric, such as requests per second) and
  `type: External` (a queue length) need a metrics adapter (Prometheus Adapter, KEDA). KEDA can also
  scale to zero, which the HPA can't.
- **Several metrics:** the HPA computes a count for each and takes the largest.
- **Readiness matters:** Pods that aren't ready, or just started, are left out of the CPU average,
  so a slow-starting app doesn't trigger a second scale-up because its new Pods look idle.

## 4. VPA (docs)

The Vertical Pod Autoscaler (a separate project) watches usage over days and recommends requests.
In `Off` mode it only writes recommendations to its status; in `Recreate` mode it evicts Pods so
they restart with new requests; `InPlaceOrRecreate` uses in-place resizing of running Pods
(the Pod `resize` subresource, beta and on by default since Kubernetes 1.33) when it can. On
this 1.34 cluster, `kubectl patch pod rz --subresource resize` changed a running container from
100m/200m to 150m/300m CPU with `restartCount` still 0 **(lab)**.

Don't let HPA and VPA both act on CPU for the same workload: VPA raising requests lowers the
utilization percentage HPA sees, and the two chase each other. Use VPA for recommendations, or HPA
on a custom metric.

## 5. Adding nodes: Cluster Autoscaler and Karpenter (docs)

```text
HPA adds Pods ─► some don't fit ─► Pending, "Insufficient cpu"
                                     │
            ┌────────────────────────┴────────────────────────┐
            ▼                                                 ▼
  Cluster Autoscaler                                  Karpenter
  simulates the scheduler against each               reads the Pending Pods' requests,
  node group (ASG, VMSS, MIG) and grows               selectors and tolerations, and launches
  the one that would fit them                         an instance type chosen to fit them
            │                                                 │
            └──────────────► new node registers ◄─────────────┘
                             the scheduler places the Pending Pods
```

- **Both start from Pending Pods**, so requests drive node scaling too. A cluster full of Pods
  without requests never looks full.
- **Scale-down** removes nodes whose Pods fit elsewhere, using evictions, so PodDisruptionBudgets
  apply ([pod-shutdown-and-probes.md](pod-shutdown-and-probes.md) §5). Karpenter also consolidates:
  it replaces several underused nodes with fewer or cheaper ones.
- **Speed:** a new node takes from tens of seconds to minutes, much longer than a new Pod. Spare
  capacity (low-priority placeholder Pods that get preempted, [scheduling.md](scheduling.md) §5)
  hides that delay.

## Related notes

- [resources-qos-cgroups.md](resources-qos-cgroups.md): requests, the base of HPA percentages.
- [scheduling.md](scheduling.md): Pending Pods and preemption.
- [admission-control.md](admission-control.md) §2.3: the `/scale` subresource the HPA writes to.
