# conntrack: connection tracking in the Linux kernel

Connection tracking is the part of netfilter that remembers every flow passing
through a machine. It's what makes stateful firewalls, NAT and Kubernetes
Services work, and it's behind a long list of production incidents.

The examples use the same network as [iptables.md](iptables.md), with the
router doing the tracking:

```
client 10.0.1.2 ── 10.0.1.1 [ router ] 10.0.2.1 ── 10.0.2.2 server
```

Kubernetes source references are to `kubernetes/kubernetes` master. One
scenario, a full table, is described rather than shown (§10). How the lab is
built, and the servers it runs, are in [iptables.md](iptables.md) §0.2. The
server listens on port 8000 for both HTTP (TCP) and a UDP echo that answers
`udp-backend`. All `conntrack` commands run on the router
(`ip netns exec router conntrack ...`).

## 0. Terms

| Term | Meaning |
|---|---|
| **Flow** | All the packets of one conversation, in both directions. For TCP that's a connection; for UDP and ICMP conntrack decides what counts as one (§3.1) |
| **Tuple** | The fields that identify a flow: protocol, source and destination address, source and destination port (or ICMP type, code and id) |
| **Original / reply direction** | Original is the way the first packet went (client → server); reply is the way back |
| **TCP handshake** | How TCP opens a connection: SYN (client), SYN-ACK (server), ACK (client). It closes with FIN packets from each side, or abruptly with an RST |
| **`TIME_WAIT`** | The state after a TCP connection closes, kept for a while so stray late packets aren't mistaken for a new connection |
| **sysctl** | A kernel setting, read and written with `sysctl <name>` or under `/proc/sys/` (`net.netfilter.nf_conntrack_max` is `/proc/sys/net/netfilter/nf_conntrack_max`) |
| **Protocol helper** | A conntrack module that reads inside a protocol (FTP, SIP) to learn which extra connections it will open, so those count as `RELATED` |
| **NAT** | Rewriting a packet's addresses ([iptables.md](iptables.md) §6). It's stored in conntrack entries (§6) |

---

## 1. What conntrack does

For each packet, conntrack works out which **flow** it belongs to and keeps a
table entry per flow. A flow is identified by protocol, source and destination
address, and source and destination port (or ICMP id). Three things depend on
that table:

- **Stateful filtering.** A rule can say "accept replies to connections we
  started" (`--ctstate ESTABLISHED,RELATED`) without knowing which ports the
  replies come from.
- **NAT.** The address rewrite chosen for a flow's first packet is stored in
  its entry and applied to every later packet in both directions
  ([iptables.md](iptables.md) §3.1: the `nat` table counted 1 packet out of 6).
- **Load-balancer stickiness.** Kubernetes Services pick a pod once per
  connection. conntrack keeps the rest of that connection on the same pod.

UDP and ICMP have no connections, so conntrack invents them: a request and its
replies become a "flow" with a timeout.

## 2. Where it runs

Conntrack hooks in at priority −200 in `PREROUTING` and `OUTPUT`: after the
`raw` table and before `mangle`. Every packet arriving at or leaving the host is
looked up there. [iptables.md](iptables.md) §3.3 proved the ordering: `raw` saw
no connection state, `mangle` saw `NEW`.

Where conntrack sits among the other tables, lowest priority number first:

```text
PREROUTING / OUTPUT                          POSTROUTING / INPUT
  -400 defrag   (reassemble fragments)         -150 mangle
  -300 raw      (NOTRACK = skip conntrack)        0 filter        (INPUT only)
  -200 CONNTRACK  look up / create  ◄──┐        +100 SNAT (srcnat)
  -150 mangle                          │        last  CONNTRACK CONFIRM  ◄── save
  -100 DNAT (dstnat)  kube-proxy here  │
     routing decision                  └── the entry exists before NAT,
                                           so NAT can record its choice in it
```

