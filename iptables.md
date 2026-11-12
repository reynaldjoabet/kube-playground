# iptables: how Linux packet filtering and NAT work

The outputs below are real, and anything described but not measured is marked.
The examples use one network: a client, a router that forwards between two
subnets, and a server. All rules are on the router.

```
 client 10.0.1.2 ── 10.0.1.1 [ router ] 10.0.2.1 ── 10.0.2.2 server
```

Connection tracking (conntrack) has its own document: [conntrack.md](conntrack.md).
To see how Kubernetes uses all of this, read [kubernetes-services.md](kubernetes-services.md).

## 0. Terms and the lab

### 0.1 Terms

| Term | Meaning |
|---|---|
| **Packet** | One unit of network data: headers (addresses, ports, flags) plus payload |
| **Network namespace** | A separate copy of the kernel's network stack: its own interfaces, routes and firewall rules. Each "machine" in the lab is one |
| **veth pair** | A virtual cable: two interfaces where whatever goes in one comes out the other |
| **Routing decision** | The kernel's choice of where a packet goes next: to a local process, or out of an interface |
| **Forwarding** | Passing a packet that's addressed to another machine on to it. Off by default; `net.ipv4.ip_forward=1` turns it on |
| **netfilter** | The packet-filtering framework inside the Linux kernel. iptables and nftables are both front ends to it |
| **Hook** | A fixed point in the packet path where netfilter runs rules (§1) |
| **Table, chain, rule** | A table groups chains by job; a chain is an ordered list of rules; a rule is matches plus a target (§2) |
| **Match** | A condition on the packet (`-p tcp --dport 80`). All of a rule's matches must be true for it to apply |
| **Target** | What to do when a rule matches (`-j ACCEPT`, `-j DNAT ...`) (§4.1) |
| **Verdict** | The final decision about a packet: accept it or drop it |
| **Policy** | A built-in chain's default verdict for packets no rule decided (§4.3) |
| **conntrack** | The kernel's table of connections, which makes stateful rules and NAT possible ([conntrack.md](conntrack.md)) |
| **NAT** | Network address translation: rewriting a packet's source (SNAT) or destination (DNAT) address (§6) |
| **ICMP** | The protocol for network control messages: `ping` (echo request and reply) and errors like "port unreachable" |

### 0.2 The lab

The client, router and server are three network namespaces inside one
privileged Docker container (`ubuntu:22.04` with `iptables`, `conntrack`,
`curl`, `tcpdump` and `python3` installed). This script builds them:

```bash
for n in client router server; do ip netns add $n; done
# two virtual cables: client<->router and router<->server
ip link add c-r netns client type veth peer name r-c netns router
ip link add r-s netns router type veth peer name s-r netns server
# addresses
ip -n client addr add 10.0.1.2/24 dev c-r
ip -n router addr add 10.0.1.1/24 dev r-c
ip -n router addr add 10.0.2.1/24 dev r-s
ip -n server addr add 10.0.2.2/24 dev s-r
for n in client router server; do ip -n $n link set lo up; done
ip -n client link set c-r up; ip -n router link set r-c up
ip -n router link set r-s up; ip -n server link set s-r up
# the client and server send everything else via the router
ip -n client route add default via 10.0.1.1
ip -n server route add default via 10.0.2.1
# the router forwards between its two interfaces
ip netns exec router sysctl -qw net.ipv4.ip_forward=1
```

```
$ ip netns exec client ping -c1 10.0.2.2
64 bytes from 10.0.2.2: icmp_seq=1 ttl=63 time=0.972 ms      # ttl 64 - 1 hop = 63: it crossed the router
```

The server runs a small HTTP server on port 8000 that replies with the
address and port it saw the request come from. That's how the NAT examples
show what the server sees:

```python
# /srv-ip.py, started with: ip netns exec server python3 /srv-ip.py 8000 &
import http.server, sys
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        b = ("seen-from=%s:%d\n" % self.client_address).encode()
        self.send_response(200); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("0.0.0.0", int(sys.argv[1])), H).serve_forever()
```

```
$ ip netns exec client curl -s 10.0.2.2:8000
seen-from=10.0.1.2:52022
```

All `iptables` commands below run on the router, i.e. prefixed with
`ip netns exec router`. The prefix is left out to keep them short.

---

## 1. What iptables actually is

The kernel's networking code contains **netfilter**, which is a set of
**hooks**. A hook is a fixed point on a packet's path where registered code
gets to inspect the packet and decide what happens to it. There are five:

| Hook | When it runs |
|---|---|
| `PREROUTING` | As soon as a packet arrives, before the routing decision |
| `INPUT` | The packet is addressed to this machine |
| `FORWARD` | The packet is passing through to somewhere else |
| `OUTPUT` | The packet was created by a local process |
| `POSTROUTING` | Just before the packet leaves, after routing |

