# Ethereum Test Suite

The `integration-tests/ets` directory is a git subtree pointing at the Ethereum Consensus Tests,
also known as the Ethereum Test Suite (ETS), config files for retesteth (the
tool for running these tests) and a wrapper script to set command line
options. Oh, and this readme file is in there too, of course.

- ETS: https://github.com/ethereum/tests
- retesteth: https://github.com/ethereum/retesteth

**note:** the tests are in a git submodule.
Run `git submodule update --recursive --init` to download them.

# Important note

Currently, the tests only work for the Berlin HF, so all commands to run a test need to have `--singlenet Berlin` appended, like:

```commandline
integration-tests/ets/retesteth -t GeneralStateTests -- --singlenet Berlin
```

## Running locally

Use the `integration-tests/ets/run` wrapper script to boot SC EVM and run retesteth against it.

```
$ nix develop .#retesteth
# integration-tests/ets/run
```

Read on for more fine-grained control over running SC EVM and retesteth, by
running them separately.

## Running ETS in a Nix environment

Start SC EVM in test mode:

```
sbt -Dlogging.logs-level=WARN \
-Dsc-evm.node-key-file=integration-tests/ets/node.key \
testnode/run
```

NB. raising the log level is a good idea as there will be a lot of output,
depending on how many tests you run.

Once the RPC API is up, run retesteth:

```
integration-tests/ets/retesteth -t GeneralStateTests
```

You can also run parts of the suite; refer to `integration-tests/ets/retesteth --help` for details.

## Running retesteth in Docker (eg. macOS)

You should run SC EVM outside Nix as that is probably more convenient for your
tooling (eg. attaching a debugger.)

```
sbt -Dlogging.logs-level=WARN \
-Dsc-evm.node-key-file=integration-tests/ets/node.key \
testnode/run
```

The other mentioned commands are intended to be run inside the nix container.

Retesteth will need to be able to connect to SC EVM, running on the host
system. First, find the IP it should use inside the docker container with the `getent hosts host.docker.internal`
command:

```
node=$(getent hosts host.docker.internal | cut -f1 -d" ")
```

Finally, run retesteth in Nix in Docker:

```
integration-tests/ets/retesteth -t GeneralStateTests -- --nodes $node:8546
```

## Useful options:

You can run one test by selecting one suite and using `--singletest`, for instance:

```
integration-tests/ets/retesteth -t GeneralStateTests/VMTests/vmArithmeticTest -- --nodes $node:8546 --singletest add
```

However, it is not always clear in which subfolder the suite is when looking at the output of retesteth.

To get more insight about what is happening, you can use `--verbosity 6`. It will print every RPC call
made by retesteth and also print out the state by using our `debug_*` endpoints. Note however that
`debug_accountRange` and `debug_storageRangeAt` implementations are not complete at the moment.
