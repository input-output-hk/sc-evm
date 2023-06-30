# Steps to register an SPO as a sidechain validator:

For this guide, we assume you are a SPO with access to a cold secret key and a cardano address with funds.

1. Start CTL stack by following [howto](../howto/run-locally-cardano-infra.md), it is required to run the final `sidechain-main-cli register` command

2. Generate the SPO signatures. The `sc-evm-cli` tool can be used.
   Open a terminal, run `nix develop .#sc-evm-cli` and then run:

   ```
   sc-evm-cli generate-signature \
     --genesis-hash <hash> \
     --sidechain-id 80 \
     --genesis-utxo <TxHash>#<TxId> \
     --threshold-numerator 2 \
     --threshold-denominator 3  \
     --spo-signing-key $(cat <path to cold key>) \
     --sidechain-signing-key $(cat <path to validator private key>) \
     --registration-utxo <TxHash>#<TxId>
   ```

   `genesis-utxo` is the parameter of the sidechain, it's consumed when a sidechain is initialized.

   `spo-signing-key` is obtained from the cold.skey of the SPO to be registered. You will use the string from the field cborHex without the first 4 characters (5820).

   `sidechain-signing-key` is the private key associated with the SPO being registered. It will be used to sign the registration and later to sign the sidechain blocks.

   `registration-utxo` is a UTXO from the short payment address associated with the signing-key file.

   `genesis-hash` can be retrieved using the `sc-evm-cli genesis-hash` command.

   To query UTXOs from an address run `cardano-cli query utxo --address <address> --testnet-magic 7`

   Example of getting signatures with chain-id 80 and genesis-hash `d8063cc6e907f497360ca50238af5c2e2a95a8869a2ce74ab3e75fe6c9dcabd0`

   ```
   sc-evm-cli generate-signature \
     --genesis-hash d8063cc6e907f497360ca50238af5c2e2a95a8869a2ce74ab3e75fe6c9dcabd0 \
     --sidechain-id 80 \
     --genesis-utxo be6c22802e00349c953f53c2533d972accfd333e76af6dfeb2585760cdd4e933#0 \
     --threshold-numerator 2 \
     --threshold-denominator 3 \
     --spo-signing-key 8067e0e28bbf8bf05020706074e4737b3e88554752b01b53097ebf6e267c6291 \
     --sidechain-signing-key 0046c0b086d250036896c0253e9291abc50d21bb9f445a09a8685fc09921d2bf \
     --registration-utxo 4f99bdb9459924e2ea2131b527c3934f0e3c2b630cea929604fd75f66fed9bcb#0
     {
      "spoPublicKey" : "6903d409fadc11d387085a59b53770c3d6fb4c482d54b713a89a430c6987d962",
      "spoSignature" : "d61c7b893db42483fa03e574b81f599a4b27f6b77f2e6d141ead0667d31787110080b254d41def6a8e70d437caba18f3b98ab2aedf230913a356007958f58d0b",
      "sidechainPublicKey" : "023e5054a7c0d33805a845e389794c82cbf1a01f5e16b4f14ff87911bef506f1ed",
      "sidechainSignature" : "e0e87eed4b7ae50d4d0ba66adfd2ef35f5a2e08aa35dd8b75e8da17583787acf36e63a8a1b8ad7656c3919e7f2b682723fffe1fe099d113f65ac4f04596f41b7"
     }
   ```

3. Use outputs as parameters for `sidechain-main-cli register` execution:

   ```
   sidechain-main-cli register \
     --payment-signing-key-file $SIGNING_KEY \
     --genesis-committee-hash-utxo be6c22802e00349c953f53c2533d972accfd333e76af6dfeb2585760cdd4e933#0 \
     --sidechain-id 80 \
     --sidechain-genesis-hash d8063cc6e907f497360ca50238af5c2e2a95a8869a2ce74ab3e75fe6c9dcabd0 \
     --spo-public-key 6903d409fadc11d387085a59b53770c3d6fb4c482d54b713a89a430c6987d962 \
     --sidechain-public-key 023e5054a7c0d33805a845e389794c82cbf1a01f5e16b4f14ff87911bef506f1ed \
     --spo-signature d61c7b893db42483fa03e574b81f599a4b27f6b77f2e6d141ead0667d31787110080b254d41def6a8e70d437caba18f3b98ab2aedf230913a356007958f58d0b \
     --sidechain-signature 7f242762aea794c8e7f013f567235b41f3ed27bd1f26a4a04f85ea2cbdf7f0fe26a37c5d44116019394d38fd8a8ac0cf08f94f11353e3121d0cd73bf187bcbdd \
     --registration-utxo e0e87eed4b7ae50d4d0ba66adfd2ef35f5a2e08aa35dd8b75e8da17583787acf36e63a8a1b8ad7656c3919e7f2b682723fffe1fe099d113f65ac4f04596f41b7#1

   [INFO] 2023-01-18T13:02:13.442Z CommitteeCandidateValidator.register: Submitted committeeCandidate register Tx: (TransactionHash (hexToByteArrayUnsafe "87436af4f7afee41a5eaf75cbdcbacf2e9fd208165ecff0e98cff2dcbb57cf07"))
   [INFO] 2023-01-18T13:02:21.746Z CommitteeCandidateValidator.register: Register Tx submitted successfully!
   {"endpoint":"CommitteeCandidateReg","transactionId":"87436af4f7afee41a5eaf75cbdcbacf2e9fd208165ecff0e98cff2dcbb57cf07"}
   ```

   `$SIGNING_KEY` holds the absolute path to the `payment.skey` of the SPO being registered.

   Now the validator is registered!