- **Create before DNAT.** The entry records the packet as the client sent it,
  still addressed to the ClusterIP. DNAT then stores its choice of Pod in the
  same entry, so later packets reuse that choice without walking kube-proxy's
  rules again.
- **Confirm at the very end.** The reply side of the entry isn't final until both
  DNAT and SNAT have run. So the entry only goes into the table once both are
  done, and the `[NEW]` event fires at that point.
- **raw goes before conntrack.** That's how `NOTRACK` works. It's also why a
  `NOTRACK` packet can't be NATed: it has no entry to store the NAT choice in.

The nat, raw and mangle priorities were read from `nft list table` on minikube.
Conntrack's own −200 and "last" (`INT_MAX`) are kernel constants
(`NF_IP_PRI_CONNTRACK`, `NF_IP_PRI_CONNTRACK_CONFIRM`) that `nft list hooks` didn't
show there. The full walk of one SYN through these hooks is in
[kubernetes-services.md](kubernetes-services.md) ("Inside the node's kernel:
conntrack, then NAT, then save").

**Tracking is enabled per network namespace, and only on demand.** In a fresh
namespace with only stateless rules, `conntrack -L` returned
`0 flow entries` and the `nat` chains never saw a packet. One rule that needed
state (`-m conntrack --ctstate ...`) turned tracking on
([iptables.md](iptables.md) §3.2). Each container or pod network namespace has
its own conntrack table.

## 3. Reading an entry

```
tcp  6 118 TIME_WAIT src=10.0.1.2 dst=10.0.2.2 sport=39588 dport=8000 src=10.0.2.2 dst=10.0.1.2 sport=8000 dport=39588 [ASSURED] mark=0 use=1
│    │ │   │         └──────────── original direction ───────────┘ └───────────── reply direction ──────────────┘  │         │      │
│    │ │   │                                                                                                   │         │      └ references
│    │ │   └ TCP state (conntrack's own copy of the TCP state machine)                                          │         └ connmark
│    │ └ seconds until it expires if no more packets arrive                                                    └ seen traffic in both directions
│    └ protocol number
└ protocol name
```

| Field | Meaning |
|---|---|
| Original tuple | The flow as the first packet described it |
| Reply tuple | What replies must look like. Without NAT it's the original reversed; with NAT it reveals the rewrite (§6) |
| `[UNREPLIED]` | Only one direction seen so far |
| `[ASSURED]` | Both directions seen. Assured entries are never evicted early when the table fills (§10) |
| `mark` | The connection mark (`CONNMARK`), carried with the flow ([iptables.md](iptables.md) §4.5 shows one set to 7) |
| `use` | How many kernel references hold the entry; useful only when debugging the kernel |

In this entry the reply tuple is the original one reversed, so there's no
NAT. With NAT, the two halves stop mirroring each other (§6).

### 3.1 UDP and ICMP entries

UDP has no handshake, so conntrack infers the flow from the tuple and
decides the timeout from what it has seen. Four cases, each from a fresh
client socket (with `conntrack -L -p udp` on the router after each):

```
# 1. one datagram to the UDP echo, one reply
udp 17 29  src=10.0.1.2 dst=10.0.2.2 sport=46001 dport=8000 src=10.0.2.2 dst=10.0.1.2 sport=8000 dport=46001

# 2. three quick request/reply exchanges on the same ports
udp 17 29  src=10.0.1.2 dst=10.0.2.2 sport=46002 dport=8000 src=10.0.2.2 dst=10.0.1.2 sport=8000 dport=46002

# 3. four exchanges, 1 s apart (the flow lasted over 2 s)
udp 17 118 src=10.0.1.2 dst=10.0.2.2 sport=46004 dport=8000 src=10.0.2.2 dst=10.0.1.2 sport=8000 dport=46004 [ASSURED]

# 4. one datagram to 10.0.2.99, which doesn't exist, so nothing replies
udp 17 28  src=10.0.1.2 dst=10.0.2.99 sport=46003 dport=8000 [UNREPLIED] src=10.0.2.99 dst=10.0.1.2 sport=8000 dport=46003
```

- **Cases 1 and 2** saw replies but stay on the short 30 s timeout and are
  not `[ASSURED]`. A quick request and reply, like a DNS lookup, is treated
  as a one-off.
- **Case 3** became a "stream": `[ASSURED]` with the 120 s
  `udp_timeout_stream`. On this kernel (7.0) that needs replies *and* a flow
  that has lasted more than 2 seconds. Having replies alone, as in case 2,
  wasn't enough.
- **Case 4** is `[UNREPLIED]`: no reply was ever seen. The reply tuple is
  still filled in. It's what conntrack *would* accept as a reply.

A ping creates an ICMP entry keyed on type, code and the ping's id instead of
ports:

```
icmp 1 29 src=10.0.1.2 dst=10.0.2.2 type=8 code=0 id=24309 src=10.0.2.2 dst=10.0.1.2 type=0 code=0 id=24309
          └ type 8 = echo request ───────────────────┘    └ type 0 = echo reply, same id ─────────────────┘
```

Every `ping` run gets a new id, so each run is its own flow, and
`conntrack -C` (count) went up by one.

## 4. A TCP connection's life

`conntrack -E` streams table events as they happen. Run it in one terminal
on the router and make one HTTP request from the client in another:

```bash
ip netns exec router conntrack -E -p tcp --dport 8000     # terminal 1: watch
ip netns exec client curl -s 10.0.2.2:8000                # terminal 2: one request
```

Each line is one change to the entry, with the event type in brackets:

```
[NEW]    tcp 6 120    SYN_SENT    src=10.0.1.2 dst=10.0.2.2 sport=39588 dport=8000 [UNREPLIED] src=10.0.2.2 ...
[UPDATE] tcp 6 60     SYN_RECV    src=10.0.1.2 dst=10.0.2.2 sport=39588 dport=8000 src=10.0.2.2 ...
[UPDATE] tcp 6 432000 ESTABLISHED src=10.0.1.2 dst=10.0.2.2 sport=39588 dport=8000 src=10.0.2.2 ...
[UPDATE] tcp 6 120    FIN_WAIT    ...
[UPDATE] tcp 6 30     LAST_ACK    ...
[UPDATE] tcp 6 120    TIME_WAIT   ...
```

Mapped to the packets that caused each step:

| Event | Caused by | Why that state |
|---|---|---|
| `[NEW] SYN_SENT [UNREPLIED]` | client's SYN | first packet; nothing back yet |
| `SYN_RECV` | server's SYN-ACK | the server answered; the handshake is half done |
| `ESTABLISHED` | client's ACK | handshake complete; this is where the 5-day timer applies |
| `FIN_WAIT` | first FIN (one side finished sending) | closing has started |
| `LAST_ACK` | the other side's FIN | both sides finished; waiting for the last ACK |
| `TIME_WAIT` | the final ACK | closed; kept to absorb late packets |

The request and response themselves don't change the state, so they produce
no events. All of this happened within 3 ms. The entry then stayed in
`TIME_WAIT` for 120 seconds before the kernel removed it (a `[DESTROY]` event
in `conntrack -E`). Each state has its own idle timeout (the
third column), set by sysctl:

| State | Default timeout | Sysctl |
|---|---|---|
| `SYN_SENT` | 120 s | `net.netfilter.nf_conntrack_tcp_timeout_syn_sent` |
| `SYN_RECV` | 60 s | `..._syn_recv` |
| `ESTABLISHED` | **432,000 s (5 days)** | `..._established` |
| `FIN_WAIT` | 120 s | `..._fin_wait` |
| `CLOSE_WAIT` | 60 s | `..._close_wait` |
| `LAST_ACK` | 30 s | `..._last_ack` |
| `TIME_WAIT` | 120 s | `..._time_wait` |
| UDP, not (yet) a stream | 30 s | `net.netfilter.nf_conntrack_udp_timeout` |
| UDP stream (replies seen, flow older than 2 s, §3.1) | 120 s | `..._udp_timeout_stream` |
| ICMP | 30 s | `..._icmp_timeout` |
| Other protocols | 600 s | `..._generic_timeout` |

The 5-day `ESTABLISHED` timeout means a connection that vanished without a FIN
or RST, for example because a client crashed or a middlebox lost its state,
holds a table slot for 5 days. kube-proxy lowers it to 1 day by default (§15).

## 5. Connection states in firewall rules

`-m conntrack --ctstate` matches on how a packet relates to the table. I
counted each state with one rule per state, then sent four kinds of traffic.
The counting rules (no target, so they only count):

```bash
for s in NEW ESTABLISHED RELATED INVALID UNTRACKED; do
  iptables -A FORWARD -m conntrack --ctstate $s -m comment --comment $s
done
iptables -Z FORWARD                     # zero the counters before each run
iptables -L FORWARD -n -v               # read them after
```

Run 3's stray ACK was a hand-built packet, sent with scapy (a Python library
for crafting packets), because a normal socket can't send an ACK without a
connection:

