/// ENVVAR
// - CI:                output gas report to file instead of stdout
// - COVERAGE:          enable coverage report
// - ENABLE_GAS_REPORT: enable gas report
// - COMPILE_MODE:      production modes enables optimizations (default: development)
// - COMPILE_VERSION:   compiler version (default: 0.8.3)
// - COINMARKETCAP:     coinmarkercat api key for USD value in gas report
const { mnemonic } = require("./secrets.json");

const fs = require("fs");
const path = require("path");
const argv = require("yargs/yargs")()
  .env("")
  .options({
    ci: {
      type: "boolean",
      default: false,
    },
    coverage: {
      type: "boolean",
      default: false,
    },
    gas: {
      alias: "enableGasReport",
      type: "boolean",
      default: false,
    },
    mode: {
      alias: "compileMode",
      type: "string",
      choices: ["production", "development"],
      default: "development",
    },
    compiler: {
      alias: "compileVersion",
      type: "string",
      default: "0.8.3",
    },
    coinmarketcap: {
      alias: "coinmarketcapApiKey",
      type: "string",
    },
  }).argv;

require("@nomiclabs/hardhat-truffle5");

if (argv.enableGasReport) {
  require("hardhat-gas-reporter");
}

for (const f of fs.readdirSync(path.join(__dirname, "hardhat"))) {
  require(path.join(__dirname, "hardhat", f));
}

const withOptimizations =
  argv.enableGasReport || argv.compileMode === "production";

/**
 * @type import('hardhat/config').HardhatUserConfig
 */
module.exports = {
  solidity: {
    version: argv.compiler,
    settings: {
      optimizer: {
        enabled: withOptimizations,
        runs: 200,
      },
    },
  },
  networks: {
    hardhat: {
      blockGasLimit: 10000000,
      allowUnlimitedContractSize: !withOptimizations,
    },
    sc_evm_network: {
      blockGasLimit: 8000000,
      url: process.env.SC_EVM_URL || "http://localhost",
      accounts: { mnemonic: mnemonic },
    },
  },
  gasReporter: {
    currency: "USD",
    outputFile: argv.ci ? "gas-report.txt" : undefined,
    coinmarketcap: argv.coinmarketcap,
  },
  mocha: {
    timeout: 180000,
  },
};

if (argv.coverage) {
  require("solidity-coverage");
  module.exports.networks.hardhat.initialBaseFeePerGas = 0;
}