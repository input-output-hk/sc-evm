# Block Transactions metric

## Definition

Number of transactions that are part of a block body.
Only transactions that were validated and executed successfully are added to a block.

## Metric

Formula: `sc_evm_block_transactions_count{namespace="$namespace"})`
