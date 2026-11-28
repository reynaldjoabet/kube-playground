# gRPC in Kubernetes: kube-apiserver → etcd, CRI and CSI

Kubernetes uses gRPC for its plugin interfaces: the API server talks to etcd, the kubelet talks to
the container runtime (CRI) and to storage drivers (CSI). This note explains how gRPC works on the
wire (protobuf, HTTP/2 frames, streams, header compression, Unix sockets) and then follows real
calls in each of the three, captured and decoded in labs.

Labels: **(lab)** = run and captured here, **(docs)** = from specifications or project docs,
**(not tested)** = neither.

Labs (all on Docker Desktop, kernel `7.0.14-linuxkit`):

- **etcd lab:** a plaintext etcd 3.6.4 and kube-apiserver v1.34.1 in two containers, so the
  traffic between them can be captured and decoded (§2).
- **CRI and CSI labs:** the k3s v1.34.1+k3s1 cluster from [network-policy.md](network-policy.md)
  §6, node `np-server` (§3, §4).

---

## 0. Where Kubernetes uses gRPC (docs)

| Who talks to whom | API | Transport |
|---|---|---|
| **kube-apiserver → etcd** | etcd v3 (`KV`, `Watch`, `Lease`, …) | TCP, normally TLS with client certificates |
| **kubelet → container runtime** | **CRI** (`RuntimeService`, `ImageService`) | Unix socket |
| **kubelet, CSI sidecars → storage driver** | **CSI** (`Identity`, `Controller`, `Node`) | Unix socket |
| kubelet ↔ device plugins | device plugin API | Unix socket |
| plugins → kubelet | plugin registration (`GetInfo`, `NotifyRegistrationStatus`) | Unix socket |
| kubelet → DRA drivers | `NodePrepareResources`, … | Unix socket |
| kube-apiserver → KMS plugin | KMS v2 (`Encrypt`, `Decrypt`) | Unix socket |
| monitoring agents → kubelet | PodResources API | Unix socket |
| kubelet → container | gRPC health probe (`livenessProbe.grpc`) | TCP |
| kube-apiserver ↔ konnectivity | apiserver-network-proxy tunnel | gRPC streams |

**Not gRPC:** `kubectl`, controllers and the kubelet talk to kube-apiserver over REST (JSON or
protobuf encoding, but no gRPC services). CNI plugins are binaries fed JSON on stdin. containerd
talks to its shims over **ttrpc**, a smaller gRPC-like protocol.

The pattern: gRPC for **plugin interfaces**, where one process calls another through a strictly
versioned API, mostly over a local Unix socket. REST for the public API.

---

## 1. How gRPC works

gRPC is four layers stacked on each other, each with an example from the labs:

```text
 ┌──────────────────────────────────────────────────────────────────────┐
 │ the API contract: a .proto file                                      │
 │   service KV { rpc Put(PutRequest) returns (PutResponse); }          │
 ├──────────────────────────────────────────────────────────────────────┤
 │ each message: protobuf bytes, with a 5-byte gRPC prefix              │
 │   00 | 00000014 | 0a0b2f64656d6f…                                    │
 ├──────────────────────────────────────────────────────────────────────┤
 │ each call: one HTTP/2 stream                                         │
 │   HEADERS :path=/etcdserverpb.KV/Put → DATA → HEADERS grpc-status    │
 ├──────────────────────────────────────────────────────────────────────┤
 │ the connection: TCP (with or without TLS) or a Unix socket           │
 │   etcd:2379   or   /run/k3s/containerd/containerd.sock               │
 └──────────────────────────────────────────────────────────────────────┘
```

### 1.1 The contract: a `.proto` file (docs)

Both sides are generated from the same definition. From etcd's `api/etcdserverpb/rpc.proto`:

```protobuf
service KV {
  rpc Range(RangeRequest) returns (RangeResponse);
  rpc Put(PutRequest) returns (PutResponse);
  rpc DeleteRange(DeleteRangeRequest) returns (DeleteRangeResponse);
  rpc Txn(TxnRequest) returns (TxnResponse);
}
service Watch {
  rpc Watch(stream WatchRequest) returns (stream WatchResponse);   // "stream" on both sides
}
message PutRequest {
  bytes key = 1;          // the numbers, not the names, go on the wire
  bytes value = 2;
  int64 lease = 3;
  bool prev_kv = 4;
}
```

The generated client has a `Put()` function. Calling it sends an HTTP/2 request to the path
**`/<package>.<Service>/<Method>`**, here `/etcdserverpb.KV/Put`.

### 1.2 Protobuf: field numbers and wire types (docs; bytes from the lab)

A protobuf message is a list of fields. Each one starts with a **tag** byte,
`(field number << 3) | wire type`. Wire type 0 is a varint (integers, bools, enums), wire type 2 is
length-delimited (strings, bytes and nested messages). There are no field names and no
separators.

`etcdctl put /demo/hello world`, request message as captured:

```text
0a 0b 2f 64 65 6d 6f 2f 68 65 6c 6c 6f   12 05 77 6f 72 6c 64
│  │  └ "/demo/hello" (11 bytes)          │  │  └ "world"
│  └ length 11                            │  └ length 5
└ tag 0x0a = field 1, wire type 2 (key)   └ tag 0x12 = field 2, wire type 2 (value)
```

20 bytes for the whole request. Fields left at their default (`lease: 0`, `prev_kv: false`)
aren't sent at all.

The response, `PutResponse { header: ResponseHeader }`, nests a message inside field 1:

```text
0a 1a  08 b298eaf1948386fccd01  10 cdd2918bd2b881cf8e01  18 68  20 02
│  │   │  cluster_id (varint)   │  member_id             │  │   │  raft_term = 2
│  │   └ field 1, varint        └ field 2                │  └ 0x68 = 104: revision
│  └ 26 bytes follow            └ field 3: revision      └ field 4
└ field 1 (header), wire type 2: a nested message
```

