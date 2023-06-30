# Changing bridge contract

The bridge contract is precompiled and included in the sidechain genesis block. Any change to the bridge contract results in a change to its bytecode,
that causes genesis block to change, which causes the genesis hash to change. Plutus scripts that are a main chain part of the bridge are parameterized using chainId and genesisHash.
That means that any change to either of these two parameters changes the Plutus scripts content. Plutus scripts derive their addresses from the hash of their content.
Those new addresses have to be updated next in the relevant SC_EVM configuration file.

To sum up: any change to the bridge contract causes Plutus scripts to change, which requires an update in the SC_EVM configuration file and is essentially a brand new sidechain.

If you change the bridge contract, you must update the script with the genesis with the bytecode for it to take effect.