`iptables` is a **userspace command** that loads rules into those hooks. It
isn't a daemon and doesn't run anything itself. On current distributions the
same `iptables` syntax writes **nftables** rules into the kernel (§8). Its
companions are `ip6tables` (IPv6), `iptables-save` and `iptables-restore`.

## 2. Tables, chains and rules

Rules are grouped into **tables** by purpose. Each table attaches **chains** to
some of the hooks:

| Table | Purpose | Chains (hooks) |
|---|---|---|
| `raw` | Runs before connection tracking; used to exempt traffic from it (`NOTRACK`) | `PREROUTING`, `OUTPUT` |
| `mangle` | Change packet fields or set marks (TTL, DSCP, `MARK`) | all five |
| `nat` | Rewrite addresses (DNAT, SNAT, MASQUERADE, REDIRECT) | `PREROUTING`, `INPUT`, `OUTPUT`, `POSTROUTING` |
| `filter` | The default: accept or drop | `INPUT`, `FORWARD`, `OUTPUT` |
| `security` | SELinux and other security-module labelling | `INPUT`, `FORWARD`, `OUTPUT` |

A **chain** is an ordered list of **rules**. A rule is a list of **matches**
followed by an optional **target**:

```
iptables -t filter -A FORWARD -p tcp --dport 80 -m conntrack --ctstate NEW -j ACCEPT
         └─ table   └─ chain  └──────── matches ──────────────────────┘    └─ target
```

The **built-in chains** (`INPUT`, `FORWARD`, ...) each have a **policy**, the
verdict for packets that reach the end without a terminating match. You can
also create your own chains (§5).

`-t` picks the table; without it, `filter` is used. A rule can have no target
at all, as several examples below do: it then only counts the packets that
match it, and evaluation carries on with the next rule.

### 2.1 The commands you'll use

Run on a scratch chain `DEMO` in the router, with the output of each:

```
$ iptables -N DEMO                                         # -N: create a new chain
$ iptables -A DEMO -s 10.0.1.2 -p icmp -m comment --comment "ping from client"   # -A: append at the end
$ iptables -A DEMO -p tcp --dport 8000 -m comment --comment "web"
$ iptables -I DEMO 1 -p tcp --dport 22 -m comment --comment "ssh, inserted at the top"   # -I n: insert at position n
$ iptables -A FORWARD -j DEMO                              # send forwarded traffic through DEMO
```

After two pings and one HTTP request from the client:

```
$ iptables -L DEMO -n -v --line-numbers                    # -L: list; -n: numbers, not names; -v: counters
Chain DEMO (1 references)
num   pkts bytes target  prot opt in  out  source      destination
1        0     0         tcp  --  *   *    0.0.0.0/0   0.0.0.0/0    tcp dpt:22 /* ssh, inserted at the top */
2        2   168         icmp --  *   *    10.0.1.2    0.0.0.0/0    /* ping from client */
3        6   397         tcp  --  *   *    0.0.0.0/0   0.0.0.0/0    tcp dpt:8000 /* web */
```

- `1 references`: one rule elsewhere jumps here (the `FORWARD` one).
- `pkts`/`bytes`: packets that matched. Each rule sees only one direction of
  the traffic:
  - Rule 2 counted the 2 echo requests. The replies come *from* `10.0.2.2`,
    so `-s 10.0.1.2` doesn't match them.
  - Rule 3 counted 6 packets, all sent by the client: the handshake, the
    request, acknowledgements and the close. The server's replies have
    *source* port 8000, not destination port 8000, so they don't match.
- `*` under `in`/`out`: any interface. `0.0.0.0/0`: any address.

The same chain in the form you'd type it (`-S` prints rules as commands, which
is easier to copy than `-L`):

```
$ iptables -S DEMO
-N DEMO
-A DEMO -p tcp -m tcp --dport 22 -m comment --comment "ssh, inserted at the top"
-A DEMO -s 10.0.1.2/32 -p icmp -m comment --comment "ping from client"
-A DEMO -p tcp -m tcp --dport 8000 -m comment --comment web
```

`--dport` needs the `tcp` match module, so `iptables` added `-m tcp` for you.
`10.0.1.2` became `10.0.1.2/32`, a single address.

Changing and removing rules:

```
$ iptables -R DEMO 3 -p tcp --dport 8080 -m comment --comment "web, replaced"   # -R n: replace rule n
$ iptables -D DEMO 1                                       # -D n: delete rule n (or give the whole rule)
$ iptables -Z DEMO                                         # -Z: zero the counters
$ iptables -L DEMO -n -v --line-numbers
num   pkts bytes target  prot opt in  out  source      destination
1        0     0         icmp --  *   *    10.0.1.2    0.0.0.0/0    /* ping from client */
2        0     0         tcp  --  *   *    0.0.0.0/0   0.0.0.0/0    tcp dpt:8080 /* web, replaced */
```

Deleting rule 1 renumbered the others: positions are not stable IDs, so
re-list before using a number.

