package io.iohk.ethereum.vm.utils

import ABI._

object ABI {
  final case class Param(name: String, `type`: String)
}

final case class ABI(`type`: String, name: String = "", inputs: Seq[Param] = Nil, outputs: Seq[Param] = Nil) {
  val shortSignature: String = s"$name(${inputs.map(_.`type`).mkString(",")})"

  val fullSignature: String = {
    val in = inputs.map(i => i.`type` + " " + i.name).mkString(", ")
    val out = outputs
      .map { o =>
        val s = if (o.name.isEmpty) "" else " "
        o.`type` + s + o.name
      }
      .mkString(", ")

    s"$name($in) returns ($out)"
  }
}