```python
from scapy.all import IP, TCP, send
send(IP(src="10.0.1.2", dst="10.0.2.2")/TCP(sport=46000, dport=8000, flags="A", seq=12345, ack=1), verbose=0)
``` Run 4 needed the `raw`-table
`NOTRACK` rules in §11. The results:

```

1) one HTTP request (handshake, request, response, close)
     NEW 1   ESTABLISHED 12   RELATED 0   INVALID 0   UNTRACKED 0
2) UDP to a closed port on the server, which answers ICMP port-unreachable
     NEW 1   ESTABLISHED 0    RELATED 1   INVALID 0   UNTRACKED 0
3) a stray TCP ACK with no connection (nf_conntrack_tcp_loose=0)
     NEW 0   ESTABLISHED 0    RELATED 0   INVALID 2   UNTRACKED 0
4) UDP to port 9999, which the raw table marks NOTRACK
     NEW 0   ESTABLISHED 0    RELATED 0   INVALID 1   UNTRACKED 1

```

| State | Means | Seen in |
|---|---|---|
| `NEW` | First packet of a flow with no entry yet | the SYN in run 1; the first datagram in run 2 |
| `ESTABLISHED` | Belongs to an entry, in either direction | the other 12 packets in run 1 |
| `RELATED` | A new flow tied to an existing one: ICMP errors about it, or data connections opened by a protocol helper (FTP, SIP) | the ICMP port-unreachable in run 2 |
| `INVALID` | Matches no entry and can't start one, or fails conntrack's TCP checks | the stray ACK and the server's RST in run 3 |
| `UNTRACKED` | Exempted in `raw` with `CT --notrack` (§11) | the request in run 4 |