Removing the chain, in the only order that works:

```
$ iptables -D FORWARD -j DEMO      # 1. remove the jump (a referenced chain can't be deleted)
$ iptables -F DEMO                 # 2. -F: flush, i.e. delete all its rules
$ iptables -X DEMO                 # 3. -X: delete the now-empty, unreferenced chain
```

`-P <chain> <verdict>` sets a built-in chain's policy, e.g.
`iptables -P FORWARD DROP`.

### 2.2 Reading `iptables-save` output

`iptables-save` prints a whole table in the format `iptables-restore` loads.
With a stateful rule, a web rule, a `DROP` policy and one HTTP request
(`-c` includes the counters):

```
$ iptables-save -c -t filter
# Generated by iptables-save v1.8.7 on Thu Oct 29 11:20:07 2026
*filter                                   ← start of the filter table
:INPUT ACCEPT [0:0]                       ← built-in chain, its policy, [packets:bytes] that hit the policy
:FORWARD DROP [0:0]
:OUTPUT ACCEPT [0:0]
[11:795] -A FORWARD -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT    ← [packets:bytes] for this rule
[1:60] -A FORWARD -p tcp -m tcp --dport 8000 -m comment --comment "allow web" -j ACCEPT
COMMIT                                    ← end of the table: everything above is applied at once
# Completed on Thu Oct 29 11:20:07 2026
```

The one new connection's first packet (60 bytes, the SYN) matched the web
rule. Its other 11 packets, in both directions, matched the stateful rule
first. User-defined chains appear as `:NAME - [0:0]`, with `-` because they
have no policy. This format is what you keep in version control and load
with `iptables-restore` (§10).

When several tables use the same hook, their chains run in priority order,
lowest first. Listing the router's ruleset through nftables shows each chain's
priority:

```
table ip raw      type filter hook prerouting  priority raw      # -300
table ip mangle   type filter hook prerouting  priority mangle   # -150
table ip nat      type nat    hook prerouting  priority dstnat   # -100
table ip filter   type filter hook forward     priority filter   #    0
table ip security type filter hook forward     priority 150
table ip nat      type nat    hook postrouting priority srcnat   #  100
```

The numbers in comments are the values nftables uses for those names.
Connection tracking registers itself at −200, between `raw` and `mangle`
(§3.3 proves the ordering).

## 3. A packet's path, measured

### 3.1 Forwarded traffic and local traffic

I added a counting-only rule (a match with no target) to every chain of every
table on the router, then sent three pings. The first run went *through* the
router, the second went *to* it. The counting rules were added like this:

```bash
for t in raw mangle nat filter security; do
  for c in $(iptables -t $t -S | awk '/^-P/ {print $2}'); do   # the table's built-in chains
    iptables -t $t -A $c -p icmp -m comment --comment "count $t $c"
  done
done
iptables -A FORWARD -m conntrack --ctstate NEW,ESTABLISHED,RELATED   # switches conntrack on (§3.2)
ip netns exec client ping -c3 10.0.2.2                 # then the same with 10.0.1.1
iptables -t <table> -L <chain> -n -v                   # read each counter
```

```
== 3 pings client -> server (FORWARDED through router) ==
  raw        PREROUTING   6 pkts
  mangle     PREROUTING   6 pkts
  nat        PREROUTING   1 pkts
  mangle     FORWARD      6 pkts
  filter     FORWARD      6 pkts
  security   FORWARD      6 pkts
  mangle     POSTROUTING  6 pkts
  nat        POSTROUTING  1 pkts

== 3 pings client -> router ITSELF ==
  raw PREROUTING 3   mangle PREROUTING 3   nat PREROUTING 1
  mangle INPUT 3     filter INPUT 3        security INPUT 3    nat INPUT 1
  raw OUTPUT 3       mangle OUTPUT 3       filter OUTPUT 3     security OUTPUT 3
  mangle POSTROUTING 3
```

This shows four things:

- **Forwarded packets never touch `INPUT` or `OUTPUT`.** Their path is
  `PREROUTING → FORWARD → POSTROUTING`. The 6 counts are the 3 echo requests
  plus the 3 replies.
- **Packets addressed to the router stop at `INPUT`.** Its replies start fresh
  at `OUTPUT` and leave via `POSTROUTING`.
- **The `nat` table sees only the first packet of each connection (1, not 6).**
  The NAT decision is stored in the conntrack entry, and every later packet,
  replies included, reuses it without consulting `nat` again. That's why a
  `nat` rule changes nothing for connections that already exist
  (see [conntrack.md](conntrack.md) §7).
- **Locally generated replies skip `nat OUTPUT`.** They belong to an existing
  connection.

```
                     ┌──────────── local process ────────────┐
                     │                                       │
                   INPUT                                   OUTPUT
             mangle,filter,security,nat         raw,mangle,nat,filter,security
                     ▲                                       │
 in ─► PREROUTING ─► routing ──────► FORWARD ─────────────► POSTROUTING ─► out
      raw,mangle,nat  decision    mangle,filter,security    mangle,nat
       (conntrack between raw and mangle)
```

