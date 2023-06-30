# Cardano follower lag

Internally, SC_EVM uses [cardano-db-sync](https://github.com/input-output-hk/cardano-db-sync) as a Cardano follower.
It is important that this follower is up to date with the main chain. This document's goal is to explain how DB Sync
lagging might affect SC_EVM.

SC_EVM can handle some level of lag but the impact depends on the feature of the node (synchronization/consensus or block
production) and events that happen on the main chain. The short version is:

- Any lag by one block can make a node unaware of a new stable incoming cross chain transaction, and a consequence
  make the node reject branches that are in fact valid if they contain unknown/unstable transactions, but won't affect
  block production.
- In the absence of incoming cross-chain transaction, 1/30th of the main-chain epoch duration of lag will cause issues
  because the node won't be able to know which validators are in the committee. This will prevent a node from taking new
  blocks during the handover, and also block production during the handover.
- The lag will also cause the RPC endpoints to give stale data. It's not problematic for SC_EVM itself but can be
  for someone running a relay.

Note about lag handling: right now, SC_EVM does not behave differently when getting a block that it cannot validate
because DB Sync is lagging, and an effectively invalid block. In both cases it will discard the branch as if it was
invalid. This is done to simplify the implementation. The expectation is that if the branch is valid and is the "right"
branch, then it will be advertised by the network over and over until we are not lagging anymore.

For both cases, SC_EVM just discards a block so this distinction is purely semantic. The difference is that internally
we have a specific error that allows us to distinguish between the cases if some day we want to refine the behaviour.

## Committee rotation

To determine the committee, SC_EVM needs:

- Stake delegation and nonce from the previous main-chain epoch, computed by DB Sync at the beginning of said epoch
- The state of validator registrations (ie, registrations UTXOs) at the end of the previous epoch (around 1 day before
  the end on Cardano mainnet).

As of now, SC_EVM needs information about main-chain epoch N-1 in order to know the committees during main-chain epoch N.

During the handover phase at the boundary of a main-chain epoch, ie transitioning from main-chain epoch N to N+1, SC_EVM
needs to know the info about main-chain epoch N and the state of registrations at a reference slot. The reference slot
is chosen to
be `2 * <securityParam> / <activeSlotsCoeff>` slots before the first slot of the epoch, and SC_EVM finds the latest
block (the reference block), which was appended to the chain during or before the reference slot.

On Cardano main chain, securityParam=2160 (ie 1/10th of the expected block per epoch) and activeSlotsCoeff=0.05. This
means that we
look at the state at around 4/5 of the epoch duration, one day before the end of the epoch.

If a node is lagging so that a node does now know which block is the reference block, it might not see a registration or
deregistration and won't agree on the next committee with the other node.

### Worst case

The worst case is if a registration was done during the reference block with the sidechain epoch having the same duration
as the main-chain epoch because then the duration for the lag before the effect is visible is minimal.
In that situation, the node will be stuck when the handover starts if it cannot compute the next commitee.
If `E` is the main-chain epoch duration (equal to the sidechain's):

- The reference slot happens at `4/5 E`
- The handover starts at `5/6 E`. By this time SC_EVM needs to know about the state of Cardano at the reference slot.
  So SC_EVM will potentially be stuck if the node is lagging by `5/6 E - 4/5 E = 1/30 E` where `E` is the main chain
  duration(<beginning of handover phase> - <reference slot>)

When this situation happens:

- A block producer will fork and produce a wrong block during the handover phase (wrong committee in the handover signature)
- A block producer or passive node will consider blocks invalid because of the handover.
- A node won't be able to synchronize to the network as it cannot validate the chain advertised by its bootstrap nodes

### Other behaviour

If no registration/deregistration happens during a given epoch, then a lagging node will still agree on the candidate
list because the registration state did not change. But the stake delegation must be known. SC_EVM uses the stake
delegation for main-chain epoch N to compute the committees for epoch N+1. DB Sync does not provide a way to know if the
stake delegation computation is done, so if the node is lagging too much we can get some incomplete stake delegation.

Note that this is the reason SC_EVM takes data from the previous epoch instead of the current one to compute the
committee (even though it would be possible in theory).

Even if the candidate registrations are right, we would get the same result as before:

- A block producer will fork and produce a wrong block during the handover phase (wrong committee in the handover signature)
- A block producer or passive node will consider blocks invalid because of the handover.

Taking the worst case as before, it would happen when the lag is around `5/6 E`.

And extreme case is if we have no information for the epoch at all. Then a block producer won't produce any block,
and will not be able to validate blocks with a handover transaction. The difference here is that it's easier to
see that the node lacks information.

## Cross chain transactions

Incoming transaction is the feature that will impact the most a SC_EVM node. Any lag that makes a node unaware of a new
main-chain block might impact the node.

An incoming transaction must be stable on Cardano to be included (around 12 hours on Cardano mainnet). So if a block
producer is up-to-date, and add a new incoming transaction to the chain, another node lagging might consider that block
invalid because it does not know that the transaction is stable. From there two possibilities:

- A block producer will fork to another chain. If the lag too high ( around
  `<slotDuration> * <committeeSize> * <stabilityParameter>` seconds), it might fork beyond stable
- A passive node will be stuck until it knows that the transaction is stable

The problem will happen if the lag duration is more than the propagation time of a sidechain block, ie by the time the
lagging node is receiving a new block it still hasn't received the main-chain block making the incoming transaction
stable. The worst case scenario is if every main-chain block contains an incoming transaction and the node
systematically reject a branch because of an unstable incoming transaction (but this is quite improbable).

The opposite situation is not blocking because there is no obligation to include incoming transaction for a block to be
considered valid, so a block produced by a lagging node won't be considered invalid.

## Main chain information

SC_EVM provides information about the main chain from RPC endpoints.
These endpoints are not essential to the liveness of the chain, but lag might impact what is returned by the node:

- `sidechain_getPendingTransactions` might not show a pending incoming transaction because it does not know about it yet
- `sidechain_getCurrentCandidates` won't return recent candidates
- `sidechain_getSignaturesToUpload` could return incorrect data: this point might be problematic for a relay node.
