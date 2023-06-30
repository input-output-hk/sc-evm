#!/usr/bin/env bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"

ERC_LIST="ERC20 ERC721 ERC1155"
if [[ $1 == "" || ! $ERC_LIST =~ (^|[[:space:]])$1($|[[:space:]]) ]]; then
  echo "Error: Unsupported ERC"
  echo "Valid ERCs:"
  for erc in $ERC_LIST; do
    echo $erc
  done
  exit 1
fi
ERC=$1
LOG_FILE="$(pwd)/out/$ERC-test-logs.log"

(
  cd "$SCRIPT_DIR/openzeppelin-contracts"
  npx hardhat --network sc_evm_network test test/token/$ERC/$ERC.test.js | tee -i $LOG_FILE
)

if [ ! -f "$LOG_FILE" ]; then
  echo "$LOG_FILE does not exist."
  exit 1
fi

FAILING="$(grep "[1-9][0-9]* failing" $LOG_FILE)"
SUCCESS="$(grep "[0-9][0-9]* passing" $LOG_FILE)"

if [ "$FAILING" = "" ]; then
  echo "Success"
  exit 0
else
  echo "Failure"
  echo $SUCCESS
  echo $FAILING
  exit 1
fi
