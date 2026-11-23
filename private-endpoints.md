# Private endpoints on AWS and Azure

How a private endpoint gives a service you don't run an address inside your own network, on AWS and on Azure. Both sides were run on local emulators: the AWS Emulator (EC2, ELBv2, S3, Route 53) and floci-az (`Microsoft.Network`, `Microsoft.Storage`, Blob). Every request and response below is what went over the wire. Neither emulator moves packets or answers DNS queries, so the labs show the control plane: the resources, their fields and their state changes. What happens to a packet and a DNS lookup in the real clouds is labelled (docs).

Labels: **(lab)** seen in these runs, **(code)** read in the emulator's source, **(docs)** from AWS or Microsoft documentation.

## 0. Short answers

- **A private endpoint is a network interface in your subnet, with a private IP from that subnet, that delivers traffic to a service someone else runs.** On AWS it is an *interface VPC endpoint* (one ENI per subnet). On Azure it is a *private endpoint* (one NIC). The service keeps its public name; DNS inside your network is changed so that name resolves to the private IP.
- **There are always three parts.** The interface (ENI or NIC), the DNS change that makes the normal hostname point at it, and a check on the service side that lets in only traffic that arrived through an endpoint. On AWS that check is a policy condition (`aws:SourceVpce`). On Azure it is `publicNetworkAccess: Disabled` plus the endpoint's connection being `Approved`.
- **The DNS part is where the clouds differ most.** AWS hides it: `PrivateDnsEnabled=true` makes AWS answer `sts.us-east-1.amazonaws.com` with the endpoint's addresses from a private zone you never see. Azure makes you own it: public DNS returns a `CNAME` to `<name>.privatelink.blob.core.windows.net`, and *your* private DNS zone of that name, linked to your VNet, holds the `A` record.
- **AWS also has gateway endpoints** (S3 and DynamoDB only). They have no IP and no DNS change: they add a route for the service's prefix list to your route tables. Azure's nearest equivalent is a *service endpoint*, a subnet setting, which is not a private endpoint either.
- **Publishing your own service works the same way on both.** You put it behind a load balancer (AWS: Network Load Balancer, Azure: Standard Load Balancer), publish it as an *endpoint service* (AWS) or *private link service* (Azure), and approve each consumer's connection.
- **In Kubernetes nothing in the Pod changes.** The Pod resolves the usual hostname through CoreDNS. CoreDNS forwards to the VPC or VNet resolver, which gives the private answer. The only Kubernetes-side work is letting the Pod's traffic reach the endpoint (security group, NSG, NetworkPolicy egress).

## 1. The pieces, side by side

| | AWS | Azure |
|---|---|---|
| Interface in your network | interface VPC endpoint: one ENI per subnet you choose, `InterfaceType: vpc_endpoint`, requester-managed (lab) | private endpoint: one NIC in one subnet (lab) |
| Route-only variant | gateway VPC endpoint, S3 and DynamoDB only: a prefix-list route in the route tables you choose (lab) | service endpoint on the subnet (docs), not covered here |
| Name of the target | a service name, `com.amazonaws.us-east-1.sts` or `com.amazonaws.vpce.us-east-1.vpce-svc-…` (lab) | the target's ARM id plus a *group id* (sub-resource), e.g. `blob` (lab) |
| DNS | `PrivateDnsEnabled=true`: AWS answers the service's public name from a private zone tied to the VPC; needs `enableDnsSupport` and `enableDnsHostnames` (lab: refused without) | public `CNAME` to `privatelink.*`; you create the `privatelink.*` private DNS zone, link it to the VNet and let a *private DNS zone group* write the `A` record (lab, docs) |
| Endpoint-specific names | `vpce-….sts.us-east-1.vpce.amazonaws.com` and one per AZ (lab) | none; the NIC's IP plus your zone's record |
| Restrict the caller side | endpoint policy on the endpoint (lab) | none on the endpoint; NSGs on the endpoint's subnet when `privateEndpointNetworkPolicies` is enabled (docs) |
| Restrict the service side | resource policy with `aws:SourceVpce` / `aws:SourceVpc` (lab: not enforced by the emulator) | `publicNetworkAccess: Disabled` / firewall rules on the target (lab: not enforced by floci-az) |
| Approval | endpoint services with `AcceptanceRequired=true`: the provider accepts each endpoint (lab: emulator has no connection to accept) | every connection has a state: automatic connections are approved if you may approve on the target, manual ones wait as `Pending` (lab, docs) |
| Your own service | NLB → `CreateVpcEndpointServiceConfiguration`; optional private DNS name proven with a TXT record (lab) | Standard Load Balancer → `privateLinkServices`; consumers find it by *alias* (lab) |

## 2. What happens when a Pod calls the service (docs)

The two diagrams use the addresses from the labs below. The lookup and the TLS connection are what the clouds do; the emulators only stored the resources.

```text
┌─ app-vpc 10.10.0.0/16  (enableDnsSupport, enableDnsHostnames) ───────────────────────────┐
│                                                                                          │
│  Pod 10.10.1.37 (VPC CNI: the Pod has a VPC address)                                     │
│   1  DNS  sts.us-east-1.amazonaws.com ?                                                  │
│        CoreDNS → node resolver → VPC resolver 10.10.0.2                                  │
│        answer from the endpoint's hidden private zone: 10.10.1.4, 10.10.2.4              │
│   2  TLS  10.10.1.4:443   (SNI and Host still say sts.us-east-1.amazonaws.com)           │
│        │                                                                                 │
│        ▼                                                                                 │
│  ┌──────────────────────────────────────────────────────────┐                            │
│  │ ENI eni-…d7  10.10.1.4  (subnet us-east-1a)              │                            │
│  │ ENI eni-…d8  10.10.2.4  (subnet us-east-1b)              │                            │
│  │ both belong to interface endpoint vpce-…d9               │                            │
│  │ security group endpoints-sg: tcp 443 from 10.10.0.0/16   │                            │
│  └─────────────────┬────────────────────────────────────────┘                            │
│                    │                                                                     │
└────────────────────┼─────────────────────────────────────────────────────────────────────┘
                     │  AWS network (PrivateLink), never an internet gateway or NAT
                     ▼
 ┌──────────────────────────────────────────────────────────────────────┐
 │ STS, us-east-1                                                       │
 │ request context: aws:SourceVpce=vpce-…d9, aws:SourceVpc=vpc-…c5,     │
 │                  aws:VpcSourceIp=10.10.1.37                          │
 └──────────────────────────────────────────────────────────────────────┘
```

```text
┌─ app-vnet 10.20.0.0/16 ──────────────────────────────────────────────────────────────────┐
│                                                                                          │
│  Pod 10.20.1.15 (Azure CNI: the Pod has a VNet address)                                  │
│   1  DNS  pelabsa.blob.core.windows.net ?                                                │
│        CoreDNS → node resolver → Azure DNS 168.63.129.16                                 │
│        public zone:  CNAME pelabsa.privatelink.blob.core.windows.net                     │
│        your zone privatelink.blob.core.windows.net (linked to app-vnet):                 │
│                      A 10.20.8.4   (written by the private DNS zone group)               │
│   2  TLS  10.20.8.4:443   (SNI and Host still say pelabsa.blob.core.windows.net)         │
│        │                                                                                 │
│        ▼                                                                                 │
│  ┌──────────────────────────────────────────────────────────────────────┐                │
│  │ NIC pe-pelabsa-blob.nic  10.20.8.4  (subnet pe-subnet 10.20.8.0/24)  │                │
│  │ private endpoint pe-pelabsa-blob → pelabsa, groupId blob, Approved   │                │
│  └─────────────────┬────────────────────────────────────────────────────┘                │
│                    │                                                                     │
└────────────────────┼─────────────────────────────────────────────────────────────────────┘
                     │  Microsoft network, never the account's public endpoint
                     ▼
 ┌────────────────────────────────────────────────────────────────────────┐
 │ storage account pelabsa                                                │
 │ publicNetworkAccess Disabled: only private endpoint traffic is let in  │
 │ everything else: 403 AuthorizationFailure                              │
 └────────────────────────────────────────────────────────────────────────┘
```

- **TLS still uses the public name.** The Pod connects to a private IP, but its SNI and `Host` header are `sts.us-east-1.amazonaws.com` or `pelabsa.blob.core.windows.net`, so the service's normal certificate is valid. This is why both clouds change DNS instead of handing you a different hostname. Calling the endpoint by IP, or by a name the certificate doesn't cover, fails certificate validation.
- **Only resolvers that see the private zone get the private answer.** On AWS that is the VPC's own resolver (the `.2` address, or `169.254.169.253`). On Azure it is `168.63.129.16` from a VNet linked to the zone. A machine outside, or a custom DNS server that forwards elsewhere, gets the public answer. Hybrid setups therefore forward to Route 53 Resolver inbound endpoints or Azure DNS Private Resolver.
- **The service sees where the request came from.** AWS adds `aws:SourceVpce`, `aws:SourceVpc` and `aws:VpcSourceIp` to the request context, and policies can test them. `aws:SourceIp` holds no private address for these requests, so `aws:SourceIp` conditions don't work through an endpoint. Azure tags the traffic with the private endpoint's link, and the storage firewall uses that to let it in while `publicNetworkAccess` is `Disabled`.

## 3. AWS: the lab

