# Storage metrics

## Definition

### Reverse storage (read time)

Duration of reading data from ReverseChainStorageImpl.

### Block header (read time)

Duration of reading a block header from AlternativeBlockHeadersStorageImpl.

### Block header (write time)

Duration of writing a block header into storage.

Two actions are taken into account for this metric:

- updating AlternativeBlockHeadersStorageImpl
- updating ReverseChainStorageImpl

### Block body (read time)

Duration of reading a block body from AlternativeBlockBodiesStorageImpl.

### Block body (write time)

Duration of writing a block body into AlternativeBlockBodiesStorageImpl.

### Stable number (read time)

Duration of reading a block hash from StableNumberMappingStorageImpl.

### Stable number (write time)

Duration of writing a block hash into StableNumberMappingStorageImpl.

### Stable transaction (read time)

Duration of reading the transaction location from StableTransactionMappingStorageImpl.

### Stable indexes update (write time)

Duration of writing:

- a block hash into StableNumberMappingStorageImpl
- and for writing transaction locations into StableTransactionMappingStorageImpl

### Latest stable number (read time)

Duration of reading the latest stable number from ObftAppStorage

### Latest stable number (write time)

Duration of writing the latest stable number into ObftAppStorage

### Receipts (read time)

Duration of reading receipts from ReceiptStorageImpl

### Receipts (write time)

Duration of writing receipts into ReceiptStorageImpl

### EVM code (read time)

Duration of reading an evm code from EvmCodeStorage

### EVM code (write time)

Duration of writing an evm code into EvmCodeStorage

### Node (read time)

Duration of reading a node from NodeStorage

### Node (write time)

Duration of writing a node into NodeStorage

## Computation

Every storage metrics is computed as follows:

The value is obtained by computing the average of the execution time for every block in intervals of 30 seconds.

Formulae: `rate(sc_evm_storage_<metric_name>_sum{namespace="$namespace"}[30s]) / rate(sc_evm_storage_<metric_name>_count{namespace="$namespace"}[30s])`
