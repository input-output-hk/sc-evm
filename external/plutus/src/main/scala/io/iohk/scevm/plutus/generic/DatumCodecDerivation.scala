package io.iohk.scevm.plutus.generic

import io.iohk.scevm.plutus.DatumCodec

import scala.language.experimental.macros

trait DatumCodecDerivation {
  def derive[T]: DatumCodec[T] = macro DatumCodecMacro.derive[T]
}

object DatumCodecMacro {
  import scala.reflect.macros.whitebox
  def derive[T: c.WeakTypeTag](c: whitebox.Context): c.Tree = {
    import c.universe._
    val t = weakTypeOf[T]
    q"""_root_.io.iohk.scevm.plutus.DatumCodec.make(
          _root_.io.iohk.scevm.plutus.DatumEncoder.derive[$t],
          _root_.io.iohk.scevm.plutus.DatumDecoder.derive[$t]
       )"""
  }
}