The lab used AWS Emulator 1.0.385 (2026.11.14) at `https://127.0.0.1:9297`, called with boto3, so `Host` is the emulator. Against AWS the same calls go to `ec2.us-east-1.amazonaws.com`, `elasticloadbalancing.us-east-1.amazonaws.com` and `<bucket>.s3.us-east-1.amazonaws.com`. Setup calls that aren't shown created:

- in **`app-vpc`** (10.10.0.0/16): subnets 10.10.1.0/24 (`us-east-1a`) and 10.10.2.0/24 (`us-east-1b`), a route table associated with both, and a security group `endpoints-sg` that allows tcp 443 from 10.10.0.0/16;
- in **`provider-vpc`** (10.30.0.0/16): two subnets.

### 3.1 Interface endpoint for STS

A new VPC has `enableDnsSupport` on and `enableDnsHostnames` off:

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
X-Amz-Date: 20261123T165939Z
Authorization: AWS4-HMAC-SHA256 Credential=<key id>/20261123/us-east-1/ec2/aws4_request, SignedHeaders=…, Signature=<64 hex>

Action=DescribeVpcAttribute&Version=2016-11-15&VpcId=vpc-000000000000000c5&Attribute=enableDnsHostnames
```

```http
HTTP/1.1 200 OK
content-type: text/xml;charset=UTF-8

<DescribeVpcAttributeResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15">
  <enableDnsHostnames>
    <value>false</value>
  </enableDnsHostnames>
  <vpcId>vpc-000000000000000c5</vpcId>
</DescribeVpcAttributeResponse>
```

The private DNS name needs both attributes, so the first try is refused:

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
X-Amz-Date: 20261123T165939Z
Authorization: AWS4-HMAC-SHA256 Credential=<key id>/20261123/us-east-1/ec2/aws4_request, SignedHeaders=…, Signature=<64 hex>

Action=CreateVpcEndpoint&Version=2016-11-15&VpcId=vpc-000000000000000c5&VpcEndpointType=Interface&ServiceName=com.amazonaws.us-east-1.sts&SubnetId.1=subnet-000000000000000cd&SubnetId.2=subnet-000000000000000cf&SecurityGroupId.1=sg-000000000000000d1&PrivateDnsEnabled=true
```

```http
HTTP/1.1 400 Bad Request
content-type: text/xml;charset=UTF-8

<Response xmlns="http://ec2.amazonaws.com/doc/2016-11-15">
  <Errors>
    <Error>
      <Code>InvalidParameter</Code>
      <Message>Enabling private DNS requires both enableDnsSupport and enableDnsHostnames VPC attributes set to true for vpc-000000000000000c5</Message>
    </Error>
  </Errors>
  <RequestID>00000000-0000-0000-0000-000000000007</RequestID>
</Response>
```

Turn on DNS hostnames and try again:

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
X-Amz-Date: 20261123T165939Z
Authorization: AWS4-HMAC-SHA256 Credential=<key id>/20261123/us-east-1/ec2/aws4_request, SignedHeaders=…, Signature=<64 hex>

Action=ModifyVpcAttribute&Version=2016-11-15&VpcId=vpc-000000000000000c5&EnableDnsHostnames.Value=true
```

```http
HTTP/1.1 200 OK
content-type: text/xml;charset=UTF-8

<ModifyVpcAttributeResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15">
  <return>true</return>
</ModifyVpcAttributeResponse>
```

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
X-Amz-Date: 20261123T165939Z
Authorization: AWS4-HMAC-SHA256 Credential=<key id>/20261123/us-east-1/ec2/aws4_request, SignedHeaders=…, Signature=<64 hex>

Action=CreateVpcEndpoint&Version=2016-11-15&VpcId=vpc-000000000000000c5&VpcEndpointType=Interface&ServiceName=com.amazonaws.us-east-1.sts&SubnetId.1=subnet-000000000000000cd&SubnetId.2=subnet-000000000000000cf&SecurityGroupId.1=sg-000000000000000d1&PrivateDnsEnabled=true
```

```http
HTTP/1.1 200 OK
content-type: text/xml;charset=UTF-8

<CreateVpcEndpointResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15">
  <vpcEndpoint>
    <vpcEndpointId>vpce-000000000000000d9</vpcEndpointId>
    <vpcEndpointType>Interface</vpcEndpointType>
    <vpcId>vpc-000000000000000c5</vpcId>
    <serviceName>com.amazonaws.us-east-1.sts</serviceName>
    <state>pending</state>
    <policyDocument>{"Statement":[{"Action":"*","Effect":"Allow","Principal":"*","Resource":"*"}]}</policyDocument>
    <subnetIdSet>
      <item>subnet-000000000000000cd</item>
      <item>subnet-000000000000000cf</item>
    </subnetIdSet>
    <groupSet>
      <item>
        <groupId>sg-000000000000000d1</groupId>
        <groupName>endpoints-sg</groupName>
      </item>
    </groupSet>
    <privateDnsEnabled>true</privateDnsEnabled>
    <requesterManaged>false</requesterManaged>
    <networkInterfaceIdSet>
      <item>eni-000000000000000d7</item>
      <item>eni-000000000000000d8</item>
    </networkInterfaceIdSet>
    <dnsEntrySet>
      <item>
        <dnsName>sts.us-east-1.amazonaws.com</dnsName>
        <hostedZoneId>Z00000000000DB</hostedZoneId>
      </item>
      <item>
        <dnsName>vpce-000000000000000d9.sts.us-east-1.vpce.amazonaws.com</dnsName>
        <hostedZoneId>Z00000000000DA</hostedZoneId>
      </item>
      <item>
        <dnsName>vpce-000000000000000d9-us-east-1a.sts.us-east-1.vpce.amazonaws.com</dnsName>
        <hostedZoneId>Z00000000000DA</hostedZoneId>
      </item>
      <item>
        <dnsName>vpce-000000000000000d9-us-east-1b.sts.us-east-1.vpce.amazonaws.com</dnsName>
        <hostedZoneId>Z00000000000DA</hostedZoneId>
      </item>
    </dnsEntrySet>
  </vpcEndpoint>
</CreateVpcEndpointResponse>
```

What the response says:

- **`networkInterfaceIdSet`**: one ENI per subnet in `SubnetId.N`. The endpoint is reachable in both AZs, and each AZ has its own address.
- **`dnsEntrySet`** lists three kinds of name:
  - `sts.us-east-1.amazonaws.com`, the service's normal regional name. It answers with the ENIs only inside this VPC, because `privateDnsEnabled` is `true`.
  - `vpce-…d9.sts.us-east-1.vpce.amazonaws.com`, the endpoint's own regional name. It works with or without private DNS.
  - `vpce-…d9-us-east-1a.sts.us-east-1.vpce.amazonaws.com`, one name per AZ, for clients that want to stay in their AZ.
- **The `hostedZoneId` values** belong to zones AWS manages. They aren't Route 53 zones in your account (docs). The emulator agrees: `ListHostedZonesByVPC` for `app-vpc` is empty:

```http
GET /2013-04-01/hostedzonesbyvpc?vpcid=vpc-000000000000000c5&vpcregion=us-east-1 HTTP/1.1
Host: 127.0.0.1:9297
X-Amz-Date: 20261123T165940Z
Authorization: AWS4-HMAC-SHA256 Credential=<key id>/20261123/us-east-1/route53/aws4_request, SignedHeaders=…, Signature=<64 hex>
```

```http
HTTP/1.1 200 OK
content-type: application/xml

<ListHostedZonesByVPCResponse xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
  <MaxItems>100</MaxItems>
</ListHostedZonesByVPCResponse>
```

- **`policyDocument`**: an endpoint gets the allow-everything policy unless you pass one (§3.3).
- **`state`**: `pending`. A second later it was `available`.

The ENIs are ordinary ENIs with `interfaceType` `vpc_endpoint`, `requesterManaged` `true`, and the first usable address in each subnet. AWS reserves `.0` to `.3`, so the first usable one is `.4`:

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
X-Amz-Date: 20261123T165939Z
Authorization: AWS4-HMAC-SHA256 Credential=<key id>/20261123/us-east-1/ec2/aws4_request, SignedHeaders=…, Signature=<64 hex>

Action=DescribeNetworkInterfaces&Version=2016-11-15&NetworkInterfaceId.1=eni-000000000000000d7&NetworkInterfaceId.2=eni-000000000000000d8
```

```http
HTTP/1.1 200 OK
content-type: text/xml;charset=UTF-8

<DescribeNetworkInterfacesResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15">
  <networkInterfaceSet>
    <item>
      <availabilityZone>us-east-1a</availabilityZone>
      <description>VPC Endpoint Interface vpce-000000000000000d9</description>
      <groupSet>
        <item>
          <groupId>sg-000000000000000d1</groupId>
          <groupName>endpoints-sg</groupName>
        </item>
      </groupSet>
      <interfaceType>vpc_endpoint</interfaceType>
      <networkInterfaceId>eni-000000000000000d7</networkInterfaceId>
      <privateDnsName>ip-10-10-1-4.ec2.internal</privateDnsName>
      <privateIpAddress>10.10.1.4</privateIpAddress>
      <requesterManaged>true</requesterManaged>
      <status>available</status>
      <subnetId>subnet-000000000000000cd</subnetId>
      <vpcId>vpc-000000000000000c5</vpcId>
      <availabilityZoneId>use1-az1</availabilityZoneId>
    </item>
    <!-- 1 more item(s) -->
  </networkInterfaceSet>
