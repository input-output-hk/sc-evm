# Block Propagation (internal) metric

## Definition

The following actions are performed during internal block propagation:

1. Slot event tick
2. New block creation
3. Block saved to storage
4. Block evaluation by consensus
5. Publishing of the block to a topic
6. Broadcasting block to other peers

## Metrics

### Internal block propagation time

Time it takes for the generation of a block, from the occurrence of a slot event until it is sent to other peers.
This metric is divided into several submetrics, because several measurements are taken by different parts of the codebase:

Formula: `( rate(sc_evm_block_propagation_generation_time_sum{namespace="$namespace"}[30s]) + rate(sc_evm_block_propagation_publishing_time_sum{namespace="$namespace"}[30s]) + rate(sc_evm_block_propagation_to_peeractor_time_sum{namespace="$namespace"}[30s]) + rate(sc_evm_block_propagation_to_network_time_sum{namespace="$namespace"}[30s]) ) / (rate(sc_evm_block_propagation_generation_time_count{namespace="$namespace"}[30s]) + rate(sc_evm_block_propagation_publishing_time_count{namespace="$namespace"}[30s]) + rate(sc_evm_block_propagation_to_peeractor_time_count{namespace="$namespace"}[30s]) + rate(sc_evm_block_propagation_to_network_time_count{namespace="$namespace"}[30s]) )`

This formula shows the computation of the average time for internal block propagation in intervals of 30 seconds.
