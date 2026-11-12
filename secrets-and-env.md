# Secrets, environment variables and the cloud

See also: [`rbac-and-identity.md`](rbac-and-identity.md) (RBAC, OIDC, IRSA) · [`cloud-kubernetes.md`](cloud-kubernetes.md) · [`azure.md`](azure.md) · [`helm.md`](helm.md)

## 0. The short version

There are three separate problems, and most confusion comes from mixing them up:

| Problem | Question | Answered by |
|---|---|---|
| **Storage** | Where does the value live, and who can read it there? | a Kubernetes Secret, AWS Secrets Manager, Azure Key Vault, Scaleway Secret Manager, Vault |
| **Identity** | How does a workload prove who it is, so the store will answer? | IRSA, EKS Pod Identity, Entra Workload ID, an API key, Vault Kubernetes auth |
| **Delivery** | How does the value reach the process? | env var, file, or an SDK call at runtime |

A cloud secret store solves storage. It also creates a new question: the Pod needs a credential to open the store, so where does *that* live? This is the **secret zero** problem, and it's where the clouds differ most:

```
 AWS, Azure     Pod has a short-lived Kubernetes token ─► cloud trusts the cluster ─► temporary cloud credential
                No long-lived password exists anywhere.

 Scaleway       Long-lived API key in a Kubernetes Secret ─► Secret Manager
                The store is managed, but secret zero is a static key you must rotate.

 Hetzner        No secret store, no KMS. You bring your own, or use someone else's.
```

## 1. Config, secrets and environment variables

**Config** is a value that's safe to show: a log level, a feature flag, a hostname. **A secret** is a value that gives access to something: a password, an API key, a private key. The test: *would you be fine with this on a public wiki?*

These are different things that people often merge:

| Term | What it is |
|---|---|
| Environment variable | a way to **deliver** a value to a process |
| Kubernetes `ConfigMap` | a place to **store** non-sensitive values |
| Kubernetes `Secret` | a place to **store** sensitive values |
| `.env` file | a file that a tool loads into env vars; nothing more |

"Env var" says nothing about safety. A password in an env var is a secret delivered in a leaky way.

### What an environment variable really is

An env var is a string copied into a process's memory when it starts. The kernel hands the list to the new process at `exec`, and after that nothing outside can change it. The process can only change its own copy, and its children inherit a copy.

That explains the main behaviours:

- **It can't be updated from outside.** Rotating a password can't reach a running process through an env var.
- **It leaks by inheritance.** Every child process gets it, including that shell you spawned and that crash reporter.
- **It's visible from outside.** It's readable in `/proc/<pid>/environ`, and the container runtime stores it too (§3).
- **It leaks by accident.** Frameworks dump env vars in debug pages and crash reports. That's the most common real-world leak

### Don't put secrets in images

Anything in a Dockerfile `ENV`, `ARG` or `COPY .env` ends up in image layers. Anyone who can pull the image can read it with `docker history` or by unpacking the layers. Deleting the file in a later layer doesn't remove it from the earlier one. Images get pushed to registries, cached on nodes and copied around, and you can't rotate a value out of them. Deliver secrets at run time, never at build time.

## 2. The Kubernetes Secret

A Secret is an API object much like a ConfigMap, with a few extra protections around handling. Its values are **base64 encoded, which isn't encryption**.

**(lab)**

```
$ kubectl -n secrets-demo create secret generic demo-db \
    --from-literal=username=app --from-literal=password=fake-pass-v1

$ kubectl -n secrets-demo get secret demo-db -o yaml
apiVersion: v1
data:
  password: ZmFrZS1wYXNzLXYx
  username: YXBw
kind: Secret
metadata:
  name: demo-db
  namespace: secrets-demo
type: Opaque

$ kubectl -n secrets-demo get secret demo-db -o jsonpath='{.data.password}' | base64 -d
fake-pass-v1
```

Base64 exists so that binary values fit in JSON and YAML. Anyone can reverse it, with no key.

### One Secret, several key/value pairs

A Secret is a **named, namespaced object that holds a map of key/value pairs**. `demo-db` above is one object (`secrets-demo/demo-db`) with two keys, `username` and `password`. Each key is independent: a Pod can use one key, some, or all.

```text
Secret "demo-db"  (namespace secrets-demo)
├── username  →  app
└── password  →  fake-pass-v1
```

- **Key names** may contain letters, digits, `-`, `_` and `.` **(docs)**. Keys like `tls.crt` or `config.json` are fine.
- **Values** are arbitrary bytes (base64 in `data`), so a key can hold a whole file: a certificate, a JSON config, a keystore.
- **The 1 MiB limit is for the whole object**, all keys together, not per key (see "Limits and failure modes" below).
- Group keys that belong together and change together: one Secret per database, per API client, per certificate.

#### Creating one with several keys

All of these create a single Secret with several keys **(docs, not tested here)**:

```sh
# from literals: one --from-literal per key
kubectl create secret generic db-creds \
  --from-literal=username=app \
  --from-literal=password='s3cr3t' \
  --from-literal=host=db.internal

# from files: the file name becomes the key (or set it with key=path)
kubectl create secret generic app-tls \
  --from-file=tls.crt=./server.crt \
  --from-file=tls.key=./server.key \
  --from-file=./ca.crt                      # key "ca.crt"

# from an env file: each KEY=value line becomes a key
cat > db.env <<'EOF'
DB_USER=app
DB_PASSWORD=s3cr3t
DB_HOST=db.internal
EOF
kubectl create secret generic db-env --from-env-file=db.env
```

The same thing as YAML, using `stringData` so the values are readable (the API server converts them to base64 `data`; see "The Secret model" at the end of this section):

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: db-creds
  namespace: secrets-demo
type: Opaque
stringData:
  username: app
  password: s3cr3t
  host: db.internal
  config.json: |                 # a whole file as one value
    {"pool": 10, "ssl": true}
```

#### How the keys reach a Pod

How the keys are delivered depends on how the Pod refers to the Secret (§3 covers env vars vs files in depth):

```yaml
containers:
- name: app
  env:
  - name: DB_PASSWORD               # ONE key → one env var, any name you like
    valueFrom:
      secretKeyRef:
        name: db-creds
        key: password
  envFrom:
  - secretRef:                      # ALL keys → one env var each, named after the key
      name: db-env                  # gives DB_USER, DB_PASSWORD, DB_HOST
    prefix: ""                      # optional, e.g. "APP_" → APP_DB_USER
  volumeMounts:
  - name: creds
    mountPath: /etc/db
    readOnly: true
volumes:
- name: creds
  secret:
    secretName: db-creds            # ALL keys → one file each, named after the key
```

```text
/etc/db/
├── username       (contents: app)
├── password       (contents: s3cr3t)
├── host           (contents: db.internal)
└── config.json    (contents: {"pool": 10, "ssl": true})
```

To mount only some keys, or rename them, list them with `items`. Each `key` picks a Secret key and `path` names the file, the same `path` as in a projected volume:

```yaml
volumes:
- name: creds
  secret:
    secretName: db-creds
    items:
    - key: password
      path: db-password             # → /etc/db/db-password; the other keys aren't mounted
```

`envFrom` only works well when the keys are valid env var names, so use `DB_PASSWORD`-style keys for Secrets meant for env vars, and file-style keys (`tls.crt`, `config.json`) for Secrets meant to be mounted.

#### Example: a Kafka client (SASL/SCRAM over TLS)

A Kafka client connecting with `security.protocol=SASL_SSL` needs:

- **SASL credentials**: a username and password, short strings that suit env vars;
- **a truststore**: the CA certificate of the brokers, often as a PKCS12 file, plus its password. It's binary, so it has to be mounted as a file;
- for mutual TLS instead of SASL, **a keystore** with the client's certificate and key, plus its password.

The bootstrap address and topic names aren't secret, so they belong in a ConfigMap or plain env vars.

Two Secrets, split by how they're used (**not tested here**):

```sh
# 1. SASL credentials: env-var-style keys
kubectl -n orders create secret generic kafka-sasl \
  --from-literal=KAFKA_SASL_USERNAME=orders-service \
  --from-literal=KAFKA_SASL_PASSWORD='s3cr3t'

# 2. TLS material: file-style keys (binary truststore + its password)
kubectl -n orders create secret generic kafka-tls \
  --from-file=truststore.p12=./kafka-ca-truststore.p12 \
  --from-literal=truststore.password='changeit'
```

```text
Secret "kafka-sasl"                       Secret "kafka-tls"
├── KAFKA_SASL_USERNAME → orders-service  ├── truststore.p12      → (binary PKCS12)
└── KAFKA_SASL_PASSWORD → s3cr3t          └── truststore.password → changeit
```

##### The Secret manifests

The same two Secrets written as YAML. Text values can go in `stringData`. The truststore is binary, so it has to go in `data`, base64-encoded: `stringData` only takes strings.

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: kafka-sasl
  namespace: orders
type: Opaque
stringData:
  KAFKA_SASL_USERNAME: orders-service
  KAFKA_SASL_PASSWORD: s3cr3t
---
apiVersion: v1
kind: Secret
metadata:
  name: kafka-tls
  namespace: orders
type: Opaque
data:
  truststore.p12: MIIKcgIBAzCCChwGCSqGSIb3DQEHAaCCCg0Eggo...   # base64 -i kafka-ca-truststore.p12 (truncated)
stringData:
  truststore.password: changeit
```

`data` and `stringData` can be mixed in one Secret. If a key appears in both, `stringData` wins.

What `kubectl get secret -o yaml` returns afterwards: everything is in `data`, base64-encoded, and `stringData` is gone (see "Why `stringData` never comes back"):

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: kafka-sasl
  namespace: orders
type: Opaque
data:
  KAFKA_SASL_PASSWORD: czNjcjN0                 # s3cr3t
  KAFKA_SASL_USERNAME: b3JkZXJzLXNlcnZpY2U=     # orders-service
---
apiVersion: v1
kind: Secret
metadata:
  name: kafka-tls
  namespace: orders
type: Opaque
data:
  truststore.p12: MIIKcgIBAzCCChwGCSqGSIb3DQEHAaCCCg0Eggo...
  truststore.password: Y2hhbmdlaXQ=             # changeit
```

**For mutual TLS instead of SASL**, the client's certificate and key replace the username and password. One Secret can hold the keystore, the truststore and both passwords:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: kafka-mtls
  namespace: orders
type: Opaque
data:
  keystore.p12: MIIJ0QIBAzCCCZcGCSqGSIb3DQEHAaCCCYgEgg...     # client cert + private key (PKCS12)
  truststore.p12: MIIKcgIBAzCCChwGCSqGSIb3DQEHAaCCCg0Eggo...   # brokers' CA
stringData:
  keystore.password: k3yst0re
  truststore.password: changeit
```

The client then sets `security.protocol=SSL`, `ssl.keystore.type=PKCS12`, `ssl.keystore.location`, `ssl.keystore.password`, and the truststore properties below.

**What Strimzi generates** for a `KafkaUser` named `orders-service` with `authentication: scram-sha-512`, as `kubectl get secret -o yaml` shows it **(docs, shape from the Strimzi docs; values are this example's)**:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: orders-service                          # same name as the KafkaUser
  namespace: kafka                              # the namespace the User Operator watches
  labels:
    app.kubernetes.io/managed-by: strimzi-user-operator
    strimzi.io/cluster: my-cluster
    strimzi.io/kind: KafkaUser
  ownerReferences:
  - apiVersion: kafka.strimzi.io/v1beta2
    kind: KafkaUser
    name: orders-service                        # deleting the KafkaUser deletes this Secret
type: Opaque
data:
  password: czNjcjN0
  sasl.jaas.config: b3JnLmFwYWNoZS5rYWZrYS5jb21tb24uc2VjdXJpdHkuc2NyYW0uU2NyYW1Mb2dpbk1vZHVsZSByZXF1aXJlZCB1c2VybmFtZT0ib3JkZXJzLXNlcnZpY2UiIHBhc3N3b3JkPSJzM2NyM3QiOw==
  # decodes to: org.apache.kafka.common.security.scram.ScramLoginModule required username="orders-service" password="s3cr3t";
```

The username isn't a key: it's the Secret's (and KafkaUser's) name. `sasl.jaas.config` is the whole JAAS line, ready to pass to the client as-is.

##### Using them in a Pod

The Pod takes `kafka-sasl` as env vars and mounts `kafka-tls` as files:

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: orders-consumer
  namespace: orders
spec:
  containers:
  - name: app
    image: registry.example.com/orders-consumer:1.4.0
    env:
    - name: KAFKA_BOOTSTRAP_SERVERS             # not secret: plain value (or a ConfigMap)
      value: kafka-bootstrap.kafka.svc:9093
    - name: KAFKA_TRUSTSTORE_LOCATION           # where the mounted file is (mountPath + path)
      value: /etc/kafka/tls/truststore.p12
    - name: KAFKA_TRUSTSTORE_PASSWORD           # one key from the TLS Secret as an env var
      valueFrom:
        secretKeyRef:
          name: kafka-tls
          key: truststore.password
    envFrom:
    - secretRef:
        name: kafka-sasl                        # → KAFKA_SASL_USERNAME, KAFKA_SASL_PASSWORD
    volumeMounts:
    - name: kafka-tls
      mountPath: /etc/kafka/tls
      readOnly: true
  volumes:
  - name: kafka-tls
    secret:
      secretName: kafka-tls
      items:
      - key: truststore.p12                     # mount only the truststore file;
        path: truststore.p12                    # the password came in as an env var
```

```text
/etc/kafka/tls/
└── truststore.p12
```

The application turns these into Kafka client properties (the same names apply to the Java client, fs2-kafka and zio-kafka, which wrap it). The `${...}` below stand for "read this env var". The Kafka client doesn't expand them itself: the app reads the env vars and sets the properties (or, with a properties file, uses Kafka's `EnvVarConfigProvider` and writes `${env:KAFKA_SASL_PASSWORD}`) **(docs)**:

```properties
bootstrap.servers=${KAFKA_BOOTSTRAP_SERVERS}
security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required \
  username="${KAFKA_SASL_USERNAME}" password="${KAFKA_SASL_PASSWORD}";
ssl.truststore.type=PKCS12
ssl.truststore.location=${KAFKA_TRUSTSTORE_LOCATION}
ssl.truststore.password=${KAFKA_TRUSTSTORE_PASSWORD}
```

**If Strimzi runs the Kafka cluster**, you don't create these Secrets by hand. The operators create them, with their own key names **(docs)**:

