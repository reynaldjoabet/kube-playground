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
│  zone 1: parent prefix (24 bits)       │ zone 2: borrowed (2)││ zone 3 │
│         192.168.1                     │       00/01/10/11   ││ 6 bits │
└───────────────────────────────────────┴─────────────────────┘└────────┘
```
Now here's the key fact about each zone, across all machines in the building:

| Zone | Value across machines | What differences here mean |
| --- | --- | --- |
| Zone 1 - parent prefix (24 bits) | identical on every single device in the company - everyone is inside 192.168.1 | differences never occur (inside your network) |
| Zone 2 - borrowed bits (2) | identical within a subnet, different between subnets | this is what distinguishes your subnets |
| Zone 3 - host bits (6) | different per machine within a subnet | distinguishes machines |
