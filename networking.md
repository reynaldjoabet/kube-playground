 If you had a department of 500 people, one Class C (254) was too small, so you'd grab a Class B (65,534) and waste ~`65,000` addresses. This is exactly what burned through the IPv4 address space and forced the move to classless addressing.
 
 
 `Classful subnetting solved a different problem than the one that caused the waste`

## Subnetting only divides downward
Classful subnetting lets you take a block you've already been allocated and chop it into smaller pieces by borrowing host bits:

`One /16  →  many /24s, /26s, /30s ...   ✓ (dividing down works)`
But it has no way to go upward — you cannot combine several small blocks into one bigger network. And that's precisely what the 500-host department needs.

## The 500-host problem, walked through classfully
Your department needs one network with 500 hosts. Your options were:

### Option A — grab a Class B (/16, 65,534 hosts).
Subnet it if you want, but the allocation was a Class B. You just consumed a 65k block for 500 people → ~`65,000` wasted. Subnetting the Class B into smaller pieces doesn't give the addresses back to anyone else — you still own (and removed from the pool) the whole /16.

### Option B — grab two Class Cs (254 + 254 = 508).
This looks efficient. But classfully it fails for two reasons:
- You can't merge them into one network. 500 hosts in one broadcast domain need one contiguous network. Two Class Cs are two separate networks (e.g. `200.1.1.0` and `200.1.2.0`), each with its own network + broadcast address, requiring a router between them. You can subnet a network smaller, but classful addressing gives you no mask that makes two `/24`s into a single `/23` — because...
- Internet routing was classful too. Routers inferred the mask purely from the leading bits (`110... = /24`). There was no way to advertise `200.1.0.0/23` and have the world's routers honor it. The mask wasn't carried in routing updates, so a "shorter-than-class" prefix was meaningless. Supernetting simply didn't exist


## What CIDR actually changed
CIDR removed the fixed boundary in both directions and carried the mask explicitly in routing:

`500 hosts  →  one /23  =  2⁹ − 2 = 510 usable hosts   ← one network, ~10 wasted`

A `/23` is "half a Class B" / "two Class Cs merged" — a size that was literally unnameable under classful rules. Because the `/23` travels with the route, every router now knows the split is at bit 23, no class inference needed.


## Layer 1 — The outside world: mask = class, always
To any router outside your organization, the mask was purely inferred from the leading bits:

`address starts 110...  →  it's Class C  →  mask is /24. Period.`
There was no field in a routing advertisement carrying a mask, so the global routing system only ever saw networks in Class `A/B/C` sizes. Everyone agreed a `200.1.1.x` address was a `/24` because the bits said so.

## Layer 2 — Inside: subnetting existed, but forced one mask per classful network
Here's the nuance. Inside your own org you could subnet — e.g. take Class C `200.1.1.0/24` and split it into `/26` subnets. So your internal mask (`/26`) differed from the classful default (`/24`). That part was allowed.

But every subnet of that same network number had to use the same mask. This is called FLSM — Fixed-Length Subnet Masking. You could not have one part of `200.1.1.0` be `/26` and another part `/28`.

`Why? Because the routing protocols didn't carry the mask either`
The early classful routing protocols — RIPv1 and IGRP — sent updates that contained the network number but no subnet mask.

## What unlocked variable masks
Classless routing protocols — RIPv2, OSPF, EIGRP, IS-IS, BGP — added the subnet mask (prefix length) into every routing update. Once the mask travels with the route, a router no longer has to infer anything from the class or assume its own mask. That's what made both VLSM (variable masks inside a network) and CIDR (classless allocation/aggregation on the internet) possible


## Classless Subnetting (CIDR) — the modern system
CIDR = Classless Inter-Domain Routing (1993, RFC 1518/1519). It threw out the fixed class boundaries entirely.

The one idea that changes everything
The network/host split can happen at any bit position, not just octet boundaries. The mask (`/n`) is carried explicitly with the address instead of being inferred from the first octet.

So `10.0.0.0` is no longer forced to be a `/8` Class A. It can be `10.0.0.0/12`, `/20`, `/30` — whatever you declare. The leading bits of the address no longer determine anything

*Note: `/31` is a special case — RFC 3021 allows using both addresses on point-to-point links since there's no need for network/broadcast there. `/32` identifies exactly one host.

### VLSM — Variable Length Subnet Masking
VLSM = using different mask lengths on different subnets of the same network, sized to actual need. This is CIDR applied inside your own network. You couldn't do this classfully (one mask per class).

## Supernetting 
The core enabler: a prefix shorter than the classful default is now legal and expressible

Take four Class C networks:
```sh
200.1.0.0 /24
200.1.1.0 /24
200.1.2.0 /24
200.1.3.0 /24
```
Look at the 3rd octet in binary (the part that differs):
```sh
0 = 000000 00
1 = 000000 01
2 = 000000 10
3 = 000000 11
        └─┬─┘ └┬┘
   first 6 bits    last 2 bits
    IDENTICAL      vary 00→11
```    
All four share the first 22 bits (8 + 8 + first 6 of the 3rd octet). Only the last 2 bits of the 3rd octet change, running through 00, 01, 10, 11 — every combination.

