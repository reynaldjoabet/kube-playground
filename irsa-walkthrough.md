# IRSA end to end: the EKS walkthrough, run step by step (lab)

This note runs the "EKS Walkthrough" from the `amazon-eks-pod-identity-webhook` README (the IRSA
webhook) one step at a time, and shows every request and response. Each step is labelled:

- **(lab)**: run here, output copied as printed;
- **(code)**: read in the source of the version named;
- **(docs)**: from AWS documentation, not tested;
- **(not tested)**: an inference.

How IRSA works in general, and the other token types, are in [workload-identity.md](workload-identity.md).
This file is the full sequence with the wire traffic.

| Piece | What ran | Version |
|---|---|---|
| Kubernetes | k3s + Calico lab cluster ([calico-layer3.md](calico-layer3.md)): `cal-server`, `cal-agent1`, `cal-agent2` | v1.34.1+k3s1 |
| IRSA webhook | `public.ecr.aws/eks/amazon-eks-pod-identity-webhook`, repo's `deploy/` manifests | v0.6.19 |
| AWS APIs (EKS, IAM, STS, S3, Cognito Identity, EC2) | the local AWS Emulator, `https://127.0.0.1:9297` | 1.0.385 (2026.11.14) |
| Client on the host | boto3 with a logging hook that prints each HTTP request and response | boto3 1.42.97 |
| Client in the Pod | `amazon/aws-cli` with `--debug` | 2.31.0 |

**What could and couldn't be run.** Everything on the Kubernetes side ran for real. Everything on
the AWS side ran against the emulator, with one exception. **The emulator never fetches an
issuer's keys over the network**, so it can't verify a token signed by the k3s cluster (§6). The
part of STS after the signature check (trust policy, credentials, using them) is shown instead with
a token from an issuer the emulator hosts itself, Amazon Cognito Identity (§7).

---

## 0. The sequence

```text
 ┌────────────────────┐ ┌────────────────┐    ┌─────────┐  ┌───────────────┐ ┌────────────────────┐
 │ you (aws, kubectl) │ │ kube-apiserver │    │ kubelet │  │ Pod (AWS SDK) │ │ AWS (IAM, STS, S3) │
 └─────────┬──────────┘ └───────┬────────┘    └───┬─────┘  └──────┬────────┘ └─────────┬──────────┘
           │                    │                 │               │                    │
           │ 1 OIDC provider    │                 │               │                    │
           │────────────────────┼─────────────────┼───────────────┼───────────────────►│
           │                    │                 │               │                    │
           │ 2 IAM role         │                 │               │                    │
           │────────────────────┼─────────────────┼───────────────┼───────────────────►│
           │                    │                 │               │                    │
           │ 3 SA, Pod          │                 │               │                    │
           │───────────────────►│                 │               │                    │
           │                    │                 │               │                    │
           │                    │ 4 webhook       │               │                    │
           │                    │   edits Pod     │               │                    │
           │                    │                 │               │                    │
           │                    │ 5 scheduled     │               │                    │
           │                    │────────────────►│               │                    │
           │                    │                 │               │                    │
           │                    │ 6 TokenRequest  │               │                    │
           │                    │◄────────────────│               │                    │
           │                    │                 │               │                    │
           │                    │                 │ 7 token file  │                    │
           │                    │                 │──────────────►│                    │
           │                    │                 │               │                    │
           │                    │                 │               │ 8 AssumeRole...    │
           │                    │                 │               │───────────────────►│
           │                    │                 │               │                    │
           │                    │                 │               │                    │ 9 verifies
           │                    │                 │               │                    │
           │                    │                 │               │ 10 credentials     │
           │                    │                 │               │◄───────────────────│
           │                    │                 │               │                    │
           │                    │                 │               │ 11 S3 call         │
           │                    │                 │               │───────────────────►│
           │                    │                 │               │                    │
```