</DescribeNetworkInterfacesResponse>
```

The second ENI is `eni-…d8`, `10.10.2.4`, in `us-east-1b`.

**One endpoint per service per VPC can own the private DNS name (docs).** AWS refuses a second `PrivateDnsEnabled=true` endpoint for STS in the same VPC, because the private zone `sts.us-east-1.amazonaws.com` already exists there. The emulator accepted it and listed the same name again:

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
X-Amz-Date: 20261123T165940Z
Authorization: AWS4-HMAC-SHA256 Credential=<key id>/20261123/us-east-1/ec2/aws4_request, SignedHeaders=…, Signature=<64 hex>

Action=CreateVpcEndpoint&Version=2016-11-15&VpcId=vpc-000000000000000c5&VpcEndpointType=Interface&ServiceName=com.amazonaws.us-east-1.sts&SubnetId.1=subnet-000000000000000cd&SecurityGroupId.1=sg-000000000000000d1&PrivateDnsEnabled=true
```

```http
HTTP/1.1 200 OK
content-type: text/xml;charset=UTF-8

<CreateVpcEndpointResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15">
  <vpcEndpoint>
    <vpcEndpointId>vpce-000000000000000dd</vpcEndpointId>
    <vpcEndpointType>Interface</vpcEndpointType>
    <vpcId>vpc-000000000000000c5</vpcId>
    <serviceName>com.amazonaws.us-east-1.sts</serviceName>
    <state>pending</state>
    <policyDocument>{"Statement":[{"Action":"*","Effect":"Allow","Principal":"*","Resource":"*"}]}</policyDocument>
    <subnetIdSet>
      <item>subnet-000000000000000cd</item>
    </subnetIdSet>
    <groupSet>
      <item>
        <groupId>sg-000000000000000d1</groupId>
        <groupName>endpoints-sg</groupName>
      </item>
    </groupSet>
    <privateDnsEnabled>true</privateDnsEnabled>
    <requesterManaged>false</requesterManaged>
    <networkInterfaceIdSet>
      <item>eni-000000000000000dc</item>
    </networkInterfaceIdSet>
    <dnsEntrySet>
      <item>
        <dnsName>sts.us-east-1.amazonaws.com</dnsName>
        <hostedZoneId>Z00000000000DF</hostedZoneId>
      </item>
      <!-- 2 more item(s) -->
    </dnsEntrySet>
  </vpcEndpoint>
</CreateVpcEndpointResponse>
```

### 3.2 Gateway endpoint for S3

S3 offers both kinds. The service detail lists `Gateway` and `Interface`:

```http
HTTP/1.1 200 OK
content-type: text/xml;charset=UTF-8

<DescribeVpcEndpointServicesResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15">
  <serviceNameSet>
    <item>com.amazonaws.us-east-1.s3</item>
  </serviceNameSet>
  <serviceDetailSet>
    <item>
      <serviceName>com.amazonaws.us-east-1.s3</serviceName>
      <serviceId>vpce-svc-e9da6b72</serviceId>
      <serviceType>
        <item>
          <serviceType>Gateway</serviceType>
        </item>
        <item>
          <serviceType>Interface</serviceType>
        </item>
      </serviceType>
      <availabilityZoneSet>
        <item>us-east-1a</item>
        <!-- 2 more item(s) -->
      </availabilityZoneSet>
      <owner>amazon</owner>
      <baseEndpointDnsNameSet>
        <item>s3.us-east-1.vpce.amazonaws.com</item>
        <item>s3.us-east-1.amazonaws.com</item>
      </baseEndpointDnsNameSet>
      <privateDnsName>s3.us-east-1.amazonaws.com</privateDnsName>
      <privateDnsNameSet>
        <item>
          <privateDnsName>s3.us-east-1.amazonaws.com</privateDnsName>
        </item>
      </privateDnsNameSet>
      <vpcEndpointPolicySupported>true</vpcEndpointPolicySupported>
      <acceptanceRequired>false</acceptanceRequired>
      <privateDnsNameVerificationState>verified</privateDnsNameVerificationState>
    </item>
  </serviceDetailSet>
</DescribeVpcEndpointServicesResponse>
```

A gateway endpoint takes route tables instead of subnets, and is `available` at once:

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
X-Amz-Date: 20261123T165940Z
Authorization: AWS4-HMAC-SHA256 Credential=<key id>/20261123/us-east-1/ec2/aws4_request, SignedHeaders=…, Signature=<64 hex>

Action=CreateVpcEndpoint&Version=2016-11-15&VpcId=vpc-000000000000000c5&VpcEndpointType=Gateway&ServiceName=com.amazonaws.us-east-1.s3&RouteTableId.1=rtb-000000000000000d4
```

```http
HTTP/1.1 200 OK
content-type: text/xml;charset=UTF-8

<CreateVpcEndpointResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15">
  <vpcEndpoint>
    <vpcEndpointId>vpce-000000000000000e0</vpcEndpointId>
    <vpcEndpointType>Gateway</vpcEndpointType>
    <vpcId>vpc-000000000000000c5</vpcId>
    <serviceName>com.amazonaws.us-east-1.s3</serviceName>
    <state>available</state>
    <policyDocument>{"Statement":[{"Action":"*","Effect":"Allow","Principal":"*","Resource":"*"}]}</policyDocument>
    <routeTableIdSet>
      <item>rtb-000000000000000d4</item>
    </routeTableIdSet>
    <requesterManaged>false</requesterManaged>
  </vpcEndpoint>
</CreateVpcEndpointResponse>
```

The route table gains a route whose destination is a prefix list and whose target is the endpoint:

```http
HTTP/1.1 200 OK
content-type: text/xml;charset=UTF-8

<DescribeRouteTablesResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15">
  <routeTableSet>
    <item>
      <associationSet>
        <item>
          <main>false</main>
          <routeTableAssociationId>rtbassoc-000000000000000d5</routeTableAssociationId>
          <routeTableId>rtb-000000000000000d4</routeTableId>
          <subnetId>subnet-000000000000000cd</subnetId>
          <associationState>
            <state>associated</state>
          </associationState>
        </item>
        <item>
          <main>false</main>
          <routeTableAssociationId>rtbassoc-000000000000000d6</routeTableAssociationId>
          <routeTableId>rtb-000000000000000d4</routeTableId>
          <subnetId>subnet-000000000000000cf</subnetId>
          <associationState>
            <state>associated</state>
          </associationState>
        </item>
      </associationSet>
      <routeTableId>rtb-000000000000000d4</routeTableId>
      <routeSet>
        <item>
          <destinationCidrBlock>10.10.0.0/16</destinationCidrBlock>
          <gatewayId>local</gatewayId>
          <origin>CreateRouteTable</origin>
          <state>active</state>
        </item>
        <item>
          <destinationPrefixListId>pl-e9da6b72</destinationPrefixListId>
          <gatewayId>vpce-000000000000000e0</gatewayId>
          <origin>CreateRoute</origin>
          <state>active</state>
        </item>
      </routeSet>
      <vpcId>vpc-000000000000000c5</vpcId>
    </item>
  </routeTableSet>
</DescribeRouteTablesResponse>
```

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
X-Amz-Date: 20261123T165940Z
Authorization: AWS4-HMAC-SHA256 Credential=<key id>/20261123/us-east-1/ec2/aws4_request, SignedHeaders=…, Signature=<64 hex>

Action=DescribePrefixLists&Version=2016-11-15&Filter.1.Name=prefix-list-name&Filter.1.Value.1=com.amazonaws.us-east-1.s3
```

```http
HTTP/1.1 200 OK
content-type: text/xml;charset=UTF-8

<DescribePrefixListsResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15">
  <prefixListSet>
    <item>
      <cidrSet>
        <item>198.18.96.0/21</item>
      </cidrSet>
      <prefixListId>pl-e9da6b72</prefixListId>
      <prefixListName>com.amazonaws.us-east-1.s3</prefixListName>
    </item>
  </prefixListSet>
</DescribePrefixListsResponse>
```

The emulator's prefix list holds a documentation range (`198.18.96.0/21`). In AWS it holds S3's public ranges for the region, and that route sends traffic to them through the endpoint instead of the internet gateway or NAT. Nothing about DNS changes: `s3.us-east-1.amazonaws.com` still resolves to public addresses, and the route catches them.

Gateway endpoints are free, but only usable from inside the VPC: not from peered VPCs, Transit Gateway or on-premises (docs). Those need an S3 *interface* endpoint.

### 3.3 Policies on both sides

The **endpoint policy** limits what may pass through the endpoint, for any caller in the VPC:

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
X-Amz-Date: 20261123T165940Z
Authorization: AWS4-HMAC-SHA256 Credential=<key id>/20261123/us-east-1/ec2/aws4_request, SignedHeaders=…, Signature=<64 hex>

