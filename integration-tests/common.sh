#!/usr/bin/env bash

function wait_for_sc_evm {
  if [ "$#" -ne 1 ]; then
    echo "Illegal number of parameters. Expected 1 got $#"
    echo 'Usage wait_for_sc_evm $sc_evm_pid'
  fi

  while ! nc -z localhost 8546; do
    if ! (ps -p "$sbt_process") >/dev/null; then
      echo "error: the SC EVM node stopped"
      exit 3
    fi
    sleep 0.5
  done
}