### Why anyone bothers — routing table scale
This is the practical payoff. Without aggregation, the global BGP table would carry one entry per /24 — millions of routes. An ISP that owns a contiguous, aligned chunk advertises one supernet route upstream:
```sh
Instead of:  256 × /24 entries
Advertise:     1 × /16 entry     → the rest of the internet stores one line
```
Route aggregation (a.k.a. route summarization) is the reason the IPv4 routing table is in the hundreds-of-thousands range instead of the tens-of-millions.


Each subnet must be uniquely identifiable by its leading bits (no two subnets can share the same network bits, or a router couldn't tell them apart).

`n` bits produce exactly `2ⁿ `unique combinations, each combination is one subnet's identity, and since the bits sit in the high positions of the field, counting through them steps the network address forward by exactly one block size each time.

Every bit in an octet has a fixed place value (what it's worth when it's a 1):
```sh
position:      A   B   C   D   E   F   G   H
place value: 128  64  32  16   8   4   2   1
```
Now we borrow 2 bits for the subnet. The rule is: borrowed bits come off the left (the big-value end). So:

```sh
   A    B  | C    D    E    F    G    H
 128   64  | 32   16    8    4    2    1
 └subnet┘  |  └────── host bits ──────┘
```
"High positions" just means the two leftmost bits — the ones worth 128 and 64. The host bits are the leftovers, worth 32 down to 1.


the "subnet number" is not worth 1 — it's worth what its position says
Here's the crux. When we write the subnet number `00`, `01`, `10`, `11`, that looks like we're counting 0,1,2,3. But those digits live in the `64` and `128` columns, not the ones column. So the actual value they add to the octet is scaled by their place value.

The lowest subnet bit (position B) is worth `64`. So "add 1 to the subnet number" = "add 64 to the octet." That 64 is the block size.

Every bit in an octet has a fixed place value (what it's worth when it's a 1):
```sh
position:      A   B   C   D   E   F   G   H
place value: 128  64  32  16   8   4   2   1
```
Now we borrow 2 bits for the subnet. The rule is: borrowed bits come off the left (the big-value end). So:
```sh
   A    B  | C    D    E    F    G    H
 128   64  | 32   16    8    4    2    1
 └subnet┘  |  └────── host bits ──────┘
```
"High positions" just means the two leftmost bits — the ones worth 128 and 64. The host bits are the leftovers, worth 32 down to 1.

`Same address, different mask → different meaning`

Take the address `192.168.1.70` and look at its 4th octet:

`70 = 0 1 0 0 0 1 1 0`
Now watch what happens when we interpret it with two different masks:

With mask /24 (no subnetting — all 8 bits of the 4th octet are host bits):
```sh
network part          host part
192.168.1 . | 0 1 0 0 0 1 1 0
            → "host #70 on network 192.168.1.0"
```            
With mask /26 (2 bits borrowed):
```sh
network part               host part
192.168.1 . 0 1 | 0 0 0 1 1 0
            └┬┘
        subnet bits = 01 → "host #6 on subnet 192.168.1.64"
```        

Divide `192.168.1.0/24`, borrow 2 bits → new prefix /24 + 2 = /26 → mask 255.255.255.192:

```sh
Subnet	Network address	Mask	Hosts
1	192.168.1.0	255.255.255.192 (/26)	.1–.62
2	192.168.1.64	255.255.255.192 (/26)	.65–.126
3	192.168.1.128	255.255.255.192 (/26)	.129–.190
4	192.168.1.192	255.255.255.192 (/26)	.193–.254
```
 When we split a /24 into four subnets: every device in the building gets the same mask (/26). The four subnets differ only in their network address (.0, .64, .128, .192)

Devices are "on the same network" when their network bits are identical. Their host bits differ

Two devices are "on different networks" because and only because their addresses differ within the mask-covered positions.

```sh
   Subnet A: 192.168.10.0/28                Subnet B: 192.168.10.16/28
   (.1–.14)                                  (.17–.30)

   PC1 ──┐                                  ┌── PC3
         ├──[switch]── Router ──[switch]────┤
   PC2 ──┘        (.1)     (.17)            └── PC4

```
```sh
┌────────── network part (26 bits, covered by mask) ──────────┐┌─ host ─┐
│  zone 1: parent prefix (24 bits)      │ zone 2: borrowed (2)││ zone 3 │
│         192.168.1                     │       00/01/10/11   ││ 6 bits │
└───────────────────────────────────────┴─────────────────────┘└────────┘
```
Now here's the key fact about each zone, across all machines in the building:

| Zone | Value across machines | What differences here mean |
| --- | --- | --- |
| Zone 1 - parent prefix (24 bits) | identical on every single device in the company - everyone is inside 192.168.1 | differences never occur (inside your network) |
| Zone 2 - borrowed bits (2) | identical within a subnet, different between subnets | this is what distinguishes your subnets |
| Zone 3 - host bits (6) | different per machine within a subnet | distinguishes machines |

## Linux routing: route scopes and routing tables

For every destination IP address, the kernel decides one of three things:

- **The address belongs to this machine:** deliver it locally.
- **The address is on a directly connected network:** send it straight out of an
  interface to that address.
- **The address is remote:** send it to a next-hop router. **The next hop must
  itself be directly reachable,** that is, on a connected network.

### Route scopes

A route's **scope** says how far away the destination is: how visible the route
is.

| Scope | Value | Destinations | Example |
|---|---|---|---|
| `host` | 254 | addresses **on this machine** | `local 172.17.0.2 dev eth0 … scope host` |
| `link` | 253 | addresses on a **directly connected network**, reached without a router | `172.17.0.0/16 dev eth0 … scope link` |
| `global` (also called `universe`) | 0 | destinations **beyond the local network**, reached through one or more routers | `default via 172.17.0.1 dev eth0` (the default gateway) |

Two more exist, rarely used: `site` (200, IPv6 site-local) and `nowhere`
(255, a destination that doesn't exist). The names come from
`/etc/iproute2/rt_scopes`.

`ip route` doesn't print `scope global`, because it's the default. `ip -d route`
shows it:

```text
$ ip route show table main
default via 172.17.0.1 dev eth0
172.17.0.0/16 dev eth0 proto kernel scope link src 172.17.0.2

$ ip -d route show table main
unicast default via 172.17.0.1 dev eth0 proto boot scope global
unicast 172.17.0.0/16 dev eth0 proto kernel scope link src 172.17.0.2
```

You can query all routing tables for one scope:

```text
$ ip route show table all scope host        # addresses on this machine
local 127.0.0.0/8 dev lo table local proto kernel src 127.0.0.1
local 127.0.0.1 dev lo table local proto kernel src 127.0.0.1
local 172.17.0.2 dev eth0 table local proto kernel src 172.17.0.2

$ ip route show table all scope link        # directly connected networks
172.17.0.0/16 dev eth0 proto kernel src 172.17.0.2
broadcast 127.255.255.255 dev lo table local proto kernel src 127.0.0.1
broadcast 172.17.255.255 dev eth0 table local proto kernel src 172.17.0.2

$ ip route show table all scope global      # through a router
default via 172.17.0.1 dev eth0
```

`ip route get` shows which kind of route a destination uses:

```text
$ ip route get 172.17.0.2      # this machine's own address    → local (host scope)
local 172.17.0.2 dev lo src 172.17.0.2
$ ip route get 172.17.0.1      # on the connected network      → straight out of eth0 (link scope)
172.17.0.1 dev eth0 src 172.17.0.2
$ ip route get 8.8.8.8         # remote                         → via the gateway (global scope)
8.8.8.8 via 172.17.0.1 dev eth0 src 172.17.0.2
```

### The next hop must be directly reachable

A route `via <gateway>` is accepted only if the gateway is reachable through a
**link-scope** route, so that it sits on a connected network. Otherwise the kernel
refuses it:

```text
$ ip route add 198.51.100.0/24 via 192.0.2.1          # 192.0.2.1 is on no connected network
Error: Nexthop has invalid gateway.
$ ip route add 198.51.100.0/24 via 172.17.0.1         # the connected gateway
$ ip route show 198.51.100.0/24
198.51.100.0/24 via 172.17.0.1 dev eth0
```

`onlink` tells the kernel to trust that the gateway is on the link anyway, even
if no route covers it:

```text
$ ip route add 203.0.113.0/24 via 192.0.2.1 dev eth0 onlink
$ ip route show 203.0.113.0/24
203.0.113.0/24 via 192.0.2.1 dev eth0 onlink
```

Kubernetes CNIs work within this rule. The AWS VPC CNI gives each Pod a default
route via `169.254.1.1`, an address no interface owns. To make that a valid next
hop, it first adds a link-scope route for it inside the Pod
([cloud-kubernetes.md](cloud-kubernetes.md)):

```text
169.254.1.1 dev eth0 scope link        ← makes the gateway "directly reachable"
default via 169.254.1.1 dev eth0       ← now accepted
```

### Routing tables

Routes are grouped into **tables**, and a machine can have many. Four table IDs
are reserved (`/etc/iproute2/rt_tables`):

| ID | Name | Holds |
|---|---|---|
| 255 | `local` | host-scope routes for the machine's own addresses, and broadcast routes. Filled by the kernel |
| 254 | `main` | the routes `ip route` shows and changes by default: connected networks, the default gateway |
| 253 | `default` | empty by default; a last-resort table |
| 0 | `unspec` | "any table" in queries such as `ip route show table all` |

```text
$ ip route show table local
local 127.0.0.0/8 dev lo proto kernel scope host src 127.0.0.1
local 127.0.0.1 dev lo proto kernel scope host src 127.0.0.1
broadcast 127.255.255.255 dev lo proto kernel scope link src 127.0.0.1
local 172.17.0.2 dev eth0 proto kernel scope host src 172.17.0.2
broadcast 172.17.255.255 dev eth0 proto kernel scope link src 172.17.0.2
```

**"Up to 255 tables" was the old limit.** The original netlink field for the
table ID was 8 bits. Linux replaced it with a 32-bit attribute long ago, so
table IDs now run up to about 4.29 billion. In the lab, all four of these worked:

```text
$ ip route add default via 172.17.0.1 table 100
$ ip route add default via 172.17.0.1 table 1000
$ ip route add default via 172.17.0.1 table 100000
$ ip route add default via 172.17.0.1 table 4294967294
$ ip route show table 4294967294
default via 172.17.0.1 dev eth0
```

Give a table a name by adding a line such as `1000 vpn` to
`/etc/iproute2/rt_tables`.

### Which table is used: policy rules

A table is only consulted if a **rule** points to it. `ip rule` lists the rules,
checked in priority order. By default, `local` is checked first, then `main`, then
`default`:

```text
$ ip rule
0:      from all lookup local
1000:   from 10.9.9.9 lookup 1000          ← added: traffic from 10.9.9.9 uses table 1000
32766:  from all lookup main
32767:  from all lookup default
```

The same destination then goes different ways depending on the source address:

```text
$ ip route show table 1000
default via 172.17.0.254 dev eth0 onlink

$ ip route get 8.8.8.8 from 172.17.0.2
8.8.8.8 from 172.17.0.2 via 172.17.0.1 dev eth0                   ← main table
$ ip route get 8.8.8.8 from 10.9.9.9
8.8.8.8 from 10.9.9.9 via 172.17.0.254 dev eth0 table 1000        ← the rule picked table 1000
```

This is how the AWS VPC CNI sends each Pod's traffic out of the right network
interface: one table per ENI, and one `from <pod IP>` rule per Pod
([cloud-kubernetes.md](cloud-kubernetes.md)).

*Lab: every output above is from a throwaway `alpine:3.20` container with
`--cap-add NET_ADMIN` and `iproute2` installed. The container has its own network
namespace, so the host's routes weren't touched.*

### Worked example: a router with two internet links (lab)

A classic use of several tables: a router with **two uplinks** (WAN1, WAN2) and
**two LANs**. LAN1 should leave through WAN1, and LAN2 through WAN2. One main
table can only have one default route, so each WAN gets **its own table with its
own default route**, and **rules pick the table by the client's source subnet**.

```text
                      ┌────── router ──────┐
 LAN1 10.100.0.0/24 ─►│ lan1          wan1 │─► 192.168.10.200 (WAN1 gateway) ─┐
                      │                    │                                  ├─► internet (8.8.8.8)
 LAN2 10.200.0.0/24 ─►│ lan2          wan2 │─► 192.168.12.254 (WAN2 gateway) ─┘
                      └────────────────────┘
```

The lab rebuilds this inside one throwaway `--privileged` container, with
network namespaces for the router, both gateways, the "internet" (8.8.8.8 on a
loopback) and a client in each LAN. The router masquerades out of each WAN.
Paths are shown with `traceroute` from the clients; the second hop is the
gateway used.

**Step 1: before any policy routing.** The main table has one default route, via
WAN1, so both LANs leave through WAN1:

```text
router$ ip route show table main
default via 192.168.10.200 dev wan1
10.100.0.0/24 dev lan1 proto kernel scope link src 10.100.0.1
10.200.0.0/24 dev lan2 proto kernel scope link src 10.200.0.1
192.168.10.0/24 dev wan1 proto kernel scope link src 192.168.10.1
192.168.12.0/24 dev wan2 proto kernel scope link src 192.168.12.1

LAN1 client → 8.8.8.8 hops: 10.100.0.1  192.168.10.200  8.8.8.8
LAN2 client → 8.8.8.8 hops: 10.200.0.1  192.168.10.200  8.8.8.8     ← also WAN1
```

**Step 2: name two tables and give each a default route.**

```text
router$ printf '100\twan1\n200\twan2\n' >> /etc/iproute2/rt_tables
router$ ip route add default via 192.168.10.200 table wan1
router$ ip route add default via 192.168.12.254 table wan2
```

Each table has its own default. **`ip route show` without `table` only shows
`main`**, so the new routes are easy to miss:

```text
router$ ip route show scope global                 ← main only
default via 192.168.10.200 dev wan1
router$ ip route show scope global table wan1
default via 192.168.10.200 dev wan1
router$ ip route show scope global table wan2
default via 192.168.12.254 dev wan2
router$ ip route show table all scope global       ← every table, labelled
default via 192.168.10.200 dev wan1 table wan1
default via 192.168.12.254 dev wan2 table wan2
default via 192.168.10.200 dev wan1
```

Nothing changes yet. LAN2 still leaves via `192.168.10.200`, because **no rule
sends anything to the new tables**. A table on its own is never consulted.

**Step 3: rules that pick the table by source.**

```text
router$ ip rule add from 10.100.0.0/24 table wan1      # "table" and "lookup" mean the same here
router$ ip rule add from 10.200.0.0/24 table wan2
router$ ip rule
0:      from all lookup local
32764:  from 10.200.0.0/24 lookup wan2
32765:  from 10.100.0.0/24 lookup wan1
32766:  from all lookup main
32767:  from all lookup default
```

Without `priority`, each new rule gets the number just below the lowest existing
one (above 0). So **the rule added last is checked first** (32764 before
32765). That doesn't matter here, because the two never match the same traffic.
When they could overlap, set `priority` explicitly.

The router's decision for a packet arriving from each LAN (`iif` simulates
"arrived on that interface"), and the real paths:

```text
router$ ip route get 8.8.8.8 from 10.100.0.10 iif lan1
8.8.8.8 from 10.100.0.10 via 192.168.10.200 dev wan1 table wan1
router$ ip route get 8.8.8.8 from 10.200.0.10 iif lan2
8.8.8.8 from 10.200.0.10 via 192.168.12.254 dev wan2 table wan2

LAN1 client → 8.8.8.8 hops: 10.100.0.1  192.168.10.200  8.8.8.8     ← WAN1
LAN2 client → 8.8.8.8 hops: 10.200.0.1  192.168.12.254  8.8.8.8     ← WAN2
```

**Pitfall 1: traffic between the LANs now goes to the internet.** The rule says
"everything from 10.100.0.0/24 uses table wan1", and table `wan1` has only a
default route. The connected route to LAN2 lives in `main`, which is never
reached:

```text
router$ ip route get 10.200.0.10 from 10.100.0.10 iif lan1
10.200.0.10 from 10.100.0.10 via 192.168.10.200 dev wan1 table wan1     ← out to WAN1!
LAN1 client: ping 10.200.0.10 → 0 packets received
```

The fix is a rule, **checked before the source rules**, that sends internal
destinations to `main`:

```text
router$ ip rule add to 10.0.0.0/8 lookup main priority 100
router$ ip route get 10.200.0.10 from 10.100.0.10 iif lan1
10.200.0.10 from 10.100.0.10 dev lan2
LAN1 client: ping 10.200.0.10 → 2 packets received
```

The other common fix is to copy the connected routes into each custom table
(`ip route add 10.200.0.0/24 dev lan2 table wan1`, …).

**Pitfall 2: an empty table falls through silently.** When the chosen table has
**no matching route**, the kernel doesn't stop. It continues with the next rule:

```text
router$ ip route del default table wan2
router$ ip route get 8.8.8.8 from 10.200.0.10 iif lan2
8.8.8.8 from 10.200.0.10 via 192.168.10.200 dev wan1                   ← fell through to main
LAN2 client → 8.8.8.8 hops: 10.200.0.1  192.168.10.200  8.8.8.8
```

The same happens when **the WAN2 link goes down**. The kernel removes routes
through a downed interface, so `table wan2` becomes empty and LAN2 moves to WAN1:

```text
router$ ip link set wan2 down
router$ ip route show table wan2
(empty)
router$ ip route get 8.8.8.8 from 10.200.0.10 iif lan2
8.8.8.8 from 10.200.0.10 via 192.168.10.200 dev wan1
```

Sometimes that's what you want: automatic failover. Sometimes it isn't, for
example when LAN2 must never leave through WAN1 (a different ISP, a different
public IP, a compliance boundary). To **fail closed** instead, put a
`blackhole` route with a worse metric in the table. It stays when the link goes
down, so the lookup never falls through:

```text
router$ ip route add blackhole default metric 1000 table wan2
router$ ip route show table wan2
default via 192.168.12.254 dev wan2
blackhole default metric 1000
router$ ip link set wan2 down
router$ ip route show table wan2
blackhole default metric 1000
LAN2 client: ping 8.8.8.8 → 0 packets received          ← dropped, not leaked through WAN1
```

What a production version also needs (not part of this lab):

- **The router's own traffic.** Rules such as `from 192.168.12.1 lookup wan2`, so
  replies leaving from a WAN address go back out of the same WAN.
- **NAT per uplink.** One `MASQUERADE` per WAN interface (`-o wan1`, `-o wan2`),
  as in the lab, so each LAN appears under that WAN's public address.
- **Reverse-path filtering.** `rp_filter` set to strict (1) can drop replies that
  come back on an interface the main table wouldn't use. Loose mode (2) is the
  usual setting on multi-WAN routers.
- **Other selectors.** Rules can match more than the source: `to`, `iif`
  (incoming interface), `fwmark` (a mark set by iptables/nftables, for example
  per destination port), `uidrange`, `ipproto` and `dport`.

### More policy-routing examples (lab)

Rules can choose a table by much more than the source subnet. Each example
below ran in the same lab: the dual-WAN router from the worked example, with
tables `wan1` and `wan2`, and a rule `to 10.0.0.0/8 lookup main priority 40`
for LAN-to-LAN traffic. Which uplink a packet really used was counted with
iptables counters on the router's `POSTROUTING` chain, per output interface and
port.

| Example | Rule matches on | Real-world users |
|---|---|---|
| A | a packet **mark** (`fwmark`) set by iptables/nftables | multi-WAN routers (OpenWrt mwan3), split-tunnel VPNs |
| B | the **user** that owns the socket (`uidrange`) | Android (per-app networks), per-user VPNs |
| C | the **source address** of the router's own replies | servers with several NICs, AWS VPC CNI |
| D | a mark, pointing at a table that delivers **any address locally** | Istio (TPROXY mode), Cilium's L7 proxy |
| E | the **absence** of a mark (`not fwmark`), plus `suppress_prefixlength` | `wg-quick`, Tailscale |
| F | source + destination, with a **blocking action** instead of a table | simple segmentation |

#### A. By packet mark: HTTPS via WAN2, everything else via WAN1

iptables marks LAN1's port-443 traffic, and a rule sends marked packets to table
`wan2`. Routing itself can't see ports, but a mark can carry any match iptables
or nftables can make (port, protocol, connection state, …):

```text
router$ iptables -t mangle -A PREROUTING -i lan1 -p tcp --dport 443 -j MARK --set-mark 0x2
router$ ip rule add fwmark 0x2 lookup wan2 priority 50
router$ ip rule
0:      from all lookup local
40:     from all to 10.0.0.0/8 lookup main
50:     from all fwmark 0x2 lookup wan2
32766:  from all lookup main
32767:  from all lookup default

router$ ip route get 8.8.8.8 from 10.100.0.10 iif lan1
8.8.8.8 from 10.100.0.10 via 192.168.10.200 dev wan1
router$ ip route get 8.8.8.8 from 10.100.0.10 iif lan1 mark 0x2
8.8.8.8 from 10.100.0.10 via 192.168.12.254 dev wan2 table wan2 mark 2
```

The LAN1 client connected to 8.8.8.8 on port 80 and on port 443. Packets that
left each uplink:

```text
out wan1:80   1        out wan1:443  0
out wan2:80   0        out wan2:443  1          ← only HTTPS took WAN2
```

#### B. By user: one user's traffic via WAN2

`uidrange` matches the owner of the socket, for traffic the machine itself
sends. Here user 1000 on the router uses WAN2 and everyone else uses WAN1:

```text
router$ ip rule add uidrange 1000-1000 lookup wan2 priority 60
router$ ip route get 8.8.8.8
8.8.8.8 via 192.168.10.200 dev wan1 src 192.168.10.1 uid 0
router$ ip route get 8.8.8.8 uid 1000
8.8.8.8 via 192.168.12.254 dev wan2 table wan2 src 192.168.12.1 uid 1000
```

Root connected to port 80, and user `app` (UID 1000) to port 443:

```text
out wan1:80   1        out wan1:443  0
out wan2:80   0        out wan2:443  1          ← user 1000's connection took WAN2
```

Android is built on this: each app has its own UID, and rules (with UID
ranges and marks) send each app's traffic to the table of the network it should
use: Wi-Fi, mobile data, or a VPN.

#### C. Replies from the right interface (a host with two networks)

A host with an address on each of two networks gets a request on one of them,
but its main table sends the reply out of the other. With **strict reverse-path
filtering** (`rp_filter=1`, the default on many distributions) it gets worse: the
request is dropped on arrival, because the kernel checks that a reply would leave
by the interface the request came in on. The internet host pinged the
router's **WAN2** address, 192.168.12.1:

```text
router$ sysctl -w net.ipv4.conf.all.rp_filter=1 net.ipv4.conf.wan2.rp_filter=1
router$ ip route get 172.31.2.2 from 192.168.12.1
172.31.2.2 from 192.168.12.1 via 192.168.10.200 dev wan1                 ← reply would leave via WAN1
internet$ ping 192.168.12.1 → 0 packets received

router$ ip rule add from 192.168.12.1 lookup wan2 priority 70
router$ ip route get 172.31.2.2 from 192.168.12.1
172.31.2.2 from 192.168.12.1 via 192.168.12.254 dev wan2 table wan2      ← back the way it came
internet$ ping 192.168.12.1 → 2 packets received
```

"Replies from address X use the table of X's interface" is the standard fix for
multi-NIC servers. It's also what the AWS VPC CNI does per Pod, so each Pod's
traffic leaves by the ENI its IP belongs to.

#### D. Deliver any destination locally: transparent proxying (TPROXY)

A table whose only route is `local 0.0.0.0/0 dev lo` makes **every** address
count as local. Packets marked for that table are delivered to the machine
itself, whatever their destination. With iptables' `TPROXY` target, which marks
the packet and steers it to a proxy's port, the proxy receives a connection that
was addressed to somewhere else:

```text
router$ ip route add local 0.0.0.0/0 dev lo table 133
router$ ip rule add fwmark 0x1 lookup 133 priority 80
router$ iptables -t mangle -A PREROUTING -i lan1 -p tcp -d 8.8.8.8 --dport 80 \
          -j TPROXY --on-port 15001 --tproxy-mark 0x1/0x1
router$ socat TCP4-LISTEN:15001,ip-transparent,fork SYSTEM:'echo answered by a proxy on the ROUTER'

router$ ip route show table 133
local default dev lo scope host
router$ ip route get 8.8.8.8 from 10.100.0.10 iif lan1 mark 0x1
local 8.8.8.8 from 10.100.0.10 dev lo table 133 mark 1

LAN1 client → 8.8.8.8:80 says: answered by a proxy on the ROUTER
router's socket during the connection:  local 8.8.8.8:80   peer 10.100.0.10:43271
```

The router's socket **is 8.8.8.8:80**. The client thinks it's talking to
8.8.8.8, and the proxy sees the original destination without NAT. This is how
Istio's TPROXY interception mode and Cilium's L7 proxy hand traffic to Envoy.

The routing rule alone isn't enough. Without `TPROXY` and a socket with
`IP_TRANSPARENT`, the lab's router received the SYN (seen with `tcpdump`) but
never answered. The reply has to come *from* 8.8.8.8, an address the router
doesn't own, and only a transparent socket is allowed to send from it.

#### E. Everything through a tunnel, except the tunnel's own packets (`wg-quick`)

To send all traffic through a VPN tunnel, the default route must point at the
tunnel. But the tunnel's own encrypted packets must still leave by the normal
route, or they'd loop back into the tunnel. `wg-quick` solves it with two rules
(here with a dummy `wg0` device in place of WireGuard):

```text
router$ ip route add default dev wg0 table 51820
router$ ip rule add not fwmark 51820 table 51820 priority 90         # unmarked traffic → the tunnel table
router$ ip rule add table main suppress_prefixlength 0 priority 89   # main, but ignore its default route
router$ ip rule
0:      from all lookup local
40:     from all to 10.0.0.0/8 lookup main
80:     from all fwmark 0x1 lookup 133                ← left over from example D
89:     from all lookup main suppress_prefixlength 0
90:     not from all fwmark 0xca6c lookup 51820
32766:  from all lookup main
32767:  from all lookup default

router$ ip route get 8.8.8.8
8.8.8.8 dev wg0 table 51820 src 10.99.0.1                      ← normal traffic: the tunnel
router$ ip route get 8.8.8.8 mark 51820
8.8.8.8 via 192.168.10.200 dev wan1 src 192.168.10.1 mark 0xca6c   ← the tunnel's packets: the real uplink
router$ ip route get 10.200.0.10
10.200.0.10 dev lan2 src 10.200.0.1                            ← connected networks: still direct
```

- **The tunnel software marks its own encrypted packets with 51820** (`0xca6c`),
  so the "not fwmark" rule skips them and they use `main`.
- **`suppress_prefixlength 0`** means "use `main`, but reject any route whose
  prefix is `/0` or shorter", that is, ignore its default route. Specific routes
  in `main`, such as connected LANs, still win. Only the default is replaced by
  the tunnel.

Tailscale uses the same idea, with its own table (52) and marks.

#### F. Rules that block: `prohibit`, `unreachable`, `blackhole`

A rule can end the lookup with an action instead of a table:

```text
router$ ip rule add from 10.200.0.0/24 to 10.100.0.0/24 prohibit priority 30
router$ ip rule show priority 30
30:     from 10.200.0.0/24 to 10.100.0.0/24 prohibit

LAN2 → LAN1 ping: 1 packets transmitted, 0 packets received
LAN1 → LAN2 ping: 1 packets transmitted, 0 packets received      ← blocked too!
```

The second result is the catch. **Routing rules are stateless.** LAN1's ping
reaches LAN2, but LAN2's reply goes from 10.200.0.0/24 to 10.100.0.0/24 and
matches the rule. For "LAN1 may start connections to LAN2, but not the other
way round", use a firewall with connection tracking (iptables/nftables
`ct state`), not a routing rule.

