# Data generator

This module supports the generation of chains for integration testing purposes.
The generation is deterministic and generates a _rocksDB__ directory per chain that can be used in SC EVM.

## Generate a chain

`SimpleForkGenerator` puts together the different pieces of code that generate, import and store the chain.
The example uses the values from `simple-fork.conf` and produces a "base chain" and two forks (see _rocksDB_ directories in `${HOME}/.sc-evm/`).

```sbt
dataGenerator/run
// or
dataGenerator/run io.iohk.dataGenerator.SimpleForkGenerator
```

### Limitations

Only contracts transactions are available.
To simplify the retrieval of the contract address, all contracts are deployed on BlockNumber(1) for all senders.
Therefore, the number of contracts is bounded by the number of contracts deployable on one block.

## Usage of the generated chain

Copy the generated _rocksDB_ directory to your node _rocksDB_ directory

**Important**: the validators provided in the config are used as seeds to generate new key pairs since all private keys are required for generating a chain.
Therefore, set the nodes of your testing network with the newly generated keys.

## Troubleshooting

### Hints

- Run with _DEBUG_ log-level
- Check there is no transaction error
  ```log
  [i.i.m.l.TransactionExecutionImpl] DEBUG - Transaction TransactionHash(7cf5617f3db8906be6d8ce5966dcd34f6804890c90bf1bad2e72a1a1c4adb8ef) execution end. Summary:
  - Error: None.
  - Total Gas to Refund: 73577
  - Execution gas paid to producer: 26423
  ```
- Check there are no warnings about invalid transactions
  ```log
  [i.i.m.ledger.BlockPreparationImpl] WARN  - TransactionHash(cd03c6d5090de085ac0ada09b215e3b69685998f4fd9944a00a0b632de223267) ignored while preparing block at Slot(number = 1), BlockNumber(1). Reason: Insufficient transaction gas limit: Transaction gas limit (0) < transaction intrinsic gas (60368).
  ```

### Frequent error

The following error usually indicates that storage was not empty beforehand

```log
[error] java.lang.RuntimeException: The block was not imported
```

To clean the storage, run

```shell
rm -r ~/.sc-evm/<network>/rocksdb
rm -r ~/.sc-evm/<network>/logs
```