### 3.2 conntrack only starts when something needs it

The first run of that experiment showed **0** packets in every `nat` chain,
and `conntrack -L` was empty. Connection tracking wasn't running in the router
namespace at all. On current kernels it's switched on per network namespace,
and only once a rule needs it, such as a NAT target or a `conntrack` match. My
counting rules needed neither. Adding one rule fixed it:

```bash
iptables -I FORWARD -m conntrack --ctstate NEW,ESTABLISHED,RELATED
```

```
conntrack: icmp 1 29 src=10.0.1.2 dst=10.0.2.2 type=8 code=0 id=40759 src=10.0.2.2 dst=10.0.1.2 type=0 code=0 id=40759
```

The practical consequence: in a namespace with no stateful rules there are no
conntrack entries, the `nat` table is never consulted, and `conntrack -L`
shows nothing. That's not a bug.

### 3.3 `raw` runs before conntrack

One rule asking for `ctstate NEW` in `raw` and one in `mangle`, then three
pings:

```
[0:0] -A PREROUTING -p icmp -m conntrack --ctstate NEW -m comment --comment rawNEW      # raw
[6:504] -A PREROUTING -p icmp -m comment --comment rawANY                               # raw
[1:84] -A PREROUTING -p icmp -m conntrack --ctstate NEW -m comment --comment mangleNEW  # mangle
```

Every packet reaches `raw` (6), but none of them has a connection state there
yet (0). By `mangle` the first packet is `NEW` (1). That's the reason `NOTRACK`
has to go in `raw`: it's the only place that runs before tracking begins.

## 4. Rules: matches, targets and order

### 4.1 Targets end processing, or don't

A **terminating** target ends the walk through the chain. A
**non-terminating** target acts on the packet and the walk continues.

| Target | Terminating | Effect |
|---|---|---|
| `ACCEPT` | yes | Pass this chain, and the rest of the current table for this hook |
| `DROP` | yes | Discard silently |
| `REJECT` | yes | Discard and tell the sender (ICMP error or TCP RST) |
| `DNAT`, `SNAT`, `MASQUERADE`, `REDIRECT` | yes | Rewrite addresses (`nat` table only) |
| `RETURN` | yes, for the current chain | Go back to the calling chain; at the end of a built-in chain, apply its policy |
| `LOG`, `NFLOG` | no | Record the packet and continue |
| `MARK`, `CONNMARK`, `TOS`, `TTL` | no | Change metadata or fields and continue |
| `CT --notrack` | no | Skip connection tracking (`raw` only) |

`ACCEPT` doesn't mean "deliver now". It only ends the current table. The packet
still goes through the remaining tables on that hook and later hooks.

### 4.2 DROP and REJECT, timed

A client making an HTTP request through the router, with a 3-second timeout:

```
no rules, policy ACCEPT                      curl exit=0  http=200  0.11s
-j DROP                                      curl exit=28 http=000  3.02s   # hangs until the timeout
-j REJECT (default icmp-port-unreachable)    curl exit=7  http=000  0.01s   # refused straight away
-j REJECT --reject-with tcp-reset            curl exit=7  http=000  0.01s
```

- **`DROP`** leaves the client waiting out its timeout and retransmitting. That
  costs the caller resources and makes failures slow and hard to diagnose. Use
  it for traffic you don't want to acknowledge, such as the internet edge or
  scanners.
- **`REJECT`** fails fast. Use it inside your own network, where a quick,
  clear error is better.

### 4.3 First match wins, and the policy is the fallback

```
ACCEPT then DROP                             curl exit=0  http=200  0.01s
DROP then ACCEPT                             curl exit=28 http=000  3.02s
```

The policy only applies to packets that fall off the end of a built-in chain:

```
no rules, policy DROP                        curl exit=28 http=000  3.02s
policy DROP + ACCEPT dport 80 only           curl exit=28 http=000  3.01s   # <- the classic mistake
policy DROP + ESTABLISHED + ACCEPT dport 80  curl exit=0  http=200  0.01s
```

The middle line is the most common firewall bug. `--dport 80` matches the
client's packets to the server, but the server's **replies** come *from* port
80, so they hit the `DROP` policy. A stateful accept rule lets the return
traffic through:

```
Chain FORWARD (policy DROP 7 packets, 420 bytes)
num   pkts bytes target  prot  ...
1       12  1810 ACCEPT  all   ... ctstate RELATED,ESTABLISHED
2        4   240 ACCEPT  tcp   ... tcp dpt:80
```

Only the 4 packets of the new connection reached rule 2; the 12 others were
accepted by rule 1.

### 4.4 Matches you'll actually use

