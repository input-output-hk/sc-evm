package io.iohk.scevm.rpc.armadillo

import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.ECDSASignature
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.{Address, BlockHash, BlockNumber, BlockOffset, Gas, Nonce, Slot, Token, TransactionHash}
import io.iohk.scevm.rpc.AlwaysNull
import io.iohk.scevm.rpc.controllers.DebugController.{TraceParameters, TraceTransactionResponse}
import io.iohk.scevm.rpc.controllers.EthTransactionController._
import io.iohk.scevm.rpc.controllers.EthVMController.{CallResponse, CallTransaction}
import io.iohk.scevm.rpc.controllers.EthWorldStateController.{SmartContractCode, StorageData}
import io.iohk.scevm.rpc.controllers.InspectController.GetChainResponse
import io.iohk.scevm.rpc.controllers.NetController.{ChainMode, GetNetworkInfoResponse, NetVersion}
import io.iohk.scevm.rpc.controllers.PersonalController.{Passphrase, RawPrivateKey}
import io.iohk.scevm.rpc.controllers.SanityController._
import io.iohk.scevm.rpc.controllers.TxPoolController.{GetContentResponse, RpcPooledTransaction}
import io.iohk.scevm.rpc.controllers.Web3Controller.ClientVersion
import io.iohk.scevm.rpc.controllers._
import io.iohk.scevm.rpc.domain.RpcBlockResponse.EmptyHash
import io.iohk.scevm.rpc.domain._
import io.iohk.scevm.utils.SystemTime.TimestampSeconds

import java.time.Duration
import scala.concurrent.duration.DurationInt

final case class Documentation[T](description: String, example: T)

object Documentation {
  implicit def toDocumentationOfOption[T](implicit documentation: Documentation[T]): Documentation[Option[T]] =
    Documentation(documentation.description, Some(documentation.example))
}

// scalastyle:off line.size.limit
// scalastyle:off magic.number
trait DocumentationImplicits {

  implicit val addressDocumentation: Documentation[Address] = Documentation(
    "Hexadecimal string of 20 bytes representing an address",
    Address(Hex.decodeUnsafe("CC95F2A1011728FC8B861B3C9FEEFBB4E7449B98"))
  )

  implicit val bigIntDocumentation: Documentation[BigInt] = Documentation("An integer", BigInt(15))

  implicit val blockHashDocumentation: Documentation[BlockHash] = Documentation(
    "Hexadecimal string of 32 bytes representing the hash of a block",
    BlockHash(Hex.decodeUnsafe("a1c2cdab830ca8351ad58a4ea08d68e899eea1a1ad57adb905089bd705d41dc0"))
  )

  implicit val blockNumberDocumentation: Documentation[BlockNumber] = Documentation(
    "Hexadecimal string representing the number of a block",
    BlockNumber(5)
  )

  implicit val blockOffsetDocumentation: Documentation[BlockOffset] = Documentation(
    "Hexadecimal string representing a difference between two block's number",
    BlockOffset(BigInt(42))
  )

  implicit val booleanDocumentation: Documentation[Boolean] = Documentation("A boolean", true)

  implicit val byteDocumentation: Documentation[Byte] = Documentation("A byte", 0x78)

  implicit val byteStringDocumentation: Documentation[ByteString] = Documentation(
    "Hexadecimal string",
    Hex.decodeUnsafe("0123456789ABCDEF")
  )

