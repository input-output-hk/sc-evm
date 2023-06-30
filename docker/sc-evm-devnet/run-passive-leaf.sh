#!/usr/bin/env bash

docker run --rm \
  --network sc-evm-devnet_sc-evm-net \
  --env-file .leaf_env \
  -e START_SYNC \
  -e SC_EVM_LOGLEVEL \
  scevm_image:latest sc-evm-launch -Dconfig.file=./conf/local-standalone/application.conf