| Secret | Created for | Keys |
|---|---|---|
| `<cluster>-cluster-ca-cert` | the cluster (the brokers' CA) | `ca.crt`, `ca.p12`, `ca.password` |
| `<user>` for a `KafkaUser` with `authentication: scram-sha-512` | each client user | `password`, `sasl.jaas.config` |
| `<user>` for a `KafkaUser` with `authentication: tls` | each client user | `ca.crt`, `user.crt`, `user.key`, `user.p12`, `user.password` |

These keys are file-style (`ca.p12`, `user.p12`) or ready-made values (`sasl.jaas.config` is the whole JAAS line). So you mount the `.p12` files and pull single keys like `password` with `secretKeyRef`. `envFrom` would produce env var names like `sasl.jaas.config`, which most apps won't read. The cluster CA Secret lives in the Kafka cluster's namespace, so a client in another namespace needs it copied over (for example by ESO, or by mirroring it with a tool).

#### Example: Confluent Cloud, all credentials in one Secret

With Confluent Cloud, the credentials are **API keys**: a key and secret belonging to a service account, used as the SASL `PLAIN` username and password. Schema Registry has its **own, separate** API key. Confluent Cloud's endpoints use publicly trusted certificates, so no truststore is needed **(docs)**.

Six values that one app uses together fit well in one Secret:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: confluent-credentials
  namespace: orders
type: Opaque
stringData:
  bootstrap-url: pkc-12345.westeurope.azure.confluent.cloud:9092
  user-bootstrap: ABCDEFGHIJKLMNOP              # Kafka cluster API key
  password-bootstrap: aB3dE5fG7hI9jK1l...       # Kafka cluster API secret
  url-registry: https://psrc-67890.westeurope.azure.confluent.cloud
  user-registry: QRSTUVWXYZ234567               # Schema Registry API key
  password-registry: zY8xW6vU4tS2rQ0p...        # Schema Registry API secret
```

**Using it as env vars.** Map each key to an env var name with `secretKeyRef`. `envFrom` would create env vars named after the keys, and names with `-` aren't usable: a shell can't expand `$user-bootstrap`.

```yaml
env:
- name: KAFKA_BOOTSTRAP_SERVERS
  valueFrom: {secretKeyRef: {name: confluent-credentials, key: bootstrap-url}}
- name: KAFKA_API_KEY
  valueFrom: {secretKeyRef: {name: confluent-credentials, key: user-bootstrap}}
- name: KAFKA_API_SECRET
  valueFrom: {secretKeyRef: {name: confluent-credentials, key: password-bootstrap}}
- name: SCHEMA_REGISTRY_URL
  valueFrom: {secretKeyRef: {name: confluent-credentials, key: url-registry}}
- name: SCHEMA_REGISTRY_API_KEY
  valueFrom: {secretKeyRef: {name: confluent-credentials, key: user-registry}}
- name: SCHEMA_REGISTRY_API_SECRET
  valueFrom: {secretKeyRef: {name: confluent-credentials, key: password-registry}}
```

**Using it as files.** Mount the whole Secret, and each key becomes a file:

```yaml
volumeMounts:
- {name: confluent, mountPath: /etc/kafka/confluent, readOnly: true}
volumes:
- {name: confluent, secret: {secretName: confluent-credentials}}
```

```text
/etc/kafka/confluent/
├── bootstrap-url
├── user-bootstrap
├── password-bootstrap
├── url-registry
├── user-registry
└── password-registry
```

The client config can then point at those files with Kafka's `DirectoryConfigProvider`. `${dir:<directory>:<file>}` is replaced with the file's contents when the client is created, and it can sit inside a longer value **(docs, not tested)**:

```properties
config.providers=dir
config.providers.dir.class=org.apache.kafka.common.config.provider.DirectoryConfigProvider

bootstrap.servers=${dir:/etc/kafka/confluent:bootstrap-url}
security.protocol=SASL_SSL
sasl.mechanism=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required \
  username='${dir:/etc/kafka/confluent:user-bootstrap}' \
  password='${dir:/etc/kafka/confluent:password-bootstrap}';

schema.registry.url=${dir:/etc/kafka/confluent:url-registry}
basic.auth.credentials.source=USER_INFO
basic.auth.user.info=${dir:/etc/kafka/confluent:user-registry}:${dir:/etc/kafka/confluent:password-registry}
```

The `schema.registry.*` and `basic.auth.*` lines are read by Confluent's serializers, not by the Kafka client itself. Whether they receive the resolved values or the literal `${dir:...}` text wasn't checked. If they get the literal text, use the env var form for the Schema Registry values.

**One Secret or several?** One is fine here. Split only if:

- **different apps need different parts.** RBAC and mounts work per Secret, not per key, so anything that can read `confluent-credentials` gets all six values;
- **the parts rotate on different schedules or are owned by different teams.**

The two URLs aren't really secret and could go in a ConfigMap, but keeping them with the credentials is harmless.

**Rotation:**

1. Create a second API key for the service account.
2. Update the Secret.
3. Restart the Pods. Env vars never change in a running container, and the Kafka client reads mounted files only when it's created.
4. Delete the old key.

Both keys work during the switch. In production, have ESO create this Secret from a vault (§8) rather than keeping the YAML in Git.

#### Example: MongoDB Community Operator, a generated connection-string Secret

Here you write a password Secret and a `MongoDBCommunity` resource. The operator then **generates** a second Secret per user, holding the same connection in several forms. The values are shown decoded, as `stringData`; `kubectl get secret -o yaml` shows them base64-encoded under `data`. **(docs, from memory; names and keys not checked against a running operator)**

```yaml
# You create: the user's password
apiVersion: v1
kind: Secret
metadata:
  name: my-user-password
  namespace: mongodb
type: Opaque
stringData:
  password: s3cr3t
---
# You create: the replica set and its users
apiVersion: mongodbcommunity.mongodb.com/v1
kind: MongoDBCommunity
metadata:
  name: example-mongodb
  namespace: mongodb
spec:
  members: 3
  type: ReplicaSet
  version: "7.0.12"
  security:
    authentication:
      modes: ["SCRAM"]
  users:
  - name: my-user
    db: admin
    passwordSecretRef:
      name: my-user-password
    roles:
    - {name: readWrite, db: orders}
    scramCredentialsSecretName: my-user-scram
    # connectionStringSecretName: orders-mongo    # optional: choose the Secret name yourself
```

```yaml
# The operator creates: <resource>-<db>-<user>
apiVersion: v1
kind: Secret
metadata:
  name: example-mongodb-admin-my-user
  namespace: mongodb
type: Opaque
stringData:
  username: my-user
  password: s3cr3t
  connectionString.standard: mongodb://my-user:s3cr3t@example-mongodb-0.example-mongodb-svc.mongodb.svc.cluster.local:27017,example-mongodb-1.example-mongodb-svc.mongodb.svc.cluster.local:27017,example-mongodb-2.example-mongodb-svc.mongodb.svc.cluster.local:27017/admin?replicaSet=example-mongodb&ssl=false
  connectionString.standardSrv: mongodb+srv://my-user:s3cr3t@example-mongodb-svc.mongodb.svc.cluster.local/admin?replicaSet=example-mongodb&ssl=false
```

- **`connectionString.standard`** lists every replica set member.
- **`connectionString.standardSrv`** uses a DNS SRV lookup on the headless Service, so it doesn't need to change when members change.

The app usually needs only one key:

```yaml
env:
- name: MONGODB_URI
  valueFrom: {secretKeyRef: {name: example-mongodb-admin-my-user, key: connectionString.standardSrv}}
```

The generated Secret is owned by the operator. To change the password, update `my-user-password` (the source), not the generated Secret, which the operator would overwrite.

#### Example: cert-manager, a TLS Secret with optional Java keystores

You write a `Certificate`. cert-manager gets it signed by the issuer and writes the result into the Secret named in `secretName`. **(docs, from memory; not checked against a running cert-manager)**

```yaml
# You create: a Certificate
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: orders-api
  namespace: orders
spec:
  secretName: orders-api-tls          # the Secret cert-manager writes
  dnsNames: [orders.example.com]
  issuerRef:
    name: letsencrypt-prod
    kind: ClusterIssuer
  keystores:                          # optional: also write Java keystores
    pkcs12:
      create: true
      passwordSecretRef:
        name: orders-api-keystore-password
        key: password
```

The keystore password comes from a Secret you create yourself:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: orders-api-keystore-password
  namespace: orders
type: Opaque
stringData:
  password: k3yst0re
```

```yaml
# cert-manager creates
apiVersion: v1
kind: Secret
metadata:
  name: orders-api-tls
  namespace: orders
  annotations:
    cert-manager.io/certificate-name: orders-api
    cert-manager.io/issuer-name: letsencrypt-prod
    cert-manager.io/issuer-kind: ClusterIssuer
    cert-manager.io/common-name: orders.example.com
    cert-manager.io/alt-names: orders.example.com
type: kubernetes.io/tls
data:
  tls.crt: LS0tLS1CRUdJTi...          # leaf certificate + chain (PEM)
  tls.key: LS0tLS1CRUdJTi...          # private key (PEM)
  ca.crt: LS0tLS1CRUdJTi...           # issuing CA, when the issuer provides it
  keystore.p12: MIIKcgIBAzCC...       # only with keystores.pkcs12.create
  truststore.p12: MIIGagIBAzCC...     # only with keystores.pkcs12.create
  # keystore.jks, truststore.jks      # with keystores.jks.create instead
```

- **`type: kubernetes.io/tls`** is what Ingress and Gateway expect. They read `tls.crt` and `tls.key`.
- **A JVM app**, such as a Kafka client, can mount the `.p12` files directly and use the password from `orders-api-keystore-password`.
- **`LS0tLS1CRUdJTi...`** is base64 for `-----BEGIN`, the start of a PEM file. `MII...` is the start of a base64-encoded DER or PKCS12 structure.
- **`ca.crt` may be missing or empty for ACME issuers like Let's Encrypt**, which don't return the root separately. CA and Vault issuers usually fill it (not checked).
- **cert-manager renews the certificate before it expires and rewrites the Secret.** Mounted files update on their own (§3, "What happens when the Secret changes"), but apps that read the certificate only at startup need a restart or a reload.

### In etcd, on a default cluster, it's plain text

Going past the API server and reading etcd directly **(lab)**, the password shows up as readable text.

This etcd read was taken **later in the lab**, after the rotation test in §3 ("What happens when the Secret changes"). That test changed the password from `fake-pass-v1` to `fake-pass-v2` with this command:

```
$ kubectl -n secrets-demo create secret generic demo-db \
    --from-literal=username=app --from-literal=password=fake-pass-v2 \
    --dry-run=client -o yaml | kubectl apply -f -
```

That's why the output below shows `v2`, and why it has a `last-applied-configuration` annotation: `kubectl apply` adds one, but the original `kubectl create` didn't.

```
$ etcdctl get /registry/secrets/secrets-demo/demo-db | strings
/registry/secrets/secrets-demo/demo-db
Secret
demo-db
secrets-demo"
...
0kubectl.kubernetes.io/last-applied-configuration
{"apiVersion":"v1","data":{"password":"ZmFrZS1wYXNzLXYy","username":"YXBw"},"kind":"Secret",...}
...
password
fake-pass-v2
username
Opaque
```

Two things to notice:

1. `fake-pass-v2` is sitting there unencrypted. Not even base64: that's only the API's JSON form, and etcd stores the raw bytes. Anyone with an etcd backup has every Secret.
2. **`kubectl apply` made a second copy.** The `last-applied-configuration` annotation holds the whole Secret, base64 value included. A tool that hides `data` but prints annotations would still show it. `kubectl create` doesn't add this annotation. Server-side apply doesn't either

### Limits and failure modes

**(lab)**

| Test | Result |
|---|---|
| Secret over 1 MiB | `data: Too long: may not be more than 1048576 bytes` |
| Change an `immutable: true` Secret | `data: Forbidden: field is immutable when immutable is set` |
| Env var refers to a missing Secret | Pod stuck in `CreateContainerConfigError`: `Error: secret "nope" not found` |
| Volume refers to a missing Secret | Pod stuck in `ContainerCreating`: `FailedMount ... secret "nope" not found` |

Immutability can't be undone: to change the value, delete and recreate the Secret **(docs)**. It also lowers API server load, because kubelets stop watching those Secrets **(docs)**.

### Secret types: one example of each

Every Secret has a `type`. If you leave it out, you get `Opaque`.

The type does **not** change how the value is stored or protected: every type is base64 in the API and handled the same way in etcd. It does two things:

1. **It tells the API server which keys must be present.** The API server rejects the Secret if they're missing.
2. **It tells other tools what the Secret is for.** The kubelet, kubeadm, Helm, ingress controllers and so on look for a specific type.

**(code)** All the rules below come from `ValidateSecret` and `ValidateSecretUpdate` in
`kubernetes/kubernetes` `pkg/apis/core/validation/validation.go`, and from the constants in `staging/src/k8s.io/api/core/v1/types.go`
Bootstrap token key names are from `staging/src/k8s.io/cluster-bootstrap/token/api/types.go`.

What the API server actually checks, by type **(code)**:

| `type` | API server requires | Checks the content? |
|---|---|---|
| `Opaque` or empty | nothing | no |
| `kubernetes.io/basic-auth` | key `username` **or** `password` present | no (empty values are allowed) |
| `kubernetes.io/tls` | keys `tls.crt` **and** `tls.key` present | **warns but accepts**: bad PEM or a key that doesn't match the cert gives a `Warning:` and the Secret is still created |
| `kubernetes.io/dockerconfigjson` | key `.dockerconfigjson` | **yes**: must parse as JSON |
| `kubernetes.io/dockercfg` | key `.dockercfg` | **yes**: must parse as JSON |
| `kubernetes.io/ssh-auth` | key `ssh-privatekey`, **non-empty** | no |
| `kubernetes.io/service-account-token` | annotation `kubernetes.io/service-account.name` | n/a: a controller fills in `data` |
| `bootstrap.kubernetes.io/token` | **nothing** (falls into `default: // no-op`) | no: checked only when the token is used |
| any other string | nothing | no |

Rules that apply to every type **(code)**:

- **The type can't be changed** after creation (`ValidateImmutableField(newSecret.Type, oldSecret.Type, ...)`). To change it, delete the Secret and create it again.
- Each key must be a valid ConfigMap-style key: letters, digits, `-`, `_` and `.`. The total size limit is 1 MiB (`MaxSecretSize`).
- `stringData` is turned into `data` **before** validation. The code comment says: "We don't validate StringData, as it was already converted back to Data before validation is happening." So the required keys can come from either field.

All values in the examples below are fake.

**The examples show what you *write*, not what you *get back*.** They use `stringData` so the values are readable. The API server copies every `stringData` entry into `data` and drops `stringData` (see "The Secret model" at the end of this section). So `kubectl get secret -o yaml` never shows `stringData`. It shows the same keys under `data`, base64-encoded. Example 2 shows both forms side by side.

**(lab)** Every example below was run on minikube (Kubernetes v1.34.0) in a throwaway namespace `secret-types-demo`, which was deleted afterwards. The real output is shown after each example and collected in "Lab results" at the end. Items still marked **(not tested)** were not run.

#### 1. `Opaque`: anything, no rules

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: app-db
  namespace: shop
type: Opaque                 # same as leaving 'type' out
stringData:
  DB_USER: app
  DB_PASSWORD: fake-pass-v1
  config.json: '{"poolSize": 10}'   # any key, any content
```

Made by `kubectl create secret generic app-db --from-literal=DB_USER=app ...`. It's the default for app config: database passwords, API keys, and everything ESO and the CSI driver sync (§8).

#### 2. `kubernetes.io/basic-auth`: a username and password

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: example-kc-initial-admin
  namespace: keycloak
type: kubernetes.io/basic-auth
stringData:
  username: temp-admin                         # required: username OR password (or both)
  password: 0f1e2d3c4b5a69788796a5b4c3d2e1f0   # fake
```

What you write, and what the API server returns afterwards:

```yaml
# you write (kubectl apply -f)                 # you get back (kubectl get secret -o yaml)
apiVersion: v1                                  apiVersion: v1
kind: Secret                                    kind: Secret
metadata:                                       metadata:
  name: example-kc-initial-admin                  name: example-kc-initial-admin
  namespace: keycloak                             namespace: keycloak
type: kubernetes.io/basic-auth                  type: kubernetes.io/basic-auth
stringData:                                     data:
  username: temp-admin                            username: dGVtcC1hZG1pbg==
  password: 0f1e2d3c4b5a69788796a5b4c3d2e1f0      password: MGYxZTJkM2M0YjVhNjk3ODg3OTZhNWI0YzNkMmUxZjA=
                                                # no stringData: it's write-only
```

The right-hand side is the base64 of the left-hand values (`printf temp-admin | base64` → `dGVtcC1hZG1pbg==`). The real operator writes `data` directly, so its Secret never had `stringData` in the first place. **(lab, same conversion seen in "What the lab showed" below)**

This is exactly the shape the Keycloak operator creates for its first admin user ([keycloak-on-kubernetes.md](keycloak-on-kubernetes.md) §5). If neither key is present, the API server returns `data[username]: Required value` and `data[password]: Required value`. An empty value (`password: ""`) is allowed, because the code only checks that the key exists.

#### 3. `kubernetes.io/tls`: a certificate and its private key

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: example-tls-secret
  namespace: keycloak
type: kubernetes.io/tls
stringData:
  tls.crt: |                 # required; PEM certificate (chain: leaf first)
    -----BEGIN CERTIFICATE-----
    MIIB...fake...
    -----END CERTIFICATE-----
  tls.key: |                 # required; PEM private key
    -----BEGIN PRIVATE KEY-----
    MIIE...fake...
    -----END PRIVATE KEY-----
  ca.crt: |                  # optional extra key; cert-manager adds it (not tested)
    -----BEGIN CERTIFICATE-----
    MIIB...fake...
    -----END CERTIFICATE-----
```

Made by `kubectl create secret tls example-tls-secret --cert certificate.pem --key key.pem`. cert-manager also creates this type. Used by:

- Ingress `spec.tls[].secretName`;
- Gateway listeners' `certificateRefs`;
- Keycloak's `spec.http.tlsSecret`.

**Bad content gets a warning, not a rejection.** On every create and update of a `kubernetes.io/tls` Secret, the API server runs Go's `tls.X509KeyPair(tls.crt, tls.key)`. If that fails, it returns the error as a **warning** and still stores the Secret.
**(code)** `warningsForSecret` in `pkg/registry/core/secret/strategy.go`, called from `WarningsOnCreate` and `WarningsOnUpdate`.

`kubectl create secret tls` does the same check **on your machine**, before sending anything, and refuses outright.

**(lab)**

```
# junk content via YAML: warned, but created
$ kubectl apply -f tls-garbage.yaml          # tls.crt: not-a-cert, tls.key: not-a-key
Warning: tls: failed to find any PEM data in certificate input
secret/tls-garbage created

# cert A with key B via YAML: warned, but created
Warning: tls: private key does not match public key
secret/x created

# the same two cases via kubectl create secret tls: refused by kubectl itself
$ kubectl create secret tls tls-cli-bad --cert bad.crt --key bad.key
error: tls: failed to find any PEM data in certificate input
$ kubectl create secret tls y --cert a.crt --key b.key
error: tls: private key does not match public key

# missing key: rejected by the API server
The Secret "tls-missing" is invalid: data[tls.key]: Required value
```

So a CI pipeline that applies YAML (Argo CD, Flux, `kubectl apply`) can store a broken or mismatched cert. Look for the `Warning:` line in its logs. Otherwise the problem only shows up when nginx, Envoy or Keycloak tries to load the cert.

#### 4. `kubernetes.io/dockerconfigjson`: registry login for pulling images

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: regcred
  namespace: shop
type: kubernetes.io/dockerconfigjson
stringData:
  .dockerconfigjson: |       # required; must be valid JSON
    {
      "auths": {
        "registry.example.com": {
          "username": "robot",
          "password": "fake-registry-token",
          "auth": "cm9ib3Q6ZmFrZS1yZWdpc3RyeS10b2tlbg=="
        }
      }
    }
---
# how it's used: the kubelet reads it when pulling the image
apiVersion: v1
kind: Pod
metadata: {name: web, namespace: shop}
spec:
  imagePullSecrets:
  - name: regcred            # must be in the same namespace as the Pod
  containers:
  - name: web
    image: registry.example.com/shop/web:1.0
```

`auth` is just `base64("robot:fake-registry-token")`, the same format as `~/.docker/config.json`.

Made by `kubectl create secret docker-registry regcred --docker-server=... --docker-username=... --docker-password=...`.

If the content isn't valid JSON, the API server rejects it with `data[.dockerconfigjson]: Invalid value: "<secret contents redacted>": ...`. Note that the error message doesn't echo the value back.

#### 5. `kubernetes.io/dockercfg`: the older registry format

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: regcred-legacy
  namespace: shop
type: kubernetes.io/dockercfg
stringData:
  .dockercfg: |              # required; must be valid JSON
    {
      "registry.example.com": {
        "username": "robot",
        "password": "fake-registry-token",
        "email": "robot@example.com",
        "auth": "cm9ib3Q6ZmFrZS1yZWdpc3RyeS10b2tlbg=="
      }
    }
```

This is the old `~/.dockercfg` layout: registries at the top level, with no `"auths"` wrapper. It's still accepted and still JSON-checked **(code)**. For new Secrets, use #4.

#### 6. `kubernetes.io/ssh-auth`: an SSH private key

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: git-deploy-key
  namespace: gitops
type: kubernetes.io/ssh-auth
stringData:
  ssh-privatekey: |          # required and must be NON-empty
    -----BEGIN OPENSSH PRIVATE KEY-----
    b3Bl...fake...
    -----END OPENSSH PRIVATE KEY-----
  known_hosts: |             # optional extra key that some GitOps tools read (not tested)
    github.com ssh-ed25519 AAAA...fake...
```

Unlike basic-auth, an **empty** key fails here. The code checks `len(secret.Data["ssh-privatekey"]) == 0`, not just whether the key exists.

Typical use: Git access for CI jobs or GitOps controllers, mounted as a file. Kubernetes itself does nothing with this type.

#### 7. `kubernetes.io/service-account-token`: a long-lived ServiceAccount token (old style)

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: ci-bot-token
  namespace: ci
  annotations:
    kubernetes.io/service-account.name: ci-bot   # required; the ServiceAccount must exist
type: kubernetes.io/service-account-token
# no data: the token controller fills it in. After creation, `kubectl get -o yaml` shows:
#   data:
#     token:     <JWT that never expires>
#     ca.crt:    <cluster CA>
#     namespace: ci
#   annotations:
#     kubernetes.io/service-account.uid: <uid>
```

The code comment says: "Only require Annotations[kubernetes.io/service-account.name]. Additional fields … might be contributed later by a controller loop." The key names `token`, `ca.crt` and `namespace` are the constants in `types.go`.

**(lab)**

```
# no annotation: rejected
The Secret "sat-bad" is invalid: metadata.annotations[kubernetes.io/service-account.name]: Required value

# for SA "ci-bot": the controller filled it in within 3 s
data keys: ca.crt namespace token
annotations: kubernetes.io/service-account.name=ci-bot
             kubernetes.io/service-account.uid=fd63a868-44d4-4952-a671-d0ebe3528d61
token claims:
{"iss":"kubernetes/serviceaccount",
 "kubernetes.io/serviceaccount/namespace":"secret-types-demo",
 "kubernetes.io/serviceaccount/secret.name":"ci-bot-token",
 "kubernetes.io/serviceaccount/service-account.name":"ci-bot",
 "kubernetes.io/serviceaccount/service-account.uid":"fd63a868-...",
 "sub":"system:serviceaccount:secret-types-demo:ci-bot"}

# for SA "ghost" (doesn't exist): accepted, then deleted by the controller
secret/ghost-token created
$ kubectl get secret ghost-token          # 3 s later
Error from server (NotFound): secrets "ghost-token" not found

# a brand-new ServiceAccount gets no token Secret
$ kubectl create sa probe-sa; kubectl get sa probe-sa -o jsonpath='{.secrets}'
(empty)
```

Compare these claims with a projected token (§7):

| | Legacy Secret token | Projected token |
|---|---|---|
| `exp` claim | **none**: never expires | yes |
| `aud` claim | **none** | yes |
| `iss` | the fixed string `kubernetes/serviceaccount` | the cluster's issuer URL |
| Valid until | the Secret is deleted | it expires |

The API server checks only the annotation. The token controller then either fills in the Secret or, if the ServiceAccount doesn't exist, deletes it.

**Avoid it if you can.** This token stays valid until the Secret is deleted, and anyone who can read the Secret has it. Pods today get short-lived, audience-bound projected tokens (§7), and new ServiceAccounts no longer get a token Secret, as the lab shows (this changed in Kubernetes 1.24, per the docs). Use this only for things outside the cluster that truly can't fetch a fresh token.

#### 8. `bootstrap.kubernetes.io/token`: joining a node to the cluster (kubeadm)

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: bootstrap-token-abcdef          # must be "bootstrap-token-" + token-id
  namespace: kube-system                # only read from kube-system
type: bootstrap.kubernetes.io/token
stringData:
  token-id: abcdef                      # 6 chars [a-z0-9]       (format: not tested)
  token-secret: 0123456789abcdef        # 16 chars [a-z0-9]      (format: not tested)
  description: "fake token for notes"
  expiration: "2026-11-08T00:00:00Z"    # RFC 3339; the token cleaner deletes it after this
  usage-bootstrap-authentication: "true"   # may be used as a bearer token: abcdef.0123456789abcdef
  usage-bootstrap-signing: "true"          # may sign the cluster-info ConfigMap
  auth-extra-groups: system:bootstrappers:kubeadm:default-node-token
```

The key names and `bootstrap-token-` prefix come from `cluster-bootstrap/token/api/types.go`. Authenticated users of a token become `system:bootstrap:<token-id>` in group `system:bootstrappers` (`BootstrapUserPrefix`, `BootstrapDefaultGroup`).

`kubeadm token create` makes these, and `kubeadm join --token abcdef.0123456789abcdef` uses them.

**The API server doesn't validate any of it.** The type goes through the `default: // no-op` branch. A badly formed one is accepted and then simply fails when a node tries to join.

**(lab)**

```
# nonsense bootstrap token, wrong name, wrong namespace: accepted
$ kubectl apply -f -   # name: not-a-bootstrap-name, token-id: "NOT VALID"
secret/not-a-bootstrap-name created

# the real one minikube's kubeadm created (token-secret not shown)
$ kubectl -n kube-system get secrets --field-selector type=bootstrap.kubernetes.io/token
bootstrap-token-4v7t68: keys=auth-extra-groups token-id token-secret
                             usage-bootstrap-authentication usage-bootstrap-signing
token-id=4v7t68  expiration=(none)  groups=system:bootstrappers:kubeadm:default-node-token
```

minikube's token has **no `expiration` key**, so it never expires. Real `kubeadm token create` tokens default to a 24 h TTL (docs).

#### 9. A custom type: `helm.sh/release.v1` and your own

```yaml
# What Helm stores for each release revision
apiVersion: v1
kind: Secret
metadata:
  name: sh.helm.release.v1.my-app.v3     # release "my-app", revision 3
  namespace: shop
  labels:
    owner: helm
    name: my-app
    status: deployed
    version: "3"
type: helm.sh/release.v1
data:
  release: H4sIAAAA...                   # gzip + base64 of the release JSON (chart, values, manifest)
---
# Your own type: any string, no rules; useful so tools can find "their" Secrets
apiVersion: v1
kind: Secret
metadata:
  name: payments-webhook
  namespace: shop
type: example.com/webhook-signing-key
stringData:
  key: fake-hmac-key
```

The API server treats any unknown type exactly like `Opaque` (`default: // no-op`).

**(lab)** The Envoy Gateway install from earlier left a real Helm release Secret:

```
$ kubectl get secrets -A --field-selector type=helm.sh/release.v1
envoy-gateway-system   sh.helm.release.v1.eg.v1
  labels: name=eg owner=helm status=deployed version=1   modifiedAt=1793541118
  keys:   release

# decode: Kubernetes base64 → Helm's own base64 → gzip → JSON
$ kubectl -n envoy-gateway-system get secret sh.helm.release.v1.eg.v1 \
    -o jsonpath='{.data.release}' | base64 -d | base64 -d | gzip -dc | jq 'keys'
["apply_method","chart","hooks","info","manifest","name","namespace","version"]
chart: gateway-helm v1.9.3
```

Note the **double base64**: Helm base64-encodes the gzip itself, and then Kubernetes base64-encodes the Secret data again. `manifest` holds every rendered YAML file. This install used no custom values, so there was no `config` key. With `--set password=...`, the password would be in `config`.

The Helm example is a reminder: a Helm release Secret contains the **rendered values**, so any password you passed with `--set` ends up in it. Anyone who can `get secrets` in that namespace can read it from there.

#### Lab results, all types together

**(lab)** minikube, Kubernetes v1.34.0, namespace `secret-types-demo` (deleted afterwards). All values fake.

| Test | Result |
|---|---|
| `create secret generic` with no type | `type=Opaque` |
| basic-auth with username and password | created |
| basic-auth with neither key | `data[username]: Required value` and `data[password]: Required value` |
| basic-auth with only `password: ""` | **created** (an empty value is fine) |
| `create secret tls` with a real self-signed cert | created; keys `tls.crt`, `tls.key` |
| tls YAML with junk content | **`Warning: tls: failed to find any PEM data…`, created** |
| tls YAML with mismatched cert and key | **`Warning: tls: private key does not match public key`, created** |
| `create secret tls` with junk or mismatched files | `error:` from kubectl, nothing sent |
| tls YAML missing `tls.key` | `data[tls.key]: Required value` |
| `create secret docker-registry` | `type=kubernetes.io/dockerconfigjson`, content `{"auths":{"registry.example.com":{"username":"robot","password":"fake-registry-token","auth":"cm9ib3Q6…"}}}` |
| dockerconfigjson `"not json"` | `data[.dockerconfigjson]: Invalid value: "<secret contents redacted>": invalid character 'o' in literal null` |
| dockercfg valid JSON | created |
| dockercfg `"{broken"` | `data[.dockercfg]: Invalid value: "<secret contents redacted>": invalid character 'b' looking for beginning of object key string` |
| ssh-auth with a key | created |
| ssh-auth with `ssh-privatekey: ""` | **`data[ssh-privatekey]: Required value`** (unlike basic-auth) |
| SA token, no annotation | `metadata.annotations[kubernetes.io/service-account.name]: Required value` |
| SA token for an existing SA | created, then filled with `ca.crt`, `namespace`, `token` |
| SA token for a missing SA | created, then **deleted by the token controller** |
| bootstrap token with junk name, namespace and content | **created** (no validation) |
| custom type `example.com/webhook-signing-key` | created |
| patch `Opaque` → `basic-auth` | `type: Invalid value: "kubernetes.io/basic-auth": field is immutable` |
| key name `bad/key` | rejected; key names must match `[-._a-zA-Z0-9]+` |

Secret types across the whole lab cluster, while the demo namespace still existed:

```
   3 Opaque
   2 bootstrap.kubernetes.io/token          (1 real in kube-system + 1 demo)
   1 example.com/webhook-signing-key        (demo)
   1 helm.sh/release.v1                     (Envoy Gateway's Helm release)
   2 kubernetes.io/basic-auth
   1 kubernetes.io/dockercfg
   1 kubernetes.io/dockerconfigjson
   1 kubernetes.io/service-account-token
   1 kubernetes.io/ssh-auth
   6 kubernetes.io/tls                      (2 demo + Envoy Gateway / webhook certs)
```

#### Quick reference: which command makes which type

| Command | `type` |
|---|---|
| `kubectl create secret generic` | `Opaque` |
| `kubectl create secret tls` | `kubernetes.io/tls` |
| `kubectl create secret docker-registry` | `kubernetes.io/dockerconfigjson` |
| `kubectl create secret generic --type=<any>` | whatever you pass |
| `kubeadm token create` | `bootstrap.kubernetes.io/token` |
| `helm install` / `helm upgrade` (default storage) | `helm.sh/release.v1` |
| Keycloak operator (initial admin) | `kubernetes.io/basic-auth` |

### The Secret model: `data`, `stringData` and `type`

#### One struct for every type

**(code)** `staging/src/k8s.io/api/core/v1/types.go` (Kubernetes `master`), comments trimmed:

```go
type Secret struct {
    metav1.TypeMeta                                  // apiVersion, kind
    metav1.ObjectMeta                                // metadata
    Immutable  *bool              `json:"immutable,omitempty"`
    Data       map[string][]byte  `json:"data,omitempty"`        // base64 in JSON/YAML
    StringData map[string]string  `json:"stringData,omitempty"`  // write-only
    Type       SecretType         `json:"type,omitempty"`        // just a string
}

type SecretType string   // "Opaque", "kubernetes.io/tls", "helm.sh/release.v1", ...
```

That's the whole object. **There are no type-specific fields.** There's no `username` field for basic-auth and no `tls.crt` field for TLS. Every type keeps its values in the same two maps, using whatever key names it needs. `stringData` belongs to that one struct, so **every type accepts it**: `Opaque`, `basic-auth`, `tls`, custom types, all of them.

The type only matters in two places:

1. **Validation.** `ValidateSecret` switches on `secret.Type` and checks that certain **keys** exist in `data` (see the table at the start of this section).
2. **The tools that consume it.** Ingress controllers look for `tls.crt` and `tls.key`, the kubelet looks for `.dockerconfigjson`, and so on.

**(lab)** The cluster's own schema lists the same fields:

```
$ kubectl explain secret
FIELDS:
  apiVersion  <string>
  data        <map[string]string>
  immutable   <boolean>
  kind        <string>
  metadata    <ObjectMeta>
  stringData  <map[string]string>
    stringData allows specifying non-binary secret data in string form. It is
    provided as a write-only input field for convenience. All keys and values
    are merged into the data field on write, overwriting any existing values.
    The stringData field is never output when reading from the API.
  type        <string>
```

#### Why `stringData` never comes back

**(code)** `pkg/apis/core/v1/conversion.go`:

```go
func Convert_v1_Secret_To_core_Secret(in *v1.Secret, out *core.Secret, s conversion.Scope) error {
    if err := autoConvert_v1_Secret_To_core_Secret(in, out, s); err != nil {
        return err
    }
    // StringData overwrites Data
    if len(in.StringData) > 0 {
        if out.Data == nil {
            out.Data = map[string][]byte{}
        }
        for k, v := range in.StringData {
            out.Data[k] = []byte(v)
        }
    }
    return nil
}
```

`StringData` is tagged `+k8s:conversion-gen=false`. The API server's **internal** Secret, the one that's validated and written to etcd, has no `StringData` field at all:

```
 your YAML (v1)                       internal Secret                 etcd
 ──────────────                       ───────────────                 ────
 data:       {password: Zm9v}   ─┐
 stringData: {password: bar,     ├─►  data: {password: "bar",   ──►  protobuf fields
              username: app}    ─┘          username: "app"}         1 metadata
 type: kubernetes.io/basic-auth  ──►  type                            2 data
                                      (no stringData)                 3 type
                                      ▲
                                      validation runs here
```

This is also why the decoded Secret in [etcd-storage.md](etcd-storage.md) §3.2 has only `1 metadata`, `2 data` and `3 type`.

#### What the lab showed

**(lab)** minikube v1.34.0, throwaway namespaces `sd-demo` and `od-demo` (deleted afterwards), fake values:

| Test | Result |
|---|---|
| `Opaque` written with `stringData: {DB_USER: app, DB_PASSWORD: fake-pass-v1}` | created; reading it back shows only `data: {DB_PASSWORD: ZmFrZS1wYXNzLXYx, DB_USER: YXBw}` |
| `basic-auth` with `stringData` `username`, `password` and an extra key `note` | created; all three keys in `data`. **Extra keys are allowed**: the type only sets a minimum |
| Read it back as JSON | `"stringData" in object` → **False** |
| The same key in `data` (`from-data`) and `stringData` (`from-stringData`) | stored value is **`from-stringData`**: `stringData` wins |
| Required key only in `data` (`username: dGVtcC1hZG1pbg==`), no `stringData` | created: either field satisfies the type |
| `data: {password: not-base64!}` | `BadRequest … illegal base64 data at input byte 3`. Values in `data` must be valid base64; `stringData` avoids that mistake |

#### Why you rarely see `stringData` with `Opaque`

`kubectl create secret generic` doesn't use `stringData`. It base64-encodes the values itself, sends `data`, and doesn't even send a `type`. The server fills in `Opaque`. **(lab)**

```
$ kubectl create secret generic gen --from-literal=DB_PASSWORD=fake --dry-run=client -o yaml
apiVersion: v1
data:
  DB_PASSWORD: ZmFrZQ==
kind: Secret
metadata:
  name: gen
                              ← no stringData, no type
```

So `stringData` only shows up in hand-written YAML, and you never see it in anything read back from the API.

#### Writing `data` directly

`data` is the main field, and `stringData` is only a convenience that gets converted into it. You can write `data` yourself; you just base64-encode each value:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: example-kc-initial-admin
  namespace: keycloak
type: kubernetes.io/basic-auth
data:
  username: dGVtcC1hZG1pbg==        # printf temp-admin | base64
  password: ZmFrZS1wYXNzLXYx        # printf fake-pass-v1 | base64
```

`kubectl get secret -o yaml` returns this exactly as written, because `data` is already the stored form. This is also what the Keycloak operator does in code: `addToData("username", Utils.asBase64("temp-admin"))`.

**The `echo` newline trap (lab).** Throwaway namespace `data-demo`, deleted afterwards. `username` was encoded with `printf` and `password` with `echo`:

```
printf temp-admin | base64     → dGVtcC1hZG1pbg==
echo temp-admin | base64       → dGVtcC1hZG1pbgo=     ← different: ends in "o="
echo -n temp-admin | base64    → dGVtcC1hZG1pbg==

$ kubectl get secret with-data -o yaml
data:
  password: ZmFrZS1wYXNzLXYxCg==       ← "Cg==" at the end is the newline
  username: dGVtcC1hZG1pbg==

decoded (od -c):
username:  t e m p - a d m i n
password:  f a k e - p a s s - v 1 \n  ← trailing newline stored as part of the password
```

`echo` adds a newline, which becomes part of the value. The API server accepts it, because it's valid base64. The app then sends `fake-pass-v1\n` to the database, and the login fails in a way that's hard to see. Use `printf` or `echo -n`. Writing `stringData`, or using `kubectl create secret --from-literal`, avoids the problem entirely.

#### Two `kubectl apply` gotchas with `stringData`

**(lab)**

**1. Removing a key from `stringData` doesn't delete it.**

```
# first apply:  stringData {username, password, note}
# second apply: stringData {username}            ← password and note removed
$ kubectl get secret example-kc-initial-admin -o jsonpath='{.data}'   (keys)
['note', 'password', 'username']                 ← both still there
```

Client-side `kubectl apply` does a three-way merge. The "last applied" side remembers `stringData` keys, but the live object only has `data`. Dropping a `stringData` key never turns into deleting a `data` key. To really remove a key, use `kubectl replace` or delete and recreate the Secret. Or put the values in `data` in your YAML, so the merge can see them. The exact merge path isn't traced in code; this is the observed behaviour.

**2. The `last-applied-configuration` annotation keeps your `stringData` in plain text.**

```
$ kubectl get secret example-kc-initial-admin \
    -o jsonpath='{.metadata.annotations.kubectl\.kubernetes\.io/last-applied-configuration}'
{"apiVersion":"v1","kind":"Secret","metadata":{...},"stringData":{"username":"temp-admin"},"type":"kubernetes.io/basic-auth"}
```

That's not even base64. After the first apply it held the password too. It's the "second copy" problem from "In etcd, on a default cluster, it's plain text" above, made worse. `kubectl create`, server-side apply (`kubectl apply --server-side`) and controllers such as the Keycloak operator don't write this annotation. If you apply Secrets from YAML, prefer `--server-side`.

## 3. Delivering a Secret to a Pod: env var or file

The lab Pod used both, plus a third form, `subPath`:

```yaml
containers:
- name: app
  env:
  - name: DB_PASSWORD
    valueFrom: {secretKeyRef: {name: demo-db, key: password}}
  volumeMounts:
  - {name: s, mountPath: /etc/db, readOnly: true}                     # whole Secret, as files
  - {name: s, mountPath: /etc/one/password, subPath: password}        # one file, via subPath
- name: other                                                         # no reference at all
volumes:
- name: s
  secret: {secretName: demo-db}
```

### Reading Pod YAML: where `env` belongs

`kubectl get pod -o yaml` prints fields in **alphabetical order**, so a container's `env` comes before its `name`. It can look as if `env` belongs to no container:

```yaml
spec:
  containers:
  - env:                        # ← the "- " starts the first container
    - name: KAFKA_API_KEY       #    (this "name" is the env var's name, not the container's)
      valueFrom:
        secretKeyRef:
          key: user-bootstrap
          name: confluent-credentials
    image: registry.example.com/orders-service:1.4.0
    imagePullPolicy: IfNotPresent
    name: app                   # ← the container's name, further down (alphabetical)
    resources: {}
    volumeMounts:
    - mountPath: /etc/kafka/confluent
      name: confluent
      readOnly: true
```

What you'd write, with `name` first because people write it that way:

```yaml
spec:
  containers:
  - name: app
    image: registry.example.com/orders-service:1.4.0
    env:
    - name: KAFKA_API_KEY
      valueFrom:
        secretKeyRef: {name: confluent-credentials, key: user-bootstrap}
```

Both are the same container. Order doesn't matter in YAML maps. What matters:

- **`containers:` is a list.** Each `- ` starts one container, and everything indented under it (`env`, `image`, `name`, `volumeMounts`) belongs to that container, whatever order it's printed in.
- **`env` is always inside a container** (`spec.containers[].env`). There's no Pod-level `env`, so env vars are set per container.
- **Several different fields are called `name`.** Above, `- name: KAFKA_API_KEY` is an env var's name, `name: confluent-credentials` is a Secret's name, and `name: app` is the container's. Indentation tells you which is which.

Many YAML snippets in these notes are **fragments** that start at `env:`, `volumeMounts:` or `volumes:`. `env` and `volumeMounts` go inside a container (`spec.containers[]` in a Pod, `spec.template.spec.containers[]` in a Deployment); `volumes` goes at Pod level (`spec.volumes`, or `spec.template.spec.volumes`).

### Where the value ended up

**(lab)**

```
# Pod spec: only a reference. Reading the Pod doesn't show the value.
$ kubectl get pod reader -o jsonpath='{.spec.containers[0].env}'
[{"name":"DB_PASSWORD","valueFrom":{"secretKeyRef":{"key":"password","name":"demo-db"}}}, ...]

# Inside the container: the value, in the process's environment
$ env | grep DB_PASSWORD
DB_PASSWORD=fake-pass-v1
$ tr '\0' '\n' </proc/1/environ | grep DB_
DB_PASSWORD=fake-pass-v1

# The mount is tmpfs: held in RAM, not written to the node's disk
$ grep /etc/db /proc/mounts
tmpfs /etc/db tmpfs ro,relatime,size=8124776k,noswap 0 0

# A sidecar in the same Pod with no reference sees nothing
$ env | grep -c DB_PASSWORD      # in container "other"
0
$ ls /etc/db
ls: /etc/db: No such file or directory

# On the node: the file, readable by root
$ sudo cat /var/lib/kubelet/pods/<uid>/volumes/kubernetes.io~secret/s/password
fake-pass-v2

# On the node: the runtime stored the env var in plain text
$ sudo docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' <id> | grep DB_
DB_PASSWORD=fake-pass-v1
```

Every place a value exists is a place it can be read:

```
                 Secret object ──────► etcd            plain text unless encrypted (§5)
                      │
                      │ kubelet fetches it, only for Pods on its node
                      ▼
       ┌──────────────┴───────────────┐
       ▼                              ▼
  as an env var                   as a volume
  runtime config on node          tmpfs on node (RAM)
  (docker inspect, as root)       /var/lib/kubelet/pods/...
       │                              │
       ▼                              ▼
  /proc/<pid>/environ             file in the container
  children inherit it             only this container's mounts
```

Root on a node can read every secret of every Pod on that node, in any delivery form. Kubernetes doesn't protect against a compromised node. It limits the damage: a kubelet only gets Secrets for Pods scheduled there.

### What happens when the Secret changes

This is the most practical difference. **(lab)**: the Secret changed from `v1` to `v2` while the Pod ran:

```
# rotate: build the new Secret locally, then apply it over the old one
$ kubectl -n secrets-demo create secret generic demo-db \
    --from-literal=username=app --from-literal=password=fake-pass-v2 \
    --dry-run=client -o yaml | kubectl apply -f -

# then poll the mounted file once a second
file changed after 52s -> fake-pass-v2

volume:  fake-pass-v2     updated in place
subPath: fake-pass-v1     stale
env:     fake-pass-v1     stale
```

How the volume updates without a torn read: the files are symlinks, and the kubelet writes a new timestamped directory, then swaps one symlink.

```
..2026_11_05_17_15_25.2699071141/        new directory
..data -> ..2026_11_05_17_15_25.2699071141   swapped atomically
password -> ..data/password              unchanged since the Pod started
username -> ..data/username
```

This is also why `subPath` doesn't update: it binds one specific file, not the symlink that gets swapped. The Kubernetes docs say the delay can reach the kubelet sync period plus the cache delay **(docs)**. One run took 52 s here, so treat that as one sample, not a constant.

Then the `app` container was killed from the node, so that it restarted in the same Pod **(lab)**:

```
reader   2/2   Running   1 (9s ago)   42m

env after container restart:     fake-pass-v2
subPath after container restart: fake-pass-v2
```

So a container restart is enough: the Pod doesn't need to be replaced. The kubelet works out env vars and `subPath` mounts each time a container starts.

| | Env var | Volume | `subPath` |
|---|---|---|---|
| Updates while running | no | **yes**, after a delay | no |
| Picks up a change on container restart | yes | yes | yes |
| Readable through `/proc/<pid>/environ` | yes | no | no |
| Inherited by child processes | yes | no | no |
| App needs to change | no | read a file, ideally re-read it | read a file |

**Recommendation:** use files for real secrets. The app must also re-read the file: a value that the app read once at startup is as stale as an env var. Use env vars when the app or a third-party image only supports them.

### Are volumes more secure?

Volumes are **safer against accidental leaks**: no `/proc/<pid>/environ`, no inheritance by child processes, no copy in the runtime's container config, and they update on rotation (table above). They are **not** a defence against someone who already has access.

**Where volumes don't help.** Both forms are equally exposed:

- **Anyone who can create Pods** in the namespace can mount any Secret there, as a file or an env var. That's the RBAC bypass in §4 (`stolen=fake-pass-v2`).
- **Root on the node** can read both. The file sits in `/var/lib/kubelet/pods/<uid>/volumes/…` (tmpfs, but readable by root), and the env var is in the runtime's config.
- **Code running inside the container** can just read the file. Volumes protect against leaking, not against a compromised app.
- **etcd and backups.** The Secret is stored the same way whichever way you deliver it (§2, §5).
- **An app that reads the file once at startup** is as stale as an env var. Rotation only helps if the app re-reads it.

#### Making the volume tighter

```yaml
spec:
  securityContext:
    fsGroup: 10001                          # REQUIRED with 0400 + non-root, see lab below
  containers:
  - name: app
    securityContext: {runAsUser: 10001, runAsNonRoot: true}
    volumeMounts:
    - {name: s, mountPath: /etc/db, readOnly: true}   # only in the container that needs it
  volumes:
  - name: s
    secret:
      secretName: demo-db
      defaultMode: 0400                       # owner read-only
      items: [{key: password, path: db-pass}] # only the key this app needs
```

**(lab)** minikube v1.34.0, throwaway namespace `harden-demo` (deleted), fake values. The same Pod, with and without `fsGroup`:

```
without fsGroup (defaultMode 0400):
  uid=10001 gid=0(root) groups=0(root)
  -r--------  1 root  root   12  db-pass
  read: PERMISSION DENIED                 ← the app can't read its own secret

with fsGroup: 10001 (defaultMode 0400 or 0440, same result):
  uid=10001 gid=0(root) groups=0(root),10001
  -r--r-----  1 root  10001  12  db-pass  ← kubelet set the group to fsGroup and added group read
  read: OK

/etc/db/username: No such file or directory   ← items exposed only "password" (as db-pass)
```

Secret files are owned by root. `0400` alone means **only root** can read them, so a non-root app is locked out. `fsGroup` makes the kubelet give the files to that group and add group read. Without `fsGroup`, use `0440` or the default `0644`, or run as root (which you shouldn't).

- Mount it **only in the containers that need it**. Sidecars that don't mount it see nothing (lab, below).
- Use **`items`** to expose only the keys this app needs. The others aren't in the directory at all (lab: `username` missing).
- Avoid **`subPath`** for secrets that rotate, because it never updates.

**In short:** use files by default. Use env vars when the app or image only supports them, which is common and acceptable. The real protection comes from elsewhere:

- RBAC on who can create Pods and read Secrets (§4);
- encryption at rest (§5);
- not giving workloads long-lived credentials at all: short-lived tokens and workload identity (§7), or a CSI mount straight from a secrets manager (§8).

### Volumes belong to the Pod, mounts belong to each container

In the lab Pod above, `volumes:` lists what the Pod *could* use. Each container's `volumeMounts:` decides what that container actually gets. Container `other` declares no mounts, so it sees nothing.

#### Reading a volume entry

```yaml
volumes:
- name: s                          # 1. the volume's name, used by volumeMounts to refer to it
  secret:                          # 2. the volume TYPE: "fill this volume from a Secret"
    secretName: demo-db            # 3. options for that type: which Secret
```

`secret:` doesn't mean "this is a Secret". It means "this **volume** is of type secret". The Secret object itself (`demo-db`) is a separate object in the namespace.

**Every volume has the same shape: a name, plus exactly one type.** Like `valueFrom` (one source per env var), each volume entry has a `name` and one type field. The type decides where the files come from, and the fields under it are that type's settings:

```yaml
volumes:
- name: s                                  # files = keys of Secret demo-db
  secret: {secretName: demo-db}

- name: cfg                                # files = keys of ConfigMap app-config
  configMap: {name: app-config}

- name: scratch                            # an empty directory, nothing pre-filled
  emptyDir: {}

- name: data                               # a real disk, via a PersistentVolumeClaim
  persistentVolumeClaim: {claimName: db-data}

- name: podinfo                            # files = the Pod's own labels/annotations
  downwardAPI:
    items: [{path: labels, fieldRef: {fieldPath: metadata.labels}}]
```

These are all volumes. Only the type differs: `secret`, `configMap`, `emptyDir`, `persistentVolumeClaim`, `downwardAPI`.

**Where everything lives:**

```
 Secret object "demo-db"          Pod spec
 (its own object in etcd)         ─────────
 data:                            volumes:                       ← POD level: what volumes exist
   password: ZmFrZS1w…   ◄──────    - name: s                      and where their files come from
                                      secret:
                                        secretName: demo-db
                                        # optional extras:
                                        # items: [{key: password, path: db-pass}]
                                        # defaultMode: 0400
                                        # optional: true
                                  containers:
                                  - name: app
                                    volumeMounts:                ← CONTAINER level: where it appears
                                    - name: s           ─────────   (matched by name "s")
                                      mountPath: /etc/db
                                      readOnly: true

 result inside container app:  /etc/db/password  →  fake-pass-v1
```

#### Mounted vs unmounted volumes

**(lab)** minikube v1.34.0, throwaway namespaces `vol-demo` and `vol-demo2` (deleted), fake values. One Pod with two containers and two Secret volumes; only `app` mounts `used`:

```yaml
containers:
- name: app
  volumeMounts: [{name: used, mountPath: /etc/db, readOnly: true}]
- name: other                                   # no volumeMounts
volumes:
- {name: used,   secret: {secretName: demo-db}}
- {name: unused, secret: {secretName: demo-db}} # mounted by nobody
```

```
container app    /etc/db: password       (1 mount)
container other  /etc/db: —              (0 mounts)   ← can't see the Secret at all

on the node, /var/lib/kubelet/pods/<uid>/volumes/:
kubernetes.io~secret/used                      tmpfs  ← created
kubernetes.io~projected/kube-api-access-vrgqx  tmpfs  ← the automatic SA token volume
                                                      ← "unused" was never created
```

#### A volume no container mounts is skipped entirely

"Skipped" means the kubelet never prepares the volume at all, which goes further than "not mounted". A volume reaches a container in two steps, and an unused volume stops before the first:

```
                      used volume "used"                 unused volume "unused"
                      (a container mounts it)            (no container mounts it)
                      ───────────────────────            ────────────────────────
 Step 1  kubelet, on the node
   fetch Secret demo-db from the API         yes                         NO
   create tmpfs dir on the node              yes                         NO
     /var/lib/kubelet/pods/<uid>/volumes/
       kubernetes.io~secret/used/
   write the key files into it               yes                         NO
   (fail with FailedMount if Secret missing) yes                         NO

 Step 2  container runtime, at container start
   bind-mount that dir into each container   into "app" at /etc/db       nothing to mount
   that lists it in volumeMounts
```

If even one container mounts the volume, step 1 happens: the Secret is fetched and written to the node. Step 2 then puts it only into the containers that list it.

**(code)** `pkg/kubelet/volumemanager/populator/desired_state_of_world_populator.go` (Kubernetes `master`):

```go
mounts, devices, _ := util.GetPodVolumeNames(pod, ...)
for _, podVolume := range pod.Spec.Volumes {
    if !mounts.Has(podVolume.Name) && !devices.Has(podVolume.Name) {
        // Volume is not used in the pod, ignore it.
        logger.V(4).Info("Skipping unused volume", ...)
```

**(lab)**

| Pod | Result |
|---|---|
| Secret volume declared, no container mounts it, Secret exists | `Running`; nothing written to the node |
| Secret volume declared, no container mounts it, **Secret doesn't exist** | **`Running`**, no `FailedMount` event |
| Same missing Secret, but a container mounts it | stuck in `ContainerCreating` with `FailedMount` (see "Limits and failure modes" in §2) |

**"No container mounts it" isn't access control.** Anyone who can edit the Pod spec can add a mount. Anyone who can create Pods can reference any Secret in the namespace (§4). The only guarantee is that each container sees what it mounts and nothing else.

#### Volumes are used for much more than Secrets

A Secret volume is one type among many. The same `volumes:` and `volumeMounts:` pattern carries all of these:

| Volume type | Used for |
|---|---|
| `secret` | Secret keys as files (tmpfs) |
| `configMap` | config files |
| `projected` | several sources in one directory: Secret, ConfigMap, Downward API, **ServiceAccount token**. Every Pod gets one automatically (`kube-api-access-…`, above) |
| `downwardAPI` | Pod labels, annotations and resources as files |
| `emptyDir` | scratch space shared by the Pod's containers, deleted with the Pod; `medium: Memory` makes it tmpfs |
| `persistentVolumeClaim` | real storage that outlives the Pod (databases, StatefulSets) |
| `csi` | driver-provided storage; also the Secrets Store CSI driver (§8.1) |
| `hostPath` | a directory on the node; powerful and risky |
| `image` | files from an OCI image, always read-only. Beta behind the `ImageVolume` gate, **off** in the main lab: there the API server silently drops the volume *and* its mounts. **(lab)** Works on a separate CRI-O 1.35 test cluster with the gate on (see the note below this table) |

#### Every volume type, written → stored → seen

**(lab)** One Pod using all of them on minikube v1.34.0. Throwaway namespace `voltypes`, deleted along with its PV and node directory. Fake values. The Pod had `labels: {app: demo, team: payments}` and a container memory limit of `64Mi`.

Setup objects:

```bash
kubectl create secret generic demo-db --from-literal=password=fake-pass-v1 --from-literal=username=app
kubectl create configmap app-config --from-literal=color=blue --from-literal=app.properties='pool.size=10'
```

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata: {name: data}
spec:
  accessModes: [ReadWriteOnce]
  resources: {requests: {storage: 1Gi}}
```

**1. `secret`**

```yaml
# written
- name: sec
  secret: {secretName: demo-db}
# stored
- name: sec
  secret: {secretName: demo-db, defaultMode: 420}        # 420 = 0644, added by default
```

Seen at `/v/secret`: `..data  password  username`, on **tmpfs** (`ro, size=65536k`), read-only.

**2. `configMap`**

```yaml
# written
- name: cfg
  configMap:
    name: app-config
    items: [{key: app.properties, path: app.properties}]   # only this key
# stored: same + defaultMode: 420
```

Seen at `/v/configmap`: `app.properties` → `pool.size=10`, on **ext4** (the node's disk, not tmpfs), read-only. `color` isn't there because of `items`.

**3. `projected`**: several sources merged into one directory

```yaml
# written
- name: proj
  projected:
    sources:
    - secret:      {name: demo-db, items: [{key: password, path: db/password}]}
    - configMap:   {name: app-config, items: [{key: color, path: color}]}
    - downwardAPI: {items: [{path: pod-name, fieldRef: {fieldPath: metadata.name}}]}
    - serviceAccountToken: {audience: vault, expirationSeconds: 600, path: token}
# stored: same + defaultMode: 420, and fieldRef gets apiVersion: v1
```

Seen at `/v/projected`: `color  db/  pod-name  token`. One directory, four sources, on tmpfs.

**4. `downwardAPI`**

```yaml
# written
- name: podinfo
  downwardAPI:
    items:
    - {path: labels, fieldRef: {fieldPath: metadata.labels}}
    - {path: mem-limit-mi, resourceFieldRef: {containerName: app, resource: limits.memory, divisor: 1Mi}}
# stored: same + defaultMode: 420, fieldRef.apiVersion: v1
```

Seen: `labels` contains `app="demo"` and `team="payments"`; `mem-limit-mi` contains `64`. On tmpfs.

**5. `emptyDir`**

```yaml
- name: scratch
  emptyDir: {}            # stored exactly like this
```

Seen: empty, **writable**, on **ext4** (node disk), deleted with the Pod.

**6. `emptyDir` in memory**

```yaml
- name: ram
  emptyDir: {medium: Memory, sizeLimit: 16Mi}
```

Seen: empty, writable, on **tmpfs `size=16384k`**. Writing 20 MiB stopped at exactly **16,777,216 bytes**: the tmpfs was full. The Pod kept running and wasn't evicted.

**7. `persistentVolumeClaim`**

```yaml
- name: data
  persistentVolumeClaim: {claimName: data}
```

The PVC itself changed after creation:

```yaml
# PVC written: accessModes + 1Gi request
# PVC stored, after binding:
spec:
  storageClassName: standard          # default StorageClass filled in
  volumeMode: Filesystem              # default
  volumeName: voltypes-data-pv        # set when bound
status: {phase: Bound, capacity: {storage: 1Gi}, accessModes: [ReadWriteOnce]}
```

Seen: empty and writable.

**Lab surprise:** minikube's `storage-provisioner` Pod was **missing**. The PVC sat in `Pending` ("Waiting for a volume to be created … by the external provisioner 'k8s.io/minikube-hostpath'"), and the Pod couldn't be scheduled (`pod has unbound immediate PersistentVolumeClaims`). I fixed it by creating a **PersistentVolume by hand** that names the claim, instead of re-enabling the addon:

```yaml
apiVersion: v1
kind: PersistentVolume
metadata: {name: voltypes-data-pv}
spec:
  capacity: {storage: 1Gi}
  accessModes: [ReadWriteOnce]
  storageClassName: standard
  claimRef: {namespace: voltypes, name: data}      # reserve it for this PVC
  hostPath: {path: /tmp/voltypes-data, type: DirectoryOrCreate}
```

That's **static provisioning**: an admin creates the PV. **Dynamic** provisioning is a provisioner creating it from the StorageClass. The test PV sat under `/tmp`, which is tmpfs on the minikube node, so it showed up as tmpfs.

**8. `hostPath`**

```yaml
- name: logs
  hostPath: {path: /var/log, type: Directory}     # type: the path must already be a directory
```

Seen at `/v/hostpath`: the **node's** `/var/log` (`alternatives.log  calico  containers  pods`), read-only because the mount said `readOnly: true`. Without that, the Pod could write to the node.

**9. `csi`**, with no driver installed

```yaml
- name: store
  csi:
    driver: secrets-store.csi.k8s.io
    readOnly: true
    volumeAttributes: {secretProviderClass: my-provider}
```

The API server stored it unchanged, but the Pod stayed `Pending` in `ContainerCreating`:

```
MountVolume.SetUp failed for volume "store" : kubernetes.io/csi: ... driver name
secrets-store.csi.k8s.io not found in the list of registered CSI drivers
```

`kubectl get csidrivers` showed none on this cluster. With the driver installed, this is how §8.1 mounts secrets straight from a store.

**What the test showed:**

| Type | Filesystem in the container | Writable | Defaults the API server added |
|---|---|---|---|
| `secret` | tmpfs (sized to the Pod's memory limit: 64Mi) | no | `defaultMode: 420` |
| `configMap` | **ext4, node disk** | no | `defaultMode: 420` |
| `projected` | tmpfs | no | `defaultMode: 420`, `fieldRef.apiVersion` |
| `downwardAPI` | tmpfs | no | `defaultMode: 420`, `fieldRef.apiVersion` |
| `emptyDir` | ext4, node disk | yes | none |
| `emptyDir` (Memory) | tmpfs `size=sizeLimit` | yes, until full | none |
| PVC | whatever backs the PV | yes | on the **PVC**: `storageClassName`, `volumeMode`, `volumeName` |
| `hostPath` | the node's own filesystem | if not `readOnly` | none |
| `csi` | depends on the driver | depends | none; fails with no driver |

Two details stand out:

- **Secret volumes are tmpfs (RAM), but ConfigMap volumes are on disk.** That's one practical reason not to put passwords in ConfigMaps.
- **The kubelet adds a `..data` link and a timestamped directory to every API-sourced volume**, so updates can swap all the files at once (§3).

#### The `image` volume, tested

**On the main lab** (Docker runtime, gate off: `kubernetes_feature_enabled{name="ImageVolume",stage="BETA"} 0`), a Pod with an image volume was **accepted and ran, but the volume and its mount were gone** from the stored spec. `/opt/tools` didn't exist, and there was no warning, not even on a server dry-run. **(code)** `dropImageVolumes` in `pkg/api/pod/util.go`: if the gate is off, image volumes are removed from the Pod spec before it's stored.

**On a separate minikube profile** (`--container-runtime=cri-o --feature-gates=ImageVolume=true`; CRI-O 1.35.7, Kubernetes v1.34.0; gate reports `1`; profile deleted afterwards):

```yaml
volumes:
- name: tools
  image: {reference: nginx:1.27-alpine, pullPolicy: IfNotPresent}
containers:
- name: app
  image: busybox:1.36
  volumeMounts:
  - {name: tools, mountPath: /opt/tools, readOnly: true}
  - {name: html,  mountPath: /opt/html,  subPath: usr/share/nginx/html}   # (html = a 2nd image volume, same reference)
```

```
$ ls /opt/tools                    → bin dev docker-entrypoint.sh etc lib ... usr var   (nginx image's root fs)
$ head -2 /opt/tools/etc/os-release → NAME="Alpine Linux"                                (inside a busybox container)
$ ls /opt/html                     → 50x.html index.html                                (subPath into the image)
$ grep /opt/ /proc/mounts          → overlay /opt/tools overlay ro,...   overlay /opt/html overlay ro,...
$ touch /opt/tools/x               → Read-only file system         (read-only even where readOnly wasn't set)
$ /opt/tools/usr/sbin/nginx -v     → sh: not found                 (files only: the binary needs Alpine's musl
                                                                    loader, which this container doesn't have)
events: Pulling image "nginx:1.27-alpine" ... Pulled ... Started container app
```

A reference that doesn't exist behaves like a bad container image: the container stays in `ImagePullBackOff` (`unable to pull image or OCI artifact`).

**What it's for:** shipping **data** as an OCI artifact, versioned and pulled like an image. Examples are ML models, static sites, config bundles and plugins. You don't need an init container to copy the files. It gives you files, not a second runtime.

**What it needs:** the `ImageVolume` gate on (API server and kubelet), and a runtime that supports it (CRI-O here). The main lab's Docker runtime (cri-dockerd, containerd 1.7.27) can't do it.

`secretKeyRef` is one of **five** sources an env var can take its value from. Each variable uses either `value` or exactly one `valueFrom` source.

**(code)** These come from `EnvVarSource` in `staging/src/k8s.io/api/core/v1/types.go` (Kubernetes `master`). The allowed paths come from `validEnvDownwardAPIFieldPathExpressions` and `validContainerResourceFieldPathExpressions` in `pkg/apis/core/validation/validation.go`. **(lab)** `kubectl explain pod.spec.containers.env.valueFrom` on minikube v1.34.0 lists the same five.

| Source | Reads from | Example |
|---|---|---|
| `secretKeyRef` | one key of a **Secret** in the Pod's namespace | `{name: demo-db, key: password}` |
| `configMapKeyRef` | one key of a **ConfigMap** in the Pod's namespace | `{name: app-config, key: color}` |
| `fieldRef` | a field of the **Pod itself** (Downward API) | `{fieldPath: metadata.name}` |
| `resourceFieldRef` | the **container's own** requests/limits | `{resource: limits.memory, divisor: 1Mi}` |
| `fileKeyRef` | a key in an **env file inside a volume** | `{volumeName: cfg, path: app.env, key: DB_HOST}`; alpha, behind the `EnvFiles` feature gate |

#### All of them in one Pod

**(lab)** Throwaway namespace `vf-demo` (deleted), fake values. The Pod had `labels: {team: payments}`, `annotations: {owner: alice}`, `requests: {cpu: 250m, memory: 64Mi}` and `limits: {memory: 128Mi}`.

```yaml
env:
- {name: PLAIN,         value: "hello"}                                   # plain value, no valueFrom
- {name: DB_PASSWORD,   valueFrom: {secretKeyRef:    {name: demo-db, key: password}}}
- {name: MAYBE_TOKEN,   valueFrom: {secretKeyRef:    {name: nope, key: x, optional: true}}}
- {name: COLOR,         valueFrom: {configMapKeyRef: {name: app-config, key: color}}}
- {name: POD_NAME,      valueFrom: {fieldRef: {fieldPath: metadata.name}}}
- {name: POD_NAMESPACE, valueFrom: {fieldRef: {fieldPath: metadata.namespace}}}
- {name: POD_UID,       valueFrom: {fieldRef: {fieldPath: metadata.uid}}}
- {name: TEAM,          valueFrom: {fieldRef: {fieldPath: "metadata.labels['team']"}}}
- {name: OWNER,         valueFrom: {fieldRef: {fieldPath: "metadata.annotations['owner']"}}}
- {name: NODE_NAME,     valueFrom: {fieldRef: {fieldPath: spec.nodeName}}}
- {name: SA_NAME,       valueFrom: {fieldRef: {fieldPath: spec.serviceAccountName}}}
- {name: HOST_IP,       valueFrom: {fieldRef: {fieldPath: status.hostIP}}}
- {name: POD_IP,        valueFrom: {fieldRef: {fieldPath: status.podIP}}}
- {name: POD_IPS,       valueFrom: {fieldRef: {fieldPath: status.podIPs}}}
- {name: CPU_REQ_MILLI, valueFrom: {resourceFieldRef: {resource: requests.cpu, divisor: 1m}}}
- {name: CPU_LIMIT,     valueFrom: {resourceFieldRef: {resource: limits.cpu}}}
- {name: MEM_LIMIT_MI,  valueFrom: {resourceFieldRef: {resource: limits.memory, divisor: 1Mi}}}
- {name: GREETING,      value: "$(POD_NAME) in $(POD_NAMESPACE) is $(COLOR)"}  # refers to earlier vars
```

What the container saw:

```
PLAIN          hello
DB_PASSWORD    fake-pass-v1
MAYBE_TOKEN    <unset>                ← optional + missing Secret = variable not set, Pod still starts
COLOR          blue
POD_NAME       vf
POD_NAMESPACE  vf-demo
POD_UID        3b67e88b-906c-416f-b221-4baae23f914a
TEAM           payments
OWNER          alice
NODE_NAME      minikube
SA_NAME        default
HOST_IP        192.168.49.2
POD_IP         10.244.120.80
POD_IPS        10.244.120.80
CPU_REQ_MILLI  250                    ← 250m with divisor 1m
CPU_LIMIT      8                      ← no CPU limit set → the node's allocatable CPU (8)
MEM_LIMIT_MI   128
GREETING       vf in vf-demo is blue  ← $(VAR) expansion of earlier variables
```

#### What each source accepts

- **`secretKeyRef` / `configMapKeyRef`**: `name`, `key`, `optional`. They work only in the **Pod's own namespace**.
- **`fieldRef`**: `fieldPath`, plus an optional `apiVersion`. The allowed paths:
  - `metadata.name`, `metadata.namespace`, `metadata.uid`
  - `metadata.labels['<key>']`, `metadata.annotations['<key>']`
  - `spec.nodeName`, `spec.serviceAccountName`
  - `status.hostIP`, `status.hostIPs`, `status.podIP`, `status.podIPs`

  Anything else is rejected. `metadata.uid` and `status.hostIPs` are allowed even though `kubectl explain` doesn't list them.
- **`resourceFieldRef`**: `resource`, an optional `divisor`, and an optional `containerName`. Valid resources are `requests.cpu`, `limits.cpu`, `requests.memory`, `limits.memory`, `requests.ephemeral-storage`, `limits.ephemeral-storage`, plus `requests.hugepages-*` and `limits.hugepages-*`. **With no limit set, `limits.*` reports the node's allocatable amount** (lab: `CPU_LIMIT=8`).
- **`fileKeyRef`**: `volumeName`, `path`, `key`, `optional`. The idea is that an init container writes a `KEY=value` file into a shared volume, and the main container's env vars are filled from it.

#### Errors

**(lab)**

| Mistake | Result |
|---|---|
| two sources in one `valueFrom` | `valueFrom: may not have more than one field specified at a time` |
| `value` and `valueFrom` together | ``valueFrom: may not be specified when `value` is not empty`` |
| `fieldRef: metadata.generation` | `field label not supported: metadata.generation` |
| `fileKeyRef` on this cluster | ``must specify one of: `fieldRef`, `resourceFieldRef`, `configMapKeyRef` or `secretKeyRef` ``, because the `EnvFiles` gate is off, so the field doesn't exist here |
| `secretKeyRef` to a missing key, not optional | Pod accepted, container stuck in **`CreateContainerConfigError: couldn't find key no-such-key in Secret vf-demo/demo-db`** |
| missing Secret with `optional: true` | variable simply not set |

#### Related, but not `valueFrom`

- **`envFrom`** loads **every** key of a Secret or ConfigMap at once (`secretRef`, `configMapRef`, optional `prefix`). One binary (non-UTF-8) value makes the whole container fail to start.
- **The same Downward API data can be mounted as files** (`volumes[].downwardAPI`). Unlike env vars, those files **update** when labels or annotations change **(docs; not tested)**. Env vars are fixed when the container starts. The Keycloak operator uses `fieldRef` to pass `POD_IP`, the Pod name and the node name into Keycloak ([keycloak-on-kubernetes.md](keycloak-on-kubernetes.md) §3).

## 4. What protects a Secret, and what doesn't

### RBAC has two bypasses

Reading a Secret needs `get`. But:

- **`list` is as good as `get`.** Listing returns full objects, values included **(docs)**.
- **Creating a Pod is as good as reading.** A Pod can mount any Secret in its namespace, and its logs can print it.

**(lab)**: `deployer` has no rights on Secrets, only on Pods:

```
$ kubectl auth can-i get secrets --as=system:serviceaccount:secrets-demo:deployer
no
$ kubectl get secret demo-db --as=system:serviceaccount:secrets-demo:deployer
Error from server (Forbidden): secrets "demo-db" is forbidden: ...

$ kubectl apply --as=...deployer -f sneaky-pod.yaml      # env P from secretKeyRef demo-db
pod/sneaky created
$ kubectl logs sneaky --as=...deployer
stolen=fake-pass-v2
```

The API server allowed all of this. **The real boundary for a Secret is the namespace**, not the RBAC rule on `secrets`. Anyone who can deploy to a namespace can read its Secrets. So separate teams and sensitive workloads by namespace, and treat "can create Pods" and "can edit Deployments" as "can read Secrets".

The same applies to a cloud store: if a ServiceAccount can read a secret from AWS, anyone who can run a Pod as that ServiceAccount can too.

### The defence layers

| Threat | What helps |
|---|---|
| Stolen etcd backup or disk | encryption at rest (§5) |
| Person browsing with `kubectl` | RBAC on `get`, `list`, `watch` |
| Someone who can create Pods in the namespace | namespace separation; admission policy (explained and tested below) |
| Secret committed to Git | SOPS or Sealed Secrets (§10); a store outside Git |
| Stolen long-lived credential | short-lived identity (§7) |
| Leak you don't know about | audit logs and regular rotation (§9) |
| Root on the node, or code execution in the Pod | very little: the process needs the value, so an attacker running as it can get it |

### Someone who can create Pods: namespace separation and admission policy

**The problem.** RBAC treats "can create Pods" and "can read Secrets" as separate permissions. But a Pod can name any Secret in its namespace, and the kubelet hands over the value. So in practice **create Pods = read every Secret in the namespace** (`stolen=fake-pass-v2` above). The two defences limit that from different sides.

**(lab)** minikube v1.34.0, throwaway namespaces `team-a` and `team-b` (deleted), the policy deleted afterwards, fake values.

#### 1. Namespace separation: limits *which* Secrets are reachable

A Pod can only refer to Secrets in **its own** namespace. There's nowhere to name another one.

```
$ kubectl explain pod.spec.containers.env.valueFrom.secretKeyRef   → key, name, optional   (no namespace)
$ kubectl explain pod.spec.volumes.secret                          → defaultMode, items, optional, secretName

Pod "thief" in team-b, asking for demo-db (which exists only in team-a):
CreateContainerConfigError: secret "demo-db" not found
```

So the namespace is the boundary. **Put sensitive Secrets in a namespace where only trusted people and pipelines can create Pods**, rather than next to everyone's apps. If the production DB password lives in `payments-prod`, an intern who can create Pods in `dev` can't touch it.

#### 2. Admission policy: limits *who* can use a Secret inside the namespace

Sometimes several people must be able to create Pods in the same namespace. An admission policy checks each request before it's stored and can say "only this identity may run workloads that reference `demo-db`". The built-in **`ValidatingAdmissionPolicy`** (no extra software) does this with a CEL expression.

Setup: `deployer` and `intruder` both have RBAC to create Pods and Deployments in `team-a`. **Neither** can `get secrets` (`kubectl auth can-i` → `no` for both).

**Version A: the policy checks Pods only:**

```yaml
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicy
metadata: {name: demo-db-users}
spec:
  matchConstraints:
    resourceRules:
    - {apiGroups: [""], apiVersions: [v1], operations: [CREATE, UPDATE], resources: [pods]}
  validations:
  - expression: >-
      !<pod references demo-db via env, envFrom, secret or projected volume>
      || request.userInfo.username in [
           'system:serviceaccount:team-a:deployer',
           'system:serviceaccount:kube-system:replicaset-controller']   # else Deployments can't work
    message: "only team-a's deployer may run workloads that use Secret demo-db"
---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicyBinding
metadata: {name: demo-db-users}
spec:
  policyName: demo-db-users
  validationActions: [Deny]
  matchResources: {namespaceSelector: {matchLabels: {kubernetes.io/metadata.name: team-a}}}
```

```
intruder creates Pod using demo-db:   denied: only team-a's deployer may run workloads that use Secret demo-db
deployer creates Pod using demo-db:   pod/legit created
intruder creates Pod WITHOUT secret:  pod/plain created          ← everything else still works

bypass: intruder creates a Deployment instead
  deployment.apps/sneaky-deploy created
  pod created by replicaset-controller → log: got=fake-pass-v1   ← STOLEN
```

The policy checks **who sent the request**. The bypassing Pod wasn't sent by the intruder: the **ReplicaSet controller** created it, and the policy had to allow that controller. An allowed controller acts on behalf of whoever created its parent object.

**Version B: also check `deployments` and `replicasets`** (and allow `deployment-controller`):

```
intruder creates Deployment using demo-db:  denied: only team-a's deployer may run workloads ...
deployer creates Deployment using demo-db:  created, rolled out, log: got=fake-pass-v1
```

The full Version B policy, as tested. The binding is the same as above:

```yaml
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicy
metadata: {name: demo-db-users}
spec:
  failurePolicy: Fail
  matchConstraints:
    resourceRules:
    - {apiGroups: [""],   apiVersions: [v1], operations: [CREATE, UPDATE], resources: [pods]}
    - {apiGroups: [apps], apiVersions: [v1], operations: [CREATE, UPDATE], resources: [deployments, replicasets]}
  variables:
  - name: spec                      # the Pod spec, wherever it sits in this kind
    expression: "object.kind == 'Pod' ? object.spec : object.spec.template.spec"
  - name: usesSecret
    expression: >-
      variables.spec.containers.exists(c,
        (has(c.env) && c.env.exists(e, has(e.valueFrom) && has(e.valueFrom.secretKeyRef) && e.valueFrom.secretKeyRef.name == 'demo-db')) ||
        (has(c.envFrom) && c.envFrom.exists(f, has(f.secretRef) && f.secretRef.name == 'demo-db')))
      || (has(variables.spec.volumes) && variables.spec.volumes.exists(v,
        (has(v.secret) && v.secret.secretName == 'demo-db') ||
        (has(v.projected) && v.projected.sources.exists(s, has(s.secret) && s.secret.name == 'demo-db'))))
  validations:
  - expression: >-
      !variables.usesSecret || request.userInfo.username in [
        'system:serviceaccount:team-a:deployer',
        'system:serviceaccount:kube-system:replicaset-controller',
        'system:serviceaccount:kube-system:deployment-controller']
    message: "only team-a's deployer may run workloads that use Secret demo-db"
```

This policy still doesn't check `initContainers` or `ephemeralContainers`. A complete policy must cover every place a Pod spec can reference a Secret.

**A Job is blocked anyway.** With the Pods-only policy, the intruder could create a `Job`, but every Pod it tried failed with `FailedCreate … pods "sneaky-job-…" is forbidden: ValidatingAdmissionPolicy 'demo-db-users' … denied`. The Pods came from the **job-controller**, which isn't on the allow list.

**The rule:** each controller you allow becomes a way around the policy, so the policy must also check the object that controller works from. Allow `replicaset-controller` → check ReplicaSets. Allow `deployment-controller` → check Deployments. The same goes for StatefulSets, DaemonSets, Jobs and CronJobs if you allow their controllers.

## 5. Encryption at rest

There are two separate layers, and "encrypted at rest" can mean either:

| Layer | Encrypts | Protects against | Doesn't protect against |
|---|---|---|---|
| **Disk** | the whole volume under etcd | a stolen physical disk | anyone reading etcd through etcd itself, including its backups |
| **API level** | each object, before the API server writes it to etcd | etcd access, etcd backups | anyone reading through the API |

The API server always decrypts for an authorised caller. So encryption at rest does nothing against `kubectl get secret`.

### Envelope encryption

Calling a remote key service for every write would be slow, and handing the master key to the cluster would defeat the point. The fix is two keys:

```
 DEK (data encryption key)     encrypts the Secret. Local, used often.
 KEK (key encryption key)      encrypts the DEK. Lives in the KMS and never leaves it.

 Write:   Secret ──encrypt with DEK──► ciphertext ─────────────┐
          DEK    ──send to KMS, encrypt with KEK──► wrapped DEK ┴─► stored together in etcd

 Read:    wrapped DEK ──send to KMS──► DEK ──decrypt──► Secret
```

To read a stolen etcd backup, an attacker also needs the KMS to unwrap the key, and every KMS call is authorised and logged. To revoke access, you disable the KEK.

#### Kubernetes KMS v2 details

**(docs)** From the Kubernetes page [Using a KMS provider for data encryption](https://kubernetes.io/docs/tasks/administer-cluster/kms-provider/):

- The API server generates a seed, sends it to the KMS once to be wrapped, and derives a new single-use DEK for each write from that seed.
- It talks to a local plugin over a Unix socket, and the plugin talks to the remote KMS.
- KMS v1 has been deprecated since v1.28 and off by default since v1.29. KMS v2 has been stable since v1.29.

**(code)** The first two points are confirmed in the Kubernetes source (`master`, under `staging/src/k8s.io/apiserver/pkg/`). Not run in the lab: that needs a KMS plugin and an `EncryptionConfiguration` on the API server.

**1. "Generates a seed, sends it to the KMS once, derives a new DEK per write."** `storage/value/encrypt/envelope/kmsv2/envelope.go`:

```go
// GenerateTransformer generates a new transformer and encrypts the DEK/seed using the envelope service.
seed, err := aestransformer.GenerateKey(aestransformer.MinSeedSizeExtendedNonceGCM)
transformer, err := aestransformer.NewHKDFExtendedNonceGCMTransformer(seed)
```

The stored object records its source type as `EncryptedDEKSourceType_HKDF_SHA256_XNONCE_AES_GCM_SEED`.

`storage/value/encrypt/aes/aes_extended_nonce.go` says each write derives a new key with **HKDF-SHA256** from the seed and fresh random bytes: "KDF(seed, random_bytes) -> key". It then encrypts with AES-GCM.

```go
info := make([]byte, infoSizeExtendedNonceGCM)
randomNonce(info)                       // fresh random input per write
e.derivedKeyTransformer(info, ...)      // "failed to derive write key from KDF" on error
```

"Once" means **once per KMS key, per API server**. From `server/options/encryptionconfig/config.go`:

- A background check asks the plugin for its current key ID every **1 minute** (`kmsv2PluginHealthzPositiveInterval`).
- If the key ID is unchanged, the seed is reused ("state is valid and status keyID is unchanged … so there is no need to rotate it").
- If the key ID changes (the KEK was rotated), a new seed is generated and wrapped.
- If the plugin keeps failing, **writes stop after 3 minutes** (`kmsv2PluginWriteDEKSourceMaxTTL`). Reads continue ("allow reads indefinitely in all cases").

So the KMS is called rarely: at startup and after each KEK rotation, not on every write. That's the main improvement of KMS v2 over v1, which called the KMS for every new DEK.

**2. "Talks to a local plugin over a Unix socket."** `storage/value/encrypt/envelope/kmsv2/grpc_service.go`:

```go
// unixProtocol is the only supported protocol for remote KMS provider.
unixProtocol = "unix"
...
c, err := net.DialUnix(unixProtocol, nil, &net.UnixAddr{Name: addr})
```

The API server speaks gRPC to a plugin on the same machine. The plugin (AWS, Azure, Vault and so on) talks to the real KMS over the network. The code only shows the API-server-to-plugin side; how the plugin talks to the KMS depends on the plugin.

```
 kube-apiserver ──gRPC over unix:///…/kms.sock──► KMS plugin (same node) ──network──► cloud KMS / Vault
   seed → wrap once per key ID                       holds cloud credentials         KEK never leaves
   HKDF(seed, random) → DEK per write
```

The KMS v1 deprecation dates are from the docs only.

This has a sharp edge: **lose the KEK and you lose the cluster's data.** On EKS, a disabled customer key gives you a window of 30 days to re-enable it, and a deleted key leaves the cluster unrecoverable **(docs)**. Protect the key from deletion before anything else.

### What each cloud does

| | Default | Your own key? |
|---|---|---|
| **EKS** (1.28+) | **On by default:** all API data, KMS v2, AWS-owned key, plus disk encryption **(docs)** | Yes, customer managed KMS key |
| **AKS** | Azure Storage AES-256 at disk level, always on. API-level KMS is an **optional** layer you turn on **(docs)** | Yes. Platform-managed or customer-managed keys in Key Vault; the new experience needs 1.33+ |
| **Kapsule** (Scaleway) | Scaleway's FAQ says Secrets are encrypted at rest in etcd; only Secrets, not all objects **(docs, search extract)** | Not found |
| **Hetzner** | No managed control plane on the product page, so **this is your job** | No KMS to use |
| **minikube, kubeadm, self-managed** | **Nothing** at API level. See the etcd output in §2 **(lab)** | You configure it |

### Doing it yourself

For self-managed clusters without a KMS, give the API server a key in a file **(not tested on this lab)**:

```yaml
# passed with kube-apiserver --encryption-provider-config=...
apiVersion: apiserver.config.k8s.io/v1
kind: EncryptionConfiguration
resources:
  - resources: [secrets]
    providers:
      - secretbox:                  # first provider encrypts new writes
          keys:
            - name: key1
              secret: <base64 of 32 random bytes>
      - identity: {}                # still able to read old plain-text entries
```

```bash
# existing Secrets stay plain text until rewritten
kubectl get secrets -A -o json | kubectl replace -f -
```

This protects etcd backups and etcd disks. The key sits in a file on the control-plane node, so it doesn't protect against root there. **Keep the key out of the etcd backup**, or the backup includes its own key.

## 6. What a cloud secret store adds

A Kubernetes Secret stores a value. A secret store adds what you need once several apps, clusters and people are involved:

| Feature | Kubernetes Secret | Cloud secret store |
|---|---|---|
| One source across clusters and VMs | no: one copy per cluster | yes |
| Who read what, and when | only if API audit logging is on | built-in logs |
| Access tied to cloud identity | no | yes |
| Version history | no | yes |
| Automatic rotation | no | some stores, for some targets |
| Survives losing the cluster | no | yes |

The store itself works like this, on all three clouds:

```
 secret "prod/db"
   ├── version 3   current
   ├── version 2   previous, kept for rollback
   └── version 1
 encrypted with envelope encryption
 every read: check identity ─► check permission ─► decrypt ─► log
```

### 6.1 The link between a Kubernetes Secret and a secrets manager

A Kubernetes Secret and a cloud secrets manager don't know about each other. Kubernetes has no field that means "this Secret's value lives in AWS". They're two separate stores:

```
 Cloud secrets manager                      Kubernetes Secret
 (AWS Secrets Manager, Key Vault, ...)      (object in etcd)
   the source of truth                        a copy, or nothing at all
   versions, audit, IAM                       base64, RBAC, namespace
            │                                          ▲
            └──────────── nothing connects them ───────┘
                          unless you install something
```

Something you install has to connect them, and there are only three ways:

```
 1. COPY (sync)    store ──► ESO controller ──────────────► Kubernetes Secret ──► Pod (env or file)
                   The Secret becomes a cache of the store, refreshed on a timer.     §8.2

 2. MOUNT          store ──► CSI driver + provider ───────► file in the Pod           §8.1
                   No Secret at all ... unless you turn on sync:
                   store ──► CSI ──► file ──► Kubernetes Secret ──► env var

 3. DIRECT         store ◄── app's SDK, at runtime                                    §8 (A)
                   No Secret, no file. Kubernetes only supplies the identity.
```

Each one needs an identity to read the store, and that's where the clouds differ (§7):

```
 Kubernetes ServiceAccount token ──► cloud trusts it ──► cloud credential ──► read the store
```

#### A second, separate link: KMS

People often mix this one up with the first. A cloud KMS can also encrypt Kubernetes Secrets inside etcd (§5):

```
 Kubernetes Secret ──► API server ──► encrypt with DEK ──► etcd
                                         │
                                         └─ DEK wrapped by a KMS key (AWS KMS, Key Vault key)
```

This doesn't move secrets to the cloud. The value still lives in etcd; the cloud only holds the key that protects it. It's a different product too: a **KMS** holds keys, while a **secrets manager** holds values.

#### How it plays out on each provider

| | AWS | Azure | Scaleway | Hetzner |
|---|---|---|---|---|
| **Secrets manager** | Secrets Manager (and Parameter Store) | Key Vault | Secret Manager | none |
| **1. Copy (ESO)** | yes | yes | yes, **the documented path** | via self-hosted Vault/OpenBao |
| **2. Mount (CSI)** | yes, AWS provider (ASCP) | yes, AKS add-on | no provider | via Vault/OpenBao provider |
| **3. Direct (SDK)** | yes | yes | yes, with an API key | via Vault API |
| **Identity used** | Pod Identity or IRSA: no password | Workload ID: no password | **static API key in a Kubernetes Secret** | Vault Kubernetes auth: no password, but Vault's unseal keys |
| **KMS for etcd** | on by default (1.28+) | optional | Secrets encrypted, per FAQ | you configure it, key on disk |

**AWS** (§11):

```
 Secrets Manager "prod/db"
        ▲  GetSecretValue
        │  temporary credentials (STS or Pod Identity Agent)
        │
 ServiceAccount token ─── Pod Identity association, or IRSA role annotation
        │
   ESO: Secret "db-credentials" ─► Pod
   CSI: /mnt/secrets-store/prod-db in the Pod
   SDK: the app calls Secrets Manager itself
```

No password anywhere in the cluster. Separately, etcd is already encrypted with an AWS KMS key.

**Azure** (§12):

```
 Key Vault "my-vault" / secret "db-password"
        ▲  Key Vault Secrets User role
        │  Entra token
        │
 ServiceAccount token ─── federated credential (issuer + system:serviceaccount:ns:name)
        │
   ESO: Secret ─► Pod
   CSI: file (the provider uses the clientID in the SPC)
   SDK: DefaultAzureCredential, after the webhook injects a token file
```

Also no password. KMS for etcd is something you turn on.

**Scaleway** (§13):

```
 Secret Manager (region, project)
        ▲  SecretManagerReadOnly + SecretManagerSecretAccess
        │
 API key  ◄── stored in Kubernetes Secret "scw-credentials"   ◄── secret zero
        │
   ESO: Secret ─► Pod
```

The store is real and managed, but the link starts with a long-lived key kept in a Kubernetes Secret. I found no Pod federation and no CSI provider for Scaleway. So the "copy" pattern with a static key is the only documented route.

**Hetzner** (§14):

```
 no secrets manager ──► you run Vault or OpenBao (or use another cloud's store)
        ▲  Vault role
        │  Vault checks the token with the Kubernetes TokenReview API
 ServiceAccount token
        │
   ESO or CSI ─► Secret or file
```

There's no cloud identity here, but Vault's Kubernetes auth gives the same password-free login. Secret zero moves to Vault's unseal keys, and etcd encryption is yours to configure.

#### In short

**The cloud store owns the value. The Kubernetes Secret is at most a copy. ESO or the CSI driver makes the copy, using a cloud identity granted to a Kubernetes ServiceAccount.** The difference between providers is mostly whether that identity is short-lived (AWS, Azure, and Vault on Hetzner) or a stored key (Scaleway). GCP isn't covered.

## 7. Identity: solving secret zero

The store needs proof of who's asking. There are two ways to give it that proof:

```
 Static key                                 Federation

 long-lived key in a Kubernetes Secret      short-lived Kubernetes token
 ├─ works for months or years               ├─ expires
 ├─ works from anywhere if it leaks         ├─ useful only as an exchange for a cloud token
 └─ you rotate it by hand                   └─ no stored password anywhere
```

### The token every Pod can have

Kubernetes is itself an identity provider. It signs short-lived tokens (JWTs) for a ServiceAccount, and each token states who it's for. **(lab)**: these are the decoded claims:

```json
{
  "iss": "https://kubernetes.default.svc.cluster.local",
  "sub": "system:serviceaccount:secrets-demo:deployer",
  "aud": ["sts.amazonaws.com"],
  "iat": 1793898955,
  "exp": 1793899555,
  "kubernetes.io": {"namespace": "secrets-demo",
                    "serviceaccount": {"name": "deployer"}}
}
```

| Claim | Meaning | Why it matters |
|---|---|---|
| `iss` | who signed it | the cloud looks up this issuer's public keys |
| `sub` | who it's about | the trust rule matches this exact namespace and ServiceAccount |
| `aud` | who it's meant for | a token for AWS can't be replayed at something else that checks `aud` |
| `exp` | when it dies | here 10 minutes after `iat` |

The cluster also publishes where to find its public keys **(lab)**:

```
$ kubectl get --raw /.well-known/openid-configuration
{"issuer":"https://kubernetes.default.svc.cluster.local",
 "jwks_uri":"https://192.168.49.2:8443/openid/v1/jwks", ...}
```

The `jwks_uri` here is a private address, so no cloud could reach it to check a signature. **That's why this minikube cluster can't federate as it stands.** EKS and AKS host a public issuer URL for each cluster, and that's the piece that makes federation work.

### Creating ServiceAccount tokens: long-lived and short-lived

There are two kinds of ServiceAccount token:

- **Long-lived:** a Secret of type `kubernetes.io/service-account-token`. It's the old style, kept in etcd and never expiring. See example 7 in §2.
- **Short-lived:** issued on demand by the **TokenRequest API** (`serviceaccounts/token`) and never stored.

All of them are signed by the same key and carry the same `iss`. They differ in `aud`, `exp` and what they're bound to.

**(lab)** minikube v1.34.0, ServiceAccounts `ci-bot` and `app-sa` in throwaway namespaces `tok2` and `tok-demo` (deleted).

#### Long-lived: a Secret

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: ci-bot-token
  namespace: tok2
  annotations:
    kubernetes.io/service-account.name: ci-bot   # the ServiceAccount must exist
type: kubernetes.io/service-account-token
# leave data empty: the token controller fills in token, ca.crt, namespace
```

```
$ kubectl apply -f ci-bot-token.yaml
$ kubectl get secret ci-bot-token -o jsonpath='{.data.token}' | base64 -d     (decoded)
aud = none, exp = NONE (never expires)
```

To revoke it, delete the Secret.

#### Short-lived: three ways, all through the TokenRequest API

**1. From the command line**, for scripts, CI and testing:

```bash
kubectl -n tok2 create token ci-bot                          # default 1 h
kubectl -n tok2 create token ci-bot --duration=10m
kubectl -n tok2 create token ci-bot --duration=10m --audience=vault
kubectl -n tok2 create token ci-bot --bound-object-kind=Pod --bound-object-name=p   # dies with the Pod
```

**2. The raw API call** that `kubectl create token` makes, for programs and controllers:

```bash
kubectl create --raw /api/v1/namespaces/tok2/serviceaccounts/ci-bot/token -f - <<'EOF'
{"apiVersion":"authentication.k8s.io/v1","kind":"TokenRequest",
 "spec":{"audiences":["vault"],"expirationSeconds":900}}
EOF
→ status.expirationTimestamp: 2026-11-07T19:01:47Z    token: aud=['vault'], 900 s
```

**3. Mounted into a Pod**, which the kubelet keeps refreshed. This is the normal way for apps:

```yaml
spec:
  serviceAccountName: ci-bot
  containers:
  - name: c
    image: busybox:1.36
    volumeMounts:
    - {name: vault-token, mountPath: /var/run/secrets/tokens, readOnly: true}
  volumes:
  - name: vault-token
    projected:
      sources:
      - serviceAccountToken:
          audience: vault           # who the token is for
          expirationSeconds: 600    # 10 min
          path: vault-token         # → /var/run/secrets/tokens/vault-token
```

```
$ kubectl exec p -- cat /var/run/secrets/tokens/vault-token    (decoded)
aud = ['vault'], exp - iat = 600 s, bound to Pod p
```

#### Limits

**(lab)**

| Requested | Result |
|---|---|
| `--duration=5m`, or `expirationSeconds: 300` in a Pod | **rejected**: `may not specify a duration less than 10 minutes` |
| 10m, 2h, 48h | issued exactly as asked: 600 s, 7200 s, 172800 s |
| **8760h (1 year)** | **issued**: 31,536,000 s |

There's no upper cap here, because minikube doesn't set `--service-account-max-token-expiration`. On a real cluster, set it, for example to `48h`, so nobody can mint year-long tokens. Many managed clusters set a limit **(not tested)**.

#### Every token, compared

**(lab)**

| Token | `aud` | Lifetime (`exp − iat`) | Bound to |
|---|---|---|---|
| Secret `kubernetes.io/service-account-token` | none | **no `exp`** | the Secret |
| **Default token mounted in every Pod** | the API server | **365 days** (with `warnafter` +3607 s) | Pod, node |
| `kubectl create token app-sa` | the API server | 1 h | nothing |
| `… --duration=10m --audience=https://sso.example.com/realms/test` | your audience | 10 min | nothing |
| `… --bound-object-kind=Pod --bound-object-name=p --duration=10m` | the API server | 10 min | Pod `p` |

**Binding to a Pod really revokes the token.** The 10-minute Pod-bound token was checked with a `TokenReview` before and after deleting the Pod:

```
TokenReview before delete: true system:serviceaccount:tok-demo:app-sa
TokenReview after delete:  [invalid bearer token, service account token has been invalidated]
```

There were still minutes left on the clock. An old-style Secret token can't do this; it stays valid wherever it was copied.

#### Why the default Pod token says 365 days

The default projected volume asks for `expirationSeconds: 3607`, an odd number on purpose. **(code)** From `pkg/registry/core/serviceaccount/storage/token.go` (Kubernetes `master`):

```go
// Only perform the extension when token is pod-bound.
if r.extendExpiration && pod != nil && exp == token.WarnOnlyBoundTokenExpirationSeconds && r.isKubeAudiences(req.Spec.Audiences) {
    warnAfter = exp                          // 3607
    exp = r.maxExtendedExpirationSeconds     // 1 year
}
```

`pkg/serviceaccount/claims.go` defines `WarnOnlyBoundTokenExpirationSeconds = 60*60 + 7` and `ExpirationExtensionSeconds = 24 * 365 * 60 * 60`.

When a Pod-bound token for the API server asks for exactly 3607 s, and `--service-account-extend-token-expiration` is on (the default; minikube doesn't change it), the API server issues a **1-year** token. It records `warnafter` = 3607 s so it can report clients still using the token after an hour. This kept apps that read the token once at startup working when clusters moved off never-expiring Secret tokens.

What this means:

- The kubelet **rewrites the file** well before the hour is up, so apps that re-read it always have a fresh token **(not tested: about 80% of the TTL, per the docs)**.
- A **stolen** default token still works for up to a year, **unless its Pod is deleted**. The Pod binding is the real protection.
- For a truly short token, ask for your own (`--duration`, or a projected volume with your own `expirationSeconds` and `audience`). The extension only applies at exactly 3607 s with the API server's audience.
- Cluster operators can turn it off with `--service-account-extend-token-expiration=false`.

#### Long-lived vs short-lived

| | Long-lived (Secret) | Short-lived (TokenRequest) |
|---|---|---|
| How to create | `type: kubernetes.io/service-account-token` Secret | `kubectl create token`, the `/token` API, or a projected volume |
| Expiry | none | 10 min up to the configured maximum (default 1 h) |
| Audience | none, so any service accepting SA tokens will accept it | you choose it (`--audience`, `audience:`) |
| Stored in etcd | **yes**: anyone who can `get secrets` can read it | no: issued, signed and returned, nothing saved |
| Revoke | delete the Secret | wait for expiry; bound tokens die with their Pod or Secret; or delete the ServiceAccount (not tested) |
| Use for | only clients outside the cluster that can't refresh tokens | everything else |

**Which to use:**

- **To call the Kubernetes API from a Pod:** the default mounted token. Re-read the file; don't cache it.
- **To prove the Pod's identity to something else** (Keycloak, Vault, a cloud): a projected token with **your own `audience`** and a short `expirationSeconds`. The audience stops a token stolen from one service being replayed at another. See the federation flow below and [keycloak-on-kubernetes.md](keycloak-on-kubernetes.md) §17.
- **For a script or CI job outside the cluster:** `kubectl create token --duration=…`.
- **Long-lived Secret tokens:** only when nothing else is possible. Delete the Secret when done.

### The federation flow

Both clouds follow this shape:

```
 1. Setup (once)         cloud: "trust tokens from issuer X
                                 when sub = system:serviceaccount:shop:api"

 2. Pod starts           kubelet writes a short-lived token into a file in the Pod

 3. App or SDK ─────────► cloud token service: here's my token
                          ├─ fetch issuer's public keys, check the signature
                          ├─ check iss, sub, aud, exp against the trust rule
                          └─ return a temporary cloud credential

 4. App ────────────────► secret store: read prod/db  (with the temporary credential)
                          ├─ check the permission for that identity
                          └─ return the value, write an audit log

 5. Before expiry         kubelet refreshes the token file; SDK repeats step 3
```

No password was stored at any point. If the token leaks, it's short-lived and usable only by someone who can reach the exchange. That's a much smaller problem than a leaked static key, though still one to avoid.

## 8. Getting a store's value into a Pod

Whichever cloud you use, there are three patterns:

```
 A. SDK in the app      Pod ─► store API at runtime
                        Nothing in Kubernetes at all.

 B. Sync (ESO)          store ─► controller ─► Kubernetes Secret ─► Pod (env or file)
                        The app sees an ordinary Secret.

 C. Mount (CSI)         store ─► CSI driver, at Pod start ─► tmpfs file in the Pod
                        No Secret object, unless you ask for one.
```

| | A. SDK | B. External Secrets Operator | C. Secrets Store CSI Driver |
|---|---|---|---|
| Copy in etcd | **no** | yes | no, unless you sync |
| Works as env var | yes, app sets its own | **yes** | only with sync to a Secret |
| App code changes | **yes** | no | small: read a file |
| Identity used | the Pod's | the controller's, or a referenced ServiceAccount | the Pod's |
| Rotation | app decides | re-read every `refreshInterval` | optional re-fetch, off by default (§8.1) |
| If the store is down | app must handle it | existing Secret stays (§8.2) | new Pods can't start; running Pods keep their files (§8.1) |

### B. External Secrets Operator (ESO)

ESO is a controller. You describe which store to use and what to fetch, and it writes a normal Kubernetes Secret. **(docs)**

```yaml
apiVersion: external-secrets.io/v1
kind: ExternalSecret
metadata: {name: db, namespace: shop}
spec:
  refreshInterval: 1h               # 0 = fetch once, never update
  secretStoreRef: {name: my-store, kind: SecretStore}
  target:
    name: db-credentials            # the Kubernetes Secret to write
    creationPolicy: Owner           # deleted along with the ExternalSecret
  data:
  - secretKey: password             # key in the Kubernetes Secret
    remoteRef:
      key: prod/db                  # name in the cloud store
      property: password            # field, if the value is JSON
```

- The result is a regular Secret, so §2 to §4 all still apply: etcd copy, namespace boundary and all.
- The `SecretStore` is where the cloud differences live. The `ExternalSecret` looks the same on every cloud, so app teams don't need to care which cloud it is.
- Prefer a store that **references** a ServiceAccount over giving the controller itself access. ESO's Azure docs warn that with a mounted identity, anyone who can create a store can read secrets **(docs)**.

ESO wasn't run in this lab: see the record at the end. §8.2 follows its code end to end.

### C. Secrets Store CSI Driver

The driver mounts values as a volume. You describe them in a `SecretProviderClass` and refer to it from a Pod:

```yaml
volumes:
- name: secrets
  csi:
    driver: secrets-store.csi.k8s.io
    readOnly: true
    volumeAttributes:
      secretProviderClass: db-secrets
```

**How it works, from install to file (AWS example).** There are four pieces: the software installed once with Helm, the secret in the store, a `SecretProviderClass` that names it, and a Pod that mounts it.

![Secrets Store CSI Driver: Helm installs the SecretProviderClass CRD into the cluster; the SecretProviderClass's objectName "db-creds" points to the DB-CREDS secret in AWS Secrets Manager](image-15.png)

*Slide: KodeKloud.*

```
 you ──helm install──► cluster
                        ├─ CSIDriver secrets-store.csi.k8s.io
                        ├─ CRDs: SecretProviderClass, SecretProviderClassPodStatus
                        ├─ DaemonSet: driver + node-driver-registrar   (on every node)
                        └─ DaemonSet: AWS provider (ASCP)              (on every node)

 you ──kubectl apply──► SecretProviderClass "db-secrets"  ──names──►  AWS Secrets Manager
                        Pod with a CSI volume ──refers to──┘          secret "db-creds":
                                                                       {"DB_USERNAME": "myuser",
                                                                        "DB_PASSWORD": "mypass"}
```

**Step 1: install the driver and the AWS provider.** Both are Helm charts installed into `kube-system`. The driver chart creates the `CSIDriver` object, the two CRDs, RBAC and the driver DaemonSet (§8.1 has what each part does):

```sh
helm repo add secrets-store-csi-driver https://kubernetes-sigs.github.io/secrets-store-csi-driver/charts
helm install -n kube-system csi-secrets-store secrets-store-csi-driver/secrets-store-csi-driver

helm repo add aws-secrets-manager https://aws.github.io/secrets-store-csi-driver-provider-aws
helm install -n kube-system secrets-provider-aws aws-secrets-manager/secrets-store-csi-driver-provider-aws
```

The AWS provider's chart installs a compatible driver as a dependency by default (`secrets-store-csi-driver.install=false` turns that off), so on EKS the second install alone is enough **(docs)**. Both are DaemonSets, which is why EKS Fargate isn't supported **(docs)**.

To check the install, list the CRDs:

![kubectl get crd on an EKS cluster: secretproviderclasses and secretproviderclasspodstatuses (secrets-store.csi.x-k8s.io) appear next to EKS's own CRDs](image-18.png)

*Screenshot: KodeKloud.*

- **The two `secrets-store.csi.x-k8s.io` lines** are the driver's CRDs: `secretproviderclasses` (what you write, Step 3) and `secretproviderclasspodstatuses` (what the driver writes per mounted Pod, §8.1). Both were created at the same second, by the Helm install.
- **The other four** (`cninodes`, `eniconfigs`, `policyendpoints`, `securitygrouppolicies`) belong to EKS itself: the VPC CNI and the VPC resource controller. They were there before the install, with an older timestamp.
- **`-n kube-system` has no effect.** CRDs are cluster-scoped, so `kubectl get crd` lists them all whatever the namespace. The resources they define are namespaced: a SecretProviderClass lives in the same namespace as the Pods that use it.

Also check the DaemonSets, one Pod per node each: `kubectl get ds -n kube-system` should show the driver (`csi-secrets-store…`) and the AWS provider, with READY equal to the number of nodes.

**Step 2: the secret in the store.** One Secrets Manager secret can hold several values as a JSON object:

```sh
aws secretsmanager create-secret --name db-creds \
  --secret-string '{"DB_USERNAME":"myuser","DB_PASSWORD":"mypass"}'
```

**Step 3: the SecretProviderClass.** It's in the Pod's namespace and holds names only, no values:

```yaml
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata:
  name: db-secrets
spec:
  provider: aws                       # → the driver dials aws.sock
  parameters:
    objects: |
      - objectName: "db-creds"        # the secret's name (or ARN) in Secrets Manager
        objectType: "secretsmanager"  # or "ssmparameter" for Parameter Store
        jmesPath:                     # optional: also split the JSON into one file per key
          - path: DB_USERNAME
            objectAlias: db-username
          - path: DB_PASSWORD
            objectAlias: db-password
```

#### The same SecretProviderClass with and without `secretObjects`

`secretObjects` is the optional part that **also copies** mounted files into a Kubernetes Secret. Both versions below are for the same AWS secret.

**Without `secretObjects`: files only.**

```yaml
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata:
  name: db-secrets
spec:
  provider: aws
  parameters:
    objects: |
      - objectName: "db-creds"
        objectType: "secretsmanager"
        jmesPath:
          - path: DB_USERNAME
            objectAlias: db-username
          - path: DB_PASSWORD
            objectAlias: db-password
```

**With `secretObjects`: files plus a Kubernetes Secret.**

```yaml
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata:
  name: db-secrets
spec:
  provider: aws
  secretObjects:                      # ← the only difference
  - secretName: db-creds-k8s          # the Kubernetes Secret the driver creates
    type: Opaque
    data:
    - objectName: db-username         # must match a file name in the mount (here: the objectAlias)
      key: DB_USERNAME                # key inside the Kubernetes Secret
    - objectName: db-password
      key: DB_PASSWORD
  parameters:
    objects: |
      - objectName: "db-creds"
        objectType: "secretsmanager"
        jmesPath:
          - path: DB_USERNAME
            objectAlias: db-username
          - path: DB_PASSWORD
            objectAlias: db-password
```

What each one produces when a Pod mounts the volume:

```text
WITHOUT secretObjects                     WITH secretObjects
─────────────────────                     ──────────────────
files in the Pod (tmpfs on the node):     the same files, plus
  /mnt/secrets/db-creds      (full JSON)
  /mnt/secrets/db-username   (myuser)     Secret "db-creds-k8s" (stored in etcd)
  /mnt/secrets/db-password   (mypass)       DB_USERNAME: myuser
                                            DB_PASSWORD: mypass
no Kubernetes Secret
```

The generated Secret, as `kubectl get secret db-creds-k8s -o yaml` would show it (values decoded here; the driver also labels it and sets owner references, see "Cleanup" in §8.1):

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: db-creds-k8s
  namespace: <the Pod's namespace>
type: Opaque
data:
  DB_USERNAME: bXl1c2Vy               # myuser
  DB_PASSWORD: bXlwYXNz               # mypass
```

Only the `secretObjects` version lets a container use the values as env vars, because `secretKeyRef` needs a Kubernetes Secret:

```yaml
containers:
- name: app
  env:
  - name: DB_PASSWORD
    valueFrom:
      secretKeyRef: {name: db-creds-k8s, key: DB_PASSWORD}
  volumeMounts:                       # still REQUIRED: the sync reads the mounted files
  - {name: db-creds, mountPath: /mnt/secrets, readOnly: true}
```

| | Without `secretObjects` | With `secretObjects` |
|---|---|---|
| Files in the Pod | yes | yes, the same |
| Kubernetes Secret | none | `db-creds-k8s`, created by the driver's sync controller |
| Value in etcd and in `kubectl get secret` | no | **yes** |
| Env vars via `secretKeyRef` | not possible | possible |
| Ingress/Gateway TLS from the store | not possible | possible (with `type: kubernetes.io/tls`) |
| Needs `syncSecret.enabled=true` in the Helm chart | no | **yes**, or the write fails with `Forbidden` (see "The `syncSecret.enabled` switch" in Example 4) |
| Volume mount still needed | yes | **yes**: no mounting Pod, no Secret |
| Secret lifetime | n/a | created at the first mount, cleaned up through the Pod's owners (§8.1, "Cleanup") |

`objectName` in `secretObjects` refers to a **file in the mount**: the `objectAlias` when one is set, otherwise the `objectName` from `parameters.objects`. Here `db-username` and `db-password` are the aliases from `jmesPath`. Using `db-creds` would copy the whole JSON into one key.

Use the version without `secretObjects` unless something can only read a Kubernetes Secret. The `secretObjects` version puts the values back in etcd, which undoes the main advantage of the CSI driver for those values (see "Main advantage" below).

#### Who creates the Secret?

`secretObjects` is an instruction to the **CSI driver**, not to Kubernetes itself. The driver then creates the Secret through the Kubernetes API, the same way `kubectl create secret` would.

```text
SecretProviderClass.secretObjects   ← you declare "I want a Secret named db-creds-k8s with these keys"
        │
        ▼
CSI driver Pod (sync controller, a DaemonSet Pod on the node)
  reads the mounted files (db-username, db-password)
        │
        │  POST /api/v1/namespaces/<ns>/secrets      ← an ordinary API call, as the driver's ServiceAccount
        ▼
API server  ── checks RBAC: can secrets-store-csi-driver create Secrets?
        │        (only if the chart was installed with syncSecret.enabled=true)
        ▼
etcd: Secret "db-creds-k8s"
```

- **Kubernetes doesn't act on `secretObjects` itself.** The API server stores the SecretProviderClass like any other object and ignores its contents. A SecretProviderClass is a custom resource, and only the driver understands it.
- **The driver is just an API client.** It needs permission to create Secrets like any other client. That's what `syncSecret.enabled=true` grants, through the `secretprovidersyncing-role` ClusterRole. Without it, the API server answers `Forbidden`.
- **It keeps the Secret up to date.** When the files change (rotation), the driver updates the Secret. It also sets owner references, copied from the mounting Pod's owners (for a Deployment, its ReplicaSet), so Kubernetes garbage-collects the Secret when those owners are deleted (§8.1, "Cleanup").

#### Why both `parameters.objects` and `secretObjects`?

They do two different jobs, at two different stages, and they're read by two different components:

| | `parameters.objects` | `secretObjects` |
|---|---|---|
| Question it answers | **What do I fetch** from the store? | **Which of the fetched files** should also be copied into a Kubernetes Secret? |
| Read by | the **provider** (AWS, Azure, Vault, GCP) | the **driver** (the same for every provider) |
| Format | provider-specific (AWS: `objectName`, `objectType`, `jmesPath`; Azure: `objectName`, `objectType`, with `keyvaultName` and `tenantId` as separate parameters; Vault: `secretPath`, `secretKey`) | always the same: `secretName`, `type`, `data[].objectName`, `data[].key` |
| Talks to | the external store (Secrets Manager, Key Vault, ...) | the Kubernetes API |
| Produces | **files** in the Pod's mount | a **Kubernetes Secret** |
| Required? | yes, otherwise nothing is fetched | no, it's optional |

`secretObjects` **can't fetch anything**. It has no idea where Secrets Manager or Key Vault is, or how to authenticate. It only reads files that `objects` has already put in the mount:

```text
parameters.objects                         secretObjects
(input: what to fetch)                     (output: what to copy)
──────────────────────                     ──────────────────────
objectName: db-creds ──provider──► files in the mount ──driver──► Secret db-creds-k8s
  jmesPath:                          /mnt/secrets/db-creds             (reads files by name)
    DB_USERNAME → db-username        /mnt/secrets/db-username ◄── objectName: db-username → key DB_USERNAME
    DB_PASSWORD → db-password        /mnt/secrets/db-password ◄── objectName: db-password → key DB_PASSWORD
```

So `secretObjects[].data[].objectName` is **a reference to a file that `objects` created**, not a name in the store. If you remove `objects`, there are no files and the sync fails. The driver code returns `file matching objectName db-username not found in the pod`, as quoted in §8.1, Example 4 **(code)**.

Why it was designed this way:

- **Fetching is provider-specific and copying isn't.** Each provider has its own way to name and fetch secrets, so that lives in `parameters`, which the driver passes to the provider without reading. Writing a Kubernetes Secret is the same for every provider, so the driver does it once, in one format.
- **There's one fetch.** Values come from the store once, through the mount, and the Secret is copied from those files. There's no second call to the store, and the files and the Secret can't disagree.
- **You can choose what to copy.** `objects` can fetch ten values while `secretObjects` copies only the one that needs to be an env var. The other nine stay as files and out of etcd.

#### `objectType` values: AWS and Azure

`objectType` belongs to `parameters.objects`, so each provider defines its own values. GCP and Vault don't have the field: GCP uses `resourceName`, Vault uses `secretPath` + `secretKey`.

**AWS** has two values (`provider/secret_descriptor.go`) **(code)**:

| `objectType` | Fetches from | API call |
|---|---|---|
| `secretsmanager` | AWS Secrets Manager | `GetSecretValue` |
| `ssmparameter` | SSM Parameter Store | `GetParameters` |

```go
var typeMap = map[string]SecretType{
	"secretsmanager": SecretsManager,
	"ssmparameter":   SSMParameter,
	"ssm":            SSMParameter,   // only for reading ARNs, rejected as an objectType
}
```

Details from the validation code:

- **`ssm` is rejected** as an `objectType`, even though it's in the map. That entry exists because an SSM ARN's service part is `ssm` (`arn:aws:ssm:...`). Writing `objectType: "ssm"` fails with `Invalid objectType: ssm`.
- **The values are case-sensitive.** It's a plain map lookup, so `SecretsManager` or `SSMParameter` gives `Invalid objectType`.
- **`objectType` is optional if `objectName` is a full ARN.** The type is then taken from the ARN's service part (`arn:aws:secretsmanager:…` → Secrets Manager). Otherwise it's required: `Must use objectType when a full ARN is not specified`.
- **If you give both, they must agree.** `objectType: ssmparameter` with a Secrets Manager ARN fails with `objectType does not match ARN`.

```yaml
objects: |
  - objectName: "db-creds"                     # name → objectType required
    objectType: "secretsmanager"
  - objectName: "/orders/prod/db-host"         # SSM parameter name
    objectType: "ssmparameter"
  - objectName: "arn:aws:secretsmanager:eu-west-1:111122223333:secret:db-creds-AbCdEf"
    # objectType omitted: taken from the ARN
```

**Azure** has three values (`pkg/provider/types/types.go`) **(code)**:

```go
VaultObjectTypeSecret      = "secret"
VaultObjectTypeKey         = "key"
VaultObjectTypeCertificate = "cert"
```

Any other value fails with `Invalid vaultObjectTypes. Should be secret, key, or cert`. The provider compares the value exactly, so it's case-sensitive: `Secret` or `certificate` fails.

What each one writes to the file matters more than the list (`pkg/provider/provider.go`) **(code)**:

| `objectType` | Key Vault API | What ends up in the file |
|---|---|---|
| `secret` | `GetSecret` | **the secret's value, as is.** If the name belongs to a **certificate**, you get the certificate **and its private key**, converted from PFX to PEM by default |
| `key` | `GetKey` | **only the public key**, as PEM (RSA or EC). The private key never leaves Key Vault |
| `cert` | `GetCertificate` | **only the public certificate**, as PEM. **No private key** |

This catches people out with TLS. To get a certificate *with* its private key, for an Ingress or a server, use `objectType: secret` with the **certificate's name**. That works because Key Vault stores each certificate's key pair as a hidden secret with the same name. `objectType: cert` gives you only the public part.

```yaml
parameters:
  keyvaultName: orders-kv
  tenantId: <tenant-id>
  clientID: <workload identity client ID>
  objects: |
    array:
      - |
        objectName: db-password
        objectType: secret          # a plain secret value
      - |
        objectName: orders-api-tls
        objectType: secret          # certificate + private key (PEM)
        objectAlias: tls.pem
      - |
        objectName: orders-api-tls
        objectType: cert            # public certificate only
        objectAlias: tls.crt
      - |
        objectName: signing-key
        objectType: key             # public key only
```

The Azure `objects` format is different from AWS. It's an `array:` of YAML strings, each starting with `- |`.

#### One SecretProviderClass per cloud, same Pod (not tested)

Both classes below provide the same things. Each mounts a database username and password plus one more value as files, and copies them into a Kubernetes Secret named `orders-db`.

##### Azure Key Vault (Workload Identity)

```yaml
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata:
  name: orders-secrets
  namespace: orders
spec:
  provider: azure
  parameters:
    usePodIdentity: "false"                 # the old AAD Pod Identity; deprecated
    clientID: "<managed-identity-client-id>" # Workload Identity: the identity to sign in as
    keyvaultName: "orders-kv"
    tenantId: "<tenant-id>"
    objects: |
      array:
        - |
          objectName: db-username
          objectType: secret
        - |
          objectName: db-password
          objectType: secret
          objectVersion: ""                 # empty means the latest version
        - |
          objectName: orders-tls            # a certificate in Key Vault
          objectType: secret                # secret gives the cert plus its private key, as PEM
          objectAlias: tls.pem              # file name in the mount
  secretObjects:
  - secretName: orders-db
    type: Opaque
    data:
    - objectName: db-username               # file name in the mount
      key: username                         # key in the Kubernetes Secret
    - objectName: db-password
      key: password
```

Setting up the identity:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: orders
  namespace: orders
  annotations:
    azure.workload.identity/client-id: "<managed-identity-client-id>"
```

The managed identity also needs:

- a federated identity credential for `system:serviceaccount:orders:orders`;
- read access to secrets on the vault (the "Key Vault Secrets User" role).

##### AWS Secrets Manager and Parameter Store (IRSA or EKS Pod Identity)

```yaml
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata:
  name: orders-secrets
  namespace: orders
spec:
  provider: aws
  parameters:
    region: eu-west-1
    usePodIdentity: "true"                  # "true" = EKS Pod Identity; leave out for IRSA
    objects: |
      - objectName: "orders/db-creds"       # one JSON secret: {"username":..,"password":..}
        objectType: "secretsmanager"
        jmesPath:                           # split the JSON into separate files
          - path: username
            objectAlias: db-username
          - path: password
            objectAlias: db-password
      - objectName: "/orders/db-host"
        objectType: "ssmparameter"          # not "ssm"
        objectAlias: db-host
  secretObjects:
  - secretName: orders-db
    type: Opaque
    data:
    - objectName: db-username
      key: username
    - objectName: db-password
      key: password
    - objectName: db-host
      key: host
```

Setting up the identity, depending on which mode you use:

```yaml
# IRSA: annotate the ServiceAccount with the IAM role
apiVersion: v1
kind: ServiceAccount
metadata:
  name: orders
  namespace: orders
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::111122223333:role/orders-secrets
```

```bash
# EKS Pod Identity: no annotation; link the ServiceAccount to the role instead
aws eks create-pod-identity-association --cluster-name prod \
  --namespace orders --service-account orders \
  --role-arn arn:aws:iam::111122223333:role/orders-secrets
```

The role needs `secretsmanager:GetSecretValue` and `secretsmanager:DescribeSecret` on the secret, and `ssm:GetParameters` on the parameter.

Two details from AWS Emulator's Secrets Manager code (a model of AWS's behaviour, not AWS's own code):

- **The version read is `AWSCURRENT` unless you name one.** `GetSecretValue` takes `VersionId` or `VersionStage`; with neither, it returns the version labelled `AWSCURRENT`. In the SPC, `objectVersion` or `objectVersionLabel` (for example `AWSPREVIOUS`) pins a different one.
- **The value is decrypted with the secret's KMS key on every read.** If KMS refuses, the read fails with `DecryptionFailure`, not `AccessDenied`. With a customer-managed key, the role also needs `kms:Decrypt` on that key (the AWS-managed `aws/secretsmanager` key needs nothing extra).

##### The Pod (the same on both clouds)

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: orders
  namespace: orders
spec:
  serviceAccountName: orders
  containers:
  - name: app
    image: busybox:1.36
    command: ["sleep", "3600"]
    env:
    - name: DB_PASSWORD
      valueFrom:
        secretKeyRef:
          name: orders-db          # created by secretObjects
          key: password
    volumeMounts:
    - name: secrets
      mountPath: /mnt/secrets      # files: db-username, db-password, ...
      readOnly: true
  volumes:
  - name: secrets
    csi:
      driver: secrets-store.csi.k8s.io
      readOnly: true
      volumeAttributes:
        secretProviderClass: orders-secrets
```

Only the SecretProviderClass and the identity setup change between clouds. The Pod stays the same.

#### The `secretObjects` flow end to end (Azure Key Vault example)

From deploy to a running container with env vars, when the SecretProviderClass has `secretObjects`. Redrawn from a team diagram, with two corrections:

- **The driver doesn't call Key Vault itself.** It asks the Azure provider over a gRPC socket on the node, and the provider calls Key Vault, using the Pod's workload identity.
- **The kubelet doesn't read the Secret directly.** It asks the API server, and the Secret may not exist yet when the container is first created.

```mermaid
sequenceDiagram
    autonumber
    participant H as Helm / kubectl
    participant API as API server
    participant K as kubelet (node)
    participant D as CSI driver
    participant P as Azure provider
    participant KV as Key Vault
    participant S as Kubernetes Secret

    H->>API: create SecretProviderClass + Deployment
    API-->>K: Pod scheduled on this node
    K->>D: NodePublishVolume (volume with secretProviderClass=...)
    D->>API: read the SecretProviderClass
    D->>P: Mount (gRPC over unix socket): objects + Pod's SA token
    P->>KV: GET each object listed in the SPC (workload identity token)
    KV-->>P: values
    P-->>D: file contents
    D->>D: write files to the Pod's tmpfs, mount at mountPath
    D-->>K: volume ready
    Note over D,S: sync controller (async, separate from the mount)
    D->>S: create/update Secret from SPC.secretObjects (reads the mounted files)
    K->>API: resolve env secretKeyRef
    alt Secret already exists
        API-->>K: Secret data
        K->>K: start container with env vars
    else not created yet
        API-->>K: not found → CreateContainerConfigError
        K->>API: retry until the Secret appears, then start the container
    end
```

The same steps as a flowchart, including the branches where it stops:

```mermaid
flowchart TD
    A[Helm/kubectl: create SecretProviderClass + Deployment] --> B[Pod scheduled on a node]
    B --> C[kubelet: mount the secrets-store CSI volume]
    C --> D[CSI driver reads the SPC, calls the Azure provider over gRPC]
    D --> E{Provider can get a token<br/>and read Key Vault?}
    E -- no --> E1[FailedMount event<br/>Pod stuck in ContainerCreating]
    E -- yes --> F[Files written to the Pod's tmpfs<br/>mounted at mountPath]
    F --> G{SPC has secretObjects?}
    G -- no --> H[Files only, no Kubernetes Secret<br/>container starts if it uses no secretKeyRef]
    G -- yes --> I{syncSecret.enabled=true?}
    I -- no --> I1[Write fails with Forbidden, warning logged<br/>no Kubernetes Secret]
    I -- yes --> J[Sync controller creates/updates the Kubernetes Secret<br/>from the mounted files]
    J --> K{Container env uses secretKeyRef?}
    I1 --> K
    H --> K
    K -- no --> L[Container starts]
    K -- yes --> M{Secret exists?}
    M -- yes --> L2[Container starts with env vars]
    M -- no --> M1[CreateContainerConfigError<br/>kubelet retries]
    M1 --> M
```

What the diagrams show:

- **The mount comes first, always.** Without a Pod mounting the volume, nothing is fetched and no Secret is created.
- **The Secret is created from the files**, not fetched again from Key Vault.
- **Env vars are fixed when the container starts.** If rotation later updates the Secret, the env vars don't change until the Pod restarts (§9).
- **If `syncSecret.enabled` is off**, a `secretKeyRef` to the synced Secret never resolves, and the container stays in `CreateContainerConfigError` (reasoned from the code, not tested).

**Step 4: give the Pod access in AWS.** The Pod's ServiceAccount needs an IAM role allowing `secretsmanager:GetSecretValue` and `secretsmanager:DescribeSecret` on that secret, linked by IRSA (the `eks.amazonaws.com/role-arn` annotation) or an EKS Pod Identity association (§ "Where the Pod's identity comes from").

**Step 5: the Pod spec.** The next step is to write the Pod spec and tell the Pod: "we want this specific secret mounted inside the container". It takes two parts, a CSI **volume** that points at the SecretProviderClass, and a **volumeMount** that puts it into the container:

```yaml
spec:
  volumes:
    - name: db-creds
      csi:
        driver: secrets-store.csi.k8s.io
        readOnly: true
        volumeAttributes:
          secretProviderClass: db-secrets
  containers:
    - name: nginx
      image: nginx
      volumeMounts:
        - name: db-creds
          mountPath: /tmp
```

Line by line:

| Field | What it does |
|---|---|
| `volumes[].name: db-creds` | The volume's name *inside this Pod*, used only to match the `volumeMounts` entry below. It's unrelated to the AWS secret, even though both are called `db-creds` here |
| `csi:` | An inline (ephemeral) CSI volume: it exists only for this Pod, with no PersistentVolume or PVC. The kubelet calls the driver's `NodePublishVolume` when the Pod starts on a node, and unpublishes it when the Pod goes away |
| `driver: secrets-store.csi.k8s.io` | Which CSI driver the kubelet calls. It must match the `CSIDriver` object the Helm chart created, and the name the driver registered on the node (① in "The secrets chain end to end") |
| `readOnly: true` | Required, not just recommended: the driver rejects the mount with `InvalidArgument: Readonly is not true in request` otherwise (`nodeserver.go`) **(code)**. The container can read the files but not change them |
| `volumeAttributes.secretProviderClass: db-secrets` | The link to the SecretProviderClass by **name**, looked up in the **Pod's namespace**. It's passed to the driver as part of the volume context, together with the Pod's name, namespace, UID and ServiceAccount token |
| `volumeMounts[].name: db-creds` | Mounts the volume with that name into this container. Only containers that list it see the files; other containers in the same Pod don't |
| `mountPath: /tmp` | The directory in the container where the files appear |

So the chain of names is: `volumeMounts.name` → `volumes.name` → `secretProviderClass: db-secrets` → SPC `objectName: db-creds` → the secret in Secrets Manager.

![The Pod's CSI volume names secretProviderClass db-secrets, which matches the SecretProviderClass's metadata.name; its objectName "db-creds" points to the DB-CREDS secret in AWS Secrets Manager](image-16.png)

*Slide: KodeKloud.*

Three changes make the slide's version safer for real use:

```yaml
spec:
  serviceAccountName: web                    # the ServiceAccount from step 4 (IRSA / Pod Identity)
  volumes:
    - name: secrets-store
      csi:
        driver: secrets-store.csi.k8s.io
        readOnly: true
        volumeAttributes:
          secretProviderClass: db-secrets
  containers:
    - name: nginx
      image: nginx
      volumeMounts:
        - name: secrets-store
          mountPath: /mnt/secrets            # a dedicated directory, not /tmp
          readOnly: true
```

- **Set `serviceAccountName`.** Without it the Pod runs as `default`, which has no IAM role, and the mount fails with an access-denied error from AWS.
- **Don't mount over `/tmp`.** A mount hides whatever the image had at that path, and this volume is read-only, so anything in the container that writes temporary files to `/tmp` breaks. Use a directory of its own.
- **`readOnly` on the volumeMount too.** It makes the container's view read-only as well. It's optional, since the volume is already read-only.

The mount happens before the container starts. If any part of the chain fails (wrong SPC name or namespace, missing provider, IAM denied, secret not found), the Pod stays in `ContainerCreating` and `kubectl describe pod` shows a `FailedMount` event with the provider's error.

**What happens when the Pod starts.** Once the Pod is scheduled to a node, three components on that node fetch the secret: the kubelet, the CSI driver and the provider. The kubelet and the driver run on every node, and so does the provider, as a DaemonSet:

![At Pod start the kubelet calls the CSI driver, the driver calls the secret provider, the provider fetches DB-CREDS from AWS Secrets Manager and hands the values back, and the driver writes them into the Pod's volume](image-17.png)

*Slide: KodeKloud. Its title says "External Secrets Operator", but the picture is the CSI driver's flow. ESO has no kubelet or CSI step: its controller writes a Kubernetes Secret instead (§8 B).*

Following the arrows:

1. **kubelet → CSI driver.** Before starting the container, the kubelet sees the `csi` volume and calls `NodePublishVolume` on the driver over its Unix socket (`csi.sock`). The request carries the volume attributes (`secretProviderClass: db-secrets`), the Pod's details and its ServiceAccount token, plus the target directory under `/var/lib/kubelet/pods/<uid>/…`.
2. **CSI driver → SecretProviderClass.** The driver reads `db-secrets` from the API server, in the Pod's namespace. That's the CRD in the middle of the slide.
3. **CSI driver → Secret Provider.** `spec.provider: aws` tells the driver which socket to dial (`aws.sock`). It calls `Mount` with the SPC's `parameters`, the Pod details and the token.
4. **Provider ⇄ AWS Secrets Manager** (the dashed arrows). The provider trades the Pod's token for AWS credentials (IRSA via STS, or the Pod Identity agent), then calls `GetSecretValue` for `db-creds` (§ "What 'external API' means").
5. **Provider → CSI driver → volume** (the dashed arrow back, then the curved arrow to the volume). The provider returns the file contents in its `MountResponse`. The driver writes them into a tmpfs at the target directory, which becomes the Pod's volume, and records the version in a `SecretProviderClassPodStatus`.
6. **Driver → kubelet.** The driver answers `NodePublishVolume` with success. The kubelet then starts the container with that directory mounted at `mountPath`.

**In short:** the driver mounts a volume into the Pod, and that volume holds the secrets pulled from AWS, one file per secret. It appears exactly at the `mountPath` given in the Pod spec. With the slide's `mountPath: /tmp`, the credentials show up as files in `/tmp`:

```
/tmp/db-creds          {"DB_USERNAME":"myuser","DB_PASSWORD":"mypass"}
```

The app reads them like any other file, for example `cat /tmp/db-creds`. Strictly, the driver writes the files into a tmpfs in the Pod's directory on the node, and the container runtime then bind-mounts that directory at `mountPath` when it starts the container. The effect is the same: whatever the image had in `/tmp` is hidden, and `/tmp` now contains only the secret files, read-only. That's why a dedicated path such as `/mnt/secrets` is the better choice (Step 5).

**What happens if you mount at `/tmp`, exactly as the slide does:**

```yaml
volumeMounts:
  - name: db-creds
    mountPath: /tmp
```

1. **It works.** The Pod starts, and `/tmp/db-creds` holds the JSON from AWS. `cat /tmp/db-creds` inside the nginx container prints `{"DB_USERNAME":"myuser","DB_PASSWORD":"mypass"}`.
2. **The container's original `/tmp` is hidden.** A mount covers the directory underneath it, so `ls /tmp` shows only `db-creds`. Anything the image put in `/tmp` is still in the image but can't be seen while the mount is there. Nothing is deleted, and it's per container: other containers in the Pod and the node's own `/tmp` are unaffected.
3. **`/tmp` becomes read-only.** The kubelet makes a container mount read-only if either the volumeMount says `readOnly` or the volume itself is read-only: `ReadOnly: mount.ReadOnly || mustMountRO` in `kubelet_pods.go`, where `mustMountRO` comes from the volume's attributes. For a CSI volume that's its `readOnly` field (`csiMountMgr.GetAttributes` in `csi_mounter.go`) **(code)**. The driver requires `readOnly: true`, so `/tmp` is read-only even though the slide doesn't set `readOnly` on the volumeMount. Any write fails **(not tested)**:

   ```
   $ kubectl exec web -- touch /tmp/x
   touch: cannot touch '/tmp/x': Read-only file system
   ```

**Does that break nginx?** Most likely not **(not tested)**. The official nginx image keeps its temporary files under `/var/cache/nginx`, and none of its startup scripts (`10-listen-on-ipv6…`, `20-envsubst…`, `30-tune-worker…`) use `/tmp`. That's why the demo works.

**Other apps can break,** because many expect a writable `/tmp`:

- **Java:** `java.io.tmpdir` defaults to `/tmp`, and the JVM writes performance data there (`/tmp/hsperfdata_*`). File uploads and temp files then fail.
- **Python:** the `tempfile` module, plus anything that unpacks or caches to `/tmp`.
- **Shell scripts:** anything using `mktemp`.
- **Many web frameworks:** they buffer large uploads to `/tmp`.

These usually fail at runtime rather than at startup, with `Read-only file system` errors, so they're hard to trace back to the secret mount. The slide's `/tmp` is fine for a demo; for real workloads, use a directory of its own such as `/mnt/secrets`.

Two things the slide doesn't show: the secret **never passes through the API server or etcd** (the driver only reads the SPC from it), and every arrow except step 4 stays **on the node**. §8.1, "The secrets chain end to end", traces each of these calls with the real code and the gRPC frames.

**The result** inside the container. The provider writes the whole secret as one file named after `objectName`, plus one file per `jmesPath` alias **(code)**: `secrets_manager_provider.go` appends the full secret, then the JSON entries:

```
/mnt/secrets/db-creds       {"DB_USERNAME":"myuser","DB_PASSWORD":"mypass"}
/mnt/secrets/db-username    myuser
/mnt/secrets/db-password    mypass
```

Without `jmesPath` only the first file exists, and the app has to parse the JSON itself. What happens between the Pod being scheduled and these files appearing (kubelet → driver → provider → STS → Secrets Manager) is traced call by call in §8.1, "The secrets chain end to end".

If you enable sync to a Kubernetes Secret, note that it's tied to Pods: a Pod has to mount the volume before the Secret is created **(docs)**. Cleanup is less simple than the docs suggest: see §8.1.

**The main advantage: credentials no longer live in Kubernetes Secrets.** With a plain Secret, or with ESO, the value is copied into a Secret object, so it sits in etcd and anyone with `get secrets` in the namespace can read it. With the CSI driver, by default no Secret object is created at all:

```
 ESO, or CSI with secretObjects                 CSI driver, default
 ──────────────────────────────                 ───────────────────
 AWS Secrets Manager                            AWS Secrets Manager
        │ copied in, then kept in sync                 │ fetched as the Pod, at Pod start
        ▼                                              │ (and on each rotation poll)
 Kubernetes Secret ── stored in etcd                   ▼
        │             readable via the API      tmpfs volume in the Pod ── nothing in etcd,
        ▼             (get secrets RBAC)               │                   nothing to `get`
 Pod: env var or volume                         Pod: file at /mnt/secrets/<name>
```

What that removes:

- **No copy in etcd.** Backups, etcd snapshots and anyone with direct etcd access no longer hold the value. (Encryption at rest, §5, protects that copy; CSI removes it.)
- **No `get secrets` path.** RBAC on Secrets stops mattering for these values. Access is decided by the cloud's IAM policy for the Pod's ServiceAccount (§ "What 'external API' means").
- **One source of truth.** The value is managed only in the external store (rotation, audit logs, versioning), not copied into each cluster and namespace.
- **No static cloud keys either.** The provider authenticates with the Pod's own token (IRSA, Pod Identity, Workload Identity), so there are no AWS access keys sitting in a Secret just to reach the store.

Two benefits follow from that list:

**1. A smaller attack surface.** Every copy of a secret is somewhere an attacker can find it. Count the places one value lives:

| Where the value can be read | Plain Secret / ESO | CSI, default |
|---|---|---|
| External store | ✓ (ESO) | ✓ |
| etcd, its backups and snapshots | ✓ | ✗ |
| Kubernetes API (`get`/`list`/`watch secrets`, including controllers with cluster-wide Secret access) | ✓ | ✗ |
| Each cluster and namespace that needs it | ✓ one copy each | ✗ fetched on demand |
| Node and Pod (tmpfs file, or env var) | ✓ | ✓ |

CSI removes the middle rows. What's left is the store itself, guarded by IAM, and the running Pod, which needs the value anyway. A leaked kubeconfig with `get secrets`, a stolen etcd backup or an over-privileged controller no longer exposes these values.

**2. Easier compliance and audits.** Security standards and auditors typically ask *where* secrets are stored, *who* can read them, whether every read is *logged*, and whether they are *rotated* **(not tested; check the specific standard)**. With CSI the answers come from one place:

- **Where:** only in the external store, encrypted with keys you control (for example KMS), not also in every cluster's etcd and backups. That keeps the clusters' storage and backups out of the secret-storage question.
- **Who:** the store's IAM policy, per Pod ServiceAccount, instead of Kubernetes RBAC on Secrets plus IAM on the store.
- **Logged:** each read is an API call made as the Pod's identity, so it lands in the store's own audit log (CloudTrail for Secrets Manager, Azure Monitor logs for Key Vault, Cloud Audit Logs for GCP, Vault's audit device). Reads of a Kubernetes Secret only show up if API server audit logging is set to record them.
- **Rotated:** rotation is done once in the store. With CSI rotation enabled, Pods pick up the new value without anyone editing a Secret in each cluster.

What it doesn't remove:

**1. The value is still on the node.** The app has to read the secret, so it ends up as a plain file in the Pod's tmpfs volume, under `/var/lib/kubelet/pods/<pod-uid>/volumes/kubernetes.io~csi/<volume>/mount` on the node. CSI moves the secret out of the cluster's storage, not out of the running workload. Who can still read it:

| Who | How |
|---|---|
| The app | Reads the file, which is the point. If it logs the value or crashes with a core dump, the value goes with it |
| Anyone with `pods/exec` on the Pod | `kubectl exec … cat /mnt/secrets/<name>` |
| Root on the node | Reads the tmpfs directory directly, for every Pod on that node. Root can also dial the provider socket and call `Mount` for any Pod (§ "The secrets chain end to end") **(not tested)** |

What helps: restrict `pods/exec` in RBAC as tightly as `get secrets` used to be, keep privileged and hostPath Pods off nodes running sensitive workloads, and treat a node compromise as exposing every secret mounted on it.

**2. `secretObjects` brings the etcd copy back.** Syncing into a Kubernetes Secret puts the value back in etcd and behind `get secrets`, which undoes the benefits above for that value. It's needed when a consumer can only read a Secret object: env vars via `secretKeyRef`, or an Ingress or Gateway TLS secret. Turn it on per object, for those consumers only. The synced Secret also only exists while some Pod mounts the volume (see "Sync as Kubernetes Secret" in §8.1).

**3. New values don't arrive on their own.**

| Delivery | When a value changes in the store |
|---|---|
| CSI file, rotation off (the driver's default) | Not seen until the Pod is recreated |
| CSI file, rotation on | File rewritten within one poll interval (default `2m`) **(code)** |
| File mounted with `subPath` | Never updated: a `subPath` mount is fixed when the container starts **(docs)** |
| Env var from a synced Secret | Never updated: env vars are set once, at container start |

Even when the file changes, the app has to re-read it. Many apps read config only at startup, so rotation usually also needs a file watch in the app, or a restart triggered by a tool such as Reloader. Plan rotation in the store so the old value stays valid for a while (two active versions), since Pods pick up the new one at different times.

**And it adds one dependency.** Pod start now depends on the external store and the cloud identity service. If Secrets Manager, Entra ID or Vault is unreachable, or the IAM policy is wrong, `NodePublishVolume` fails and the Pod stays in `ContainerCreating`, retried with a `FailedMount` event. A Pod that's already running keeps its files. A plain Secret has no such dependency, because the kubelet reads it from the API server. Calls also count against the store's rate limits and pricing, once per Pod start and once per rotation poll per Pod.

**Which one?** ESO is the usual default, because apps need no changes and env vars keep working. Use CSI when you don't want a copy in etcd. Use the SDK when you want dynamic behaviour or the least exposure, and you own the code.

### 8.1 SecretProviderClass, from the code

This section follows the real code of the driver and its providers. The code and YAML are quoted from these repos:

| Repo | What it is |
|---|---|
| `kubernetes-sigs/secrets-store-csi-driver` | the driver and the CRDs |
| `aws/secrets-store-csi-driver-provider-aws` | AWS provider (ASCP) |
| `Azure/secrets-store-csi-driver-provider-azure` | Azure provider |
| `hashicorp/vault-csi-provider` | Vault provider |
| `GoogleCloudPlatform/secrets-store-csi-driver-provider-gcp` | GCP provider |

#### What it is

A `SecretProviderClass` (SPC) is a namespaced custom resource that says what to fetch, and from which kind of store. It holds no secret values. This is the whole spec (`apis/v1/secretproviderclass_types.go`):

```go
type SecretProviderClassSpec struct {
	// Configuration for provider name
	Provider Provider `json:"provider,omitempty"`
	// Configuration for specific provider
	Parameters    map[string]string `json:"parameters,omitempty"`
	SecretObjects []*SecretObject   `json:"secretObjects,omitempty"`
}
```

Only three fields, and each has one job:

| Field | Job | Read by |
|---|---|---|
| `provider` | picks the plugin | the driver |
| `parameters` | a map of strings, meaning whatever that plugin decides | the provider; the driver just forwards it |
| `secretObjects` | optional copy into a Kubernetes Secret | the driver's sync controller |

The driver doesn't understand `parameters`. That's why it can support AWS, Azure and Vault with one CRD, and why the `objects` format differs in every example below.

#### The three links

The short version:

```
 Pod volume
   driver: secrets-store.csi.k8s.io ───► CSIDriver object (tokens, Pod info)
   secretProviderClass: db-secrets ────► SPC, looked up in the POD's namespace
                                          provider: aws ───► aws.sock on the same node
                                          parameters ──────► merged with Pod info, sent to the provider
```

The same links in more detail:

```
 Pod
  └─ volumes[].csi
       ├─ driver: secrets-store.csi.k8s.io ──────► LINK 1: kubelet finds the CSI driver by this name
       └─ volumeAttributes:
            secretProviderClass: db-secrets ─────► LINK 2: driver gets SPC by name, in the POD's namespace
                                                        │
                                  SecretProviderClass "db-secrets"
                                    provider: aws ─────► LINK 3: driver opens the socket <provider>.sock
                                                        │
                       /etc/kubernetes/secrets-store-csi-providers/aws.sock
                                                        │
                                  provider DaemonSet (same node) ─► the store
```

**Link 2** is a plain lookup using the Pod's namespace, so **an SPC can't be shared across namespaces**. Each namespace needs its own copy (`pkg/secrets-store/utils.go`):

```go
spc := &secretsstorev1.SecretProviderClass{}
spcKey := types.NamespacedName{
	Namespace: namespace,    // the Pod's namespace
	Name:      name,
}
if err := c.Get(ctx, spcKey, spc); err != nil {
	return nil, fmt.Errorf("failed to get secretproviderclass %s/%s, error: %w", namespace, name, err)
}
```

**Link 3** is simply the provider's name plus `.sock` (`pkg/secrets-store/provider_client.go`):

```go
tryPath := filepath.Join(p.socketPaths[k], provider+".sock")
```

So `provider: aws` must match a file `aws.sock`, and the AWS provider creates exactly that in `main.go`: `fmt.Sprintf("%s/aws.sock", *endpointDir)`. The connection has no TLS. The code comment says *"the interface is only secured through filesystem ACLs"*.

#### Why drivers exist at all

Drivers exist so Kubernetes doesn't have to contain code for every storage system and secrets store in the world. Kubernetes defines one standard **interface**, and each vendor writes the plugin that speaks it.

**The problem drivers solve: "in-tree" code.** Originally, support for each cloud's disks was compiled **into Kubernetes itself**, inside the kubelet and controller binaries. The old volume types are still in the 1.34 schema, now marked deprecated. **(lab)** `kubectl explain pod.spec.volumes.awsElasticBlockStore` says:

```
Deprecated: AWSElasticBlockStore is deprecated. All operations for the in-tree
awsElasticBlockStore type are redirected to the ebs.csi.aws.com CSI driver.
```

`azureDisk`, `gcePersistentDisk`, `cinder` (OpenStack) and `vsphereVolume` say the same: each is "redirected to" its CSI driver.

Having that code inside Kubernetes caused real problems **(general knowledge, not tested)**:

| Problem with in-tree code | Effect |
|---|---|
| Every storage vendor's code in Kubernetes' repo | Kubernetes maintainers had to review and ship code for systems they didn't run or understand |
| Tied to Kubernetes releases | A bug fix for Azure disks had to wait for the next Kubernetes release, and for every cluster to upgrade |
| Runs inside the kubelet | A crash or security bug in one vendor's code could take down the kubelet on every node, even nodes that never used that storage |
| Only Kubernetes could use it | Nomad, Cloud Foundry and others had to write their own integration for each vendor |
| A new vendor needed Kubernetes' approval | Smaller storage systems couldn't get support at all |

**The fix: a standard interface plus plugins.** The **Container Storage Interface (CSI)** is a gRPC API spec. Kubernetes calls standard methods. A driver, built and released by the vendor and installed as ordinary Pods, implements them for its own system:

```
                    Kubernetes (knows only CSI)
                              │  gRPC calls defined by the spec
          ┌───────────────────┼─────────────────────┬──────────────────────┐
          ▼                   ▼                     ▼                      ▼
   ebs.csi.aws.com    disk.csi.azure.com    csi.hetzner.cloud    secrets-store.csi.k8s.io
   (AWS's code)       (Microsoft's code)    (Hetzner's code)     (SIG's code)
```

The spec has three groups of methods. A driver implements only what makes sense for it.

| Service | Methods (examples) | Who needs it |
|---|---|---|
| Identity | `GetPluginInfo` | every driver: "who am I" |
| Controller | `CreateVolume`, `DeleteVolume`, `ControllerPublishVolume` (attach a disk to a node) | storage that is created and attached: cloud disks |
| Node | `NodePublishVolume` (mount into the Pod), `NodeUnpublishVolume` | every driver: the actual mount on the node |

**(code)** The Secrets Store driver shows how small a driver can be. It registers all three services (`pkg/secrets-store/server.go`), but the controller methods are empty:

```go
func (cs *controllerServer) CreateVolume(...) (...) {
    return nil, status.Error(codes.Unimplemented, "")
}
func (cs *controllerServer) ControllerPublishVolume(...) (...) {
    return nil, status.Error(codes.Unimplemented, "")
}
```

There's no disk to create or attach. All the real work is in `NodePublishVolume`: fetch the secrets from the provider and write them into a tmpfs on the node. It uses CSI purely as a way to **put files into a Pod at mount time**, which is why it's used inline in the Pod and not through a PVC.

**It's the same idea everywhere in Kubernetes.** Kubernetes keeps its core small and pushes vendor-specific work behind standard interfaces:

| Area | Interface | Plugins (examples) | Where in these notes |
|---|---|---|---|
| Storage | **CSI** | EBS, Azure Disk, Hetzner, Secrets Store | §3, §8.1 |
| Networking | **CNI** | Calico (in the lab), Cilium, Azure CNI | [cloud-kubernetes.md](cloud-kubernetes.md) |
| Container runtime | **CRI** | containerd, CRI-O, cri-dockerd (the lab) | §3: the `image` volume test needed a CRI-O runtime |
| Encryption keys | **KMS plugin** (gRPC over a Unix socket) | AWS KMS, Azure Key Vault, Vault | §5 |
| Secrets in the CSI driver | **provider** (gRPC over `<name>.sock`) | aws, azure, gcp, vault | below |
| Cloud APIs (load balancers, nodes) | **cloud-controller-manager** | one per cloud | [cloud-control-manager.md](cloud-control-manager.md) |

The Secrets Store setup has **two** plugin layers: Kubernetes → CSI → driver, and driver → provider → cloud. That's why a single driver name works for every cloud. The driver plugs into Kubernetes, and the providers plug into the driver.

#### One driver for every cloud, one provider per cloud

**For secrets, there's just one driver name, and each cloud brings its own provider plugin for it.** Other CSI drivers, for disks and file storage, have names of their own.

**(code)** Repositories read:

- `kubernetes-sigs/secrets-store-csi-driver` (the driver)
- `aws/secrets-store-csi-driver-provider-aws`
- `Azure/secrets-store-csi-driver-provider-azure`
- `GoogleCloudPlatform/secrets-store-csi-driver-provider-gcp`
- `hashicorp/vault-csi-provider`

**The Secrets Store CSI driver is one driver for every cloud.** `secrets-store.csi.k8s.io` is the name of **one** driver, the Kubernetes SIG project `kubernetes-sigs/secrets-store-csi-driver`. The name is set by a flag in `cmd/secrets-store-csi-driver/main.go`:

```go
driverName = flag.String("drivername", "secrets-store.csi.k8s.io", "name of the driver")
```

It's also registered in `deploy/csidriver.yaml` (`kind: CSIDriver`, `name: secrets-store.csi.k8s.io`). So for secrets, the `driver:` value in a Pod's `csi` volume is always the same, on AWS, Azure, GCP or Vault.

**What differs per cloud is the provider.** The driver itself knows nothing about AWS or Azure. It hands the work to a provider plugin running on the same node, which it finds by name through a Unix socket (`pkg/secrets-store/provider_client.go`):

```go
// <path>/<plugin_name>.sock
tryPath := filepath.Join(p.socketPaths[k], provider+".sock")
```

The name comes from the **SecretProviderClass**, not from the Pod:

```yaml
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
spec:
  provider: aws          # → driver dials aws.sock
```

| Provider | `provider:` value | Socket on the node (as deployed) | Backing store |
|---|---|---|---|
| AWS | `aws` | `/var/run/secrets-store-csi-providers/aws.sock` | Secrets Manager, Parameter Store |
| Azure | `azure` | `/var/run/secrets-store-csi-providers/azure.sock` | Key Vault |
| GCP | `gcp` | `/etc/kubernetes/secrets-store-csi-providers/gcp.sock` | Secret Manager |
| HashiCorp Vault | `vault` | `/var/run/secrets-store-csi-providers/vault.sock` | Vault |

The driver's own docs (`docs/book/src/introduction.md`) list the supported providers as **Azure, GCP, AWS, Vault, Akeyless, Conjur and OpenBao**. All of them support syncing to a Kubernetes Secret and rotation; only Azure supports Windows. **Hetzner and Scaleway aren't in the list.** Hetzner has no secrets manager (§14). For Scaleway, the usual route is External Secrets Operator (§13).

```
Pod volume:  csi.driver = secrets-store.csi.k8s.io          (always this)
                 │  volumeAttributes.secretProviderClass: my-spc
                 ▼
SecretProviderClass my-spc:  provider: aws | azure | gcp | vault
                 │
                 ▼  driver tries <dir>/<provider>.sock in each of its two node directories:
                    --provider-volume                   = /var/run/secrets-store-csi-providers        (default)
                    --additional-provider-volume-paths  = /etc/kubernetes/secrets-store-csi-providers

 secrets-store CSI driver (DaemonSet Pod on the node)
   │
   ├─gRPC over unix:///var/run/secrets-store-csi-providers/aws.sock ───────► AWS provider ──network──► Secrets Manager / Parameter Store
   │                                          (provider flag --provider-volume=…, writes <dir>/aws.sock)
   │
   ├─gRPC over unix:///var/run/secrets-store-csi-providers/azure.sock ─────► Azure provider ─network──► Key Vault
   │                                          (provider sees it as unix:///provider/azure.sock: hostPath mounted at /provider)
   │
   ├─gRPC over unix:///etc/kubernetes/secrets-store-csi-providers/gcp.sock ► GCP provider ──network──► Secret Manager
   │                                          (provider env TARGET_DIR=/etc/kubernetes/…, PROVIDER_NAME default "gcp")
   │
   └─gRPC over unix:///var/run/secrets-store-csi-providers/vault.sock ─────► Vault provider ─network──► Vault
                                              (provider sees it as /provider/vault.sock: hostPath mounted at /provider)

 All on the same node. No TLS on the socket: "only secured through filesystem ACLs" (driver comment).
 The provider Pods hold the cloud credentials / workload identity; the driver never talks to the cloud.
```

**(code)** Where each path comes from:

| | Socket on the node | Set by |
|---|---|---|
| Driver searches | `/var/run/secrets-store-csi-providers`, then `/etc/kubernetes/secrets-store-csi-providers` | `driver/cmd/secrets-store-csi-driver/main.go`: `provider-volume`, `additional-provider-volume-paths` defaults |
| AWS | `/var/run/secrets-store-csi-providers/aws.sock` | `aws/main.go`: `provider-volume` default + `fmt.Sprintf("%s/aws.sock", ...)`; `deployment/aws-provider-installer.yaml` mounts the same host path |
| Azure | `/var/run/secrets-store-csi-providers/azure.sock` | `azure/deployment/provider-azure-installer.yaml`: `--endpoint=unix:///provider/azure.sock`, hostPath `/var/run/secrets-store-csi-providers` mounted at `/provider`. The code default (`/tmp/azure.sock`) is overridden. Windows: `C:\k\secrets-store-csi-providers` |
| GCP | `/etc/kubernetes/secrets-store-csi-providers/gcp.sock` | `gcp/main.go`: `TARGET_DIR` + `PROVIDER_NAME` (default `gcp`, `vars/vars.go`); `deploy/provider-gcp-plugin.yaml` sets `TARGET_DIR=/etc/kubernetes/secrets-store-csi-providers` |
| Vault | `/var/run/secrets-store-csi-providers/vault.sock` | `vault/deployment/vault-csi-provider.yaml`: `-endpoint=/provider/vault.sock`, hostPath `/var/run/secrets-store-csi-providers` mounted at `/provider`. The code default (`/tmp/vault.sock`) is overridden |

The `/tmp/...` paths in the providers' code are only defaults; the shipped manifests point the sockets into the shared node directory, because that's where the driver looks. GCP is the one provider that uses the driver's *second* directory. The paths are from the providers' own manifests; Helm values or managed add-ons (EKS, AKS, GKE) may change them **(not tested)**.

**Other CSI drivers: one per storage service.** `csi:` volumes in general aren't only for secrets. Each cloud has **one CSI driver per storage service**, not one per cloud, and each driver registers its own name.

**(code)** Each name was confirmed in that project's source (a driver-name constant or its own `kind: CSIDriver` manifest):

| Cloud | Storage service | What it is | CSI driver name | Repo |
|---|---|---|---|---|
| **AWS** | EBS | block disk | `ebs.csi.aws.com` | `kubernetes-sigs/aws-ebs-csi-driver` |
| | EFS | NFS file share | `efs.csi.aws.com` | `kubernetes-sigs/aws-efs-csi-driver` |
| | FSx (Lustre) | high-performance file system | `fsx.csi.aws.com` | `kubernetes-sigs/aws-fsx-csi-driver` |
| | S3 (Mountpoint) | object storage mounted as files | `s3.csi.aws.com` | `awslabs/mountpoint-s3-csi-driver` |
| **Azure** | Managed Disks | block disk | `disk.csi.azure.com` | `kubernetes-sigs/azuredisk-csi-driver` |
| | Azure Files | SMB/NFS file share | `file.csi.azure.com` | `kubernetes-sigs/azurefile-csi-driver` |
| | Blob Storage | object storage (blobfuse/NFS) | `blob.csi.azure.com` | `kubernetes-sigs/blob-csi-driver` |
| **GCP** | Persistent Disk | block disk | `pd.csi.storage.gke.io` | `kubernetes-sigs/gcp-compute-persistent-disk-csi-driver` |
| | Filestore | NFS file share | `filestore.csi.storage.gke.io` (plus a `multishare.` variant) | `kubernetes-sigs/gcp-filestore-csi-driver` |
| | Cloud Storage FUSE | object storage mounted as files | `gcsfuse.csi.storage.gke.io` | `GoogleCloudPlatform/gcs-fuse-csi-driver` |
| **Hetzner** | Volumes | block disk | `csi.hetzner.cloud` | `hetznercloud/csi-driver` |
| **Scaleway** | Block Storage | block disk | `csi.scaleway.com` | `scaleway/scaleway-csi` |

**Why one driver per service, even though CSI is one interface.** CSI is the **shape** of the conversation, but each storage service needs very different code behind it:

- **Different cloud APIs:** EBS uses EC2's `AttachVolume`; EFS uses NFS mount targets; S3 is an HTTP object API.
- **Different mechanics on the node:** a block disk shows up as a device you format and mount; NFS or SMB is a network mount; S3 or GCS goes through a FUSE program.
- **Different sharing rules:** a block disk attaches to **one node** (`ReadWriteOnce`), while a file share can be mounted by many Pods on many nodes (`ReadWriteMany`).
- **Separate teams and release cycles:** a fix to the EFS driver shouldn't require a new EBS driver.

**(code)** The split shows in which drivers implement `ControllerPublishVolume`, the "attach to a node" step:

| Implemented (attaches a disk to the node) | `Unimplemented` (network storage: nothing to attach) |
|---|---|
| EBS, Azure Disk, GCP PD, Hetzner | EFS, S3, Azure Files, Filestore (and the Secrets Store driver) |

**How you pick one.** You rarely write the driver name in a Pod. A **StorageClass** names the driver as its `provisioner`, and a PVC picks the StorageClass:

```yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata: {name: fast-disk}
provisioner: ebs.csi.aws.com          # ← which driver
parameters: {type: gp3}               # ← options that driver understands
---
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata: {name: shared-files}
provisioner: efs.csi.aws.com
parameters: {provisioningMode: efs-ap, fileSystemId: fs-0123456789abcdef0}
```

```yaml
kind: PersistentVolumeClaim
spec:
  storageClassName: shared-files      # → efs.csi.aws.com
  accessModes: [ReadWriteMany]        # many Pods, many nodes
```

The `parameters` are specific to each driver. These values are from AWS docs knowledge, **not tested**.

So storage drivers are normally used through a **StorageClass and PVC**, not written as a `csi:` volume in the Pod. The Secrets Store driver is the odd one out: it's used **inline** in the Pod's `volumes`. `kubectl get csidrivers` lists the drivers a cluster has. **(lab)** The minikube lab has none, which is why a `csi` volume there fails with "driver name … not found in the list of registered CSI drivers" (§3, "Every volume type, written → stored → seen").

#### Where you write the driver, and where the provider

For secrets you write **both**, in different objects: the **driver** in the Pod and the **provider** in the SecretProviderClass. For storage you write **only the driver**, and only in the StorageClass. The Pod never mentions it. **(code)** Both examples below are the projects' own example files.

**Secrets: the driver in the Pod, the provider in the SecretProviderClass.** From `aws/secrets-store-csi-driver-provider-aws`, `examples/ExampleDeployment-IRSA.yaml` (the Pod template):

```yaml
volumes:
- name: secrets-store-inline
  csi:
    driver: secrets-store.csi.k8s.io          # ← the DRIVER: always this, on every cloud
    readOnly: true
    volumeAttributes:
      secretProviderClass: "nginx-irsa-deployment-aws-secrets"   # ← points to the class below
```

`examples/ExampleSecretProviderClass-IRSA.yaml`:

```yaml
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata:
  name: nginx-irsa-deployment-aws-secrets
spec:
  provider: aws                                # ← the PROVIDER: this is where the cloud is chosen
  parameters:
    objects: |
        - objectName: "MySecret"
          objectType: "secretsmanager"
```

Moving from AWS to Azure means changing **only the SecretProviderClass** (`provider: azure` plus Azure's `parameters`). The Pod's `driver:` line stays the same.

**Storage: the driver only in the StorageClass.** From `kubernetes-sigs/aws-ebs-csi-driver`, `examples/kubernetes/dynamic-provisioning/manifests/`:

```yaml
# storageclass.yaml — the ONLY place the driver appears
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: ebs-sc
provisioner: ebs.csi.aws.com                  # ← the DRIVER
volumeBindingMode: WaitForFirstConsumer
```

```yaml
# claim.yaml — names the StorageClass, not the driver
kind: PersistentVolumeClaim
metadata:
  name: ebs-claim
spec:
  accessModes: [ReadWriteOnce]
  storageClassName: ebs-sc
```

```yaml
# pod.yaml — names the claim, not the driver
volumes:
- name: persistent-storage
  persistentVolumeClaim:
    claimName: ebs-claim
```

There's no "provider" for storage. The driver *is* the cloud-specific part, so the chain is shorter:

```
SECRETS:  Pod ──driver: secrets-store.csi.k8s.io──► SecretProviderClass ──provider: aws──► AWS provider ──► Secrets Manager
STORAGE:  Pod ──claimName──► PVC ──storageClassName──► StorageClass ──provisioner: ebs.csi.aws.com──► EBS
```

#### Both chains, at the level of sockets and gRPC

Both paths use the same building blocks: **Unix sockets on the node, with gRPC spoken over them**. They differ in who calls whom. A Unix socket is a file that two processes on the **same machine** use to talk. Containers share one by mounting the same node directory (`hostPath`), or the same `emptyDir` within one Pod.

| Socket | Who listens | Who connects | Protocol |
|---|---|---|---|
| `/var/lib/kubelet/plugins_registry/…` | node-driver-registrar sidecar | **kubelet**, to discover the driver | kubelet plugin-registration gRPC |
| `/var/lib/kubelet/plugins/<driver>/csi.sock` | the CSI driver (node part) | **kubelet** | **CSI gRPC** (`NodePublishVolume` …) |
| `/var/lib/csi/sockets/pluginproxy/csi.sock` | the CSI driver (controller part, EBS) | sidecars in the same Pod | **CSI gRPC** (`CreateVolume`, `ControllerPublishVolume` …) |
| `/var/run/secrets-store-csi-providers/<provider>.sock` | the secrets **provider** | the Secrets Store driver | **provider gRPC** (`Mount`) |

**Secrets: Pod → driver → provider → AWS.** **(code)** From `kubernetes-sigs/secrets-store-csi-driver` (`deploy/`, `pkg/secrets-store/`, `provider/v1alpha1/service.proto`) and `aws/secrets-store-csi-driver-provider-aws`.

```
 0. INSTALL (once per node)
    driver DaemonSet Pod
      ├─ secrets-store container   listens on /csi/csi.sock
      │                            (= node: /var/lib/kubelet/plugins/csi-secrets-store/csi.sock)
      └─ node-driver-registrar     tells kubelet: "driver secrets-store.csi.k8s.io lives at
                                   /var/lib/kubelet/plugins/csi-secrets-store/csi.sock"
                                   (via /var/lib/kubelet/plugins_registry/)
    AWS provider DaemonSet Pod     listens on /var/run/secrets-store-csi-providers/aws.sock
    CSIDriver object: attachRequired: false, podInfoOnMount: true,
                      volumeLifecycleModes: [Ephemeral], requiresRepublish: true

 1. Pod scheduled to node N. Its volume says  csi.driver: secrets-store.csi.k8s.io
    attachRequired: false → no attach step, nothing happens in the control plane

 2. kubelet on N ──CSI gRPC over unix:///var/lib/kubelet/plugins/csi-secrets-store/csi.sock──►
      NodePublishVolume {
        target_path:    /var/lib/kubelet/pods/<uid>/volumes/kubernetes.io~csi/<vol>/mount
        volume_context: secretProviderClass: nginx-irsa-deployment-aws-secrets
                        csi.storage.k8s.io/pod.name, pod.namespace, pod.uid      ← podInfoOnMount
                        csi.storage.k8s.io/serviceAccount.tokens                 ← tokenRequests
      }

 3. driver: mount a tmpfs at target_path                       (nodeserver.go: Mount("tmpfs", …))
    driver ──HTTPS──► API server: GET SecretProviderClass in the Pod's namespace
                      → provider: aws, parameters: objects …

 4. driver ──provider gRPC over unix:///var/run/secrets-store-csi-providers/aws.sock──►
      CSIDriverProvider.Mount(MountRequest {
        attributes:  SPC parameters + pod info + SA tokens (JSON)
        secrets:     nodePublishSecretRef contents, if any
        target_path, permission,
        current_object_version: [] (or last versions, on rotation)
      })

 5. AWS provider ──HTTPS──► AWS STS: AssumeRoleWithWebIdentity(SA token)   (IRSA; stscreds)
                 ──HTTPS──► Secrets Manager: GetSecretValue("MySecret")
    ◄── MountResponse { files: [{path: "MySecret", contents: <bytes>}],
                        object_version: [{id: "MySecret", version: "…"}] }

 6. driver writes the files into the tmpfs, records versions in SecretProviderClassPodStatus
    kubelet bind-mounts target_path into the container at its mountPath → app reads the file

 7. requiresRepublish: true → kubelet calls NodePublishVolume again periodically
    → steps 4–6 repeat with current_object_version → that's how rotation works
```

The gRPC API between driver and provider is small (`provider/v1alpha1/service.proto`):

```protobuf
service CSIDriverProvider {
    rpc Version(VersionRequest) returns (VersionResponse) {}
    rpc Mount(MountRequest) returns (MountResponse) {}
}
message MountRequest  { string attributes = 1; string secrets = 2; string target_path = 3;
                        string permission = 4; repeated ObjectVersion current_object_version = 5; }
message MountResponse { repeated ObjectVersion object_version = 1; ...; repeated File files = 3; }
message File          { string path = 1; ...; bytes contents = 3; }
```

The **provider never writes to the Pod's filesystem**. It returns file contents over gRPC, and the **driver** writes them (in `MountContent`, via `resp.GetFiles()`). Only the provider talks to AWS. Only the driver talks to the kubelet and the API server.

**Storage: PVC → StorageClass → EBS driver → EC2.** **(code)** From `kubernetes-sigs/aws-ebs-csi-driver` (`deploy/kubernetes/base/`, `pkg/driver/node.go`, `pkg/cloud/`). Storage has **two driver parts**: a **controller** Deployment, which talks to the AWS API, and a **node** DaemonSet, which talks to the kubelet. There's also an extra attach step.

```
 0. INSTALL
    ebs-csi-controller Deployment (1+ replicas, anywhere)
      ├─ ebs-plugin        listens on unix:///var/lib/csi/sockets/pluginproxy/csi.sock   (emptyDir, shared in the Pod)
      ├─ csi-provisioner ─┐
      ├─ csi-attacher    ─┼─ sidecars: watch Kubernetes objects, call ebs-plugin over that socket
      ├─ csi-resizer     ─┤
      └─ csi-snapshotter ─┘
    ebs-csi-node DaemonSet (every node)
      ├─ ebs-plugin              listens on /csi/csi.sock = node: /var/lib/kubelet/plugins/ebs.csi.aws.com/csi.sock
      └─ node-driver-registrar   registers that path with the kubelet (via plugins_registry/)
    CSIDriver object: attachRequired: true, podInfoOnMount: false

 1. PROVISION   PVC ebs-claim (storageClassName: ebs-sc → provisioner: ebs.csi.aws.com)
    csi-provisioner (watches PVCs whose StorageClass provisioner is its driver)
      ──CSI gRPC over pluginproxy/csi.sock──► CreateVolume{name, capacity, parameters}
    ebs-plugin ──HTTPS──► EC2 CreateVolume                         (ec2.CreateVolumeInput)
    csi-provisioner creates the PersistentVolume, binds it to the PVC

 2. ATTACH      Pod using the PVC scheduled to node N
    attach-detach controller (in kube-controller-manager) creates a VolumeAttachment
    csi-attacher (watches VolumeAttachments)
      ──CSI gRPC over pluginproxy/csi.sock──► ControllerPublishVolume{volume_id, node_id}
    ebs-plugin ──HTTPS──► EC2 AttachVolume → disk appears on node N as a device

 3. MOUNT       kubelet on N
      ──CSI gRPC over unix:///var/lib/kubelet/plugins/ebs.csi.aws.com/csi.sock──►
      NodeStageVolume   → format if needed, mount device at a global node path
                          (node.go: FormatAndMountSensitiveWithFormatOptions)
      NodePublishVolume → bind-mount that path into the Pod's volume dir
                          (node.go: mountOptions := []string{"bind"})
    kubelet bind-mounts it into the container → app sees a filesystem

 Delete: the same in reverse (NodeUnpublish/Unstage → ControllerUnpublish/EC2 DetachVolume
         → DeleteVolume/EC2 DeleteVolume, if the reclaim policy is Delete)
```

| | Secrets Store | EBS |
|---|---|---|
| Driver parts | node only (DaemonSet) | **controller** (Deployment) + **node** (DaemonSet) |
| CSIDriver `attachRequired` | `false`: no attach step | `true`: VolumeAttachment → `ControllerPublishVolume` |
| CSIDriver `podInfoOnMount` | `true`: Pod name/namespace/UID and SA tokens sent to the driver | `false`: the disk doesn't care which Pod |
| Who talks to AWS | the **provider**, from every node | the **controller** plugin only |
| AWS calls | STS + Secrets Manager `GetSecretValue` | EC2 `CreateVolume`, `AttachVolume`, `DetachVolume`, `DeleteVolume` |
| Extra gRPC hop | driver → provider (`Mount` over `aws.sock`) | none: the driver is the cloud code |
| Node CSI calls | `NodePublishVolume` only, repeated for rotation | `NodeStageVolume` then `NodePublishVolume` |

**Labels:**

- The socket paths, CSIDriver fields, gRPC messages and EC2 calls are **(code)**, from the repos above.
- Who triggers what (the sidecars watching PVCs and VolumeAttachments, the attach-detach controller creating VolumeAttachments, and kubelet discovery through `plugins_registry`) is the standard CSI design. It wasn't traced in those components' own code here: **(docs; not traced)**.
- **(lab)** On minikube, `/var/lib/kubelet/plugins/` and `/var/lib/kubelet/plugins_registry/` are both **empty**, matching `kubectl get csidrivers` showing none.

#### The secrets chain end to end: every socket, every gRPC call

Three gRPC conversations happen on the node, each over its own Unix socket. In each one, one side **listens** (it's the gRPC server and creates the socket file) and the other **dials** (it's the client):

```
                        node N (all of this is on one machine)
 ┌──────────────────────────────────────────────────────────────────────────────────────────────┐
 │                                                                                                │
 │  kubelet ─────① registration────► /var/lib/kubelet/plugins_registry/secrets-store.csi.k8s.io-reg.sock
 │    │           (kubelet dials)        ▲ listens: node-driver-registrar (sidecar in the driver Pod)
 │    │                                                                                           │
 │    └──────────② CSI────────────────► /var/lib/kubelet/plugins/csi-secrets-store/csi.sock        │
 │                (kubelet dials)        ▲ listens: secrets-store container (the driver)          │
 │                                       │   the container sees it as /csi/csi.sock               │
 │                                       │                                                        │
 │  driver ──────③ provider─────────► /var/run/secrets-store-csi-providers/aws.sock              │
 │                (driver dials)         ▲ listens: AWS provider Pod                              │
 │                                                                                                │
 └──────────────────────────────────────────────────────────────────────────────────────────────┘
        driver ──HTTPS──► API server  (read the SecretProviderClass, write SecretProviderClassPodStatus)
      provider ──HTTPS──► AWS STS + Secrets Manager
```

| # | Socket on the node | Server (listens) | Client (dials) | gRPC service | Calls |
|---|---|---|---|---|---|
| ① | `/var/lib/kubelet/plugins_registry/<driver>-reg.sock` | node-driver-registrar | kubelet | `pluginregistration.Registration` | `GetInfo`, `NotifyRegistrationStatus` |
| ② | `/var/lib/kubelet/plugins/csi-secrets-store/csi.sock` | the driver | kubelet (also the liveness-probe sidecar) | CSI `Identity` + `Node` | `GetPluginInfo`, `Probe`, `NodeGetInfo`, `NodePublishVolume`, `NodeUnpublishVolume` |
| ③ | `/var/run/secrets-store-csi-providers/<provider>.sock` | the provider | the driver | `v1alpha1.CSIDriverProvider` | `Version`, `Mount` |

**(code)** The listeners and dialers:

- The driver listens with `net.Listen("unix", …)` and registers the CSI Identity and Node servers (`pkg/secrets-store/server.go`). It deletes a stale socket file first.
- The AWS and Vault providers listen the same way (`net.Listen("unix", endpoint)` in their `main.go`).
- The driver dials the provider with `grpc.NewClient("unix:"+socketPath, …)` (`provider_client.go`).
- The kubelet dials the registration socket with a 10-second timeout (`pluginmanager/operationexecutor/operation_generator.go`).

The `-reg.sock` file name is the registrar's convention **(docs; not traced)**.

**Step ①: the driver introduces itself (once, when the driver Pod starts).** **(code: kubelet `operation_generator.go`, `csi_plugin.go`; registration proto `pluginregistration/v1/api.proto`)**

```
 kubelet's plugin watcher sees a new file in /var/lib/kubelet/plugins_registry/
 kubelet ──dial──► …/secrets-store.csi.k8s.io-reg.sock
   GetInfo(InfoRequest{})
   ◄── PluginInfo { type: "CSIPlugin",
                    name: "secrets-store.csi.k8s.io",
                    endpoint: "/var/lib/kubelet/plugins/csi-secrets-store/csi.sock",   ← from --kubelet-registration-path
                    supported_versions: [...] }
 kubelet ──dial──► /var/lib/kubelet/plugins/csi-secrets-store/csi.sock               ← now socket ②
   NodeGetInfo()  ◄── { node_id: "<node name>" }                                      (driver: --nodeid)
   → kubelet adds the driver to the CSINode object for this node
 kubelet ──► registrar: NotifyRegistrationStatus{ plugin_registered: true }
```

The registrar sidecar exists only to answer step ①. Its real job is to tell the kubelet where socket ② is. The driver's own socket path is `/csi/csi.sock` inside the container, and `/var/lib/kubelet/plugins/csi-secrets-store/csi.sock` on the node, through a `hostPath` volume.

**Step ②: the kubelet asks the driver to mount (each Pod start).** **(code: kubelet `csi_mounter.go`, `csi_util.go`, `csi_plugin.go`; driver `nodeserver.go`)**

```
 kubelet ──► csi.sock
   NodePublishVolume {
     volume_id:      "csi-" + sha256(podUID + volumeName)        ← makeVolumeHandle: inline volumes have no PV
     target_path:    /var/lib/kubelet/pods/<uid>/volumes/kubernetes.io~csi/<vol>/mount
     readonly:       true                                        ← the driver rejects false
     volume_context: secretProviderClass: db-secrets             ← the Pod's volumeAttributes
                     csi.storage.k8s.io/pod.name / pod.namespace / pod.uid / serviceAccount.name
                     csi.storage.k8s.io/ephemeral: "true"         ← podInfoOnMount: true
                     csi.storage.k8s.io/serviceAccount.tokens: {"<audience>": {"token": …, "expirationTimestamp": …}}
                                                                 ← tokenRequests in the CSIDriver object
     secrets:        nodePublishSecretRef contents, if set
   }
```

There's no `NodeStageVolume` before this, even though the driver advertises `STAGE_UNSTAGE_VOLUME`. The kubelet's `CanDeviceMount` returns `false` for `Ephemeral` volumes, so inline volumes skip staging. There's no controller call either (`attachRequired: false`).

The driver then works inside that one call. It creates the target directory, mounts a **tmpfs** there, reads the SecretProviderClass from the API server, and merges `spec.parameters` with the whole `volume_context` (`maps.Copy(parameters, attrib)`). Then it opens socket ③.

**Step ③: the driver asks the provider for the files.** **(code: `provider_client.go`)**

```go
// 1. find the socket: try each directory, first one that exists wins
tryPath := filepath.Join(p.socketPaths[k], provider+".sock")   // provider = spec.provider in the SPC
if _, err := os.Stat(tryPath); err == nil { socketPath = tryPath; break }
// 2. dial it, once per provider; the connection is cached in p.clients[provider]
conn, err := grpc.NewClient("unix:"+socketPath,
    grpc.WithAuthority("localhost"),
    grpc.WithTransportCredentials(insecure.NewCredentials()),   // "only secured through filesystem ACLs"
    grpc.WithDefaultServiceConfig(ServiceConfig))               // waitForReady, retry UNAVAILABLE up to 3×
// 3. call Mount, then write what comes back
resp, err := client.Mount(ctx, &v1alpha1.MountRequest{Attributes: …, Secrets: …, TargetPath: …,
                                                      Permission: …, CurrentObjectVersion: …})
fileutil.WritePayloads(targetPath, resp.GetFiles())
```

- `attributes`, `secrets` and `permission` are all **JSON strings** inside the protobuf message. The driver `json.Marshal`s the merged map, the `nodePublishSecretRef` Secret and the file mode. Each provider then `json.Unmarshal`s `attributes` to get its `objects`, the Pod name and the token.
- If no `<provider>.sock` file exists, the error is `provider not found` (`errProviderNotFound`). The provider name must match `^[a-zA-Z0-9_-]{0,30}$`.
- A response bigger than 4 MiB fails, with a hint to raise `--max-call-recv-msg-size`.
- If `--provider-health-check` is on, `Version` is called every `--provider-health-check-interval` as a health check. `Mount` is the only call that carries secrets.

The provider does the cloud work, AWS here: STS with the Pod's token, then `GetSecretValue`. It returns `MountResponse{files, object_version}`. The driver writes the files into the tmpfs and records the versions in a `SecretProviderClassPodStatus`. Then it returns an empty `NodePublishVolumeResponse` to the kubelet, which starts the container with that directory mounted.

The driver can create a mount the kubelet sees because the driver container mounts `/var/lib/kubelet/pods` with `mountPropagation: Bidirectional` (`deploy/secrets-store-csi-driver.yaml`). The tmpfs it mounts inside its own container propagates back to the node.

**Later calls on the same sockets:**

- **Rotation:** with `requiresRepublish: true` on the CSIDriver, the kubelet calls `NodePublishVolume` again periodically. `deploy/csidriver.yaml` always sets it; the Helm chart sets it to the value of `enableSecretRotation`. With `--enable-secret-rotation`, the driver skips the call until `--rotation-poll-interval` has passed since the last write. Otherwise it repeats ③ with `current_object_version` set to the last versions. With rotation off, an already-populated mount is skipped (`"target path is already mounted and populated"`).
- **Pod deleted:** the kubelet calls `NodeUnpublishVolume`. The driver deletes the files and unmounts the tmpfs. **The provider isn't called**: there's no `Unmount` RPC in `service.proto`.

**What actually crosses the socket.** **(lab)** This is a local demo, not the real driver. A Python fake provider implements the real `service.proto`. A client follows `provider_client.go`: it looks for `<dir>/demo.sock`, dials `unix:`, then calls `Version` and `Mount` twice, the second time with `current_object_version` set. A tap between them decodes each HTTP/2 frame. It ran in a throwaway `python:3.12-slim` container with grpcio 1.66.1, with the sockets in the same directory the real driver uses:

```
$ ls -l /var/run/secrets-store-csi-providers/
srwxr-xr-x 1 root root 0 … demo.sock                ← "s" = a socket file, size 0: nothing is stored in it

  driver→provider  connection preface 'PRI * HTTP/2.0…'          ← gRPC is HTTP/2, here over the Unix socket
  driver→provider  SETTINGS  stream 0  (36 bytes)
  provider→driver  SETTINGS  stream 0  (30 bytes)
  provider→driver  SETTINGS  stream 0  flags=0x1 (ACK)
  driver→provider  SETTINGS  stream 0  flags=0x1 (ACK)

  driver→provider  HEADERS  stream 1  :path: /v1alpha1.CSIDriverProvider/Version | :authority: localhost
                                      | :method: POST | :scheme: http | content-type: application/grpc
  driver→provider  DATA     stream 1  gRPC prefix compressed=0 length=10  → VersionRequest { version: "v1alpha1" }
  provider→driver  HEADERS  stream 1  :status: 200 | content-type: application/grpc
  provider→driver  DATA     stream 1  length=30  → VersionResponse { version: "v1alpha1" runtime_name: "demo-provider" … }
  provider→driver  HEADERS  stream 1  grpc-status: 0                    ← trailers: 0 = OK

  driver→provider  HEADERS  stream 3  :path: /v1alpha1.CSIDriverProvider/Mount | :authority: localhost | …
  driver→provider  DATA     stream 3  length=524  → MountRequest { attributes: "{\"objects\": \"[\\\"db-password\\\"]\",
                                       \"secretProviderClass\": \"db-secrets\", \"csi.storage.k8s.io/pod.name\": \"web-0\",
                                       \"csi.storage.k8s.io/pod.namespace\": \"shop\", …
                                       \"csi.storage.k8s.io/serviceAccount.tokens\": \"{\\\"sts.amazonaws.com\\\": …" … }
  provider→driver  HEADERS  stream 3  :status: 200 | content-type: application/grpc
  provider→driver  DATA     stream 3  length=48   → MountResponse { object_version { id: "db-password" version: "v1" }
                                                                    files { path: "db-password" mode: 420 contents: "s3cr3t-v1" } }
  provider→driver  HEADERS  stream 3  grpc-status: 0

  driver→provider  HEADERS  stream 5  :path: /v1alpha1.CSIDriverProvider/Mount
  driver→provider  DATA     stream 5  length=543  → MountRequest { … current_object_version { id: "db-password" version: "v1" } }
  …
[driver] …/volumes/kubernetes.io~csi/secrets/mount/db-password = s3cr3t-v1
```

Reading it:

- **One connection, many calls.** Each RPC is a new HTTP/2 **stream** (1, 3, 5) on the same socket connection. That's the driver's cached `p.clients[provider]`.
- **The method is just a URL path:** `/<package>.<service>/<method>`, from `package v1alpha1; service CSIDriverProvider`.
- **`:authority: localhost`** comes from `grpc.WithAuthority("localhost")`. A Unix socket has no hostname.
- **`:scheme: http`, not https:** there's no TLS. Anyone who can open the socket file can call `Mount` for any Pod. The only protection is who can reach that `hostPath` directory on the node: by default only root, and any Pod allowed to mount it **(not tested)**.
- **Each message is a 5-byte gRPC prefix** (1 byte compressed flag, 4 bytes length) followed by the protobuf bytes. `VersionRequest{version:"v1alpha1"}` is 10 bytes: `0a 08` (field 1, length 8) plus `v1alpha1`.
- **The secret value travels in plain bytes** in `MountResponse.files[].contents`, over a socket that never leaves the node. It goes from there straight into the tmpfs.
- **The status is in trailers.** `grpc-status: 0` is sent after the data, as a second HEADERS frame. A provider error comes back either as a non-zero `grpc-status`, or as `MountResponse.error.code` with status 0. The driver checks both (`MountContent`).

The demo scripts are a provider, a driver-side client and the frame tap, about 100 lines of Python together. The cloud call is replaced by a fixed value.

#### What "external API" means: the calls behind `Mount`

The one-line summary of the driver is: *it gets secrets from external APIs and mounts them into containers as volumes.* Each part of that sentence needs unpacking.

**"External API" means the secret store's own network API,** reached over the internet or a private endpoint: AWS Secrets Manager or Parameter Store, Azure Key Vault, GCP Secret Manager, or a Vault server. It is not a Kubernetes API, and **the driver never calls it.** The driver only talks gRPC to the provider over the Unix socket (③ above). The provider, inside its `Mount` handler, is the one that makes the network calls, using the cloud's normal SDK.

**Is it a GET request?** Sometimes. It's always two steps, an identity exchange then a read, and only some stores use a plain HTTP GET for the read:

```
provider.Mount(attributes, …)
   │  attributes carry the Pod's ServiceAccount token (csi.storage.k8s.io/serviceAccount.tokens)
   │
   ├─ 1. trade the Pod's token for a cloud credential ──► the cloud's identity service
   │
   └─ 2. read each object with that credential ──────────► the secret store's API
            └─ values come back in MountResponse.files → driver writes them to the tmpfs
```

| Provider | 1. Identity exchange | 2. Read call | On the wire |
|---|---|---|---|
| AWS | IRSA: STS `AssumeRoleWithWebIdentity` with the Pod's token (`stscreds.NewWebIdentityRoleProvider`). Pod Identity: plain HTTP to the node agent at `http://169.254.170.23/v1/credentials` **(code)** | Secrets Manager `DescribeSecret` then `GetSecretValue`; Parameter Store `GetParameters` **(code)** | HTTPS **POST** to `secretsmanager.<region>.amazonaws.com` with a JSON body and an `X-Amz-Target: secretsmanager.GetSecretValue` header, signed with SigV4. AWS's JSON APIs name the action in a header, not the URL **(docs)** |
| Azure | Workload Identity: the Pod's token (audience `api://AzureADTokenExchange`) as a client assertion to Entra ID (`azidentity.ClientAssertionCredential`) **(code)**, an HTTPS POST to the tenant's token endpoint **(docs)** | `azsecrets` `GetSecret(name, version)`, or `GetKey` / `GetCertificate` **(code)** | HTTPS **GET** `https://<vault>.vault.azure.net/secrets/<name>/<version>?api-version=…` with `Authorization: Bearer <token>` **(docs)** |
| GCP | Pod's token → `securetoken.googleapis.com/v1/identitybindingtoken`, then IAM Credentials `GenerateAccessToken` for a Google service account **(code)** | Secret Manager `AccessSecretVersion` **(code)** | **gRPC** over TLS to `secretmanager.googleapis.com:443`, not REST. It's the same kind of HTTP/2 + protobuf call as ③, but across the network **(code)** |
| Vault | HTTPS **POST** `/v1/auth/<mount>/login` with `{jwt, role}`; Vault checks the JWT with Kubernetes and returns a Vault token. If the mount request has no token, the provider creates one for the Pod's ServiceAccount itself (TokenRequest, 15 minutes) **(code)** | `RequestSecret` on the configured `secretPath` **(code)** | HTTPS **GET** `/v1/<secretPath>` with the Vault token by default. The SPC can set another `method` and a body (`secretArgs`), for example to POST to a dynamic-secrets endpoint **(code)** |

So the read is a GET for Azure and Vault, a signed POST for AWS, and a gRPC call for GCP. Every one is authenticated **as the Pod**, not as the driver or the node. That's why the cloud's IAM policy, not Kubernetes RBAC, decides whether a Pod can read a secret.

**"Synchronizes" is weaker than it sounds.**

| What | When | Default |
|---|---|---|
| Fetch and mount | Once, in `NodePublishVolume`, before the container starts | Always |
| Fetch again (rotation) | The driver calls `Mount` again every `--rotation-poll-interval` and rewrites the files | Off in the driver binary: `--enable-secret-rotation=false`, interval `2m` **(code)** |
| Copy into a Kubernetes Secret | Only if the SPC has `secretObjects`, and only while a Pod mounts the volume | Off |

Without rotation, a value changed in the store isn't seen until the Pod is recreated. With rotation, each poll costs one identity exchange (unless cached) plus one read per object, for every Pod that mounts the volume. On a large cluster, that's the number to compare with the store's API rate limits and per-call pricing **(not tested)**.

#### One Pod start, step by step

```
 kubelet                    CSI driver (node)               provider (node)            store
    │                              │                              │                      │
    │ 1 NodePublishVolume          │                              │                      │
    │   Pod info + tokens ────────►│                              │                      │
    │                              │ 2 read SPC from the API      │                      │
    │                              │ 3 merge parameters + Pod info│                      │
    │                              │ 4 mount tmpfs on target path │                      │
    │                              │ 5 Mount gRPC ───────────────►│                      │
    │                              │                              │ 6 log in, fetch ────►│
    │                              │                              │◄──────── values ─────│
    │                              │◄──────── 7 files + versions ─│                      │
    │                              │ 8 write files atomically     │                      │
    │                              │ 9 create SPCPS status object │                      │
    │◄───────── 10 done ───────────│                              │                      │
    │ 11 start containers
```

The code for steps 2 to 5 is in `NodePublishVolume` (`pkg/secrets-store/nodeserver.go`), trimmed here:

```go
secretProviderClass := attrib[secretProviderClassField]   // from the Pod's volumeAttributes
...
spc, err := getSecretProviderItem(ctx, ns.client, secretProviderClass, podNamespace)  // step 2
provider, err := getProviderFromSPC(spc)
parameters, err = getParametersFromSPC(spc)
// send all the volume attributes sent from kubelet to the provider
maps.Copy(parameters, attrib)                                                         // step 3

serviceAccountTokens := getServiceAccountTokens(req)
if len(serviceAccountTokens) > 0 {
	parameters[csiPodServiceAccountTokens] = serviceAccountTokens
}
...
err = ns.mounter.Mount("tmpfs", targetPath, "tmpfs", []string{})                      // step 4
...
ns.mountSecretsStoreObjectContent(ctx, providerName, string(parametersStr), ...)      // step 5
```

Step 3 is what connects everything: **the SPC parameters and the Pod's details are merged into one map**. `maps.Copy` runs after the SPC is read, so the provider receives both what to fetch and who is asking.

Step 5 is one gRPC call, defined in `provider/v1alpha1/service.proto`. Every provider implements this same interface:

```protobuf
service CSIDriverProvider {
    rpc Version(VersionRequest) returns (VersionResponse) {}
    rpc Mount(MountRequest) returns (MountResponse) {}
}
message MountRequest {
    string attributes = 1;    // the parameters field, plus Pod info, as a JSON string
    string secrets = 2;       // content of the nodePublishSecretRef Secret
    ...
}
message MountResponse {
    repeated ObjectVersion object_version = 1;
    Error error = 2;
    repeated File files = 3;  // path, mode, contents
}
```

The tmpfs is mounted **before** the fetch, so secret values go straight to RAM. The files are then written with `AtomicWriter`, the same symlink swap as the Secret volume in §3.

#### Where the Pod's identity comes from

The provider needs the identity of the Pod, not its own. That comes from the **`CSIDriver` object**, a cluster-wide setting (`deploy/csidriver.yaml`, plus `tokenRequests` from the chart):

```yaml
apiVersion: storage.k8s.io/v1
kind: CSIDriver
metadata:
  name: secrets-store.csi.k8s.io    # LINK 1 points here
spec:
  podInfoOnMount: true              # kubelet passes Pod name, namespace, UID, ServiceAccount
  attachRequired: false
  volumeLifecycleModes:
  - Ephemeral
  requiresRepublish: true           # kubelet calls NodePublishVolume repeatedly (rotation)
  tokenRequests:                    # kubelet creates a token for the Pod's ServiceAccount
  - audience: sts.amazonaws.com
  - audience: pods.eks.amazonaws.com
```

```
 tokenRequests audience ──► kubelet asks for a token: Pod's ServiceAccount, that audience
                        ──► driver forwards it in parameters
                        ──► provider picks the token for its audience
                        ──► exchanges it with the cloud
```

So the **Pod's app doesn't log in to the cloud. The provider does, using the Pod's token.** The SPC doesn't grant access: permission still lives in the cloud or Vault.

The driver's chart defaults to `tokenRequests: []`. Each provider's chart sets its own audiences, but if you install the driver separately you must add them. For that case, the AWS README says so explicitly.

| Provider | Audience | Who chooses the identity |
|---|---|---|
| AWS, Pod Identity | `pods.eks.amazonaws.com` | EKS association on the ServiceAccount |
| AWS, IRSA | `sts.amazonaws.com` | `eks.amazonaws.com/role-arn` annotation on the ServiceAccount, read by the provider |
| Azure, Workload ID | `api://AzureADTokenExchange` | `clientID` **in the SPC** |
| Vault | SPC's `audience`, default `vault` | `roleName` **in the SPC**, checked by Vault |

#### Example 1: AWS with Pod Identity

From `examples/ExampleSecretProviderClass-PodIdentity.yaml` and `ExampleDeployment-PodIdentity.yaml` in the AWS repo:

```yaml
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata:
  name: nginx-pod-identity-deployment-aws-secrets   # ◄──────────────────────┐
spec:                                                                        │
  provider: aws                                     # aws.sock               │
  parameters:                                                                │
    objects: |                                                               │
      - objectName: "MySecret"                      # name in Secrets Manager│
        objectType: "secretsmanager"                # or ssmparameter        │
    usePodIdentity: "true"                          # default is IRSA        │
---                                                                          │
apiVersion: apps/v1                                                          │
kind: Deployment                                                             │
metadata:                                                                    │
  name: nginx-pod-identity-deployment                                        │
spec:                                                                        │
  template:                                                                  │
    spec:                                                                    │
      serviceAccountName: nginx-pod-identity-deployment-sa                   │
      volumes:                                                               │
        - name: secrets-store-inline    # ◄───────────┐                      │
          csi:                                        │                      │
            driver: secrets-store.csi.k8s.io          │                      │
            readOnly: true                            │                      │
            volumeAttributes:                         │                      │
              secretProviderClass: "nginx-pod-identity-deployment-aws-secrets"
      containers:                                     │
        - name: nginx-pod-identity-deployment         │
          image: nginx                                │
          volumeMounts:                               │
            - name: secrets-store-inline  # ──────────┘
              mountPath: "/mnt/secrets-store"
              readOnly: true
```

The result is the file `/mnt/secrets-store/MySecret`.

The provider's side (`server/server.go`, trimmed) shows each SPC parameter being used:

```go
region := attrib[regionAttrib]                       // parameters.region
usePodIdentityStr := attrib[usePodIdentityAttrib]    // parameters.usePodIdentity
serviceAccountTokens := attrib[serviceAccountTokensAttrib]  // added by the driver
...
// Default to use IRSA if usePodIdentity parameter is not set in the mount request
usePodIdentity := false
...
token, roleArn, err := s.resolveCredentials(ctx, usePodIdentity, parsedTokens, nameSpace, svcAcct)
...
descriptors, err := provider.NewSecretDescriptorList(mountDir, translate, attrib[secProvAttrib], regions)
```

```
 usePodIdentity: "true"  ─► token for pods.eks.amazonaws.com ─► Pod Identity Agent ─► credentials
 (unset or "false")      ─► token for sts.amazonaws.com
                            + role ARN from the ServiceAccount annotation ─► STS ─► credentials
```

#### Example 2: Azure with Workload ID

From the Azure repo's `workload-identity-mode.md`. Here the identity is in the SPC:

```yaml
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata:
  name: azure-kvname-wi
spec:
  provider: azure
  parameters:
    usePodIdentity: "false"
    clientID: "<client id of the application or user-assigned managed identity>"
    keyvaultName: "kvname"
    objects:  |
      array:
        - |
          objectName: secret1
          objectType: secret        # object types: secret, key or cert
          objectVersion: ""         # default to latest if empty
        - |
          objectName: key1
          objectType: key
          objectVersion: ""
    tenantID: "tid"
---
kind: Pod
apiVersion: v1
metadata:
  name: busybox-secrets-store-inline-wi
spec:
  serviceAccountName: ${SERVICE_ACCOUNT_NAME}    # must match the federated credential's subject
  containers:
    - name: busybox
      image: registry.k8s.io/e2e-test-images/busybox:1.29-4
      command: ["/bin/sleep", "10000"]
      volumeMounts:
      - name: secrets-store01-inline
        mountPath: "/mnt/secrets-store"
        readOnly: true
  volumes:
    - name: secrets-store01-inline
      csi:
        driver: secrets-store.csi.k8s.io
        readOnly: true
        volumeAttributes:
          secretProviderClass: "azure-kvname-wi"
```

```
 SPC clientID ───────────► which managed identity to become
 Pod serviceAccountName ─► which ServiceAccount the token is for
 Federated credential ───► allows that ServiceAccount to become that identity
 Role on the vault ──────► allows that identity to read the secrets

 All four must agree, or the mount fails.
```

Two details that are easy to get wrong:

- **This Pod has no `azure.workload.identity/use` label.** That label makes the webhook set up the *app's* SDK (§12). Here the *provider* does the login, using the `tokenRequests` token. Add the label only if the app also calls Azure itself.
- **Use `tenantID`, not `tenantId`.** The code logs `tenantId` as deprecated and reads `tenantID` first. Older examples, including this repo's `service-principal` one, still use the old spelling.

The provider's code shows why each field is required (`pkg/provider/provider.go`):

```go
if keyvaultName == "" {
	return nil, fmt.Errorf("keyvaultName is not provided")
}
if tenantID == "" {
	return nil, fmt.Errorf("tenantId is not provided")
}
```

#### Example 3: Vault, for self-hosted setups like Hetzner

From `test/bats/configs/vault-kv-secretproviderclass.yaml` in the Vault repo. This is the example for clouds without their own provider:

```yaml
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata:
  name: vault-kv
spec:
  provider: vault
  parameters:
    roleName: "kv-role"             # Vault role that decides what this ServiceAccount may read
    audience: "vault"               # token audience, and must match the Vault role
    objects: |
      - objectName: "secret-1"      # becomes the file name
        secretPath: "secret/data/kv1"
        secretKey: "bar1"           # field inside that Vault secret
        filePermission: 0624
      - objectName: "secret-2"
        secretPath: "secret/data/kv2"
        secretKey: "bar2"
```

Notice how different `objects` is from AWS and Azure: `secretPath` and `secretKey` don't exist there. The same `objects` key means something different to each provider, as §8.1's opening explained.

The provider logs in to Vault with the Pod's token (`internal/auth/kubernetes_jwt.go`):

```go
jwt := k.params.PodInfo.ServiceAccountToken
if jwt == "" {
	// "no suitable token found in mount request, using self-generated service account JWT"
	jwt, err = k.createJWTToken(ctx, k.params.PodInfo, k.params.Audience)
}
...
return fmt.Sprintf("/v1/auth/%s/login", mountPath), map[string]string{
	"jwt":  jwt,
	"role": k.params.VaultRoleName,
}, nil
```

If `tokenRequests` is missing, the Vault provider creates the token itself. That's why its manifest grants it `create` on `serviceaccounts/token` cluster-wide. AWS has no such fallback: it fails with an error instead.

```
 Pod's ServiceAccount token + roleName ─► Vault /v1/auth/kubernetes/login
                                          Vault checks the token with Kubernetes
                                          Vault checks the role allows this ServiceAccount
                                       ◄─ Vault token ─► read secret/data/kv1, field bar1
                                                       ─► file secret-1
```

`vaultAddress` isn't set here, so the provider uses its own default (the `--vault-addr` flag or `VAULT_ADDR`). Example 4 sets it per SPC.

#### Example 4: syncing to a Kubernetes Secret, for env vars

Env vars can only come from a Secret or ConfigMap. So getting a store's value into an env var takes a sync. From the Vault repo's `vault-kv-sync-secretproviderclass.yaml` and `nginx-kv-env-var.yaml`, trimmed:

```yaml
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata:
  name: vault-kv-sync
spec:
  provider: vault
  secretObjects:
  - secretName: kvsecret                # the Kubernetes Secret to create
    type: Opaque
    data:
    - objectName: secret-2              # must match a FILE in the mount (see below)
      key: username                     # key inside the Kubernetes Secret
  parameters:
    roleName: "kv-role"
    audience: "vault"
    vaultAddress: https://vault:8200
    objects: |
      - objectName: "secret-2"          # creates the file "secret-2"
        secretPath: "v1/secret/data/kv-sync2"
        secretKey: "bar2"
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nginx-deployment
spec:
  template:
    spec:
      serviceAccountName: nginx-kv
      containers:
      - image: docker.mirror.hashicorp.services/nginxinc/nginx-unprivileged
        name: nginx
        env:
        - name: SECRET_USERNAME
          valueFrom:
            secretKeyRef:
              name: kvsecret            # the synced Secret
              key: username
        volumeMounts:                   # REQUIRED, even if the app never reads the file
        - name: secret-volume
          mountPath: "/mnt/secrets-store"
          readOnly: true
      volumes:
        - name: secret-volume
          csi:
            driver: secrets-store.csi.k8s.io
            readOnly: true
            volumeAttributes:
              secretProviderClass: "vault-kv-sync"
```

The sync **reads the mounted files back from the Pod's volume**. It doesn't fetch from the store. That's why the volume is required, and why `objectName` has to match a file (`pkg/util/secretutil/secret.go`):

```go
file, ok := files[objectName]
if !ok {
	return datamap, fmt.Errorf("file matching objectName %s not found in the pod", objectName)
}
content, err := os.ReadFile(file)
...
datamap[dataKey] = content
```

```
 store ─► provider ─► driver ─► file "secret-2" in the Pod's tmpfs
                                     │
                     sync controller reads the file
                                     │
                                     ▼
                     Secret "kvsecret", key "username" ───► stored in etcd
                                     │
                                     ▼
                     env SECRET_USERNAME in the container
```

Note the timing problem. The env var needs the Secret, but the Secret needs the mount, and both belong to the same Pod start. The container waits on `CreateContainerConfigError` (§2) until the Secret appears, then the kubelet retries (reasoned from the code, not tested).

##### The `syncSecret.enabled` switch

Syncing also has its own switch. `syncSecret.enabled` is a Helm value in the driver chart, and it defaults to `false`. It only changes RBAC. The sync code is in the driver either way, but it only works with the permission **(code)**:

- **`false`:** the driver's base ClusterRole (`templates/role.yaml`) has no rule for `secrets` at all. It covers events, pods, SecretProviderClasses, SecretProviderClassPodStatuses and CSIDrivers.
- **`true`:** two more templates are rendered. `role-syncsecret.yaml` is a **ClusterRole** `secretprovidersyncing-role` with `create, delete, get, list, patch, update, watch` on `secrets`. `role-syncsecret_binding.yaml` binds it to the `secrets-store-csi-driver` ServiceAccount.

It only matters if your SecretProviderClass has `secretObjects`. Without `secretObjects`, the driver never tries to create a Secret.

| | `false` (default) | `true` |
|---|---|---|
| Volume mount and files | work | work, same files |
| `secretObjects` in the SPC | the write fails with `Forbidden`, the code logs a warning, and the mount still works | a Kubernetes Secret is created and kept in step with the mounted files |
| `env: secretKeyRef` on the synced Secret | the container stays in `CreateContainerConfigError` (`secret "…" not found`), because the Secret never appears (reasoned from the code, not tested) | works once the sync controller creates the Secret (see the timing note above) |
| Ingress/Gateway TLS from the synced Secret | no Secret, so no certificate | works |
| Value in etcd and in `kubectl get secret` | no | **yes** |

Setting it:

```sh
# driver chart directly
helm install csi-secrets-store secrets-store-csi-driver/secrets-store-csi-driver \
  -n kube-system --set syncSecret.enabled=true

# via the AWS provider chart (the driver is a subchart, values go under its name)
helm install secrets-provider-aws aws-secrets-manager/secrets-store-csi-driver-provider-aws \
  -n kube-system --set secrets-store-csi-driver.syncSecret.enabled=true
```

Two things to know about `true`:

- **The driver can then read and write every Secret in the cluster**, not just the ones it syncs, because the role is a ClusterRole, not a namespaced Role. A compromised driver Pod (a privileged DaemonSet on every node) becomes a way to read all Secrets.
- **It undoes CSI's main advantage for synced values.** They're back in etcd and visible to `kubectl get secret` (see "Main advantage" in §8). Leave it `false` unless a consumer can only read a Secret object: env vars, or TLS for an Ingress or Gateway.

**Cleanup: the docs and the code differ.** The docs say *"When all the pods consuming the secret are deleted, the Kubernetes secret is also deleted."* But the code doesn't make the Pod the owner. It copies the **Pod's owners** (`controllers/secretproviderclasspodstatus_controller.go`):

```go
if ownerRefs := pod.GetOwnerReferences(); len(ownerRefs) > 0 {
	ownerRefs = slices.Clone(ownerRefs)
	...
	return ownerRefs, nil
}
// Static pods have no owner references, so use the SPCPS as the owner.
```

For a Deployment, the owner is its ReplicaSet. So, reasoning from this code (not tested):

| Situation | Synced Secret |
|---|---|
| Standalone Pod deleted | deleted: its owner (the SPCPS) goes with the Pod |
| Deployment Pods restart or get replaced | stays |
| Deployment scaled to 0 | **stays**: the ReplicaSet still exists, so the value stays in etcd |
| Deployment deleted | deleted, once all its ReplicaSets are gone |
| Rolling update | each new ReplicaSet is added as another owner (`mergeOwnerRefs`) |

Don't count on "no Pods means no copy in etcd".

#### Example 5: a static key with `nodePublishSecretRef`

Where there's no federation, the credential itself comes from a Kubernetes Secret. This is the Azure service principal mode, from `examples/service-principal/` in the Azure repo:

```yaml
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata:
  name: azure-kvname
spec:
  provider: azure
  parameters:
    usePodIdentity: "false"
    keyvaultName: "kvname"
    objects:  |
      array:
        - |
          objectName: secret1
          objectType: secret
    tenantId: "tid"
---
kind: Pod
apiVersion: v1
metadata:
  name: busybox-secrets-store-inline
spec:
  containers:
  - name: busybox
    image: registry.k8s.io/e2e-test-images/busybox:1.29-4
    command: ["/bin/sleep", "10000"]
    volumeMounts:
    - name: secrets-store-inline
      mountPath: "/mnt/secrets-store"
      readOnly: true
  volumes:
    - name: secrets-store-inline
      csi:
        driver: secrets-store.csi.k8s.io
        readOnly: true
        volumeAttributes:
          secretProviderClass: "azure-kvname"
        nodePublishSecretRef:           # on the POD's volume, not in the SPC
          name: secrets-store-creds     # Kubernetes Secret holding the credentials
```

```
 Secret "secrets-store-creds" ─► kubelet ─► driver ─► MountRequest.secrets ─► provider ─► Key Vault
```

This is the secret zero problem from §7 again: a long-lived key in the cluster. Prefer Example 2 when you can.

#### Rotation: what really happens

Rotation is off by default and marked alpha. There's no rotation controller any more: since v1.6.0 it reuses the mount call (docs: `secret-auto-rotation.md`):

```
 CSIDriver requiresRepublish: true
      └─► kubelet calls NodePublishVolume again, periodically, for running Pods

 driver flag --enable-secret-rotation=false  (default) ─► already mounted? skip
 driver flag --enable-secret-rotation=true             ─► due? fetch from the provider again
                                                          (due = --rotation-poll-interval, default 2m)
```

From `nodeserver.go`:

```go
// If rotation is not enabled, don't remount the already mounted secrets.
if !rotationEnabled && mounted && hasContent {
	...
	skipped = true
	return &csi.NodePublishVolumeResponse{}, nil
}
```

And what happens if the store is down, the question §8's table raises:

```go
if isRemountRequest {
	// Mask error until fix available for https://github.com/kubernetes/kubernetes/issues/121271
	isErrorMasked = true
	return &csi.NodePublishVolumeResponse{}, nil
}
return nil, fmt.Errorf("failed to mount secrets store objects for pod %s/%s, err: %w", ...)
```

| | Store is down |
|---|---|
| New Pod (first mount) | the mount fails, so the Pod can't start |
| Running Pod (rotation) | error logged and hidden; the Pod keeps its old files |

So a store outage doesn't hurt running Pods, but it does stop scale-ups, rollouts and rescheduled Pods.

Rotation only updates the files. Everything from §3 and §9 still applies: env vars and `subPath` need a restart, and the app must re-read files.

#### The status object: SecretProviderClassPodStatus

After a mount, the driver records which versions a Pod has, in an object named `<pod>-<namespace>-<spc>`. It's owned by the Pod, so it goes when the Pod does. It's useful for debugging. Example from the driver's docs:

```yaml
status:
  mounted: true
  objects:
  - id: secret/secret1
    version: b82206cb5ac249918008b0b97fd1fd66
  podName: nginx-secrets-store-inline-multiple-crd
  secretProviderClassName: azure-spc
  targetPath: /var/lib/kubelet/pods/1b7b0740-.../volumes/kubernetes.io~csi/secrets-store-inline-0/mount
```

```
 Pod ──owns──► SecretProviderClassPodStatus ──names──► SecretProviderClass
                       │                                      │
              object versions                          secretObjects
              target path on the node                         │
                       └──────── sync controller ─────────────┴──► Kubernetes Secret
```

#### How it all connects

```
 cluster-wide                       CSIDriver "secrets-store.csi.k8s.io"
                                      tokenRequests, podInfoOnMount, requiresRepublish
                                                   ▲
                                                   │ driver name
 namespace "shop"                                  │
   Deployment ─► Pod ─ serviceAccountName ─────────┼───────► ServiceAccount
                  │                                │              │
                  └─ volume (csi) ─────────────────┘              │ token
                       │                                          │
                       │ secretProviderClass: name                │
                       ▼                                          │
                 SecretProviderClass                              │
                   provider ─► <provider>.sock                    │
                   parameters ─────────────────┐                  │
                   secretObjects ─► Secret     ▼                  ▼
 on each node                               provider DaemonSet ◄──┘
                                                   │
 outside the cluster                               ▼
                                     store: checks identity, then permission
```

#### When it fails: the real error messages

These are strings from the code. They should appear in the Pod's `FailedMount` events, with the Pod stuck in `ContainerCreating` as in §2 (not tested).

| Cause | Message |
|---|---|
| SPC missing, or in another namespace | `failed to get secretproviderclass shop/x, error: ...` |
| `spec.provider` empty | `provider not set in shop/x` |
| `spec.parameters` empty | `parameters not set in shop/x` |
| Volume not `readOnly: true` | `Readonly is not true in request` |
| Provider not installed, or typo in `provider:` | `error connecting to provider "aws": provider not found: provider "aws"` |
| AWS: `tokenRequests` missing the audience | `token for audience "pods.eks.amazonaws.com" not found - ensure tokenRequests includes this audience in CSIDriver` |
| AWS IRSA: no annotation | `An IAM role must be associated with service account api (namespace: shop)` |
| Azure: field missing | `keyvaultName is not provided`, `tenantId is not provided` |
| Sync: `objectName` doesn't match a file | `file matching objectName x not found in the pod` (Pod event) |

To debug, read in this order: `kubectl describe pod`, then the logs of the driver and provider Pods on **that Pod's node**, since both run per node.

Which stores have a provider: the driver's supported list has Azure, GCP, AWS, Vault, Akeyless, Conjur and OpenBao. **Scaleway and Hetzner aren't on it**, so on Scaleway use ESO (§13), and on Hetzner use Vault or OpenBao (§14).

### 8.2 External Secrets Operator, from the code

This follows `external-secrets/external-secrets`.

#### The three objects and how they link

```
 ExternalSecret "db"  (namespace shop)            what to fetch, where to write it
   spec.secretStoreRef: {name: aws, kind: SecretStore}
        │
        │ kind SecretStore        ─► looked up in the SAME namespace as the ExternalSecret
        │ kind ClusterSecretStore ─► looked up cluster-wide, then checked against spec.conditions
        ▼
 SecretStore "aws"                                how to reach the store, and as whom
   spec.provider.aws: region, auth ...
        │
        │ provider block ─► provider code in the controller ─► NewClient (logs in)
        ▼
 AWS Secrets Manager / Key Vault / Vault / Scaleway ...
        │
        ▼
 Secret "db"  (namespace shop)                    the result, an ordinary Kubernetes Secret
   label      reconcile.external-secrets.io/managed: "true"
   annotation reconcile.external-secrets.io/data-hash: <hash of data>
   ownerReference: ExternalSecret "db"            (creationPolicy Owner, the default)
```

The store lookup (`pkg/controllers/secretstore/client_manager.go`):

```go
if storeRef.Kind == esv1.ClusterSecretStoreKind {
	var store esv1.ClusterSecretStore
	err := m.client.Get(ctx, ref, &store)          // no namespace: cluster-scoped
	...
}
ref.Namespace = namespace                          // the ExternalSecret's namespace
var store esv1.SecretStore
err := m.client.Get(ctx, ref, &store)
```

Unlike the SPC, which lives in each namespace, a `ClusterSecretStore` can serve many namespaces. It can limit which ones with `spec.conditions`, and the code rejects other namespaces with:

```
using cluster store "x" is not allowed from namespace "y": denied by spec.condition
```

`conditions` can match namespaces by name, label selector or regex. With no `conditions`, every namespace may use the store. Matching by label has the same weakness as the Gateway `allowedRoutes` selector: anyone who can label a namespace can opt it in.

#### One reconcile, step by step

The controller runs one reconcile per `ExternalSecret`. Trimmed from `Reconcile` in `pkg/controllers/externalsecret/externalsecret_controller.go`:

```
 1. get the ExternalSecret; if it's being deleted, run cleanup, remove the finalizer
 2. add the finalizer
 3. look up the target Secret (name = spec.target.name, or the ExternalSecret's name)
 4. skip if: no refresh due AND the Secret exists AND its data still matches the hash
 5. GetProviderSecretData: resolve the store, log in, fetch every data / dataFrom entry
 6. no data at all?  apply deletionPolicy (Retain, Delete or Merge)
 7. build the Secret: template, labels, data-hash annotation, ownerReference
 8. create or update it, according to creationPolicy
 9. write status, requeue for the next refresh
```

Step 4 is the important one. The skip check:

```go
// refresh will be skipped if ALL the following conditions are met:
// 1. refresh interval is not 0
// 2. resource generation of the ExternalSecret has not changed
// 3. the last refresh time of the ExternalSecret is within the refresh interval
// 4. the target secret is valid:
//     - it exists
//     - it has the correct "managed" label
//     - it has the correct "data-hash" annotation
if !shouldRefresh(externalSecret) && isSecretValid(existingSecret, externalSecret) {
	return r.getRequeueResult(externalSecret), nil
}
```

And the validity check, the reason hand edits don't survive:

```go
// if the data-hash annotation is missing or incorrect, then it's invalid
// this is how we know if the data has chanced since we last updated the secret
if existingSecret.Annotations[esv1.AnnotationDataHash] != esutils.ObjectHash(existingSecret.Data) {
	return false
}
```

The controller also watches the metadata of every Secret carrying the `managed` label:

```go
WatchesMetadata(
	&v1.Secret{},
	handler.EnqueueRequestsFromMapFunc(r.findObjectsForSecret),
	builder.WithPredicates(predicate.ResourceVersionChangedPredicate{}, secretHasESLabel),
)
```

Together these explain what you'd see:

| You do | What triggers | Result |
|---|---|---|
| Edit the Secret by hand | the watch, right away | data no longer matches the hash, so ESO fetches again and overwrites your edit |
| Delete the Secret | the watch, right away | it no longer exists, so ESO recreates it |
| Change the value in the store | nothing in the cluster | picked up at the next refresh, `refreshInterval` later |
| Edit the ExternalSecret | the ExternalSecret watch | its generation changed, so ESO refreshes right away |

So **ESO only notices a store change on its timer**. A hand edit is noticed immediately, because the Secret is in the cluster and the store isn't.

#### When to refresh

`getRequeueResult` schedules the next run based on the last refresh:

```go
// if the refresh interval is <= 0, we should not requeue
if refreshInterval <= 0 {
	return ctrl.Result{}
}
...
// if there is time remaining, requeue after the remaining time
if timeSinceLastRefresh < refreshInterval {
	return ctrl.Result{RequeueAfter: refreshInterval - timeSinceLastRefresh}
}
```

| Setting | Default | Meaning |
|---|---|---|
| `refreshInterval` | `1h0m0s` | time between fetches; `0` means fetch once |
| `refreshPolicy` | `Periodic` | also `CreatedOnce` (never again) and `OnChange` (only when the ExternalSecret changes) |
| `target.creationPolicy` | `Owner` | ESO owns the Secret; Kubernetes deletes it along with the ExternalSecret |
| `target.deletionPolicy` | `Retain` | what to do if the value is gone from the store |

#### Logging in: the controller borrows a ServiceAccount's identity

This is the biggest difference from CSI. With CSI, the kubelet hands over the Pod's own token. With ESO, **the controller requests a token for a ServiceAccount itself**, using the TokenRequest API (`providers/v1/aws/auth/token_fetcher.go`):

```go
tokRsp, err := p.k8sClient.ServiceAccounts(p.Namespace).CreateToken(context.Background(), p.ServiceAccount, &authv1.TokenRequest{
	Spec: authv1.TokenRequestSpec{
		Audiences: p.Audiences,
	},
}, metav1.CreateOptions{})
```

That needs permission to create tokens, and the Helm chart grants it cluster-wide by default:

```yaml
# values.yaml
rbac:
  serviceAccountTokenCreate: true
  # When set to false, users must create per-ServiceAccount Role/RoleBinding with resourceNames constraint
  # to grant ESO token creation for specific ServiceAccounts referenced in SecretStore specs.
```

```yaml
# templates/rbac.yaml, when that value is true
- apiGroups: [""]
  resources: ["serviceaccounts/token"]
  verbs: ["create"]
```

So **ESO can obtain a token for any ServiceAccount in the cluster.** What stops a team from borrowing another team's identity is the code that picks the ServiceAccount's namespace (`providers/v1/aws/auth/auth.go`):

```go
name := auth.JWTAuth.ServiceAccountRef.Name
if isClusterKind && auth.JWTAuth.ServiceAccountRef.Namespace != nil {
	namespace = *auth.JWTAuth.ServiceAccountRef.Namespace
}
```

| Store kind | Which ServiceAccounts it can use |
|---|---|
| `SecretStore` | only ones in its own namespace; a namespace in the reference is ignored |
| `ClusterSecretStore` | any namespace named in the reference |

So creating `ClusterSecretStore`s should be for cluster admins only. A namespaced `SecretStore` is safe for app teams, because it can only use their own ServiceAccounts.

The ServiceAccount must also carry the IRSA annotation. The code reads the same annotation as the CSI provider, with the same error text:

```go
roleArn := sa.Annotations[roleARNAnnotation]   // "eks.amazonaws.com/role-arn"
if roleArn == "" {
	return nil, fmt.Errorf("an IAM role must be associated with service account %s (namespace: %s)", name, namespace)
}
tokenAud := sa.Annotations[audienceAnnotation]   // "eks.amazonaws.com/audience"
if tokenAud == "" {
	tokenAud = defaultTokenAudience               // "sts.amazonaws.com"
}
```

For Vault, the order of fallbacks matters (`providers/v1/vault/auth_kubernetes.go`):

```
 1. serviceAccountRef set ─► TokenRequest for that ServiceAccount (valid 600 s)
                             failing that, a legacy token Secret for it
 2. secretRef set         ─► a JWT stored in a Kubernetes Secret
 3. neither               ─► the CONTROLLER'S OWN token:
                             /var/run/secrets/kubernetes.io/serviceaccount/token
```

Case 3 is the risk ESO's Azure docs warn about: the store then acts as ESO itself, so anyone who can create a store gets ESO's access. Always set `serviceAccountRef`.

The Scaleway provider has no token step at all. It reads both API key halves from Kubernetes Secrets each time it creates a client (`providers/v1/scaleway/provider.go`):

```go
accessKey, err := loadConfigSecret(ctx, cfg.AccessKey, kube, namespace, store.GetKind())
secretKey, err := loadConfigSecret(ctx, cfg.SecretKey, kube, namespace, store.GetKind())
scwClient, err := scw.NewClient(
	scw.WithDefaultRegion(scw.Region(cfg.Region)),
	scw.WithDefaultProjectID(cfg.ProjectID),
	scw.WithAuth(accessKey, secretKey),
	...
```

That's the static secret zero from §13, seen in code.

```
                 who proves identity           what it proves with
 AWS (IRSA)      controller, for a SA        ─► TokenRequest token ─► STS ─► temporary credentials
 Vault           controller, for a SA        ─► TokenRequest token ─► Vault login
 Scaleway        controller                  ─► static API key from a Secret
```

#### When things go wrong

| Situation | What the code does |
|---|---|
| **Store unreachable** or login fails | `markAsFailed("could not get secret data from provider")`, returns the error, retries with backoff. **The existing Secret isn't touched.** |
| Store's own status not Ready | `SecretStore "x" is not ready`. This "flood gate" is on by default (`--enable-flood-gate=true`) |
| Value deleted from the store, `deletionPolicy: Retain` (default) | Secret kept as it is, ExternalSecret shows an error |
| Same, `deletionPolicy: Delete` | Secret deleted, but only if ESO owns it (`creationPolicy: Owner`) |
| Same, `deletionPolicy: Merge` | the missing keys are removed, the Secret stays |
| Two ExternalSecrets target the same Secret | the second fails: `target is owned by another ExternalSecret` |
| `target.name` changed | the old Secret is found by its `created-by` label and deleted |
| ExternalSecret deleted | with `creationPolicy: Owner`, Kubernetes deletes the Secret through the ownerReference; with `Orphan`, it stays |

The first row makes ESO **fail safe for running apps**: a store outage leaves the last good value in place. The flip side is that a revoked secret also stays in the cluster until the store is reachable again, or until someone deletes it.

#### Example: AWS with IRSA, namespaced

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: shop-secrets
  namespace: shop
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::111122223333:role/shop-secrets   # read by ESO
---
apiVersion: external-secrets.io/v1
kind: SecretStore
metadata: {name: aws, namespace: shop}
spec:
  provider:
    aws:
      service: SecretsManager
      region: eu-central-1
      auth:
        jwt:
          serviceAccountRef: {name: shop-secrets}    # same namespace only
---
apiVersion: external-secrets.io/v1
kind: ExternalSecret
metadata: {name: db, namespace: shop}
spec:
  refreshInterval: 15m
  secretStoreRef: {name: aws, kind: SecretStore}
  target:
    name: db-credentials
    creationPolicy: Owner
    deletionPolicy: Retain
  dataFrom:
  - extract:
      key: prod/db              # a JSON secret; each field becomes a key
```

```
 ExternalSecret db ─► SecretStore aws ─► ServiceAccount shop-secrets
                                           │ annotation: role ARN
                                           ▼
                       controller: TokenRequest(shop-secrets, aud sts.amazonaws.com)
                                           ▼
                       STS AssumeRoleWithWebIdentity ─► Secrets Manager GetSecretValue prod/db
                                           ▼
                       Secret db-credentials: {username: ..., password: ...}
```

The app's own Pod doesn't need any AWS identity here. Only `shop-secrets` does, and only ESO uses it.

#### Example: a ClusterSecretStore for several teams

```yaml
apiVersion: external-secrets.io/v1
kind: ClusterSecretStore
metadata: {name: vault}
spec:
  conditions:
  - namespaceSelector:
      matchLabels: {kubernetes.io/metadata.name: shop}   # name label: set by Kubernetes, can't be forged
  - namespaces: [search]
  provider:
    vault:
      server: https://vault.internal:8200
      path: secret
      version: v2
      auth:
        kubernetes:
          mountPath: kubernetes
          role: eso-reader
          serviceAccountRef:
            name: eso-vault
            namespace: external-secrets     # allowed, because this is a ClusterSecretStore
```

Every namespace allowed by `conditions` gets that store's access. Vault sees one identity, `external-secrets/eso-vault`, whichever team's ExternalSecret asked. So Vault can't tell `shop`'s requests from `search`'s, and `conditions` is the only thing between them. For per-team access in Vault itself, use a namespaced `SecretStore` per team, each with its own ServiceAccount and Vault role.

#### ESO compared with the CSI driver

| | CSI driver (§8.1) | ESO |
|---|---|---|
| Triggered by | a Pod starting | a timer, plus watches |
| Whose token | the Pod's, given by the kubelet | any ServiceAccount's, minted by the controller |
| Needs token-minting rights | no (Vault's fallback aside) | yes, cluster-wide by default |
| Result | files in one Pod | a Secret anyone in the namespace can mount |
| Store outage | new Pods can't start | nothing changes in the cluster |
| Hand edits to the result | not applicable | overwritten right away |
| Store change reaches the cluster | at the next rotation poll (off by default) | at the next `refreshInterval` |

## 9. Rotation

Rotating means replacing a value, ideally before anyone needs to. The change has to reach two sides, and they can't change at the same instant.

```
 Wrong order:  change DB password ─► apps still use the old one ─► outage until restart

 Right order:  both values valid for a while
   1. Create the new credential     DB accepts old AND new
   2. Publish to the store          version 4 becomes current
   3. Roll it out to apps           restart or reload
   4. Revoke the old credential     only after all apps have moved
```

AWS Secrets Manager's managed rotation follows this shape: the new version is held as `AWSPENDING`, tested, then promoted to `AWSCURRENT`, and the old one becomes `AWSPREVIOUS` (general AWS behaviour; the page fetched didn't show these details).

How a new value reaches the app, using the results from §3:

| Delivery | What the app needs |
|---|---|
| Env var | restart the container |
| Volume, read once at start | restart the container |
| Volume, re-read on each use or on file change | nothing |
| `subPath` | restart the container |
| SDK | re-fetch when a cache expires, or when auth fails |

For restarts, `kubectl rollout restart deployment/x` replaces Pods one at a time. Tools such as Stakater Reloader do this when a Secret changes (not tested).

**Time adds up.** Worst case for a synced Secret: ESO's `refreshInterval` + kubelet delay + the app's reload. Step 4 has to wait longer than that total.

### Example: a new secret version in Azure Key Vault

Creating a new version in Key Vault **never restarts a Pod by itself**. Whether the Pod even *sees* the new value depends on how the secret reaches it.

**1. Secrets Store CSI driver (Azure provider), mounted as a file** (§8.1, "Rotation: what really happens"):

```
new version in Key Vault
   │
   ├─ rotation OFF (the default: --enable-secret-rotation=false)
   │     → nothing happens. The Pod keeps the old file until it's recreated.
   │
   └─ rotation ON (--enable-secret-rotation=true)
         → kubelet re-calls NodePublishVolume (requiresRepublish: true)
         → driver asks the Azure provider again, at most every --rotation-poll-interval (default 2m)
         → the FILE in the Pod is updated in place. The Pod is NOT restarted.
```

- **`objectVersion` in the SecretProviderClass:** empty means "latest", so new versions are picked up. If you **pin a version**, a new version is ignored until you change the SecretProviderClass.
- **The app must re-read the file.** If it read the password once at startup, it keeps using the old one in memory.
- **If you also sync to a Kubernetes Secret** (`secretObjects`) and use it as an **env var**, the Secret updates but the env var doesn't. Env vars are fixed at container start (§3 lab: env stayed `v1` until a restart).

**2. External Secrets Operator (ExternalSecret → Kubernetes Secret)** (§8.2):

```
new version in Key Vault ──(next refreshInterval, default 1h)──► ESO updates the Kubernetes Secret
                                                                   │
                         Pod using it as a VOLUME ◄────────────────┤ file updates after ~1 min (kubelet sync)
                         Pod using it as an ENV VAR ◄──────────────┘ stays OLD until the container restarts
```

ESO never restarts Pods either.

**3. The app reads Key Vault directly (SDK).** The app gets whatever version it asks for, whenever it asks. Nothing in Kubernetes is involved.

**If you *want* a restart,** something has to change the Pod template or delete the Pods:

| Option | How |
|---|---|
| By hand | `kubectl rollout restart deployment/<name>` |
| Automatically, on Secret change | a controller like **Stakater Reloader** (`reloader.stakater.com/auto: "true"` on the Deployment) watches Secrets and triggers a rollout. Works with ESO or with CSI `secretObjects` syncing, because both produce a Kubernetes Secret **(not tested)** |
| Checksum annotation | Helm or your operator puts a hash of the Secret on the Pod template, so changing it causes a rollout. This is exactly what the Keycloak operator does ([keycloak-on-kubernetes.md](keycloak-on-kubernetes.md) §4) |

**Summary:**

| Delivery | New Key Vault version → | Pod restarted? |
|---|---|---|
| CSI file, rotation off (default) | nothing | no |
| CSI file, rotation on | file updated within ~2 min | no |
| CSI-synced Secret / ESO Secret → env var | Secret updates, env var stays old | no; you must restart |
| ESO Secret → volume | file updates after refresh + ~1 min | no |
| SDK in the app | the app fetches it | no |

To avoid restarts: mount it as a file, turn rotation on, leave `objectVersion` empty, and make the app re-read the file. If the app only reads env vars or reads config once at startup, pair it with Reloader or a checksum annotation so new versions trigger a rolling restart.

## 10. Secrets and Git

GitOps wants everything in Git, and secrets must not be readable there. The options:

| Approach | What's in Git | Decrypted by | Note |
|---|---|---|---|
| **Reference only** | an `ExternalSecret` | ESO, from the store | No secret in Git at all. Best if you have a store. |
| **SOPS** | a file with values encrypted | your deploy tool, with an age or KMS key | No server to run |
| **Sealed Secrets** | a `SealedSecret`, encrypted to the cluster's public key | a controller in the cluster | Tied to one cluster's key |

SOPS and Sealed Secrets keep the value in Git history forever, encrypted. If the key leaks, old values are exposed too. Removing a real plain-text secret committed to Git means **rotating it**, not just deleting the file, because it stays in history.

### 10.1 Sealed Secrets, from the code

This follows `bitnami-labs/sealed-secrets`.

**The idea:** a controller in the cluster holds a private key. Anyone with the matching public certificate can encrypt, but only the controller can decrypt.

```
 your laptop                                   cluster
 ───────────                                   ───────
 kubeseal ◄────── cert.pem ──────────────────── controller   (private key in a Secret)
    │      (public, from /v1/cert.pem)                ▲
    │                                                 │
    │ encrypt each value                              │
    ▼                                                 │
 SealedSecret YAML ─► Git ─► kubectl apply / GitOps ──┘
                                                      │ decrypt
                                                      ▼
                                              Secret (owned by the SealedSecret)
```

#### The keys

The controller creates its own key pair at startup, and stores it as a Secret in its namespace labelled `sealedsecrets.bitnami.com/sealed-secrets-key`. The defaults come from `cmd/controller/main.go`:

```go
defaultKeyRenewPeriod = 30 * 24 * time.Hour
fs.IntVar(&f.KeySize, "key-size", 4096, "Size of encryption key.")
fs.DurationVar(&f.ValidFor, "key-ttl", 10*365*24*time.Hour, "Duration that certificate is valid for.")
```

Every 30 days it **adds** a new key, and new sealing uses the newest. Old keys stay, so old SealedSecrets keep working. The README warns that this is *"not a substitute for periodical rotation of your actual secret values"*. A new key protects only future seals, while everything sealed before can still be opened with the old key.

**Back up the key Secrets.** If the cluster is lost without them, every SealedSecret in Git becomes unreadable, and you have to re-seal each value from its source. That makes the private key the secret zero here.

#### How one value is encrypted

From `pkg/crypto/crypto.go`. Each value gets its own random AES key, and only that small key is encrypted with RSA:

```go
func HybridEncrypt(rnd io.Reader, pubKey *rsa.PublicKey, plaintext, label []byte) ([]byte, error) {
	// Generate a random symmetric key
	sessionKey := make([]byte, sessionKeyBytes)            // 32 bytes
	...
	aed, err := cipher.NewGCM(block)                       // AES-GCM
	// Encrypt symmetric key
	rsaCiphertext, err := rsa.EncryptOAEP(sha256.New(), rnd, pubKey, sessionKey, label)
	// First 2 bytes are RSA ciphertext length, so we can separate
	// all the pieces later.
	...
	// SessionKey is only used once, so zero nonce is ok
	ciphertext = aed.Seal(ciphertext, zeroNonce, plaintext, nil)
```

```
 encryptedData.password =  base64( [2-byte length] [RSA-OAEP(AES key, label)] [AES-GCM(value)] )
```

This is envelope encryption again (§5), with the RSA key as the KEK and a fresh AES key as the DEK.

#### The label: why a sealed value can't be moved

The `label` passed to RSA-OAEP is part of the encryption. Decryption fails unless the same label is supplied. It comes from the Secret's name and namespace (`sealedsecret_expansion.go`):

```go
func EncryptionLabel(namespace, name string, scope SealingScope) []byte {
	switch scope {
	case ClusterWideScope:
		l = ""
	case NamespaceWideScope:
		l = namespace
	case StrictScope:
		fallthrough
	default:
		l = fmt.Sprintf("%s/%s", namespace, name)
	}
```

| Scope | Label | Can be applied as |
|---|---|---|
| `strict` (default) | `shop/db-credentials` | only that name in only that namespace |
| `namespace-wide` | `shop` | any name in `shop` |
| `cluster-wide` | empty | any name, any namespace |

The code comment says why this matters: *"This check ensures that we can't be tricked into decrypting a sealed secret into an unexpected namespace/name."* Without it, anyone able to create a SealedSecret in their own namespace could copy your sealed value there, have the controller decrypt it, and read the result. So keep the default `strict` unless you need otherwise.

#### Unsealing in the cluster

From `pkg/controller/controller.go`, trimmed:

```go
newSecret, err := c.attemptUnseal(ssecret)        // try every private key
...
secret, err := c.sclient.Secrets(ns).Get(ctx, name, ...)
if k8serrors.IsNotFound(err) {
	secret, err = c.sclient.Secrets(ns).Create(ctx, newSecret, ...)
}
if !metav1.IsControlledBy(secret, ssecret) && !isAnnotatedToBeManaged(secret) && !isAnnotatedToBePatched(secret) {
	msg := fmt.Sprintf("Resource %q already exists and is not managed by SealedSecret", secret.Name)
	...
}
```

- **It tries every private key** in turn (`HybridDecrypt` loops over them), which is how old SealedSecrets survive key renewal.
- **It won't overwrite a Secret it doesn't own**, unless that Secret is annotated to allow it.
- **The Secret is owned by the SealedSecret**, so deleting the SealedSecret deletes the Secret.

One more detail ties back to §2. When you seal a Secret taken from the cluster, `kubeseal` removes the `last-applied-configuration` annotation first:

```go
// We need to strip those annotations or else we would
// be leaking secrets in clear in a way that might be non obvious to users.
StripLastAppliedAnnotations(s.Spec.Template.ObjectMeta.Annotations)
```

That's the same plain-text copy this lab found by accident in etcd.

#### Example

```bash
# --raw also exists for single values. This form seals a whole Secret manifest.
kubectl create secret generic db-credentials -n shop \
  --from-literal=password=example --dry-run=client -o yaml \
| kubeseal --format yaml > db-credentials.sealed.yaml
```

```yaml
apiVersion: bitnami.com/v1alpha1
kind: SealedSecret
metadata:
  name: db-credentials
  namespace: shop
spec:
  encryptedData:
    password: AgB4k...           # safe to commit: needs the controller's private key
  template:
    metadata:
      name: db-credentials
      namespace: shop
```

(The `kubeseal` command is from general knowledge, not checked against this commit. The output is illustrative, not real.)

### 10.2 SOPS, from the code

This follows `getsops/sops`.

**The idea:** SOPS encrypts the *values* in a YAML, JSON, INI, dotenv or binary file and leaves the *keys* readable. There's no server: decryption happens wherever the file is read, by anyone holding one of the listed master keys.

From SOPS's own `example.yaml`:

```yaml
app2:
    db:
        user: ENC[AES256_GCM,data:tQ1l,iv:o9MiMveNYO7T82yDab+4pAt17DO6B4wl8yGy3oFbDb8=,tag:0RjvUUtSQwaUc9SF3RSTZQ==,type:str]
        password: ENC[AES256_GCM,data:8Ll+TDCgzQ==,iv:aao0OSVdFwaB9EGZ0O+Wn5bRJ6do7hgiQioqiwDi1w0=,tag:WKj0nuSSPkm2dBOBwinYvg==,type:str]
somelist_unencrypted:
    - all elements of this list
    - remain in clear text
    - because of the _unencrypted suffix in the key
sops:
    lastmodified: "2025-08-06T18:55:05Z"
    mac: ENC[AES256_GCM,data:GCWOyMJo56...,type:str]
    pgp:
        - created_at: "2019-12-10T22:45:53Z"
          enc: |-
            -----BEGIN PGP MESSAGE-----
            ...
```

That makes diffs and reviews useful: you can see *which* key changed, without seeing the value.

#### Two layers of keys

```
 one random 32-byte data key per file
   ├─ encrypts every value          (AES-256-GCM)
   └─ is itself encrypted by EACH master key, stored in the "sops:" section
        age recipient 1  ─► copy 1
        age recipient 2  ─► copy 2
        AWS KMS key      ─► copy 3        any ONE of these can recover the data key
```

From `sops.go`:

```go
func (tree *Tree) GenerateDataKeyWithKeyServices(svcs []keyservice.KeyServiceClient) ([]byte, []error) {
	newKey := make([]byte, 32)
	_, err := rand.Read(newKey)
	...
	return newKey, tree.Metadata.UpdateMasterKeysWithKeyServices(newKey, svcs)
}
```

```go
if len(m.KeyGroups) == 1 {
	// If there's only one key group, we can't do Shamir. All keys
	// in the group encrypt the whole data key.
	parts = append(parts, dataKey)
} else {
	...
	parts, err = shamir.Split(dataKey, len(m.KeyGroups), int(m.ShamirThreshold))
```

With several **key groups**, the data key is split with Shamir's secret sharing. Then you need keys from, say, 2 of 3 groups: for example the CI's KMS key *and* a person's age key. This avoids one key being enough on its own.

Adding or removing a person means re-encrypting the data key for the new list of master keys (`sops updatekeys`). That gives a new person access to all of the file's **history** in Git too, and removing someone doesn't take back what they could already decrypt. Rotate the values when someone leaves.

#### Each value is tied to its place in the file

From `aes/cipher.go` and `sops.go`:

```go
pathString := strings.Join(path, ":") + ":"        // e.g. "app2:db:password:"
in, err = cipher.Encrypt(in, key, pathString)
...
out := gcm.Seal(nil, iv, plainBytes, []byte(additionalData))
return fmt.Sprintf("ENC[AES256_GCM,data:%s,iv:%s,tag:%s,type:%s]", ...)
```

The value's path is the GCM *additional data*, so it's checked on decryption. If someone moves `password`'s ciphertext under another key, decryption fails. This is the same idea as Sealed Secrets' label. The `type` field restores numbers and booleans as their original types.

#### Tamper detection: the MAC

While encrypting, SOPS hashes every value with SHA-512 and stores the result, encrypted, as `sops.mac`. Decryption recomputes it:

```go
hash := sha512.New()
...
hash.Write(bytes)        // every value, in order
...
return fmt.Sprintf("%X", hash.Sum(nil)), nil
```

If someone edits, adds or removes an encrypted value without the data key, the MAC no longer matches and SOPS refuses to decrypt. Values left in clear text by the selectors below are included in the MAC too, unless `mac_only_encrypted` is set.

#### Choosing what to encrypt

By default every value is encrypted, except keys ending in `_unencrypted`. Other selectors exist (`shouldBeEncrypted` in `sops.go`): `unencrypted_suffix`, `encrypted_suffix`, `unencrypted_regex`, `encrypted_regex`, plus two for comments.

For Kubernetes Secrets, the common choice is to encrypt only `data` and `stringData`, so `apiVersion`, `kind` and `metadata` stay readable for tools (common practice, not from the SOPS repo):

```yaml
# .sops.yaml, at the repo root
creation_rules:
  - path_regex: k8s/.*\.secret\.yaml$
    encrypted_regex: ^(data|stringData)$
    age: age1qyp...              # public key(s) that can decrypt
```

#### Getting it into the cluster

SOPS only produces a decrypted file. Something has to decrypt and apply it, and that something needs a private key (general knowledge, not from the SOPS repo):

```
 Git: secret.yaml (values encrypted)
        │
        ├─ Flux kustomize-controller: decrypts in the cluster using a key in a Secret
        ├─ Argo CD + a plugin (KSOPS, helm-secrets): decrypts during rendering
        └─ CI job: sops -d secret.yaml | kubectl apply -f -
        │
        ▼
 ordinary Kubernetes Secret ─► §2–4 all apply
```

Each option moves secret zero somewhere: into a cluster Secret (Flux), the Argo CD repo server, or the CI system. Using a cloud KMS key as the master key avoids a long-lived private key file. Access then depends on cloud identity, as in §7.

### 10.3 Sealed Secrets vs SOPS

| | Sealed Secrets | SOPS |
|---|---|---|
| Who can encrypt | anyone with the public cert | anyone with a public key listed (age, PGP) or access to the KMS key |
| Who can decrypt | only the controller in that cluster | anyone with one master key, anywhere |
| Server needed | yes, the controller | no |
| Readable diffs | no: the whole value is one blob | yes: keys stay readable |
| Tied to | one cluster's key; re-seal for another cluster | the master keys you list |
| Moving a value elsewhere | blocked by the label (strict scope) | blocked by the path in GCM |
| Tamper detection | AES-GCM per value | AES-GCM per value, plus a MAC over the file |
| Losing the key | every SealedSecret is unreadable: back up the key Secret | lose all master keys and the file is unreadable |
| Removing a person | not applicable: people never hold the private key | doesn't revoke history: rotate the values |
| Result in the cluster | an ordinary Secret | an ordinary Secret |

Both end as an ordinary Kubernetes Secret, so everything about etcd encryption, RBAC and namespaces from §2–5 still applies. They solve **getting a secret into Git safely**, not protecting it once it's in the cluster.

## 11. AWS

```
 Store         Secrets Manager (versions, rotation, cross-region)
               Parameter Store SecureString (simpler; also supported by the CSI provider)
 Key service   KMS
 Identity      EKS Pod Identity (simpler) or IRSA (older, wider support)
 etcd          encrypted by default on 1.28+
```

### Pod Identity

Set up two things: a role, and an association between that role and a ServiceAccount. The role trusts a single AWS service principal, so it needs no per-cluster edits. **(docs)**

```json
{ "Effect": "Allow",
  "Principal": {"Service": "pods.eks.amazonaws.com"},
  "Action": ["sts:AssumeRole", "sts:TagSession"] }
```

```
 Pod starts ─► EKS injects:
                 AWS_CONTAINER_CREDENTIALS_FULL_URI=http://169.254.170.23/v1/credentials
                 AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE=.../eks-pod-identity-token
                 token volume, audience pods.eks.amazonaws.com, valid 24 h

 SDK ─► Pod Identity Agent on the same node (DaemonSet, 169.254.170.23)
          └─► EKS Auth API: AssumeRoleForPodIdentity ─► temporary credentials
```

### IRSA

Register the cluster's OIDC issuer as an identity provider in IAM, write a role trust policy that matches the ServiceAccount, and annotate the ServiceAccount. The SDK then exchanges the token itself, using `sts:AssumeRoleWithWebIdentity`. (The `eks.amazonaws.com/role-arn` annotation and `sts.amazonaws.com` audience are confirmed in the AWS provider's code, §8.1. The `AWS_ROLE_ARN` and `AWS_WEB_IDENTITY_TOKEN_FILE` variables are from general AWS knowledge; the page fetched didn't show them.)

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: api
  namespace: shop
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::111122223333:role/shop-api
```

| | Pod Identity | IRSA |
|---|---|---|
| OIDC provider in IAM | **not needed** | one per cluster |
| Trust policy per cluster | no: one principal for all | yes |
| Who talks to AWS | agent on the node | the SDK in each Pod |
| Works on | EKS on Linux EC2 nodes | also Fargate and others (not tested) |
| Cross-account | delegate via roles | natively in the trust policy (not tested) |

### Gotchas

- **Credential order.** The SDK stops at the first credentials it finds. If old keys are in env vars, they still win after you add Pod Identity **(docs)**. That makes migration safe, and also makes a forgotten key quietly stay in use.
- **Node role leak.** Unless the instance metadata service (IMDS) is restricted, Pods can reach the node's role **(docs)**. Restrict it, or per-Pod identity isn't truly per-Pod.
- **ASCP needs both permissions.** `secretsmanager:GetSecretValue` and `secretsmanager:DescribeSecret` **(docs)**.
- **ASCP not on Fargate.** The CSI provider needs EC2 nodes **(docs)**.

### Using it

CSI, with the AWS provider (ASCP). Leaving `usePodIdentity` out means IRSA **(docs)**.

```yaml
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata: {name: db-secrets, namespace: shop}
spec:
  provider: aws
  parameters:
    usePodIdentity: "true"
    objects: |
      - objectName: "prod/db"
        objectType: "secretsmanager"
        jmesPath:
          - path: password
            objectAlias: password
```

ESO, with IRSA (the `auth.jwt` form is from general knowledge of ESO's AWS docs):

```yaml
apiVersion: external-secrets.io/v1
kind: SecretStore
metadata: {name: my-store, namespace: shop}
spec:
  provider:
    aws:
      service: SecretsManager
      region: eu-central-1
      auth:
        jwt:
          serviceAccountRef: {name: api}
```

## 12. Azure

```
 Store         Key Vault (holds secrets, keys and certificates)
 Key service   Key Vault keys, or Managed HSM
 Identity      Microsoft Entra Workload ID
 etcd          disk encryption always; API-level KMS optional
```

Key Vault has one container for all three object types. A **vault** holds secrets, keys and certificates, while a **Managed HSM pool** holds only HSM-backed keys **(docs)**. New vaults use Azure RBAC by default **(docs)**. For an app, the role to know is **Key Vault Secrets User** (read values). **Key Vault Secrets Officer** can manage them.

### Workload ID

```
 Setup (once per ServiceAccount):
   managed identity  ──role assignment──►  Key Vault Secrets User on the vault
   managed identity  ──federated credential──►  issuer = cluster OIDC URL
                                                 subject = system:serviceaccount:shop:api

 Pod starts, labelled azure.workload.identity/use: "true"
   ─► webhook injects AZURE_FEDERATED_TOKEN_FILE and a token volume
      audience api://AzureADTokenExchange

 SDK ─► Microsoft Entra: here's my Kubernetes token ─► Entra access token
     ─► Key Vault: read db-password
```

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: api
  namespace: shop
  annotations:
    azure.workload.identity/client-id: <managed identity client id>
---
apiVersion: apps/v1
kind: Deployment
spec:
  template:
    metadata:
      labels:
        azure.workload.identity/use: "true"     # on the POD template, not the Deployment
    spec:
      serviceAccountName: api
```

In your code, `DefaultAzureCredential` finds all of this by itself **(docs)**.

### Gotchas

- **The label goes on the Pod.** The webhook only changes Pods with that label **(docs)**.
- **20 federated credentials per identity.** Sharing one identity across many clusters hits this. Identity bindings exist to remove the limit **(docs)**.
- **Annotation changes need a Pod restart** **(docs)**.
- **Re-read the token file.** Kubernetes refreshes it in place, so don't cache its contents, and don't hard-code its path **(docs)**.
- **The add-on creates a node-level identity.** `azurekeyvaultsecretsprovider-xxxxx` is assigned to the node scale set **(docs)**. Prefer Workload ID: an identity on the nodes isn't tied to one app.
- **Rotation is off by default** in the CSI add-on: `enableSecretRotation: false`, polling every 2 minutes once on **(docs)**.
- **KMS key deletion can be final.** A permanently deleted key means encrypted secrets can't be recovered **(docs)**.

### Using it

CSI, enabled with `az aks enable-addons --addons azure-keyvault-secrets-provider`:

```yaml
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata: {name: db-secrets, namespace: shop}
spec:
  provider: azure
  parameters:
    usePodIdentity: "false"
    clientID: <managed identity client id>
    keyvaultName: my-vault
    tenantID: <tenant id>
    objects: |
      array:
        - |
          objectName: db-password
          objectType: secret
```

ESO **(docs)**:

```yaml
apiVersion: external-secrets.io/v1
kind: SecretStore
metadata: {name: my-store, namespace: shop}
spec:
  provider:
    azurekv:
      authType: WorkloadIdentity
      vaultUrl: https://my-vault.vault.azure.net
      serviceAccountRef: {name: api}
```

## 13. Scaleway

```
 Store         Secret Manager (regional, per project, versioned)
 Key service   Key Manager
 Identity      IAM application + API key (static)
 etcd          Secrets encrypted at rest, per the FAQ
```

Secret Manager uses envelope encryption with AES-256. By default the key is Scaleway-managed, and since mid-2025 you can choose a symmetric key of your own from Key Manager **(docs, search extract)**.

### The difference: secret zero is a static key

Scaleway's own tutorial for Kapsule uses ESO with an IAM application's API key **(docs)**. I found no Scaleway documentation for federating Pods to Scaleway IAM. That doesn't prove it doesn't exist, so check the current docs.

```
 Setup:
   IAM application ──policy──► SecretManagerReadOnly + SecretManagerSecretAccess
   IAM application ──generates──► API key (access key + secret key)

 Cluster:
   Kubernetes Secret "scw-credentials"   ◄── YOUR secret zero: a long-lived key
        │
        ▼
   SecretStore ─► ESO ─► Secret Manager ─► Kubernetes Secret ─► Pod
```

```yaml
apiVersion: external-secrets.io/v1
kind: SecretStore
metadata: {name: my-store, namespace: shop}
spec:
  provider:
    scaleway:
      region: fr-par
      projectId: <project uuid>
      accessKey:
        secretRef: {name: scw-credentials, key: access-key}
      secretKey:
        secretRef: {name: scw-credentials, key: secret-key}
---
apiVersion: external-secrets.io/v1
kind: ExternalSecret
metadata: {name: db, namespace: shop}
spec:
  refreshInterval: 1h
  secretStoreRef: {name: my-store, kind: SecretStore}
  target: {name: db-credentials}
  data:
  - secretKey: password
    remoteRef:
      key: id:<secret uuid>        # also name:<name> or path:<path>
      version: latest_enabled
      property: password           # if the value is JSON
```

### Living with a static key

You still get a central store, versions and access control, so this remains well worth using. Just treat the key as the most sensitive thing in the cluster:

- **Scope it tightly.** One IAM application per cluster or environment, with only the two permission sets, on only that project.
- **Read-only.** A stolen key shouldn't be able to change secrets.
- **Keep it in a locked namespace.** Remember §4: anyone who can create Pods there can read it. A `ClusterSecretStore` in an admin-only namespace keeps it away from app teams.
- **Give it an expiry and rotate it.** Put that in a calendar or automation.
- **Encrypt it in Git, or create it outside Git.** It's the one value ESO can't fetch for you.

## 14. Hetzner

**Hetzner has no secret manager and no KMS.** Hetzner's Cloud product page lists servers, networks, firewalls, load balancers, images and storage, and names neither **(docs)**. It lists Kubernetes only as an integration, so you run the control plane yourself, and with it everything in §5.

```
 Store         none: bring your own
 Key service   none
 Identity      API tokens for Hetzner's own API only
 etcd          your job (§5)
```

Your first secret is Hetzner's own. The cloud controller manager needs an API token, created by hand **(docs)**:

```bash
kubectl -n kube-system create secret generic hcloud --from-literal=token=<hcloud API token>
```

That token can manage the project's servers. Anyone who can read this Secret, or create Pods in `kube-system`, can use it. Keep `kube-system` tightly restricted.

### Your options

| Option | Good for | Cost |
|---|---|---|
| **A. Self-host OpenBao or Vault** | Several apps, audit logs, dynamic secrets | You run a critical service: backups, upgrades, HA, unsealing |
| **B. Use another provider's store** | No ops; you're fine with a dependency | Secret zero is a static key (or federation, below); traffic leaves Hetzner |
| **C. SOPS or Sealed Secrets** | Small setups, one or two clusters | No audit log of reads, and rotation by hand |
| **D. Plain Secrets + etcd encryption** | Very small setups | No central store at all |

**Option A gets you real identity without a cloud.** Vault's Kubernetes auth has the workload present its ServiceAccount token, and Vault checks it with the TokenReview API **(docs)**. No static credential is needed:

```
 ESO or Pod ─► Vault: here's my ServiceAccount token
                 └─► Kubernetes TokenReview API: is this token valid?
                 └─► Vault role: this ServiceAccount may read shop/*
                 ◄── short-lived Vault token ─► read secret
```

Secret zero doesn't vanish. It moves to Vault's unseal keys, which you must keep outside the cluster. Normally a cloud KMS auto-unseals Vault, but Hetzner has none, so you hold the keys or use a KMS elsewhere (not tested).

```yaml
apiVersion: external-secrets.io/v1
kind: SecretStore
metadata: {name: my-store, namespace: shop}
spec:
  provider:
    vault:
      server: https://vault.internal:8200
      path: secret
      version: v2
      auth:
        kubernetes:
          mountPath: kubernetes
          role: shop-api
          serviceAccountRef: {name: api}
```

**Option B can avoid the static key too.** A self-managed cluster can publish its OIDC issuer and public keys at a public URL, and AWS or Azure can then trust it, in the same way as §7 (not tested). This is the fix for the `jwks_uri` problem shown there, but you take on hosting that endpoint and rotating the signing keys.

**Recommendation.** One or two small clusters: **C**, plus etcd encryption. Several apps or a team: **A**, if someone can really own it. A self-hosted Vault nobody maintains is worse than SOPS. If you'd rather not run anything: **B**.

## 15. Side by side

| | AWS | Azure | Scaleway | Hetzner |
|---|---|---|---|---|
| Secret store | Secrets Manager, Parameter Store | Key Vault | Secret Manager | none |
| KMS | KMS | Key Vault keys, Managed HSM | Key Manager | none |
| Managed Kubernetes | EKS | AKS | Kapsule | none on the product page |
| Pod identity | Pod Identity, IRSA | Workload ID | not found: API key | none |
| Secret zero | **none** (federated) | **none** (federated) | static API key | static key, or Vault unseal keys |
| API-level etcd encryption | default | optional | Secrets only, per FAQ | your job |
| CSI provider | ASCP | Key Vault provider | not found | via Vault (not tested) |
| ESO provider | yes | yes | yes | via Vault |

## 16. Checklist

**Whatever the cloud:**

- [ ] No secrets in images, Git or `.env` files that get committed
- [ ] Files over env vars, where the app allows it
- [ ] etcd encrypted at API level
- [ ] Namespaces as the boundary: "can deploy here" means "can read Secrets here"
- [ ] `get`, `list`, `watch` on Secrets limited; `list` treated as `get`
- [ ] Short-lived identity where available, no static keys
- [ ] Every static key that remains has an owner, an expiry and a scope
- [ ] Rotation tested, including a full restart of the app
- [ ] The KMS key protected from deletion
- [ ] Secrets kept out of logs

**Common mistakes:**

| Mistake | Why it hurts |
|---|---|
| Thinking base64 protects anything | §2: one command reverses it |
| Rotating a secret but not restarting the app | §3: env vars stay on the old value |
| Using `subPath` and expecting updates | §3: it stays stale |
| Granting `list` on Secrets for a "read-only" role | §4: `list` returns values |
| Giving "just Pod access" to someone who shouldn't see Secrets | §4: the `sneaky` Pod test |
| Assuming "encrypted at rest" stops `kubectl get secret` | §5: the API server decrypts for callers |
| Backing up etcd along with its encryption key | §5: the backup includes its own key |
| Deleting a secret from Git instead of rotating it | §10: it's still in history |
| One shared credential for all environments | §13: one leak becomes every environment |

## 17. Automating the creation of Secrets

Two different jobs get called "automating secrets":

- **Copying** a value that already exists somewhere (Key Vault, AWS, Vault, an encrypted file in git) into a Kubernetes Secret.
- **Generating** a value that doesn't exist yet, so nobody types or sees the password.

### 17.1 Copying: the tools already covered

| Tool | What you write | What creates the Secret | Section |
|---|---|---|---|
| External Secrets Operator | `ExternalSecret` → a store | the ESO controller, re-synced on `refreshInterval` | §8.2 |
| Secrets Store CSI driver | SecretProviderClass with `secretObjects` | the driver, only while a Pod mounts the volume | §8.1, Example 4 |
| Sealed Secrets | `SealedSecret` (encrypted, safe in git) | the controller, by decrypting it | §10.1 |
| SOPS | an encrypted file in git | the GitOps tool (Flux) or helm-secrets, at apply time | §10.2 |

### 17.2 Generating: ESO generators

**(code)** ESO has a second API group, `generators.external-secrets.io/v1alpha1`, whose objects **produce** values instead of reading them (`apis/generators/v1alpha1/types_*.go`):

| Kind | Produces |
|---|---|
| `Password` | random passwords |
| `UUID` | a UUID |
| `SSHKey` | an SSH key pair (RSA or ECDSA) |
| `ECRAuthorizationToken`, `ACRAccessToken`, `GCRAccessToken`, `QuayAccessToken`, `CloudsmithAccessToken` | short-lived registry login tokens |
| `STSSessionToken` | temporary AWS credentials |
| `GithubAccessToken`, `GitlabAccessToken`, `Grafana` | short-lived tokens for those services |
| `VaultDynamicSecret` | a Vault dynamic secret, such as a fresh database user |
| `Webhook` | whatever an HTTP endpoint returns |
| `MFA` | a TOTP code |
| `ClusterGenerator` | a cluster-scoped wrapper around any of the above |

**A generated password, end to end.** The fields are from `types_password.go`, and the YAML is based on the repo's `docs/snippets/generator-password*.yaml`:

```yaml
apiVersion: generators.external-secrets.io/v1alpha1
kind: Password
metadata: {name: db-password, namespace: shop}
spec:
  length: 32                 # default 24
  digits: 6                  # default 25% of length
  symbols: 4                 # default 25% of length
  symbolCharacters: "-_@"    # default ~!@#$%^&*()_+`-={}|[]\:"<>?,./  (keep it safe for URLs and shells)
  noUpper: false
  allowRepeat: true
  # secretKeys: [app, admin] # one independent password per key; default key is "password"
  # encoding: raw            # raw | base64 | base64url | base32 | hex
---
apiVersion: external-secrets.io/v1
kind: ExternalSecret
metadata: {name: db-password, namespace: shop}
spec:
  refreshPolicy: CreatedOnce # ← generate once, never again (see the trap below)
  target:
    name: db-credentials     # the Secret ESO creates
  dataFrom:
  - sourceRef:
      generatorRef:
        apiVersion: generators.external-secrets.io/v1alpha1
        kind: Password
        name: db-password
```

Result: a Secret `db-credentials` with one key, `password`, holding something like `k9Fq-2xV…`. No SecretStore is involved: the value comes from the generator.

**The trap: every sync generates a new value.** **(code)** The ExternalSecret controller calls the generator's `Generate()` each time it syncs (`pkg/controllers/externalsecret/externalsecret_controller_secret.go`). `refreshInterval` defaults to `1h0m0s`. So an ExternalSecret using a `Password` generator with the defaults **changes the password every hour**. Your app gets a new password that the database has never heard of. To get one stable value, use either:

- `refreshPolicy: CreatedOnce`: "Creates the Secret only if it does not exist and does not update it thereafter", or
- `refreshInterval: "0s"`: "fetch and create it once".

A short interval is what you *want* for the token generators (ECR, STS, GitHub): those tokens expire, so getting a new one regularly is the point.

**The generated value lives only in etcd, unless you push it out.** If the Secret is deleted, the password is gone. **`PushSecret`** (`external-secrets.io/v1alpha1`) writes a Secret's value, or a generator's output directly, **into** a store. Based on `docs/snippets/pushsecret-generator-rotation-example.yaml`, adapted for Azure:

```yaml
apiVersion: external-secrets.io/v1alpha1
kind: PushSecret
metadata: {name: db-password-to-kv, namespace: shop}
spec:
  refreshInterval: 0s              # push once; a non-zero interval rotates (new password each time)
  secretStoreRefs:
  - name: azure-kv                 # a SecretStore with write access to the vault
    kind: SecretStore
  selector:
    generatorRef:                  # exactly one of selector.secret or selector.generatorRef
      apiVersion: generators.external-secrets.io/v1alpha1
      kind: Password
      name: db-password
  data:
  - match:
      secretKey: password          # key in the generator output
      remoteRef:
        remoteKey: shop-db-password  # name of the Key Vault secret
```

Now Key Vault is the source of truth. Everything else (the app, the database setup job, other clusters) reads it from there with an ordinary ExternalSecret or the CSI driver. The identity behind `azure-kv` needs **write** permission on the vault (Key Vault Secrets Officer), not just read **(docs; not tested)**.

### 17.3 Other things that create Secrets for you

- **kustomize `secretGenerator`:** builds a Secret at build time and adds a hash of the content to its name. When the value changes, the name changes, so every Deployment that refers to it rolls. The same "change the template, get a rollout" mechanism as §9's checksum annotation. **(docs; not tested)**

  ```yaml
  # kustomization.yaml
  secretGenerator:
  - name: app-config
    literals: [API_MODE=strict]
    files: [tls.key]
  # → Secret app-config-7g2m4k8h5c, and references to app-config are rewritten to that name
  ```

  The values still come from files or literals you have, so this packages Secrets; it doesn't keep values out of git.
- **cert-manager:** creates and renews `kubernetes.io/tls` Secrets from `Certificate` objects (§2) **(not tested)**.
- **Operators that make credentials for their own app:** the Keycloak operator creates the initial admin Secret if you don't give it one ([keycloak-on-kubernetes.md](keycloak-on-kubernetes.md)). Database operators such as CloudNativePG create the app user's password Secret the same way **(docs; not tested)**.
- **Vault dynamic secrets:** Vault's database engine creates a **new database user** per request, with an expiry. These reach Pods through the Vault Secrets Operator, the Vault Agent injector, or ESO's `VaultDynamicSecret` **(docs; not tested)**.
- **Terraform:** `random_password` plus `azurerm_key_vault_secret` creates the value straight into Key Vault, and ESO or the CSI driver delivers it. Note that the password also ends up in the Terraform state file **(not tested)**.
- **Built into Kubernetes:**
  - Helm stores each release as a `helm.sh/release.v1` Secret (§2, type 9).
  - The token controller fills an empty `kubernetes.io/service-account-token` Secret with `token`, `ca.crt` and `namespace` (§7).
  - kubeadm creates `bootstrap.kubernetes.io/token` Secrets for joining nodes (§2, type 8).

### 17.4 Which one

| You want | Use |
|---|---|
| A value that already exists in a cloud store | ESO `ExternalSecret`, or the CSI driver (§8) |
| A new random password, kept by the cluster only | ESO `Password` generator + `refreshPolicy: CreatedOnce` |
| A new random password, kept in Key Vault | `Password` generator + `PushSecret`, or Terraform `random_password` |
| Short-lived registry or cloud tokens | ESO token generators with a short `refreshInterval` |
| Per-request database users | Vault dynamic secrets |
| TLS certificates | cert-manager |
| Secrets in git | Sealed Secrets or SOPS (§10) |

## Sources

Kubernetes:
[Secrets](https://kubernetes.io/docs/concepts/configuration/secret/) ·
[Good practices for Secrets](https://kubernetes.io/docs/concepts/security/secrets-good-practices/) ·
[KMS provider](https://kubernetes.io/docs/tasks/administer-cluster/kms-provider/) ·
[Sync as Kubernetes Secret (CSI driver)](https://github.com/kubernetes-sigs/secrets-store-csi-driver/blob/main/docs/book/src/topics/sync-as-kubernetes-secret.md)

Secrets Store CSI Driver and providers (code quoted in §8.1):
[Driver](https://github.com/kubernetes-sigs/secrets-store-csi-driver) ·
[Auto rotation](https://github.com/kubernetes-sigs/secrets-store-csi-driver/blob/HEAD/docs/book/src/topics/secret-auto-rotation.md) ·
[Providers](https://github.com/kubernetes-sigs/secrets-store-csi-driver/blob/HEAD/docs/book/src/providers.md) ·
[AWS provider](https://github.com/aws/secrets-store-csi-driver-provider-aws) ·
[Azure provider](https://github.com/Azure/secrets-store-csi-driver-provider-azure) ·
[Azure Workload Identity mode](https://github.com/Azure/secrets-store-csi-driver-provider-azure/blob/HEAD/website/content/en/configurations/identity-access-modes/workload-identity-mode.md) ·
[Vault provider](https://github.com/hashicorp/vault-csi-provider) ·
[GCP provider](https://github.com/GoogleCloudPlatform/secrets-store-csi-driver-provider-gcp)

The store APIs the providers call:
[AWS GetSecretValue](https://docs.aws.amazon.com/secretsmanager/latest/apireference/API_GetSecretValue.html) ·
[Azure Key Vault Get Secret](https://learn.microsoft.com/en-us/rest/api/keyvault/secrets/get-secret/get-secret) ·
[GCP AccessSecretVersion](https://cloud.google.com/secret-manager/docs/reference/rpc/google.cloud.secretmanager.v1#google.cloud.secretmanager.v1.SecretManagerService.AccessSecretVersion) ·
[Vault Kubernetes auth API](https://developer.hashicorp.com/vault/api-docs/auth/kubernetes)

Code read for §8.2 and §10:
[External Secrets Operator](https://github.com/external-secrets/external-secrets) ·
[ExternalSecret controller](https://github.com/external-secrets/external-secrets/blob/HEAD/pkg/controllers/externalsecret/externalsecret_controller.go) ·
[Sealed Secrets](https://github.com/bitnami-labs/sealed-secrets) ·
[Sealed Secrets crypto](https://github.com/bitnami-labs/sealed-secrets/blob/HEAD/pkg/crypto/crypto.go) ·
[SOPS](https://github.com/getsops/sops) ·
[SOPS core](https://github.com/getsops/sops/blob/HEAD/sops.go)

External Secrets Operator:
[ExternalSecret API](https://external-secrets.io/latest/api/externalsecret/) ·
[AWS provider](https://external-secrets.io/latest/provider/aws-secrets-manager/) ·
[Azure Key Vault provider](https://external-secrets.io/latest/provider/azure-key-vault/) ·
[Scaleway provider](https://external-secrets.io/latest/provider/scaleway/) ·
[Vault provider](https://external-secrets.io/latest/provider/hashicorp-vault/)

AWS:
[EKS Pod Identity](https://docs.aws.amazon.com/eks/latest/userguide/pod-identities.html) ·
[How Pod Identity works](https://docs.aws.amazon.com/eks/latest/userguide/pod-id-how-it-works.html) ·
[IRSA](https://docs.aws.amazon.com/eks/latest/userguide/iam-roles-for-service-accounts.html) ·
[Default envelope encryption](https://docs.aws.amazon.com/eks/latest/userguide/envelope-encryption.html) ·
[Secrets Manager in EKS](https://docs.aws.amazon.com/secretsmanager/latest/userguide/integrate_eks.html) ·
[ASCP with Pod Identity](https://docs.aws.amazon.com/secretsmanager/latest/userguide/ascp-pod-identity-integration.html) ·
[AWS Emulator, Secrets Manager `get_secret_value.rs`](https://github.com/aws/aws-emulator)

Azure:
[Workload ID on AKS](https://learn.microsoft.com/en-us/azure/aks/workload-identity-overview) ·
[Key Vault CSI provider](https://learn.microsoft.com/en-us/azure/aks/csi-secrets-store-driver) ·
[Connecting an identity to the CSI driver](https://learn.microsoft.com/en-us/azure/aks/csi-secrets-store-identity-access) ·
[Data encryption at rest concepts](https://learn.microsoft.com/en-us/azure/aks/kms-data-encryption-concepts) ·
[Legacy KMS etcd encryption](https://learn.microsoft.com/en-us/azure/aks/use-kms-etcd-encryption) ·
[What is Key Vault](https://learn.microsoft.com/en-us/azure/key-vault/general/basic-concepts)

Scaleway:
[External Secrets on Kapsule](https://www.scaleway.com/en/docs/kubernetes/api-cli/external-secrets-kubernetes/) ·
[Secret Manager encryption](https://www.scaleway.com/en/docs/secret-manager/reference-content/data-encryption-with-secret-manager/) ·
[Secret Manager FAQ](https://www.scaleway.com/en/docs/secret-manager/faq/) ·
[Kubernetes FAQ](https://www.scaleway.com/en/docs/kubernetes/faq/) ·
[Key Manager](https://www.scaleway.com/en/docs/key-manager/)

Hetzner:
[Cloud products](https://www.hetzner.com/cloud) ·
[hcloud CCM quick start](https://github.com/hetznercloud/hcloud-cloud-controller-manager/blob/main/docs/guides/quickstart.md)
