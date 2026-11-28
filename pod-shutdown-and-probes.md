# Pod shutdown, probes and disruption budgets

What happens between `kubectl delete pod` and the process exiting, why requests can fail in that
window, and what `preStop`, readiness probes, `terminationGracePeriodSeconds` and
PodDisruptionBudgets change. Run on the k3s + Calico lab cluster (v1.34.1+k3s1, kube-proxy in
iptables mode), with millisecond timestamps from the API server's node, the Pod and the client.

Labels: **(lab)** seen in the runs below; **(docs)** from the Kubernetes documentation.

## 0. Short answers

- **Deleting a Pod starts two things in parallel.** The kubelet runs `preStop` and then sends
  SIGTERM; the EndpointSlice controller marks the endpoint not ready, and kube-proxy on every node
  removes it from the Service's rules. Neither waits for the other (§2).
- **So an app that exits on SIGTERM can drop requests.** In the lab the process exited 70–130 ms
  before kube-proxy removed its rule in two of three runs, and one new connection was refused.
  In a big cluster the rule update can take seconds.
- **The fix is to keep serving after SIGTERM, briefly.** Either a `preStop` hook that sleeps a few
  seconds (SIGTERM comes after it), or an app that keeps serving for a few seconds after SIGTERM.
  Both gave zero errors (§3).
- **`terminationGracePeriodSeconds` (default 30) covers `preStop` plus shutdown.** When it runs out,
  the kubelet sends SIGKILL (§4).
- **Readiness controls traffic, liveness controls restarts.** A failing readiness probe takes the
  Pod out of the Service without restarting it; a failing liveness probe restarts the container
  **(docs)**.
- **A PodDisruptionBudget only guards evictions.** The Eviction API refused with `429` when the
  budget allowed 0 disruptions; a plain `kubectl delete pod` went through (§5). `kubectl drain`
  uses evictions.

## 1. The lab

- **`web`:** a Deployment of 2 Pods running a small Python HTTP server that answers with its Pod
  name. An environment variable picks what it does on SIGTERM: `exit` at once, `drain` (keep
  serving 5 s, then exit) or `ignore`. Readiness probe: `GET /` every second.
- **`client`:** a Pod that opens a new connection to the Service every 10 ms and logs the Pod
  that answered, or the error. A new connection each time, so kube-proxy picks a Pod each time.