### 1.3 HTTP/2: one connection, many streams, everything in frames (docs; lab capture)

HTTP/2 sends everything in **frames**. Each frame starts with a 9-byte header:

```text
┌─────────────────┬─────────┬─────────┬───┬────────────────────────────────┐
│ length (24 bit) │ type (8)│ flags(8)│ R │ stream identifier (31 bit)     │
└─────────────────┴─────────┴─────────┴───┴────────────────────────────────┘
then `length` bytes of payload
```

| Type | Name | Used for in gRPC |
|---|---|---|
| 0 | DATA | the gRPC messages |
| 1 | HEADERS | the request line and headers; also the trailers at the end |
| 4 | SETTINGS | connection parameters, sent first by both sides (stream 0) |
| 6 | PING | keepalive and round-trip measurement (stream 0) |
| 8 | WINDOW_UPDATE | flow control: "you may send N more bytes" |
| 3 | RST_STREAM | cancel one call without closing the connection |
| 7 | GOAWAY | "I'm closing; finish what you have" |

A **stream** is one request/response exchange inside the connection. Client-started streams have
odd numbers (1, 3, 5, …), increasing with each call. Many streams can be open at once, and their
frames interleave freely. That is **multiplexing**: one TCP connection carries many concurrent
calls, so there's no head-of-line blocking between them at the HTTP level.

The complete `etcdctl put` connection, frame by frame (lab, `tshark -d tcp.port==2379,http2`):

```text
 #   direction          frame                                   what it is
 1-3 client ⇄ etcd      TCP SYN, SYN-ACK, ACK
 4   client → etcd      Magic "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"  connection preface: "I speak HTTP/2" (h2c, no TLS, no Upgrade)
 6   client → etcd      SETTINGS[0]                             client's settings (stream 0 = the connection itself)
 8   etcd → client      SETTINGS[0]  Max frame size: 16384      server's settings
 10  etcd → client      SETTINGS[0] ACK                         "got yours"
 12  client → etcd      SETTINGS[0] ACK
 13  client → etcd      HEADERS[1]: POST /etcdserverpb.KV/Put   the call starts: stream 1
 14  client → etcd      DATA[1] (GRPC) END_STREAM               the request message; client is done sending
 16  etcd → client      WINDOW_UPDATE[0] 25, PING[0]            flow control credit back; keepalive ping
 17  client → etcd      PING[0] ACK
 18  etcd → client      HEADERS[1] 200 OK                       response headers
                        DATA[1] (GRPC)                          the response message
                        HEADERS[1] END_STREAM                   trailers: grpc-status 0 → the call is over
 19- client ⇄ etcd      TCP FIN …                               etcdctl exits, connection closes
```

### 1.4 One gRPC call on the wire (lab)

The request HEADERS frame (frame 13):

```text
:method: POST                         ← always POST
:scheme: http                         ← "http" because this connection has no TLS
:path: /etcdserverpb.KV/Put           ← /<package>.<Service>/<Method>
:authority: etcd:2379                 ← the target the client dialed
content-type: application/grpc        ← marks the body as gRPC messages
user-agent: grpc-go/1.71.1
te: trailers                          ← "I understand trailers", required by gRPC
grpc-timeout: 4996785u                ← deadline: ~5 s left (u = microseconds)
client-api-version: 3.6               ← custom metadata, etcd's own
```

Every gRPC message in a DATA frame gets a **5-byte prefix**. Frame 14, the whole TCP payload:

```text
00 00 19 | 00 | 01 | 00 00 00 01 | 00 | 00 00 00 14 | 0a 0b 2f64656d6f2f68656c6c6f 12 05 776f726c64
└ HTTP/2 frame header: length 25, type DATA, flags END_STREAM, stream 1
                                   └ gRPC prefix: compressed flag 0, message length 20
                                                      └ the protobuf message (§1.2)
```

The prefix exists because a stream can carry **several** messages one after another (streaming
calls), and DATA frames can split or merge them. The length tells the receiver where each message
ends.

The response ends with **trailers**, a second HEADERS frame after the data, with END_STREAM:

```text
HEADERS[1]  :status: 200, content-type: application/grpc      ← HTTP-level success
DATA[1]     00 0000001c 0a1a08…                                ← 28-byte PutResponse
HEADERS[1]  grpc-status: 0, grpc-message: (empty)  END_STREAM  ← the gRPC result
```

The call's real result is in `grpc-status` (0 = OK, 5 = NOT_FOUND, 4 = DEADLINE_EXCEEDED,
14 = UNAVAILABLE, …), not in `:status`, which is 200 even for most failures. It has to come
after the body because a streaming call only knows whether it succeeded once it has finished.

### 1.5 Four kinds of call (docs; examples from the labs)

| Kind | `.proto` | Lab example |
|---|---|---|
| unary | `rpc Put(PutRequest) returns (PutResponse)` | etcd `KV/Range`, `KV/Txn`; CRI `Version`; CSI `NodePublishVolume` |
| server streaming | `returns (stream X)` | (CRI `GetContainerEvents`, not captured) |
| client streaming | `rpc X(stream Y)` | (rare in these APIs) |
| bidirectional | `rpc Watch(stream WatchRequest) returns (stream WatchResponse)` | etcd `Watch`: the API server's configmaps watch stayed open on stream 5 for the whole capture (§2.6) |

A streaming call is still one HTTP/2 stream. It just carries many DATA frames, each with one or
more prefixed messages, and the trailers only arrive when the stream ends.

### 1.6 HPACK: why headers are tiny, and why you can't always decode them (lab)

