# FS2 streams

## Definitions

### Message received

Monitor the `PeerEvent` messages received

### Blockchain host

Monitor the `PeerEvent.MessageFromPeer` messages received that contain a message of type `OBFT1.RequestMessage`, ie. one of these message:

- GetBlockBodies
- GetBlockHeaders
- GetNodeData
- GetPooledTransactions
- GetReceipts
- GetStableHeaders

### Stable header requester

Monitor the stream that generates `PeerAction.MessageToPeer` for asking stable headers when the following messages are received:

- `PeerEvent.MessageFromPeer` received with a message of type `OBFT1.NewBlock`
- `PeerEvent.PeerHandshakeSuccessful`

### Block gossip

Monitor the stream that handle `PeerEvent.MessageFromPeer` received with a message of type `OBFT1.NewBlock` and gossip them to other peers.

### Consensus

Monitor the stream that handles `ObftBlock` received from the network.

### Transactions handler

Monitor the stream that is responsible for:

- requesting missing transactions
- importing transactions received from other peers
- exchange pool of transactions during a handshake with another peer

### Seeker

Monitor the stream that is responsible for fetching a branch by downloading blocks in order to synchronize the node.

## Metrics

Multiple metrics are displayed in Grafana for each stream

### Rate

Formula: `rate(sc_evm_fs2_stream_item_count_total{namespace="$namespace",stream_name="<stream_name>"}[30s])`

This formula shows the rate of received messages during the specified time range.

### Buffered item

Formula: `sc_evm_fs2_stream_buffered_items{namespace="$namespace",stream_name="<stream_name>"}`

### Event time

Formula: `rate(sc_evm_fs2_stream_item_wait_time_seconds_count{namespace="$namespace",stream_name="<stream_name>"}[30s])/rate(sc_evm_fs2_stream_item_wait_time_seconds_sum{namespace="$namespace",stream_name="<stream_name>"}[30s])`
