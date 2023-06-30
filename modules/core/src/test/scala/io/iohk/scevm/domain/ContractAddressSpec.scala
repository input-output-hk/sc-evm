package io.iohk.scevm.domain

import io.iohk.ethereum.utils.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ContractAddressSpec extends AnyFlatSpec with Matchers {
  // values come from https://ethereum.stackexchange.com/a/761
  private val sender = Hex.decodeUnsafe("6ac7ea33f8831ea9dcc53393aaa88b25a785dbf0")
  "ContractAddress" should "compute the expected value for nonce=0" in {
    val expected0 = Address(Hex.decodeUnsafe("cd234a471b72ba2f1ccf0a70fcaba648a5eecd8d"))
    val computed0 = ContractAddress.fromSender(Address(sender), Nonce(0))

    computed0 shouldBe expected0
  }

  it should "compute the expected value for nonce=1" in {
    val expected1 = Address(Hex.decodeUnsafe("343c43a37d37dff08ae8c4a11544c718abb4fcf8"))
    val computed1 = ContractAddress.fromSender(Address(sender), Nonce(1))

    computed1 shouldBe expected1
  }
}
