# ERC20 Tests

This folder contains a setup to run ERC20 tests from
[openzeppelin-contracts](https://github.com/OpenZeppelin/openzeppelin-contracts).

It serves as a smoke test to check a few things:

- SC EVM starts
- A passive node relay transactions
- A passive node synchronize with the latest block
- The EVM can run an instance of a ERC20 contract
- A validator node creates blocks with new transactions

The current setup is as follows:

```
[Validator] <----(RLPx)----> [Passive Node] <---(jRPC)---> [ERC20 test suite]
```

## Running the tests

The tests are automatically run on the CI (see [here](../../.github/workflows/ci.yml). They can
also run locally.

Prerequisites:

- nodejs
- docker

running `./integration-tests/erc20/run` will create a docker image, download the openzeppelin-contracts
repository and start the tests.

It can be faster to run it by hand once the setup has been done once to avoid running npm again.

```
# at the root of the repository
# assuming integration-tests/erc20/setup.sh has been called once before
sbt 'set node/version := "latest"' node/Docker/publishLocal
docker compose -f integration-tests/erc20/docker-compose.yml up
# export NODE_OPTIONS=--openssl-legacy-provider # <-- Use this command if using nodejs v17 or downgrade to nodejs v16
SC_EVM_URL=http://localhost:8546 integration-tests/erc20/run_tests.sh ERC20
docker compose -f integration-tests/erc20/docker-compose.yml down
```
