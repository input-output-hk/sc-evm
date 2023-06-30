#!/usr/bin/env bash

#Available timeout option, example: setup.sh timeout=20000

git clone --depth 1 --branch v4.4.0 https://github.com/OpenZeppelin/openzeppelin-contracts.git

# If there is a timeout set, change it in the files
[[ $1 =~ ^timeout=[0-9]*$ ]] && TIMEOUT_VALUE=$(cut -d = -f 2 <<<$1) || echo "did not match"

echo $TIMEOUT_VALUE
if [ $TIMEOUT_VALUE ]; then
  sed -i "s|timeout: [0-9]*,|timeout: $TIMEOUT_VALUE,|g" config-files/hardhat.config.js
fi
#

cp config-files/hardhat.config.js config-files/secrets.json openzeppelin-contracts

cd openzeppelin-contracts
if command -v nvm; then
  nvm use v14.17.0
fi
npm install @nomiclabs/hardhat-solhint
npm install
npm install mocha mochawesome
