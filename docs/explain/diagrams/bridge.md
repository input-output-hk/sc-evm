## Flow: how to move SC_TOKEN from Side Chain to Main Chain.

```mermaid
sequenceDiagram
  autonumber
  actor Alice
  participant LC1 as SC node 1
  participant LC2 as SC node 2
  participant SCBSC as SC Bridge Smart Contract
  participant MCBSC as MC Bridge Smart Contract

  Alice->>SCBSC: lock tokens(amount: 10, address:0x1)
  LC1->>SCBSC: get transactions batch(sc_epoch)
  SCBSC->>LC1: transaction batch
  LC2->>SCBSC: get transactions batch(sc_epoch)
  SCBSC->>LC2: transaction batch
  Note over LC1,LC2: Nodes one after another signs sidechain certificate
  LC1->>SCBSC: sign(sc_epoch, sig(next_committee_hash), sig(mptRoot(txBatch)))
  LC2->>SCBSC: sign(sc_epoch, sig(next_committee_hash), sig(mptRoot(txBatch)))
  par
    Note over LC1,MCBSC: Only one node has to send it, but all can try (and should) assuming that there will be some gratification for the winner
    Note over LC1,MCBSC: It might be required to break this call down into two due to plutus limits
    LC1->>SCBSC: get_sidechain_certificate(sc_epoch)
    LC1->>MCBSC: submit_certificate(sc_epoch, next_committee, sig(next_committee_hash), mptRoot(txBatch), sig(mptRoot(txBatch)))
  and
    LC2->>SCBSC: get_sidechain_certificate(sc_epoch)
    LC2->>MCBSC: submit_certificate(sc_epoch, next_committee, sig(next_committee_hash), mptRoot(txBatch), sig(mptRoot(txBatch)))
  end
  Alice->>MCBSC: Claim 10 tokens
```

1\. User Alice wants to move 10 SC_TOKEN to Main Chain

2\. & 3. SC Node 1 requests transactions that should be moved to Main Chain

6\. SC Node 1 puts end of epoch data (committee proof, transactions proof) to the ledger

8\. SC Node 1 fetches end of epoch data from Side Chain ledger

9\. SC Node 1 posts end of epoch data to Main Chain, triggers MC to verify posted certificate

12\. User Alice claims 10 native token SC_TOKEN

## Flow: how to reward epoch validator

```mermaid
sequenceDiagram
  autonumber
  actor Alice
  participant LC1 as SC node 1
  participant LC2 as SC node 2
  participant SCBSC as SC Bridge Smart Contract
  participant MCBSC as MC Bridge Smart Contract
  Note over LC1,LC2: Nodes one after another signs sidechain certificate
  LC1->>SCBSC: sign(sc_epoch, sig(next_committee_hash), sig(mptRoot(txBatch)))
  LC2->>SCBSC: sign(sc_epoch, sig(next_committee_hash), sig(mptRoot(txBatch)))

  par
    Note over LC1,MCBSC: Only one node has to send it, but all can try (and should) assuming that there will be some gratification for the winner
    Note over LC1,MCBSC: It might be required to break this call down into two due to plutus limits
    LC1->>SCBSC: get_sidechain_certificate(sc_epoch)
    LC1->>MCBSC: submit_certificate(sc_epoch, next_committee, sig(next_committee_hash), mptRoot(txBatch), sig(mptRoot(txBatch)))
  and
    LC2->>SCBSC: get_sidechain_certificate(sc_epoch)
    LC2->>MCBSC: submit_certificate(sc_epoch, next_committee, sig(next_committee_hash), mptRoot(txBatch), sig(mptRoot(txBatch)))
  end

  par
    LC1--)MCBSC:observe storeCert executed
  and
    LC2--)MCBSC:observe storeCert executed
  end


  Note over SCBSC, LC1: other nodes validate that transaction against what they observed on MC
  LC1 ->> SCBSC: unlock rewards for epoch
```

1\. SC Node 1 puts end of epoch data (committee proof, transactions proof) to the ledger

3\. SC Node 1 fetches end of epoch data from Side Chain ledger

4\. SC Node 1 posts end of epoch data to Main Chain, triggers MC to verify posted certificate

7\. SC Node 1 observes MC verifying the posted end of epoch certificates

9\. Previous epoch nodes are awarded SC_TOKEN accordingly to blocks produced in the previous Side Chain epoch
