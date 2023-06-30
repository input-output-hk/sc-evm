package io.iohk.sidechains.relay

import io.iohk.ethereum.crypto.{ECDSA, ECDSASignature, ECDSASignatureNoRecovery}
import io.iohk.ethereum.utils.Hex

import RpcFormats.{CrossChainSignaturesEntry, SidechainParams}

/** Responsibility: facilitate creation of sidechain-main-cli parameters. */
object CtlParameters {

  type Committee = List[String]

  def makeSidechainParams(sidechainParams: SidechainParams): List[String] =
    List(
      "--genesis-committee-hash-utxo",
      sidechainParams.genesisUtxo,
      "--sidechain-id",
      Integer.parseInt(sidechainParams.chainId.stripPrefix("0x"), 16).toString,
      "--sidechain-genesis-hash",
      sidechainParams.genesisHash.stripPrefix("0x"),
      "--threshold-numerator",
      sidechainParams.thresholdNumerator.toString,
      "--threshold-denominator",
      sidechainParams.thresholdDenominator.toString
    )

  def makeSidechainEpochParams(epoch: Long): List[String] =
    List("--sidechain-epoch", epoch.toString)

  def makeSigningKeyParams(skeyPath: String): List[String] = List("--payment-signing-key-file", skeyPath)

  def makeMerkleRootParams(merkleRoot: String): List[String] = List("--merkle-root", merkleRoot.stripPrefix("0x"))

  def makePreviousMerkleRootParams(previousMerkleRoot: Option[String]): List[String] =
    previousMerkleRoot.toList.flatMap(r => List("--previous-merkle-root", r.stripPrefix("0x")))

  def makeSignatureParameters(committee: Committee, signatureEntries: List[CrossChainSignaturesEntry]): List[String] = {
    val signatures = signatureEntries.map(e => (e.committeeMember, e.signature)).toMap
    committee
      .map { member =>
        val signaturePart = signatures.get(member).map(signature => s":${dropRecoveryByte(signature)}").getOrElse("")
        s"${compressKey(member)}$signaturePart"
      }
      .sorted
      .flatMap(v => List("--committee-pub-key-and-signature", v))
  }

  def makeSignatureParametersFromKeys(
      committee: List[ECDSA.PublicKey],
      signatureEntries: List[(ECDSA.PublicKey, ECDSASignatureNoRecovery)]
  ): List[String] = {
    val signatures = signatureEntries.toMap
    committee
      .map { member =>
        val signaturePart =
          signatures.get(member).map(signature => s":${Hex.toHexString(signature.toBytes)}").getOrElse("")
        s"${Hex.toHexString(member.compressedBytes)}$signaturePart"
      }
      .sorted
      .flatMap(v => List("--committee-pub-key-and-signature", v))
  }

  def makeNewCommitteeParams(pubKeys: List[String]): List[String] =
    pubKeys.map(compressKey).sorted.flatMap(List("--new-committee-pub-key", _))

  def makeNewCommitteeParamsFromKey(pubKeys: List[ECDSA.PublicKey]): List[String] =
    pubKeys.map(k => Hex.toHexString(k.compressedBytes)).sorted.flatMap(List("--new-committee-pub-key", _))

  def makeCommitteeParams(pubKeys: List[String]): List[String] =
    pubKeys.map(compressKey).sorted.flatMap(List("--committee-pub-key", _))

  def compressKey(keyHexString: String): String =
    Hex.toHexString(ECDSA.PublicKey.fromHexUnsafe(keyHexString.stripPrefix("0x")).compressedBytes)

  private def dropRecoveryByte(signatureHexString: String): String =
    Hex.toHexString(ECDSASignature.fromHexUnsafe(signatureHexString.stripPrefix("0x")).withoutRecoveryByte.toBytes)
}
