package io.iohk.scevm.rpc.faucet

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all._
import io.iohk.ethereum.crypto
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.domain.{Address, TransactionHash}
import io.iohk.scevm.network.NewTransactionsListener
import io.iohk.scevm.network.TransactionsImport.TransactionsFromRpc
import io.iohk.scevm.rpc.domain.JsonRpcError.LogicError
import io.iohk.scevm.rpc.domain.TransactionRequest
import io.iohk.scevm.rpc.{NonceService, ServiceResponse}
import io.iohk.scevm.wallet.Wallet

class FaucetController(
    faucetConfig: FaucetConfig,
    nonceService: NonceService[IO],
    blockchainConfig: BlockchainConfig,
    transactionsImportService: NewTransactionsListener[IO]
) {

  def sendTransaction(address: Address): ServiceResponse[TransactionHash] =
    faucetConfig match {
      case FaucetConfig.Enabled(faucetPrivateKey, txGasPrice, txGasLimit, txValue) =>
        val pubKey        = ECDSA.PublicKey.fromPrivateKey(faucetPrivateKey)
        val faucetAddress = Address(crypto.kec256(pubKey.bytes))
        val wallet        = Wallet(faucetAddress, faucetPrivateKey)
        sendTransaction(
          TransactionRequest(
            from = faucetAddress,
            to = Some(address),
            value = Some(txValue),
            gas = Some(txGasLimit),
            gasPrice = Some(txGasPrice)
          ),
          wallet
        ).map(_.asRight)
      case FaucetConfig.Disabled => IO.pure(Left(LogicError("Faucet is disabled")))
    }

  private def sendTransaction(request: TransactionRequest, wallet: Wallet): IO[TransactionHash] =
    for {
      nextTxNonce <- nonceService.getNextNonce(wallet.address)
      tx           = request.toTransaction(nextTxNonce)
      stx         <- IO.delay(wallet.signTx(tx, Some(blockchainConfig.chainId)))
      _           <- transactionsImportService.importTransaction(TransactionsFromRpc(NonEmptyList.one(stx)))
    } yield stx.hash
}
