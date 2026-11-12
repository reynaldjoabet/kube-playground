# IOMMU: why it exists

The core problem is that devices do **DMA** (direct memory access): they read
and write RAM directly, without the CPU. Before IOMMUs, devices used raw
physical addresses with no translation and no permission checks. Each problem
below follows from that.

An **IOMMU** sits between devices and memory, the way the MMU sits between the
CPU and memory. It translates the addresses a device uses (I/O virtual
addresses, **IOVAs**) into physical addresses, and checks that the device is
allowed to touch them.

| Problem | What the IOMMU provides |
|---|---|
| Devices can't address all of RAM | Low IOVAs mapped to any physical page |
| Devices need contiguous buffers | Scattered pages mapped to look contiguous |
| Any device can write anywhere | Per-device page tables; stray accesses fault |
| VMs can't safely own real devices | Guest-physical to host-physical translation, per device |
| Devices can forge interrupts | Interrupt remapping |
| Accelerators can't share process memory | Shared virtual addressing (SVA/PASID) |

## Hardware or software?

The IOMMU is **hardware**. Software (the kernel) programs it, the same way the
CPU's MMU is hardware that the kernel configures.

### The hardware part

- **Where it is.** It's a physical unit in the path between devices and RAM.
  On modern x86 it's integrated into the CPU package, at the PCIe root complex.
  On ARM it's a separate block on the SoC.
- **What it's called:**

  | Vendor | Name | Notes |
  |---|---|---|
  | Intel | VT-d | Its units are called DMAR / DRHD |
  | AMD | AMD-Vi | Often just "IOMMU" |
  | ARM | SMMU | |

- **What it does, per transaction:**
  - reads the requester ID
  - walks the page tables
  - caches translations in the IOTLB
  - checks permissions
  - blocks bad accesses and raises fault interrupts

  This has to be hardware, because it happens on every DMA, at bus speed.

### The software part

The kernel decides what each device may reach. It builds the device table and
the per-domain page tables, and tells the hardware when to invalidate cached
translations. Drivers do this through the DMA API, and VFIO does it for devices
handed to VMs or userspace (next section).

## How translation works: MMU vs IOMMU

The IOMMU does for device memory accesses what the MMU does for the CPU's.
Both translate an address, check permissions, and either reach physical RAM or
fault:

```
              CPU SIDE                                     DEVICE SIDE

   +-------------------------+                 +-------------------------+
   |      CPU (process)      |                 |  Device (NIC/GPU/disk)  |
   |                         |                 |                         |
   |  load/store to          |                 |  DMA read/write to      |
   |  VIRTUAL address        |                 |  IOVA (I/O virtual)     |
   |  e.g. 0x7fff_1000       |                 |  e.g. 0x0010_0000       |
   +------------+------------+                 +------------+------------+
                |                                           |
                | VA                                        | IOVA + requester ID
                |                                           | (bus:dev.fn, e.g. 03:00.0)
                v                                           v
   +-------------------------+                 +-------------------------+
   |           MMU           |                 |          IOMMU          |
   |                         |                 |                         |
   |  1. which page tables?  |                 |  1. which page tables?  |
   |     -> CR3 (current     |                 |     -> look up requester|
   |        process)         |                 |        ID in device     |
   |                         |                 |        table -> domain  |
   |  2. check TLB           |                 |  2. check IOTLB         |
   |     miss -> walk tables |                 |     miss -> walk tables |
   |                         |                 |                         |
   |  3. check permissions   |                 |  3. check permissions   |
   |     (R/W/X, user/kern)  |                 |     (R/W)               |
   +-----+-------------+-----+                 +-----+-------------+-----+
         |             |                             |             |
     mapped        not mapped                    mapped        not mapped
         |             |                             |             |
         |             v                             |             v
         |   +------------------+                    |   +------------------+
         |   |   PAGE FAULT     |                    |   |   DMA FAULT      |
         |   |   -> kernel      |                    |   |   access blocked |
         |   |   (SIGSEGV etc.) |                    |   |   -> logged,     |
         |   +------------------+                    |   |   kernel IRQ     |
         |                                           |   +------------------+
         | physical address                          | physical address
         v                                           v
   +-------------------------------------------------------------------------+
   |                              PHYSICAL RAM                               |
   |                                                                         |
   |   [ process A pages ]  [ kernel ]  [ NIC rx buffer ]  [ VM 2 memory ]   |
   +-------------------------------------------------------------------------+
                ^                                           ^
                |                                           |
   +------------+------------+                 +------------+------------+
   |         KERNEL          |                 |         KERNEL          |
   |  builds per-process     |                 |  builds per-domain      |
   |  page tables            |                 |  page tables via        |
   |  (mmap, fork, exec)     |                 |  dma_map_*/dma_unmap_*  |
   +-------------------------+                 +-------------------------+
```

