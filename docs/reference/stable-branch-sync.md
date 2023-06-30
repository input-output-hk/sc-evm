# Description

The `StableBranch` sync mechanism uses known stable branches on the network to select its target, reach it, and assess
if the synchronization is finished.

# Design principles

The `StableBranch` sync is targeted to be:

- simple: easy to understand, easy to maintain
- good enough for regular operation: network startup, single node restart, fresh node introduction
- robust: either succeed and leave a node in a viable state, or fail and stop the node with a proper description

As such, not all the edge cases are handled (see [Edge Cases](#edge-cases))

# Glossary

## Initial local stable

The stable block of the SC_EVM node before the synchronization. The synchronization
will always respect this initial local stable, and will only sync with branches extending it.

## Stable block density

Shorthand for the density computed between an ancestor and the peer's stable blocks.
The ancestor block is defined as the n-th previous block (see configuration section), or genesis if
the peer's chain is smaller than n.

## Network stable

The stable block from connected peers with a density above 2/3 and with the highest block number

OBFT guarantees protection against malicious intent when more than 2/3 of the slot leaders are honest.
By enforcing a minimum density above 2/3 the synchronization targets an honest and valid chain.
Under that threshold, it can't distinguish between malicious construct or honest participation
issues (like network split, downtime, etc)

## Checkpoint

When the synchronization has managed to download and execute a branch up to a target network stable block,
it will mark the reached block as its internal checkpoint.

As the chain will keep growing during that time, it may be required to re-assess the network local block,
and download the missing part. The checkpoint is then used as the starting point for the download and
execution of the missing blocks.

However, the checkpoint can be discarded by the synchronization process if the new network stable is not
on the same branch. In that case, the synchronization restarts from the initial local stable.

# Algorithm

- start from its own stable (referred to as initial local stable, and used as first checkpoint)
- select the best peer stable (referred to as network stable)
- if the target stable is on the same branch as the checkpoint, download and execute blocks from checkpoint up to network stable
  - update checkpoint with reached network stable
  - repeat from checkpoint until current checkpoint == network stable
- if the target stable is not on the same branch as the last checkpoint, restart from the initial local stable
- if the target stable is not on the same branch as the initial local stable, error
- once everything is done, update the local stable to reflect the node content, and hand over control
  to the consensus

# Network Stable Header selection

The network stable header selection is done thanks to a `resolver`.
Resolvers apply rules to extract the proper network stable header from the connected peers.

SC_EVM use two types of `resolvers`: `functional` and `technical`.

The `functional resolvers` express the business logic rules to select the network stable headers:

`HighDensity`:

- select all the headers with a score (chain density) above 2/3
- among all those selected, choose the header with the highest block number

`LowDensity`

- select all the headers with a score (chain density) above a minimum threshold (see configuration)
- among those selected, choose the header with the highest score. When multiple headers share the same high
  score, use the one with the highest block number

The `technical resolvers` compose and extend all the resolvers :

`with retry`:

- delegate the network stable selection to another resolver
- in case of failure retry the given number of times, according to configuration

`with fallback`:

- use the first resolver to find a network stable header
- if no result is found, fallback to the second resolver

The initial lookup is done with the following:

`with retry(HighDensity) with fallback LowDensity`

This allows the sync process to wait for other nodes to startup, exchange score, and favor the 'honest' chains.
After all the tentatives have failed, it can then fall back (once) to a lower density chain.

All the other following lookups are done with:

`with retry(HighDensity with fallback LowDensity)`

This allows the sync process to keep favoring the 'honest' chain, and be able to fall back immediately to
low density ones. This is particularly important for the last check (`local stable == network stable`). If the
initial lookup version had been used, and the sync is actually targeting a low density chain, it would have
to wait for all the retries to happen before returning the low density header, during which time the chain would
have progressed, meaning the sync would never be able to catch up with the network.

As such, if a high density target is chosen during the initial lookup, it would still be favored during the next ones.

If a low density target is chosen during the initial lookup, it means that no high density target was available
nor will interfere with this lookup.

The low density fallback can be deactivated in both cases (initial and following look-up) with the configuration entry
`enable-low-density-sync-fallback=false`

# Initial network startup

To allow a new network startup, when all the nodes are still at genesis, and are not able to advertise a proper scored
stable header, the rules are relaxed to allow the node to enter consensus instead of stopping
itself.

Relaxed rules:

- the node still needs at least another connected peer
- the node cannot find a network stable header because all other nodes have a chain that is too short for
  proper density computation
- the node is still at genesis

In that case, the node is able to leave the `Synchronization` phase and enter the `Consensus` one.

Please note that a node still needs connected peers to start. As a consequence, it's not possible to start a single node
network.

# Implementation detail

The process is carried out in chunks where intermediate checkpoints are used to prevent losing progress
(ie to download and execute repeatedly from the local node stable).
This is important for long chains, where the sync loop will run multiple times
while trying to catch up with the network. That way, it will only download and execute
the delta between the already recovered part and the new one, instead of always starting from
the node's starting state.

# Configuration

| Entry name                                                      | Description                                                                                                                                        | Example value |
| --------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- | ------------- |
| sc-evm.branchFetcher.max-blocks-per-message                      | max number of blocks to ask at once                                                                                                                | 1000          |
| sc-evm.branchFetcher.request-timeout                             | timeout delay before failing operation                                                                                                             | 10.seconds    |
| sc-evm.branchFetcher.subscription-queue-size                     | Technical buffer size                                                                                                                              | 10            |
| sc-evm.branchSlice.max-headers-in-memory-count                   | Max number of headers stored in memory during sync                                                                                                 | 10000         |
| sc-evm.sync.scoring-ancestor-offset                              | Offset for the "reasonable" ancestor used for scoring<br/> the stable branch of the peer<br/>Must be multiple times larger than the committee size |               |
| sc-evm.sync.network-stable-header-resolver-retry-count           | Number of tentatives to do before failing the network stable lookup                                                                                | 20            |
| sc-evm.sync.network-stable-header-resolver-retry-delay           | Time to wait before the next network stable lookup                                                                                                 | 30 seconds    |
| sc-evm.sync.enable-low-density-sync-fallback                     | Enable sync to target a low density chain if not finding a high density one                                                                        | true          |
| sc-evm.sync.network-stable-header-resolver-low-density-threshold | Minimum score for a header to be considered in the fallback lookup                                                                                 | 0.1           |
| sc-evm.network.peer.max-blocks-per-full-blocks-message           | Max number of blocks to return when answering GetFullBlocks requests                                                                               | 1000          |

# Edge cases

## StableHeader stuck during sync

During the sync process, a node will share its `StableHeader` (used for scoring) available during node's initialization, and won't
update it until the sync is finished. If its scoring is above all the other peers, but it's lagging behind,
it will act as a magnet and hide the real target.

This can easily happen if two nodes are stopped on a sane chain (density = 1), letting the chain keep growing, but
with a lower density. Upon the nodes restart, they will target the other high-scoring stable, and sync to that target,
while ignoring the growing part of the chain. Once their synchronization is done, they will enter the Consensus phase, which
will need to manage the remaining part of the chain.

example:

All nodes are participating in a high density chain from `G` (genesis) up to `B1`

```
node1: G [high density chain] B1
node2: G [high density chain] B1
node3: G [high density chain] B1
```

At that point, `node 2` and `3` are stopped. `Node 1` keeps participating in the chain.

```
node1: G [high density chain] B1 [low density chain] B2
node2: G [high density chain] B1 STOPPED
node3: G [high density chain] B1 STOPPED
```

`Node 2` and `3` are restarted. `Node 2` sees the high density stable from `node 3`, and `node 3` sees the one from `node 2`,
hence their sync target is `B1`.
Their sync loop finishes immediately, as they are already at `B1`, and they will ignore `B2` which has a lower score.
`Consensus` is now handling the `B1-B2` chain. This may be problematic as `Consensus` should only handle chain up to k blocks, but
in that case, depending on the delay between the restart, the `B1-B2` slice can be of arbitrary length.

The workaround would be to only restart one node at a time in case of conflicting scores. This works thanks to the
fact that a node ignores its own stable header when doing network stable resolution.