```bash
-p tcp --dport 443                         # protocol and port
-m multiport --dports 22,80,443            # several ports in one rule
-s 10.0.0.0/8 -d 192.168.1.10              # source and destination addresses or ranges
-i eth0 -o eth1                            # in and out interfaces (-i not in OUTPUT, -o not in PREROUTING)
-m conntrack --ctstate NEW,ESTABLISHED     # connection state (conntrack.md §5)
-m set --match-set blocked src             # an ipset (§9)
-m limit --limit 10/second --limit-burst 20     # rate limit
-m hashlimit --hashlimit-above 100/sec --hashlimit-mode srcip --hashlimit-name per-ip
-m connlimit --connlimit-above 50          # concurrent connections per source
-m mark --mark 0x4000/0x4000               # a mark set earlier (by MARK or another table)
-m comment --comment "why this rule exists"
-m addrtype --dst-type LOCAL               # destination is one of this host's addresses
```

The `hashlimit` and `connlimit` lines are syntax examples; I didn't measure
them.

**`limit`, measured.** A token bucket: it allows `--limit-burst` matches
straight away, then refills at `--limit`. Only matching packets are
accepted; the rest fall through to the next rule:

```bash
iptables -A FORWARD -p icmp -s 10.0.1.2 -m limit --limit 10/second --limit-burst 5 -j ACCEPT
iptables -A FORWARD -p icmp -s 10.0.1.2 -j DROP
ip netns exec client ping -i 0.01 -c 200 -q 10.0.2.2         # 200 pings, 100 per second
```

```
200 packets transmitted, 27 received, 86.5% packet loss, time 2223ms

   27  2268 ACCEPT  icmp  10.0.1.2  0.0.0.0/0  limit: avg 10/sec burst 5
  173 14532 DROP    icmp  10.0.1.2  0.0.0.0/0
```

The pings took 2.2 s. 5 burst + 10/s × 2.2 s ≈ 27, which is exactly what got
through. Note that `limit` is one bucket for *everything* the rule matches:
all clients share it. For a separate bucket per source address, use
`hashlimit --hashlimit-mode srcip`.

### 4.5 Marks: tagging a packet in one place, acting on it in another

A **packet mark** is a 32-bit number attached to a packet only while it's on
this machine. A **connection mark** is the same thing stored on the conntrack
entry, so it lasts for the whole connection. A common pattern is to decide
once, in `mangle`, and act later, in other tables or in routing:

```bash
# mark new connections to port 8000 with 7 ...
iptables -t mangle -A PREROUTING -p tcp --dport 8000 -m conntrack --ctstate NEW -j MARK --set-mark 0x7
# ... copy the packet's mark onto its connection ...
iptables -t mangle -A PREROUTING -m conntrack --ctstate NEW -j CONNMARK --save-mark
# ... and copy it back onto every later packet of that connection, both directions
iptables -t mangle -A PREROUTING -m conntrack --ctstate ESTABLISHED -j CONNMARK --restore-mark

# count by mark in filter
iptables -A FORWARD -m mark --mark 0x7 -m comment --comment "marked 7"
iptables -A FORWARD -m mark --mark 0x7 -s 10.0.2.2 -m comment --comment "marked 7, reply direction"
iptables -A FORWARD -m mark ! --mark 0x7 -m comment --comment "not marked"
```

One HTTP request and two pings from the client:

```
   12   855  all  0.0.0.0/0  0.0.0.0/0  mark match 0x7 /* marked 7 */
    6   458  all  10.0.2.2   0.0.0.0/0  mark match 0x7 /* marked 7, reply direction */
    4   336  all  0.0.0.0/0  0.0.0.0/0  mark match ! 0x7 /* not marked */

    1    60 MARK      tcp dpt:8000 ctstate NEW MARK set 0x7
    2   144 CONNMARK  ctstate NEW CONNMARK save
   14  1047 CONNMARK  ctstate ESTABLISHED CONNMARK restore
```

- **Only one packet was marked directly** (the SYN, by `MARK`), yet all 12
  packets of the HTTP connection carried mark 7. `--restore-mark` copied it
  from the connection.
- **That includes the 6 replies** from the server, which never matched
  `--dport 8000` at all. That's the point of `CONNMARK`: a decision about a
  connection also applies to its return traffic.
- **The 4 ping packets** (2 requests, 2 replies) belong to a different
  connection, whose mark is 0.

The connection mark is visible in conntrack, next to an older connection from
before the rules existed:

```
TIME_WAIT src=10.0.1.2 dst=10.0.2.2 sport=45154 dport=8000 src=10.0.2.2 dst=10.0.1.2 sport=8000 dport=45154 [ASSURED] mark=0
TIME_WAIT src=10.0.1.2 dst=10.0.2.2 sport=45166 dport=8000 src=10.0.2.2 dst=10.0.1.2 sport=8000 dport=45166 [ASSURED] mark=7
```