Run 4 shows a side effect of `NOTRACK`. The server's ICMP error about the
untracked datagram was `INVALID`, because there was no tracked flow for it to
be `RELATED` to. Untracked traffic loses error handling on the return path.

`nf_conntrack_tcp_loose` (default `1`) decides whether conntrack picks up a TCP
flow from its middle, for example after the router rebooted. With `1`, the
stray ACK would have created an `ESTABLISHED` entry. With `0`, only a SYN can
start a flow, which is stricter but breaks existing connections after a
failover.

## 6. NAT lives in the entry

A port forward plus masquerade on the router (setup in
[iptables.md](iptables.md) §6):

```

tcp TIME_WAIT src=10.0.1.2 dst=10.0.1.1 sport=41504 dport=8080   src=10.0.2.2 dst=10.0.1.2 sport=8000 dport=41504
tcp TIME_WAIT src=10.0.1.2 dst=10.0.1.1 sport=41508 dport=8080   src=10.0.2.2 dst=10.0.2.1 sport=8000 dport=41508

```

- **DNAT shows up as the reply's source.** The client sent to `10.0.1.1:8080`,
  but replies come from `10.0.2.2:8000`. The first entry has only DNAT: its
  reply is addressed back to the client's own `10.0.1.2`.
- **SNAT shows up as the reply's destination.** The second entry also had
  masquerade, so the reply is addressed to the router's `10.0.2.1` instead.

