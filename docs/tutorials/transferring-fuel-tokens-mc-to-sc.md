# Steps to transfer tokens from main chain to sidechain:

1.  Create wallets and obtain SC_TOKEN tokens by transferring from sidechain: [Steps to transfer tokens from sidechain to main chain](transferring-fuel-tokens-sc-to-mc.md)
2.  Move the number of tokens you want to transfer. (https://docs.cardano.org/native-tokens/getting-started). You need to know the policy ID, which can be obtained by querying your address.
    `cardano-cli query utxo --address addr_test1wqpr… $network`
    The result will be something like this:

        ```
                                       TxHash                                 TxIx        Amount
        --------------------------------------------------------------------------------------
        2daf5cd09137c63f8adbaad297636cbe1a6142ab298485e0cac47b591e5db74b     0        613648238 lovelace + TxOutDatumNone
        6139f661d7d30bb51b070d5338ab4d39c1651921ef0472cd6af8ab2e9c3380f1     1        5000000 lovelace + TxOutDatumNone
        68c2b54e91e52643b3b4e1f3cce1d6651129bb0840fcb84a87086ad5358626e3     1        3500000 lovelace + 99999998622 6f1e7de82f60f7bb4edde75b8b1cefb12a12d88e20e318947c1c130b.4655454c + TxOutDatumNone
        ```

In this case the policy ID of the native token is 6f1e7de82f60f7bb4edde75b8b1cefb12a12d88e20e318947c1c130b.4655454c and the amount is 99999998622.

3. Send tokens using the [sidechain  cli](../../tooling/README.md#cli):

   ```shell
   sc-evm-cli burn-sc-token --signing-key-file payment.skey \
     --recipient 0xae3dffee97f92db0201d11cb8877c89738353bce \
     --sc-evm-url <sc-evm-node-url> \
     --amount 10
   ```

   In a few minutes the tokens should appear on the sidechain:

   ```shell
   curl -L -X POST http://127.0.0.1:8546 -H 'Content-type:application/json' -d '{
       "jsonrpc": "2.0",
       "method": "eth_getBalance",
       "params": ["0xae3dffee97f92db0201d11cb8877c89738353bce", "latest"],
       "id": 1}'
   ```
