# trustless-bridge

trustless-bridge is the central element of the EVM Sidechains that operates on the Cardano main chain.
It's made up of on-chain and off-chain code. The on-chain code is Plutus that performs on-chain validations and operations.
The off-chain code is the user interface to the on-chain part.

## sidechain parameters

In order to allow many EVM Sidechains be run against one Cardano chain, each sidechain is identified by a set of parameters:

- sidechain id
- EVM genesis block hash
- genesis Utxo
- required signatures threshold (numerator and denominator)

These parameters are hashed together with the Plutus code to obtain scripts, Cardano addresses, and policy ids, so they identify sidechain.
If any of these differ, we have another sidechain.

## CTL

trustless-sidechain CTL is a [CTL](https://github.com/Plutonomicon/cardano-transaction-lib) application.
CTL is a PureScript library for building smart contract transactions on Cardano.
This allows interacting with EVM Sidechains on-chain code using the JavaScript stack.

### Dependencies

In order to perform, trustless-sidechain CTL has three dependencies:

- ogmios
- kupo

They are provided for the environment in `config.json`:

```json
{
  "runtimeConfig": {
    "network": "testnet",
    "ctlServer": {
      "host": "<ctl server host address>",
      "port": 443,
      "path": null,
      "secure": true
    },
    "ogmios": {
      "host": "<ogmios server host address>",
      "port": 1337,
      "path": null,
      "secure": true
    },
    "kupo": {
      "host": "<kupo server host address>",
      "port": 9999,
      "path": null,
      "secure": true
    }
  }
}
```

Note that you can start local instances for kupo and ogmios on top of preprod via the `sidechain` devshell. 
See [here](../howto/run-locally-cardano-infra.md).
