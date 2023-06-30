# Relay

## Overview

The relay process connects to **sidechain node** and gets data of epochs that are not yet relayed to the main chain.
It builds `sidechain-main-cli` commands for the chain initialization (only if it hasn't been done yet), saving transactions Merkle root hash
and updating committee, and then executes them.

## Dependencies

Connectivity to **ogmios**, **kupo** and **sidechain node** is required.

The relay process depends on `sidechain-main-cli` - [the trustless-sidechain CTL application](https://github.com/input-output-hk/trustless-sidechain/) - being present in the path.  
`sidechain-main-cli` uses the _config.json_ file. It's mandatory to provide **ogmios** and **kupo** parameters.
The remaining parameters can be left **null**, because relay provides them in runtime.
Please edit and rename _config.example.json_ to _config.json_.

## Building

`sbt relay/stage` in the repository root.

## Usage

After the build, an executable is available in _tooling/relay/target/universal/stage/bin/relay_.

When invoked, relay will try to (optionally) init sidechain and the relay up to 100 epochs.

```commandline
# Change to module directory
cd tooling/relay

# For help:
./target/universal/stage/bin/relay --help

# Example invocation
./target/universal/stage/bin/relay --node-url http://my-sc-evm-node --relay-signing-key-path /secrets/relay.skey --init-signing-key-path /secrets/init.skey
```

There is a script to restart **relay** perpetually with a short sleep between runs.
It assumes the parameters are in order. The last parameter is sleep length in seconds.

```commandline
./relay.sh http://my-sc-evm-node /secrets/relay.skey /secrets/init.skey 30
```

## Sidechain initialization

`--init-signing-key-path` is an optional parameter. If set, and sidechain initial committee hasn't been set, then Relay will try to run `sidechain-main-cli init`.

## Epoch data upload to the main chain

Invokes `sidechain-main-cli save-root` and `sidechain-main-cli committee-hash` with data obtained from the **sidechain node**.
Upload data that was pending when relay started.