```bash
conntrack -L --dst-nat      # 2 entries: flows that were DNATed
conntrack -L --src-nat      # 1 entry: flows that were SNATed
```

The kernel undoes NAT on replies by matching the reply tuple, never by
consulting the `nat` table again.

## 7. Existing flows ignore rule changes

The `nat` table is consulted once, when a flow starts. Changing a NAT rule
therefore has **no effect on flows that already have an entry**. For TCP that's
usually fine, because connections end. For UDP the "connection" ends only after
an idle timeout, and steady traffic keeps resetting it.

An experiment: a UDP client sending from one fixed source port every 0.5 s to
`10.0.1.1:5000`, DNATed to backend A. At t=2 s I repointed the rule to
backend B, and at t=4.5 s I deleted the entry:

```
t=0.0s backend-A
...
t=2.0s backend-A      <- DNAT rule now points to backend-B
t=2.5s backend-A
t=3.0s backend-A
t=3.5s backend-A
t=4.0s backend-A
t=4.5s backend-A      <- conntrack -D -p udp --orig-port-dst 5000
t=5.0s backend-B
t=5.5s backend-B
```

The entry that kept traffic going to backend A:

```
udp 17 119 src=10.0.1.2 dst=10.0.1.1 sport=45000 dport=5000 src=10.0.2.2 dst=10.0.1.2 sport=7001 dport=45000 [ASSURED]
```

Its 119 s timer was reset by every packet. Without the delete, this client
would have used backend A for as long as it kept sending, even if backend A had
been gone.

This is what happens in Kubernetes when a UDP pod is deleted. kube-proxy
handles it explicitly: `CleanStaleEntries` (`pkg/proxy/conntrack/cleanup.go`)
deletes UDP entries whose DNAT target isn't a serving endpoint any more. It
covers ClusterIPs, load balancer IPs, external IPs and NodePorts. The source
comment describes exactly this failure: "One-way UDP flows (e.g. statsd)
refresh the conntrack entry timeout with every packet, so without this cleanup
they keep sending traffic to the deleted endpoint IP indefinitely."

The fix, by hand:

```bash
conntrack -D -p udp --orig-dst <service-ip> --orig-port-dst <port>   # flows to a frontend
conntrack -D -p udp --reply-src <old-backend-ip>                     # flows to a dead backend
```

## 8. INVALID packets and `tcp_be_liberal`

Conntrack checks TCP sequence numbers against each flow's window. I opened a
real connection, captured its sequence numbers, then injected a data packet on
the same flow with a sequence number 2³¹ beyond the window:

```
tcp_be_liberal=0: INVALID=1 pkts
tcp_be_liberal=1: INVALID=0 pkts
```

A first attempt with a guessed sequence number and a wrong acknowledgement
number was **not** flagged INVALID. Only an out-of-window packet with an
otherwise plausible header was. Don't assume every malformed packet is caught.

Why this matters:

- **INVALID packets get no NAT.** They don't match an entry, so they reach the
  destination untranslated. If the receiver answers with an RST, it can tear
  down a connection that was otherwise healthy. Large retransmissions,
  window-scaling mismatches and middleboxes that rewrite sequence numbers can
  all produce out-of-window packets.
- **Two standard fixes:** drop INVALID explicitly
  (`-m conntrack --ctstate INVALID -j DROP`), or make conntrack lenient with
  `net.netfilter.nf_conntrack_tcp_be_liberal=1`. Lenient mode stops marking
  out-of-window packets INVALID.
