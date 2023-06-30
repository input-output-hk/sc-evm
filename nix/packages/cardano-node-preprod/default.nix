{
  inputs,
  pkgs,
  ...
}: let
  cardano-node-preprod = inputs.cardano-node.packages.${pkgs.system}."preprod/node";
in
  pkgs.writeScriptBin "cardano-node-preprod" ''
    mkdir -p "$PRJ_DATA_DIR"
    cd "$PRJ_DATA_DIR" || exit 1

    exec ${cardano-node-preprod}/bin/cardano-node-preprod
  ''
