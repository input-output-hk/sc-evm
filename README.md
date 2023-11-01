# Proof of Concept EVM Sidechain Client

## Notice

IOG will no longer be updating or maintaining this repository. 

After three months of experimentation with the proof-of-concept EVM sidechain testnet, the team gathered valuable learnings from this experience and this playground. New use cases and functionality were tested, feedback from the community was gathered, and we are now continuing to develop the interoperability solutions in line with the partnerchains framework announced at the Cardano Summit 2023.

All information included in this repository is considered publicly available and is free to be leveraged by developers to fork it to build and experiment with their own EVM sidechain solution. Should you have any questions, please reach out to the team on the [IOG Discord server](https://discord.com/invite/inputoutput).


The proof of concept (POC) EVM sidechain client is not production ready in its current form and use of it is entirely at
your own risk as this repository is not maintained. Please see [DISCLAIMER](./DISCLAIMER.md) for more information.

## Description

The Proof-of-Concept EVM Sidechain client is the sidechain client component described in the first version of the
sidechain toolkit specification. Written in Scala, the client runs as a sidechain to a Cardano network based on
Ouroboros-BFT.

### Documentation

Some documentation is available in the [docs](./docs) folder.

The technical specification is [here](./TECHNICAL_SPECIFICATION_v1.1.pdf).

## Next steps

If you decide to investigate further, or add your own development, start with
the [development instructions](./DEVELOPMENT.md) and go from there

## Known issues
There are two known issues with the trustless sidechain as released.
### Issue #46 CTL warnings regarding certain definitions

**Description**  
Several warnings from CTL like the following appear:
```bash
16/16 UserDefinedWarning] test/Test/MerkleRootChaining.purs:189:1

  189  testScenario2 ∷ PlutipTest
       ^^^^^^^^^^^^^^^^^^^^^^^^^^
  
  A custom warning occurred while solving type class constraints:
  
    This function returns only one `PaymentPubKeyHash` even in case multiple `PaymentPubKeysHash`es are available. Use `ownPaymentPubKeysHashes` instead
```
**Conclusion**  
Impact: zero  
Severity: low  
Essentially, there are multi-address wallets on Cardano (Eternl is the big one). These will essentially create a new address for every transaction; any 'change' UTXOs will be sent to a new address. This means that you can't necessarily tie multiple transactions to the same wallet via payment public key hash (PKH). Other wallets (eg Nami) are single-address. With these wallets, a payment PKH is functionally a 1:1 mapping to wallet 'identity'.  
The problem is the assumption that:  

PKH == "wallet identity"  

and "wallet Identity" is a functional approximation for user identity therefore PKH is a functional approximation for user identity.  

But the first statement is false, and thus the conclusion is false. It will be fine for Plutip tests because KeyWallet is single-address, but it will fail if any action on-chain or off-chain falls prey to this.  

In this open-source version of the repository, these warnings have no impact, as the project is not supporting light-wallets yet. If someone decides to implement light wallet integration, multi-address wallets would not work properly.
### Issue #38 distributed set issue
Please note that this issue has been fixed in a later version of this repository.  

**Description**  
It is possible for a malicious user to submit a transaction to make a node in the distributed set unspendable, so this would block people from claiming their sidechain token.  

**How to reproduce**    
An attacker legitimately gets a transaction from the signed Merkle root insertion.  
When the attacker claims their sidechain token, instead of using the off-chain interface to build the transaction, they build their own transaction by constructing it identically to the off-chain transaction, but paying multiple tokens to the distributed set output as well.  
Now, if an honest person wants to claim some sidechain tokens and it happens that they need to consume the distributed set node to which the attacker paid multiple extra tokens, the honest person will not be able to spend that node because Plutus must decode the entire ScriptContext before doing any of the actual logic that takes up extra ExUnits. Hence, provided the attacker has paid sufficient extra tokens to the UTXO, the honest node will not be able to spend the output since the validator will spend all of its ExUnits trying to parse through the ScriptContext (and failing).  

**Expected behaviour**  
The above should be impossible. A way to fix this would be to verify on-chain that the values at the UTXOs in the distributed set are 'relatively small'.  
  
**Conclusion**  
Impact: low  
Severity: low  
A malicious user could potentially block random token claims (but be unable to target them). The incentive to do this attack is low, because:  
- the attack cannot be targeted
- high cost of initiating the attack
- cannot actually gain funds or release them (cannot be used for ransoming)
- as the sidechain grows, the chances of a successful attack decrease.  

To initiate this attack, a malicious user would have to:  
1. Actively modify the off-chain code, to include sidechain tokens into the distributed set element.  
2. Mint enough sidechain tokens.   
2. Issue a claim of their own tokens, and use this transaction to put sidechain tokens into the distributed set element UTXO.  

