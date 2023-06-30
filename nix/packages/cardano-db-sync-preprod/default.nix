{
  inputs,
  pkgs,
  ...
}: let
  postgres = pkgs.postgresql;
  cardano-db-sync = inputs.cardano-db-sync.apps.${pkgs.system}.cardano-db-sync.program;
  cardano-db-sync-schema = "${inputs.cardano-db-sync.sourceInfo}/schema";
  cardano-config = pkgs.writeTextFile {
    name = "config-preprod.json";
    text = builtins.toJSON inputs.cardano-node.environments.${pkgs.system}.preprod.networkConfig;
  };
  db-sync-config-content = builtins.fromJSON (builtins.readFile config/db-sync-config.json) // {NodeConfigFile = cardano-config;};
  db-sync-config = pkgs.writeTextFile {
    name = "db-sync-config-preprod.json";
    text = builtins.toJSON db-sync-config-content;
  };
in
  pkgs.writeScriptBin "cardano-db-sync-preprod" ''
    : ''${CARDANO_NODE_SOCKET_PATH:?CARDANO_NODE_SOCKET_PATH is not set}
    export STATE_ROOT="''${EXT_DATA_DIR:-$PRJ_DATA_DIR}/db-sync-preprod"
    export PG_SOCKET_PATH="$STATE_ROOT/pg"
    export DATA_DIR="$STATE_ROOT/data"

    ####################################################################
    export POSTGRES_HOST="localhost"
    export POSTGRES_PORT="''${POSTGRES_PORT:-5432}"
    export PGPASSFILE="${config/pgpass}"
    export DATABASE="$STATE_ROOT/cexplorer"

    echo "Using state root directory: $STATE_ROOT"
    mkdir -p $PG_SOCKET_PATH $DATA_DIR/config/custom

    if [[ ! -f "$STATE_ROOT/initialized" ]]; then
      # Create a database with the data stored in the current directory
      ${postgres}/bin/initdb -D $DATABASE --no-locale --encoding=UTF8

      # Start PostgreSQL running as the current user
      # and with the Unix socket in the current directory
      ${postgres}/bin/pg_ctl -D $DATABASE -l $PG_SOCKET_PATH/logfile -o "--unix_socket_directories='$PG_SOCKET_PATH'" start

      echo "Waiting for pg process to finish initialization"
      sleep 3

      export PG_HOST=$PG_SOCKET_PATH
      # Create a database
      #createdb cexplorer
      echo "Creating database..."
      ${postgres}/bin/psql -d postgres -h $PG_SOCKET_PATH -c 'create database cexplorer;'

      echo "Creating user..."
      ${postgres}/bin/psql -d postgres -h $PG_SOCKET_PATH -c "create user postgres with encrypted password 'postgres';"
      echo "Granting permissions..."
      ${postgres}/bin/psql -d postgres -h $PG_SOCKET_PATH -c "grant all privileges on database cexplorer to postgres;"

      touch "$STATE_ROOT/initialized"
    else
      export PG_HOST=$PG_SOCKET_PATH
      echo "Database already created. Skipping creation..."
      ${postgres}/bin/pg_ctl -D $DATABASE -l $PG_SOCKET_PATH/logfile -o "--unix_socket_directories='$PG_SOCKET_PATH'" start
    fi

    trap "${postgres}/bin/pg_ctl -D $DATABASE stop" EXIT

      ${cardano-db-sync} --socket-path $CARDANO_NODE_SOCKET_PATH \
      --state-dir $DATA_DIR/db-sync-state \
      --config ${db-sync-config} \
      --schema-dir ${cardano-db-sync-schema}
  ''
