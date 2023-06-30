package io.iohk.ethereum.utils

import org.scalacheck.{Arbitrary, Gen}

import java.math.BigInteger

object Generators {
  private def byteArrayOfNItemsGen(n: Int): Gen[Array[Byte]] = Gen.listOfN(n, Arbitrary.arbitrary[Byte]).map(_.toArray)

  lazy val bigIntGen: Gen[BigInt] = byteArrayOfNItemsGen(32).map(b => new BigInteger(1, b))
}