Action=ModifyVpcEndpoint&Version=2016-11-15&VpcEndpointId=vpce-000000000000000e0&PolicyDocument=%7B%22Version%22%3A+%222012-10-17%22%2C+%22Statement%22%3A+%5B%7B%22Effect%22%3A+%22Allow%22%2C+%22Principal%22%3A+%22%2A%22%2C+%22Action%22%3A+%5B%22s3%3AGetObject%22%2C+%22s3%3APutObject%22%5D%2C+%22Resource%22%3A+%22arn%3Aaws%3As3%3A%3A%3Ape-lab-private%2F%2A%22%7D%5D%7D
```

```text
# the same form fields, URL-decoded
Action = ModifyVpcEndpoint
Version = 2016-11-15
VpcEndpointId = vpce-000000000000000e0
PolicyDocument =
  {
    "Version": "2012-10-17",
    "Statement": [
      {
        "Effect": "Allow",
        "Principal": "*",
        "Action": [
          "s3:GetObject",
          "s3:PutObject"
        ],
        "Resource": "arn:aws:s3:::pe-lab-private/*"
      }
    ]
  }
```

```http
HTTP/1.1 200 OK
content-type: text/xml;charset=UTF-8

<ModifyVpcEndpointResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15">
  <return>true</return>
</ModifyVpcEndpointResponse>
```

The **bucket policy** is the service side. This statement denies everything not coming through the gateway endpoint:

```http
PUT /pe-lab-private?policy HTTP/1.1
Host: 127.0.0.1:9297
X-Amz-Date: 20261123T165940Z
X-Amz-Content-SHA256: 62c294191772e3d6faddd7a96a349c711dcfd904a5338ace915bb3061bc28a2a
Authorization: AWS4-HMAC-SHA256 Credential=<key id>/20261123/us-east-1/s3/aws4_request, SignedHeaders=…, Signature=<64 hex>

{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "DenyUnlessThroughOurEndpoint",
      "Effect": "Deny",
      "Principal": "*",
      "Action": "s3:*",
      "Resource": [
        "arn:aws:s3:::pe-lab-private",
        "arn:aws:s3:::pe-lab-private/*"
      ],
      "Condition": {
        "StringNotEquals": {
          "aws:SourceVpce": "vpce-000000000000000e0"
        }
      }
    }
  ]
}
```

```http
HTTP/1.1 204 No Content
content-type: application/xml
```

From the host, which has no endpoint, AWS would refuse the next request. The request context has no `aws:SourceVpce`, and IAM treats a negated operator (`StringNotEquals`) on a missing key as a match (docs), so the `Deny` applies. The emulator let it through:

```http
GET /pe-lab-private/report.csv HTTP/1.1
Host: 127.0.0.1:9297
x-amz-checksum-mode: ENABLED
X-Amz-Date: 20261123T165940Z
X-Amz-Content-SHA256: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
Authorization: AWS4-HMAC-SHA256 Credential=<key id>/20261123/us-east-1/s3/aws4_request, SignedHeaders=…, Signature=<64 hex>
```

```http
HTTP/1.1 200 OK
content-type: binary/octet-stream

id,total
1,42
```

The emulator does enforce bucket policies: an unconditional `Deny` was refused. What it skips is statements whose condition uses a network key. The same `Deny s3:GetObject` with different conditions:

| `Deny s3:GetObject` with condition | Request context key in AWS | Emulator result |
|---|---|---|
| `StringNotEquals aws:SourceVpce` | absent outside an endpoint → condition **true** → denied | allowed |
| `StringNotEquals aws:SourceVpc` | absent → **true** → denied | allowed |
| `Null aws:SourceVpce = true` | absent → **true** → denied | allowed |
| `NotIpAddress aws:SourceIp 10.0.0.0/8` | `aws:SourceIp` is the host's address, not in 10/8 → **true** → denied | allowed |
| `StringNotEquals aws:PrincipalAccount 111111111111` | present (`000000000000`) → true → denied | denied |
| `(no condition)` | always applies → denied | denied |

So, on the emulator, only the unconditional `Deny` and the `aws:PrincipalAccount` one were enforced. Statements on network keys (`aws:SourceVpce`, `aws:SourceVpc`, `aws:SourceIp`) were skipped, and `Null` matched nothing. The binary does contain these key names (`strings` shows `aws:SourceVpce`, `aws:SourceVpc`, `aws:VpcSourceIp`), but a request from the host has no way to arrive "through" an endpoint.

### 3.4 PrivateLink: publishing your own service

`provider-vpc` runs an internal Network Load Balancer:

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
X-Amz-Date: 20261123T165940Z
Authorization: AWS4-HMAC-SHA256 Credential=<key id>/20261123/us-east-1/elasticloadbalancing/aws4_request, SignedHeaders=…, Signature=<64 hex>

Action=CreateLoadBalancer&Version=2015-12-01&Name=orders-nlb&Type=network&Scheme=internal&Subnets.member.1=subnet-000000000000000e9&Subnets.member.2=subnet-000000000000000eb
```

```http
HTTP/1.1 200 OK
content-type: text/xml

<CreateLoadBalancerResponse xmlns="http://elasticloadbalancing.amazonaws.com/doc/2015-12-01/">
  <CreateLoadBalancerResult>
    <LoadBalancers>
      <member>
        <LoadBalancerArn>arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/net/orders-nlb/35e397365a1878e6</LoadBalancerArn>
        <DNSName>orders-nlb-35e397365a1878e6.elb.us-east-1.amazonaws.com</DNSName>
        <LoadBalancerName>orders-nlb</LoadBalancerName>
        <Scheme>internal</Scheme>
        <VpcId>vpc-000000000000000e1</VpcId>
        <State>
          <Code>provisioning</Code>
        </State>
        <Type>network</Type>
        <AvailabilityZones>
          <member>
            <ZoneName>us-east-1a</ZoneName>
            <SubnetId>subnet-000000000000000e9</SubnetId>
          </member>
          <!-- 1 more item(s) -->
        </AvailabilityZones>
      </member>
    </LoadBalancers>
  </CreateLoadBalancerResult>
</CreateLoadBalancerResponse>
```

The provider publishes it as an endpoint service. `AcceptanceRequired=true` means each consumer endpoint waits for the provider. `PrivateDnsName` is the name consumers should be able to use instead of the `vpce-svc-…` names:

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
X-Amz-Date: 20261123T165941Z
Authorization: AWS4-HMAC-SHA256 Credential=<key id>/20261123/us-east-1/ec2/aws4_request, SignedHeaders=…, Signature=<64 hex>

Action=CreateVpcEndpointServiceConfiguration&Version=2016-11-15&NetworkLoadBalancerArn.1=arn%3Aaws%3Aelasticloadbalancing%3Aus-east-1%3A000000000000%3Aloadbalancer%2Fnet%2Forders-nlb%2F35e397365a1878e6&AcceptanceRequired=true&PrivateDnsName=orders.internal.example.com
```

```text
# the same form fields, URL-decoded
Action = CreateVpcEndpointServiceConfiguration
Version = 2016-11-15
NetworkLoadBalancerArn.1 = arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/net/orders-nlb/35e397365a1878e6
AcceptanceRequired = true
PrivateDnsName = orders.internal.example.com
```

```http
HTTP/1.1 200 OK
content-type: text/xml;charset=UTF-8

<CreateVpcEndpointServiceConfigurationResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15">
  <serviceConfiguration>
    <serviceType>
      <item>
        <serviceType>Interface</serviceType>
      </item>
    </serviceType>
    <serviceId>vpce-svc-000000000000000ef</serviceId>
    <serviceName>com.amazonaws.vpce.us-east-1.vpce-svc-000000000000000ef</serviceName>
    <serviceState>Available</serviceState>
    <availabilityZoneSet>
      <item>us-east-1a</item>
      <!-- 2 more item(s) -->
    </availabilityZoneSet>
    <acceptanceRequired>true</acceptanceRequired>
    <networkLoadBalancerArnSet>
      <item>arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/net/orders-nlb/35e397365a1878e6</item>
    </networkLoadBalancerArnSet>
    <baseEndpointDnsNameSet>
      <item>vpce-svc-000000000000000ef.us-east-1.vpce.amazonaws.com</item>
    </baseEndpointDnsNameSet>
    <privateDnsName>orders.internal.example.com</privateDnsName>
    <privateDnsNameConfiguration>
      <state>pendingVerification</state>
      <type>TXT</type>
      <value>vpce:8e69ed04e4fddac1</value>
      <name>_33413bd985df2649</name>
    </privateDnsNameConfiguration>
    <remoteAccessEnabled>false</remoteAccessEnabled>
  </serviceConfiguration>
</CreateVpcEndpointServiceConfigurationResponse>
```

`privateDnsNameConfiguration` is the proof-of-ownership step. The provider must create a TXT record named `_33413bd985df2649.orders.internal.example.com` with the value `vpce:8e69ed04e4fddac1` in public DNS. Until AWS sees it, the state stays `pendingVerification` and consumers can't use the name (docs).

Next the provider allows the consumer's account. The emulator doesn't implement this call:

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
X-Amz-Date: 20261123T165941Z
Authorization: AWS4-HMAC-SHA256 Credential=<key id>/20261123/us-east-1/ec2/aws4_request, SignedHeaders=…, Signature=<64 hex>

Action=ModifyVpcEndpointServicePermissions&Version=2016-11-15&ServiceId=vpce-svc-000000000000000ef&AddAllowedPrincipals.1=arn%3Aaws%3Aiam%3A%3A000000000000%3Aroot
```

```text
# the same form fields, URL-decoded
Action = ModifyVpcEndpointServicePermissions
Version = 2016-11-15
ServiceId = vpce-svc-000000000000000ef
AddAllowedPrincipals.1 = arn:aws:iam::000000000000:root
```

