package io.iohk.scevm.rpc.armadillo.personal

import cats.syntax.all._
import io.circe.literal.JsonStringContext
import io.iohk.armadillo.JsonRpcError
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.ECDSASignature
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.{Address, Gas, Nonce, Token, TransactionHash}
import io.iohk.scevm.rpc.armadillo.ArmadilloScenarios
import io.iohk.scevm.rpc.controllers.PersonalController.{Passphrase, RawPrivateKey}
import io.iohk.scevm.rpc.domain.TransactionRequest
import org.json4s.JsonAST.JObject
import org.json4s.JsonDSL._
import org.json4s.{JArray, JNull, JString}

import java.time.Duration

class PersonalEndpointsSpec extends ArmadilloScenarios {
  "personal_newAccount" shouldPass new EndpointScenarios(PersonalApi.personal_newAccount) {
    val passphrase: String = "new!Super'p@ssphr@se"
    "handle successful request" in successScenario(
      params = JArray(List(JString(passphrase))),
      expectedInputs = Passphrase(passphrase),
      serviceResponse = Address(Hex.decodeUnsafe("aa6826f00d01fe4085f0c3dd12778e206ce4e2ac")),
      expectedResponse = json""""0xaa6826f00d01fe4085f0c3dd12778e206ce4e2ac""""
    )

    "handle error when passphrase is too short" in errorScenario(
      params = JArray(List(JString(passphrase))),
      serverError = JsonRpcError.noData(-32000, s"Provided passphrase must have at least 7 characters"),
      expectedResponse = json"""{"code":  -32000, "message": "Provided passphrase must have at least 7 characters" }"""
    )
  }

  "personal_listAccounts" shouldPass new EndpointScenarios(PersonalApi.personal_listAccounts) {
    val addresses: List[Address] = List(34, 12391, 123).map(Address(_))
    "handle successful request" in successScenario(
      params = JArray(Nil),
      expectedInputs = (),
      serviceResponse = addresses,
      expectedResponse = json"""${addresses.map(_.toString)}"""
    )
  }

  "personal_unlockAccount" shouldPass new EndpointScenarios(PersonalApi.personal_unlockAccount) {
    private val addressWithoutPrefix: String = "7a44789ed3cd85861c0bbf9693"
    private val address                      = JString(s"0x$addressWithoutPrefix")
    private val passphrase                   = JString("a-Proper!P@$$Phrase?")
    "handle valid request" in successScenario(
      params = JArray(address :: passphrase :: Nil),
      expectedInputs = (Address(Hex.decodeUnsafe(addressWithoutPrefix)), Passphrase(passphrase.s), None),
      serviceResponse = true,
      expectedResponse = json"""true"""
    )

    "handle valid request with duration" in successScenario(
      params = JArray(address :: passphrase :: JString("1") :: Nil),
      expectedInputs =
        (Address(Hex.decodeUnsafe(addressWithoutPrefix)), Passphrase(passphrase.s), Some(Duration.ofSeconds(1))),
      serviceResponse = true,
      expectedResponse = json"""true"""
    )

    "handle valid request with null duration" in successScenario(
      params = JArray(address :: passphrase :: JNull :: Nil),
      expectedInputs = (Address(Hex.decodeUnsafe(addressWithoutPrefix)), Passphrase(passphrase.s), None),
      serviceResponse = true,
      expectedResponse = json"""true"""
    )

    "reject request with negative duration" in validationFailScenario(
      params = JArray(address :: passphrase :: JString("-1") :: Nil),
      expectedResponse = json"""{"code": -32602, "message": "Invalid params"}"""
    )

    "handle possible errors" in errorScenario(
      params = JArray(address :: passphrase :: Nil),
      serverError = JsonRpcError.noData(-32602, "Duration should be a number of seconds, less than 2^31 - 1"),
      expectedResponse =
        json"""{"code":-32602, "message":  "Duration should be a number of seconds, less than 2^31 - 1"}"""
    )
  }

  "personal_lockAccount" shouldPass new EndpointScenarios(PersonalApi.personal_lockAccount) {
    val address: String = "aa6826f00d01fe4085f0c3dd12778e206ce4e2ac"
    "handle valid request" in successScenario(
      params = JArray(List(JString(s"0x$address"))),
      expectedInputs = Address(Hex.decodeUnsafe(address)),
      serviceResponse = true,
      expectedResponse = json"""true"""
    )
  }