- **Recording:** one `web` Pod is deleted; for 12 s the run records the Pod's log, the
  EndpointSlice (watched as JSON, timestamped on arrival), the node's `iptables-save -t nat` every
  50 ms (when the DNAT rule to the Pod's IP disappears), and the client's log. All on one Docker
  VM, so the clocks agree.

## 2. The race: the app exits on SIGTERM (lab)

Three deletions, `MODE=exit`, no `preStop` (times from the `kubectl delete`):

```text
run 1    +228 ms  client   last answer from the Pod
         +242 ms  pod      SIGTERM received, exits at +251 ms
         +301 ms  endpoint ready=false, serving=true, terminating=true
         +313 ms  client   ERROR Connection refused          ← the Pod is gone, the rule isn't
         +382 ms  iptables DNAT rules to 10.244.189.25: 0

run 2    +113 ms  pod      SIGTERM received, exits
         +178 ms  endpoint ready=false, terminating=true
         +191 ms  iptables rule removed                       (78 ms of exposure; no request hit it)

run 3    +403 ms  iptables rule removed
         +430 ms  endpoint ready=false, terminating=true
         +461 ms  pod      SIGTERM received, exits            (the rule went first: no exposure)
```

- **The order changes from run to run.** Pod shutdown is driven by the kubelet on the Pod's node;
  the rule change by the EndpointSlice controller and then kube-proxy on every node. Both start
  from the same deletion, independently.
- **"Connection refused"** is a new connection DNAT-ed to the old Pod IP after the process had
  closed its socket. An open keep-alive connection would see a reset instead.
- **This cluster is small and idle**: about 100 ms of exposure. With hundreds of nodes and many
  endpoints, kube-proxy updates, cloud load balancers and Ingress controllers can lag by seconds.
- **`serving` stayed true** until the readiness probe failed (+2.8 s in the first run). `serving`
  is readiness without the terminating rule: proxies that support it can keep sending to a
  terminating Pod that still answers, when nothing else is left **(docs)**.

## 3. Two fixes (lab)

**B. `preStop` sleeps, the app still exits at once:**

```yaml
lifecycle:
  preStop:
    exec: {command: ["sleep", "5"]}
```

```text
         +136 ms  endpoint ready=false, terminating=true
         +161 ms  iptables rule removed
        +5214 ms  pod      SIGTERM received, exits
client errors: 0 (and 0 in two more runs)
```

The kubelet runs `preStop` to completion before SIGTERM. The rules were gone 5 s before the
process stopped. The sleep needs the `sleep` binary in the image. For images without one there's a
built-in `preStop: {sleep: {seconds: 5}}`, on by default since Kubernetes 1.30 **(docs)**; this
1.34 API server accepted it **(lab, server-side dry run)**.

**C. The app keeps serving for 5 s after SIGTERM (`MODE=drain`), no `preStop`:**

```text
         +173 ms  pod      SIGTERM received
         +285 ms  endpoint ready=false, terminating=true
         +453 ms  client   last answer from the Pod           ← still served after SIGTERM
         +474 ms  iptables rule removed
        +5178 ms  pod      drain time over, exits
client errors: 0
```

The same effect, in the app. Servers with a graceful shutdown (stop accepting, finish open
requests) still need the delay before they stop accepting, or they reproduce §2.

## 4. The grace period and SIGKILL (lab + docs)

`MODE=ignore` with `terminationGracePeriodSeconds: 5`:

```text
        +1087 ms  pod      SIGTERM received (ignored)
        +1500 ms  iptables rule removed, endpoint terminating
  about +6 s      the container is gone; the kubelet's next probe fails:
                  "dial tcp 10.244.189.40:8080: connect: invalid argument"
```

The process never logged an exit: the kubelet sent SIGKILL when the 5 s ran out, counted from
when it started stopping the container **(docs)**. The grace period includes `preStop`: a
`preStop` that sleeps 25 s leaves 5 s of the default 30 for the app.

A container's main process is PID 1 in its PID namespace. If it's a shell that doesn't forward
signals (`sh -c "python app.py"`), the app never sees SIGTERM and always gets SIGKILL at the end of
the grace period. Use `exec` in the shell, or run the app directly, as this lab did.

## 5. PodDisruptionBudget (lab)

```text
$ kubectl -n life create pdb web --selector=app=web --min-available=2
$ kubectl -n life get pdb web
NAME   MIN AVAILABLE   MAX UNAVAILABLE   ALLOWED DISRUPTIONS
web    2               N/A               0                    ← 2 Pods, 2 must stay

$ kubectl create --raw /api/v1/namespaces/life/pods/<pod>/eviction -f eviction.json
Error from server (TooManyRequests): Cannot evict pod as it would violate the pod's disruption budget.

$ kubectl -n life delete pod <pod>
pod "<pod>" deleted                 ← a delete isn't an eviction; the PDB doesn't apply

(scaled to 3)
web    2               N/A               1
$ kubectl create --raw …/eviction …
{"kind":"Status","apiVersion":"v1","metadata":{},"status":"Success","code":201}
```

- **Who uses evictions:** `kubectl drain`, the Cluster Autoscaler and Karpenter when they remove
  nodes, and node upgrades in managed services. They retry while the budget says no.
- **Who doesn't:** `kubectl delete`, a Deployment's rolling update (it has its own `maxUnavailable`),
  and the kubelet's own evictions under node pressure **(docs)**.
- **A budget that can never be met** (`minAvailable` equal to the replica count) blocks drains
  forever; the lab's first state was one.

## 6. Probes (docs)

| Probe | Failing means | Effect |
|---|---|---|
| `readinessProbe` | don't send me traffic | endpoint `ready: false`; the Pod keeps running |
| `livenessProbe` | I'm stuck | the kubelet restarts the container |
| `startupProbe` | still starting | liveness and readiness are held off until it passes |

- **Readiness is checked for the Pod's whole life,** not only at start. The lab's probe failure in
  §4 is the kubelet probing a container that was already killed.
- **A liveness probe that depends on a database** restarts every Pod when the database is down,
  which doesn't help. Keep liveness to "is this process alive".

## Related notes

- [kubernetes-services.md](kubernetes-services.md): the iptables rules kube-proxy writes, and
  EndpointSlices.
- [grpc-load-balancing.md](grpc-load-balancing.md): the same problem for long-lived gRPC
  connections, solved with GOAWAY.
- [deployments-and-replicasets.md](deployments-and-replicasets.md): rolling updates.
- [resources-qos-cgroups.md](resources-qos-cgroups.md): evictions under node pressure.
