## nftables
`nftables` is a rewrite of `iptables` by the same authors. Same kernel subsystem (netfilter), same hooks (PREROUTING/OUTPUT/FORWARD/POSTROUTING), but a unified, more flexible user-space tool.

It replaces four separate tools:
- iptables (IPv4)
- ip6tables (IPv6)
- arptables (ARP)
- ebtables (bridge)
All become one command: `nft`


```sh
# Create a table (inet = IPv4 + IPv6 combined)
nft add table inet firewall

# Create chains attached to hooks
nft add chain inet firewall input   { type filter hook input priority 0 \; policy drop \; }
nft add chain inet firewall forward { type filter hook forward priority 0 \; policy drop \; }
nft add chain inet firewall output  { type filter hook output priority 0 \; policy accept \; }
# Allow established/related connections
nft add rule inet firewall input ct state established,related accept
# Allow SSH, HTTP, HTTPS
nft add rule inet firewall input tcp dport { 22, 80, 443 } accept

# Allow DNS (UDP and TCP)
nft add rule inet firewall input meta l4proto { tcp, udp } th dport 53 accept

# Allow max 5 new SSH connections per minute per source IP
nft add rule inet firewall input tcp dport 22 ct state new meter ssh-limit { ip saddr limit rate 5/minute } accept

# NAT-Masquerade outgoing traffic from private network
nft add table ip nat
nft add chain ip nat postrouting { type nat hook postrouting priority 100 \; }
nft add chain ip nat prerouting  { type nat hook prerouting priority -100 \; }

# SNAT — masquerade outgoing traffic
nft add rule ip nat postrouting oifname "eth0" masquerade

# DNAT — port forward 80 to internal server
nft add rule ip nat prerouting iifname "eth0" tcp dport 80 dnat to 192.168.1.50:8080
```
The `{ }` syntax is a set — matched in O(1) instead of linear rules. iptables would need three separate rules.
`policy drop` = default action if no rules match (deny all, then whitelist).

```sh
nft add chain ip nat postrouting { type nat hook postrouting priority 100 \; }
#              │  │   │              │        │               │
#              │  │   │              │        │               └─ run order (higher = later)
#              │  │   │              │        └─ attach to the POSTROUTING hook
#              │  │   │              └─ this chain does NAT
#              │  │   └─ chain name (arbitrary, you pick it)
#              │  └─ table name
#              └─ address family (ip = IPv4)
```
3 types for `IP/inet` families: `filter`, `nat`, `route`.  
But there are additional types if you include other address families:
| Address family | Available types |
|---|---|
| ip, ip6, inet (normal networking) | filter, nat, route |
| arp (ARP packets) | filter |
| bridge (bridged traffic) | filter, nat |
| netdev (raw ingress/egress on a NIC) | filter |

The valid combinations are:

| Type | Allowed hooks |
|---|---|
| filter | prerouting, input, forward, output, postrouting |
| nat | prerouting, input, output, postrouting |
| route | output |

```c
// all five hooks for the filter type
static const struct nft_chain_type nft_chain_filter_ipv4 = {
    .name       = "filter",
    .hook_mask  = (1 << NF_INET_LOCAL_IN) |
                  (1 << NF_INET_LOCAL_OUT) |
                  (1 << NF_INET_FORWARD) |
                  (1 << NF_INET_PRE_ROUTING) |
                  (1 << NF_INET_POST_ROUTING),
};
// nat
static const struct nft_chain_type nft_chain_nat_ipv4 = {
    .name       = "nat",
    .hook_mask  = (1 << NF_INET_PRE_ROUTING) |
                  (1 << NF_INET_POST_ROUTING) |
                  (1 << NF_INET_LOCAL_OUT) |
                  (1 << NF_INET_LOCAL_IN),
};
// route
static const struct nft_chain_type nft_chain_route_ipv4 = {
    .name       = "route",
    .hook_mask  = (1 << NF_INET_LOCAL_OUT),
};
```
The `hook_mask` is a bitmask — each bit corresponds to a hook. The kernel checks this mask when you try to create a chain, and rejects it if the hook isn't in the mask

## Iptables vs nftables
|  | iptables | nftables |
|---|---|---|
| Hooks | Same 5 Netfilter hooks | Same 5 Netfilter hooks |
| Tables/chains | Fixed (filter, nat, mangle, raw) | User-defined |
| Rule engine | Hardcoded match/target modules in kernel | Virtual machine (bytecode) in kernel |
| Userspace tool | iptables | nft |
| Performance | Linear rule matching | Optimized (sets, maps, concatenations) |