HTTP/2 compresses headers with **HPACK**. Both ends keep a **static table** (61 common headers
such as `:method: POST` = index 3) and a **dynamic table** that fills up as headers are sent on
that connection. A repeated header becomes a one-byte index.

On a long-lived API server → etcd connection, a whole request header block was **9 bytes**:

```text
Header Block Fragment: 83 86 c6 c5 c4 c3 c2 c1 c0
                       │  │  └──────────────────┴ dynamic table entries 62–68
                       │  │                       (:path, :authority, content-type, …)
                       │  └ static 6: :scheme: http
                       └ static 3: :method: POST
```

The dynamic table exists only in the two endpoints' memory. A capture that starts after the
connection opened can't decode those indexes: tshark showed `:method` and `:scheme` but
`<unknown>` for the rest. To see the API server's calls in §2, the capture had to start first and
the API server had to be restarted.

### 1.7 Transports: TCP+TLS, plaintext TCP, Unix socket (docs; lab for the last two)

| Transport | How HTTP/2 starts | `:scheme` / `:authority` | Who is allowed in | Used by |
|---|---|---|---|---|
| **TCP + TLS** | TLS handshake with ALPN `h2`, then the preface | `https` / host:port | TLS client certificates (mTLS) | API server → etcd in real clusters (`--etcd-certfile`, `--etcd-cafile`) **(not tested here)** |
| **plaintext TCP** (h2c) | the preface straight away ("prior knowledge") | `http` / host:port | anyone who can reach the port | the etcd lab (§2), on purpose, to read the traffic |
| **Unix socket** (h2c) | the preface straight away | `http` / `localhost` | **file permissions** on the socket (`srw-rw----`, root) | CRI, CSI, device plugins, KMS, kine in k3s |

A Unix socket is a file-system path, not a network address. The kernel copies bytes between the
two processes without IP or TCP at all, so there's nothing to route, nothing to sniff from the
network, and no TLS needed. Whoever can open the file can call the API. That's why CRI and CSI use
sockets: the caller (kubelet) and the server are on the same node, and root ownership of the
socket file is the access control.

The lab saw this from both ends: the CRI request carried `:authority: localhost` (§3.3), and the
CSI driver saw its peer as `unix:` with no address (§4.3).

Because tcpdump can't capture a Unix socket, the CRI lab relays it through TCP on loopback with
`socat` (§3.2). The bytes on that TCP hop are exactly the HTTP/2 frames that would have gone over
the socket.

---
## 2. kube-apiserver → etcd (lab)

### 2.1 Setup: etcd and the API server without TLS

A real cluster runs this connection over mutual TLS. For the lab both containers sit on one
Docker network and talk plaintext gRPC, so every byte can be read:

```bash
docker network create grpc-lab
docker run -d --name etcd --network grpc-lab registry.k8s.io/etcd:3.6.4-0 \
  etcd --name e1 --data-dir /tmp/e \
       --listen-client-urls http://0.0.0.0:2379 --advertise-client-urls http://etcd:2379

# service-account keys and a static token, generated on the host into ./k
openssl genrsa -out k/sa.key 2048 && openssl rsa -in k/sa.key -pubout -out k/sa.pub
echo 'labtoken,admin,uid-admin,system:masters' > k/tokens.csv

docker run -d --name apiserver --network grpc-lab -v $PWD/k:/k:ro registry.k8s.io/kube-apiserver:v1.34.1 \
  kube-apiserver --etcd-servers=http://etcd:2379 \
    --service-account-issuer=https://kubernetes.default.svc \
    --service-account-key-file=/k/sa.pub --service-account-signing-key-file=/k/sa.key \
    --token-auth-file=/k/tokens.csv --authorization-mode=AlwaysAllow \
    --service-cluster-ip-range=10.96.0.0/16 --cert-dir=/tmp/certs --secure-port=6443

# kubectl from the k3s image
docker run --rm --network grpc-lab --entrypoint /bin/kubectl rancher/k3s:latest \
  --server https://apiserver:6443 --insecure-skip-tls-verify --token labtoken get ns

# capture from inside etcd's network namespace, decode as HTTP/2
docker run -d --name cap --network container:etcd -v $PWD:/out nicolaka/netshoot:v0.13 \
  tcpdump -i eth0 -U -s0 -w /out/apiserver-full.pcap "tcp port 2379"
docker run --rm -v $PWD:/out nicolaka/netshoot:v0.13 tshark -r /out/apiserver-full.pcap -d tcp.port==2379,http2 …
```

etcd is `172.20.0.2`, the API server `172.20.0.3`. The capture started, the API server was
restarted (so every connection opened inside the capture, §1.6), and then:

```text
kubectl create configmap grpc-demo2 --from-literal=greeting=hello
kubectl get configmap grpc-demo2
kubectl delete configmap grpc-demo2
```

tshark has no etcd `.proto` loaded, so it shows protobuf fields only by number. The messages below
were decoded with a short Python script that maps the numbers to names from etcd's `rpc.proto` and
`kv.proto` (the same varint/length walk as §1.2).

### 2.2 Many connections, many streams

```text
TCP connections opened by the API server during the capture:  65
  (before the restart, `ss` in etcd's namespace showed ~60 established connections from 172.20.0.3)
highest HTTP/2 stream id seen on one connection:              207
```

