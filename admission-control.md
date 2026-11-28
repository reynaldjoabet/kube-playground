# Admission control: webhooks, ValidatingAdmissionPolicy and Pod Security Admission

What happens to a write request between authorization and etcd, and three ways to add rules
there: CEL policies in the API server, Pod Security Admission, and webhooks. Run on the k3s +
Calico lab cluster (v1.34.1+k3s1).

Labels: **(lab)** seen in the runs below; **(docs)** from the Kubernetes documentation.

## 0. Short answers

- **Admission runs after authentication and authorization, before the object is stored.** First
  the mutating phase (built-in mutating plugins, then mutating webhooks), then schema validation,
  then the validating phase (built-in validators such as Pod Security, ValidatingAdmissionPolicy,
  validating webhooks). Any of them can reject; only the mutating ones can change the object
  **(docs)**.
- **ValidatingAdmissionPolicy (GA since 1.30) is a webhook without a webhook.** CEL expressions run
  inside the API server, a binding chooses where they apply and what a failure does (`Deny`,
  `Warn`, `Audit`). No server, no TLS, no network hop (§2).
- **A rule on `deployments` doesn't cover `deployments/scale`.** `kubectl scale --replicas=9` went
  through a policy that rejected `replicas: 10` from `kubectl patch` (§2.3).
- **Pod Security Admission checks Pods against three fixed levels** (`privileged`, `baseline`,
  `restricted`), configured with namespace labels. In `enforce` mode it rejects Pods, but not the
  Deployments that create them: the Deployment was accepted and its ReplicaSet couldn't create a
  single Pod (§3).
- **MutatingAdmissionPolicy isn't available here.** It's beta in 1.34 and its API isn't served
  unless enabled; for mutation, webhooks remain the tool (§4).

## 1. Where admission sits

```text
request ─► authentication ─► authorization (RBAC) ─► MUTATING admission
                                                       built-in plugins, then
                                                       MutatingWebhookConfiguration webhooks
                                                       (MutatingAdmissionPolicy when enabled)
                                                              │
                                                              ▼
                                                     schema validation of the object
                                                              │
                                                              ▼
                                                     VALIDATING admission
                                                       built-in validators (PodSecurity, …)
                                                       ValidatingAdmissionPolicy
                                                       ValidatingWebhookConfiguration webhooks
                                                              │
                                                              ▼
                                                           etcd
```

Reads (`get`, `list`, `watch`) don't go through admission. Neither do changes the API server
makes itself, such as garbage collection deletes **(docs)**.

## 2. ValidatingAdmissionPolicy (lab)

### 2.1 The policy and its binding

```yaml
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicy
metadata: {name: deploy-rules}
spec:
  failurePolicy: Fail
  matchConstraints:
    resourceRules:
    - {apiGroups: [apps], apiVersions: [v1], operations: [CREATE, UPDATE], resources: [deployments]}
  variables:
  - {name: labels, expression: "has(object.metadata.labels) ? object.metadata.labels : {}"}
  validations:
  - expression: "object.spec.replicas <= 5"
    messageExpression: "'replicas must be at most 5, got ' + string(object.spec.replicas)"
  - expression: "'team' in variables.labels"
    message: "Deployments need a team label"
    reason: Invalid
---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicyBinding
metadata: {name: deploy-rules}
spec:
  policyName: deploy-rules
  validationActions: [Deny]
  matchResources:
    namespaceSelector: {matchLabels: {policy: strict}}
```

The policy says what to check; the binding says where (here: namespaces labelled
`policy=strict`) and what to do on failure. One policy can have several bindings, and a binding
can pass parameters from a `paramKind` object (a ConfigMap or a CRD) **(docs)**.

### 2.2 Results

```text
no team label, in adm-strict
  error: failed to create deployment: … ValidatingAdmissionPolicy 'deploy-rules' with binding
  'deploy-rules' denied request: Deployments need a team label             (HTTP 422)
same, in adm-other (no policy=strict label)
  deployment.apps/a created
team label, replicas 7
  … denied request: replicas must be at most 5, got 7
team label, replicas 2
  deployment.apps/b created
```

- **`reason` sets the HTTP status.** The team-label rule has `reason: Invalid` and came back as
  `422 Unprocessable Entity`; without a reason the default is `Forbidden` (403) **(docs)**.
- **`messageExpression` builds the message from the object;** `message` is fixed text.
- **`variables` are computed once and shared** by the validations. `has()` guards against a
  missing field: `object.metadata.labels` is absent, not empty, on an object without labels.

### 2.3 The scale subresource gets past it

```text
$ kubectl -n adm-strict scale deploy b --replicas=9
deployment.apps/b scaled
spec.replicas=9
$ kubectl -n adm-strict patch deploy b -p '{"spec":{"replicas":10}}'
… denied request: replicas must be at most 5, got 10
```

`kubectl scale` writes to `deployments/scale`, a different resource with an `autoscaling/v1
Scale` object. The rule lists `deployments` only. To cover it, add `deployments/scale` to
`resources` and write expressions that work on the `Scale` object (`object.spec.replicas` exists
there too). The HorizontalPodAutoscaler also writes through `/scale`.