```http
HTTP/1.1 501 Not Implemented
content-type: text/xml;charset=UTF-8

<Response xmlns="http://ec2.amazonaws.com/doc/2016-11-15">
  <Errors>
    <Error>
      <Code>NotImplemented</Code>
      <Message>operation ModifyVpcEndpointServicePermissions is scaffolded but not implemented in this emulator</Message>
    </Error>
  </Errors>
  <RequestID>00000000-0000-0000-0000-000000000008</RequestID>
</Response>
```

The consumer sees the service by name. `vpcEndpointPolicySupported` is `false`: endpoint policies exist only for AWS services.

```http
HTTP/1.1 200 OK
content-type: text/xml;charset=UTF-8

<DescribeVpcEndpointServicesResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15">
  <serviceNameSet>
    <item>com.amazonaws.vpce.us-east-1.vpce-svc-000000000000000ef</item>
  </serviceNameSet>
  <serviceDetailSet>
    <item>
      <serviceName>com.amazonaws.vpce.us-east-1.vpce-svc-000000000000000ef</serviceName>
      <serviceId>vpce-svc-000000000000000ef</serviceId>
      <serviceType>
        <item>
          <serviceType>Interface</serviceType>
        </item>
      </serviceType>
      <availabilityZoneSet>
        <item>us-east-1a</item>
        <!-- 2 more item(s) -->
      </availabilityZoneSet>
      <owner>000000000000</owner>
      <baseEndpointDnsNameSet>
        <item>vpce-svc-000000000000000ef.us-east-1.vpce.amazonaws.com</item>
      </baseEndpointDnsNameSet>
      <privateDnsName>orders.internal.example.com</privateDnsName>
      <privateDnsNameSet>
        <item>
          <privateDnsName>orders.internal.example.com</privateDnsName>
        </item>
      </privateDnsNameSet>
      <vpcEndpointPolicySupported>false</vpcEndpointPolicySupported>
      <acceptanceRequired>true</acceptanceRequired>
      <privateDnsNameVerificationState>pendingVerification</privateDnsNameVerificationState>
    </item>
  </serviceDetailSet>
</DescribeVpcEndpointServicesResponse>
```

The consumer creates an interface endpoint to it:

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
X-Amz-Date: 20261123T165941Z
Authorization: AWS4-HMAC-SHA256 Credential=<key id>/20261123/us-east-1/ec2/aws4_request, SignedHeaders=…, Signature=<64 hex>

Action=CreateVpcEndpoint&Version=2016-11-15&VpcId=vpc-000000000000000c5&VpcEndpointType=Interface&ServiceName=com.amazonaws.vpce.us-east-1.vpce-svc-000000000000000ef&SubnetId.1=subnet-000000000000000cd&SubnetId.2=subnet-000000000000000cf&SecurityGroupId.1=sg-000000000000000d1
```

```http
HTTP/1.1 200 OK
content-type: text/xml;charset=UTF-8

<CreateVpcEndpointResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15">
  <vpcEndpoint>
    <vpcEndpointId>vpce-000000000000000f2</vpcEndpointId>
    <vpcEndpointType>Interface</vpcEndpointType>
    <vpcId>vpc-000000000000000c5</vpcId>
    <serviceName>com.amazonaws.vpce.us-east-1.vpce-svc-000000000000000ef</serviceName>
    <state>pending</state>
    <policyDocument>{"Statement":[{"Action":"*","Effect":"Allow","Principal":"*","Resource":"*"}]}</policyDocument>
    <subnetIdSet>
      <item>subnet-000000000000000cd</item>
      <item>subnet-000000000000000cf</item>
    </subnetIdSet>
    <groupSet>
      <item>
        <groupId>sg-000000000000000d1</groupId>
        <groupName>endpoints-sg</groupName>
      </item>
    </groupSet>
    <privateDnsEnabled>true</privateDnsEnabled>
    <requesterManaged>false</requesterManaged>
    <networkInterfaceIdSet>
      <item>eni-000000000000000f0</item>
      <item>eni-000000000000000f1</item>
    </networkInterfaceIdSet>
    <dnsEntrySet>
      <item>
        <dnsName>orders.internal.example.com</dnsName>
        <hostedZoneId>Z00000000000F4</hostedZoneId>
      </item>
      <item>
        <dnsName>vpce-000000000000000f2.vpce-svc-000000000000000ef.us-east-1.vpce.amazonaws.com</dnsName>
        <hostedZoneId>Z00000000000F3</hostedZoneId>
      </item>
      <!-- 2 more item(s) -->
    </dnsEntrySet>
  </vpcEndpoint>
</CreateVpcEndpointResponse>
```

**What AWS does next (docs):**

1. The endpoint goes to `pendingAcceptance`.
2. The provider sees a connection in `DescribeVpcEndpointConnections` and calls `AcceptVpcEndpointConnections` (or `Reject…`).
3. Only then is the endpoint `available`, with traffic flowing from the ENIs to the NLB.
4. `orders.internal.example.com` is used only once the domain is verified.

**What the emulator does:** the endpoint went straight to `available`, with the unverified private DNS name in `dnsEntrySet`. No connection was recorded, so there is nothing for the provider to accept:

```http
HTTP/1.1 200 OK
content-type: text/xml;charset=UTF-8

<DescribeVpcEndpointsResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15">
  <vpcEndpointSet>
    <item>
      <vpcEndpointId>vpce-000000000000000f2</vpcEndpointId>
      <vpcEndpointType>Interface</vpcEndpointType>
      <vpcId>vpc-000000000000000c5</vpcId>
      <serviceName>com.amazonaws.vpce.us-east-1.vpce-svc-000000000000000ef</serviceName>
      <state>available</state>
      <policyDocument>{"Statement":[{"Action":"*","Effect":"Allow","Principal":"*","Resource":"*"}]}</policyDocument>
      <subnetIdSet>
        <item>subnet-000000000000000cd</item>
        <item>subnet-000000000000000cf</item>
      </subnetIdSet>
      <groupSet>
        <item>
          <groupId>sg-000000000000000d1</groupId>
          <groupName>endpoints-sg</groupName>
        </item>
      </groupSet>
      <privateDnsEnabled>true</privateDnsEnabled>
      <requesterManaged>false</requesterManaged>
      <networkInterfaceIdSet>
        <item>eni-000000000000000f0</item>
        <item>eni-000000000000000f1</item>
      </networkInterfaceIdSet>
      <dnsEntrySet>
        <item>
          <dnsName>orders.internal.example.com</dnsName>
          <hostedZoneId>Z00000000000F4</hostedZoneId>
        </item>
        <!-- 3 more item(s) -->
      </dnsEntrySet>
    </item>
  </vpcEndpointSet>
</DescribeVpcEndpointsResponse>
```

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
X-Amz-Date: 20261123T165942Z
Authorization: AWS4-HMAC-SHA256 Credential=<key id>/20261123/us-east-1/ec2/aws4_request, SignedHeaders=…, Signature=<64 hex>

Action=DescribeVpcEndpointConnections&Version=2016-11-15&Filter.1.Name=service-id&Filter.1.Value.1=vpce-svc-000000000000000ef
```

```http
HTTP/1.1 200 OK
content-type: text/xml;charset=UTF-8

<DescribeVpcEndpointConnectionsResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15"/>
```

```http
POST / HTTP/1.1
Host: 127.0.0.1:9297
Content-Type: application/x-www-form-urlencoded; charset=utf-8
X-Amz-Date: 20261123T165942Z
Authorization: AWS4-HMAC-SHA256 Credential=<key id>/20261123/us-east-1/ec2/aws4_request, SignedHeaders=…, Signature=<64 hex>

Action=AcceptVpcEndpointConnections&Version=2016-11-15&ServiceId=vpce-svc-000000000000000ef&VpcEndpointId.1=vpce-000000000000000f2
```

```http
HTTP/1.1 200 OK
content-type: text/xml;charset=UTF-8

<AcceptVpcEndpointConnectionsResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15">
  <unsuccessful>
    <item>
      <error>
        <code>InvalidVpcEndpointId.NotFound</code>
        <message>The VPC endpoint ID 'vpce-000000000000000f2' does not name a connection to the service 'vpce-svc-000000000000000ef'</message>
      </error>
      <resourceId>vpce-000000000000000f2</resourceId>
    </item>
  </unsuccessful>
</AcceptVpcEndpointConnectionsResponse>
```

## 4. Azure: the lab

The lab used floci-az nightly-11222026 (source revision `0f6d082`), plain HTTP on `127.0.0.1:4589`. These are raw ARM calls, the same requests the Azure SDKs and Terraform send. In Azure they go to `management.azure.com`. `{rg}` in the logs stands for `/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/pe-lab`. floci-az ignored the token (`VALIDATE_TOKENS=false`). Not shown: the resource group.

### 4.1 The target and the network

A storage account that refuses public traffic:

```http
PUT {rg}/providers/Microsoft.Storage/storageAccounts/pelabsa?api-version=2023-05-01 HTTP/1.1
Host: 127.0.0.1:4589
Authorization: Bearer eyJ0eXAiOiJKV1Qi…
Content-Type: application/json

{
  "location": "eastus",
  "kind": "StorageV2",
  "sku": {
    "name": "Standard_LRS"
  },
  "properties": {
    "publicNetworkAccess": "Disabled",
    "networkAcls": {
      "defaultAction": "Deny",
      "bypass": "None"
    }
  }
}
```

