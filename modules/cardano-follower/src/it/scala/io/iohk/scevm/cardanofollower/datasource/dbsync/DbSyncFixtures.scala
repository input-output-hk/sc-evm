package io.iohk.scevm.cardanofollower.datasource.dbsync

import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.Address
import io.iohk.scevm.trustlesssidechain.cardano.{AssetName, EpochNonce, MainchainTxHash, PolicyId}

/** Values in this file correspond to values in files in it/resources/db/migration
  */
trait DbSyncFixtures {
  val FuelAssetName: AssetName = AssetName(ByteString("FUEL".getBytes))

  val McToScScriptAddress: PolicyId =
    PolicyId.fromHexUnsafe("52424aa2e3243dabef86f064cd3497bc176e1ca51d3d7de836db5571")

  val nftPolicyId: PolicyId =
    PolicyId.fromHexUnsafe("636f6d6d697474656586f064cd3497bc176e1ca51d3d7de836db5571")

  val tx1Hash: MainchainTxHash = MainchainTxHash(
    Hex.decodeUnsafe("ABEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1")
  )
  val tx2Hash: MainchainTxHash =
    MainchainTxHash.decodeUnsafe("BBEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1")
  val tx5Hash: MainchainTxHash = MainchainTxHash(
    Hex.decodeUnsafe("0beed7fb0067f14d6f6436c7f7dedb27ce3ceb4d2d18ff249d43b22d86fae3f1")
  )

  val xcTxHash1: MainchainTxHash = MainchainTxHash(
    Hex.decodeUnsafe("EBEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1")
  )
  val xcTxHash2: MainchainTxHash = MainchainTxHash(
    Hex.decodeUnsafe("EBEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F2")
  )
  val xcTxHash3: MainchainTxHash = MainchainTxHash(
    Hex.decodeUnsafe("EBEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F3")
  )

  val invalidXcTx1Hash: MainchainTxHash = MainchainTxHash(
    Hex.decodeUnsafe("BBBED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1")
  )
  val invalidXcTx2Hash: MainchainTxHash = MainchainTxHash(
    Hex.decodeUnsafe("CCCED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1")
  )

  val tx1RecipientAddress: Address = Address(Hex.decodeAsArrayUnsafe("CC95F2A1011728FC8B861B3C9FEEFBB4E7449B98"))

  val epoch189Nonce: EpochNonce = EpochNonce(
    BigInt("77767497351609740808791974902407088128725460654185244904577174913945395979249")
  )
  val epoch190Nonce: EpochNonce = EpochNonce(
    BigInt("77767497351609740808791974902407088128725460654185244904577174913945395979250")
  )

  val mainchainPubKeyHex1 = "bfbee74ab533f40979101057f96de62e95233f2a5216eb16b54106f09fd7350d"
  val sidechainSignatureHex1 =
    "f8ec6c7f935d387aaa1693b3bf338cbb8f53013da8a5a234f9c488bacac01af259297e69aee0df27f553c0a1164df827d016125c16af93c99be2c19f36d2f66e"
  val sidechainPubKeyHex1 =
    "fe8d1eb1bcb3432b1db5833ff5f2226d9cb5e65cee430558c18ed3a3c86ce1af07b158f244cd0de2134ac7c1d371cffbfae4db40801a2572e531c573cda9b5b4"
  val mainchainSignatureHex1 =
    "28d1c3b7df297a60d24a3f88bc53d7029a8af35e8dd876764fd9e7a24203a3482a98263cc8ba2ddc7dc8e7faea31c2e7bad1f00e28c43bc863503e3172dc6b0a"

  val mainchainPubKeyHex2 = "cfbee74ab533f40979101057f96de62e95233f2a5216eb16b54106f09fd7350d"

  val mainchainPubKeyHex3 = "3fd6618bfcb8d964f44beba4280bd91c6e87ac5bca4aa1c8f1cde9e85352660b"
  val sidechainPubKeyHex3 =
    "333e47cab242fefe88d7da1caa713307290291897f100efb911672d317147f729211701e5c5e70690952bc2f5fa98478898128bc5c77f8837c392dae660c6bc4"
  val mainchainSignatureHex3 =
    "1fd2f1e5ad14c829c7359474764701cd74ab9c433c29b0bbafaa6bcf22376e9d651391d08ae6f40b418d2abf827c4c1fcb007e779a2beba7894d68012942c708"
  val sidechainSignatureHex3 =
    "3e8a8b29e513a08d0a66e22422a1a85d1bf409987f30a8c6fcab85ba38a85d0d27793df7e7fb63ace12203b062feb7edb5e6664ac1810b94c38182acc6167425"
}