### 2.4 Warn and Audit

With `validationActions: [Warn, Audit]` on the binding:

```text
Warning: Validation failed for ValidatingAdmissionPolicy 'deploy-rules' with binding
'deploy-rules': Deployments need a team label
deployment.apps/c created
```

`Warn` sends the message back as an HTTP `Warning` header, which kubectl prints. `Audit` adds it
to the audit event's annotations. Rolling out a policy as `Warn` first shows who would break.

### 2.5 Mistakes are found at write time

A policy with `object.spec.replicaz`:

```text
status.typeChecking.expressionWarnings:
  fieldRef: spec.validations[0].expression
  warning:  apps/v1, Kind=Deployment: ERROR: <input>:1:12: undefined field 'replicaz'
```

The API server type-checks the expressions against the schemas of the matched types. The policy is
still stored: with `failurePolicy: Fail` a broken expression rejects every matching request, so
check `status` after creating one.

## 3. Pod Security Admission (lab)

```text
$ kubectl label ns psa pod-security.kubernetes.io/enforce=restricted \
                       pod-security.kubernetes.io/warn=restricted

$ kubectl -n psa run p --image=busybox:1.36 -- sleep infinity
Error from server (Forbidden): pods "p" is forbidden: violates PodSecurity "restricted:latest":
  allowPrivilegeEscalation != false (… must set securityContext.allowPrivilegeEscalation=false),
  unrestricted capabilities (… must set securityContext.capabilities.drop=["ALL"]),
  runAsNonRoot != true (… must set securityContext.runAsNonRoot=true),
  seccompProfile (… must set securityContext.seccompProfile.type to "RuntimeDefault" or "Localhost")
```

**A Deployment of the same Pod is accepted:**

```text
$ kubectl -n psa create deploy d --image=busybox:1.36 -- sleep infinity
Warning: would violate PodSecurity "restricted:latest": … (the same four)
deployment.apps/d created
$ kubectl -n psa get deploy d
d     0/1   0     0     5s
event FailedCreate: Error creating: pods "d-7948696958-9z7lb" is forbidden: violates PodSecurity …
```

- **`enforce` applies to Pods only.** The `warn` and `audit` modes also check the Pod templates in
  workloads, which is where the warning came from. Without `warn`, the Deployment is created
  silently and nothing runs.
- **The compliant version:** pod `securityContext: {runAsNonRoot: true, runAsUser: 1000,
  seccompProfile: {type: RuntimeDefault}}` and container `securityContext:
  {allowPrivilegeEscalation: false, capabilities: {drop: [ALL]}}` was admitted.
- **Raising the level on a namespace checks what's already there:**

```text
$ kubectl label --overwrite ns psa2 pod-security.kubernetes.io/enforce=restricted
Warning: existing pods in namespace "psa2" violate the new PodSecurity enforce level "restricted:latest"
Warning: p: allowPrivilegeEscalation != false, unrestricted capabilities, runAsNonRoot != true, seccompProfile
namespace/psa2 labeled                    (the running Pod is left alone)
```

| Level | What it allows **(docs)** |
|---|---|
| `privileged` | anything |
| `baseline` | no privileged containers, host namespaces, hostPath, host ports, or added dangerous capabilities |
| `restricted` | baseline, plus: non-root, no privilege escalation, drop ALL capabilities, a seccomp profile |

Each label can pin a version (`pod-security.kubernetes.io/enforce-version: v1.34`) so that a
cluster upgrade doesn't tighten `latest` under running workloads.

## 4. Webhooks and mutation (docs)

- **A webhook is an HTTPS service the API server calls** with an `AdmissionReview`. It answers
  allowed or denied and, for mutating webhooks, a JSON patch. Its configuration selects the
  resources, namespaces and objects, sets `failurePolicy` (`Fail` or `Ignore`) and a timeout.
- **Mutating webhooks run in order, and can be run again** (`reinvocationPolicy: IfNeeded`) when a
  later one changes the object. [workload-identity.md](workload-identity.md) reads the Azure
  Workload Identity webhook, which injects a token volume and env vars this way.
- **A webhook is in the request path.** If it's down and `failurePolicy: Fail`, every matching
  request fails, including ones that would recreate the webhook's own Pods. Exclude its namespace.
- **MutatingAdmissionPolicy** (CEL patches, like VAP for mutation) is beta in 1.34 and off by
  default; this cluster serves only `admissionregistration.k8s.io/v1`, without it **(lab)**.
- **Policy engines** (Kyverno, OPA Gatekeeper) run as webhooks; Kyverno can also generate VAPs.

## Related notes

- [rbac-and-identity.md](rbac-and-identity.md): authentication and authorization, the steps before admission.
- [pod-security-context.md](pod-security-context.md): the `securityContext` fields `restricted` asks for.
- [workload-identity.md](workload-identity.md): a mutating webhook read end to end.
- [watching-the-api-server.md](watching-the-api-server.md): who calls the API server and when.