- **kube-proxy does one of the two.** It adds a `ctstate INVALID -j DROP` rule
  unless liberal mode is configured. The comment in
  `pkg/proxy/iptables/proxier.go` warns of an "unexpected connection reset if
  nf_conntrack_tcp_be_liberal is not set".

## 9. SNAT port exhaustion

SNAT has to give each flow a unique tuple. When many clients share one SNAT
address and all talk to **the same destination address and port**, the only
thing left to vary is the source port. I gave SNAT 5 ports and opened 10
connections:

```bash
iptables -t nat -A POSTROUTING -o r-s -p tcp -j SNAT --to-source 10.0.2.1:40000-40004
```

```
1:ok 2:ok 3:ok 4:ok 5:ok 6:FAIL(TimeoutError) 7:FAIL 8:FAIL 9:FAIL 10:FAIL

SNATed flows (reply dport = SNAT port used):
  sport=9000 dport=40000 ... 40001 ... 40002 ... 40003 ... 40004

conntrack -S: insert_failed=14 drop=14   (before: 0 / 0)
```

The sixth connection didn't get an error. Its SYNs were silently dropped, and
each retry added to `insert_failed`. The client only saw timeouts.

The same 5 ports were enough for 10 connections once they went to two
different destination ports:

```
sport=9000 dport=40000..40004
sport=9001 dport=40000..40004
9000:ok ×5   9001:ok ×5
```

So exhaustion depends on how concentrated the destinations are, not on the
total number of connections. It hits these setups:

- a NAT gateway or node sending all egress to one external API, database or
  proxy
- many short connections, each keeping its port reserved through `TIME_WAIT`
  (120 s in conntrack)

Fixes:

- **Add more SNAT addresses** (`--to-source 10.0.2.1-10.0.2.4`), or give
  workloads their own egress IPs.
- **Pool and keep alive connections** to that hot destination.
- **Use `--random-fully`.** It randomises the port choice, reducing collisions
  when several CPUs pick ports at once. kube-proxy's `MASQUERADE` uses it
  ([kubernetes-services.md](kubernetes-services.md) §3.4).
- **Watch `insert_failed`** (§13).

## 10. Table size, memory, and a full table

**Not demonstrated.** `nf_conntrack_max` can only be changed from the
kernel's initial network namespace, and it affects every namespace on the
host, so a full table wasn't reproduced. What follows comes from the kernel's
documented behaviour and from kube-proxy's source.

| Setting | Value here | Notes |
|---|---|---|
| `nf_conntrack_max` | 262,144 | One global limit. Each namespace's own entry count is checked against it |
| `nf_conntrack_buckets` | 262,144 | Hash table size; read-only outside the initial namespace |
| `nf_conntrack_count` | per namespace | e.g. 343 on a Kubernetes node's namespace, 58 in the host's initial namespace |
| Memory per entry | **256 bytes** | Slab object size from `/proc/slabinfo` |

When a namespace's entry count reaches the limit:

1. The kernel tries to evict an entry that isn't `[ASSURED]` (`early_drop` in
   `conntrack -S`). Half-open and unreplied flows go first.
2. If there are none, the new flow's packet is dropped (`drop`), and the kernel
   logs `nf_conntrack: nf_conntrack: table full, dropping packet`.
3. Existing flows keep working; only *new* flows fail. From outside it looks
   like random connection timeouts that hit some requests and not others.

Sizing guidance:

- **Memory is cheap.** 1 million entries at 256 bytes is about 256 MB plus the
  hash table, so raising the limit on a busy NAT box or Kubernetes node is
  usually fine.
- **Fewer long-lived junk entries beats a bigger table.** Lower
  `..._timeout_established` so dead connections don't sit for 5 days.
- **Keep `buckets` sized to the limit.** Set it at module load
  (`nf_conntrack hashsize=`) to roughly `max` divided by a small factor.
  Lookups degrade when buckets are much fewer than entries.

kube-proxy sizes the limit itself (§15).