kube-proxy uses a packet mark the same way: `KUBE-MARK-MASQ` sets bit
`0x4000` early, and `KUBE-POSTROUTING` masquerades only packets with that bit
([kubernetes-services.md](kubernetes-services.md) §3.4). Policy routing uses
marks too (`ip rule add fwmark 7 table 100`).

## 5. User-defined chains: jump, goto, RETURN

```bash
iptables -N WEB; iptables -N AUDIT
iptables -A AUDIT -j RETURN
iptables -A WEB -j MARK --set-mark 7          # non-terminating: carries on
iptables -A WEB -j AUDIT                      # jump: comes back here afterwards
iptables -A WEB -m comment --comment "web: back from AUDIT"
iptables -A FORWARD -p tcp --dport 80 -j WEB
iptables -A FORWARD -p tcp --dport 80 -m comment --comment "FORWARD: after WEB returned"
```

The same connection, with `-j WEB` (jump) and then `-g WEB` (goto):

```
with -j WEB:  WEB 6 pkts   AUDIT 6   "web: back from AUDIT" 6   "FORWARD: after WEB returned" 6
with -g WEB:  WEB 7 pkts                                         "FORWARD: after WEB returned" 0
```

- **`-j` (jump)** is like a function call. When the target chain ends or
  `RETURN`s, evaluation continues at the next rule of the caller.
- **`-g` (goto)** doesn't come back. When the target chain ends, control goes
  back to whatever called the caller. For a built-in chain that means its
  policy.

User chains are how big rulesets stay manageable: one chain per concern, a
single jump into it from the built-in chain, and a set of chains you can flush
without touching anyone else's rules. kube-proxy (`KUBE-SERVICES`), Docker
(`DOCKER`, `DOCKER-USER`) and Calico (`cali-*`) all do this.

## 6. NAT

| Target | Chain | Rewrites | Typical use |
|---|---|---|---|
| `DNAT` | `PREROUTING`, `OUTPUT` | destination | Port forwarding, load balancing (kube-proxy Services) |
| `REDIRECT` | `PREROUTING`, `OUTPUT` | destination becomes this host | Transparent proxies |
| `SNAT` | `POSTROUTING`, `INPUT` | source, to a fixed address | Shared egress address |
| `MASQUERADE` | `POSTROUTING` | source, to the outgoing interface's address | Dynamic addresses, home routers, Kubernetes |

A server that replies with the address it sees the request coming from:

```bash
# port forward: router:8080 -> server:8000
iptables -t nat -A PREROUTING -d 10.0.1.1 -p tcp --dport 8080 -j DNAT --to-destination 10.0.2.2:8000
# hide the clients behind the router's address on the server side
iptables -t nat -A POSTROUTING -o r-s -j MASQUERADE
```

```
direct to server:                seen-from=10.0.1.2:33766
DNAT router:8080 -> server:8000: seen-from=10.0.1.2:41504    # client IP preserved
DNAT + MASQUERADE out r-s:       seen-from=10.0.2.1:41508    # client IP replaced by the router's
```

The conntrack table records the rewrite for each connection. Each entry holds
the *original* direction followed by the expected *reply* direction:

```
tcp 6 119 TIME_WAIT src=10.0.1.2 dst=10.0.1.1 sport=41504 dport=8080 src=10.0.2.2 dst=10.0.1.2 sport=8000 dport=41504
tcp 6 119 TIME_WAIT src=10.0.1.2 dst=10.0.1.1 sport=41508 dport=8080 src=10.0.2.2 dst=10.0.2.1 sport=8000 dport=41508
                    └──────────────── what the client sent ─────┘ └──── what the reply will look like ─────────┘
```

