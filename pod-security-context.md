# Pod securityContext: user, groups and hardening

This note covers which Linux user and groups a container really runs as, where those groups come from, and the settings that lock a Pod down. Lab results are from Alpine 3.22 Pods on minikube profile `k8s-137` (Kubernetes 1.37.2, containerd 2.3.4).

---

## 0. Terms

| Term | Meaning |
|---|---|
| **uid / gid** | The process's user ID and primary group ID. 0 is root. |
| **supplementary groups** | Extra groups the process belongs to. Linux checks all of them for file access, so each one adds permissions. |
| **`supplementalGroupsPolicy`** | A Pod-level setting: `Merge` (default) or `Strict`. It decides whether the groups listed in the image's `/etc/group` are added. |
| **capabilities** | Pieces of root's power (e.g. `NET_BIND_SERVICE`, `CHOWN`). `CapEff` in `/proc/self/status` shows the effective set. |
| **NoNewPrivs** | A kernel flag: the process and its children can't gain privileges, so setuid binaries like `su` don't work. Set by `allowPrivilegeEscalation: false`. |
| **seccomp** | A syscall filter. `Seccomp:` in `/proc/self/status` is 0 when off and 2 when a filter is active. |
| **PSA** | Pod Security Admission: the built-in admission check for the `privileged`, `baseline` and `restricted` levels, turned on per namespace with a label. |

---

## 1. `status.containerStatuses[].user`: what the container really runs as

After a container starts, the kubelet asks the container runtime for the identity of its first process and reports it in the Pod's **status**. You don't write this field; Kubernetes added it with the SupplementalGroupsPolicy feature (KEP-3619).

```yaml
status:
  containerStatuses:
  - name: c
    user:
      linux:
        uid: 0
        gid: 0
        supplementalGroups: [0, 1, 2, 3, 4, 6, 10, 11, 20, 26, 27]
```

That's from an Alpine Pod with no `securityContext` at all **(lab)**:

- **`uid: 0`, `gid: 0`**: the process runs as root, because nothing set `runAsUser` and the image has no non-root `USER`.
- **`supplementalGroups`**: almost all of these come from the **image's `/etc/group`**, not from the Pod spec.

### Where the groups come from

With the default policy, `Merge`, the runtime finds the user's name in the image's `/etc/passwd`. It then adds every group in the image's `/etc/group` that lists that name as a member. Any `supplementalGroups` and `fsGroup` from the Pod spec are added on top.

Alpine's `/etc/group` lists root as a member of most system groups **(lab)**:

```text
root:x:0:root
bin:x:1:root,bin,daemon
daemon:x:2:root,bin,daemon
sys:x:3:root,bin
adm:x:4:root,daemon
disk:x:6:root
wheel:x:10:root
floppy:x:11:root
dialout:x:20:root
tape:x:26:root
video:x:27:root
```

Those are exactly the 11 groups in the status above. `id` inside the container shows the same list:

```text
uid=0(root) gid=0(root) groups=0(root),1(bin),2(daemon),3(sys),4(adm),6(disk),10(wheel),11(floppy),20(dialout),26(tape),27(video)
```

### Why it matters

Each extra group gives access to files owned by that group. For example, a log file on a volume owned by `adm` with mode `640` is readable by the container, even though the manifest never granted `adm`. Before this field existed, the merged groups didn't show up anywhere. Now the status shows them, and `Strict` turns the merging off.

| Policy | Supplementary groups |
|---|---|
| `Merge` (default) | groups from the image's `/etc/group` for that user, plus `supplementalGroups` and `fsGroup` |
| `Strict` | only `runAsGroup`, `supplementalGroups` and `fsGroup` |

`Strict` needs support from the container runtime: containerd 2.0+ or CRI-O 1.31+. To check a node:

```sh
kubectl get node -o jsonpath='{.items[*].status.features}'
# {"supplementalGroupsPolicy":true}            (lab, containerd 2.3.4)
```

If the node doesn't support it, a Pod that asks for `Strict` is rejected rather than run with the merged groups **(docs)**.

---

## 2. Lab: five Pods compared

Each Pod runs `alpine:3.22` with `sleep 3600` and `automountServiceAccountToken: false`. Only the `securityContext` changes:

