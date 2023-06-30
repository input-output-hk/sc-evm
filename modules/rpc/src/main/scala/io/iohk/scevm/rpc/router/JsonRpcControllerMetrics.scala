package io.iohk.scevm.rpc.router

import io.prometheus.client.{Counter, Histogram}

import java.time.Duration

case object JsonRpcControllerMetrics {
  private val namespace = "sc_evm"
  private val subsystem = "rpc"

  final val NotFoundMethodsCounter: Counter =
    Counter
      .build()
      .namespace(namespace)
      .subsystem(subsystem)
      .name("methods_not_found")
      .help("Total amount of methods calls not found")
      .withExemplars()
      .register()

  final val MethodsSuccessCounter: Counter =
    Counter
      .build()
      .namespace(namespace)
      .subsystem(subsystem)
      .name("methods_success")
      .help("Total amount of methods calls processed successfully")
      .withExemplars()
      .register()

  final val MethodsErrorCounter: Counter =
    Counter
      .build()
      .namespace(namespace)
      .subsystem(subsystem)
      .name("methods_error")
      .help("Total amount of methods calls processed unsuccessfully")
      .withExemplars()
      .register()

  final val MethodsExceptionCounter: Counter =
    Counter
      .build()
      .namespace(namespace)
      .subsystem(subsystem)
      .name("methods_exception")
      .help("Total amount of methods calls that threw an exception")
      .withExemplars()
      .register()

  final private val MethodProcessingDuration =
    Histogram
      .build()
      .namespace(namespace)
      .subsystem(subsystem)
      .name("method_response_time")
      .help("Amount of time it took to process an RPC call")
      .labelNames("method")
      .withExemplars()
      .register()

  def recordMethodProcessingDuration(method: String, time: Duration): Unit =
    MethodProcessingDuration.labels(method).observe(time.toMillis / 1000.0)
}