  implicit val callResponseDocumentation: Documentation[CallResponse] = Documentation(
    "Hexadecimal string representing the value of the executed contract",
    CallResponse(Hex.decodeUnsafe("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))
  )

  implicit val callTransactionDocumentation: Documentation[CallTransaction] = Documentation(
    """Object with the following fields:
      |- `value`: Number of tokens transferred by the transaction
      |- `input`: Hexadecimal string representing the payload of the transaction
      |- `from` (optional): Hexadecimal string of 20 bytes representing the address the transaction is sent from
      |- `to` (optional): Hexadecimal string of 20 bytes representing the address of the recipient
      |- `gas` (optional): gas provided for the execution of the transaction
      |- `gasPrice` (optional): Hexadecimal string representing the gas price""".stripMargin,
    CallTransaction(
      from = Some(Hex.decodeUnsafe("CC95F2A1011728FC8B861B3C9FEEFBB4E7449B98")),
      to = Some(Address(Hex.decodeUnsafe("5e05fe3739f967995292ca32442444f1aa27d7e5"))),
      gas = Some(Gas(0x76c0)),
      gasPrice = Some(Token(BigInt(Hex.decodeAsArrayUnsafe("9184e72a000")))),
      value = Token(123),
      input = Hex.decodeUnsafe("")
    )
  )

  implicit val checkChainConsistencyRequestDocumentation: Documentation[CheckChainConsistencyRequest] = Documentation(
    """Object that contains the following fields:
      |- `from` (optional): Hexadecimal string representing the number of a block
      |- `to` (optional): Hexadecimal string representing the number of a second block (must be greater than `from`)""".stripMargin,
    CheckChainConsistencyRequest(
      Some(BlockNumber(42)),
      Some(BlockNumber(58))
    )
  )

  implicit val checkChainConsistencyResponseDocumentation: Documentation[CheckChainConsistencyResponse] = Documentation(
    """Object that contains the following fields:
      |- `from`: Hexadecimal string representing the number of the first block
      |- `to`: Hexadecimal string representing the number of the second block
      |- `errors`: List of errors that happened during the validations""".stripMargin,
    CheckChainConsistencyResponse(
      BlockNumber(42),
      BlockNumber(58),
      List(MissingStableNumberMappingEntry(BlockNumber(50)), MissingStableNumberMappingEntry(BlockNumber(51)))
    )
  )

  implicit val clientVersionDocumentation: Documentation[ClientVersion] = Documentation(
    "The current client version",
    ClientVersion("v0.0.1-SNAPSHOT")
  )

  implicit val durationDocumentation: Documentation[Duration] = Documentation(
    "String representing a duration",
    Duration.ofSeconds(20)
  )

  implicit val eCDSASignatureDocumentation: Documentation[ECDSASignature] = Documentation(
    "Hexadecimal string representing an ECDSA signature",
    ECDSASignature.fromHexUnsafe(
      "9bf40cc7d2504603e80bf32c2aaa55d2f28db6f454cab4b2f86bebce332da77f54419c5ec1d81e4e2ca30f380517c7c22c0b2935130451baf3655a397c8260571b"
    )
  )

  implicit val ethBlockParamDocumentation: Documentation[EthBlockParam] = Documentation(
    "Hexadecimal string representing a block number, or one of the following strings: `earliest`, `latest` or `pending`",
    BlockParam.ByNumber(BlockNumber(85))
  )

  implicit val gasDocumentation: Documentation[Gas] = Documentation(
    "Hexadecimal string representing the gas price",
    Gas(67)
  )

  implicit val getChainResponseDocumentation: Documentation[GetChainResponse] = Documentation(
    """Object that contains the following fields:
      |- `nodes`: List of objects with the following fields:
      |  - `hash`: Hexadecimal string of 32 bytes representing the hash of a block
      |  - blockNumber: Hexadecimal string representing the number of a block
      |  - slot: Hexadecimal string representing the number of a slot
      |  - signer: The public side of the key used to sign the block
      |- `links`: List of objects with the following fields:
      |  - parent: Hexadecimal string of 32 bytes representing the hash of the parent block
      |  - child: Hexadecimal string of 32 bytes representing the hash of the child block
      |- `best`: Hexadecimal string of 32 bytes representing the hash of the current best block
      |- `stable`: Hexadecimal string of 32 bytes representing the hash of the current stable block""".stripMargin,
    GetChainResponse(
      Seq(
        InspectController.Block(
          BlockHash(Hex.decodeUnsafe("a1c2cdab830ca8351ad58a4ea08d68e899eea1a1ad57adb905089bd705d41dc0")),
          BlockNumber(5),
          Slot(5),
          ByteString(
            Hex.decodeAsArrayUnsafe(
              "fe8d1eb1bcb3432b1db5833ff5f2226d9cb5e65cee430558c18ed3a3c86ce1af07b158f244cd0de2134ac7c1d371cffbfae4db40801a2572e531c573cda9b5b4"
            )
          )
        ),
        InspectController.Block(
          BlockHash(Hex.decodeUnsafe("c2a32d1f055aa884f2bfc4a6b47fa3c02635a4dcb9720904df6b422a07c6c618")),
          BlockNumber(4),
          Slot(4),
          ByteString(
            Hex.decodeAsArrayUnsafe(
              "fe8d1eb1bcb3432b1db5833ff5f2226d9cb5e65cee430558c18ed3a3c86ce1af07b158f244cd0de2134ac7c1d371cffbfae4db40801a2572e531c573cda9b5b4"
            )
          )
        ),
        InspectController.Block(
          BlockHash(Hex.decodeUnsafe("877851c14b93b4fef72a5783721e7d90099011fa0bf78424f30ef68e1a13ec6d")),
          BlockNumber(4),
          Slot(4),
          ByteString(
            Hex.decodeAsArrayUnsafe(
              "fe8d1eb1bcb3432b1db5833ff5f2226d9cb5e65cee430558c18ed3a3c86ce1af07b158f244cd0de2134ac7c1d371cffbfae4db40801a2572e531c573cda9b5b4"
            )
          )
        ),
        InspectController.Block(
          BlockHash(Hex.decodeUnsafe("2a6377291ad539baf0e485223616bc61591c1d41991726676c76a13b7cf44a2c")),
          BlockNumber(3),
          Slot(3),
          ByteString(
            Hex.decodeAsArrayUnsafe(
              "fe8d1eb1bcb3432b1db5833ff5f2226d9cb5e65cee430558c18ed3a3c86ce1af07b158f244cd0de2134ac7c1d371cffbfae4db40801a2572e531c573cda9b5b4"
            )
          )
        )
      ),
      Seq(
        InspectController.Link(
          BlockHash(Hex.decodeUnsafe("c2a32d1f055aa884f2bfc4a6b47fa3c02635a4dcb9720904df6b422a07c6c618")),
          BlockHash(Hex.decodeUnsafe("a1c2cdab830ca8351ad58a4ea08d68e899eea1a1ad57adb905089bd705d41dc0"))
        ),
        InspectController.Link(
          BlockHash(Hex.decodeUnsafe("2a6377291ad539baf0e485223616bc61591c1d41991726676c76a13b7cf44a2c")),
          BlockHash(Hex.decodeUnsafe("c2a32d1f055aa884f2bfc4a6b47fa3c02635a4dcb9720904df6b422a07c6c618"))
        ),
        InspectController.Link(
          BlockHash(Hex.decodeUnsafe("2a6377291ad539baf0e485223616bc61591c1d41991726676c76a13b7cf44a2c")),
          BlockHash(Hex.decodeUnsafe("877851c14b93b4fef72a5783721e7d90099011fa0bf78424f30ef68e1a13ec6d"))
        ),
        InspectController.Link(
          BlockHash(Hex.decodeUnsafe("a82a9805f714a31173494e544ee825892759ba0ae29815d3f0924a2a15b7eba7")),
          BlockHash(Hex.decodeUnsafe("2a6377291ad539baf0e485223616bc61591c1d41991726676c76a13b7cf44a2c"))
        )
      ),
      Seq(
        BlockHash(Hex.decodeUnsafe("a82a9805f714a31173494e544ee825892759ba0ae29815d3f0924a2a15b7eba7")),
        BlockHash(Hex.decodeUnsafe("2a6377291ad539baf0e485223616bc61591c1d41991726676c76a13b7cf44a2c")),
        BlockHash(Hex.decodeUnsafe("c2a32d1f055aa884f2bfc4a6b47fa3c02635a4dcb9720904df6b422a07c6c618")),
        BlockHash(Hex.decodeUnsafe("a1c2cdab830ca8351ad58a4ea08d68e899eea1a1ad57adb905089bd705d41dc0"))
      ),
      BlockHash(Hex.decodeUnsafe("2a6377291ad539baf0e485223616bc61591c1d41991726676c76a13b7cf44a2c"))
    )
  )

  implicit val getContentResponseDocumentation: Documentation[GetContentResponse] = Documentation(
    """Object that contains two fields, `pending` and `queued`, which represent the list of scheduled transactions.
      |- `pending`: Pending transactions are transactions that can be added to the next block, based on account nonce.
      |- `queued`: Queued transactions are transactions that can not be added to next block, but they will eventually become pending.""".stripMargin,
    GetContentResponse(
      Map(
        Address(Hex.decodeUnsafe("12de31509cdc1c3e9b8987c15b1124dee71455a2")) -> Map(
          Nonce(438551) -> RpcPooledTransaction(
            accessList = None,
            blockHash = BlockHash(Hex.decodeUnsafe("0000000000000000000000000000000000000000000000000000000000000000")),
            blockNumber = AlwaysNull,
            from = Address(Hex.decodeUnsafe("12de31509cdc1c3e9b8987c15b1124dee71455a2")),
            gas = 0x15f90,
            gasPrice = Some(0x0),
            hash = Hex.decodeUnsafe("7cdd16f9819e307814dc7a499833e9646be860fd194344261760b32835b30f6b"),
            input = Hex.decodeUnsafe(""),
            maxFeePerGas = None,
            maxPriorityFeePerGas = None,
            nonce = Nonce(0x0),
            r = BigInt(Hex.decodeAsArrayUnsafe("24d613af676839284e0892ad12bb144ae70782d7cf35975bfceda7f31453c3dc")),
            s = BigInt(Hex.decodeAsArrayUnsafe("1b4563906b7e3c466aa75a5a2d99a3bd6beb83015f9d2c646f4b9a771582bf49")),
            to = Some(Hex.decodeUnsafe("7729bdd1dfe815998a66e75b4cd40834eb4ecd7b")),
            transactionIndex = AlwaysNull,
            `type` = 0x00,
            v = Some(0xc2),
            value = Token(BigInt(Hex.decodeAsArrayUnsafe("de0b6b3a7640000")))
          )
        )
      ),
      Map.empty
    )
  )

  implicit val getLogsRequestDocumentation: Documentation[GetLogsRequest] = Documentation(
    """Object that contains the following fields:
      |- `address`: List of addresses
      |- `topics`: List of hexadecimal values. More information here: https://ethereum.org/en/developers/docs/apis/json-rpc/#eth_newfilter
      |- `fromBlock`: Hexadecimal string representing a block number, or one of the following strings: `earliest`, `latest` or `pending`
      |- `toBlock`: Hexadecimal string representing a block number, or one of the following strings: `earliest`, `latest` or `pending`
      |- `blockHash`: Hexadecimal string of 32 bytes representing the hash of a block.
      |**Note**: `fromBlock` and `toBlock` must be set together. If `blockHash` is defined, then `fromBlock` and `toBlock` must not be defined.""".stripMargin,
    GetLogsRequestByRange(
      BlockParam.Earliest,
      BlockParam.ByNumber(BlockNumber(10)),
      Seq(Address(Hex.decodeUnsafe("5e05fe3739f967995292ca32442444f1aa27d7e5"))),
      Seq(AnythingTopicSearch)
    )
  )

  implicit val getNetworkInfoResponseDocumentation: Documentation[GetNetworkInfoResponse] = Documentation(
    """Object that contains the following fields:
      |- `chainId`: Hexadecimal string representing the chain ID
      |- `networkId`: Integer representing the network ID
      |- `stabilityParameter`: The number of blocks that are considered as unstable, i.e. number of blocks between the current stable block (excluded) and the current best block (included)
      |- `slotDuration`: The duration of a slot
      |- `chainMode`: `Standalone` or `Sidechain`""".stripMargin,
    GetNetworkInfoResponse(
      ChainId.unsafeFrom(78),
      78,
      16,
      3.seconds,
      ChainMode.Sidechain
    )
  )

  implicit val intDocumentation: Documentation[Int] = Documentation("A number", 15)

  implicit val listOfAddressDocumentation: Documentation[List[Address]] = Documentation(
    "List of hexadecimal strings of 20 bytes representing an address",
    List(
      Address(Hex.decodeUnsafe("CC95F2A1011728FC8B861B3C9FEEFBB4E7449B98")),
      Address(Hex.decodeUnsafe("5e05fe3739f967995292ca32442444f1aa27d7e5"))
    )
  )

  implicit val extendedBlockParamDocumentation: Documentation[ExtendedBlockParam] = Documentation(
    "Hexadecimal string representing a block number, or one of the following strings: `earliest`, `latest`, `pending` or `stable`",
    BlockParam.Stable
  )

  implicit val netVersionDocumentation: Documentation[NetVersion] = Documentation(
    "Number representing the network version",
    NetVersion("78")
  )

  implicit val nonceDocumentation: Documentation[Nonce] = Documentation(
    "Hexadecimal string representing a nonce",
    Nonce(3)
  )

  implicit val passphraseDocumentation: Documentation[Passphrase] = Documentation(
    "String representing a passphrase",
    Passphrase("A t0tal1y sEcrEt pa5sw0rd !")
  )

  implicit val rawPrivateKeyDocumentation: Documentation[RawPrivateKey] = Documentation(
    "Hexadecimal string of 32 bytes representing a private key",
    RawPrivateKey(Hex.decodeUnsafe("7a44789ed3cd85861c0bbf9693c7e1de1862dd4396c390147ecf1275099c6e6f"))
  )

  implicit val rpcBlockResponseDocumentation: Documentation[RpcBlockResponse] = Documentation(
    """Object that contains the following fields:
      |- `difficulty`: Always `0x0`
      |- `extraData`: Always `0x0000000000000000000000000000000000000000`
      |- `gasLimit`: Hexadecimal string representing the maximum amount of gas that could be spent
      |- `gasUsed`: Hexadecimal string representing the total amount of gas used
      |- `logsBloom`: Hexadecimal string of 256 bytes representing the bloom filter for the logs of the block
      |- `miner`: Hexadecimal string of 256 bytes representing the bloom filter for the logs of the block
      |- `mixHash`: Hexadecimal string of 20 bytes representing the address of the account to be rewarded for the creation of the block
      |- `number`: Hexadecimal string representing the number of the block
      |- `parentHash`: Hexadecimal string of 32 bytes representing the hash of the parent block
      |- `receiptsRoot`: Hexadecimal string of 32 bytes representing the root of the receipts trie of the block
      |- `sha3Uncles`: Always `0xc5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470`
      |- `size`: Block's size in bytes
      |- `stateRoot`: Hexadecimal string of 32 bytes representing the root of the final state trie of the block
      |- `timestamp`: The unix timestamp when the block was created
      |- `totalDifficulty`: Always `0x0`
      |- `transactions`: A list of transaction hashes or transaction objects (see return type of **eth_getTransactionByHash**)
      |- `transactionsRoot`: Hexadecimal string of 32 bytes representing the root of the transaction trie of the block
      |- `uncles`: Always `[]`
      |- `hash`: Hexadecimal string of 32 bytes representing the hash of the block""".stripMargin,
    RpcBlockResponse(
      baseFeePerGas = None,
      gasLimit = 0x7a1200,
      gasUsed = 0xfb84,
      logsBloom = Hex.decodeUnsafe(
        "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000080000000000000000000080000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000040000000000000000000000000000100000000000000000000000000000000000000000000000000000000000000000000000008000000001000000000000000000000000000000000000000000000000000"
      ),
      miner = Hex.decodeUnsafe("9d40666d7aba4e5bb0d9aeef76c7198a530dbce7"),
      number = BlockNumber(0x6890),
      parentHash = BlockHash(Hex.decodeUnsafe("b0ce7b352729abbcc78ca50c36a827f549b84a5bf18fbc7988e7bf11f0dd5ea0")),
      receiptsRoot = ByteString("60a33133afc8a178bc7eb2be9b8cb3c16308750545abc1745ac563b4884774dc"),
      sha3Uncles = EmptyHash,
      size = 0x3e2,
      stateRoot = Hex.decodeUnsafe("8a479593589f232eb46c13fde19c3efde50f321e25151d3127621a9a5ba74598"),
      timestamp = TimestampSeconds(0x6374beff),
      transactions = Right(
        Seq(
          RpcSigned1559TransactionResponse(
            nonce = Nonce(0x0),
            hash = Hex.decodeUnsafe("c2d5d4589896ff40e4e487191cee9a28aa0f98c7c74a8fa76c387b3965c7a32c"),
            from = Address(Hex.decodeUnsafe("98f04bd668837466530638c65ae695759e9a0b78")),
            to = Some(Hex.decodeUnsafe("254f0feb0e1d3d54b6bed12872b35414761d0750")),
            gas = 0x2dc6c0,
            value = 0x0,
            input = Hex.decodeUnsafe("c367229900000000000000000000000040409dc3530287b0840e8c8d83d169d46eda8075"),
            maxPriorityFeePerGas = 0x3b9aca00,
            maxFeePerGas = 0x77359400,
            accessList = Seq.empty,
            chainId = 0x4f,
            yParity = 0x00,
            r = BigInt(Hex.decodeAsArrayUnsafe("64a909c53d68bb4fe20e0ddd48e9c283daa37b635f95a6e377c1b6d63b594a0f")),
            s = BigInt(Hex.decodeAsArrayUnsafe("64a909c53d68bb4fe20e0ddd48e9c283daa37b635f95a6e377c1b6d63b594a0f")),
            transactionIndex = 0x0,
            blockHash = Hex.decodeUnsafe("80cc293ebaab7bc41ab5497193299c06fc2452af80e0faf25aa6010a68818e2c"),
            blockNumber = 0x6890
          ),
          RpcSigned1559TransactionResponse(
            nonce = Nonce(0x704),
            hash = Hex.decodeUnsafe("da29fe2e1782271a062de8bb1b44df930a893e5ca5f1fcb06bc7db82dbdbcefe"),
            from = Address(Hex.decodeUnsafe("9d40666d7aba4e5bb0d9aeef76c7198a530dbce7")),
            to = Some(Hex.decodeUnsafe("696f686b2e6d616d626100000000000000000000")),
            gas = 0x1d2e2,
            value = 0x0,
            input = Hex.decodeUnsafe(
              "76cd7cbc0000000000000000000000000000000000000000000000000000000000000020000000000000000000000000000000000000000000000000000000000000004170977297c77299f3973015c3fc518d81b66201945c5e95417e782f27c55c63a44869d9614fb31d86d9ced2739b293ee61c9dfbeaf615120da4efc76b9797a3621c00000000000000000000000000000000000000000000000000000000000000"
            ),
            maxPriorityFeePerGas = 0x0,
            maxFeePerGas = 0x0,
            accessList = Seq.empty,
            chainId = 0x4e,
            yParity = 0x01,
            r = BigInt(Hex.decodeAsArrayUnsafe("e995030a616915beaefa442630eb896af86df6561b242cdef89f1a29c642cb8e")),
            s = BigInt(Hex.decodeAsArrayUnsafe("199f6408dcb4617eef73a3e68749cb9355829c38fe859b24c83d0f94eb18ccca")),
            transactionIndex = 0x1,
            blockHash = Hex.decodeUnsafe("80cc293ebaab7bc41ab5497193299c06fc2452af80e0faf25aa6010a68818e2c"),
            blockNumber = 0x6890
          )
        )
      ),
      transactionsRoot = Hex.decodeUnsafe("b3fac6619fe45eb528e11f95908f0e1adec41292ad64ac7867202cdd5c40e167"),
      uncles = Nil,
      hash = Some(BlockHash(Hex.decodeUnsafe("80cc293ebaab7bc41ab5497193299c06fc2452af80e0faf25aa6010a68818e2c")))
    )
  )

  implicit val rpcFullTransactionResponseDocumentation: Documentation[RpcFullTransactionResponse] = Documentation(
    """Object with the following fields:
      |- `type`: Byte representing the type of the transaction (legacy = 0x00, eip-2930= 0x01, eip-1559 = 0x02)
      |- `blockHash`: Hexadecimal string of 32 bytes representing the hash of a block
      |- `blockNumber`: Hexadecimal string representing the number of a block
      |- `from`: Hexadecimal string of 20 bytes representing the address of the sender
      |- `gas`: Hexadecimal string representing the gas price
      |- `hash`: Hexadecimal string of 32 bytes representing the hash of a transaction
      |- `input`: Hexadecimal string representing the payload of the transaction
      |- `nonce`: Hexadecimal string representing a nonce
      |- `r`: Hexadecimal string representing `r` in the ECDSA signature of the signer
      |- `s`: Hexadecimal string representing `s` in the ECDSA signature of the signer
      |- `transactionIndex`: Index of the transaction in the block
      |- `value`: Hexadecimal string representing the number of transferred tokens
      |- `to` (optional): Hexadecimal string of 20 bytes representing the address of the receiver. Not present for contract creation.
      |
      |Depending on the type of the transaction, additional fields may be present.
      |For legacy transactions:
      |- `gasPrice`: Hexadecimal string representing the gas price of the transaction
      |- `v`: Hexadecimal string representing the recovery ID of the signer's ECDSA signature
      |For EIP-2930 transactions:
      |- `gasPrice`: Hexadecimal string representing the gas price of the transaction
      |- `accessList`: List of objects with the following fields:
      |  - `address`: Hexadecimal string of 20 bytes representing an address that the transaction plans to access
      |  - `storageKeys`: List of storage keys that the transaction plans to access
      |- `chainId`: Hexadecimal string representing the chain ID
      |- `yParity`: The parity of the y-value of a secp256k1 signature
      |For EIP-1559 transactions:
      |- `accessList`: List of objects with the following fields:
      |  - `address`: Hexadecimal string of 20 bytes representing an address that the transaction plans to access
      |  - `storageKeys`: List of storage keys that the transaction plans to access
      |- `chainId`: Hexadecimal string representing the chain ID
      |- `yParity`: The parity of the y-value of a secp256k1 signature
      |- `maxPriorityFeePerGas`: Hexadecimal string representing the tip that the user wants to pay for having its transaction included in a block faster
      |- `maxFeePerGas`: Hexadecimal string representing the maximum amount of tokens the user is willing to pay for the transaction""".stripMargin,
    RpcSigned1559TransactionResponse(
      nonce = Nonce(0x0),
      hash = Hex.decodeUnsafe("c2d5d4589896ff40e4e487191cee9a28aa0f98c7c74a8fa76c387b3965c7a32c"),
      from = Address(Hex.decodeUnsafe("98f04bd668837466530638c65ae695759e9a0b78")),
      to = Some(Hex.decodeUnsafe("254f0feb0e1d3d54b6bed12872b35414761d0750")),
      gas = 0x2dc6c0,
      value = 0x0,
      input = Hex.decodeUnsafe("c367229900000000000000000000000040409dc3530287b0840e8c8d83d169d46eda8075"),
      maxPriorityFeePerGas = 0x3b9aca00,
      maxFeePerGas = 0x77359400,
      accessList = Seq.empty,
      chainId = 0x4f,
      yParity = 0x00,
      r = BigInt(Hex.decodeAsArrayUnsafe("64a909c53d68bb4fe20e0ddd48e9c283daa37b635f95a6e377c1b6d63b594a0f")),
      s = BigInt(Hex.decodeAsArrayUnsafe("64a909c53d68bb4fe20e0ddd48e9c283daa37b635f95a6e377c1b6d63b594a0f")),
      transactionIndex = 0x0,
      blockHash = Hex.decodeUnsafe("80cc293ebaab7bc41ab5497193299c06fc2452af80e0faf25aa6010a68818e2c"),
      blockNumber = 0x6890
    )
  )

  implicit val smartContractCodeDocumentation: Documentation[SmartContractCode] = Documentation(
    "Hexadecimal string representing the code of a smart contract",
    SmartContractCode(
      Hex.decodeUnsafe(
        "608060405234801561001057600080fd5b506004361061002b5760003560e01c8063e21f37ce14610030575b600080fd5b61003861004e565b6040516100459190610115565b60405180910390f35b6000805461005b90610186565b80601f016020809104026020016040519081016040528092919081815260200182805461008790610186565b80156100d45780601f106100a9576101008083540402835291602001916100d4565b820191906000526020600020905b8154815290600101906020018083116100b757829003601f168201915b505050505081565b60006100e782610137565b6100f18185610142565b9350610101818560208601610153565b61010a816101e7565b840191505092915050565b6000602082019050818103600083015261012f81846100dc565b905092915050565b600081519050919050565b600082825260208201905092915050565b60005b83811015610171578082015181840152602081019050610156565b83811115610180576000848401525b50505050565b6000600282049050600182168061019e57607f821691505b602082108114156101b2576101b16101b8565b5b50919050565b7f4e487b7100000000000000000000000000000000000000000000000000000000600052602260045260246000fd5b6000601f19601f830116905091905056fea2646970667358221220a1cc7e1b2e4c7fa728e7587d8e1f824795225d59f4c6c5cc5d8fd4e5776f264b64736f6c63430008070033"
      )
    )
  )

  implicit val storageDataDocumentation: Documentation[StorageData] = Documentation(
    "Hexadecimal value representing a part of the storage",
    StorageData(Hex.decodeUnsafe("00"))
  )

  implicit val transactionHashDocumentation: Documentation[TransactionHash] = Documentation(
    "Hexadecimal string of 32 bytes representing the hash of a transaction",
    TransactionHash(Hex.decodeUnsafe("1d82fc14125f858d09ae794f91e72f0cbeb8ca62539fe7380eb906d86140dab9"))
  )

  implicit val transactionLogWithRemovedDocumentation: Documentation[Seq[TransactionLogWithRemoved]] = Documentation(
    """Object with the following fields:
      |- `removed`: Always false
      |- `logIndex`: Index of the log
      |- `transactionIndex`: Index of the transaction in the block
      |- `transactionHash`: Hexadecimal string of 32 bytes representing the hash of the transaction
      |- `blockHash`: Hexadecimal string of 32 bytes representing the hash of the transaction
      |- `blockNumber`: Hexadecimal string representing the number of a block that contains the transaction
      |- `address`: Address of the log
      |- `data`: List of hexadecimal strings of 32 bytes
      |- `topics`: List of hexadecimal strings of 32 bytes""".stripMargin,
    Seq(
      TransactionLogWithRemoved(
        removed = false,
        logIndex = 0x0,
        transactionIndex = 0x0,
        transactionHash =
          TransactionHash(Hex.decodeUnsafe("3a9e5830e0e178e62863eb90d54f0775c9595566c3616e09137e808abe276da5")),
        blockHash = BlockHash(Hex.decodeUnsafe("c654866b4e634cb2edac2807f5861d57e39e16bca8cb4a0a2fa65c01c1952743")),
        blockNumber = BlockNumber(0x6977),
        address = Address(Hex.decodeUnsafe("5dc81435ee97fac07971c7b4d4f16a73b6930c53")),
        data = Hex.decodeUnsafe("00000000000000000000000000000000000000000000009f60b23736609cdc60"),
        topics = Seq(
          Hex.decodeUnsafe("ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"),
          Hex.decodeUnsafe("0000000000000000000000000000000000000000000000000000000000000000"),
          Hex.decodeUnsafe("000000000000000000000000964028bc964be646b506ee6114be7abbac4c0699")
        )
      ),
      TransactionLogWithRemoved(
        removed = false,
        logIndex = 0x1,
        transactionIndex = 0x0,
        transactionHash =
          TransactionHash(Hex.decodeUnsafe("3a9e5830e0e178e62863eb90d54f0775c9595566c3616e09137e808abe276da5")),
        blockHash = BlockHash(Hex.decodeUnsafe("c654866b4e634cb2edac2807f5861d57e39e16bca8cb4a0a2fa65c01c1952743")),
        blockNumber = BlockNumber(0x6977),
        address = Address(Hex.decodeUnsafe("e48a9641bbabfd613fb71a603f1b77b16315cfd0")),
        data = Hex.decodeUnsafe(
          "00000000000000000000000000000000000000000000000000000000000000600000000000000000000000000000000000000000000000001b7a5f826f4600000000000000000000000000000000000000000000000000000d99a8cec7e1ffff00000000000000000000000000000000000000000000000000000000000000104d696e7420537461626c6520436f696e00000000000000000000000000000000"
        ),
        topics = Seq(Hex.decodeUnsafe("2ac84346aabce3554154065f175fa2995bf95c84cfc1bb62bd1e77aefa7282df"))
      )
    )
  )

  implicit val transactionReceiptResponseDocumentation: Documentation[TransactionReceiptResponse] = Documentation(
    """Object with the following fields:
      |- `transactionHash`: Hexadecimal string of 32 bytes representing the hash of the transaction
      |- `transactionIndex`: Index of the transaction in the block
      |- `blockNumber`: Hexadecimal string representing the number of a block that contains the transaction
      |- `blockHash`: Hexadecimal string of 32 bytes representing the hash of the transaction
      |- `from`: Hexadecimal string of 20 bytes representing the address of the sender
      |- `cumulativeGasUsed`: Hexadecimal string representing the amount of gas that was already used and the gas used by this transaction
      |- `gasUsed`: Hexadecimal string representing the amount of gas used by the transaction
      |- `contractAddress (optional)`: Hexadecimal string of 20 bytes representing the address of the smart contract. Not present if the transaction is not a contract creation.
      |- `logs`: Object with the following fields:
      |  - `logIndex`: Index of the log
      |  - `transactionIndex`: Index of the transaction in the block
      |  - `transactionHash`: Hexadecimal string of 32 bytes representing the hash of the transaction
      |  - `blockHash`: Hexadecimal string of 32 bytes representing the hash of the transaction
      |  - `blockNumber`: Hexadecimal string representing the number of a block that contains the transaction
      |  - `address`: Address of the log
      |  - `data`: List of hexadecimal string of 32 bytes
      |  - `topics`: List of hexadecimal string of 32 bytes
      |- `logsBloom`: Hexadecimal string of 256 bytes representing the bloom filter for the logs of the block
      |- `root`: Hexadecimal string of 32 bytes representing the root of the final state trie after executing the transaction
      |- `status`: Hexadecimal string representing the status (0x1 for success, 0x0 for failure). Not present before Byzantium fork
      |- `to` (optional): Hexadecimal string of 20 bytes representing the address of the receiver. Not present for contract creation.""".stripMargin,
    TransactionReceiptResponse(
      transactionHash =
        TransactionHash(Hex.decodeUnsafe("3a9e5830e0e178e62863eb90d54f0775c9595566c3616e09137e808abe276da5")),
      transactionIndex = 0x0,
      blockNumber = BlockNumber(0x6977),
      blockHash = BlockHash(Hex.decodeUnsafe("c654866b4e634cb2edac2807f5861d57e39e16bca8cb4a0a2fa65c01c1952743")),
      from = Address(Hex.decodeUnsafe("964028bc964be646b506ee6114be7abbac4c0699")),
      to = Some(Address(Hex.decodeUnsafe("e48a9641bbabfd613fb71a603f1b77b16315cfd0"))),
      cumulativeGasUsed = 0x1b436,
      gasUsed = 0x1b436,
      contractAddress = None,
      logs = Seq(
        TransactionLog(
          logIndex = 0x0,
          transactionIndex = 0x0,
          transactionHash =
            TransactionHash(Hex.decodeUnsafe("3a9e5830e0e178e62863eb90d54f0775c9595566c3616e09137e808abe276da5")),
          blockHash = BlockHash(Hex.decodeUnsafe("c654866b4e634cb2edac2807f5861d57e39e16bca8cb4a0a2fa65c01c1952743")),
          blockNumber = BlockNumber(0x6977),
          address = Address(Hex.decodeUnsafe("5dc81435ee97fac07971c7b4d4f16a73b6930c53")),
          data = Hex.decodeUnsafe("00000000000000000000000000000000000000000000009f60b23736609cdc60"),
          topics = Seq(
            Hex.decodeUnsafe("ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"),
            Hex.decodeUnsafe("0000000000000000000000000000000000000000000000000000000000000000"),
            Hex.decodeUnsafe("000000000000000000000000964028bc964be646b506ee6114be7abbac4c0699")
          )
        ),
        TransactionLog(
          logIndex = 0x1,
          transactionIndex = 0x0,
          transactionHash =
            TransactionHash(Hex.decodeUnsafe("3a9e5830e0e178e62863eb90d54f0775c9595566c3616e09137e808abe276da5")),
          blockHash = BlockHash(Hex.decodeUnsafe("c654866b4e634cb2edac2807f5861d57e39e16bca8cb4a0a2fa65c01c1952743")),
          blockNumber = BlockNumber(0x6977),
          address = Address(Hex.decodeUnsafe("e48a9641bbabfd613fb71a603f1b77b16315cfd0")),
          data = Hex.decodeUnsafe(
            "00000000000000000000000000000000000000000000000000000000000000600000000000000000000000000000000000000000000000001b7a5f826f4600000000000000000000000000000000000000000000000000000d99a8cec7e1ffff00000000000000000000000000000000000000000000000000000000000000104d696e7420537461626c6520436f696e00000000000000000000000000000000"
          ),
          topics = Seq(Hex.decodeUnsafe("2ac84346aabce3554154065f175fa2995bf95c84cfc1bb62bd1e77aefa7282df"))
        )
      ),
      logsBloom = Hex.decodeUnsafe(
        "00000000000000000000000000000000000010000000000000000000000040000000000000000000000000000000000000002000000000000000000000804000000000000000000000100008400000000000000000000000000000000000000000000000020000000000000000000800000000000000000000000110000000000000000000000000000000000000001000100000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000002000000000000000000000000040000000000000040000000000020000000000000000000000000000000000000000000000000000000000000000000"
      ),
      root = None,
      status = Some(0x1)
    )
  )

  implicit val transactionRequestDocumentation: Documentation[TransactionRequest] = Documentation(
    """Object that contains the following fields:
      |- `from`: Hexadecimal string of 20 bytes representing the address of the sender
      |- `to` (optional): Hexadecimal string of 20 bytes representing the address of the recipient
      |- `value` (optional): Hexadecimal string representing the number of tokens sent in the transaction
      |- `gas` (optional): Hexadecimal string representing the gas limit
      |- `gasPrice` (optional): Hexadecimal string representing the gas price of the transaction
      |- `nonce` (optional): Hexadecimal string representing the nonce
      |- `data` (optional): Hexadecimal string representing the payload of the transaction""".stripMargin,
    TransactionRequest(
      from = Address(Hex.decodeUnsafe("CC95F2A1011728FC8B861B3C9FEEFBB4E7449B98")),
      to = Some(Address(Hex.decodeUnsafe("5e05fe3739f967995292ca32442444f1aa27d7e5"))),
      value = Some(Token(BigInt(Hex.decodeAsArrayUnsafe("de0b6b3a7640000")))),
      gas = Some(Gas(67)),
      gasPrice = Some(Token(0x01)),
      nonce = Some(Nonce(3)),
      data = Some(Hex.decodeUnsafe("508EDA7A"))
    )
  )

  implicit val traceParameterDocumentation: Documentation[TraceParameters] = Documentation(
    """Object that contains the following fields:
      |- `tracer`: the javascript code that will be used for tracing
      |- `timeout` (optional): Overrides the default timeout""".stripMargin,
    TraceParameters(
      "{data: [], fault: function(log) {}, step: function(log) { if(log.op.toString() == \"SSTORE\") this.data.push(log.stack.peek(0)); }, result: function() { return this.data; }}",
      Some(10.seconds)
    )
  )

  implicit val traceTransactionResponseDocumentation: Documentation[TraceTransactionResponse] = Documentation(
    "The result of the tracing",
    TraceTransactionResponse("[0, 1]")
  )

}
// scalastyle:on magic.number
// scalastyle:on line.size.limit