| Pod | Pod `securityContext` | Container `securityContext` |
|---|---|---|
| a | none | none |
| b | `supplementalGroupsPolicy: Strict` | none |
| c | `runAsUser: 2, runAsGroup: 2` (Alpine's `daemon` user) | none |
| d | as c, plus `supplementalGroupsPolicy: Strict` | none |
| e | hardened, see §3 | hardened, see §3 |

For each Pod I read the status, ran `id`, read `CapEff`/`NoNewPrivs`/`Seccomp` from `/proc/self/status`, tried `touch /x` and `cat /etc/shadow`, and ran `kubectl apply --dry-run=server` into a namespace labelled `pod-security.kubernetes.io/enforce=restricted` **(lab)**:

| Pod | `status.user` | `CapEff` | NoNewPrivs | Seccomp | Write to `/` | Read `/etc/shadow` | PSA `restricted` |
|---|---|---|---|---|---|---|---|
| a | uid 0, groups `[0,1,2,3,4,6,10,11,20,26,27]` | `a80425fb` (root's default set) | 0 | **0 (off)** | yes | **yes** | rejected |
| b | uid 0, groups `[0]` | `a80425fb` | 0 | 0 | yes | yes | rejected |
| c | uid 2, groups `[1,2,4]` | `0` | 0 | 0 | no | no | rejected |
| d | uid 2, groups `[2]` | `0` | 0 | 0 | no | no | rejected |
| e | uid 65532, groups `[65532]` | `0` | **1** | **2 (filter)** | **no (read-only filesystem)** | no | **accepted** |

What this shows:

- **`Strict` on its own isn't enough (b).** The process is still root, with root's capabilities, and can read `/etc/shadow`. `Strict` only removes the inherited groups.
- **A non-root user still inherits groups under `Merge` (c).** `daemon` got `bin` (1) and `adm` (4) because the image's `/etc/group` lists `daemon` in them. With `Strict` (d), it has only its own group.
- **Seccomp is off unless you set it.** Pods a to d all showed `Seccomp: 0`. The kubelet only applies `RuntimeDefault` by default when its `seccompDefault` option is on, which it isn't on this minikube.
- **Non-root already drops all capabilities (c, d).** A non-root process starts with an empty effective set, but `drop: [ALL]` still matters: it also empties the bounding set, so nothing can regain capabilities later.
- **PSA `restricted` rejected a to d** with the same four reasons each time:

  ```text
  allowPrivilegeEscalation != false (container "c" must set securityContext.allowPrivilegeEscalation=false),
  unrestricted capabilities (container "c" must set securityContext.capabilities.drop=["ALL"]),
  runAsNonRoot != true (pod or container "c" must set securityContext.runAsNonRoot=true),
  seccompProfile (pod or container "c" must set securityContext.seccompProfile.type to "RuntimeDefault" or "Localhost")
  ```

- **PSA `restricted` doesn't check `supplementalGroupsPolicy` or `readOnlyRootFilesystem`.** Pod e passes with them, but it would also pass without them. To require them across a cluster, use a ValidatingAdmissionPolicy or Kyverno **(not tested)**.

---

## 3. The hardened settings

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: app
spec:
  automountServiceAccountToken: false      # unless the app calls the Kubernetes API
  securityContext:                         # Pod level: applies to every container
    runAsNonRoot: true                     # the kubelet refuses to start a container running as uid 0
    runAsUser: 65532                       # a non-zero UID that isn't a member of any group in the image's /etc/group
    runAsGroup: 65532
    supplementalGroupsPolicy: Strict       # ignore the image's /etc/group
    fsGroup: 65532                         # only if volumes need group ownership
    seccompProfile:
      type: RuntimeDefault                 # the runtime's default syscall filter
  containers:
  - name: app
    image: alpine:3.22
    securityContext:                       # container level: these fields only exist here
      allowPrivilegeEscalation: false      # sets NoNewPrivs
      readOnlyRootFilesystem: true
      capabilities:
        drop: ["ALL"]                      # add back only what's needed, e.g. NET_BIND_SERVICE
    volumeMounts:
    - name: tmp
      mountPath: /tmp
  volumes:
  - name: tmp
    emptyDir: {}
```

| Setting | What it stops | Lab evidence |
|---|---|---|
| `runAsNonRoot` + `runAsUser` | the process running as root: reading `/etc/shadow`, writing system files, root capabilities | a reads `/etc/shadow`, e can't |
| `supplementalGroupsPolicy: Strict` | permissions from groups the image adds without you knowing | c `[1,2,4]` vs d `[2]` |
| `capabilities.drop: [ALL]` | regaining capabilities, e.g. via a setuid or file-capability binary | e `CapEff 0` |
| `allowPrivilegeEscalation: false` | `su`, `sudo` and other setuid binaries gaining root | e `NoNewPrivs 1` |
| `seccompProfile: RuntimeDefault` | rarely needed syscalls often used in kernel exploits (`unshare`, `keyctl`, `bpf`, ...) | e `Seccomp 2`, others 0 |
| `readOnlyRootFilesystem` | changing binaries or dropping tools into the image filesystem | e `touch /x: Read-only file system` |
| `automountServiceAccountToken: false` | a stolen token being used against the API server | set on all five Pods, not checked separately |

### The `/tmp` volume

With `readOnlyRootFilesystem`, `/tmp` is read-only too: `touch /tmp/x` failed in Pod e **(lab)**. Apps that write temporary files need a writable `emptyDir` mounted there, as in the YAML above. Add one for any other path the app writes to (cache directories, `/var/run`, etc.).

### Fields that are Pod-only, container-only, or both

| Field | Pod | Container |
|---|---|---|
| `runAsUser`, `runAsGroup`, `runAsNonRoot`, `seccompProfile` | yes | yes (container value wins) |
| `supplementalGroups`, `supplementalGroupsPolicy`, `fsGroup` | yes | no |
| `allowPrivilegeEscalation`, `readOnlyRootFilesystem`, `capabilities`, `privileged` | no | yes |

---

## 4. Outside the manifest

- **The image:** set a non-root `USER` in the Dockerfile, or use a distroless `nonroot` image (UID 65532). Then `runAsNonRoot: true` works without hard-coding a UID in every manifest. A minimal image also has fewer setuid binaries and a smaller `/etc/group`.
- **Pod Security Admission:** label namespaces `pod-security.kubernetes.io/enforce=restricted` so Pods like a to d are rejected at admission.
- **`hostUsers: false`** (user namespaces): even root in the container maps to an unprivileged UID on the node, so a container escape doesn't give root on the node **(not tested)**.
- **The kubelet's `seccompDefault: true`**: applies `RuntimeDefault` to every Pod that doesn't set a profile **(not tested)**.

---

## 5. Reproduce

```sh
kubectl create ns sg-lab
# create a Pod with a given securityContext, then:
kubectl -n sg-lab get pod <pod> -o jsonpath='{.status.containerStatuses[0].user.linux}'
kubectl -n sg-lab exec <pod> -- id
kubectl -n sg-lab exec <pod> -- grep -E '^(CapEff|NoNewPrivs|Seccomp):' /proc/self/status
kubectl -n sg-lab exec <pod> -- sh -c 'touch /x; cat /etc/shadow >/dev/null && echo readable'

# check a manifest against the restricted level without creating it
kubectl create ns sg-psa
kubectl label ns sg-psa pod-security.kubernetes.io/enforce=restricted
kubectl -n sg-psa apply --dry-run=server -f pod.yaml

kubectl delete ns sg-lab sg-psa
```

---

## Related notes

- [secrets-and-env.md](secrets-and-env.md): a Pod with `runAsUser`/`runAsNonRoot` reading mounted secrets, and why `/tmp` is a bad mount path for the CSI secrets volume
- [control-plane-manifests.md](control-plane-manifests.md): `seccompProfile: RuntimeDefault` on the static control-plane Pods
- [rbac-and-identity.md](rbac-and-identity.md): ServiceAccount tokens, the other half of `automountServiceAccountToken`
- [deployments-and-replicasets.md](deployments-and-replicasets.md): `securityContext` works the same in a Deployment's Pod template

## Sources

- Kubernetes docs, Configure a Security Context for a Pod or Container: https://kubernetes.io/docs/tasks/configure-pod-container/security-context/
- KEP-3619, Fine-grained SupplementalGroups control: https://github.com/kubernetes/enhancements/tree/master/keps/sig-node/3619-supplemental-groups-policy
- Kubernetes docs, Pod Security Standards: https://kubernetes.io/docs/concepts/security/pod-security-standards/
- Kubernetes docs, Pod Security Admission: https://kubernetes.io/docs/concepts/security/pod-security-admission/
- Kubernetes docs, Restrict a Container's Syscalls with seccomp: https://kubernetes.io/docs/tutorials/security/seccomp/
- Kubernetes docs, Use a User Namespace With a Pod: https://kubernetes.io/docs/tasks/configure-pod-container/user-namespaces/
- Linux man page, capabilities(7): https://man7.org/linux/man-pages/man7/capabilities.7.html
