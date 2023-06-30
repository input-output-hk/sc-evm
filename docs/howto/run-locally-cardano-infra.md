# Running the infrastructure locally

This list shows how to use the local devshells to run a local infra on top of Cardano preprod. The specific scripts used
behind the scenes can be seen in the `nix` folder.

1. Enter the SC_EVM sidechain devshell (in the root level of the repository)
   On Linux: `nix develop '.#sidechain'` (you might need to escape like `'.#sidechain'`)
   On MacOS (intel): `nix develop .#x86_64-darwin.automation.devshells.sidechain`
2. Run `cardano-node-preprod` command to start a preconfigured cardano node.
3. In another sidechain devshell (repeat step 1)run `cardano-db-sync-preprod` command to start a
   preconfigured instance of DB Sync that will connect to your instance of Cardano
   alongside a Postgres database.
4. If everything went well you should be able to query Postgres at `localhost:5432`, database: `cexplorer`
   For some examples queries
   visit: https://github.com/input-output-hk/cardano-db-sync/blob/master/doc/interesting-queries.md
5. In another shell, enter the ctl-infra devshell via `nix develop '.#ctl-infra'` (this is expected for all the
   following commands)
6. Run `ogmios-preprod` to start an instance of ogmios service connected to your local cardano node
7. In another ctl-infra devshell start `kupo-preprod`
8. If everything went well you should be able to interact with sidechain-main-cli client.