The two sides are deliberately parallel. Where they differ:

- **Choosing the page tables.**
  - The MMU uses whatever the CPU is running. On x86, the `CR3` register
    points at the current process's page tables, and a context switch changes
    it.
  - A device has no "current process", so the IOMMU identifies *who* is
    asking. Every PCIe transaction carries a **requester ID** (bus:device.function,
    e.g. `03:00.0`). The IOMMU looks it up in its device table to find the
    device's **domain**: the set of page tables that device is allowed to use.
    Several devices can share a domain; a VM's passed-through devices usually do.
- **Caching.** The MMU caches translations in the TLB, the IOMMU in the
  **IOTLB**. When the kernel unmaps a buffer it must invalidate the IOTLB
  entry too. Until it does, the device can still reach that page. Deferring
  these invalidations for speed is a known security tradeoff.
- **Faults.**
  - A CPU page fault is part of normal operation. The kernel can load the page
    (demand paging, copy-on-write) and retry the instruction, or kill the
    process with `SIGSEGV`.
  - An ordinary DMA fault can't be retried: the access is blocked, the IOMMU
    records it and raises an interrupt, and the kernel logs it. The device
    sees a failed transaction, and the driver usually has to reset it.
  - Recoverable device page faults exist only with SVA/PASID (§6).
- **Who builds the tables.**
  - The kernel builds process page tables as a side effect of `mmap`, `fork`
    and `exec`.
  - Drivers build IOMMU mappings explicitly through the **DMA API**:
    `dma_map_single`/`dma_map_page`/`dma_map_sg` before handing a buffer to
    the device, and `dma_unmap_*` after. When there's no IOMMU, the same calls
    return plain physical addresses, or use bounce buffers (§1).
  - For a device given to a VM or a userspace driver, VFIO creates the
    mappings instead, on request from the hypervisor or the program (§4).

---

Before the IOMMU the device side looked like this, with no check in the path:

```sh
   +-------------------------+
   |  Device (NIC/GPU/disk)  |
   |  DMA to PHYSICAL addr   |
   +------------+------------+
                |
                |  raw physical address, no translation, no checks
                v
   +-------------------------------------------------------------------------+
   |                            PHYSICAL RAM                                  |
   |   [ process A ]  [ KERNEL ]  [ secrets ]  [ other VMs ]                  |
   |        ^              ^           ^             ^                        |
   |        +--------------+-----------+-------------+                        |
   |               device can reach ALL of it                                 |
   +-------------------------------------------------------------------------+
```

Many devices, one IOMMU. Each device is mapped to its own domain:

```sh
   +--------+   +--------+   +--------+
   | NIC    |   | GPU    |   | NVMe   |
   | 03:00.0|   | 01:00.0|   | 02:00.0|
   +---+----+   +---+----+   +---+----+
       |            |            |
       +------------+------------+
                    |
                    v
   +------------------------------------+
   |              IOMMU                 |
   |  device table:                     |
   |    03:00.0 -> domain A  (host)     |
   |    01:00.0 -> domain B  (VM 1)     |
   |    02:00.0 -> domain A  (host)     |
   +------------------------------------+
           |                 |
           v                 v
   +---------------+  +---------------+
   | domain A      |  | domain B      |
   | page tables   |  | page tables   |
   | host buffers  |  | VM 1 memory   |
   | only          |  | only          |
   +---------------+  +---------------+

```

## 1. Addressing limits

Many devices could only generate 32-bit DMA addresses (24-bit for ISA). On a
machine with more RAM than that, the device couldn't reach memory above the
limit.

The workaround was **bounce buffers**:

1. The kernel copies the data into low memory.
2. The device does its DMA there.
3. The kernel copies the data back.

That costs CPU time and memory bandwidth.

An IOMMU lets the device use a small, low IOVA that maps to any physical page,
high memory included. Linux still keeps the bounce-buffer fallback, as
**swiotlb** (`kernel/dma/swiotlb.c`).

## 2. Fragmented memory and contiguous buffers

Many devices want one large contiguous buffer. Physical memory is fragmented,
so large contiguous allocations are hard to get.

An IOMMU can map scattered physical pages so they look contiguous in IOVA
space. This was an early motivation on DEC Alpha, Sun SPARC (DVMA) and the AGP
GART for graphics.

## 3. No protection: any device can write anywhere

DMA bypasses the CPU's MMU. A buggy driver that programs a wrong address, or a
faulty device, can corrupt arbitrary kernel memory. It's hard to debug,
because nothing faults.

Malicious hardware exploits the same gap. FireWire, Thunderbolt and PCIe **DMA
attacks** read secrets from, or patch, the running kernel through a plugged-in
device.

An IOMMU gives each device its own page tables. The device can only reach
pages explicitly mapped for it, and stray accesses fault instead of silently
corrupting memory.

