package io.iohk.scevm.rpc

import com.typesafe.config.Config

final case class FilterConfig(
    filterMaxBlockToFetch: Int
)

object FilterConfig {
  def fromConfig(etcClientConfig: Config): FilterConfig = {
    val filterConfig = etcClientConfig.getConfig("filter")

    new FilterConfig(
      filterMaxBlockToFetch = filterConfig.getInt("filter-max-block-to-fetch")
    )
  }
}
