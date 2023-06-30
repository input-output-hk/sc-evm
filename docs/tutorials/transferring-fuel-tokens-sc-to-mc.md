# Steps to transfer tokens from sidechain to main chain:

This document describes a way to make a transfer from SC EVM to Cardano.

In this document, we use `$network` variable to denote the network magic argument of the
specific Cardano network we are using.

For instance for a test network with magic 7:

```shell
  # Set network variable, e.g. network=mainnet
  network=--testnet-magic=7
```

1. Create a sidechain account (e.g. using metamask)
2. Fund the account
3. Create a Cardano address:

Prerequisites:

- A Cardano node must be running and connected to the Cardano network
- cardano-cli must be configured with local Cardano node

  ```shell
  cardano-cli address key-gen \
      --verification-key-file payment.vkey \
      --signing-key-file payment.skey

  cardano-cli address build \
      --payment-verification-key-file payment.vkey \
      --out-file payment.addr \
      $network

  export MC_ADDR=$(cat payment.addr)
  ```

  For more information: (https://developers.cardano.org/docs/stake-pool-course/handbook/keys-addresses/)

4. Fund address from whatever method is used on your specific Cardano network.

5. Lock the amount of SC_TOKEN you want to transfer to the main chain:
   Your destination address should be first converted to bech32 format. The executable can be found here: https://github.com/input-output-hk/bech32
   The transaction amount in SC_TOKEN has to be a multiple of 10^9, as that is the conversion rate between the two chains. 10^9 wei on the sidechain will be 1 SC_TOKEN on the main chain. In order to keep the private key secure, we will put it into a file and pass its path to the command line instead of passing the key explicitely.

   ```commandline
   echo $PRIV_SC_KEY > private_key.file
   TX_LOCK_HASH = sc-evm-cli lock-sc-token --sc-evm-url <sc-evm-node-url> --private-key-file ./private_key.file --recipient $(bech32 <<< $MC_ADDR) --amount $SC_TOKEN_AMOUNT
   ```

   You can verify that the transaction is successful with the RPC endpoint eth_getTransactionReceipt($RAW_TX). You can keep requesting this until a receipt is returned or the transaction times out:

   ```shell
   curl -L -X POST -H 'Content-type:application/json' -d '{
     "jsonrpc": "2.0",
     "method": "eth_getTransactionReceipt",
     "params": [$TX_LOCK_HASH],
     "id": 1
     }' <sc-evm-node-url> | jq
   ```

6. Get the sidechain status:

   ```shell
   curl -L -X POST -H 'Content-type:application/json' -d '{
      "jsonrpc": "2.0",
      "method": "sidechain_getStatus",
      "params": [],
      "id": 1
      }' <sc-evm-node-url> | jq -r '.result.sidechain | "\(.epoch) \(.epochPhase)"'
   ```

   From this we know on which epoch and what phase our lock() transaction happened.
   If it happened during epoch `N` on epochPhase `regular` we will be able to obtain the Merkle proof on epoch `N` since epochPhase changes to `handover`.
   If it happened during epoch `N` on epochPhase `closedTransactionBatch` or `handover` we will be able to obtain the Merkle proof on epoch `N+1` since epochPhase changes to `handover`.

7. Get the transaction index for the epoch our lock transaction will be processed:

   ```shell
   curl -L -X POST -H 'Content-type:application/json' -d '{
     "jsonrpc": "2.0",
     "method": "sidechain_getOutgoingTransactions",
     "params": [<N or N+1>],
     "id": 1
     }' <sc-evm-node-url> | jq

   ```

   Output will look similar to:

   ```json
   {
     "proof": {
       "bytes": "0xd8799fd8799f0002581d606e9d4f6a3f900f7b510b27f29d8e79c550686ce093946b23b3d1828ed87a80ff80ff",
       "info": {
         "transaction": {
           "value": "0x2",
           "recipient": "0x606e9d4f6a3f900f7b510b27f29d8e79c550686ce093946b23b3d1828e",
           "txIndex": 0
         },
         "merkleRootHash": "0x96e0bffb3786607057ddffa5bc3a92c5b6669109c12b5222da374cd99a815b4e"
       }
     }
   }
   ```

   We need to obtain the `txIndex` of the transaction we made, matching the recipient and amount we used to lock tokens.

8. Wait until the epochPhase changes to `handover` of epoch N or N+1 and obtain the Merkle proof for your lock action:

   ```shell
   curl -L -X POST -H 'Content-type:application/json' -d '{
     "jsonrpc": "2.0",
     "method": "sidechain_getOutgoingTxMerkleProof",
     "params": [<N or N+1>, <txIndex>],
     "id": 1
     }' <sc-evm-node-url> | jq
   ```

   From the return you need to obtain the `proof/bytes` and remove the `0x` that is prepended. We shall call that string `merkleProof`.

9. Wait until the epoch changes and the committee handover has happened. The committee handover happens automatically on the testnets by a service (relay) that tries every minute to find epoch signatures, and succeeds after a new epoch has started and the signatures are obtained. On mainnet the committee handover will be done by anyone that wants, where there will be an incentive to do so. A good way to verify is by waiting until you observe your transaction in the list of signatures to upload:

   ```shell
   curl -L -X POST -H 'Content-type:application/json' -d '{
       "jsonrpc": "2.0",
       "method": "sidechain_getSignaturesToUpload",
       "params": [<limit>],
       "id": 1
       }' <sc-evm-node-url> | jq
   ```

   Where limit = min(limit, number of epochs that are not yet relayed). The default value is 100, but you can set it to any other number. In a normal case, when the handover happens at every epoch, it should only show 0 or 1 epochs (the last one).
   Once you identify that there are no roothashes pending in the list under the epoch that your lock was processed (N or N+1) you can claim your tokens.
   By now the epoch will be N+1 or N+2, depending on the phase you performed the lock operation.

10. Claim the tokens by submitting a transaction on the main chain with the `merkleProof` you obtained:
    You will need to install CTL, following the instructions the repo's tools/README.md file.
    Then you can invoke the **sc-evm-cli** script to claim your tokens:

    ```shell
    sc-evm-cli claim-sc-token --sc-evm-url <sc-evm-node-url> --signing-key-file /PATH/TO/payment.skey --combined-proof <merkleProof without 0x>
    ```

    expected output is:

    ```json
    {
      "endpoint": "ClaimAct",
      "transactionId": "1334b3dab421911af68b9393e5cc4756c46c9ab1ac567a57450597e174351a48"
    }
    ```

    You can verify the amount you locked is in your account:

    ```shell
    cardano-cli query utxo $network --address $MC_ADDR
    ```