## 4. Virtualization and device passthrough

When a physical NIC or GPU is assigned directly to a VM, the guest's driver
programs it with **guest-physical** addresses. Without translation those
addresses hit the wrong host memory, and a guest could DMA into the hypervisor
or into other VMs.

The alternative was emulating devices in the hypervisor, which is slow.

Intel **VT-d** and AMD **AMD-Vi** exist largely for this. The IOMMU translates
guest-physical to host-physical addresses per device, and confines each device
to its own VM. Linux's **VFIO** framework builds on it.

## 5. Interrupt spoofing

Message-signaled interrupts (**MSI**) are just DMA writes to a special address
range. That means a device, or a guest that controls one, could forge
interrupts, including ones aimed at other VMs.

IOMMUs added **interrupt remapping**, which validates and translates these
writes.

## 6. Sharing address spaces with accelerators

This came later. GPUs and accelerators work better if they can use a process's
own virtual addresses directly, instead of the driver pinning and translating
every buffer.

Modern IOMMUs support **SVA** (shared virtual addressing), with **PASID**
(process address space ID) tagging each device request with the process it
belongs to. The device walks the same page tables as the process, and page
faults are handled through the IOMMU.

---

## In practice: where it matters

Roughly from most to least common.

### Protecting against malicious devices (laptops, desktops)

Plugging in a Thunderbolt or USB4 device gives it PCIe access, which means DMA.
Without an IOMMU, a $100 rogue device could read your RAM (disk encryption keys,
passwords) or patch the kernel in seconds, even with the screen locked.

With the IOMMU on, the device can only touch buffers its driver mapped (§3).

| OS | How it's enabled |
|---|---|
| Windows | "Kernel DMA Protection" |
| Linux | `intel_iommu=on`, plus Thunderbolt security levels |
| macOS | On by default |

### Giving a VM a real device (passthrough)

- **Cloud.** AWS, GCP and Azure give VMs real NICs and NVMe drives, through
  SR-IOV virtual functions, at near-native speed. The IOMMU is what stops one
  customer's VM from DMAing into another's memory (§4).
- **Home.** Passing a GPU to a Windows VM for gaming or CAD, or a disk
  controller to a NAS VM. On Linux this is VFIO plus the IOMMU.

Without it, you're stuck with emulated or paravirtual devices. They're slower,
and they miss features such as real GPU drivers.

### Safe userspace drivers

DPDK (packet processing) and SPDK (storage) drive NICs and SSDs directly from
userspace for very high throughput. VFIO with the IOMMU lets an ordinary process
do this without being able to DMA over the kernel. Without it, you need root and
full trust in the program.

### Containing buggy drivers and hardware

A driver that DMAs to a freed or wrong address produces a logged fault naming the
device and the address, instead of silent memory corruption that crashes
something unrelated hours later. This turns impossible bugs into debuggable ones
(§3; faults are described in "How translation works").

### Performance on big-memory machines

Devices limited to 32-bit DMA can reach all of RAM without bounce-buffer copies
(§1).

### Confidential computing

With AMD SEV and Intel TDX, VM memory is protected even from the host. The IOMMU
helps keep devices out of protected memory unless the guest explicitly shares it.

---

## Example: how the cloud uses it

The cloud uses the IOMMU to give tenant VMs real hardware (NICs, NVMe drives,
GPUs) at near-native speed. At the same time it guarantees that one tenant's
device can't touch another tenant's memory, or the host's.

### 1. The physical host

One physical NIC is split into many virtual NICs with **SR-IOV**. Each VM gets
one, and the IOMMU fences each one into its VM's memory:

