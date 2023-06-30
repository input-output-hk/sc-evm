package io.iohk.scevm.exec.vm

import io.iohk.ethereum.vm.utils.EvmTestEnv
import org.openjdk.jmh.annotations.{Benchmark, Scope, State}
import org.openjdk.jmh.infra.Blackhole

class EVMBench {
  @Benchmark
  def fibonacci(bh: Blackhole, state: EVMBenchState): Unit =
    bh.consume {
      state.callGetFib8Context.call()
    }
}

@State(Scope.Benchmark)
class EVMBenchState extends EvmTestEnv {
  val (pr, contract)                         = deployContract("Fibonacci", contractsDir = "../evmTest/target/contracts")
  val callGetFib8Context: ContractMethodCall = contract.getNewFib(8)
}