The API server doesn't use one connection for everything. It creates an etcd client per group of
resources (each resource's storage), and each client has its own connection, multiplexing all of
that resource's calls on it. All ConfigMap traffic ran on one connection, each call on a new
odd-numbered stream:

```text
   3.736s stream 1   /etcdserverpb.KV/Range          ← startup: list all configmaps (to fill the watch cache)
   3.736s stream 3   /etcdserverpb.KV/Range
   3.736s stream 5   /etcdserverpb.Watch/Watch       ← startup: watch all configmaps; stays open
  11.886s stream 7   /etcdserverpb.KV/Txn            ← kubectl create
  14.300s stream 9   /etcdserverpb.KV/Range          ← kubectl get
  16.695s stream 11  /etcdserverpb.KV/Range          ← kubectl delete: read current object
  16.696s stream 13  /etcdserverpb.KV/Txn            ← kubectl delete: conditional delete
  16.698s stream 15  /etcdserverpb.KV/Range
```

Every method called in the whole capture (startup plus the three commands):

```text
    139 /etcdserverpb.KV/Range
     57 /etcdserverpb.Watch/Watch
      8 /etcdserverpb.KV/Txn
      2 /etcdserverpb.Maintenance/Status
      2 /etcdserverpb.Lease/LeaseGrant
```

There is no `KV/Put` and no `KV/DeleteRange`. The API server writes **only through `Txn`**, so
every write can carry a condition.

### 2.3 `kubectl create`: a conditional put

```text
stream 7  apiserver -> etcd  TxnRequest (316 bytes)
  compare {
    target: MOD
    key: '/registry/configmaps/default/grpc-demo2'
    mod_revision: 0                       ← "only if this key has never been written"
  }
  success {
    request_put {
      key: '/registry/configmaps/default/grpc-demo2'
      value: k8s\x00 + Kubernetes protobuf, 219 bytes: b'k8s\x00\n\x0f\n\x02v1\x12\tConfigMap\x12\xbf\x01\n\xa9\x01\n\ngrpc-demo2\x12\x00\x1a\x07default"\x00*$2845ed'…
    }
  }

stream 7  etcd -> apiserver  TxnResponse (40 bytes)
  header { cluster_id: 14841639068965178418  member_id: 10276657743932975437  revision: 128  raft_term: 2 }
  succeeded: 1
  responses { response_put { header { revision: 128 } } }
```

- **The key** is `/registry/<resource>/<namespace>/<name>`. Cluster-scoped objects drop the
  namespace part.
- **The value** is the object in Kubernetes' own protobuf encoding, marked by the 4-byte magic
  `k8s\x00`, then `apiVersion: v1`, `kind: ConfigMap`, the metadata (`grpc-demo2`, `default`, the
  UID) and the data. That's a different protobuf layer from gRPC's: the API server serializes the
  object, and etcd just stores the bytes.
- **The condition** makes "create" fail if the name is taken. If `succeeded` were 0, the API server
  would answer `kubectl` with `409 AlreadyExists`.
- **`revision: 128`** is etcd's global revision after the write. It becomes the object's
  `metadata.resourceVersion`.

### 2.4 `kubectl get`: a range read of one key

```text
stream 9  apiserver -> etcd  RangeRequest (43 bytes)
  key: '/registry/configmaps/default/grpc-demo2'
  limit: 1

stream 9  etcd -> apiserver  RangeResponse (305 bytes)
  header { revision: 129 }
  kvs {
    key: '/registry/configmaps/default/grpc-demo2'
    create_revision: 128
    mod_revision: 128
    version: 1
    value: k8s\x00 + Kubernetes protobuf, 219 bytes …
  }
  count: 1
```

A GET without `resourceVersion` is a consistent read, so it goes to etcd. Watches and many LIST
requests are served from the API server's watch cache instead (§2.6).

### 2.5 `kubectl delete`: delete only if unchanged

```text
stream 13  apiserver -> etcd  TxnRequest (138 bytes)
  compare { target: MOD  key: '/registry/configmaps/default/grpc-demo2'  mod_revision: 128 }
  success { request_delete_range { key: '/registry/configmaps/default/grpc-demo2' } }
  failure { request_range        { key: '/registry/configmaps/default/grpc-demo2' } }

stream 13  etcd -> apiserver  TxnResponse (42 bytes)
  header { revision: 131 }
  succeeded: 1
  responses { response_delete_range { header { revision: 131 }  deleted: 1 } }
```

The API server first read the object (stream 11, mod_revision 128), then deleted it **only if it
was still at revision 128**. If someone had changed it in between, the `failure` branch would
return the current version instead. This is **optimistic concurrency** in one round trip: no locks,
just compare-and-swap.

### 2.6 The watch: one stream, open for the whole capture

At startup the API server opened a bidirectional `Watch` call on stream 5 and sent one request:

```text
stream 5  apiserver -> etcd  WatchRequest (54 bytes)
  create_request {
    key: '/registry/configmaps/'
    range_end: '/registry/configmaps0'      ← every key starting with "/registry/configmaps/"
    start_revision: 124                     ← continue from what the initial Range returned
    progress_notify: 1
    prev_kv: 1                              ← include the old value with each change
  }

stream 5  etcd -> apiserver  WatchResponse  header { revision: 123 }  created: 1
```

`range_end` is the prefix trick: `0` is the character after `/`, so the range `[a/, a0)` covers
every key that starts with `a/`.

The stream then stayed open, with no new request, and etcd pushed each change down it:

```text
11.887s  stream 5  etcd -> apiserver  WatchResponse (306 bytes)      ← right after the create's Txn
  header { revision: 128 }
  events {
    kv { key: '/registry/configmaps/default/grpc-demo2'  create_revision: 128  mod_revision: 128  version: 1  value: k8s\x00 … }
  }                                                                  (type PUT = 0, so not sent)

16.697s  stream 5  etcd -> apiserver  WatchResponse (354 bytes)      ← right after the delete's Txn
  header { revision: 131 }
  events {
    type: DELETE
    kv      { key: '/registry/configmaps/default/grpc-demo2'  mod_revision: 131 }
    prev_kv { key: … create_revision: 128  mod_revision: 128  version: 1  value: k8s\x00 … }
  }
```

The create's response (stream 7) and the watch event (stream 5) arrived **in the same TCP segment**
(frame 2312): two streams multiplexed on one connection.

This one watch per resource type feeds the API server's **watch cache**. Every `kubectl get -w`,
every controller's informer and every kubelet watching Pods is served from that cache over HTTP,
not by its own etcd watch. 57 `Watch` calls served the whole API server.

### 2.7 The same API in k3s: kine over a Unix socket (lab)

k3s doesn't need a real etcd. Its embedded **kine** implements the same etcd gRPC API in front of
SQLite (`/var/lib/rancher/k3s/server/db/state.db`, [azure-local-lab.md](azure-local-lab.md) §7),
and the API server reaches it over a Unix socket instead of TCP:

```text
$ docker logs k3s-np-server | grep -o -E "etcd-servers=[^ ]*|Kine available at [^\"]*"
Kine available at unix://kine.sock
etcd-servers=unix://kine.sock

$ ls -la /var/lib/rancher/k3s/server/kine.sock
srw------- 1 root root 0 Nov 20 08:49 /var/lib/rancher/k3s/server/kine.sock

$ grep -c kine.sock /proc/net/unix
67                                                  ← one per client connection, like the 65 TCP connections above
```

The API server code is the same; only the dial target changed. Every `Txn`, `Range` and `Watch`
from §2.3–§2.6 goes into kine, which turns it into SQL.

---
## 3. kubelet → container runtime: CRI (lab)

### 3.1 The socket

The kubelet finds the runtime through one setting (k3s writes it into a kubelet config drop-in):

```text
$ grep -r containerRuntimeEndpoint /var/lib/rancher/k3s/agent/etc/kubelet.conf.d/
containerRuntimeEndpoint: unix:///run/k3s/containerd/containerd.sock

$ ls -la /run/k3s/containerd/
srw-rw---- 1 root root 0 containerd.sock          ← gRPC: CRI (and containerd's own API)
srw-rw---- 1 root root 0 containerd.sock.ttrpc    ← ttrpc: containerd ↔ its shims
```

On a kubeadm node the path is `/run/containerd/containerd.sock` (or `/var/run/crio/crio.sock` for
CRI-O). `crictl` reads the same endpoint from `crictl.yaml` and speaks exactly the same API, so
it's a handy stand-in for the kubelet.

CRI has two gRPC services in package `runtime.v1` (docs, from the CRI `api.proto`):

| Service | Main methods |
|---|---|
| `RuntimeService` | `Version`, `RunPodSandbox`, `StopPodSandbox`, `RemovePodSandbox`, `ListPodSandbox`, `CreateContainer`, `StartContainer`, `StopContainer`, `RemoveContainer`, `ListContainers`, `ContainerStatus`, `Exec`, `Attach`, `PortForward`, `ContainerStats`, `GetContainerEvents` (stream) |
| `ImageService` | `ListImages`, `ImageStatus`, `PullImage`, `RemoveImage`, `ImageFsInfo` |

The kubelet sees a Pod as a **sandbox** (the Pod's network namespace and its `pause` container)
plus containers created inside it.

### 3.2 Capturing a Unix socket: a socat relay

A host-network Pod on np-server runs two socat processes. `crictl` connects to a new socket, socat
turns that into TCP on loopback, and a second socat forwards it to containerd's real socket.
tcpdump captures the TCP hop in the middle:

```text
crictl ──unix──► /run/k3s/cri-proxy.sock ─socat─► 127.0.0.1:9999 ─socat─► /run/k3s/containerd/containerd.sock ──► containerd
                                                  ▲ tcpdump -i lo port 9999
```

```yaml
apiVersion: v1
kind: Pod
metadata: { name: criproxy, namespace: default }
spec:
  nodeName: np-server
  hostNetwork: true
  containers:
  - name: proxy
    image: nicolaka/netshoot:v0.13
    securityContext: { privileged: true }
    command: ["sh", "-c"]
    args:
    - |
      socat TCP-LISTEN:9999,bind=127.0.0.1,fork,reuseaddr UNIX-CONNECT:/run/k3s/containerd/containerd.sock &
      socat UNIX-LISTEN:/run/k3s/cri-proxy.sock,fork,mode=660 TCP:127.0.0.1:9999 &
      exec sleep 1d
    volumeMounts: [{ name: run-k3s, mountPath: /run/k3s }]
  volumes: [{ name: run-k3s, hostPath: { path: /run/k3s } }]
```

Then on the node: `crictl --runtime-endpoint unix:///run/k3s/cri-proxy.sock version`, `… pods`,
`… exec <container> echo hi-from-exec`.

### 3.3 What crictl sent

```text
4 TCP connections (one per gRPC client crictl created)
  conn 0  /runtime.v1.RuntimeService/Version                 crictl version
  conn 1  /runtime.v1.RuntimeService/Version                 every command first checks the API version…
  conn 1  /runtime.v1.RuntimeService/ListPodSandbox          …then crictl pods
  conn 2  /runtime.v1.RuntimeService/Version
  conn 2  /runtime.v1.RuntimeService/Exec                    crictl exec
  conn 3  /runtime.v1.ImageService/ImageFsInfo               a separate connection for the image service client
```

The request headers, the same shape as etcd's but over a Unix socket:

```text
:method: POST
:scheme: http
:path: /runtime.v1.RuntimeService/Version
:authority: localhost                 ← no host:port for a socket
content-type: application/grpc
user-agent: grpc-go/1.72.1
te: trailers
grpc-accept-encoding: gzip
grpc-timeout: 1998599u                ← crictl's 2 s default timeout
```

`VersionResponse`, decoded by hand from the captured bytes:

```text
0a 05 "0.1.0"  12 0a "containerd"  1a 0b "v2.1.4-k3s2"  22 02 "v1"
│ version      │ runtime_name     │ runtime_version     │ runtime_api_version
```

which is exactly what `crictl version` printed:

```text
Version:  0.1.0
RuntimeName:  containerd
RuntimeVersion:  v2.1.4-k3s2
RuntimeApiVersion:  v1
```

### 3.4 `Exec` doesn't stream over gRPC

`crictl exec … echo hi-from-exec` printed `hi-from-exec`, but the gRPC response to `Exec` was only a
URL:

```text
ExecResponse  0a 24 "http://127.0.0.1:10010/exec/fscXN2Tx"
```

The client then opens a **separate HTTP connection** to containerd's streaming server at that URL
and upgrades it (SPDY or WebSocket) to carry stdin, stdout and stderr. `Attach` and `PortForward`
work the same way. gRPC sets the session up; the terminal bytes travel outside it. (The streaming
connection on port 10010 wasn't captured; only the relay on 9999 was.)

That's also the path behind `kubectl exec`: kubectl → API server → kubelet → `Exec` over CRI → URL
→ the kubelet proxies the stream.

### 3.5 What the kubelet calls for one Pod

The kubelet uses the real socket, not the relay, but containerd logs each CRI call. For
`kubectl run cri-demo --image=busybox:1.36 -- sleep 300` on np-server, then `kubectl delete pod`
(from `/var/lib/rancher/k3s/agent/containerd/containerd.log`, trimmed):

```text
RunPodSandbox for name:"cri-demo" uid:"b61c0410-…" namespace:"default"
RunPodSandbox … returns sandbox id "7a1173ce…"                              ← netns + pause container; CNI runs here
CreateContainer within sandbox "7a1173ce…" for container name:"cri-demo"
CreateContainer … returns container id "a7dca24a…"
StartContainer for "a7dca24a…" returns successfully
                                                                             (pod deleted)
StopContainer for "a7dca24a…" returns successfully
StopPodSandbox for "7a1173ce…" returns successfully
RemoveContainer for "a7dca24a…" returns successfully
```

There's no `PullImage` because busybox was already on the node. `RunPodSandbox` is also where
containerd calls the CNI plugins, which created the Pod's veth and IP (`cni0`, flannel,
[kubernetes-services.md](kubernetes-services.md) part 7).

---
## 4. kubelet → storage driver: CSI (lab)

### 4.1 Three services, three callers (docs)

CSI (`csi.v1`, from the CSI spec's `csi.proto`) splits a driver into three gRPC services. Different
Kubernetes components call each one:

| Service | Methods | Called by | Runs where |
|---|---|---|---|
| `Identity` | `GetPluginInfo`, `GetPluginCapabilities`, `Probe` | everyone, to learn the driver's name and features | with every driver instance |
| `Controller` | `CreateVolume`, `DeleteVolume`, `ControllerPublishVolume` (attach), `ControllerUnpublishVolume`, `CreateSnapshot`, `ControllerExpandVolume` | sidecars: **external-provisioner** (PVC → `CreateVolume`), **external-attacher** (VolumeAttachment → attach), external-snapshotter, external-resizer | one Deployment per cluster, talking to the cloud API |
| `Node` | `NodeStageVolume`, `NodePublishVolume`, `NodeUnpublishVolume`, `NodeUnstageVolume`, `NodeGetInfo`, `NodeGetCapabilities` | **the kubelet** | a DaemonSet, one per node |

The sidecars and the driver share a socket inside the Pod. The kubelet reaches the node part
through a socket on the host, `/var/lib/kubelet/plugins/<driver>/csi.sock`.

### 4.2 A logging CSI driver in 60 lines of Python

To see exactly what the kubelet sends, the lab runs its own driver,
`demo.csi.example.com`. It implements only `Identity` and `Node`, logs every request, and
"mounts" a volume by writing a file into the target directory:

```python
# driver.py (abridged): generated from csi.proto v1.11.0 with grpc_tools.protoc at start-up
class Identity(rpc.IdentityServicer):
    def GetPluginInfo(self, req, ctx):
        log('Identity/GetPluginInfo', req, ctx)
        return pb.GetPluginInfoResponse(name='demo.csi.example.com', vendor_version='0.1.0')
    def GetPluginCapabilities(self, req, ctx): return pb.GetPluginCapabilitiesResponse()   # no CONTROLLER_SERVICE
    def Probe(self, req, ctx): return pb.ProbeResponse()

class Node(rpc.NodeServicer):
    def NodeGetInfo(self, req, ctx):
        log('Node/NodeGetInfo', req, ctx); return pb.NodeGetInfoResponse(node_id=NODE)
    def NodeGetCapabilities(self, req, ctx):
        log('Node/NodeGetCapabilities', req, ctx); return pb.NodeGetCapabilitiesResponse()   # no STAGE_UNSTAGE
    def NodePublishVolume(self, req, ctx):
        log('Node/NodePublishVolume', req, ctx)
        os.makedirs(req.target_path, exist_ok=True)
        open(os.path.join(req.target_path, 'hello.txt'), 'w').write(
            f'written by the CSI driver for volume {req.volume_id} at {time.ctime()}\n')
        return pb.NodePublishVolumeResponse()
    def NodeUnpublishVolume(self, req, ctx):
        log('Node/NodeUnpublishVolume', req, ctx); …; return pb.NodeUnpublishVolumeResponse()

s = grpc.server(futures.ThreadPoolExecutor(4))
rpc.add_IdentityServicer_to_server(Identity(), s); rpc.add_NodeServicer_to_server(Node(), s)
s.add_insecure_port('unix:///csi/csi.sock')       # a Unix socket, no TLS
s.start()
```

Deployed as one Pod on np-server with the standard **node-driver-registrar** sidecar, plus a
`CSIDriver` object:

```yaml
apiVersion: storage.k8s.io/v1
kind: CSIDriver
metadata: { name: demo.csi.example.com }
spec:
  attachRequired: false          # no Controller service, no attach step
  podInfoOnMount: true           # pass the Pod's identity in NodePublishVolume
  volumeLifecycleModes: [Persistent]
---
apiVersion: v1
kind: Pod
metadata: { name: demo-csi-node, namespace: kube-system }
spec:
  nodeName: np-server
  containers:
  - name: driver
    image: python:3.12-slim
    command: ["sh", "-c", "pip install -q grpcio==1.71.0 grpcio-tools==1.71.0 && exec python -u /app/driver.py"]
    env: [{ name: NODE_NAME, valueFrom: { fieldRef: { fieldPath: spec.nodeName } } }]
    securityContext: { privileged: true }
    volumeMounts:
    - { name: app, mountPath: /app }                         # driver.py from a ConfigMap
    - { name: plugin-dir, mountPath: /csi }                  # where csi.sock is created
    - { name: pods, mountPath: /var/lib/kubelet/pods }       # to reach the target paths
  - name: registrar
    image: registry.k8s.io/sig-storage/csi-node-driver-registrar:v2.12.0
    args: ["--csi-address=/csi/csi.sock",
           "--kubelet-registration-path=/var/lib/kubelet/plugins/demo.csi.example.com/csi.sock", "--v=5"]
    volumeMounts:
    - { name: plugin-dir, mountPath: /csi }
    - { name: registration, mountPath: /registration }
  volumes:
  - { name: app, configMap: { name: demo-csi-driver } }
  - { name: plugin-dir, hostPath: { path: /var/lib/kubelet/plugins/demo.csi.example.com, type: DirectoryOrCreate } }
  - { name: registration, hostPath: { path: /var/lib/kubelet/plugins_registry, type: Directory } }
  - { name: pods, hostPath: { path: /var/lib/kubelet/pods, type: Directory } }
```

### 4.3 Registration: three sockets, gRPC in both directions

```text
$ ls -la /var/lib/kubelet/plugins/demo.csi.example.com/ /var/lib/kubelet/plugins_registry/
srwxr-xr-x 1 root root 0  …/plugins/demo.csi.example.com/csi.sock                 ← the driver (CSI)
srwx------ 1 root root 0  …/plugins_registry/demo.csi.example.com-reg.sock          ← the registrar (plugin registration API)
```

Logs from both containers, in order:

```text
registrar  "GRPC call" method="/csi.v1.Identity/GetPluginInfo" request="{}"          1 registrar → driver
driver     --> Identity/GetPluginInfo   (peer=unix:, user-agent=grpc-go/1.65.0)
registrar  "CSI driver name" csiDriverName="demo.csi.example.com"
registrar  "Registration Server started" socketPath="/registration/demo.csi.example.com-reg.sock"   2 registrar now serves a socket
registrar  "Received GetInfo call" request="&InfoRequest{}"                         3 kubelet → registrar
driver     --> Node/NodeGetInfo   (peer=unix:, user-agent=grpc-go/1.72.1)            4 kubelet → driver
registrar  "Received NotifyRegistrationStatus call" status="&RegistrationStatus{PluginRegistered:true,Error:,}"   5 kubelet → registrar

$ kubectl get csinode np-server -o jsonpath='{.spec.drivers[*].name}'
demo.csi.example.com                                                                6 kubelet publishes it
```

```text
                 1 GetPluginInfo
  registrar ───────────────────────────► driver (csi.sock)
     ▲ 2 serves -reg.sock                     ▲
     │                                        │ 4 NodeGetInfo
     │ 3 GetInfo  5 NotifyRegistrationStatus  │
     └─────────────────── kubelet ────────────┘
                (watches /var/lib/kubelet/plugins_registry for new sockets)
```

- The kubelet **watches `plugins_registry/`** for new socket files. Dropping a socket there is how
  any plugin (CSI, device plugin, DRA) announces itself.
- `GetInfo` tells the kubelet the plugin type, name and the **driver's** socket path
  (`--kubelet-registration-path`, a path on the host, which is why it differs from the `/csi/…`
  path inside the Pod).
- The two clients show different gRPC versions: the registrar is `grpc-go/1.65.0`, the kubelet
  `grpc-go/1.72.1`. The peer is just `unix:`, because a socket has no address.

### 4.4 Mounting: what `NodePublishVolume` carries

A static PersistentVolume for the driver, a PVC bound to it, and a Pod using the PVC:

```yaml
apiVersion: v1
kind: PersistentVolume
metadata: { name: demo-pv }
spec:
  capacity: { storage: 1Gi }
  accessModes: [ReadWriteOnce]
  persistentVolumeReclaimPolicy: Retain
  storageClassName: ""
  csi:
    driver: demo.csi.example.com
    volumeHandle: vol-0001          # the driver's own ID for the volume
    volumeAttributes: { color: blue }
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata: { name: demo-pvc, namespace: default }
spec: { accessModes: [ReadWriteOnce], storageClassName: "", volumeName: demo-pv, resources: { requests: { storage: 1Gi } } }
---
apiVersion: v1
kind: Pod
metadata: { name: csi-user, namespace: default }
spec:
  nodeName: np-server
  containers:
  - name: c
    image: busybox:1.36
    command: ["sh", "-c", "cat /data/hello.txt; sleep 3600"]
    volumeMounts: [{ name: d, mountPath: /data }]
  volumes: [{ name: d, persistentVolumeClaim: { claimName: demo-pvc } }]
```

What the driver logged when the Pod started:

```text
--> Node/NodeGetCapabilities   (×4)                       ← "do you need NodeStageVolume first?" (no)
--> Node/NodePublishVolume   (peer=unix:, user-agent=grpc-go/1.72.1)
    volume_id: "vol-0001"                                 ← the PV's volumeHandle
    target_path: "/var/lib/kubelet/pods/764607fb-…/volumes/kubernetes.io~csi/demo-pv/mount"
    volume_capability { mount { }  access_mode { mode: SINGLE_NODE_WRITER } }   ← ReadWriteOnce
    volume_context { key: "color"                                    value: "blue" }      ← volumeAttributes
    volume_context { key: "csi.storage.k8s.io/ephemeral"             value: "false" }
    volume_context { key: "csi.storage.k8s.io/pod.name"              value: "csi-user" }   ← podInfoOnMount
    volume_context { key: "csi.storage.k8s.io/pod.namespace"         value: "default" }
    volume_context { key: "csi.storage.k8s.io/pod.uid"               value: "764607fb-…" }
    volume_context { key: "csi.storage.k8s.io/serviceAccount.name"   value: "default" }
```

and the Pod's output:

```text
$ kubectl logs csi-user
written by the CSI driver for volume vol-0001 at Fri Nov 20 09:20:51 2026
```

After `NodePublishVolume` returns OK, the kubelet bind-mounts `target_path` into the container at
`/data`. Deleting the Pod produced the reverse call:

```text
--> Node/NodeUnpublishVolume
    volume_id: "vol-0001"
    target_path: "/var/lib/kubelet/pods/764607fb-…/volumes/kubernetes.io~csi/demo-pv/mount"
```

### 4.5 What a real driver does in addition (docs)

| This lab driver | A real driver (EBS, Azure Disk, Ceph RBD) |
|---|---|
| static PV, created by hand | **`CreateVolume`**: external-provisioner sees a PVC with a StorageClass and asks the driver to create a disk |
| `attachRequired: false` | **`ControllerPublishVolume`**: external-attacher sees a VolumeAttachment and asks the driver to attach the disk to the node (cloud API) |
| no `STAGE_UNSTAGE_VOLUME` capability | **`NodeStageVolume`**: format and mount the device once per node at a global path; then `NodePublishVolume` bind-mounts it per Pod |
| writes a file into `target_path` | **mounts** a filesystem there, which needs `mountPropagation: Bidirectional` on the kubelet directory so the mount is visible outside the driver's container |

The gRPC side is identical: the same `csi.v1` calls over the same kind of Unix socket.

---

## 5. The three side by side

| | kube-apiserver → etcd | kubelet → runtime (CRI) | kubelet → driver (CSI) |
|---|---|---|---|
| gRPC package | `etcdserverpb` | `runtime.v1` | `csi.v1` |
| transport in the lab | TCP, plaintext h2c | Unix socket (relayed for capture) | Unix socket |
| transport in production | TCP + mutual TLS; k3s: Unix socket to kine | Unix socket | Unix socket |
| access control | client certificates | socket file permissions | socket file permissions |
| connections | ~65, one per resource storage client | one per client (crictl opened 4) | one per caller (registrar, kubelet) |
| typical call | `Txn` with compare + put / delete | `RunPodSandbox`, `CreateContainer` | `NodePublishVolume` |
| streaming | `Watch` (bidirectional, open for hours) | `GetContainerEvents`; exec/attach go **outside** gRPC via a URL | none in the node path |
| payload inside | Kubernetes objects as `k8s\x00` protobuf blobs | container configs | volume IDs, paths, context map |

---

## 6. Cleaning up the labs

```bash
# etcd lab
docker rm -f etcd apiserver cap && docker network rm grpc-lab
# CRI and CSI labs, on np-server
kubectl delete pod criproxy csi-user
kubectl delete pvc demo-pvc && kubectl delete pv demo-pv
kubectl -n kube-system delete pod demo-csi-node && kubectl -n kube-system delete configmap demo-csi-driver
kubectl delete csidriver demo.csi.example.com
```

---

## Related notes

- [grpc-java-http2.md](grpc-java-http2.md): how grpc-java builds these frames, batches writes and interleaves streams
- [kubernetes.md](kubernetes.md) and [etcd-storage.md](etcd-storage.md): what the API server stores in etcd
- [watching-the-api-server.md](watching-the-api-server.md): the watches served from the watch cache (§2.6)
- [azure-local-lab.md](azure-local-lab.md) §7: k3s, kine and `state.db`
- [network-policy.md](network-policy.md) §6: the k3s lab cluster
- [secrets-and-env.md](secrets-and-env.md): KMS-based encryption at rest, another gRPC plugin
- [grpc-load-balancing.md](grpc-load-balancing.md): why a gRPC client behind a ClusterIP uses one Pod, and the fixes.

## Sources

- gRPC over HTTP/2 protocol: https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md
- HTTP/2, RFC 9113: https://www.rfc-editor.org/rfc/rfc9113
- HPACK, RFC 7541: https://www.rfc-editor.org/rfc/rfc7541
- Protocol Buffers encoding: https://protobuf.dev/programming-guides/encoding/
- etcd API (`rpc.proto`, `kv.proto`): https://github.com/etcd-io/etcd/tree/main/api
- CRI API: https://github.com/kubernetes/cri-api/blob/master/pkg/apis/runtime/v1/api.proto
- CSI spec v1.11.0: https://github.com/container-storage-interface/spec/blob/v1.11.0/spec.md
- Kubernetes CSI developer docs (sidecars, registration): https://kubernetes-csi.github.io/docs/
- kine: https://github.com/k3s-io/kine
