package io.iohk.scevm.rpc.armadillo

import io.iohk.armadillo.{
  JsonRpcCodec,
  JsonRpcEndpoint,
  JsonRpcEndpointTransput,
  JsonRpcInput,
  JsonRpcOutput,
  MethodName,
  jsonRpcEndpoint
}
import io.iohk.scevm.rpc.serialization.JsonMethodsImplicits

trait Api {

  /** Create the base for a JsonRpcEndpoint.
    *
    * Note: Return lines are replaced by the sequence of characters that allows to have return lines in playground-openrpc.org
    */
  def baseEndpoint(name: String, description: String): JsonRpcEndpoint[Unit, Unit, Unit] = {
    val base               = jsonRpcEndpoint(new MethodName(name))
    val refinedDescription = description.replaceAll("\n", "\r \n")
    base.description(refinedDescription)
  }

  /** Generate an instance of JsonRpcInput.
    * A description of the parameter will also be set if a description is available for the given language.
    * If no language is provided, the default language will be used.
    * An example will also be associated to the parameter.
    */
  def input[T](name: String)(implicit codec: JsonRpcCodec[T], description: Documentation[T]): JsonRpcInput.Basic[T] =
    get[T, JsonRpcInput.Basic[T]](name, io.iohk.armadillo.param[T])

  /** Generate an instance of JsonRpcOutput.
    * A description of the result will also be set if a description is available for the given language.
    * If no language is provided, the default language will be used.
    * An example will also be associated to the result.
    */
  def output[T](name: String)(implicit codec: JsonRpcCodec[T], description: Documentation[T]): JsonRpcOutput.Basic[T] =
    get[T, JsonRpcOutput.Basic[T]](name, io.iohk.armadillo.result[T])

  private def get[T, X <: JsonRpcEndpointTransput.Basic[T]](name: String, toX: String => X)(implicit
      documentation: Documentation[T]
  ): X =
    toX(name).description(documentation.description).asInstanceOf[X].example(documentation.example).asInstanceOf[X]
}

trait CommonApi extends Api with JsonMethodsImplicits with CommonRpcSchemas with DocumentationImplicits