The three actions differ in what the sender is told (from the `ip-rule` man
page; the lab's BusyBox `ping` only showed the loss, not the ICMP message):

- `prohibit`: an ICMP "administratively prohibited" error is sent back;
- `unreachable`: an ICMP "network unreachable" error is sent back;
- `blackhole`: the packet is dropped silently.

## Link-local addresses (169.254.0.0/16) in clouds and clusters

RFC 3927 §7: "A router MUST NOT forward a packet with an IPv4 Link-Local source or destination
address." Its intended use is hosts giving themselves an address when DHCP fails (Windows APIPA).
The first and last 256 addresses (`169.254.0.x`, `169.254.255.x`) are reserved and "MUST NOT be
selected by a host" (§2.1).

Clouds and network tools use the range because it never leaves the local link, binding metadata,
DNS, time and credential services to fixed addresses inside it. Who answers differs, though:

- **A process on the node itself** (Pod Identity agent, NodeLocal DNSCache): the address is
  configured on the node, so the node's kernel delivers the packet locally.
- **The cloud platform, outside the VM** (EC2 IMDS, Route 53 Resolver, Time Sync): the VM sends the
  packet out of its network interface, and the hypervisor or virtual network answers it.
- **Nobody** (`169.254.1.1` as a Pod gateway): the address is only a next hop to satisfy the
  routing rules ("The next hop must be directly reachable" above), answered by proxy ARP.

Seen on a node of the Calico lab ([calico-layer3.md](calico-layer3.md)): with `169.254.170.23`
configured on it, `ip route get 169.254.170.23` gives `local … dev lo`, while
`ip route get 169.254.169.254` gives `via 172.21.0.1 dev eth0`. A Pod's request to the first stayed
on the node; a SYN to the second left through `eth0`
([workload-identity.md](workload-identity.md) §4, "Does the request leave the node?").

| Address | Service | Who answers | Source |
|---|---|---|---|
| `169.254.169.254` | Instance metadata service: AWS EC2 IMDS, Azure IMDS, GCP metadata server, Oracle OCI, DigitalOcean | the platform | docs (Azure, GCP, OCI, DigitalOcean pages; AWS: see `fd00:ec2::254` row) |
| `169.254.169.254` | the same, on OpenStack and Hetzner Cloud | the platform | not verified |
| `169.254.169.253` | Amazon Route 53 Resolver (VPC DNS) | the platform | docs |
| `169.254.169.123` | Amazon Time Sync Service (NTP) | the platform | docs |
| `169.254.169.250` | AWS Windows KMS license activation (`slmgr.vbs /skms 169.254.169.250:1688`) | the platform | docs; a second address `.251` not found in AWS docs |
| `169.254.169.252:988` | GKE metadata server (`gke-metadata-server` in `kube-system`) for Workload Identity Federation; NetworkPolicies must allow egress to it | a Pod on the node | docs |
| `169.254.170.2` | AWS ECS task metadata and IAM credentials (`AWS_CONTAINER_CREDENTIALS_RELATIVE_URI`) | the ECS agent | docs; in the AWS SDKs' allowed-host lists (code) |
| `169.254.170.23` | AWS EKS Pod Identity Agent (`eks-pod-identity-agent`, host network) | a process on the node | docs; SDK code and lab |
| `169.254.1.1` | Pod default gateway in Calico and the AWS VPC CNI | nobody: proxy ARP | lab (Calico); code (VPC CNI) |
| `169.254.20.10` | NodeLocal DNSCache: the address commonly used in its manifests | a process on the node | the Kubernetes docs only say to pick a link-local address; the specific value is not verified |
| `169.254.42.42` | Scaleway instance metadata | the platform | not verified (docs page refused access) |
| `169.254.x.x/30` | BGP peering addresses inside AWS Site-to-Site VPN tunnels | the two tunnel ends | not verified |

Fargate, CodeBuild and App Runner are also said to use `169.254.170.2`; the CodeBuild environment
variable page doesn't mention it, and the others weren't checked. **(not verified)**

**IPv6 equivalents** (AWS uses the unique-local `fd00:ec2::/32`, not link-local `fe80::/10`):

| Address | Service | Source |
|---|---|---|
| `fd00:ec2::254` | EC2 IMDS | docs |
| `fd00:ec2::253` | Route 53 Resolver | docs |
| `fd00:ec2::123` | Time Sync (Nitro instances only) | docs |
| `fd00:ec2::23` | EKS Pod Identity Agent | SDK code and lab |
| `fd20:ce::254` | GCP metadata server, IPv6-only instances | docs |

**Look-alikes outside 169.254.0.0/16:**

- **Azure `168.63.129.16`**, "a virtual public IP address that facilitates communication channels
  to Azure platform resources": the WireServer (VM agent, health probes, Azure DNS). It isn't
  link-local, so firewall rules have to allow it explicitly.
- **Alibaba Cloud `100.100.100.200`**, the ECS metadata service. Alibaba's docs call it "a
  link-local address reachable only from within an ECS instance", but it isn't in
  `169.254.0.0/16`. It's in `100.64.0.0/10`, the carrier-grade NAT range.

Sources: RFC 3927 (https://www.rfc-editor.org/rfc/rfc3927); AWS VPC DNS
(https://docs.aws.amazon.com/vpc/latest/userguide/AmazonDNS-concepts.html); AWS Time Sync
(https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/configure-ec2-ntp.html); AWS Windows KMS
(https://docs.aws.amazon.com/AWSEC2/latest/WindowsGuide/common-messages.html); EC2 IMDS
(https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/instancedata-data-retrieval.html); ECS task
roles (https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-iam-roles.html); GKE
Workload Identity (https://docs.cloud.google.com/kubernetes-engine/docs/concepts/workload-identity);
GCP metadata (https://docs.cloud.google.com/compute/docs/metadata/querying-metadata); Azure IMDS
(https://learn.microsoft.com/azure/virtual-machines/instance-metadata-service); Azure 168.63.129.16
(https://learn.microsoft.com/azure/virtual-network/what-is-ip-address-168-63-129-16); OCI
(https://docs.oracle.com/en-us/iaas/Content/Compute/Tasks/gettingmetadata.htm); DigitalOcean
(https://docs.digitalocean.com/products/droplets/how-to/retrieve-droplet-metadata/); Alibaba
(https://www.alibabacloud.com/help/en/ecs/user-guide/view-instance-metadata); NodeLocal DNSCache
(https://kubernetes.io/docs/tasks/administer-cluster/nodelocaldns/).
