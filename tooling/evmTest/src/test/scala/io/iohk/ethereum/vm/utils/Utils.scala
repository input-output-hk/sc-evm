package io.iohk.ethereum.vm.utils

import io.circe.Error
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.auto._
import io.circe.parser.decode
import io.iohk.bytes.ByteString

import java.io.File
import scala.io.Source

object Utils {

  def loadContractCodeFromFile(file: File): ByteString = {
    val src = Source.fromFile(file)
    val raw =
      try src.mkString
      finally src.close()
    ByteString(raw.trim.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray)
  }

  def loadContractAbiFromFile(file: File): Either[Error, List[ABI]] = {
    val src = Source.fromFile(file)
    val raw =
      try src.mkString
      finally src.close()
    implicit val config = Configuration.default.withDefaults
    decode[List[ABI]](raw)
  }

}
