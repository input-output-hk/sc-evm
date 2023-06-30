#!/usr/bin/env bash

# Clean up the data dirs (most likely you'll need to run it as root because files inside have been created by docker)

set -o errexit
set -o pipefail
set -o nounset

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
SC_EVM_INSTANCES_DIR=scevm-instances

echo "Cleaning sc-evm data directories located in $SCRIPT_DIR"

for dir in $(
  cd $SCRIPT_DIR/$SC_EVM_INSTANCES_DIR
  ls -d */
); do
  node_name=${dir%%/}
  node_dir=$SCRIPT_DIR/$SC_EVM_INSTANCES_DIR/$node_name/scevm-devnet
  echo "Cleaning up node $node_name in $node_dir"
  rm -rf "$node_dir/keystore"
  rm -rf "$node_dir/logs"
  rm -rf "$node_dir/rocksdb"
done

echo "Done"
