# Sidechain epoch phases

Each sidechain epoch spans **N=12k** slots, where **k** is the EVM sidechain stability parameter.
Each sidechain epoch starting at slot **s0** is divided into phases:

1. _regular_ phase starts at slot **s0**, ends at slot **s0 + 8k - 1**, so it ends **4k** slots before the end of the epoch.
   During this phase, outgoing transactions are added to the current epoch batch and will be ready for bridging to the main chain at the beginning of the next epoch.
2. _closed transaction batches_ phase starts at slot **s0 + 8k**, ends at slot **s + 10k - 1**, so it ends **2k** slots before the end of the epoch.
   Outgoing transactions posted at this phase of epoch **E** will be added to the **E + 1** batch,
   and will be ready for bridging to the main chain at the beginning epoch **E + 2**.
3. _handover_ phase starts at slot **s + 10k**, and ends at **s + 12k - 1**. Outgoing transaction will be included as in the previous
   phase. EVM Sidechain nodes are signing outgoing transactions batches in this phase, to be ready for giving data required for bridging
   them at beginning of the next epoch. At the same time they sign the committee to be used in the next epoch.

```
|-----------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|
|Sidechain epoch E                                                                              | Sidechain epoch E + 1                                                                         |
|-----------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|
|regular                                                        |closed         |handover       |regular                                                        |closed         |handover       |
|---------------------------------------------------------------|---------------|---------------|-----------------------------------------------------------------------------------------------|
|s0 + 00|s0 + 01|s0 + 02|s0 + 03|s0 + 04|s0 + 05|s0 + 06|s0 + 07|s0 + 08|s0 + 09|s0 + 10|s0 + 11|s0 + 12|s0 + 13|s0 + 14|s0 + 15|s0 + 16|s0 + 17|s0 + 18|s0 + 19|s0 + 20|s0 + 21|s0 + 22|s0 + 23|
|-----------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|
```

**12k** slots is taken from the [Proof-of-stake Sidechains paper](https://eprint.iacr.org/2018/1239.pdf).
SC_EVM can be configured to use longer sidechain epochs, but they should not be shorter than **12k**, and they should also align with the main-chain epochs.
Situation when main-chain epoch boundary is not a sidechain epoch boundary is an error.
Multiple sidechain epochs can fill in one main-chain epoch.