| # | What happens | Who does it | Section |
|---|---|---|---|
| 1 | Register the cluster's OIDC issuer URL in IAM, with `sts.amazonaws.com` as the allowed client ID (audience) | you, once per cluster | §2 |
| 2 | Create the IAM role: a **trust policy** (who may assume it) and a **permissions policy** (what it may do) | you, once per role | §3 |
| 3 | Annotate the ServiceAccount with the role ARN; create the Pod | you | §4 |
| 4 | The mutating webhook adds env vars, a projected token volume and a mount | `pod-identity-webhook`, called by kube-apiserver | §4 |
| 5, 6 | The kubelet on the Pod's node asks kube-apiserver for a token with `aud: sts.amazonaws.com` | kubelet → TokenRequest API | §5 |
| 7 | The kubelet writes the token into the Pod's tmpfs and refreshes it | kubelet | §5 |
| 8 | The SDK sends the token to STS `AssumeRoleWithWebIdentity`, unsigned | AWS SDK in the Pod | §6 |
| 9 | STS checks the token (keys from the issuer's JWKS, `aud`, `sub`, `exp`) and the trust policy | STS | §6, §7, §8, §9 |
| 10 | STS returns temporary credentials (access key, secret key, session token, expiry) | STS | §7 |
| 11 | The SDK signs normal API calls with them; the permissions policy decides what's allowed | AWS SDK → S3 | §7 |

---

## 1. Lab setup

### 1.1 The webhook on the cluster

As in [workload-identity.md](workload-identity.md) §3: the repo's `deploy/` manifests, a self-signed
certificate from `openssl` in place of cert-manager, and the image tag filled in. One flag added to
match the README's example, which shows `AWS_REGION=us-west-2`:

```console
$ grep -A1 -- --logtostderr all-usw2.yaml
        - --logtostderr
        - --aws-default-region=us-west-2

$ kubectl apply -f all-usw2.yaml
...
mutatingwebhookconfiguration.admissionregistration.k8s.io/pod-identity-webhook created
deployment "pod-identity-webhook" successfully rolled out
```

### 1.2 The emulator

It was already running in Docker (`aws-emulator -b 0.0.0.0 -p 9297`, published on
`127.0.0.1:9297`). Its startup log says where its files are:

```text
INFO request authentication enabled  credentials_file=/Users/<you>/.aws/emulator/9297/emulator_credentials
INFO emulator server information     endpoint_url=https://127.0.0.1:9297
                                     health_check_url=https://127.0.0.1:9297/__admin/health
                                     config_file=/Users/<you>/.aws/emulator/9297/config
                                     ca_file=/Users/<you>/.aws/emulator/9297/ca.pem
```

```console
$ cat ~/.aws/emulator/9297/config
[default]
region = us-east-1
endpoint_url = https://127.0.0.1:9297
ca_bundle = /Users/<you>/.aws/emulator/9297/ca.pem
s3 =
  signature_version = s3v4

$ cat ~/.aws/emulator/9297/emulator_credentials
[default]
aws_access_key_id = EMULATORAC167D604544
aws_secret_access_key = <secret>
```

- **Requests must be SigV4-signed** with those keys (`-a, --auth on|off`, default on), as on AWS.
  The account is `000000000000`.
- **TLS with its own CA.** The server certificate is for `DNS:localhost, IP:127.0.0.1, IP:::1`
  only, which matters when a Pod calls it (§6.1).
- **Any SDK works unchanged** with `AWS_CONFIG_FILE` and `AWS_SHARED_CREDENTIALS_FILE` pointed at
  those two files. `endpoint_url` sends every service to the emulator.

### 1.3 How the requests below were captured

A boto3 client with two botocore event hooks: `before-send` sees the request as it goes to the
HTTP library, and `before-parse` sees the raw response before boto3 parses it. Each exchange is
printed as two HTTP/1.1 messages, the request and then the response, in the format they have on
the wire (request line or status line, headers, blank line, body). What differs from the bytes on
the wire:

- **Left out:** `User-Agent` and the SDK's `amz-sdk-invocation-id` / `amz-sdk-request` headers.
  `Host` is added by the HTTP library, after the hook, so it is shown from the URL.
- **Shortened (`…`):** the SigV4 signature (`Signature=<64 hex>`), the `WebIdentityToken` form
  field, session tokens and secret keys. So `Content-Length` is the real length, not the length of
  the body as printed.
- **Response bodies are indented** for reading. On the wire, the XML and JSON are one line.
- **Form bodies** (IAM and STS use `application/x-www-form-urlencoded`) are shown as sent. When
  they contain URL-encoded data, e.g. a policy document, a second block shows the fields decoded.

---

## 2. Step 1: the cluster's OIDC issuer and the IAM OIDC provider

> Create an OIDC provider in IAM for your cluster. You can find the OIDC discovery endpoint by
> describing your EKS cluster. `aws eks describe-cluster --name $CLUSTER_NAME --query cluster.identity.oidc`
> And enter "sts.amazonaws.com" as the client-id

### 2.1 Every EKS cluster has an issuer (lab)

The emulator models EKS. `CreateCluster` needs an IAM role for the cluster and two real subnets
(it checks them against its EC2: a made-up `subnet-22222222` was rejected with
`InvalidSubnetID.NotFound`). The default VPC's subnets were used. The new cluster is `CREATING` and
has no `identity` yet:

<details><summary>CreateCluster request and response</summary>

```http
POST /clusters HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/json
X-Amz-Date: 20261123T081255Z
Authorization: AWS4-HMAC-SHA256 Credential=EMULATORAC167D604544/20261123/us-east-1/eks/aws4_request, SignedHeaders=content-type;host;x-amz-date, Signature=<64 hex>
Content-Length: 254

{"name": "demo", "version": "1.34", "roleArn": "arn:aws:iam::000000000000:role/eks-cluster-role", "resourcesVpcConfig": {"subnetIds": ["subnet-00000000000000007", "subnet-00000000000000009"]}, "clientRequestToken": "797507f2-d365-463e-a0d7-20bc245a2ed3"}
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 1442
date: Mon, 23 Nov 2026 08:12:55 GMT

{
  "cluster": {
    "name": "demo",
    "arn": "arn:aws:eks:us-east-1:000000000000:cluster/demo",
    "createdAt": 1795421575.5496955,
    "version": "1.34",
    "roleArn": "arn:aws:iam::000000000000:role/eks-cluster-role",
    "resourcesVpcConfig": {
      "subnetIds": [
        "subnet-00000000000000007",
        "subnet-00000000000000009"
      ],
      "securityGroupIds": [],
      "vpcId": "vpc-00000000000000001",
      "endpointPublicAccess": true,
      "endpointPrivateAccess": false,
      "publicAccessCidrs": [
        "0.0.0.0/0"
      ],
      "controlPlaneEgressMode": "AWS_MANAGED"
    },
    "kubernetesNetworkConfig": {
      "serviceIpv4Cidr": "10.100.0.0/16",
      "ipFamily": "ipv4",
      "elasticLoadBalancing": {
        "enabled": false
      }
    },
    "logging": {
      "clusterLogging": [
        {
          "types": [
            "api",
            "audit",
            "authenticator",
            "controllerManager",
            "scheduler"
          ],
          "enabled": false
        }
      ]
    },
    "status": "CREATING",
    "certificateAuthority": {},
    "platformVersion": "eks.4",
    "tags": {},
    "accessConfig": {
      "bootstrapClusterCreatorAdminPermissions": true,
      "authenticationMode": "CONFIG_MAP"
    },
    "upgradePolicy": {
      "supportType": "EXTENDED"
    },
    "deletionProtection": false,
    "controlPlaneScalingConfig": {
      "tier": "standard"
    },
    "kubeApiServerConfig": {
      "eventTtl": "60m",
      "serviceNodePortRange": {
        "minPort": 30000,
        "maxPort": 32767
      }
    },
    "kubeSchedulerConfig": {
      "nodeResourcesFit": {
        "scoringStrategy": {
          "type": "LeastAllocated",
          "resources": [
            {
              "name": "cpu",
              "weight": 1
            },
            {
              "name": "memory",
              "weight": 1
            }
          ]
        }
      }
    },
    "kubeControllerManagerConfig": {
      "podGcControllerConfig": {
        "terminatedPodGcThreshold": 12500
      },
      "horizontalPodAutoscalerControllerConfig": {
        "horizontalPodAutoscalerSyncPeriod": "15s"
      }
    }
  }
}
```

</details>

Once `ACTIVE`, `DescribeCluster` has the issuer. `--query cluster.identity.oidc` is the AWS CLI
cutting this response down on the client side; the request is the same:

<details><summary>DescribeCluster request and full response</summary>

```http
GET /clusters/demo HTTP/1.1
Host: 127.0.0.1:9297
X-Amz-Date: 20261123T081257Z
Authorization: AWS4-HMAC-SHA256 Credential=EMULATORAC167D604544/20261123/us-east-1/eks/aws4_request, SignedHeaders=host;x-amz-date, Signature=<64 hex>
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 2416
date: Mon, 23 Nov 2026 08:12:57 GMT

{
  "cluster": {
    "name": "demo",
    "arn": "arn:aws:eks:us-east-1:000000000000:cluster/demo",
    "createdAt": 1795421575.5496955,
    "version": "1.34",
    "endpoint": "https://127.0.0.1:38539",
    "roleArn": "arn:aws:iam::000000000000:role/eks-cluster-role",
    "resourcesVpcConfig": {
      "subnetIds": [
        "subnet-00000000000000007",
        "subnet-00000000000000009"
      ],
      "securityGroupIds": [],
      "clusterSecurityGroupId": "sg-0000000000000009e",
      "vpcId": "vpc-00000000000000001",
      "endpointPublicAccess": true,
      "endpointPrivateAccess": false,
      "publicAccessCidrs": [
        "0.0.0.0/0"
      ],
      "controlPlaneEgressMode": "AWS_MANAGED"
    },
    "kubernetesNetworkConfig": {
      "serviceIpv4Cidr": "10.100.0.0/16",
      "ipFamily": "ipv4",
      "elasticLoadBalancing": {
        "enabled": false
      }
    },
    "logging": {
      "clusterLogging": [
        {
          "types": [
            "api",
            "audit",
            "authenticator",
            "controllerManager",
            "scheduler"
          ],
          "enabled": false
        }
      ]
    },
    "identity": {
      "oidc": {
        "issuer": "https://oidc.eks.us-east-1.amazonaws.com/id/49164ADEF38DAA9EB91B894E2D721EBE"
      }
    },
    "status": "ACTIVE",
    "certificateAuthority": {
      "data": "LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSUJiekNDQVJXZ0F3SUJBZ0lVRkl6dDAzU2lidjlHTU45ejV5NnpXcmlVeHBRd0NnWUlLb1pJemowRUF3SXcKRlRFVE1CRUdBMVVFQXd3S2EzVmlaWEp1WlhSbGN6QWVGdzB5TmpFeE1qTXdPREV5TlRWYUZ3MHpOakV4TWpBdwpPREV5TlRWYU1CVXhFekFSQmdOVkJBTU1DbXQxWW1WeWJtVjBaWE13V1RBVEJnY3Foa2pPUFFJQkJnZ3Foa2pPClBRTUJCd05DQUFRRGREeFlSRkxQRm9Fdjk2b3Q0MWJQZ3BsOVdZRFMwMEZ1SkNXQ2VCQVBqbjRzV24zUTdrWDQKVkFBd3FQczhzKzBLdVJzM25rNm41ZjU2L2UyMGpKYnFvME13UVRBUEJnTlZIUThCQWY4RUJRTURCNFlBTUIwRwpBMVVkRGdRV0JCVHNmYkdTVW5EVE1PU05GWlYwK1IrY0tkVXZjakFQQmdOVkhSTUJBZjhFQlRBREFRSC9NQW9HCkNDcUdTTTQ5QkFNQ0EwZ0FNRVVDSVFENDRsak5oNjBiOXhXdHRzaXcxRlJoTFUxaFNqTWdsZDlUd2RvVFgrV0gKcUFJZ0M4WVZrS29tSjFpTmZ4dVlDdlE5MjlydDM2OHMrVEdLeC9ObUNoYllDeEE9Ci0tLS0tRU5EIENFUlRJRklDQVRFLS0tLS0K",
      "active": {
        "id": "018b3af6-c177-420c-b3b0-fd2be87faca4",
        "activatedBy": "EKS"
      }
    },
    "platformVersion": "eks.4",
    "tags": {},
    "accessConfig": {
      "authenticationMode": "CONFIG_MAP"
    },
    "upgradePolicy": {
      "supportType": "EXTENDED"
    },
    "deletionProtection": false,
    "controlPlaneScalingConfig": {
      "tier": "standard"
    },
    "kubeApiServerConfig": {
      "eventTtl": "60m",
      "serviceNodePortRange": {
        "minPort": 30000,
        "maxPort": 32767
      }
    },
    "kubeSchedulerConfig": {
      "nodeResourcesFit": {
        "scoringStrategy": {
          "type": "LeastAllocated",
          "resources": [
            {
              "name": "cpu",
              "weight": 1
            },
            {
              "name": "memory",
              "weight": 1
            }
          ]
        }
      }
    },
    "kubeControllerManagerConfig": {
      "podGcControllerConfig": {
        "terminatedPodGcThreshold": 12500
      },
      "horizontalPodAutoscalerControllerConfig": {
        "horizontalPodAutoscalerSyncPeriod": "15s"
      }
    }
  }
}
```

```text
issuer = https://oidc.eks.us-east-1.amazonaws.com/id/49164ADEF38DAA9EB91B894E2D721EBE
```

</details>

The part the walkthrough needs:

```json
"identity": {
  "oidc": {
    "issuer": "https://oidc.eks.us-east-1.amazonaws.com/id/49164ADEF38DAA9EB91B894E2D721EBE"
  }
}
```

- **The issuer is per cluster.** It's `https://oidc.eks.<region>.amazonaws.com/id/<32 hex>`. Every
  ServiceAccount token from this cluster has it as `iss`.
- **The emulator's `endpoint` (`https://127.0.0.1:<port>`) is a placeholder.** Nothing listens
  there, and the emulator serves no discovery document for the issuer. On real EKS, AWS hosts
  `<issuer>/.well-known/openid-configuration` and the JWKS publicly **(docs)**.

### 2.2 What STS will fetch from the issuer (lab, k3s)

The k3s cluster serves the same two documents an EKS issuer serves publicly. Here they come from
kube-apiserver:

```console
$ kubectl get --raw /.well-known/openid-configuration
{
    "issuer": "https://kubernetes.default.svc.cluster.local",
    "jwks_uri": "https://172.21.0.2:6443/openid/v1/jwks",
    "response_types_supported": ["id_token"],
    "subject_types_supported": ["public"],
    "id_token_signing_alg_values_supported": ["RS256"]
}

$ kubectl get --raw /openid/v1/jwks
{
    "keys": [
        {
            "use": "sig",
            "kty": "RSA",
            "kid": "5q7RSqr-V4vvoweOD0_qATLMWQ8upW61QL-ZiXDdwRc",
            "alg": "RS256",
            "n": "pPhOl6lYYbNxhxCze9aebmjGAytjkdk0R0TNJr6IkxmL6rPI4oDIXvQNS4SO2bnZ...",
            "e": "AQAB"
        }
    ]
}
```

STS needs to reach both: `<iss>/.well-known/openid-configuration`, then the `jwks_uri` in it. This
issuer, `https://kubernetes.default.svc.cluster.local`, only resolves inside the cluster, and the
`jwks_uri` is a private IP. A real STS couldn't fetch either, which is why a self-managed cluster
has to publish them somewhere public, e.g. an S3 bucket or a static site, with
`--service-account-issuer` set to that URL **(docs)**. EKS does this for you.

### 2.3 CreateOpenIDConnectProvider (lab)

The walkthrough's "enter sts.amazonaws.com as the client-id" is `ClientIDList`:

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
X-Amz-Date: 20261123T081257Z
Authorization: AWS4-HMAC-SHA256 Credential=EMULATORAC167D604544/20261123/us-east-1/iam/aws4_request, SignedHeaders=content-type;host;x-amz-date, Signature=<64 hex>
Content-Length: 184

Action=CreateOpenIDConnectProvider&Version=2010-05-08&Url=https%3A%2F%2Foidc.eks.us-east-1.amazonaws.com%2Fid%2F49164ADEF38DAA9EB91B894E2D721EBE&ClientIDList.member.1=sts.amazonaws.com
```

```text
# the same form fields, URL-decoded
Action = CreateOpenIDConnectProvider
Version = 2010-05-08
Url = https://oidc.eks.us-east-1.amazonaws.com/id/49164ADEF38DAA9EB91B894E2D721EBE
ClientIDList.member.1 = sts.amazonaws.com
```

```http
HTTP/1.1 200 OK
content-type: text/xml
content-length: 453
date: Mon, 23 Nov 2026 08:12:57 GMT

<CreateOpenIDConnectProviderResponse xmlns="https://iam.amazonaws.com/doc/2010-05-08/">
  <CreateOpenIDConnectProviderResult>
    <OpenIDConnectProviderArn>arn:aws:iam::000000000000:oidc-provider/oidc.eks.us-east-1.amazonaws.com/id/49164ADEF38DAA9EB91B894E2D721EBE</OpenIDConnectProviderArn>
  </CreateOpenIDConnectProviderResult>
  <ResponseMetadata>
    <RequestId>4e6371f6-6d38-42d3-b494-677379a73cd8</RequestId>
  </ResponseMetadata>
</CreateOpenIDConnectProviderResponse>
```

The ARN is `arn:aws:iam::<account>:oidc-provider/` followed by the issuer **without `https://`**.
That same string, `oidc.eks.<region>.amazonaws.com/id/<ID>`, is the prefix of the condition keys in
the trust policy (§3).

Reading it back:

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
X-Amz-Date: 20261123T081257Z
Authorization: AWS4-HMAC-SHA256 Credential=EMULATORAC167D604544/20261123/us-east-1/iam/aws4_request, SignedHeaders=content-type;host;x-amz-date, Signature=<64 hex>
Content-Length: 200

Action=GetOpenIDConnectProvider&Version=2010-05-08&OpenIDConnectProviderArn=arn%3Aaws%3Aiam%3A%3A000000000000%3Aoidc-provider%2Foidc.eks.us-east-1.amazonaws.com%2Fid%2F49164ADEF38DAA9EB91B894E2D721EBE
```

```text
# the same form fields, URL-decoded
Action = GetOpenIDConnectProvider
Version = 2010-05-08
OpenIDConnectProviderArn = arn:aws:iam::000000000000:oidc-provider/oidc.eks.us-east-1.amazonaws.com/id/49164ADEF38DAA9EB91B894E2D721EBE
```

```http
HTTP/1.1 200 OK
content-type: text/xml
content-length: 504
date: Mon, 23 Nov 2026 08:12:57 GMT

<GetOpenIDConnectProviderResponse xmlns="https://iam.amazonaws.com/doc/2010-05-08/">
  <GetOpenIDConnectProviderResult>
    <Url>oidc.eks.us-east-1.amazonaws.com/id/49164ADEF38DAA9EB91B894E2D721EBE</Url>
    <ClientIDList>
      <member>sts.amazonaws.com</member>
    </ClientIDList>
    <ThumbprintList/>
    <CreateDate>2026-11-23T08:12:57.585Z</CreateDate>
  </GetOpenIDConnectProviderResult>
  <ResponseMetadata>
    <RequestId>266cf9d8-3e9d-4075-bcdf-80653d45a61d</RequestId>
  </ResponseMetadata>
</GetOpenIDConnectProviderResponse>
```

- **`ClientIDList` is the list of allowed audiences.** STS rejects a token whose `aud` isn't in it.
  `sts.amazonaws.com` is what the webhook puts in the token by default (§4).
- **`ThumbprintList` is empty**, since none was passed. Older guides compute the TLS certificate's
  SHA-1 thumbprint for `--thumbprint-list`. IAM made it optional and verifies the issuer's TLS
  through trusted CAs for providers like EKS's **(docs)**.
- **One provider per cluster, per account.** A role used by several clusters needs each
  cluster's provider, in each account. IAM's default quota is 100 OIDC providers per account
  ([workload-identity.md](workload-identity.md) §4).

---

## 3. Step 2: the IAM role

> Create an IAM role for your pods and modify the trust policy to allow your pod's service account
> to use the role

A role has two policies, and IRSA needs both:

| Policy | Answers | API | Here |
|---|---|---|---|
| **trust policy** (`AssumeRolePolicyDocument`) | who may become this role, and how | `CreateRole` / `UpdateAssumeRolePolicy` | the OIDC provider, `sts:AssumeRoleWithWebIdentity`, conditions on `sub` and `aud` |
| **permissions policy** | what the role may do once assumed | `AttachRolePolicy` / `PutRolePolicy` | `AmazonS3ReadOnlyAccess` |

### 3.1 The README's trust policy, as written (lab)

Copied verbatim, with `REGION`/`CLUSTER_ID` replaced by the emulated cluster's issuer:

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
X-Amz-Date: 20261123T081257Z
Authorization: AWS4-HMAC-SHA256 Credential=EMULATORAC167D604544/20261123/us-east-1/iam/aws4_request, SignedHeaders=content-type;host;x-amz-date, Signature=<64 hex>
Content-Length: 1072

Action=CreateRole&Version=2010-05-08&RoleName=s3-reader-readme&AssumeRolePolicyDocument=%7B%0A%20%22Version%22%3A%20%222012-10-17%22%2C%0A%20%22Statement%22%3A%20%5B%0A%20%20%7B%0A%20%20%20%22Effect%22%3A%20%22Allow%22%2C%0A%20%20%20%22Principal%22%3A%20%7B%0A%20%20%20%20%22Federated%22%3A%20%22arn%3Aaws%3Aiam%3A%3A000000000000%3Aoidc-provider%2Foidc.eks.us-east-1.amazonaws.com%2Fid%2F49164ADEF38DAA9EB91B894E2D721EBE%22%0A%20%20%20%7D%2C%0A%20%20%20%22Action%22%3A%20%22sts%3AAssumeRoleWithWebIdentity%22%2C%0A%20%20%20%22Condition%22%3A%20%7B%0A%20%20%20%20%22__doc_comment%22%3A%20%22scope%20the%20role%20to%20the%20service%20account%20%28optional%29%22%2C%0A%20%20%20%20%22StringEquals%22%3A%20%7B%0A%20%20%20%20%20%22oidc.eks.us-east-1.amazonaws.com%2Fid%2F49164ADEF38DAA9EB91B894E2D721EBE%3Asub%22%3A%20%22system%3Aserviceaccount%3Adefault%3Amy-serviceaccount%22%0A%20%20%20%20%7D%2C%0A%20%20%20%20%22__doc_comment%22%3A%20%22scope%20the%20role%20to%20a%20namespace%20%28optional%29%22%2C%0A%20%20%20%20%22StringLike%22%3A%20%7B%0A%20%20%20%20%20%22oidc.eks.us-east-1.amazonaws.com%2Fid%2F49164ADEF38DAA9EB91B894E2D721EBE%3Asub%22%3A%20%22system%3Aserviceaccount%3Adefault%3A%2A%22%0A%20%20%20%20%7D%0A%20%20%20%7D%0A%20%20%7D%0A%20%5D%0A%7D
```

```text
# the same form fields, URL-decoded
Action = CreateRole
Version = 2010-05-08
RoleName = s3-reader-readme
AssumeRolePolicyDocument =
  {
   "Version": "2012-10-17",
   "Statement": [
    {
     "Effect": "Allow",
     "Principal": {
      "Federated": "arn:aws:iam::000000000000:oidc-provider/oidc.eks.us-east-1.amazonaws.com/id/49164ADEF38DAA9EB91B894E2D721EBE"
     },
     "Action": "sts:AssumeRoleWithWebIdentity",
     "Condition": {
      "__doc_comment": "scope the role to the service account (optional)",
      "StringEquals": {
       "oidc.eks.us-east-1.amazonaws.com/id/49164ADEF38DAA9EB91B894E2D721EBE:sub": "system:serviceaccount:default:my-serviceaccount"
      },
      "__doc_comment": "scope the role to a namespace (optional)",
      "StringLike": {
       "oidc.eks.us-east-1.amazonaws.com/id/49164ADEF38DAA9EB91B894E2D721EBE:sub": "system:serviceaccount:default:*"
      }
     }
    }
   ]
  }
```

```http
HTTP/1.1 200 OK
content-type: text/xml
content-length: 1452
date: Mon, 23 Nov 2026 08:12:57 GMT

<CreateRoleResponse xmlns="https://iam.amazonaws.com/doc/2010-05-08/">
  <CreateRoleResult>
    <Role xmlns="https://iam.amazonaws.com/doc/2010-05-08/">
      <Path>/</Path>
      <RoleName>s3-reader-readme</RoleName>
      <RoleId>AROAESHQKAAAAGSVKY4QA</RoleId>
      <Arn>arn:aws:iam::000000000000:role/s3-reader-readme</Arn>
      <CreateDate>2026-11-23T08:12:57.605Z</CreateDate>
      <AssumeRolePolicyDocument>%7B%22Version%22%3A%222012-10-17%22%2C%22Statement%22%3A%5B%7B%22Effect%22%3A%22Allow%22%2C%22Principal%22%3A%7B%22Federated%22%3A%22arn%3Aaws%3Aiam%3A%3A000000000000%3Aoidc-provider%2Foidc.eks.us-east-1.amazonaws.com%2Fid%2F49164ADEF38DAA9EB91B894E2D721EBE%22%7D%2C%22Action%22%3A%22sts%3AAssumeRoleWithWebIdentity%22%2C%22Condition%22%3A%7B%22__doc_comment%22%3A%22scope%20the%20role%20to%20the%20service%20account%20%28optional%29%22%2C%22StringEquals%22%3A%7B%22oidc.eks.us-east-1.amazonaws.com%2Fid%2F49164ADEF38DAA9EB91B894E2D721EBE%3Asub%22%3A%22system%3Aserviceaccount%3Adefault%3Amy-serviceaccount%22%7D%2C%22__doc_comment%22%3A%22scope%20the%20role%20to%20a%20namespace%20%28optional%29%22%2C%22StringLike%22%3A%7B%22oidc.eks.us-east-1.amazonaws.com%2Fid%2F49164ADEF38DAA9EB91B894E2D721EBE%3Asub%22%3A%22system%3Aserviceaccount%3Adefault%3A%2A%22%7D%7D%7D%5D%7D</AssumeRolePolicyDocument>
      <MaxSessionDuration>3600</MaxSessionDuration>
    </Role>
  </CreateRoleResult>
  <ResponseMetadata>
    <RequestId>213a4561-406c-4fad-a944-f49a2208436c</RequestId>
  </ResponseMetadata>
</CreateRoleResponse>
```

The emulator accepted it. The policy still has three problems, whatever any IAM accepts:

1. **The two `"__doc_comment"` keys are not comments.** JSON has no comments, and `Condition` is
   a map from condition operator to tests, so `__doc_comment` sits where an operator like
   `StringEquals` goes. A key appearing twice in one object is undefined behaviour in JSON: most
   parsers keep the last value. Whether real IAM rejects this as an unknown condition operator
   wasn't tested; the emulator stored it unchanged (the response echoes it back, URL-encoded).
   Delete both lines.
2. **"(optional)" on both blocks hides the logic.** The blocks inside one `Condition` are **ANDed**.
   With both present, the Pod needs `sub` to equal `system:serviceaccount:default:my-serviceaccount`
   *and* to match `system:serviceaccount:default:*`. The second test adds nothing. Tested in §8 with
   the same shape: the token for the other subject was denied. Pick one block: `StringEquals` for
   one ServiceAccount, `StringLike` for a whole namespace.
3. **No `aud` condition.** AWS's EKS docs add `"<issuer>:aud": "sts.amazonaws.com"` next to `sub`
   **(docs)**. Without it, any audience listed on the OIDC provider is enough.

### 3.2 The trust policy as it should be written (lab)

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
X-Amz-Date: 20261123T081257Z
Authorization: AWS4-HMAC-SHA256 Credential=EMULATORAC167D604544/20261123/us-east-1/iam/aws4_request, SignedHeaders=content-type;host;x-amz-date, Signature=<64 hex>
Content-Length: 876

Action=CreateRole&Version=2010-05-08&RoleName=s3-reader&AssumeRolePolicyDocument=%7B%0A%20%20%22Version%22%3A%20%222012-10-17%22%2C%0A%20%20%22Statement%22%3A%20%5B%0A%20%20%20%20%7B%0A%20%20%20%20%20%20%22Effect%22%3A%20%22Allow%22%2C%0A%20%20%20%20%20%20%22Principal%22%3A%20%7B%0A%20%20%20%20%20%20%20%20%22Federated%22%3A%20%22arn%3Aaws%3Aiam%3A%3A000000000000%3Aoidc-provider%2Foidc.eks.us-east-1.amazonaws.com%2Fid%2F49164ADEF38DAA9EB91B894E2D721EBE%22%0A%20%20%20%20%20%20%7D%2C%0A%20%20%20%20%20%20%22Action%22%3A%20%22sts%3AAssumeRoleWithWebIdentity%22%2C%0A%20%20%20%20%20%20%22Condition%22%3A%20%7B%0A%20%20%20%20%20%20%20%20%22StringEquals%22%3A%20%7B%0A%20%20%20%20%20%20%20%20%20%20%22oidc.eks.us-east-1.amazonaws.com%2Fid%2F49164ADEF38DAA9EB91B894E2D721EBE%3Asub%22%3A%20%22system%3Aserviceaccount%3Adefault%3Amy-serviceaccount%22%2C%0A%20%20%20%20%20%20%20%20%20%20%22oidc.eks.us-east-1.amazonaws.com%2Fid%2F49164ADEF38DAA9EB91B894E2D721EBE%3Aaud%22%3A%20%22sts.amazonaws.com%22%0A%20%20%20%20%20%20%20%20%7D%0A%20%20%20%20%20%20%7D%0A%20%20%20%20%7D%0A%20%20%5D%0A%7D
```

```text
# the same form fields, URL-decoded
Action = CreateRole
Version = 2010-05-08
RoleName = s3-reader
AssumeRolePolicyDocument =
  {
    "Version": "2012-10-17",
    "Statement": [
      {
        "Effect": "Allow",
        "Principal": {
          "Federated": "arn:aws:iam::000000000000:oidc-provider/oidc.eks.us-east-1.amazonaws.com/id/49164ADEF38DAA9EB91B894E2D721EBE"
        },
        "Action": "sts:AssumeRoleWithWebIdentity",
        "Condition": {
          "StringEquals": {
            "oidc.eks.us-east-1.amazonaws.com/id/49164ADEF38DAA9EB91B894E2D721EBE:sub": "system:serviceaccount:default:my-serviceaccount",
            "oidc.eks.us-east-1.amazonaws.com/id/49164ADEF38DAA9EB91B894E2D721EBE:aud": "sts.amazonaws.com"
          }
        }
      }
    ]
  }
```

```http
HTTP/1.1 200 OK
content-type: text/xml
content-length: 1207
date: Mon, 23 Nov 2026 08:12:57 GMT

<CreateRoleResponse xmlns="https://iam.amazonaws.com/doc/2010-05-08/">
  <CreateRoleResult>
    <Role xmlns="https://iam.amazonaws.com/doc/2010-05-08/">
      <Path>/</Path>
      <RoleName>s3-reader</RoleName>
      <RoleId>AROAJYNPMYTFBGCEKOAKW</RoleId>
      <Arn>arn:aws:iam::000000000000:role/s3-reader</Arn>
      <CreateDate>2026-11-23T08:12:57.681Z</CreateDate>
      <AssumeRolePolicyDocument>%7B%22Version%22%3A%222012-10-17%22%2C%22Statement%22%3A%5B%7B%22Effect%22%3A%22Allow%22%2C%22Principal%22%3A%7B%22Federated%22%3A%22arn%3Aaws%3Aiam%3A%3A000000000000%3Aoidc-provider%2Foidc.eks.us-east-1.amazonaws.com%2Fid%2F49164ADEF38DAA9EB91B894E2D721EBE%22%7D%2C%22Action%22%3A%22sts%3AAssumeRoleWithWebIdentity%22%2C%22Condition%22%3A%7B%22StringEquals%22%3A%7B%22oidc.eks.us-east-1.amazonaws.com%2Fid%2F49164ADEF38DAA9EB91B894E2D721EBE%3Asub%22%3A%22system%3Aserviceaccount%3Adefault%3Amy-serviceaccount%22%2C%22oidc.eks.us-east-1.amazonaws.com%2Fid%2F49164ADEF38DAA9EB91B894E2D721EBE%3Aaud%22%3A%22sts.amazonaws.com%22%7D%7D%7D%5D%7D</AssumeRolePolicyDocument>
      <MaxSessionDuration>3600</MaxSessionDuration>
    </Role>
  </CreateRoleResult>
  <ResponseMetadata>
    <RequestId>6ef32f50-72c2-46ac-b540-5b8fb4def664</RequestId>
  </ResponseMetadata>
</CreateRoleResponse>
```

- `Principal.Federated` is the OIDC provider's ARN from §2.3.
- The condition keys are `<issuer without https://>:sub` and `:aud`, and are compared with the
  token's claims.
- `MaxSessionDuration` is 3600: the credentials STS returns last one hour unless the role allows
  more and the caller asks for more (`DurationSeconds`, 900 s to 12 h).
- IAM returns the policy document **URL-encoded** in the XML (`%7B%22Version%22...`). The SDKs
  decode it.

### 3.3 The permissions policy (lab)

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
X-Amz-Date: 20261123T081257Z
Authorization: AWS4-HMAC-SHA256 Credential=EMULATORAC167D604544/20261123/us-east-1/iam/aws4_request, SignedHeaders=content-type;host;x-amz-date, Signature=<64 hex>
Content-Length: 130

Action=AttachRolePolicy&Version=2010-05-08&RoleName=s3-reader&PolicyArn=arn%3Aaws%3Aiam%3A%3Aaws%3Apolicy%2FAmazonS3ReadOnlyAccess
```

```text
# the same form fields, URL-decoded
Action = AttachRolePolicy
Version = 2010-05-08
RoleName = s3-reader
PolicyArn = arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess
```

```http
HTTP/1.1 200 OK
content-type: text/xml
content-length: 248
date: Mon, 23 Nov 2026 08:12:57 GMT

<AttachRolePolicyResponse xmlns="https://iam.amazonaws.com/doc/2010-05-08/">
  <AttachRolePolicyResult/>
  <ResponseMetadata>
    <RequestId>95383f2d-ef6f-4b19-a7c9-a924475e012f</RequestId>
  </ResponseMetadata>
</AttachRolePolicyResponse>
```

A bucket `irsa-demo-bucket` with one object, `hello.txt`, was also created (not shown) for §7.

---

## 4. Step 3: the ServiceAccount and the Pod

> Modify your pod's service account to be annotated with the ARN of the role you want the pod to use

### 4.1 Applied as the README writes them (lab)

The README's ServiceAccount and Pod, with only the account ID (the emulator's `000000000000`) and
the images (`busybox`) changed:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: my-serviceaccount
  namespace: default
  annotations:
    eks.amazonaws.com/role-arn: "arn:aws:iam::000000000000:role/s3-reader"
    eks.amazonaws.com/audience: "sts.amazonaws.com"
    eks.amazonaws.com/sts-regional-endpoints: "true"
    eks.amazonaws.com/token-expiration: "86400"
---
apiVersion: v1
kind: Pod
metadata:
  name: my-pod
  namespace: default
  annotations:
    eks.amazonaws.com/skip-containers: "init-first,sidecar"
    eks.amazonaws.com/token-expiration: "86400"
spec:
  serviceAccountName: my-serviceaccount
  initContainers:
  - name: init-first
    image: busybox:1.36
    command: ["true"]
  containers:
  - name: sidecar
    image: busybox:1.36
    command: ["sleep", "3600"]
  - name: container-name
    image: busybox:1.36
    command: ["sleep", "3600"]
```

### 4.2 The Pod as stored (lab)

```console
$ kubectl get pod my-pod -o yaml | sed -n '/^spec:/,/^status:/p' \
    | grep -v -E 'terminationMessage|schedulerName|preemptionPolicy|priority:|enableServiceLinks|imagePullPolicy|resources: \{\}'
spec:
  containers:
  - command:
    - sleep
    - "3600"
    image: busybox:1.36
    name: sidecar
    volumeMounts:
    - mountPath: /var/run/secrets/kubernetes.io/serviceaccount
      name: kube-api-access-2zf5n
      readOnly: true
  - command:
    - sleep
    - "3600"
    env:
    - name: AWS_STS_REGIONAL_ENDPOINTS
      value: regional
    - name: AWS_DEFAULT_REGION
      value: us-west-2
    - name: AWS_REGION
      value: us-west-2
    - name: AWS_ROLE_ARN
      value: arn:aws:iam::000000000000:role/s3-reader
    - name: AWS_WEB_IDENTITY_TOKEN_FILE
      value: /var/run/secrets/eks.amazonaws.com/serviceaccount/token
    image: busybox:1.36
    name: container-name
    volumeMounts:
    - mountPath: /var/run/secrets/kubernetes.io/serviceaccount
      name: kube-api-access-2zf5n
      readOnly: true
    - mountPath: /var/run/secrets/eks.amazonaws.com/serviceaccount
      name: aws-iam-token
      readOnly: true
  dnsPolicy: ClusterFirst
  initContainers:
  - command:
    - "true"
    image: busybox:1.36
    name: init-first
    volumeMounts:
    - mountPath: /var/run/secrets/kubernetes.io/serviceaccount
      name: kube-api-access-2zf5n
      readOnly: true
  nodeName: cal-server
  restartPolicy: Always
  securityContext: {}
  serviceAccount: my-serviceaccount
  serviceAccountName: my-serviceaccount
  terminationGracePeriodSeconds: 30
  tolerations:
  - effect: NoExecute
    key: node.kubernetes.io/not-ready
    operator: Exists
    tolerationSeconds: 300
  - effect: NoExecute
    key: node.kubernetes.io/unreachable
    operator: Exists
    tolerationSeconds: 300
  volumes:
  - name: aws-iam-token
    projected:
      defaultMode: 420
      sources:
      - serviceAccountToken:
          audience: sts.amazonaws.com
          expirationSeconds: 86400
          path: token
  - name: kube-api-access-2zf5n
    projected:
      defaultMode: 420
      sources:
      - serviceAccountToken:
          expirationSeconds: 3607
          path: token
      - configMap:
          items:
          - key: ca.crt
            path: ca.crt
          name: kube-root-ca.crt
      - downwardAPI:
          items:
          - fieldRef:
              apiVersion: v1
              fieldPath: metadata.namespace
            path: namespace
status:
```

Compared with the README's "Everything below is added by the webhook":

| | README | v0.6.19 in the lab |
|---|---|---|
| containers changed | `container-name` | `container-name` only. `init-first` and `sidecar` untouched, as `skip-containers` asks |
| env names | the five | the same five |
| env order | `AWS_DEFAULT_REGION`, `AWS_REGION`, `AWS_ROLE_ARN`, `AWS_WEB_IDENTITY_TOKEN_FILE`, `AWS_STS_REGIONAL_ENDPOINTS` | `AWS_STS_REGIONAL_ENDPOINTS` first, then `AWS_DEFAULT_REGION`, `AWS_REGION`, `AWS_ROLE_ARN`, `AWS_WEB_IDENTITY_TOKEN_FILE` |
| volume name | `aws-token` | **`aws-iam-token`** |
| mount | `/var/run/secrets/eks.amazonaws.com/serviceaccount/` | `/var/run/secrets/eks.amazonaws.com/serviceaccount`, **`readOnly: true`** |
| token source | `audience: sts.amazonaws.com`, `expirationSeconds: 86400`, `path: token` | the same |
| region values | `us-west-2` | `us-west-2`, **only because the webhook was started with `--aws-default-region=us-west-2`** (§1.1). Without the flag there are no region variables ([workload-identity.md](workload-identity.md) §3) |

Order and names don't matter to the SDK: it reads the env var, then the file it names. Anything
that looks the volume up by name (a policy engine, a script) has to use `aws-iam-token`.

### 4.3 Every annotation, tested (lab + code)

Each line is one Pod created against a ServiceAccount with `role-arn` set. The result is read from
the stored Pod (`.spec.volumes[aws-iam-token]` and the init container's env):

```console
t-aud      sa=aud-sa             podAnn=-       → aud=my-audience exp=86400 initEnv=AWS_DEFAULT_REGION AWS_REGION AWS_ROLE_ARN AWS_WEB_IDENTITY_TOKEN_FILE
t-pod7200  sa=my-serviceaccount  podAnn=7200    → aud=sts.amazonaws.com exp=7200 initEnv=AWS_STS_REGIONAL_ENDPOINTS AWS_DEFAULT_REGION AWS_REGION AWS_ROLE_ARN AWS_WEB_IDENTITY_TOKEN_FILE
t-pod600   sa=my-serviceaccount  podAnn=600     → aud=sts.amazonaws.com exp=600 initEnv=AWS_STS_REGIONAL_ENDPOINTS AWS_DEFAULT_REGION AWS_REGION AWS_ROLE_ARN AWS_WEB_IDENTITY_TOKEN_FILE
t-pod1y    sa=my-serviceaccount  podAnn=31536000 → aud=sts.amazonaws.com exp=31536000 initEnv=AWS_STS_REGIONAL_ENDPOINTS AWS_DEFAULT_REGION AWS_REGION AWS_ROLE_ARN AWS_WEB_IDENTITY_TOKEN_FILE
t-podbad   sa=my-serviceaccount  podAnn=abc     → aud=sts.amazonaws.com exp=86400 initEnv=AWS_STS_REGIONAL_ENDPOINTS AWS_DEFAULT_REGION AWS_REGION AWS_ROLE_ARN AWS_WEB_IDENTITY_TOKEN_FILE

t-pod300   sa=my-serviceaccount(86400 ) podAnn=300   → 600
t-sa300    sa=sa-300  (300   ) podAnn=-     → 600
t-sa200k   sa=sa-200000(200000) podAnn=-     → 200000
t-sanone   sa=sa-none (      ) podAnn=-     → 86400
t-sanone2  sa=sa-none (      ) podAnn=-     → 86400

# the token payload in t-aud, t-pod600 and t-pod1y, decoded
t-aud token: aud= ['my-audience'] exp-iat= 86400
t-pod600: exp-iat= 600
t-pod1y: exp-iat= 31536000
```

| Annotation | On | README says | Lab result |
|---|---|---|---|
| `eks.amazonaws.com/role-arn` | SA | the role | → `AWS_ROLE_ARN`. Without it nothing is injected. |
| `eks.amazonaws.com/audience` | SA | defaults to `sts.amazonaws.com` | → the volume's `audience` and the token's `aud` (`my-audience` above). The IAM OIDC provider's `ClientIDList` must then contain it (§2.3) |
| `eks.amazonaws.com/sts-regional-endpoints` | SA | `"true"` adds `AWS_STS_REGIONAL_ENDPOINTS` | `AWS_STS_REGIONAL_ENDPOINTS=regional`. `aud-sa` doesn't have it, so `t-aud` didn't get the variable |
| `eks.amazonaws.com/token-expiration` | SA | defaults to 86400 | the volume's `expirationSeconds`. **Below 600 → 600. No upper limit** in the webhook: 200000 stayed 200000. Not a number → ignored, default used |
| `eks.amazonaws.com/token-expiration` | Pod | overrides the SA's | yes: 7200 beat the SA's 86400. Same rules: 300 → 600, `abc` ignored, one year passed through |
| `eks.amazonaws.com/skip-containers` | Pod | comma-separated names to skip | yes (§4.2). Init containers are mutated otherwise: `init` got the env in every test Pod |

The code agrees **(code, v0.6.19)**:

```go
// pkg/constants.go
	// 24hrs as that is max for EKS
	MaxTokenExpiration = int64(86400)
	// Default token expiration in seconds if none is defined, 22hrs
	DefaultTokenExpiration = int64(86400)
	// 10mins is min for kube-apiserver
	MinTokenExpiration = int64(600)

// pkg/handler/handler.go
// audience:        serviceaccount annotation > flag
// regionalSTS:     serviceaccount annotation > flag
// tokenExpiration: pod annotation > serviceaccount annotation > flag
...
		if expiration, err := strconv.ParseInt(expirationStr, 10, 64); err != nil {
			klog.V(4).Infof("Found invalid value for token expiration, using %d seconds as default: %v", serviceAccountTokenExpiration, err)
		} else {
			tokenExpiration = pkg.ValidateMinTokenExpiration(expiration)
		}
```

- **`MaxTokenExpiration` isn't enforced by the webhook.** The limit comes from kube-apiserver's
  `--service-account-max-token-expiration`. k3s doesn't set it, so the one-year token was issued.
  The comment says 24 h is EKS's maximum **(code comment; not tested on EKS)**.
- **The default comment says "22hrs" but the value is 86400 (24 h).** The 22–24 h random jitter
  (`addJitterToDefaultToken`, from `DefaultMinTokenExpiration = 79200`) is applied only in the EKS
  Pod Identity branch. IRSA Pods got exactly 86400 (`t-sanone`, `t-sanone2`).
- **Pods created before an annotation changes keep their old settings.** The webhook only runs on
  `CREATE`.

### 4.4 The token in the Pod (lab)

```console
$ kubectl exec my-pod -c container-name -- cat /var/run/secrets/eks.amazonaws.com/serviceaccount/token
```

Decoded:

```text
header : {"alg": "RS256", "kid": "5q7RSqr-V4vvoweOD0_qATLMWQ8upW61QL-ZiXDdwRc"}
```

```json
{
  "aud": ["sts.amazonaws.com"],
  "exp": 1795506991,
  "iat": 1795420591,
  "iss": "https://kubernetes.default.svc.cluster.local",
  "jti": "3b961c76-c830-443b-9833-598a5d440841",
  "kubernetes.io": {
    "namespace": "default",
    "node": {"name": "cal-server", "uid": "ed112203-beb3-4ba0-91eb-1d6c7d86e3fc"},
    "pod": {"name": "my-pod", "uid": "a9c9280b-81f3-4c08-a668-71c8b70020bc"},
    "serviceaccount": {"name": "my-serviceaccount", "uid": "17fd72f1-0f10-457d-9125-c67df12c9e78"}
  },
  "nbf": 1795420591,
  "sub": "system:serviceaccount:default:my-serviceaccount"
}
```

1112 characters, 256-byte RSA signature. Against §3:

| Claim | Value | Checked by STS against |
|---|---|---|
| `kid` (header) | `5q7RSqr-...` | the JWKS key with that `kid` (§2.2) |
| `iss` | `https://kubernetes.default.svc.cluster.local` | an IAM OIDC provider with this URL. On EKS: `https://oidc.eks.<region>.amazonaws.com/id/<ID>` |
| `aud` | `sts.amazonaws.com` | the provider's `ClientIDList`, and the trust policy's `:aud` |
| `sub` | `system:serviceaccount:default:my-serviceaccount` | the trust policy's `:sub` |
| `exp` | `iat` + 86400 | the current time |

---

## 5. Steps 5–7: the kubelet mints the token

Not repeated here. [workload-identity.md](workload-identity.md), "Step 2 in detail", has the
kubelet's TokenRequest (with `boundObjectRef`), the Node authorizer refusing tokens for Pods on
other nodes, the tmpfs and `..data` layout, and a token refresh watched at 88% of its lifetime.

---

## 6. Step 8: the SDK calls STS

### 6.1 From inside the Pod (lab)

A Pod with the same ServiceAccount, the AWS CLI, and two settings that point it at the emulator:

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: sdk-pod
  namespace: default
spec:
  serviceAccountName: my-serviceaccount
  containers:
  - name: emulator-relay          # 127.0.0.1:9297 in the Pod -> the emulator, so its TLS cert (localhost/127.0.0.1) matches
    image: alpine/socat:1.8.0.3
    args: ["TCP-LISTEN:9297,fork,reuseaddr,bind=127.0.0.1", "TCP:172.21.0.5:9297"]
  - name: app
    image: amazon/aws-cli:2.31.0
    command: ["sleep", "3600"]
    env:
    - name: AWS_ENDPOINT_URL      # send every AWS API call to the emulator instead of *.amazonaws.com
      value: https://127.0.0.1:9297
    - name: AWS_CA_BUNDLE         # trust the emulator's self-signed CA
      value: /etc/emulator/ca.pem
    volumeMounts:
    - name: emulator-ca
      mountPath: /etc/emulator
  volumes:
  - name: emulator-ca
    configMap:
      name: emulator-ca           # kubectl create configmap emulator-ca --from-file=ca.pem=<the emulator's ca.pem>
```

The emulator's container was attached to the lab's Docker network for this
(`docker network connect k3s-cal <emulator>`, address `172.21.0.5`) and detached afterwards. The
socat sidecar is needed because the emulator's certificate only names `127.0.0.1` and `localhost`.

The env the CLI sees, two from the Pod spec and five from the webhook:

```console
$ kubectl exec sdk-pod -c app -- sh -c 'env | grep ^AWS_ | sort'
AWS_CA_BUNDLE=/etc/emulator/ca.pem
AWS_DEFAULT_REGION=us-west-2
AWS_ENDPOINT_URL=https://127.0.0.1:9297
AWS_REGION=us-west-2
AWS_ROLE_ARN=arn:aws:iam::000000000000:role/s3-reader
AWS_STS_REGIONAL_ENDPOINTS=regional
AWS_WEB_IDENTITY_TOKEN_FILE=/var/run/secrets/eks.amazonaws.com/serviceaccount/token
```

The webhook mutated the `emulator-relay` container too: every container that isn't in
`skip-containers` gets the variables.

Then any command that needs credentials, with `--debug`:

```console
$ kubectl exec sdk-pod -c app -- aws sts get-caller-identity --debug
```

The relevant lines, in order:

```text
botocore.credentials - DEBUG - Looking for credentials via: env
botocore.credentials - DEBUG - Looking for credentials via: assume-role
botocore.credentials - DEBUG - Looking for credentials via: assume-role-with-web-identity
...
botocore.endpoint - DEBUG - Making request for OperationModel(name=AssumeRoleWithWebIdentity) with params:
  {'url_path': '/', 'method': 'POST', ...
   'body': {'Action': 'AssumeRoleWithWebIdentity', 'Version': '2011-06-15',
            'RoleArn': 'arn:aws:iam::000000000000:role/s3-reader',
            'RoleSessionName': 'botocore-session-1795420851',
            'WebIdentityToken': 'eyJhbGciOiJSUzI1NiIsImtpZCI6IjVxN1JTcXItVjR2dm93ZU...'}}
botocore.hooks - DEBUG - Event choose-signer.sts.AssumeRoleWithWebIdentity: calling handler <function disable_signing ...>
botocore.endpoint - DEBUG - Sending http request: <AWSPreparedRequest stream_output=False, method=POST,
  url=https://127.0.0.1:9297/,
  headers={'Content-Type': b'application/x-www-form-urlencoded; charset=utf-8', 'Content-Length': '1287'}>
urllib3.connectionpool - DEBUG - https://127.0.0.1:9297 "POST / HTTP/1.1" 400 363
botocore.parsers - DEBUG - Response body:
b'<ErrorResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/"><Error><Type>Sender</Type>
  <Code>InvalidIdentityToken</Code><Message>Couldn\'t retrieve verification key from your identity
  provider, please reference AssumeRoleWithWebIdentity documentation for requirements</Message>
  </Error><RequestId>49fd0360-885a-45a1-87c4-42dfb510a019</RequestId></ErrorResponse>'
botocore.credentials - WARNING - Refreshing temporary credentials failed during mandatory refresh period.

An error occurred (InvalidIdentityToken) when calling the AssumeRoleWithWebIdentity operation:
Couldn't retrieve verification key from your identity provider, please reference
AssumeRoleWithWebIdentity documentation for requirements
command terminated with exit code 254
```

What this shows:

- **The credential chain found the webhook's variables.** `env` (`AWS_ACCESS_KEY_ID`) had nothing;
  `assume-role` (a profile with `role_arn`) had nothing; `assume-role-with-web-identity` saw
  `AWS_ROLE_ARN` + `AWS_WEB_IDENTITY_TOKEN_FILE`, read the file and called STS before running
  `GetCallerIdentity`.
- **The call is unsigned.** `disable_signing` is chosen for this operation, and the request has no
  `Authorization` or `X-Amz-*` headers. The token is the only credential: the Pod has no AWS keys
  before this call.
- **`RoleSessionName` is `botocore-session-<unix time>`**, chosen by the SDK. It shows up in the
  assumed-role ARN and in CloudTrail (§7).
- **The endpoint is the override.** Without `AWS_ENDPOINT_URL` it would be
  `https://sts.us-west-2.amazonaws.com`: regional, from `AWS_REGION` and
  `AWS_STS_REGIONAL_ENDPOINTS=regional` **(not tested)**.
- **It got as far as STS's key fetch** (step 5 of the checks in §9) and stopped there. §6.2 shows why.

### 6.2 The same token from the host, step by step (lab)

The same token, copied out of `my-pod`, sent by boto3 with the wire log. First with no IAM OIDC
provider for its issuer:

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
Content-Length: 1265

Action=AssumeRoleWithWebIdentity&Version=2011-06-15&RoleArn=arn%3Aaws%3Aiam%3A%3A000000000000%3Arole%2Fs3-reader&RoleSessionName=my-pod&WebIdentityToken=eyJhbGciOiJSUzI1NiIsImtpZCI6IjVxN1JTcXItVjR2dm93ZU9EMF9xQVRM%E2%80%A6
```

```text
# the same form fields, URL-decoded
Action = AssumeRoleWithWebIdentity
Version = 2011-06-15
RoleArn = arn:aws:iam::000000000000:role/s3-reader
RoleSessionName = my-pod
WebIdentityToken = eyJhbGciOiJSUzI1NiIsImtpZCI6IjVxN1JTcXItVjR2dm93ZU9EMF9xQVRM…
```

```http
HTTP/1.1 400 Bad Request
content-type: text/xml
x-amzn-requestid: 7b3cc95c-83e0-4f3c-9fee-ef9f779b0c4b
content-length: 322
date: Mon, 23 Nov 2026 08:12:57 GMT

<ErrorResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
  <Error>
    <Type>Sender</Type>
    <Code>InvalidIdentityToken</Code>
    <Message>No OpenIDConnect provider found in your account for https://kubernetes.default.svc.cluster.local</Message>
  </Error>
  <RequestId>7b3cc95c-83e0-4f3c-9fee-ef9f779b0c4b</RequestId>
</ErrorResponse>
```

Then with the k3s issuer registered as a provider (its ARN ends `oidc-provider/kubernetes.default.svc.cluster.local`):

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
X-Amz-Date: 20261123T081257Z
Authorization: AWS4-HMAC-SHA256 Credential=EMULATORAC167D604544/20261123/us-east-1/iam/aws4_request, SignedHeaders=content-type;host;x-amz-date, Signature=<64 hex>
Content-Length: 148

Action=CreateOpenIDConnectProvider&Version=2010-05-08&Url=https%3A%2F%2Fkubernetes.default.svc.cluster.local&ClientIDList.member.1=sts.amazonaws.com
```

```text
# the same form fields, URL-decoded
Action = CreateOpenIDConnectProvider
Version = 2010-05-08
Url = https://kubernetes.default.svc.cluster.local
ClientIDList.member.1 = sts.amazonaws.com
```

```http
HTTP/1.1 200 OK
content-type: text/xml
content-length: 421
date: Mon, 23 Nov 2026 08:12:57 GMT

<CreateOpenIDConnectProviderResponse xmlns="https://iam.amazonaws.com/doc/2010-05-08/">
  <CreateOpenIDConnectProviderResult>
    <OpenIDConnectProviderArn>arn:aws:iam::000000000000:oidc-provider/kubernetes.default.svc.cluster.local</OpenIDConnectProviderArn>
  </CreateOpenIDConnectProviderResult>
  <ResponseMetadata>
    <RequestId>d8498d2b-9476-4190-8cf1-109bcbb28e93</RequestId>
  </ResponseMetadata>
</CreateOpenIDConnectProviderResponse>
```

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
Content-Length: 1265

Action=AssumeRoleWithWebIdentity&Version=2011-06-15&RoleArn=arn%3Aaws%3Aiam%3A%3A000000000000%3Arole%2Fs3-reader&RoleSessionName=my-pod&WebIdentityToken=eyJhbGciOiJSUzI1NiIsImtpZCI6IjVxN1JTcXItVjR2dm93ZU9EMF9xQVRM%E2%80%A6
```

```text
# the same form fields, URL-decoded
Action = AssumeRoleWithWebIdentity
Version = 2011-06-15
RoleArn = arn:aws:iam::000000000000:role/s3-reader
RoleSessionName = my-pod
WebIdentityToken = eyJhbGciOiJSUzI1NiIsImtpZCI6IjVxN1JTcXItVjR2dm93ZU9EMF9xQVRM…
```

```http
HTTP/1.1 400 Bad Request
content-type: text/xml
x-amzn-requestid: cb04e43c-8d90-402b-91aa-5c2ced17458d
content-length: 363
date: Mon, 23 Nov 2026 08:12:57 GMT

<ErrorResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
  <Error>
    <Type>Sender</Type>
    <Code>InvalidIdentityToken</Code>
    <Message>Couldn't retrieve verification key from your identity provider, please reference AssumeRoleWithWebIdentity documentation for requirements</Message>
  </Error>
  <RequestId>cb04e43c-8d90-402b-91aa-5c2ced17458d</RequestId>
</ErrorResponse>
```

- **No `Authorization` header** on either `AssumeRoleWithWebIdentity`, and the emulator, which
  requires SigV4 on everything else, accepted that. The IAM call between them is signed.
- **The token is checked before the role.** The first error is about the issuer, not about
  `s3-reader`'s trust policy.
- **The second error is the key fetch.** The provider exists, so STS goes to
  `<iss>/.well-known/openid-configuration` for the keys, and fails.

### 6.3 Why the emulator can't go further (lab)

The emulator never makes outbound requests, so it can't fetch any issuer's keys. To rule out
"k3s's issuer is unreachable" as the cause, a token naming a real public issuer
(`https://token.actions.githubusercontent.com`, which does serve a JWKS) with a dummy signature got
the same error, after registering that issuer as a provider:

```text
assume: An error occurred (InvalidIdentityToken) when calling the AssumeRoleWithWebIdentity
operation: Couldn't retrieve verification key from your identity provider, please reference
AssumeRoleWithWebIdentity documentation for requirements
```

If it had fetched the keys, the error would have been the signature check's ("could not be
validated", §9). The emulated EKS cluster's own issuer behaves the same: the emulator doesn't
hold keys for it, and it can't mint tokens for it. So no Kubernetes token can get past this
point in the emulator.

On real AWS, two changes get past it: an EKS cluster (AWS serves its issuer), or for a
self-managed cluster a public issuer URL serving these two documents (§2.2).

---

## 7. Steps 9–11 with an issuer the emulator hosts (lab, Cognito stand-in)

The rest of STS (trust policy evaluation, the credentials, using them) is the same whatever the
issuer. To run it, the token has to come from an issuer the emulator trusts without a network
fetch: **Amazon Cognito Identity**, which it implements. A developer-authenticated identity pool
issues OpenID tokens for any user ID you give it. Two users give two tokens with different `sub`s,
standing in for two ServiceAccounts.

| IRSA | Cognito stand-in |
|---|---|
| issuer `https://oidc.eks.<region>.amazonaws.com/id/<ID>` | `https://cognito-identity.amazonaws.com` |
| `Principal.Federated`: the OIDC provider's ARN | `cognito-identity.amazonaws.com` (built in, no provider to create) |
| `aud`: `sts.amazonaws.com` | the identity pool ID |
| `sub`: `system:serviceaccount:<ns>:<sa>` | the identity ID, `us-east-1:<uuid>` |
| condition keys `<issuer>:aud`, `<issuer>:sub` | `cognito-identity.amazonaws.com:aud`, `:sub` |

### 7.1 Two tokens (lab)

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
X-Amz-Target: AWSCognitoIdentityService.GetOpenIdTokenForDeveloperIdentity
Content-Type: application/x-amz-json-1.1
X-Amz-Date: 20261123T081257Z
Authorization: AWS4-HMAC-SHA256 Credential=EMULATORAC167D604544/20261123/us-east-1/cognito-identity/aws4_request, SignedHeaders=content-type;host;x-amz-date;x-amz-target, Signature=<64 hex>
Content-Length: 102

{"IdentityPoolId": "us-east-1:9bce8a89-c083-4790-be70-b5a321705b0a", "Logins": {"login.lab": "app-a"}}
```

```http
HTTP/1.1 200 OK
content-type: application/x-amz-json-1.1
x-amzn-requestid: 9b61f360-d831-440c-9243-2eaf3d0dc853
content-length: 519
date: Mon, 23 Nov 2026 08:12:57 GMT

{
  "IdentityId": "us-east-1:f0f19866-7b6a-435b-88ec-1f261614b336",
  "Token": "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJpc3MiOiJodHRwczovL2NvZ25pdG8taWRlbnRpdHkuYW1hem9uYXdzLmNvbSIsImF1ZCI6InVzLWVhc3QtMTo5YmNlOGE4OS1jMDgzLTQ3OTAtYmU3MC1iNWEzMjE3MDViMGEiLCJzdWIiOiJ1cy1lYXN0LTE6ZjBmMTk4NjYtN2I2YS00MzViLTg4ZWMtMWYyNjE2MTRiMzM2IiwiYW1yIjpbImF1dGhlbnRpY2F0ZWQiLCJjb2duaXRvLWlkZW50aXR5LmFtYXpvbmF3cy5jb20iLCJsb2dpbi5sYWIiXSwiaWF0IjoxNzk1NDIxNTc3LCJleHAiOjE3OTU0MjI0NzcsImp0aSI6IjMyOWZhY2NmLTUyMDYtNDEzZC05YThhLWU3OTdhM2I1MTA4MyJ9."
}
```

```text
token A header : {"alg": "none", "typ": "JWT"}
token A payload: {"iss": "https://cognito-identity.amazonaws.com", "aud": "us-east-1:9bce8a89-c083-4790-be70-b5a321705b0a", "sub": "us-east-1:f0f19866-7b6a-435b-88ec-1f261614b336", "amr": ["authenticated", "cognito-identity.amazonaws.com", "login.lab"], "iat": 1795421577, "exp": 1795422477, "jti": "329faccf-5206-413d-9a8a-e797a3b51083"}

token B header : {"alg": "none", "typ": "JWT"}
token B payload: {"iss": "https://cognito-identity.amazonaws.com", "aud": "us-east-1:9bce8a89-c083-4790-be70-b5a321705b0a", "sub": "us-east-1:730e3d2b-4621-414c-b6e6-198ad1d713aa", "amr": ["authenticated", "cognito-identity.amazonaws.com", "login.lab"], "iat": 1795421577, "exp": 1795422477, "jti": "f079d7ba-aa1d-43cf-8dba-beaf3ab34adf"}
```

- **`alg: none`, no signature.** The emulator's STS accepts this only because it issued the token
  itself. The same unsigned shape with the k3s issuer was refused: "We don't accept unsigned JWTs"
  (§9). Real Cognito signs its tokens **(docs)**.
- Lifetime 900 s (`exp - iat`). IRSA's is 86400.

### 7.2 The role (lab)

Same shape as §3.2: a federated principal, `sts:AssumeRoleWithWebIdentity`, `StringEquals` on `aud`
and `sub`. `sub` is pinned to token A's subject.

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
X-Amz-Date: 20261123T081257Z
Authorization: AWS4-HMAC-SHA256 Credential=EMULATORAC167D604544/20261123/us-east-1/iam/aws4_request, SignedHeaders=content-type;host;x-amz-date, Signature=<64 hex>
Content-Length: 732

Action=CreateRole&Version=2010-05-08&RoleName=s3-reader-cognito&AssumeRolePolicyDocument=%7B%0A%20%20%22Version%22%3A%20%222012-10-17%22%2C%0A%20%20%22Statement%22%3A%20%5B%0A%20%20%20%20%7B%0A%20%20%20%20%20%20%22Effect%22%3A%20%22Allow%22%2C%0A%20%20%20%20%20%20%22Principal%22%3A%20%7B%0A%20%20%20%20%20%20%20%20%22Federated%22%3A%20%22cognito-identity.amazonaws.com%22%0A%20%20%20%20%20%20%7D%2C%0A%20%20%20%20%20%20%22Action%22%3A%20%22sts%3AAssumeRoleWithWebIdentity%22%2C%0A%20%20%20%20%20%20%22Condition%22%3A%20%7B%0A%20%20%20%20%20%20%20%20%22StringEquals%22%3A%20%7B%0A%20%20%20%20%20%20%20%20%20%20%22cognito-identity.amazonaws.com%3Aaud%22%3A%20%22us-east-1%3A9bce8a89-c083-4790-be70-b5a321705b0a%22%2C%0A%20%20%20%20%20%20%20%20%20%20%22cognito-identity.amazonaws.com%3Asub%22%3A%20%22us-east-1%3Af0f19866-7b6a-435b-88ec-1f261614b336%22%0A%20%20%20%20%20%20%20%20%7D%0A%20%20%20%20%20%20%7D%0A%20%20%20%20%7D%0A%20%20%5D%0A%7D
```

```text
# the same form fields, URL-decoded
Action = CreateRole
Version = 2010-05-08
RoleName = s3-reader-cognito
AssumeRolePolicyDocument =
  {
    "Version": "2012-10-17",
    "Statement": [
      {
        "Effect": "Allow",
        "Principal": {
          "Federated": "cognito-identity.amazonaws.com"
        },
        "Action": "sts:AssumeRoleWithWebIdentity",
        "Condition": {
          "StringEquals": {
            "cognito-identity.amazonaws.com:aud": "us-east-1:9bce8a89-c083-4790-be70-b5a321705b0a",
            "cognito-identity.amazonaws.com:sub": "us-east-1:f0f19866-7b6a-435b-88ec-1f261614b336"
          }
        }
      }
    ]
  }
```

```http
HTTP/1.1 200 OK
content-type: text/xml
content-length: 1071
date: Mon, 23 Nov 2026 08:12:57 GMT

<CreateRoleResponse xmlns="https://iam.amazonaws.com/doc/2010-05-08/">
  <CreateRoleResult>
    <Role xmlns="https://iam.amazonaws.com/doc/2010-05-08/">
      <Path>/</Path>
      <RoleName>s3-reader-cognito</RoleName>
      <RoleId>AROA2OTKVHA5U7NHMUDEB</RoleId>
      <Arn>arn:aws:iam::000000000000:role/s3-reader-cognito</Arn>
      <CreateDate>2026-11-23T08:12:57.757Z</CreateDate>
      <AssumeRolePolicyDocument>%7B%22Version%22%3A%222012-10-17%22%2C%22Statement%22%3A%5B%7B%22Effect%22%3A%22Allow%22%2C%22Principal%22%3A%7B%22Federated%22%3A%22cognito-identity.amazonaws.com%22%7D%2C%22Action%22%3A%22sts%3AAssumeRoleWithWebIdentity%22%2C%22Condition%22%3A%7B%22StringEquals%22%3A%7B%22cognito-identity.amazonaws.com%3Aaud%22%3A%22us-east-1%3A9bce8a89-c083-4790-be70-b5a321705b0a%22%2C%22cognito-identity.amazonaws.com%3Asub%22%3A%22us-east-1%3Af0f19866-7b6a-435b-88ec-1f261614b336%22%7D%7D%7D%5D%7D</AssumeRolePolicyDocument>
      <MaxSessionDuration>3600</MaxSessionDuration>
    </Role>
  </CreateRoleResult>
  <ResponseMetadata>
    <RequestId>719cb88e-c969-4b62-9bf6-fc8cf122d88e</RequestId>
  </ResponseMetadata>
</CreateRoleResponse>
```

`AmazonS3ReadOnlyAccess` was attached as in §3.3 (not shown).

### 7.3 AssumeRoleWithWebIdentity succeeds (lab)

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
Content-Length: 606

Action=AssumeRoleWithWebIdentity&Version=2011-06-15&RoleArn=arn%3Aaws%3Aiam%3A%3A000000000000%3Arole%2Fs3-reader-cognito&RoleSessionName=my-pod&WebIdentityToken=eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJpc3MiOiJodHRwczovL2Nv%E2%80%A6
```

```text
# the same form fields, URL-decoded
Action = AssumeRoleWithWebIdentity
Version = 2011-06-15
RoleArn = arn:aws:iam::000000000000:role/s3-reader-cognito
RoleSessionName = my-pod
WebIdentityToken = eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJpc3MiOiJodHRwczovL2Nv…
```

```http
HTTP/1.1 200 OK
content-type: text/xml
content-length: 1799
date: Mon, 23 Nov 2026 08:12:57 GMT

<AssumeRoleWithWebIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
  <AssumeRoleWithWebIdentityResult>
    <Credentials xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
      <AccessKeyId>EMULATORASTMZY22CZHQ</AccessKeyId>
      <SecretAccessKey>EfuuwJ…</SecretAccessKey>
      <SessionToken>EjQTkE/xyfBk9U6sAeD+KIj+7x/4V2EhiVnrss4B…</SessionToken>
      <Expiration>2026-11-23T09:12:57Z</Expiration>
    </Credentials>
    <SubjectFromWebIdentityToken>us-east-1:f0f19866-7b6a-435b-88ec-1f261614b336</SubjectFromWebIdentityToken>
    <AssumedRoleUser>
      <AssumedRoleId>AROA2OTKVHA5U7NHMUDEB:my-pod</AssumedRoleId>
      <Arn>arn:aws:sts::000000000000:assumed-role/s3-reader-cognito/my-pod</Arn>
    </AssumedRoleUser>
    <PackedPolicySize>0</PackedPolicySize>
    <Provider>https://cognito-identity.amazonaws.com</Provider>
    <Audience>us-east-1:9bce8a89-c083-4790-be70-b5a321705b0a</Audience>
    <SessionTokenUtilization>17</SessionTokenUtilization>
    <SessionTokenSize>712</SessionTokenSize>
  </AssumeRoleWithWebIdentityResult>
  <ResponseMetadata>
    <RequestId>3d2be93e-be66-454c-8eca-9d643085d265</RequestId>
  </ResponseMetadata>
</AssumeRoleWithWebIdentityResponse>
```

| Response field | Meaning |
|---|---|
| `Credentials.AccessKeyId` | a temporary key ID. On AWS these start with `ASIA`; long-term user keys start with `AKIA` **(docs)** |
| `Credentials.SecretAccessKey` | signs requests; never sent on the wire afterwards |
| `Credentials.SessionToken` | sent as `X-Amz-Security-Token` with every request |
| `Credentials.Expiration` | one hour after the call: the role's `MaxSessionDuration` (3600) |
| `SubjectFromWebIdentityToken` | the token's `sub`. For IRSA: `system:serviceaccount:default:my-serviceaccount` |
| `AssumedRoleUser.Arn` | `arn:aws:sts::<account>:assumed-role/<role>/<RoleSessionName>`: the identity S3 and CloudTrail see |
| `Provider`, `Audience` | the token's `iss` and `aud` |
| `PackedPolicySize` | 0: no session policy was passed |

The SDK caches these credentials and calls STS again before `Expiration`, reading the token file
again each time ([workload-identity.md](workload-identity.md), Step 3 in the SDKs).

### 7.4 Using the credentials (lab)

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
X-Amz-Date: 20261123T081257Z
X-Amz-Security-Token: EjQTkE/xyfBk9U6sAeD+KIj+7x/4V2EhiVnrss4B…
Authorization: AWS4-HMAC-SHA256 Credential=EMULATORASTMZY22CZHQ/20261123/us-east-1/sts/aws4_request, SignedHeaders=content-type;host;x-amz-date;x-amz-security-token, Signature=<64 hex>
Content-Length: 43

Action=GetCallerIdentity&Version=2011-06-15
```

```http
HTTP/1.1 200 OK
content-type: text/xml
content-length: 402
date: Mon, 23 Nov 2026 08:12:57 GMT

<GetCallerIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
  <GetCallerIdentityResult>
    <UserId>AROA2OTKVHA5U7NHMUDEB:my-pod</UserId>
    <Account>000000000000</Account>
    <Arn>arn:aws:sts::000000000000:assumed-role/s3-reader-cognito/my-pod</Arn>
  </GetCallerIdentityResult>
  <ResponseMetadata>
    <RequestId>da445b85-ade5-4c32-bfe0-5070bdfa2d1d</RequestId>
  </ResponseMetadata>
</GetCallerIdentityResponse>
```

```http
GET /irsa-demo-bucket/hello.txt HTTP/1.1
Host: 127.0.0.1:9297
x-amz-checksum-mode: ENABLED
X-Amz-Date: 20261123T081257Z
X-Amz-Security-Token: EjQTkE/xyfBk9U6sAeD+KIj+7x/4V2EhiVnrss4B…
X-Amz-Content-SHA256: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
Authorization: AWS4-HMAC-SHA256 Credential=EMULATORASTMZY22CZHQ/20261123/us-east-1/s3/aws4_request, SignedHeaders=host;x-amz-checksum-mode;x-amz-content-sha256;x-amz-date;x-amz-security-token, Signature=<64 hex>
```

```http
HTTP/1.1 200 OK
content-type: binary/octet-stream
accept-ranges: bytes
last-modified: Mon, 23 Nov 2026 08:12:57 GMT
content-length: 24
etag: "2c174d9f1b9d48ba73d55b1406ad50b6"
x-amz-checksum-crc32: duHXvg==
x-amz-checksum-type: FULL_OBJECT
x-amz-server-side-encryption: AES256
x-amz-request-id: 2DCC6230136B20C5
x-amz-id-2: 2dcc6230136b20c5bf0c8e83381694440c40f2ac9a490f9cfe3bf894a9e393dd
date: Mon, 23 Nov 2026 08:12:57 GMT
```

```text
body: b'hello from the emulator\n'
```

- **Every request is signed with the temporary key** (`Credential=EMULATORAS.../...`) and carries
  `X-Amz-Security-Token`, which is part of `SignedHeaders`.
- **`GetCallerIdentity` returns the assumed role** with the session name:
  `assumed-role/s3-reader-cognito/my-pod`. In a Pod it would be `botocore-session-<time>` or
  whatever name the SDK picked.

The permissions policy is checked on every call, separately from the trust policy:

```http
PUT /irsa-demo-bucket/nope.txt HTTP/1.1
Host: 127.0.0.1:9297
Transfer-Encoding: chunked
Content-Encoding: aws-chunked
X-Amz-Trailer: x-amz-checksum-crc32
X-Amz-Decoded-Content-Length: 1
x-amz-sdk-checksum-algorithm: CRC32
X-Amz-Date: 20261123T081258Z
X-Amz-Security-Token: EjQTkE/xyfBk9U6sAeD+KIj+7x/4V2EhiVnrss4B…
X-Amz-Content-SHA256: STREAMING-UNSIGNED-PAYLOAD-TRAILER
Authorization: AWS4-HMAC-SHA256 Credential=EMULATORASTMZY22CZHQ/20261123/us-east-1/s3/aws4_request, SignedHeaders=content-encoding;host;x-amz-content-sha256;x-amz-date;x-amz-decoded-content-length;x-amz-sdk-checksum-algorithm;x-amz-security-token;x-amz-trailer, Signature=<64 hex>

<aws-chunked stream: the object's bytes, then a trailer x-amz-checksum-crc32:…>
```

```http
HTTP/1.1 403 Forbidden
content-type: application/xml
x-amz-request-id: B2198C9EACAACCD1
x-amz-id-2: b2198c9eacaaccd1c20d644f84d1bc18a02644236027577f123e2a95326091fa
content-length: 419
date: Mon, 23 Nov 2026 08:12:58 GMT

<Error>
  <Code>AccessDenied</Code>
  <Message>User: arn:aws:sts::000000000000:assumed-role/s3-reader-cognito/my-pod is not authorized to perform: s3:PutObject on resource: &quot;arn:aws:s3:::irsa-demo-bucket/nope.txt&quot; because no identity-based policy allows the s3:PutObject action</Message>
  <RequestId>B2198C9EACAACCD1</RequestId>
  <HostId>b2198c9eacaaccd1c20d644f84d1bc18a02644236027577f123e2a95326091fa</HostId>
</Error>
```

The trust policy let this identity *become* the role; `AmazonS3ReadOnlyAccess` doesn't include
`s3:PutObject`, so the write is refused with the assumed-role ARN in the message.

### 7.5 The other subject is refused (lab)

Token B, same pool, different `sub`:

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
Content-Length: 606

Action=AssumeRoleWithWebIdentity&Version=2011-06-15&RoleArn=arn%3Aaws%3Aiam%3A%3A000000000000%3Arole%2Fs3-reader-cognito&RoleSessionName=my-pod&WebIdentityToken=eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJpc3MiOiJodHRwczovL2Nv%E2%80%A6
```

```text
# the same form fields, URL-decoded
Action = AssumeRoleWithWebIdentity
Version = 2011-06-15
RoleArn = arn:aws:iam::000000000000:role/s3-reader-cognito
RoleSessionName = my-pod
WebIdentityToken = eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJpc3MiOiJodHRwczovL2Nv…
```

```http
HTTP/1.1 403 Forbidden
content-type: text/xml
x-amzn-requestid: 5c55664a-c33e-4e9d-ad00-6f1bab744ae6
content-length: 273
date: Mon, 23 Nov 2026 08:12:58 GMT

<ErrorResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
  <Error>
    <Type>Sender</Type>
    <Code>AccessDenied</Code>
    <Message>Not authorized to perform sts:AssumeRoleWithWebIdentity</Message>
  </Error>
  <RequestId>5c55664a-c33e-4e9d-ad00-6f1bab744ae6</RequestId>
</ErrorResponse>
```

HTTP 403 `AccessDenied`, where the token errors were HTTP 400 `InvalidIdentityToken`. The token was
valid; the trust policy didn't match. This is the error an IRSA Pod gets when its ServiceAccount
or namespace isn't the one in the trust policy's `:sub`.

---

## 8. Trust-policy conditions, tested (lab)

One role per variant, each tried with token A and token B. `aud` is always required to equal the
pool ID unless stated:

```text
StringEquals sub=A                               | A: allowed | B: AccessDenied
StringLike sub=us-east-1:*                       | A: allowed | B: allowed
README shape: StringEquals A + StringLike *      | A: allowed | B: AccessDenied
StringEquals sub=[A, B] (list = OR)              | A: allowed | B: allowed
aud = wrong pool                                 | A: AccessDenied | B: AccessDenied
no Condition at all                              | A: allowed | B: allowed
```

| Variant | IRSA equivalent | Result |
|---|---|---|
| `StringEquals` `sub` = A | one ServiceAccount | only A |
| `StringLike` `sub` = `us-east-1:*` | `system:serviceaccount:<ns>:*`, a whole namespace | both |
| **the README's shape**: `StringEquals` A **and** `StringLike` `*` | the README's policy | **only A**: the operators are ANDed, so the wildcard adds nothing |
| `StringEquals` `sub` = `[A, B]` | a list of ServiceAccounts | both: values in a list are ORed |
| `aud` = another pool | a wrong audience | neither |
| no `Condition` | any ServiceAccount of any namespace of the cluster | both |

The last row is the dangerous one. With no `sub` condition, **every ServiceAccount in the cluster**
can assume the role. The webhook isn't even needed: any Pod can mount a projected token with
`audience: sts.amazonaws.com` for its own ServiceAccount and send it to STS with this role's ARN. So
anyone who can create a Pod anywhere in the cluster gets the role **(not tested on AWS; follows
from §6 and §8)**.

---

## 9. What STS returns for each kind of bad token (lab)

The same `s3-reader` role, the k3s issuer registered, and the real token modified one way at a
time:

```text
not a JWT                            | InvalidIdentityToken: The ID Token provided is not a valid JWT. (You may see this error if you sent an Access Token)
expired (exp in the past)            | ExpiredTokenException: Token expired: current date/time 1795421578 must be before the expiration date/time 1795421518
unsigned (alg none, no signature)    | InvalidIdentityToken: We don't accept unsigned JWTs
iss with no IAM OIDC provider        | InvalidIdentityToken: No OpenIDConnect provider found in your account for https://example.invalid
the real k3s token                   | InvalidIdentityToken: Couldn't retrieve verification key from your identity provider, please reference AssumeRoleWithWebIdentity documentation for requirements
```

| Check (in order) | Error | Fix |
|---|---|---|
| 1. a JWT with `exp` and `iss` | 400 `InvalidIdentityToken` "not a valid JWT" | send the ID token, not an access token |
| 2. `exp` in the future (checked **before** the signature) | 400 `ExpiredTokenException` | the SDK rereads the file; the kubelet refreshes it |
| 3. signed | 400 `InvalidIdentityToken` "We don't accept unsigned JWTs" | |
| 4. an IAM OIDC provider for `iss` | 400 `InvalidIdentityToken` "No OpenIDConnect provider found in your account for `<iss>`" | §2.3, in the role's account |
| 5. the issuer's keys can be fetched | 400 `InvalidIdentityToken` "Couldn't retrieve verification key..." | a public issuer (§2.2) |
| 6. the signature verifies against them | 400 `InvalidIdentityToken` "could not be validated" (not reachable in the emulator, §6.3) | the issuer's JWKS must have the signing key |
| 7. the trust policy admits `aud` and `sub` | 403 `AccessDenied` "Not authorized to perform sts:AssumeRoleWithWebIdentity" | §3, §8 |

Rows 1–5 and 7 are lab results. The order and the message for row 6 are from the emulator's model,
as described in [workload-identity.md](workload-identity.md) §3, "What STS checks".

---

## 10. What the README leaves out or gets wrong

| README | Actually (v0.6.19, lab unless marked) |
|---|---|
| volume `aws-token` | `aws-iam-token`, mounted `readOnly: true` |
| region variables appear | only with the webhook flag `--aws-default-region` (EKS sets it) |
| `__doc_comment` keys in the trust policy | not valid policy; remove them |
| `StringEquals` and `StringLike` both marked "(optional)" | both present = AND; use one |
| trust policy without `:aud` | add `"<issuer>:aud": "sts.amazonaws.com"` **(docs)** |
| `token-expiration` defaults to 86400 | yes; values below 600 become 600, there's no maximum in the webhook, and non-numbers are ignored |
| "All new pods launched using this Service Account will be modified" | only Pods created after the annotation, and not containers in `skip-containers`; init containers are included |
| (not mentioned) | the webhook's `failurePolicy: Ignore`: if it's down, Pods start without the token and the SDK falls through to the next credential provider |
| (not mentioned) | `AssumeRoleWithWebIdentity` is unsigned; the token is the only credential |
| (not mentioned) | the issuer's discovery document and JWKS must be reachable by STS |

## Related notes

- [workload-identity.md](workload-identity.md): the token files, IRSA vs EKS Pod Identity vs Azure, the SDK code
- [rbac-and-identity.md](rbac-and-identity.md): ServiceAccount tokens, OIDC discovery and the issuer
- [cloud-kubernetes.md](cloud-kubernetes.md): the IRSA deep dive
