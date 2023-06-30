#! /bin/bash
while :; do
  echo $(date)
  ./target/universal/stage/bin/relay --node-url $1 --relay-signing-key-path $2 --init-signing-key-path $3
  sleep $4
done