```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "id": "{rg}/providers/Microsoft.Storage/storageAccounts/pelabsa",
  "name": "pelabsa",
  "type": "Microsoft.Storage/storageAccounts",
  "location": "eastus",
  "sku": {
    "name": "Standard_LRS",
    "tier": "Standard"
  },
  "kind": "StorageV2",
  "properties": {
    "supportsHttpsTrafficOnly": false,
    "accessTier": "Hot",
    "publicNetworkAccess": "Disabled",
    "networkAcls": {
      "defaultAction": "Deny",
      "bypass": "None"
    },
    "encryption": {
      "services": {
        "blob": {
          "enabled": true,
          "keyType": "Account"
        },
        "file": {
          "enabled": true,
          "keyType": "Account"
        },
        "queue": {
          "enabled": true,
          "keyType": "Account"
        },
        "table": {
          "enabled": true,
          "keyType": "Account"
        }
      },
      "keySource": "Microsoft.Storage"
    },
    "provisioningState": "Succeeded",
    "isHnsEnabled": false,
    "primaryEndpoints": {
      "table": "http://pelabsa.table.core.windows.net/",
      "dfs": "http://pelabsa.dfs.core.windows.net/",
      "queue": "http://pelabsa.queue.core.windows.net/",
      "blob": "http://pelabsa.blob.core.windows.net/"
    },
    "primaryLocation": "eastus",
    "statusOfPrimary": "available"
  }
}
```

A VNet with a node subnet and a subnet for private endpoints. `privateEndpointNetworkPolicies: Enabled` makes NSGs and route tables apply to private endpoints in that subnet. The default is `Disabled`, and then they bypass both (docs).

```http
PUT {rg}/providers/Microsoft.Network/virtualNetworks/app-vnet?api-version=2024-05-01 HTTP/1.1
Host: 127.0.0.1:4589
Authorization: Bearer eyJ0eXAiOiJKV1Qi…
Content-Type: application/json

{
  "location": "eastus",
  "properties": {
    "addressSpace": {
      "addressPrefixes": [
        "10.20.0.0/16"
      ]
    },
    "subnets": [
      {
        "name": "aks-nodes",
        "properties": {
          "addressPrefix": "10.20.0.0/22"
        }
      },
      {
        "name": "pe-subnet",
        "properties": {
          "addressPrefix": "10.20.8.0/24",
          "privateEndpointNetworkPolicies": "Enabled"
        }
      }
    ]
  }
}
```

```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "id": "{rg}/providers/Microsoft.Network/virtualNetworks/app-vnet",
  "name": "app-vnet",
  "type": "Microsoft.Network/virtualNetworks",
  "location": "eastus",
  "properties": {
    "addressSpace": {
      "addressPrefixes": [
        "10.20.0.0/16"
      ]
    },
    "subnets": [
      {
        "name": "aks-nodes",
        "properties": {
          "addressPrefix": "10.20.0.0/22",
          "provisioningState": "Succeeded"
        },
        "id": "{rg}/providers/Microsoft.Network/virtualNetworks/app-vnet/subnets/aks-nodes"
      },
      {
        "name": "pe-subnet",
        "properties": {
          "addressPrefix": "10.20.8.0/24",
          "privateEndpointNetworkPolicies": "Enabled",
          "provisioningState": "Succeeded"
        },
        "id": "{rg}/providers/Microsoft.Network/virtualNetworks/app-vnet/subnets/pe-subnet"
      }
    ],
    "provisioningState": "Succeeded"
  }
}
```

### 4.2 The private DNS zone

The zone's name is fixed by the service: `privatelink.blob.core.windows.net` for Blob, `privatelink.vaultcore.azure.net` for Key Vault, and so on (docs). That name is where Azure's public DNS points with its `CNAME`. The zone starts with only its SOA record:

```http
PUT {rg}/providers/Microsoft.Network/privateDnsZones/privatelink.blob.core.windows.net?api-version=2024-06-01 HTTP/1.1
Host: 127.0.0.1:4589
Authorization: Bearer eyJ0eXAiOiJKV1Qi…
Content-Type: application/json

{
  "location": "global"
}
```

```http
HTTP/1.1 201 Created
Content-Type: application/json;charset=UTF-8

{
  "id": "{rg}/providers/Microsoft.Network/privateDnsZones/privatelink.blob.core.windows.net",
  "name": "privatelink.blob.core.windows.net",
  "type": "Microsoft.Network/privateDnsZones",
  "location": "global",
  "etag": "\"b440f855-1452-41e1-ba53-e3d88c07d894\"",
  "properties": {
    "maxNumberOfRecordSets": 25000,
    "maxNumberOfVirtualNetworkLinks": 1000,
    "maxNumberOfVirtualNetworkLinksWithRegistration": 100,
    "numberOfRecordSets": 1,
    "numberOfVirtualNetworkLinks": 0,
    "numberOfVirtualNetworkLinksWithRegistration": 0,
    "provisioningState": "Succeeded"
  }
}
```

A zone answers only VNets linked to it. `registrationEnabled: false` because nothing should register VM names in this zone:

```http
PUT {rg}/providers/Microsoft.Network/privateDnsZones/privatelink.blob.core.windows.net/virtualNetworkLinks/app-vnet-link?api-version=2024-06-01 HTTP/1.1
Host: 127.0.0.1:4589
Authorization: Bearer eyJ0eXAiOiJKV1Qi…
Content-Type: application/json

{
  "location": "global",
  "properties": {
    "registrationEnabled": false,
    "virtualNetwork": {
      "id": "{rg}/providers/Microsoft.Network/virtualNetworks/app-vnet"
    }
  }
}
```

```http
HTTP/1.1 201 Created
Content-Type: application/json;charset=UTF-8

{
  "id": "{rg}/providers/Microsoft.Network/privateDnsZones/privatelink.blob.core.windows.net/virtualNetworkLinks/app-vnet-link",
  "name": "app-vnet-link",
  "type": "Microsoft.Network/privateDnsZones/virtualNetworkLinks",
  "location": "global",
  "etag": "\"1db9e89e-9238-4d86-b834-d2b466c29e94\"",
  "properties": {
    "provisioningState": "Succeeded",
    "registrationEnabled": false,
    "virtualNetwork": {
      "id": "{rg}/providers/Microsoft.Network/virtualNetworks/app-vnet"
    },
    "virtualNetworkLinkState": "Completed"
  }
}
```

### 4.3 The private endpoint

The request names the subnet, the target's ARM id and the group id. `privateLinkServiceConnections` (not `manual…`) asks for automatic approval:

```http
PUT {rg}/providers/Microsoft.Network/privateEndpoints/pe-pelabsa-blob?api-version=2024-05-01 HTTP/1.1
Host: 127.0.0.1:4589
Authorization: Bearer eyJ0eXAiOiJKV1Qi…
Content-Type: application/json

{
  "location": "eastus",
  "properties": {
    "subnet": {
      "id": "{rg}/providers/Microsoft.Network/virtualNetworks/app-vnet/subnets/pe-subnet"
    },
    "privateLinkServiceConnections": [
      {
        "name": "pelabsa-blob",
        "properties": {
          "privateLinkServiceId": "{rg}/providers/Microsoft.Storage/storageAccounts/pelabsa",
          "groupIds": [
            "blob"
          ]
        }
      }
    ]
  }
}
```

```http
HTTP/1.1 201 Created
Content-Type: application/json;charset=UTF-8

{
  "id": "{rg}/providers/Microsoft.Network/privateEndpoints/pe-pelabsa-blob",
  "name": "pe-pelabsa-blob",
  "type": "Microsoft.Network/privateEndpoints",
  "location": "eastus",
  "properties": {
    "subnet": {
      "id": "{rg}/providers/Microsoft.Network/virtualNetworks/app-vnet/subnets/pe-subnet"
    },
    "privateLinkServiceConnections": [
      {
        "name": "pelabsa-blob",
        "properties": {
          "privateLinkServiceId": "{rg}/providers/Microsoft.Storage/storageAccounts/pelabsa",
          "groupIds": [
            "blob"
          ],
          "privateLinkServiceConnectionState": {
            "status": "Approved",
            "description": "Auto-approved",
            "actionsRequired": "None"
          },
          "provisioningState": "Succeeded"
        },
        "id": "{rg}/providers/Microsoft.Network/privateEndpoints/pe-pelabsa-blob/privateLinkServiceConnections/pelabsa-blob"
      }
    ],
    "manualPrivateLinkServiceConnections": [],
    "customDnsConfigs": [],
    "networkInterfaces": [
      {
        "id": "{rg}/providers/Microsoft.Network/networkInterfaces/pe-pelabsa-blob.nic.c6c4ab8b"
      }
    ],
    "provisioningState": "Succeeded"
  }
}
```

- **`privateLinkServiceConnectionState`**: `Approved` / `Auto-approved`. In Azure, automatic approval happens only if the caller has the target's approval permission, `Microsoft.Storage/storageAccounts/privateEndpointConnectionsApproval/action` here. Otherwise the request fails, and the caller must use a manual connection instead (docs). floci-az approves every automatic connection (code: `normalizeConnections` sets `Approved` without looking at the target).
- **`networkInterfaces`**: Azure creates the endpoint's NIC in your resource group. You can read it, but it is managed by the endpoint.

```http
GET {rg}/providers/Microsoft.Network/networkInterfaces/pe-pelabsa-blob.nic.c6c4ab8b?api-version=2024-05-01 HTTP/1.1
Host: 127.0.0.1:4589
Authorization: Bearer eyJ0eXAiOiJKV1Qi…
```