  "personal_sendTransaction" shouldPass new EndpointScenarios(PersonalApi.personal_sendTransaction) {
    val transactionRequestWithHexadecimalValue = JObject(
      "from"     -> Address(42).toString,
      "to"       -> Address(123).toString,
      "value"    -> "3E8",
      "gas"      -> "0xC8",
      "gasPrice" -> "0xA",
      "nonce"    -> "0x1",
      "data"     -> "0xabcd"
    )
    val transactionRequestWithIntegerValue = JObject(
      "from"     -> Address(42).toString,
      "to"       -> Address(123).toString,
      "value"    -> 1000,
      "gas"      -> "0xC8",
      "gasPrice" -> "0xA",
      "nonce"    -> "0x1",
      "data"     -> "0xabcd"
    )
    val decodedTransactionRequest: TransactionRequest = TransactionRequest(
      from = Address(42),
      to = Address(123).some,
      value = Token(1000).some,
      gas = Some(Gas(200)),
      gasPrice = Some(Token(10)),
      nonce = Some(Nonce(1)),
      data = Option(Hex.decodeUnsafe("abcd"))
    )
    val passphrase: JString = JString("new!Super'p@ssphr@se")

    val txHash: ByteString = ByteString(1, 2, 3, 4)
    val hexHash            = s"0x${Hex.toHexString(txHash.toArray)}"

    "handle valid request (value as hexadecimal value)" in successScenario(
      params = JArray(transactionRequestWithHexadecimalValue :: passphrase :: Nil),
      expectedInputs = (decodedTransactionRequest, Passphrase("new!Super'p@ssphr@se")),
      serviceResponse = TransactionHash(txHash),
      expectedResponse = json"""$hexHash"""
    )

    "handle valid request (value as integer)" in successScenario(
      params = JArray(transactionRequestWithIntegerValue :: passphrase :: Nil),
      expectedInputs = (decodedTransactionRequest, Passphrase("new!Super'p@ssphr@se")),
      serviceResponse = TransactionHash(txHash),
      expectedResponse = json"""$hexHash"""
    )

    "handle an error" in errorScenario(
      params = JArray(transactionRequestWithHexadecimalValue :: passphrase :: Nil),
      serverError = JsonRpcError.noData(-32000, "Could not decrypt key with given passphrase"),
      expectedResponse = json"""{"code":  -32000, "message": "Could not decrypt key with given passphrase"}"""
    )
  }

  "personal_sign" shouldPass new EndpointScenarios(PersonalApi.personal_sign) {
    val messageWithoutPrefix: String = "deadbeaf"
    val message: JString             = JString(s"0x$messageWithoutPrefix")
    val addressWithoutPrefix: String = "9b2055d370f73ec7d8a03e965129118dc8f5bf83"
    val address: JString             = JString(s"0x$addressWithoutPrefix")
    val passphrase: JString          = JString("thePassphrase")

    val r: ByteString = Hex.decodeUnsafe("a3f20717a250c2b0b729b7e5becbff67fdaef7e0699da4de7ca5895b02a170a1")
    val s: ByteString = Hex.decodeUnsafe("2d887fd3b17bfdce3481f10bea41f45ba9f709d39ce8325427b57afcfc994cee")
    val v: Byte       = Hex.decodeUnsafe("1b").last

    "handle valid request" in successScenario(
      params = JArray(message :: address :: passphrase :: Nil),
      expectedInputs = (
        Hex.decodeUnsafe(messageWithoutPrefix),
        Address(Hex.decodeUnsafe(addressWithoutPrefix)),
        Some(Passphrase(passphrase.s))
      ),
      serviceResponse = ECDSASignature(r, s, v),
      expectedResponse =
        json""""0xa3f20717a250c2b0b729b7e5becbff67fdaef7e0699da4de7ca5895b02a170a12d887fd3b17bfdce3481f10bea41f45ba9f709d39ce8325427b57afcfc994cee1b""""
    )
  }

  "personal_importRawKey" shouldPass new EndpointScenarios(PersonalApi.personal_importRawKey) {
    val key  = "7a44789ed3cd85861c0bbf9693c7e1de1862dd4396c390147ecf1275099c6e6f"
    val addr = "0x00000000000000000000000000000000000000ff"
    val pass = "aaa"

    "handle valid request" in successScenario(
      params = JArray(JString(key) :: JString(pass) :: Nil),
      expectedInputs = (RawPrivateKey(Hex.decodeUnsafe(key)), Passphrase(pass)),
      serviceResponse = Address(addr),
      expectedResponse = json"""$addr"""
    )
  }

  "personal_ecRecover" shouldPass new EndpointScenarios(PersonalApi.personal_ecRecover) {
    val address              = "0x9b2055d370f73ec7d8a03e965129118dc8f5bf83"
    val messageWithoutPrefix = "deadbeaf"
    val message              = s"0x$messageWithoutPrefix"
    val signature            = s"0x${Hex.toHexString(ECDSASignature(BigInt(1), BigInt(2), 27: Byte).toBytes)}"

    "handle valid request" in successScenario(
      params = JArray(JString(message) :: JString(signature) :: Nil),
      expectedInputs = (Hex.decodeUnsafe(messageWithoutPrefix), ECDSASignature.fromHexUnsafe(signature)),
      serviceResponse = Address(address),
      expectedResponse = json"""$address"""
    )
  }

}