```
+===========================================================================+
|                         PHYSICAL SERVER (one host)                        |
|                                                                           |
|   +----------------+   +----------------+   +----------------+            |
|   |  VM 1          |   |  VM 2          |   |  VM 3          |            |
|   |  (Customer A)  |   |  (Customer B)  |   |  (Customer C)  |            |
|   |                |   |                |   |                |            |
|   |  guest driver  |   |  guest driver  |   |  guest driver  |            |
|   |  for "its" NIC |   |  for "its" NIC |   |  for "its" NIC |            |
|   +-------+--------+   +-------+--------+   +-------+--------+            |
|           |                    |                    |                     |
|           | direct access      | direct access      | direct access       |
|           | (no hypervisor     | (no hypervisor     | (no hypervisor      |
|           |  in the data path) |  in the data path) |  in the data path)  |
|           v                    v                    v                     |
|   +-------------------------------------------------------------------+   |
|   |                 PHYSICAL NIC  (SR-IOV capable)                    |   |
|   |                                                                   |   |
|   |   +---------+   +---------+   +---------+   +----------------+    |   |
|   |   |  VF 1   |   |  VF 2   |   |  VF 3   |   |  PF            |    |   |
|   |   | 3b:00.1 |   | 3b:00.2 |   | 3b:00.3 |   | 3b:00.0        |    |   |
|   |   | -> VM 1 |   | -> VM 2 |   | -> VM 3 |   | (host/control) |    |   |
|   |   +----+----+   +----+----+   +----+----+   +-------+--------+    |   |
|   +--------+-------------+-------------+----------------+-------------+   |
|            |             |             |                |                 |
|            |   DMA requests, each tagged with its own requester ID        |
|            v             v             v                v                 |
|   +-------------------------------------------------------------------+   |
|   |                            IOMMU                                  |   |
|   |                                                                   |   |
|   |   device table:                                                   |   |
|   |     3b:00.1 (VF 1) ----> domain 1  (VM 1 page tables)             |   |
|   |     3b:00.2 (VF 2) ----> domain 2  (VM 2 page tables)             |   |
|   |     3b:00.3 (VF 3) ----> domain 3  (VM 3 page tables)             |   |
|   |     3b:00.0 (PF)   ----> domain 0  (host page tables)             |   |
|   +--------+-------------+-------------+----------------+-------------+   |
|            |             |             |                |                 |
|            v             v             v                v                 |
|   +-------------------------------------------------------------------+   |
|   |                         PHYSICAL RAM                              |   |
|   |                                                                   |   |
|   |   [ VM 1 memory ] [ VM 2 memory ] [ VM 3 memory ] [ hypervisor ]  |   |
|   |         ^               ^               ^               ^         |   |
|   |       VF 1 only       VF 2 only       VF 3 only       PF only     |   |
|   +-------------------------------------------------------------------+   |
|                                                                           |
|   +-------------------------------------------------------------------+   |
|   |  HYPERVISOR (KVM / Nitro / Hyper-V)                               |   |
|   |  - creates VFs, assigns one per VM (VFIO)                         |   |
|   |  - builds each domain's page tables: guest-phys -> host-phys      |   |
|   |  - is NOT involved per packet, only at setup                      |   |
|   +-------------------------------------------------------------------+   |
+===========================================================================+
```

What to notice:

- **The physical function (PF) is the real device.** It stays with the host
  and is used for control.
- **Each virtual function (VF) is a separate PCIe function.** Each has its own
  requester ID (`3b:00.1`, `3b:00.2`, ...).
- **Separate requester IDs mean separate domains.** The IOMMU maps each VF to
  its own domain and page tables, exactly the lookup in
  "How translation works".
- **A VF can only reach its own VM's memory.** Any address outside its domain
  is a DMA fault, not an access to another tenant (§3, §4).
- **The hypervisor is only involved at setup.** It creates the VFs, assigns
  one to each VM through VFIO, and builds each domain's guest-physical to
  host-physical page tables. Packets then flow between the guest driver and
  its VF directly, which is why it's close to native speed.

### 2. One packet arriving for VM 2

```
   VM 2 guest driver                 IOMMU                       RAM
   ------------------                -----                       ---
   "rx buffer is at
    guest-phys 0x1000"
          |
          | programs VF 2 with 0x1000
          v
   +-------------+
   |   VF 2      |  packet arrives from network
   |  3b:00.2    |
   +------+------+
          |
          | DMA write to 0x1000, requester ID = 3b:00.2
          +------------------------->+
                                     | 1. 3b:00.2 -> domain 2
                                     | 2. walk domain 2 tables:
                                     |    guest 0x1000 -> host 0x8_4000_1000
                                     | 3. permission: write OK
                                     +---------------------------->+
                                                                   | data lands in
                                                                   | VM 2's buffer
                                                                   | at host
                                                                   | 0x8_4000_1000
```

The guest only ever knows its own guest-physical addresses. The IOMMU silently
translates them to wherever the hypervisor actually placed that VM's memory.

### 3. A malicious tenant tries to escape

```
   VM 2 (malicious) tells VF 2:
   "DMA into address 0x5000_0000"   <- hoping to hit VM 1's or the host's memory
          |
          v
   +-------------+
   |   VF 2      |
   +------+------+
          |
          | DMA write to 0x5000_0000, requester ID = 3b:00.2
          v
   +----------------------------------+
   |             IOMMU                |
   |  3b:00.2 -> domain 2             |
   |  walk domain 2 tables:           |
   |    0x5000_0000 -> NOT MAPPED     |
   +----------------+-----------------+
                    |
                    v
   +----------------------------------+
   |  BLOCKED                         |
   |  - write never reaches RAM       |
   |  - fault logged with device ID   |
   |  - hypervisor notified           |
   +----------------------------------+

   VM 1 memory: untouched
   Host memory: untouched
```

VM 2 can only name addresses that exist in its own domain. Everything else,
including other customers and the hypervisor, has no translation, so it's
unreachable.
