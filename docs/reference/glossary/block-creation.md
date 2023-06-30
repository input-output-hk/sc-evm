# Block creation metric

## Definition

The following actions are performed during block creation:

1. Generate a new unsigned block header
2. Determine the transactions to be included in the block
3. Execute the transactions to be included in the block
4. Update `InMemoryWorldState`
5. Build the MerklePatriciaTrie for transactions and receipts
6. Generate the final block with all the data computed in the points above

## Metrics

### Block creation time

Time it takes for the creation of a new block when a node is the elected slot leader.

Formula: `rate(sc_evm_block_creation_time_sum{namespace="$namespace"}[30s]) / rate(sc_evm_block_creation_time_count{namespace="$namespace"}[30s])`

This formula shows the computation of the average time for block creation in intervals of 30 seconds.

### Block creation errors

Number of error that happened during block creation.

Formula: `increase(sc_evm_block_creation_errors_total{namespace="$namespace"}[$__range])`

This formula shows the number of errors that happened only during a specific time range, removing noise from possible errors that happened before that range.
