# kube-playground
Scala client for the Kubernetes API

Typed Kubernetes Client for creating, reading, updating, removing, listing and watching resources on a Kubernetes cluster
`minikube start`

```sh
kubectl proxy --port=8080
Starting to serve on 127.0.0.1:8080
```

`curl -o k8s-openapi-v3-minikube-proxy.json http://localhost:8080/openapi/v3`

## Authentication

Regardless of mechanism, a successful authentication produces this 4-tuple, which RBAC then evaluates:
```json
{
  username:  "jane@company.com"   or   "system:serviceaccount:prod:my-app"
  uid:       optional unique ID
  groups:    ["dev-team", "engineering"]   or   ["system:serviceaccounts:prod"]
  extra:     map<string, []string>   (additional attributes)
}
```
Every authentication method is just a different way of arriving at this tuple.

The API server can be configured with multiple authenticators. They run in a chain — the first one that returns a successful result wins. If all return "I don't recognize this," the request is rejected.

| Mechanism | Used for | Common in production? |
|-----------|----------|-----------------------|
| X.509 client certificates | Initial bootstrap, kubelet identity | yes (for control-plane components) |
| ServiceAccount tokens | Pods talking to the API server | always — every cluster |
| Static token file | Emergency break-glass | rarely (insecure) |
| Static password file | (none) | removed in 1.19 |
| Bootstrap tokens | Node joining (kubeadm join) | yes, but only at node bootstrap |
| OIDC | Human SSO via Okta/Auth0 | yes — common |
| Webhook token | Delegate auth to any external service | yes — EKS/GKE/AKS all use this |
| Authenticating proxy | Frontend SSO proxy sets X-Remote-User headers | yes — Rancher, Teleport, Pomerium |
| Anonymous | Unauthenticated access | rare — usually disabled |


###  X.509 client certificates
Probably the most fundamental. The API server is configured with a CA certificate. Anything signed by that CA is accepted.
```sh
Client cert subject:  CN=jane, O=engineering, O=dev-team
                       ↓
Username:  jane
Groups:    ["engineering", "dev-team"]
The CN becomes the username; each O (Organization) becomes a group. 
```

Pros: built-in, no external dependencies, simple
Cons:
- No revocation — K8s does not support CRLs or OCSP. To revoke, you must rotate the cluster CA (huge blast radius) or rely on short-lived certs.
- Operationally painful — distributing certs to every developer is a nightmare
- Not auditable — certs don't carry tenant/email/SSO context
Used mostly for system identities: kubelet, controller-manager, scheduler, etcd. Rarely for humans in production.

### Mechanism 2: ServiceAccount tokens
Every pod gets a ServiceAccount; every SA can have tokens. The token is a JWT signed by the cluster's signing key.

```json
{
  "iss": "https://kubernetes.default.svc.cluster.local",
  "sub": "system:serviceaccount:prod:my-app-sa",
  "aud": ["https://kubernetes.default.svc.cluster.local"],
  "exp": 1807865206,
  "kubernetes.io": {
    "namespace": "prod",
    "pod": { "name": "my-app-7b8c", "uid": "..." },
    "serviceaccount": { "name": "my-app-sa", "uid": "..." }
  }
}
```
Two important things to know:

1. Modern SA tokens are projected and bound
Since K8s 1.21, the default is bound projected tokens — they're tied to a specific pod (the `kubernetes.io.pod` claim) and expire on a schedule (~1 hour, kubelet auto-rotates). If you delete the pod, the token becomes invalid even before expiry.

Old-style "forever" SA tokens stored in Secret objects are deprecated — they're issued by `kubectl create token` only on demand now.

2. The same JWT can be used externally (IRSA)
Because the SA token is a standards-compliant OIDC ID token, AWS STS can validate it. That's the entire foundation of IAM Roles for Service Accounts (IRSA), GKE Workload Identity, etc.
```sh
Pod's SA token  →  AWS STS  →  fetches K8s JWKS  →  verifies  →  returns AWS creds
```

### Bootstrap tokens
Special-purpose: only used for `kubeadm join`. A short-lived token that lets a new node authenticate just long enough to receive a permanent kubelet certificate.

`Format: [a-z0-9]{6}.[a-z0-9]{16} (e.g., abcdef.0123456789abcdef).`