## 11. NOTRACK: skipping conntrack

High-volume traffic that doesn't need state, such as a DNS server's port 53 or
a load balancer's health checks, can skip tracking entirely:

```bash
iptables -t raw -A PREROUTING -p udp --dport 9999 -j CT --notrack
iptables -t raw -A OUTPUT     -p udp --sport 9999 -j CT --notrack   # the replies too
```

The datagram to port 9999 matched `UNTRACKED` and
`conntrack -L -p udp --dport 9999` showed **0 entries** (§5, run 4).

What you give up:

- **No NAT.** Untracked packets can't be DNATed or SNATed, so this doesn't work
  in front of a Kubernetes Service or a port forward.
- **No `ESTABLISHED` matching.** Replies must be allowed by explicit stateless
  rules in both directions.
- **No `RELATED`.** ICMP errors about untracked traffic arrive as `INVALID`, as
  in §5 run 4. Drop-INVALID rules then discard them.

Use it only where those don't matter, typically for a public resolver or a
high-packet-rate UDP service.

## 12. Zones

*Described, not demonstrated.* Conntrack keys entries on the tuple alone. Two
networks with overlapping addresses that pass through the same box (VRFs,
multi-tenant routers) can collide. A zone adds an extra key:

```bash
iptables -t raw -A PREROUTING -i tenant-a -j CT --zone 1
iptables -t raw -A PREROUTING -i tenant-b -j CT --zone 2
conntrack -L --zone 1
```

Each network namespace already has its own table, so containers don't need
zones.

## 13. Tools and monitoring

```bash
conntrack -L [-p tcp] [--dport 443] [--src 10.0.1.2]   # list, with filters
conntrack -L --dst-nat / --src-nat                     # only NATed flows
conntrack -E [-e NEW,DESTROY] [-o timestamp]           # live event stream
conntrack -D -p udp --orig-port-dst 53                 # delete matching entries
conntrack -F                                           # flush everything (all flows re-evaluate; NAT flows may break)
conntrack -C                                           # count
conntrack -S                                           # per-CPU statistics
cat /proc/sys/net/netfilter/nf_conntrack_count         # count without the tool
```

`conntrack -S` after the SNAT test:

```
cpu=0  found=0 invalid=0 insert=0 insert_failed=1 drop=1 early_drop=0 error=0 search_restart=0 (null)=0 (null)=0
cpu=1  found=0 invalid=0 insert=0 insert_failed=6 drop=6 early_drop=0 ...
```

The `(null)=0` fields are statistics this kernel reports that
`conntrack v1.4.6` doesn't know the names of. The counters are per CPU; sum
them.

| Counter | Rising means |
|---|---|
| `drop` | New flows dropped: table full (§10) or a NAT tuple couldn't be allocated (§9) |
| `early_drop` | Table full; unassured entries are being evicted to make room |
| `insert_failed` | A new entry collided with an existing one: SNAT port clash (§9), or the UDP DNS race (§14) |
| `invalid` | Packets conntrack couldn't process |
| `search_restart` | Lookups retried under contention; high values mean the hash table is too small or very busy |

What to alert on:

- **Table filling up.** Alert when `nf_conntrack_count / nf_conntrack_max`
  exceeds about 75%. node_exporter exposes these as
  `node_nf_conntrack_entries` and `node_nf_conntrack_entries_limit`.
- **The rate of `drop`, `early_drop` and `insert_failed`.** With
  `--collector.conntrack`, node_exporter exports them as
  `node_nf_conntrack_stat_*`. Any steady non-zero rate is worth investigating.
- **`table full` lines** in the kernel log.

## 14. Production failure modes

