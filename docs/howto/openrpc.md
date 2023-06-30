# Accessing OpenRPC documentation

Setting `sc-evm.network.rpc.http.openrpc-specification-enabled = true` will cause the node to expose two new functionalities:

- JsonRPC method `rpc.discover` that exposes OpenRPC schemas for all endpoints, serialized as JSON
- HTTP endpoint `openrpc/docs` that redirects to an OpenRPC playground that lets you browse and call
  all the endpoints exposed by `rpc.discover`. The specific url is set via `openrpc-schema-url`.

_Note_: All endpoints except `healthcheck` are unavailable during the stable branch sync process.
This step can be skipped by setting `start-sync = false` if not needed.
