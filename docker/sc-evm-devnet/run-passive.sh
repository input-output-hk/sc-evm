#!/usr/bin/env bash

docker run --rm \
  --network sc-evm-devnet_sc-evm-net \
  --env-file .env \
  -e START_SYNC=true \
  -e SC_EVM_LOGLEVEL \
  -e SC_EVM_AKKA_LOGLEVEL \
  --name sc-evm-passive \
  scevm_image:latest sc-evm-launch -Dconfig.file=./conf/local-standalone/application.conf