| Symptom | Likely cause | Check | Fix |
|---|---|---|---|
| Random new-connection timeouts under load; existing connections fine | Table full | `count` vs `max`, `drop`/`early_drop`, `table full` in `dmesg` | Raise `nf_conntrack_max` and `hashsize`; lower the established timeout |
| Timeouts to one busy external destination only | SNAT port exhaustion | `insert_failed`, `conntrack -L --src-nat --dport <port> \| wc -l` | More SNAT IPs, connection pooling, `--random-fully` |
| UDP keeps hitting a dead or old backend after a change | Stale entry | `conntrack -L -p udp --dport <port>` shows the old DNAT target | `conntrack -D`; make sure kube-proxy cleanup runs |
| Occasional 5 s DNS delays in containers | Concurrent A and AAAA queries from one socket racing to insert the same UDP entry, so one is dropped (`insert_failed`) | `insert_failed` rising with DNS traffic | NodeLocal DNSCache, `single-request-reopen` in resolv.conf, TCP for DNS |
| Connections reset mid-transfer behind NAT | Out-of-window packets marked INVALID and passed through untranslated | INVALID counter on a `ctstate INVALID` rule | `ctstate INVALID -j DROP` or `tcp_be_liberal=1` |
| Connections break after a firewall failover | New box has no entries; with `tcp_loose=0` mid-stream packets are INVALID | Entry count on the new box | Keep `tcp_loose=1`, or sync state with `conntrackd` |
| Replies dropped on a multi-homed host | Asymmetric routing: the reply comes back through another box, which sees it as INVALID or NEW | `conntrack -E` on both paths | Fix routing, or masquerade so replies come back through the same box |
| Table full of `ESTABLISHED` entries for long-gone clients | 5-day default timeout | `conntrack -L \| awk '{print $4}' \| sort \| uniq -c` | Lower `nf_conntrack_tcp_timeout_established` |

The DNS race row is well documented for Kubernetes, but I didn't reproduce it
here. The other rows correspond to experiments in §7–§10.

## 15. Kubernetes

kube-proxy manages conntrack in four ways (`pkg/proxy/conntrack`,
`pkg/proxy/apis/config/v1alpha1/defaults.go`):

| Setting | kube-proxy default | Kernel default |
|---|---|---|
| `conntrack.maxPerCore` | 32,768 entries per CPU | – |
| `conntrack.min` | 131,072 | – |
| → resulting `nf_conntrack_max` | `max(maxPerCore × cores, min)` | 262,144 here |
| `conntrack.tcpEstablishedTimeout` | 24 h | 5 days |
| `conntrack.tcpCloseWaitTimeout` | 1 h | 60 s |
| `conntrack.tcpBeLiberal` | false, so kube-proxy adds `ctstate INVALID -j DROP` | 0 |
| `conntrack.udpTimeout`, `udpStreamTimeout` | unset (kernel default) | 30 s / 120 s |

**A value of 0 means "leave the kernel setting alone"**, per the field comments
in `staging/src/k8s.io/kube-proxy/config/v1alpha1/types.go`. minikube sets
all of them to 0:

```
conntrack:
  maxPerCore: 0
  tcpEstablishedTimeout: 0s
  tcpCloseWaitTimeout: 0s
```

That's why an established connection on the minikube node showed a timer of
431,996 s: the kernel's 5-day default, not kube-proxy's 24 hours. kind does the
same, because a node running in a container often can't change these values.
On real clusters, check what your distribution actually set with
`sysctl net.netfilter.nf_conntrack_tcp_timeout_established` on a node.

Other Kubernetes-specific effects:

- **Every Service connection uses an entry on the node that DNATs it.** That's
  usually the client pod's node. A node with many pods making short outbound
  connections is the classic table-full case.
- **Stale UDP entries are cleaned up per sync** by `CleanStaleEntries` (§7).
  This runs in kube-proxy's iptables and nftables modes. Replacement dataplanes
  such as Cilium do their own equivalent.
- **Hairpin and `externalTrafficPolicy: Cluster` traffic is masqueraded**, so
  it uses SNAT ports from the node's address and can run into the exhaustion
  in §9 ([kubernetes-services.md](kubernetes-services.md) §3.4, §4.3).
