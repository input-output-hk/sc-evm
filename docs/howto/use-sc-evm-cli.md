### Transform 'inspect_getChain' from JSON to dot

```commandline
scala-cli ./jsonToDot.scala -- file.json
```

### Trustless-sidechain dependency

Some commands depend on [sidechain-main-cli](https://github.com/input-output-hk/trustless-sidechain) under the hood.
This tool is a [node](https://nodejs.org/en/) CLI application. It also requires
the [CTL stack](../howto/run-locally-cardano-infra.md) to be up.

`node` installation is required also.
The CTL tool has several runtime dependencies:

- ogmios
- kupo

You can copy the `config.template.json` file at the root of the repository to `config.json` to provide the various servers addresses.
The rest of the parameters can stay null. They aren't used, but keys have to be present.

### Search for pending and queued incoming transactions in the chain

The lifecycle of an incoming, from main chain to sidechain, transaction is:

1. pending - transaction is observed in Cardano, but the block including it is not stable yet
2. queued - block including transaction is stable, but node hasn't seen it reflected in sidechain
   (the interval in which a transaction is queued is usually short)
3. done - transaction is present in some sidechain block accepted by queried node.

To query for all pending and queued transactions use:

```commandline
sbt "scEvmCli/run pending-txs --sc-evm-url=http://localhost:8546"

{
  "pending": [
    {
      "recipient": "0x6aad2a7b6f14e179ce162d35addc5ad705b2d945",
      "value": "0x32",
      "txId": "bbbb7dcc11c47658590d8722e3659b857d80d488fc757760dfc18a49b9e42222#0"
    }
  ],
  "queued": [
    {
      "recipient": "0x6aad2a7b6f14e179ce162d35addc5ad705b2d946",
      "value": "0x64",
      "txId": "aaaa7dcc11c47658590d8722e3659b857d80d488fc757760dfc18a49b9e42233#0"
    }
  ]
}

```

`--recipient` parameter can be used to restrict results:

```commandline
sbt "scEvmCli/run pending-txs --sc-evm-url=http://localhost:8546 --recipient=0x6aad2a7b6f14e179ce162d35addc5ad705b2d946"
```

### Search an incoming transaction in the chain

```commandline
sbt "scEvmCli/run search-incoming-txs --help"
```

for instance

```commandline
sbt "scEvmCli/run search-incoming-txs --sc-evm-url http://localhost:8546 --to 5000 --tx-hash 472305c8c7f2f388ba7ebfc3ee5459c52105a16546176ef5db544416597bd0a7"
  - Transaction in block 1 (0xcbc9b92a09edc4fb6fb46dc0982e851f4c9acf61c5c9fce1227943b4e8bee09a):
    * utxo:      472305c8c7f2f388ba7ebfc3ee5459c52105a16546176ef5db544416597bd0a7#0
    * recipient: Address(value='0000000000000000000000000000000000000000')
    * value:     1 FUEL
```

### Sending tokens to sidechain

```commandline
sbt "scEvmCli/run burn-sc-token --help"
```

for instance

```commandline
sbt "scEvmCli/run burn-sc-token --signing-key-file payment1.skey \
  --recipient ae3dffee97f92db0201d11cb8877c89738353bce \
  --amount 10"
```

The resulting amount of tokens claimable on the sidechain will be equal to the burned amount multiplied by `TOKEN_CONVERSION_RATE`.

### Funding sidechain address with faucet

```commandline
sbt "scEvmCli/run request-funds --recipient 0x0011223344556677889900112233445566778899 --sc-evm-url <SC EVM node url>"
```

### Claiming transferred funds on the main chain

```commandline
sbt "scEvmCli/run burn-sc-token --signing-key-file payment1.skey \
  --combined-proof d8799fd8799f001a0001d4c0581d606e9d4f6a3f900f7b510b27f29d8e79c550686ce093946b23b3d1828ed8799f58206ed10e8180f6e3ecbb19c060ac2dbf51eccc33569b91e2047a58be9a9e84ffd2ffff80ff"
```