On the reply side, the first entry shows only DNAT (the reply goes back to the
client's own IP). The second shows both rewrites: the reply is addressed to the
router's `10.0.2.1`.

```
    2   120 DNAT        tcp  ...  10.0.1.1  tcp dpt:8080 to:10.0.2.2:8000
    1    60 MASQUERADE  all  ...  r-s
```

The counters are **per connection, not per packet**: 2 connections went
through DNAT, and 1 of them through MASQUERADE.

**SNAT to a fixed address.** `MASQUERADE` uses whatever address the outgoing
interface has. `SNAT` uses one you name, which must be an address the router
answers for. Here the router gets an extra address, `10.0.2.100`:

```bash
ip addr add 10.0.2.100/24 dev r-s
iptables -t nat -A POSTROUTING -o r-s -s 10.0.1.0/24 -j SNAT --to-source 10.0.2.100
```

```
$ ip netns exec client curl -s 10.0.2.2:8000
seen-from=10.0.2.100:52006

$ conntrack -L -p tcp
tcp 6 119 TIME_WAIT src=10.0.1.2 dst=10.0.2.2 sport=52006 dport=8000 src=10.0.2.2 dst=10.0.2.100 sport=8000 dport=52006 [ASSURED]
```

The server saw `10.0.2.100`, and conntrack expects its reply to be addressed
to `10.0.2.100`, which it will translate back to `10.0.1.2`. The source port
`52006` survived unchanged, because no other connection was using it. Use
`SNAT` when the egress address must be fixed (an allowlist on the far side),
and `MASQUERADE` when the interface's address can change (DHCP).

**REDIRECT to this machine.** `REDIRECT` is DNAT to the router's own
address, typically to send traffic into a local proxy without the client
knowing. A second copy of the server script listens on the router at port
9000:

```bash
ip netns exec router python3 /srv-ip.py 9000 &
iptables -t nat -A PREROUTING -i r-c -p tcp --dport 8000 -j REDIRECT --to-ports 9000
```

```
$ ip netns exec client curl -s 10.0.2.2:8000          # the client still asks for the server
seen-from=10.0.1.2:52020

$ conntrack -L -p tcp
tcp 6 119 TIME_WAIT src=10.0.1.2 dst=10.0.2.2 sport=52020 dport=8000 src=10.0.1.1 dst=10.0.1.2 sport=9000 dport=52020 [ASSURED]
```

The answer came from the router, not the server. The reply tuple shows it:
`src=10.0.1.1 sport=9000`, the router's own address on the client side and
the proxy's port. The client's address is preserved, so the proxy knows who
called. Transparent proxies, and Istio's sidecar interception, work this way.

> **NAT needs a path back through the same box.** If the reply can return
> without passing through the router that did the rewrite (asymmetric routing,
> or a pod replying to itself, which is Kubernetes "hairpin" traffic), it can't
> be translated back, and the client discards it. SNAT and MASQUERADE are often
> there only to force the reply back. See
> [kubernetes-services.md](kubernetes-services.md) §3.4 for a measured example.

## 7. Logging: LOG and NFLOG

`LOG` writes to the kernel log (`dmesg`, journald). Inside network namespaces
and containers you often can't read that. `NFLOG` hands packets to a userspace
listener instead, such as `ulogd` or `tcpdump`:

```bash
iptables -A FORWARD -p tcp --dport 8000 -m conntrack --ctstate NEW \
  -j NFLOG --nflog-group 5 --nflog-prefix "new-web-conn"
tcpdump -n -i nflog:5
```

```
22:53:20.145368 IP 10.0.1.2.44772 > 10.0.2.2.8000: Flags [S], seq 3409701597, ...
22:53:20.146322 IP 10.0.1.2.44784 > 10.0.2.2.8000: Flags [S], seq 4152272337, ...

    2   120 NFLOG  tcp ... tcp dpt:8000 ctstate NEW nflog-prefix new-web-conn nflog-group 5
```

Two notes:

- **Log only `NEW`.** Two requests produced exactly two log lines, one per
  connection, rather than one per packet. Logging every packet of a busy
  connection can overwhelm the log.
- **`LOG` and `NFLOG` don't end processing.** To log and then drop, write two
  rules, or jump to a user chain that does both. Always rate-limit the logging
  rule with `-m limit`.

## 8. Two backends: nft and legacy

Two different kernel interfaces sit behind the same command:

```
/usr/sbin/iptables -> /etc/alternatives/iptables  -> iptables-nft
/usr/sbin/iptables-legacy -> xtables-legacy-multi
/usr/sbin/iptables-nft    -> xtables-nft-multi
```

`iptables-nft` translates each rule into nftables. The kernel stores and
evaluates it as nftables:

```
$ iptables -S FORWARD
-A FORWARD -p tcp -m tcp --dport 80 -m conntrack --ctstate NEW -m comment --comment "allow web" -j ACCEPT

$ nft list chain ip filter FORWARD
chain FORWARD {
    type filter hook forward priority filter; policy accept;
    meta l4proto tcp tcp dport 80 ct state new counter packets 0 bytes 0 accept
}

$ iptables-translate -A INPUT -p tcp -m multiport --dports 22,80,443 -s 10.0.0.0/8 -j ACCEPT
nft add rule ip filter INPUT ip protocol tcp ip saddr 10.0.0.0/8 tcp dport { 22,80,443} counter accept
```

**The backends don't see each other's rules:**

```
$ iptables-legacy -A FORWARD -s 10.7.7.7 -j DROP
iptables-nft sees: 0   iptables-legacy sees: 1
# Warning: iptables-legacy tables present, use iptables-legacy to see them
```

Both rulesets are still active in the kernel. A host where Docker writes
through one backend and kube-proxy or your firewall through the other has two
rulesets that each look complete on their own. That warning line is the only
hint you get. Check `iptables -V` on every component that writes rules.

**An older binary can misread rules a newer one wrote.** On the minikube node
in [kubernetes-services.md](kubernetes-services.md), kube-proxy writes rules
with `iptables v1.8.9`. The node's own `v1.8.7` printed them without their
`--dport` matches, and printed `-m mark ! --mark 0x4000/0x4000 -j RETURN` as a
bare `-j RETURN`. The rules worked correctly; only the display was wrong. When
a listing doesn't make sense, read the rules with the binary that wrote them,
or with `nft list ruleset`.

## 9. Performance: rules are a list

`filter` rules are checked in order for every packet that reaches the chain. To
measure the cost, I averaged 400 pings through the router:

| Setup | Average round trip |
|---|---|
| No rules | 16 µs |
| 20,000 non-matching `-s <ip> -j DROP` rules | **207 µs** |
| Same 20,000 rules, with `ESTABLISHED,RELATED -j ACCEPT` first | 7 µs |
| One rule, `-m set --match-set blocked src`, ipset of 20,000 addresses | 12 µs |

Treat differences of a few microseconds as noise; the 7 µs is below the
no-rules baseline.

- **Every packet walks the list.** With 20,000 rules ahead of the match, each
  packet in both directions paid the full walk: about 10 µs extra per 1,000
  rules, per packet, here.
- **The stateful shortcut is the cheapest optimisation.** Put
  `-m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT` first and only each
  connection's first packet walks the rest.
- **Sets turn a list into a hash lookup.** An ipset (or a native nftables set
  or map) checks 20,000 addresses in one step:

```bash
ipset create blocked hash:ip maxelem 65536
ipset add blocked 192.168.0.1            # or: ipset restore < file   (bulk load)
iptables -A FORWARD -m set --match-set blocked src -j DROP
```

Loading rules costs time too. `iptables-restore` of 20,000 rules took 262 ms;
one more `iptables -A` on top of them took 2 ms with the nft backend. The
legacy backend rewrites the whole table for every change, so there the cost of
an `-A` grows with table size. I didn't measure legacy.

This is the same linear cost that makes kube-proxy's iptables mode slow down
with thousands of Services, and why its nftables mode uses maps instead
([kubernetes-services.md](kubernetes-services.md) §6.1).

## 10. Production practice

**Structure the ruleset like this:**

```bash
iptables -P INPUT DROP                                      # default deny
iptables -A INPUT -i lo -j ACCEPT                           # loopback
iptables -A INPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT   # replies, first
iptables -A INPUT -m conntrack --ctstate INVALID -j DROP    # broken or forged packets
iptables -A INPUT -p icmp -j ACCEPT                         # PMTU discovery breaks without ICMP
iptables -A INPUT -p tcp --dport 22 -s 10.0.0.0/8 -m conntrack --ctstate NEW -j ACCEPT
iptables -A INPUT -m limit --limit 5/min -j NFLOG --nflog-prefix "input-drop"
```

Practices that matter in production:

- **Change rules atomically.** Write the full ruleset to a file and load it with
  `iptables-restore`. It applies one table as a single transaction, so there's
  no moment with half the rules loaded. Check the file first with
  `iptables-restore --test`. A long script of individual `iptables -A` calls
  briefly exposes a partial ruleset, and with the legacy backend it's slow too.
- **Don't lock yourself out.** Run `iptables-apply`, which rolls back unless you
  confirm. Or schedule a rollback first (`at now + 5 min <<< 'iptables-restore
  < /root/good.rules'`), and keep console access. Allow your SSH connection
  before switching the policy to DROP.
- **Share the tables politely.** Docker, kube-proxy, CNI plugins, firewalld and
  fail2ban all write to the same tables. `iptables -F` with no chain name wipes
  their rules too: container port publishing breaks, and Kubernetes Services
  stop working until the next sync. Instead:
  - Put your rules in your own chains, and flush only those.
  - On Docker hosts, use the `DOCKER-USER` chain, which Docker leaves alone.
  - Never mix backends (§8).
- **Expect locking with the legacy backend.** Concurrent writers there serialise
  on `/run/xtables.lock`. Use `iptables -w` so a command waits instead of
  failing when another agent holds the lock.
- **Make rules survive reboots.** Rules disappear on reboot unless something
  restores them: `iptables-persistent`/`netfilter-persistent` on Debian and
  Ubuntu, `iptables-services` on RHEL, or a systemd unit that runs
  `iptables-restore`. `iptables-save -c` also saves the counters.
- **IPv6 is a separate ruleset.** `ip6tables` has its own rules. A host that
  firewalls only IPv4 may still be reachable over IPv6.
- **Watch the counters.** `iptables -L -v -n -x` shows per-rule packet and byte
  counts. A rule stuck at 0 is never matching (dead, shadowed by an earlier
  rule, or in the wrong chain). `-Z` resets the counters before a test.
- **Keep big lists in sets.** Block and allow lists with more than about a
  hundred entries belong in an ipset or an nftables set, not a rule each (§9).
- **Comment every rule** (`-m comment`). In a dump, the comment is the only
  record of why a rule exists.
- **Consider writing nftables directly** for new work. It has native sets and
  maps, atomic replacement of the whole ruleset, a single tool for IPv4 and
  IPv6, and it's where kube-proxy is heading ([nftables.md](nftables.md)).