```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "id": "{rg}/providers/Microsoft.Network/networkInterfaces/pe-pelabsa-blob.nic.c6c4ab8b",
  "name": "pe-pelabsa-blob.nic.c6c4ab8b",
  "type": "Microsoft.Network/networkInterfaces",
  "properties": {
    "ipConfigurations": [
      {
        "name": "ipconfig1",
        "properties": {
          "privateIPAddress": "10.0.0.4",
          "privateIPAllocationMethod": "Dynamic",
          "primary": true,
          "provisioningState": "Succeeded"
        }
      }
    ],
    "provisioningState": "Succeeded"
  }
}
```

The NIC's address is `10.0.0.4`, which isn't in `pe-subnet` (10.20.8.0/24). floci-az writes the same address for every endpoint (code: `synthesizeProperties` defaults to `10.0.0.4`). Azure would give the first free address in the subnet, `10.20.8.4`; the first four and the last address are reserved (docs).

### 4.4 The private DNS zone group

A zone group ties the endpoint to the zone. In Azure the endpoint then writes `pelabsa` → `10.20.8.4` into the zone, and deletes it again with the endpoint (docs):

```http
PUT {rg}/providers/Microsoft.Network/privateEndpoints/pe-pelabsa-blob/privateDnsZoneGroups/default?api-version=2024-05-01 HTTP/1.1
Host: 127.0.0.1:4589
Authorization: Bearer eyJ0eXAiOiJKV1Qi…
Content-Type: application/json

{
  "properties": {
    "privateDnsZoneConfigs": [
      {
        "name": "privatelink-blob",
        "properties": {
          "privateDnsZoneId": "{rg}/providers/Microsoft.Network/privateDnsZones/privatelink.blob.core.windows.net"
        }
      }
    ]
  }
}
```

```http
HTTP/1.1 201 Created
Content-Type: application/json;charset=UTF-8

{
  "id": "{rg}/providers/Microsoft.Network/privateEndpoints/pe-pelabsa-blob/privateDnsZoneGroups/default",
  "name": "default",
  "type": "Microsoft.Network/privateEndpoints/privateDnsZoneGroups",
  "etag": "\"099f8097-d582-4aa0-a978-81f60421617c\"",
  "properties": {
    "provisioningState": "Succeeded",
    "privateDnsZoneConfigs": [
      {
        "name": "privatelink-blob",
        "properties": {
          "privateDnsZoneId": "{rg}/providers/Microsoft.Network/privateDnsZones/privatelink.blob.core.windows.net",
          "recordSets": []
        }
      }
    ]
  }
}
```

In floci-az the group is stored, but `recordSets` is empty and the zone still holds only the SOA:

```http
GET {rg}/providers/Microsoft.Network/privateDnsZones/privatelink.blob.core.windows.net/recordsets?api-version=2024-06-01 HTTP/1.1
Host: 127.0.0.1:4589
Authorization: Bearer eyJ0eXAiOiJKV1Qi…
```

```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "value": [
    {
      "id": "{rg}/providers/Microsoft.Network/privateDnsZones/privatelink.blob.core.windows.net/SOA/@",
      "name": "@",
      "type": "Microsoft.Network/privateDnsZones/SOA",
      "etag": "\"b4cacf4f-c189-4614-9c4e-22aabf14e0c0\"",
      "properties": {
        "fqdn": "privatelink.blob.core.windows.net.",
        "TTL": 3600,
        "SOARecord": {
          "host": "azureprivatedns.net.",
          "retryTime": 300,
          "email": "azureprivatedns-host.microsoft.com.",
          "serialNumber": 1,
          "refreshTime": 3600,
          "minimumTTL": 10,
          "expireTime": 2419200
        }
      }
    }
  ]
}
```

The record Azure would have written, added by hand:

```http
PUT {rg}/providers/Microsoft.Network/privateDnsZones/privatelink.blob.core.windows.net/A/pelabsa?api-version=2024-06-01 HTTP/1.1
Host: 127.0.0.1:4589
Authorization: Bearer eyJ0eXAiOiJKV1Qi…
Content-Type: application/json

{
  "properties": {
    "ttl": 10,
    "aRecords": [
      {
        "ipv4Address": "10.20.8.4"
      }
    ]
  }
}
```

```http
HTTP/1.1 201 Created
Content-Type: application/json;charset=UTF-8

{
  "id": "{rg}/providers/Microsoft.Network/privateDnsZones/privatelink.blob.core.windows.net/A/pelabsa",
  "name": "pelabsa",
  "type": "Microsoft.Network/privateDnsZones/A",
  "etag": "\"4f289ef2-89fb-4b34-b220-38110d7dab46\"",
  "properties": {
    "fqdn": "pelabsa.privatelink.blob.core.windows.net.",
    "TTL": 10,
    "aRecords": [
      {
        "ipv4Address": "10.20.8.4"
      }
    ],
    "ARecords": [
      {
        "ipv4Address": "10.20.8.4"
      }
    ]
  }
}
```

With that record, from inside `app-vnet`:

```text
pelabsa.blob.core.windows.net              CNAME  pelabsa.privatelink.blob.core.windows.net   (public DNS)
pelabsa.privatelink.blob.core.windows.net  A      10.20.8.4                                   (your zone)
```

From outside, the second name continues through public `CNAME`s to the account's public address, and `publicNetworkAccess: Disabled` refuses it there (docs).

### 4.5 Manual approval

An endpoint to a storage account in another subscription, where you have no rights. It goes into `manualPrivateLinkServiceConnections`, with a message for the owner:

```http
PUT {rg}/providers/Microsoft.Network/privateEndpoints/pe-partner-blob?api-version=2024-05-01 HTTP/1.1
Host: 127.0.0.1:4589
Authorization: Bearer eyJ0eXAiOiJKV1Qi…
Content-Type: application/json

{
  "location": "eastus",
  "properties": {
    "subnet": {
      "id": "{rg}/providers/Microsoft.Network/virtualNetworks/app-vnet/subnets/pe-subnet"
    },
    "manualPrivateLinkServiceConnections": [
      {
        "name": "partner-blob",
        "properties": {
          "privateLinkServiceId": "/subscriptions/11111111-1111-1111-1111-111111111111/resourceGroups/partner/providers/Microsoft.Storage/storageAccounts/partnersa",
          "groupIds": [
            "blob"
          ],
          "requestMessage": "orders team, ticket 4711"
        }
      }
    ]
  }
}
```

```http
HTTP/1.1 201 Created
Content-Type: application/json;charset=UTF-8

{
  "id": "{rg}/providers/Microsoft.Network/privateEndpoints/pe-partner-blob",
  "name": "pe-partner-blob",
  "type": "Microsoft.Network/privateEndpoints",
  "location": "eastus",
  "properties": {
    "subnet": {
      "id": "{rg}/providers/Microsoft.Network/virtualNetworks/app-vnet/subnets/pe-subnet"
    },
    "manualPrivateLinkServiceConnections": [
      {
        "name": "partner-blob",
        "properties": {
          "privateLinkServiceId": "/subscriptions/11111111-1111-1111-1111-111111111111/resourceGroups/partner/providers/Microsoft.Storage/storageAccounts/partnersa",
          "groupIds": [
            "blob"
          ],
          "requestMessage": "orders team, ticket 4711",
          "privateLinkServiceConnectionState": {
            "status": "Pending",
            "description": "Awaiting approval",
            "actionsRequired": "None"
          },
          "provisioningState": "Succeeded"
        },
        "id": "{rg}/providers/Microsoft.Network/privateEndpoints/pe-partner-blob/privateLinkServiceConnections/partner-blob"
      }
    ],
    "privateLinkServiceConnections": [],
    "customDnsConfigs": [],
    "networkInterfaces": [
      {
        "id": "{rg}/providers/Microsoft.Network/networkInterfaces/pe-partner-blob.nic.be80b83f"
      }
    ],
    "provisioningState": "Succeeded"
  }
}
```

**In Azure (docs):**
- The owner sees the request on the *target*, under `…/storageAccounts/partnersa/privateEndpointConnections`.
- The owner `PUT`s it back with `privateLinkServiceConnectionState.status` `Approved` or `Rejected`.
- The status is mirrored to your endpoint.
- Until then no traffic flows, though the NIC already has its IP.

floci-az has no such collection on the target, so a `Pending` connection stays `Pending`:

```http
GET {rg}/providers/Microsoft.Storage/storageAccounts/pelabsa/privateEndpointConnections?api-version=2023-05-01 HTTP/1.1
Host: 127.0.0.1:4589
Authorization: Bearer eyJ0eXAiOiJKV1Qi…
```

```http
HTTP/1.1 404 Not Found
Content-Type: application/json

{
  "error": {
    "message": "Resource not found: subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/pe-lab/providers/Microsoft.Storage/storageAccounts/pelabsa/privateEndpointConnections",
    "code": "ResourceNotFound"
  }
}
```

### 4.6 Private link service: publishing your own

The Azure counterpart of §3.4. A private link service sits in front of a Standard Load Balancer frontend. It takes NAT addresses from a subnet in the provider's VNet: the backend sees connections from those addresses, not from the consumer (docs). `visibility` controls who can find it, `autoApproval` whose endpoints skip the manual step:

