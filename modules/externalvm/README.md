# external-vm module

The module contains client side implementations for IELE and KEVM virtual machines.

```
RPC call or transaction execution -> (`VM interface`) KIELEClient -> kevm-msg.proto protocol over TCP/IP -> KEVM virtual machine
RPC call or transaction execution -> (`VM interface`) IELEClient  -> iele-msg.proto protocol over TCP/IP -> IELE virtual machine
```

You can find a similar module in SC EVM project, where the flow was slightly different:

```
RPC call or transaction execution -> `ExternalVM` -> 'generic' msg.proto protocol over TCP/IP -> KEVM or KEVM virtual machine
```

I believe the original idea was to have a common interface for both IELE and KEVM virtual machines, having a single `ExternalVM` and msg.proto definition.
However, the protocol was polluted with IELE and KEVM specific messages and the implementation was switch-casing on the
specific VM type. Moreover, KEVM (execution side) used version 2.2 of the protocol, while IELE was using version 3.0,
and they are _not binary compatible_. Since we need to support both VMs simultaneously we had to separate the clients,
which resulted in some code duplication.

### Configuration

`base.conf` contains `vm` section with the following parameters:

```
vm {
  # internal | kevm | iele | test-mode
  vm-type = "internal"

  kevm = { host = "0.0.0.0", port = 8888, proto-api-version = "2.2" }
  iele = { host = "0.0.0.0", port = 8888, proto-api-version = "3.0" }

  # Start the default internal EVM in a separate thread and use at as an external VM.
  # Can be used for testing purposes and troubleshooting.
  test-mode = { host = "0.0.0.0", port = 8888 }
}
```

`kevm` and `iele` clients connect to a running instance of the VM.
This is useful for local development, but in 'production' and testing environments an instance of the VM should be started
in a separate process on the same node.

### Test mode

There is an `io.iohk.scevm.extvm.testevm.VmServerApp` that can be started with `vm-type=test-mode`.
It can be used to emulate an external vm or test socket communication.
Note: there is no guarantee it works as expected. Keeping it here for reference.
