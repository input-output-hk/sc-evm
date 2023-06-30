# SC EVM

## Build the client

### With SBT

##### Prerequisites

- JDK 17
- sbt ([download sbt](http://www.scala-sbt.org/download.html))

##### Instructions

From the root of the project:

```commandline
sbt dist
```

Note: building in _dev_ mode allows faster and incremental compilation, for this:

- set environment variable `SC_EVM_DEV` to `true`, or
- use the system property `-DscEvmDev=true`

### With nix

It is possible to build the application with nix running:

```commandline
nix build
```

## Nix devshells

Four devshells are provided for convenience:

 - `default` provides most tools useful to work on the development of SC EVM
 - `sidechain` provides executables to run a sidechain on top of preprod (expect a long compilation time). More specifically:
   * cardano-node-preprod: a wrapper around cardano-node that will run a Cardano node on top of preprod (the state is inside .data)
   * cardano-db-sync-preprod: a wrapper around cardano-db-sync that will run DB Sync on top of cardano-node-preprod (the state is inside .data)
 - `ctl-infra` provides servers needed to run the off chain code
   * `ogmios-preprod`: a wrapper around Ogmios that targets Cardano preprod
   * `kupo-preprod`: a wrapper around Kupo that targets Cardano preprod (the state is inside .data)
 - `retesteth` provides the retesteth utility to test a node

To open a devshell, use `nix develop .#<devshell name>`

For instance, to launch the default devshell:
```commandline
nix develop
```
or for the sidechain's one:
```commandline
nix develop .#sidechain
```

## Pre-existing local configurations

### Running a fast local standalone network

This is a fast-paced network intended for testing. It has a single validator and creates a block per second. It's useful for running ERC20 tests locally.

This runs a network without sidechain capabilities with a predefined list of validators.

To start SC EVM in fast-net mode:

```commandline
sbt node/run -Dconfig.file=./modules/node/src/main/resources/conf/local-standalone-fast/application.conf
```

### Running devnet

It has a single validator and creates a block every 3 seconds.

This runs a network without sidechain capabilities with a predefined list of validators.

To start SC EVM in devnet mode:

```commandline
LEADER_PRIVATE_KEY=<redacted> sbt -Dconfig.file=./modules/node/src/main/resources/conf/local-standalone/application.conf node/run
```
Please note that at least two nodes must be available for starting a chain: a node needs at least a peer to connect to.

The first launch (from genesis) will take some time, subsequent node's starts will be faster, depending on the chain content.

#### Validator configuration

To modify the log level, add `-Dlogging.logs-level=<LEVEL>` with the desired log level.

To enable RPC API `-Dsc-evm.network.rpc.http.enabled=true`.

Other properties in the configuration can be passed via `-D<property>=<value>` as needed.

### Running devnet with docker-compose

A couple of docker-compose setup files can be found in `docker/sc-evm-devnet` for different network configurations.
As a precondition, you need to have docker and sbt installed.

[See the dedicated readme for more details.](docker/sc-evm-devnet/README.md)

## Integration tests

### ETS / retesteth

To run a node able to work with ETS:

```commandline
sbt testnode/run
```

[See the dedicated readme for more details.](integration-tests/ets/README.md)

### ERC20 tests

[See the dedicated readme for more details.](integration-tests/erc20/README.md)

### Faucet setup

Every SC EVM node can serve as a faucet.

First, you need to provide the private key to the faucet account.

This can be done either through configuration file under `sc-evm.faucet.private-key` key
or through the environment variable `SC_EVM_FAUCET_PRIVATE_KEY`.

The faucet API uses the same network interface as the rest of the node APIs.
That means that they share the network configuration.

To enable faucet functionality, turn on the faucet API in network configuration and provide faucet configuration:

```hocon
sc-evm {
  network {
    rpc {
      apis = "eth,net,web3,personal,faucet"
    }
  }
  faucet {
    # Transaction gas price
    tx-gas-price = 20000000000

    # Transaction gas limit
    tx-gas-limit = 90000

    # Transaction value
    tx-value = 1000000000000000000
  }
}
```

Run the following curl command to send tokens from your faucet to a wallet address:

```curl
curl --request POST \
  --url http://127.0.0.1:8099/ \
  --header 'Content-Type: application/json' \
  --data '{
  "jsonrpc": "2.0",
  "method": "faucet_sendFunds",
  "params": ["<address>"],
  "id": 1
}'
```
The faucet web interface can be found at https://github.com/input-output-hk/sc-evm-faucet-web

### External dependencies

#### External VM

[See the dedicated readme for more details.](modules/externalvm/README.md)
