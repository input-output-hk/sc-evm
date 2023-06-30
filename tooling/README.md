## SC-EVM-CLI

### Usage

Run time dependencies:
- [Java](http://www.java.com/getjava/) 14.20+ installed locally.
- [Node.js](https://nodejs.org/en/download/) LTS.
- Absolute path of CTL executable defined by environment variable `CTL_EXECUTABLE` (can also be set via `--ctl-executable`).

To run sc-evm-cli,

- using Sbt

  ```bash
  sbt "cli/run <subcommand> <arguments...>"
  ```

- using the binary `sc-evm-cli` (`sc-evm-cli.bat`)

  ```bash
  ./sc-evm-cli <subcommand> <arguments...>
  ```

  Hint: the script can be packaged using the command `sbt stage` (provided by [sbt-native-packager](https://www.scala-sbt.org/sbt-native-packager/gettingstarted.html#your-first-package))

To see available commands and how to use a specific command (arguments, options and flags), execute

```
sbt "cli/run --help"
```

```
sbt "cli/run <command> --help"
```

### execution-bench

sbt-jmh benchmarks of the EVM module.

```bash
sbt "executionBench/jmh:run -i 1 -wi 0 -f1 -t1 .*EVMBench.*"
```

> -i
>
> Number of measurement iterations to do. Measurement iterations are counted towards the benchmark score. (default: 1 for SingleShotTime, and 5 for all other

> -wi
>
> Number of warmup iterations to do. Warmup iterations are not counted towards the benchmark score. (default: 0 for SingleShotTime, and 5 for all other modes)

> -f
>
> How many times to fork a single benchmark. Use 0 to disable forking altogether. Warning: disabling forking may have detrimental impact on benchmark and infrastructure reliability, you might want to use a different warmup mode instead. (default:5)

> -t
>
> Number of worker threads to run with. 'max' means the maximum number of hardware threads available on the machine, figured out by JMH itself. (default: 1)

Full documentation is available on the [page](https://github.com/sbt/sbt-jmh).

To get help:

```bash
sbt "executionBench/jmh:run -h"
```

### Transform 'inspect_getChain' from JSON to dot

```commandline
scala-cli ./jsonToDot.scala -- file.json
```
