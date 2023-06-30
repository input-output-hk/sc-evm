package io.iohk.scevm.rpc.controllers

import cats.Show
import cats.data.OptionT
import cats.effect.IO
import cats.syntax.all._
import io.estatico.newtype.macros.newtype
import io.iohk.bytes.ByteString
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.consensus.WorldStateBuilder
import io.iohk.scevm.db.storage.StateStorage
import io.iohk.scevm.domain._
import io.iohk.scevm.mpt.EthereumUInt256Mpt
import io.iohk.scevm.mpt.MerklePatriciaTrie.{MPTException, MissingNodeException}
import io.iohk.scevm.rpc.controllers.BlockResolver.ResolvedBlock
import io.iohk.scevm.rpc.controllers.EthWorldStateController._
import io.iohk.scevm.rpc.domain.JsonRpcError
import io.iohk.scevm.rpc.{AccountService, ServiceResponse}
import io.iohk.scevm.serialization.Newtype
import io.iohk.scevm.utils.Logger

import scala.language.implicitConversions

object EthWorldStateController {
  final case class GetBalanceRequest(address: Address, block: EthBlockParam)

  final case class GetStorageAtRequest(address: Address, position: BigInt, block: EthBlockParam)

  final case class GetTransactionCountRequest(address: Address, block: EthBlockParam)

  final case class GetCodeRequest(address: Address, block: EthBlockParam)

  @newtype final case class SmartContractCode(value: ByteString)

  object SmartContractCode {
    implicit val show: Show[SmartContractCode] = Show.show(t => show"SmartContractCode(${t.value})")

    implicit val valueClass: Newtype[SmartContractCode, ByteString] =
      Newtype[SmartContractCode, ByteString](SmartContractCode.apply, _.value)

    // Manifest for @newtype classes - required for now, as it's required by json4s (which is used by Armadillo)
    implicit val manifest: Manifest[SmartContractCode] = new Manifest[SmartContractCode] {
      override def runtimeClass: Class[_] = SmartContractCode.getClass
    }
  }

  @newtype final case class StorageData(value: ByteString)

  object StorageData {
    implicit val show: Show[StorageData] = Show.show(t => show"${t.value}")

    implicit val valueClass: Newtype[StorageData, ByteString] =
      Newtype[StorageData, ByteString](StorageData.apply, _.value)

    // Manifest for @newtype classes - required for now, as it's required by json4s (which is used by Armadillo)
    implicit val manifest: Manifest[StorageData] = new Manifest[StorageData] {
      override def runtimeClass: Class[_] = StorageData.getClass
    }
  }
}

class EthWorldStateController(
    resolveBlock: BlockResolver[IO],
    stateStorage: StateStorage,
    blockchainConfig: BlockchainConfig,
    accountProvider: AccountService[IO],
    worldStateBuilder: WorldStateBuilder[IO]
) extends Logger {

  def getBalance(req: GetBalanceRequest): ServiceResponse[BigInt] =
    withAccount(req.address, req.block) { account =>
      account.balance
    }

  def getCode(req: GetCodeRequest): ServiceResponse[Option[SmartContractCode]] =
    (for {
      ResolvedBlock(block, _) <- OptionT(resolveBlock.resolveBlock(req.block))
      response <-
        OptionT.liftF(
          worldStateBuilder
            .getWorldStateForBlock(block.header.stateRoot, BlockContext.from(block.header))
            .use(w => IO.delay(w.getCode(req.address)))
        )
    } yield SmartContractCode(response)).value.map(_.asRight)

  def getStorageAt(req: GetStorageAtRequest): ServiceResponse[StorageData] =
    withAccount(req.address, req.block) { account =>
      StorageData(getAccountStorageAt(account.storageRoot, req.position))
    }

  def getTransactionCount(req: GetTransactionCountRequest): ServiceResponse[Nonce] =
    withAccount(req.address, req.block) { account =>
      account.nonce
    }

  private def withAccount[T](address: Address, blockParam: EthBlockParam)(
      makeResponse: Account => T
  ): ServiceResponse[T] =
    (for {
      maybeBlock <- resolveBlock.resolveBlock(blockParam)
      account <- maybeBlock match {
                   case Some(ResolvedBlock(block, _)) =>
                     accountProvider.getAccount(block.header, address)
                   case None => Account.empty(blockchainConfig.genesisData.accountStartNonce).pure[IO]
                 }
    } yield Right(makeResponse(account)))
      .handleErrorWith {
        case e: MissingNodeException =>
          log.error("MissingNodeException with message [{}]", e.message)
          IO.pure(Left(JsonRpcError.NodeNotFound))
        case e: MPTException =>
          log.error("MPTException with message [{}]", e.message)
          IO.pure(Left(JsonRpcError.NodeNotFound))
        case e => IO.raiseError(e)
      }

  private def getAccountStorageAt(
      rootHash: ByteString,
      position: BigInt
  ): ByteString = {
    val storage = stateStorage.archivingStorage
    val mpt     = EthereumUInt256Mpt.storageMpt(rootHash, storage)

    val bigIntValue    = mpt.get(position).getOrElse(BigInt(0))
    val byteArrayValue = bigIntValue.toByteArray

    // BigInt.toArray actually might return one more byte than necessary because it adds a sign bit, which in our case
    // will always be 0. This would add unwanted 0 bytes and might cause the value to be 33 byte long while an EVM
    // word is 32 byte long.
    if (bigIntValue != 0)
      ByteString(byteArrayValue.dropWhile(_ == 0))
    else
      ByteString(byteArrayValue)
  }
}
