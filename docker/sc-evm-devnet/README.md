# Local devnet

## How-to

### 1. Build the image

`./docker/sc-evm-devnet/build.sh`

### 2. Setting permissions for the bind mount

All the nodes started by docker-compose will have persistent logs and database storage, located in `sc-evm-instances/` subdirectories.

For the SC EVM client to write to the mounted directory, you might have to change the permissions of the folder on your host machine. The easiest (but probably not the safest way) to do that is by running:

```commandline
sudo chmod -R 777 ./docker/sc-evm-devnet/sc-evm-instances/sc-evm1
sudo chmod -R 777 ./docker/sc-evm-devnet/sc-evm-instances/sc-evm2
sudo chmod -R 777 ./docker/sc-evm-devnet/sc-evm-instances/sc-evm3
sudo chmod -R 777 ./docker/sc-evm-devnet/sc-evm-instances/passive1
sudo chmod -R 777 ./docker/sc-evm-devnet/sc-evm-instances/passive_leaf1
```

### 3. Run docker-compose.yml

`docker-compose.yml` sets up 3 SC EVM validator nodes, together with Grafana, Tempo, Prometheus, and an Opentelemetry-collector.

The validator nodes are all connected to each other and can report metrics and traces.

To enable metrics, set `METRICS_ENABLED=true` per SC EVM node.

To enable traces, set `TRACING_ENABLED=true` per SC EVM node.

To start the setup:

`docker-compose -f docker/sc-evm-devnet/docker-compose.yml up`

### 4. docker-compose-passive.yml

This docker-compose extends the network with passive nodes.

The node `passive1` is a passive node directly connected to all validators.

The node `passive_leaf1` is a passive node too, but only connected to `passive1`. This setup ensures that information is correctly communicated through `passive1`.

To start the passive nodes:

`docker-compose -f docker/sc-evm-devnet/docker-compose-passive.yml up`

It is possible to start a specific passive node instance with:

`docker-compose -f docker/sc-evm-devnet/docker-compose-passive.yml up passive1`

or

`docker-compose -f docker/sc-evm-devnet/docker-compose-passive.yml up passive_leaf1`

### Stopping the network

For validator nodes:

`docker-compose -f docker/sc-evm-devnet/docker-compose.yml down`

For passive nodes:

`docker-compose -f docker/sc-evm-devnet/docker-compose-passive.yml down`

### Clean data dirs

`sudo ./docker/sc-evm-devnet/clean.sh`

## The setup

The `docker-compose` config will create three nodes with private keys provided in `/sc-evm-instances/sc-evmX/sc-evm-devnet/node.key` so the nodes will always have the same `enode` id. The directory containing the key is a bind mount, and all data generated by the client will be stored in it. This is useful to eg. check the log files or spin up another client with the same data/state.

The `docker-compose-passive` config will create two passive nodes with private keys provided in `/sc-evm-instances/passive*/sc-evm-devnet/node.key`. This allows the `passive_leaf1` node to connect to the `passive1` one.

## Customizing

It is possible to change the log level by customizing the environment variables `SC_EVM_LOGLEVEL` and `SC_EVM_AKKA_LOGLEVEL` in `docker-compose.yml` and `docker-compose-passive.yml`.

Ports and the location of the data directory can also be customized, but this should usually not be necessary.

# Passive node

You can dynamically create a passive node to connect to the network with the script:

```
./run-passive.sh
```

This script starts a passive node that uses all validators as bootstrap nodes.

By default, it does not use the start sync algorithm. You can activate it with:

```
START_SYNC=true ./run-passive.sh
```

Provided that you already have a running `passive1` instance, it is also possible to start a passive leaf node with:

`./run-passive-leaf.sh`

This script starts a passive node that uses the `passive1` node as the only bootstrap.

## Adding a funded account

In a local setup, the genesis file can be manipulated to contain new funded accounts.

1. Start the docker-compose setup.
2. Create a new account by calling the `personal_newAccount` endpoint.

```commandline
curl --request POST \
  --url http://127.0.0.1:8546/ \
  --header 'Content-Type: application/json' \
  --data '{
	"jsonrpc": "2.0",
  "method": "personal_newAccount",
  "params": ["<password>"],
  "id": 1
}'
```

3. Edit the file [sc-evm-devnet-genesis.json](modules/node/src/main/resources/conf/chains/sc-evm-devnet-genesis.json) by adding the newly
   created account address in the `alloc` mapping, together with a balance, as done for the other accounts.
4. For each node that is part of the sc-evm-devnet network in `docker/sc-evm-devnet/sc-evm-instances` delete the rocksdb folder.
5. Generate a new docker image with the configuration modification by running `./docker/sc-evm-devnet/build.sh`.
   There seems to be an issue with docker images generation when only conf files
   have been modified. Remember to delete the previous SC EVM docker image before generating the new one.
6. Start the docker-compose again
7. Check that your account has funds by calling the `eth_getBalance` endpoint

```commandline
curl --request POST \
--url http://127.0.0.1:8546/ \
--header 'Content-Type: application/json' \
--data '{
"jsonrpc": "2.0",
"method": "eth_getBalance",
"params": ["<address>", "latest"],
"id": 1
}'
```

# Grafana

Grafana will be available at `http://localhost:3000` (using user and password: admin and admin) with a simple dashboard available.

[Topology overview](../../docs/explain/diagrams/docker-monitoring-topology.plantuml)