```http
PUT {rg}/providers/Microsoft.Network/privateLinkServices/orders-pls?api-version=2024-05-01 HTTP/1.1
Host: 127.0.0.1:4589
Authorization: Bearer eyJ0eXAiOiJKV1Qi…
Content-Type: application/json

{
  "location": "eastus",
  "properties": {
    "loadBalancerFrontendIpConfigurations": [
      {
        "id": "{rg}/providers/Microsoft.Network/loadBalancers/orders-lb/frontendIPConfigurations/fe"
      }
    ],
    "ipConfigurations": [
      {
        "name": "nat1",
        "properties": {
          "subnet": {
            "id": "{rg}/providers/Microsoft.Network/virtualNetworks/app-vnet/subnets/aks-nodes"
          },
          "privateIPAllocationMethod": "Dynamic"
        }
      }
    ],
    "visibility": {
      "subscriptions": [
        "00000000-0000-0000-0000-000000000001"
      ]
    },
    "autoApproval": {
      "subscriptions": []
    }
  }
}
```

```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "id": "{rg}/providers/Microsoft.Network/privateLinkServices/orders-pls",
  "name": "orders-pls",
  "type": "Microsoft.Network/privateLinkServices",
  "location": "eastus",
  "properties": {
    "loadBalancerFrontendIpConfigurations": [
      {
        "id": "{rg}/providers/Microsoft.Network/loadBalancers/orders-lb/frontendIPConfigurations/fe"
      }
    ],
    "ipConfigurations": [
      {
        "name": "nat1",
        "properties": {
          "subnet": {
            "id": "{rg}/providers/Microsoft.Network/virtualNetworks/app-vnet/subnets/aks-nodes"
          },
          "privateIPAllocationMethod": "Dynamic"
        }
      }
    ],
    "visibility": {
      "subscriptions": [
        "00000000-0000-0000-0000-000000000001"
      ]
    },
    "autoApproval": {
      "subscriptions": []
    },
    "alias": "orders-pls.092968d1-b339-4053-b52e-3757279cb6f2.azure.privatelinkservice",
    "fqdns": [],
    "provisioningState": "Succeeded"
  }
}
```

Consumers use the **alias** (`orders-pls.<guid>.azure.privatelinkservice`) as `privateLinkServiceId`, so they don't need to know or have rights on the provider's resource id. floci-az made up the alias and didn't check that `orders-lb` exists.

### 4.7 What floci-az doesn't check

An endpoint in a VNet that doesn't exist, to a storage account that doesn't exist, is created and approved:

```http
PUT {rg}/providers/Microsoft.Network/privateEndpoints/pe-bogus?api-version=2024-05-01 HTTP/1.1
Host: 127.0.0.1:4589
Authorization: Bearer eyJ0eXAiOiJKV1Qi…
Content-Type: application/json

{
  "location": "eastus",
  "properties": {
    "subnet": {
      "id": "{rg}/providers/Microsoft.Network/virtualNetworks/no-such-vnet/subnets/x"
    },
    "privateLinkServiceConnections": [
      {
        "name": "c",
        "properties": {
          "privateLinkServiceId": "{rg}/providers/Microsoft.Storage/storageAccounts/nosuchaccount",
          "groupIds": [
            "blob"
          ]
        }
      }
    ]
  }
}
```

→ `201 Created`, connection `Approved` / `Auto-approved`, and a new NIC `pe-bogus.nic.cd0f33a9` with `10.0.0.4`. Azure answers `400` for a subnet it can't find, and refuses a target it can't find (docs).

### 4.8 The data plane ignores `publicNetworkAccess`

`pelabsa` has `publicNetworkAccess: Disabled` and `networkAcls.defaultAction: Deny`. The host, which is outside any VNet, still reads a blob:

```http
GET /pelabsa/reports/q3.csv HTTP/1.1
Host: 127.0.0.1:4589
Authorization: Bearer eyJ0eXAiOiJKV1Qi…
x-ms-date: Mon, 23 Nov 2026 17:00:11 GMT
x-ms-version: 2023-11-03
```

```http
HTTP/1.1 200 OK
Content-Type: text/csv
ETag: "0x27DDF84AF9766A0"

id,total
1,42
```

Azure Storage answers that request with `403 AuthorizationFailure` ("This request is not authorized to perform this operation"), whatever key or token is used (docs). Only requests that arrive through an approved private endpoint, or match a firewall rule, get through.

## 5. Kubernetes on top

All of this is (docs) except the IRSA environment variable, which was seen in [irsa-walkthrough.md](irsa-walkthrough.md).

- **Pods need nothing special for DNS.** CoreDNS forwards names outside `cluster.local` to the node's resolver, which is the VPC or VNet resolver. So a Pod gets the same private answer as the node. A custom CoreDNS `forward` to another DNS server breaks this unless that server forwards to the cloud resolver.
- **IRSA and STS.** The EKS Pod Identity webhook injects `AWS_STS_REGIONAL_ENDPOINTS=regional`, so SDKs call `sts.<region>.amazonaws.com`. That is the name an STS interface endpoint with private DNS answers. The global `sts.amazonaws.com` has no endpoint. Without the regional setting, a cluster with no internet route can't exchange the projected token.
- **Clusters with no internet route** need endpoints for everything the nodes and Pods call:
  - on EKS: ECR (`ecr.api`, `ecr.dkr`), the S3 gateway endpoint for image layers, STS, EC2, Elastic Load Balancing, CloudWatch Logs and any services the apps use;
  - on AKS: images come through private endpoints to ACR, but the Entra ID sign-in endpoints have no private endpoint. Workload identity's token exchange needs outbound access to `login.microsoftonline.com`, which is on AKS's list of required outbound FQDNs.
- **Private API servers use the same mechanism.**
  - EKS with private endpoint access puts EKS-managed ENIs in your subnets and answers the cluster's endpoint name with their IPs inside the VPC.
  - An AKS private cluster puts a private endpoint for the API server in the node resource group, with a `privatelink.<region>.azmk8s.io` private DNS zone.
- **Something must let the traffic reach the endpoint.**
  - On AWS, the endpoint's security group must allow 443 from the Pods' addresses: the Pod IPs with the VPC CNI, the node IPs with overlay CNIs that SNAT.
  - On Azure, NSGs on the endpoint's subnet only apply if `privateEndpointNetworkPolicies` is enabled.
  - A default-deny NetworkPolicy needs an egress rule to the endpoint's subnet on 443, as well as DNS egress to CoreDNS ([network-policy.md](network-policy.md)).

## 6. Where the emulators differ from the clouds

| | AWS Emulator 1.0.385 | floci-az nightly-11222026 |
|---|---|---|
| Interface's IP | first usable address of each subnet (`.4`), as in AWS (lab) | always `10.0.0.4`, whatever the subnet (lab, code) |
| Inputs checked | private DNS refused without `enableDnsHostnames` (lab) | nothing: a missing VNet, subnet or target is accepted (lab) |
| Second private-DNS endpoint for one service | accepted; AWS refuses (lab, docs) | n/a |
| DNS records | `dnsEntrySet` filled in, no resolver (lab) | zone group stored with `recordSets: []`, no `A` record written (lab, code) |
| Approval | `AcceptanceRequired` ignored: endpoint goes `available`; no connection to accept (lab) | automatic connections always `Approved`; manual stay `Pending`, nothing can approve them (lab, code) |
| Service-side restriction | network condition keys skipped in resource policies (lab) | `publicNetworkAccess: Disabled` and `networkAcls` not enforced on Blob (lab) |
| Not implemented | `ModifyVpcEndpointServicePermissions`, `DeleteVpcEndpointServiceConfigurations` (lab: `NotImplemented`) | `privateEndpointConnections` and `privateLinkResources` on the storage account (lab: `404`) |
| Traffic | none: no packets, no DNS server | none: "ARM-state only" (floci-az `docs/services/network.md`) |

## Related notes

- [azure.md](azure.md), Private Link and Private Endpoint: the overview this file runs through.
- [irsa-walkthrough.md](irsa-walkthrough.md): IRSA on the same AWS Emulator, including the injected `AWS_STS_REGIONAL_ENDPOINTS`.
- [azure-identity-flows.md](azure-identity-flows.md): floci-az identity flows.
- [core-dns.md](core-dns.md): how Pods resolve names outside the cluster.
- [network-policy.md](network-policy.md): egress rules a Pod needs to reach an endpoint.
- AWS docs, Access AWS services through AWS PrivateLink: https://docs.aws.amazon.com/vpc/latest/privatelink/privatelink-access-aws-services.html
- AWS docs, Gateway endpoints: https://docs.aws.amazon.com/vpc/latest/privatelink/gateway-endpoints.html
- AWS docs, Share your services through AWS PrivateLink: https://docs.aws.amazon.com/vpc/latest/privatelink/privatelink-share-your-services.html
- AWS docs, IAM condition operators (missing keys): https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_elements_condition_operators.html
- Microsoft Learn, What is a private endpoint?: https://learn.microsoft.com/azure/private-link/private-endpoint-overview
- Microsoft Learn, Azure Private Endpoint DNS configuration: https://learn.microsoft.com/azure/private-link/private-endpoint-dns
- Microsoft Learn, Manage network policies for private endpoints: https://learn.microsoft.com/azure/private-link/disable-private-endpoint-network-policy
- floci-az, Network service: https://github.com/floci-io/floci-az/blob/main/docs/services/network.md
