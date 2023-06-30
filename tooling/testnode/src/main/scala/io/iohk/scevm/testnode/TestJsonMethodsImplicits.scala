package io.iohk.scevm.testnode

import cats.implicits._
import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.{Nonce, ObftGenesisAccount, UInt256}
import io.iohk.scevm.rpc.domain.JsonRpcError
import io.iohk.scevm.rpc.domain.JsonRpcError.InvalidParams
import io.iohk.scevm.rpc.serialization.JsonMethodsImplicits
import io.iohk.scevm.testnode.TestJRpcController._
import org.json4s.JsonAST.{JObject, JString, JValue}

import scala.util.Try

object TestJsonMethodsImplicits extends JsonMethodsImplicits {

  def extractChainParam(json: JObject): Either[JsonRpcError, ChainParams] =
    for {
      genesis          <- extractGenesis(json \ "genesis")
      blockchainParams <- extractBlockchainParams(json \ "params")
      sealEngine <- Try((json \ "sealEngine").extract[String]).toEither
                      .leftMap(_ => InvalidParams())
                      .flatMap(extractSealEngine)
      accounts <- extractAccounts(json \ "accounts")
    } yield ChainParams(genesis, blockchainParams, sealEngine, accounts)

  private def extractAccounts(accountsJson: JValue): Either[JsonRpcError, Map[ByteString, ObftGenesisAccount]] =
    for {
      mapping <- Try(accountsJson.extract[JObject]).toEither.leftMap(e => InvalidParams(e.toString))
      accounts <- mapping.obj.traverse { case (key, value) =>
                    for {
                      address <- extractBytes(key)
                      account <- extractAccount(value)
                    } yield address -> account
                  }
    } yield accounts.toMap

  private def extractAccount(accountJson: JValue): Either[JsonRpcError, ObftGenesisAccount] =
    for {
      storageObject <-
        Try((accountJson \ "storage").extract[JObject]).toEither.leftMap(e => InvalidParams(e.toString))
      storage <- storageObject.obj.traverse {
                   case (key, JString(value)) =>
                     Try(UInt256(decode(key)) -> UInt256(decode(value))).toEither.leftMap(e =>
                       InvalidParams(e.toString)
                     )
                   case _ => Left(InvalidParams())
                 }
      balance  = UInt256(decode((accountJson \ "balance").extract[String]))
      code     = decode((accountJson \ "code").extract[String])
      codeOpt  = if (code.isEmpty) None else Some(ByteString(code))
      nonce    = decode((accountJson \ "nonce").extract[String])
      nonceOpt = if (nonce.isEmpty || Nonce(nonce) == Nonce.Zero) None else Some(Nonce(nonce))
    } yield ObftGenesisAccount(
      balance,
      codeOpt,
      nonceOpt,
      storage.toMap
    )

  private def extractSealEngine(str: String): Either[JsonRpcError, SealEngineType] = str match {
    case "NoReward" => Right(SealEngineType.NoReward)
    case "NoProof"  => Right(SealEngineType.NoProof)
    case other      => Left(InvalidParams(s"unknown seal engine $other"))
  }

  private def extractGenesis(genesisJson: JValue): Either[JsonRpcError, GenesisParams] =
    for {
      author     <- extractBytes((genesisJson \ "author").extract[String])
      difficulty  = (genesisJson \ "difficulty").extractOrElse("0")
      extraData  <- extractBytes((genesisJson \ "extraData").extract[String])
      gasLimit   <- extractQuantity(genesisJson \ "gasLimit")
      parentHash <- extractBytes((genesisJson \ "parentHash").extractOrElse(""))
      timestamp  <- extractQuantity(genesisJson \ "timestamp")
      nonce      <- extractBytes((genesisJson \ "nonce").extract[String])
      mixHash    <- extractBytes((genesisJson \ "mixHash").extract[String])
    } yield GenesisParams(author, difficulty, extraData, gasLimit, parentHash, timestamp, nonce, mixHash)

  private def extractBlockchainParams(blockchainParamsJson: JValue): Either[JsonRpcError, BlockchainParams] =
    for {
      eIP150ForkBlock         <- optionalBlockNumber(blockchainParamsJson \ "EIP150ForkBlock")
      eIP158ForkBlock         <- optionalBlockNumber(blockchainParamsJson \ "EIP158ForkBlock")
      accountStartNonce       <- optionalQuantity(blockchainParamsJson \ "accountStartNonce")
      allowFutureBlocks        = (blockchainParamsJson \ "allowFutureBlocks").extractOrElse(true)
      blockReward             <- optionalQuantity(blockchainParamsJson \ "blockReward")
      byzantiumForkBlock      <- optionalBlockNumber(blockchainParamsJson \ "byzantiumForkBlock")
      homesteadForkBlock      <- optionalBlockNumber(blockchainParamsJson \ "homesteadForkBlock")
      constantinopleForkBlock <- optionalBlockNumber(blockchainParamsJson \ "constantinopleForkBlock")
      istanbulForkBlock       <- optionalBlockNumber(blockchainParamsJson \ "istanbulForkBlock")
      berlinForkBlock         <- optionalBlockNumber(blockchainParamsJson \ "berlinForkBlock")
      londonForkBlock         <- optionalBlockNumber(blockchainParamsJson \ "londonForkBlock")
    } yield BlockchainParams(
      EIP150ForkBlock = eIP150ForkBlock,
      EIP158ForkBlock = eIP158ForkBlock,
      accountStartNonce = accountStartNonce.map(Nonce(_)).getOrElse(Nonce.Zero),
      allowFutureBlocks = allowFutureBlocks,
      blockReward = blockReward.getOrElse(0),
      byzantiumForkBlock = byzantiumForkBlock,
      homesteadForkBlock = homesteadForkBlock,
      maximumExtraDataSize = 0,
      constantinopleForkBlock = constantinopleForkBlock,
      istanbulForkBlock = istanbulForkBlock,
      berlinForkBlock = berlinForkBlock,
      londonForkBlock = londonForkBlock
    )
}
