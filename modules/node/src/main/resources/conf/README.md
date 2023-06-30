# Configuration

Configurations are defined by three files:

- `genesis.json`: contains the parameters that are used to generate the genesis hash
- `chain.conf`: contains the structural parameter of a chain, i.e. parameters that must not be changed, otherwise the node won't be able to connect the target network
- `application.conf`: contains the parameters that can be used to tune the node (address and port of http server, RPC apis that are enabled, etc.)

## Available environment configuration

Some configuration can be overridden with environment variable:

- data
  - `SC_EVM_DATADIR`: sc-evm.datadir
- private keys
  - `LEADER_PRIVATE_KEY`: sc-evm.pos.private-keys
  - `SC_EVM_FAUCET_PRIVATE_KEY`: sc-evm.faucet.private-key
- network
  - `SC_EVM_RPC_PORT`: sc-evm.network.rpc.http.port
  - `SC_EVM_P2P_PORT`: sc-evm.network.server-address.port
- sync
  - `SYNC_MODE`: sc-evm.sync.sync-mode
  - `START_SYNC`: sc-evm.sync.start-sync
- metrics/tracing
  - `METRICS_ENABLED`: sc-evm.instrumentation.metrics.enabled
  - `TRACING_ENABLED`: sc-evm.instrumentation.tracing.enabled
  - `TRACING_EXPORT_HOST`: sc-evm.instrumentation.tracing.export-host
  - `TRACING_EXPORT_PORT`: sc-evm.instrumentation.tracing.export-port
- logs
  - `SC_EVM_LOGLEVEL`: logging.logs-level
  - `SC_EVM_AKKA_LOGLEVEL`: akka.loglevel
