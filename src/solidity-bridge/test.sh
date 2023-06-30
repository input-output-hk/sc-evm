#!/usr/bin/env bash

GIT_ROOT="$(git rev-parse --show-toplevel || :)"
SCRIPT_DIR="$GIT_ROOT/src/solidity-bridge"

cd "$GIT_ROOT"

echo "booting SC EVM with log level WARN and waiting for RPC API to be up."
sbt -Dlogging.logs-level=DEBUG \
  -Dsc-evm.node-key-file="${SCRIPT_DIR}/node.key" \
  -Dconfig.file=./tooling/testnode/src/main/resources/hardhat.conf \
  testnode/run &

sbt_process=$!

trap "kill $sbt_process" EXIT

source "$GIT_ROOT/integration-tests/common.sh"

wait_for_sc_evm $sbt_process

cd "$SCRIPT_DIR"

npm install
export SC_EVM_URL=http://127.0.0.1:8546
npx hardhat --network scEvmNetwork test
