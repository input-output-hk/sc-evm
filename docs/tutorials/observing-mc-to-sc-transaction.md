# Observing main chain to sidechain transaction

## Main chain to sidechain transaction lifecycle

1. Cross-chain transaction starts with burning SC_TOKEN in the main chain.
   Since the burn transaction is visible in the unstable part of chain, it is in **pending** state.
   The last `securityParam` blocks in the chain are considered unstable, older blocks are stable.
   Search for `securityParam` in [CIP 9](https://cips.cardano.org/cips/cip9) to see its default value and explanation.
   In test networks, we use lower values like 36.
2. When the main chain block with the burn transaction becomes stable, then the cross-chain transaction enters **queued** state.
   Queued transactions are eligible for an SC_EVM node to include them in a sidechain block.
3. When the transaction is added to the sidechain, it is **executed**.

## Querying pending and queued transactions

There is a Python script that uses the SC_EVM node RPC endpoint to display transactions that are pending or queued,
so not yet included in the sidechain.

```commandline
sc-evm-cli pending-txs --sc-evm-url <sc-evm-node-url> --recipient <recipient-address-hex>
```

The `node` parameter should point to the URL of an SC_EVM node, for example `http://localhost:8546`.

The `recipient` parameter is optional, when it is present only transactions for the given sidechain address will be displayed.

See the following example:

```commandline
sc-evm-cli pending-txs --sc-evm-url <sc-evm-node-url> --recipient=0x0000000000000000000000000000000000000000
{
  "pending": [
    {
      "recipient": "0x0000000000000000000000000000000000000000",
      "value": "0x5",
      "txId": "89dc0bb8a15f73bf2ac4013f1ebdb9d9b3d8b5a855056d69a3a4804f7f17cd96#0"
    }
  ],
  "queued": []
}
```

When a transaction is included in the sidechain,
it won't be present in the output of `sc-evm-cli pending-txs`.

## Querying executed transactions

The tool for searching already executed transaction is `sc-evm-cli search-incoming-txs`.
It communicates with an SC_EVM node to read the whole chain searching for transactions.
It's an expensive operations, performed by iteratively querying the node.
Please use `--from` and `--to` parameters to restrict range of blocks queried.
Additional filters: `--tx-hash` and `--recipient` can be used to reduce the output.
Use `--sc-evm-url` to specify a SC_EVM node URL.

Use `sc-evm-cli search-incoming-txs --help` to print all supported parameters.

Example of usage:

```commandline
sc-evm-cli search-incoming-txs --sc-evm-url <sc-evm-node-url> --from 81500 --to 83000 --recipient 0x0000000000000000000012345123451234512345
  - Transaction in block 81707 (0xc49e77839fa9e1b19313b6a60fb205d397d126e2b575dfd492d9da76eb97d421):
    * utxo:      020bd96460b1bf9de5086465530ddb42a3d1cb6ee58c8ea39108febfb1ff4560#0
    * recipient: 0x0000000000000000000012345123451234512345
    * value:     5
    * status:    Success
```

### Bonus section: sources of the data

The entity used for querying transaction state is an SC_EVM node,
however it uses a Postgres database of [DB Sync](https://github.com/input-output-hk/cardano-db-sync)
that should be up to date. Getting the state of the main-chain DB Sync itself relies on a Cardano node.
It is critical that all components are in sync to observe actual cross-chain transactions.
