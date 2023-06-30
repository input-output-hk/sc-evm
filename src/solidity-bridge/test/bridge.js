const Bridge = artifacts.require("Bridge");
const BridgeCaller = artifacts.require("BridgeCaller");

const {
  BN,
  constants,
  expectEvent,
  expectRevert,
} = require("@openzeppelin/test-helpers");
const { assert } = require("hardhat");
const { setStorageAt } = require("@nomicfoundation/hardhat-network-helpers");
const { ZERO_ADDRESS } = constants;

const slots = {
  currentEpoch: 0,
  currentSlot: 1,
  phase: 2,
};

const emptySignedData = {
  txsBatchMRH:
    "0x0000000000000000000000000000000000000000000000000000000000000000",
  checkpointBlock: {
    hash: "0x0000000000000000000000000000000000000000000000000000000000000000",
    number: 0,
  },
  outgoingTransactionSignature:
    "0x0000000000000000000000000000000000000000000000000000000000000000",
  checkpointSignature:
    "0x0000000000000000000000000000000000000000000000000000000000000000",
};

const UINT256_MAX =
  "115792089237316195423570985008687907853269984665640564039457584007913129639935";

contract("Contracts", (accounts) => {
  let fee = 5;
  let cardanoWallet =
    "0xcaadf8ad86e61a10f4b4e14d51acf1483dac59c9e90cbca5eda8b840";
  let bridge, bridgeCaller;
  let conversionRate;

  let setContractState;

  let sidechainEth = (amount) => web3.utils.toWei(amount, "ether");
  let mainchainEth = (amount) =>
    (web3.utils.toWei(amount, "ether") / conversionRate).toString();

  const [owner, validator, account1, account2, _] = accounts;

  const IncomingTransactionResult = {
    Success: "0",
    ErrorContractRecipient: "1",
  };

  const EpochPhase = {
    Regular: 0,
    ClosedTransactionBatch: 1,
    Handover: 2,
  };

  beforeEach(async () => {
    bridge = await Bridge.new();

    conversionRate = await bridge.TOKEN_CONVERSION_RATE();

    setContractState = async (epoch, epochPhase) => {
      await setStorageAt(bridge.address, slots.currentEpoch, epoch);
      await setStorageAt(bridge.address, slots.phase, epochPhase);
    };

    bridgeCaller = await BridgeCaller.new();
  });

  it("should NOT lock funds -> 0", async () => {
    await expectRevert(
      bridge.lock(cardanoWallet, { from: account1, value: 0 }),
      "Bridge: amount should be strictly positive"
    );
  });

  it("should NOT lock funds when value is not a multiple of TOKEN_CONVERSION_RATE", async () => {
    await expectRevert(
      bridge.lock(cardanoWallet, {
        from: account1,
        value: conversionRate * 10 + 1,
      }),
      "Bridge: value not multiple of TOKEN_CONVERSION_RATE"
    );
  });

  it("should lock funds", async () => {
    const amount = sidechainEth("2");

    const tx = await bridge.lock(cardanoWallet, {
      from: account1,
      value: amount,
    });

    await expectEvent(tx, "TokensLockedEvent", {
      fromAddr: account1,
      amount: amount,
      mainChainRecipient: cardanoWallet,
    });
  });

  it("should NOT unlock funds -> No Contract, No Proxy!", async () => {
    await expectRevert(
      bridgeCaller.contractCall(
        bridge.address,
        0x00,
        account1,
        sidechainEth("1")
      ),
      "Only direct calls are allowed"
    );
  });

  it("should NOT unlock funds -> Not Coinbase Account!", async () => {
    await expectRevert(
      bridge.unlock(0x00, account1, sidechainEth("1"), { from: owner }),
      "Not Coinbase Account!"
    );
  });

  it("should NOT unlock funds when there are no funds -> Not Enough Funds!", async () => {
    await expectRevert(
      bridge.unlock(0x00, account1, sidechainEth("3"), { from: validator }),
      "Not Enough Funds!"
    );
  });

  it("should NOT unlock funds -> Not Enough Funds!", async () => {
    // lock funds
    const amountToLock = sidechainEth("2");
    await bridge.lock(cardanoWallet, { from: account1, value: amountToLock });

    await expectRevert(
      bridge.unlock(0x00, account1, sidechainEth("3"), { from: validator }),
      "Not Enough Funds!"
    );
  });

  it("should unlock funds", async () => {
    // lock funds
    const amountToLock = sidechainEth("2");
    await bridge.lock(cardanoWallet, { from: account1, value: amountToLock });

    const amount = sidechainEth("1");
    const mainchainAmount = mainchainEth("1");

    const tx = await bridge.unlock(0x00, account1, mainchainAmount, {
      from: validator,
    });

    await expectEvent(tx, "IncomingTransactionHandledEvent", {
      txId: "0x00",
      recipient: account1,
      amount: amount,
      result: IncomingTransactionResult.Success,
    });
  });

  it("should NOT unlock funds but not revert in case of contract", async () => {
    const amount = sidechainEth("2");
    const mainchainAmount = mainchainEth("2");
    const txId = "0x00123456";
    const addressWithCode = bridgeCaller.address;
    await bridge.lock(cardanoWallet, { from: account1, value: amount });

    const balanceBefore = await web3.eth.getBalance(account1);
    const tx = await bridge.unlock(txId, addressWithCode, mainchainAmount, {
      from: validator,
    });

    await expectEvent(tx, "IncomingTransactionHandledEvent", {
      txId,
      recipient: addressWithCode,
      amount: amount,
      result: IncomingTransactionResult.ErrorContractRecipient,
    });
    const balanceAfter = await web3.eth.getBalance(account1);
    assert.equal(balanceAfter, balanceBefore);

    assert.equal(await bridge.getLastProcessedIncomingTransaction(), txId);
  });

  it("should NOT unlock funds -> Processed!", async () => {
    // lock funds
    const amount = sidechainEth("2");
    const mainchainAmount = mainchainEth("2");
    await bridge.lock(cardanoWallet, { from: account1, value: amount });

    await bridge.unlock(0x00, account1, mainchainAmount, { from: validator });

    // lock more funds
    await bridge.lock(cardanoWallet, { from: account1, value: amount });

    await expectRevert(
      bridge.unlock(0x00, account1, mainchainAmount, { from: validator }),
      "This transaction has already been processed"
    );
  });

  it("sign can only be called by the current validator", async () => {
    await expectRevert(
      bridge.sign(
        "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401",
        emptySignedData
      ),
      "Not Coinbase Account!"
    );
  });

  it("sign should emit an event", async () => {
    await setContractState(50, EpochPhase.Handover);
    const tx = await bridge.sign(
      "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401",
      emptySignedData,
      { from: validator }
    );
    await expectEvent(tx, "HandoverSignedEvent", {
      validator: validator,
      signature:
        "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401",
    });
    await expectEvent.notEmitted(tx, "OutgoingTransactionsSignedEvent");
    await expectEvent.notEmitted(tx, "CheckpointBlockSignedEvent");
  });

  it("sign (with merkle root hash) should emit two events", async () => {
    await setContractState(50, EpochPhase.Handover);
    const tx = await bridge.sign(
      "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401",
      {
        ...emptySignedData,
        outgoingTransactionSignature:
          "0x1111111111111111111111111111111111111111111111111111111111111111",
        txsBatchMRH:
          "0x0011223344556677889900112233445566778899001122334455667788990011",
      },
      { from: validator }
    );
    await expectEvent(tx, "HandoverSignedEvent", {
      validator: validator,
      signature:
        "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401",
    });
    await expectEvent(tx, "OutgoingTransactionsSignedEvent", {
      validator: validator,
      signature:
        "0x1111111111111111111111111111111111111111111111111111111111111111",
      merkleRootHash:
        "0x0011223344556677889900112233445566778899001122334455667788990011",
    });
    await expectEvent.notEmitted(tx, "CheckpointBlockSignedEvent");
  });

  it("sign (with checkpoint) should emit two events", async () => {
    await setContractState(50, EpochPhase.Handover);
    const checkpointBlock = {
      hash: "0x0000000000000000000000000000000000000000000000000000000000001234",
      number: 123,
    };
    const tx = await bridge.sign(
      "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401",
      {
        ...emptySignedData,
        checkpointBlock,
        checkpointSignature:
          "0x1111111111111111111111111111111111111111111111111111111111111111",
      },
      { from: validator }
    );
    await expectEvent(tx, "HandoverSignedEvent", {
      validator: validator,
      signature:
        "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401",
    });
    await expectEvent(tx, "CheckpointBlockSignedEvent", {
      validator: validator,
      signature:
        "0x1111111111111111111111111111111111111111111111111111111111111111",
      block: [checkpointBlock.hash, "" + checkpointBlock.number],
    });
    await expectEvent.notEmitted(tx, "OutgoingTransactionsSignedEvent");
  });

  it("sign (with checkpoint and merkle root hash) should emit three events", async () => {
    await setContractState(50, EpochPhase.Handover);
    const checkpointBlock = {
      hash: "0x0000000000000000000000000000000000000000000000000000000000001234",
      number: 123,
    };
    const tx = await bridge.sign(
      "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401",
      {
        outgoingTransactionSignature:
          "0x1111111111111111111111111111111111111111111111111111111111111111",
        txsBatchMRH:
          "0x0011223344556677889900112233445566778899001122334455667788990011",
        checkpointBlock,
        checkpointSignature:
          "0x2222222222222222222222222222222222222222222222222222222222222222",
      },
      { from: validator }
    );
    await expectEvent(tx, "HandoverSignedEvent", {
      validator: validator,
      signature:
        "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401",
    });
    await expectEvent(tx, "OutgoingTransactionsSignedEvent", {
      validator: validator,
      signature:
        "0x1111111111111111111111111111111111111111111111111111111111111111",
      merkleRootHash:
        "0x0011223344556677889900112233445566778899001122334455667788990011",
    });
    await expectEvent(tx, "CheckpointBlockSignedEvent", {
      validator: validator,
      signature:
        "0x2222222222222222222222222222222222222222222222222222222222222222",
      block: [checkpointBlock.hash, "" + checkpointBlock.number],
    });
  });

  it("sign (with merkle root hash) can only be called with one hash in the same epoch", async () => {
    await setContractState(50, EpochPhase.Handover);
    const tx = await bridge.sign(
      "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401",
      {
        ...emptySignedData,
        outgoingTransactionSignature:
          "0x1111111111111111111111111111111111111111111111111111111111111111",
        txsBatchMRH:
          "0x0011223344556677889900112233445566778899001122334455667788990011",
      },
      { from: validator }
    );
    await expectRevert(
      bridge.sign(
        "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401",
        {
          ...emptySignedData,
          outgoingTransactionSignature:
            "0x1111111111111111111111111111111111111111111111111111111111111111",
          txsBatchMRH:
            "0xffffff33445566778899001122334455667788990011223344556677889900ff",
        },
        { from: validator }
      ),
      "sign with different hash within same epoch"
    );
  });

  it("sign (with checkpoint) can only be called with one tuple <hash, number> in the same epoch", async () => {
    await setContractState(50, EpochPhase.Handover);
    const tx = await bridge.sign(
      "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401",
      {
        ...emptySignedData,
        checkpointBlock: {
          hash: "0x0000000000000000000000000000000000000000000000000000000000001234",
          number: 123,
        },
        checkpointSignature:
          "0x1111111111111111111111111111111111111111111111111111111111111111",
      },
      { from: validator }
    );
    await expectRevert(
      bridge.sign(
        "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401",
        {
          ...emptySignedData,
          checkpointBlock: {
            hash: "0x0000000000000000000000000000000000000000000000000000000000001235",
            number: 124,
          },
          checkpointSignature:
            "0x1111111111111111111111111111111111111111111111111111111111111111",
        },
        { from: validator }
      ),
      "sign with different checkpoint within same epoch"
    );
  });

  it("getPreviousTxsBatchMerkleRootChainEntry is zero before and after first sign (with merkle root hash) call", async () => {
    await setContractState(50, EpochPhase.Handover);

    const outgoingTransactionSignature =
      "0x1111111111111111111111111111111111111111111111111111111111111111";
    const signature =
      "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401";
    const zero =
      "0x0000000000000000000000000000000000000000000000000000000000000000";
    const hash1 =
      "0x0011223344556677889900112233445566778899001122334455667788990011";

    const h0 = await bridge.getPreviousTxsBatchMerkleRootChainEntry();
    assert.deepEqual(h0.rootHash, zero);
    assert.deepEqual(h0.epoch, UINT256_MAX);

    const tx1 = await bridge.sign(
      signature,
      {
        ...emptySignedData,
        outgoingTransactionSignature,
        txsBatchMRH: hash1,
      },
      { from: validator }
    );
    const h1 = await bridge.getPreviousTxsBatchMerkleRootChainEntry();
    assert.deepEqual(h1.rootHash, zero);
    assert.deepEqual(h1.epoch, UINT256_MAX);
  });

  it("The merkle chain is properly stored even when there is an epoch with 0 outgoing transactions", async () => {
    await setContractState(50, EpochPhase.Handover);

    const outgoingTransactionSignature =
      "0x1111111111111111111111111111111111111111111111111111111111111111";
    const signature =
      "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401";
    const zero =
      "0x0000000000000000000000000000000000000000000000000000000000000000";
    const hash1 =
      "0x0011223344556677889900112233445566778899001122334455667788990011";

    const h0 = await bridge.getPreviousTxsBatchMerkleRootChainEntry();
    // the epoch for the previous entry is UINT256_MAX as a placeholder for "no previous entry"
    assert.deepEqual(h0.rootHash, zero);
    assert.deepEqual(h0.epoch, UINT256_MAX);

    const tx1 = await bridge.sign(
      signature,
      {
        ...emptySignedData,
        outgoingTransactionSignature,
        txsBatchMRH: hash1,
      },
      { from: validator }
    );

    await setContractState(51, EpochPhase.Handover);

    const entryCurrentBeforeSign =
      await bridge.getPreviousTxsBatchMerkleRootChainEntry();
    // the epoch for the previous entry is now 50
    assert.deepEqual(entryCurrentBeforeSign.rootHash, hash1);
    assert.deepEqual(entryCurrentBeforeSign.epoch, "50");

    const tx2 = await bridge.sign(signature, emptySignedData, {
      from: validator,
    });

    const entryCurrentAfterSign =
      await bridge.getPreviousTxsBatchMerkleRootChainEntry();
    // the previous entry should not be changed by sign
    assert.deepEqual(entryCurrentAfterSign.rootHash, hash1);
    assert.deepEqual(entryCurrentAfterSign.epoch, "50");

    const entryEpoch50 = await bridge.getMerkleRootChainEntry(50);
    assert.deepEqual(entryEpoch50.rootHash, hash1);
    assert.deepEqual(entryEpoch50.previousEntryEpoch, UINT256_MAX);
    const entryEpoch51 = await bridge.getMerkleRootChainEntry(51);
    assert.deepEqual(entryEpoch51.rootHash, zero);
    assert.deepEqual(entryEpoch51.previousEntryEpoch, "50");
  });

  it("getPreviousTxsBatchMerkleRootChainEntry return the last MRH when called for the first time within an epoch", async () => {
    await setContractState(50, EpochPhase.Handover);

    const handoverSignature =
      "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401";
    const outgoingTransactionSignature =
      "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401";
    const zero =
      "0x0000000000000000000000000000000000000000000000000000000000000000";
    const hash1 =
      "0x0011223344556677889900112233445566778899001122334455667788990011";

    const h0 = await bridge.getPreviousTxsBatchMerkleRootChainEntry();
    assert.deepEqual(h0.rootHash, zero);
    assert.deepEqual(h0.epoch, UINT256_MAX);

    const tx1 = await bridge.sign(
      handoverSignature,
      {
        ...emptySignedData,
        outgoingTransactionSignature,
        txsBatchMRH: hash1,
      },
      { from: validator }
    );
    await setContractState(51, EpochPhase.Handover);
    const h1 = await bridge.getPreviousTxsBatchMerkleRootChainEntry();
    assert.deepEqual(h1.rootHash, hash1);
    assert.deepEqual(h1.epoch, "50");
  });

  it("sign (with merkle root hash) should not fail in epoch 0", async () => {
    await setContractState(0, EpochPhase.Handover);
    await bridge.sign(
      "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401",
      {
        ...emptySignedData,
        outgoingTransactionSignature:
          "0x1111111111111111111111111111111111111111111111111111111111111111",
        txsBatchMRH:
          "0x0011223344556677889900112233445566778899001122334455667788990011",
      },
      { from: validator }
    );
  });

  it("sign (with merkle root hash) should not update previousTxsBatchMerkleRootHash when called with current value again", async () => {
    await setContractState(50, EpochPhase.Handover);

    const signature =
      "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401";
    const outgoingTransactionSignature =
      "0x1111111111111111111111111111111111111111111111111111111111111111";
    const hash1 =
      "0x0011223344556677889900112233445566778899001122334455667788990011";
    const hash2 =
      "0x1122334455667788990011223344556677889900112233445566778899001122";

    const tx1 = await bridge.sign(
      signature,
      {
        ...emptySignedData,
        outgoingTransactionSignature,
        txsBatchMRH: hash1,
      },
      { from: validator }
    );
    await setContractState(150, EpochPhase.Handover);
    const tx2a = await bridge.sign(
      signature,
      {
        ...emptySignedData,
        outgoingTransactionSignature,
        txsBatchMRH: hash2,
      },
      { from: validator }
    );
    const tx2b = await bridge.sign(
      signature,
      {
        ...emptySignedData,
        outgoingTransactionSignature,
        txsBatchMRH: hash2,
      },
      { from: validator }
    );

    const h1 = await bridge.getPreviousTxsBatchMerkleRootChainEntry();
    assert.deepEqual(h1.rootHash, hash1);
    assert.deepEqual(h1.epoch, "50");
  });

  it("sign doesn't affect previousTxsBatchMerkleRootHash", async () => {
    await setContractState(50, EpochPhase.Handover);

    const handoverSignature =
      "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401";
    const outgoingTransactionSignature =
      "0x1111111111111111111111111111111111111111111111111111111111111111";
    const zero =
      "0x0000000000000000000000000000000000000000000000000000000000000000";
    const hash1 =
      "0x0011223344556677889900112233445566778899001122334455667788990011";
    const hash2 =
      "0x1122334455667788990011223344556677889900112233445566778899001122";
    const hash3 =
      "0x3322334455667788990011223344556677889900112233445566778899001133";

    const tx1 = await bridge.sign(
      handoverSignature,
      {
        ...emptySignedData,
        outgoingTransactionSignature,
        txsBatchMRH: hash1,
      },
      { from: validator }
    );
    await setContractState(150, EpochPhase.Handover);
    const tx2 = await bridge.sign(
      handoverSignature,
      {
        ...emptySignedData,
        outgoingTransactionSignature,
        txsBatchMRH: hash2,
      },
      { from: validator }
    );
    const h1 = await bridge.getPreviousTxsBatchMerkleRootChainEntry();
    assert.deepEqual(h1.rootHash, hash1);
    assert.deepEqual(h1.epoch, "50");
    await setContractState(250, EpochPhase.Handover);
    const tx4 = await bridge.sign(
      handoverSignature,
      {
        ...emptySignedData,
        outgoingTransactionSignature,
        txsBatchMRH: hash3,
      },
      { from: validator }
    );
    const h2 = await bridge.getPreviousTxsBatchMerkleRootChainEntry();
    assert.deepEqual(h2.rootHash, hash2);
    assert.deepEqual(h2.epoch, "150");
  });

  it("getHandoverSignatures should return the signature for an epoch zero", async () => {
    await setContractState(0, EpochPhase.Regular);

    const signatures = await bridge.getHandoverSignatures(0, [validator]);

    assert.deepEqual(signatures, [], "signatures should be empty");

    await setContractState(0, EpochPhase.Handover);

    await bridge.sign(
      "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401",
      emptySignedData,
      { from: validator }
    );

    const signaturesAfterSigned = await bridge.getHandoverSignatures(0, [
      validator,
    ]);

    assert.deepEqual(
      signaturesAfterSigned,
      [
        [
          validator,
          "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401",
        ],
      ],
      "signatures should contain the signature"
    );
  });

  it(
    "ClosedTransactionBatch phase should close the current batch",
    shouldCloseBatch(EpochPhase.ClosedTransactionBatch)
  );

  it(
    "Handover phase should close the current batch",
    shouldCloseBatch(EpochPhase.Handover)
  );

  it("getHandoverSignatures should return correct signatures for multiple epochs", async () => {
    const signatures = await bridge.getHandoverSignatures(0, [validator]);
    assert.deepEqual(signatures, [], "signatures should be empty");

    await setContractState(0, EpochPhase.Handover);

    await bridge.sign(
      "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95400",
      emptySignedData,
      { from: validator }
    );

    await setContractState(1, EpochPhase.Handover);

    await bridge.sign(
      "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401",
      {
        outgoingTransactionSignature:
          "0x1111111111111111111111111111111111111111111111111111111111111111",
        txsBatchMRH:
          "0x0011223344556677889900112233445566778899001122334455667788990011",
        checkpointSignature:
          "0x2222222222222222222222222222222222222222222222222222222222222222",
        checkpointBlock: {
          hash: "0x0000000000000000000000000000000000000000000000000000000000001234",
          number: 123,
        },
      },
      { from: validator }
    );
    const signaturesAfterSigned = await bridge.getHandoverSignatures(0, [
      validator,
    ]);
    const outgoingTxSignaturesAfterSigned =
      await bridge.getOutgoingTransactionSignatures(0, [validator]);
    const checkpointSignaturesAfterSigned =
      await bridge.getCheckpointSignatures(0, [validator]);

    assert.deepEqual(
      signaturesAfterSigned,
      [
        [
          validator,
          "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95400",
        ],
      ],
      "signatures should contain the signature"
    );

    assert(
      outgoingTxSignaturesAfterSigned.length == 0,
      "outgoing transactions signature should be empty"
    );
    assert(
      checkpointSignaturesAfterSigned.length == 0,
      "checkpoin signature should be empty"
    );

    const handoverSignaturesAfterSigned2 = await bridge.getHandoverSignatures(
      1,
      [validator]
    );
    const outgoingTxSignaturesAfterSigned2 =
      await bridge.getOutgoingTransactionSignatures(1, [validator]);
    const checkpointSignaturesAfterSigned2 =
      await bridge.getCheckpointSignatures(1, [validator]);

    assert.deepEqual(
      handoverSignaturesAfterSigned2,
      [
        [
          validator,
          "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401",
        ],
      ],
      "signatures should contain the signature"
    );
    assert.deepEqual(
      outgoingTxSignaturesAfterSigned2,
      [
        [
          validator,
          "0x1111111111111111111111111111111111111111111111111111111111111111",
        ],
      ],
      "signatures should contain the signature"
    );
    assert.deepEqual(
      checkpointSignaturesAfterSigned2,
      [
        [
          validator,
          "0x2222222222222222222222222222222222222222222222222222222222222222",
        ],
      ],
      "signatures should contain the signature"
    );
  });

  function shouldCloseBatch(phase) {
    return async () => {
      // assume we are in epoch 100
      await setContractState(100, EpochPhase.Regular);

      // the user locks 1 ether
      await bridge.lock("0xa1", { value: sidechainEth("1") });
      await bridge.lock("0xa2", { value: sidechainEth("2") });

      const batch100 = await bridge.outgoingTransactions(100);
      assert.deepEqual(batch100, [
        [mainchainEth("1"), "0xa1", "0", sidechainEth("1")],
        [mainchainEth("2"), "0xa2", "1", sidechainEth("2")],
      ]);

      await setContractState(100, phase);

      await bridge.lock("0xa3", { value: sidechainEth("2") });

      const closedBatch = await bridge.outgoingTransactions(100);

      // The batch was not modified
      assert.deepEqual(
        batch100,
        closedBatch,
        "first batch should not have been modified"
      );

      const batch101 = await bridge.outgoingTransactions(101);
      assert.deepEqual(batch101, [
        [mainchainEth("2"), "0xa3", "0", sidechainEth("2")],
      ]);
    };
  }

  it("getTransactionsMerkleRootHash should return merkleRootHash after it was submitted", async () => {
    await setContractState(50, EpochPhase.Handover);

    const outgoingTransactionSignature =
      "0x1111111111111111111111111111111111111111111111111111111111111111";
    const signature =
      "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401";
    const zero =
      "0x0000000000000000000000000000000000000000000000000000000000000000";
    const hash1 =
      "0x0011223344556677889900112233445566778899001122334455667788990011";

    const h0 = await bridge.getMerkleRootChainEntry(50);
    assert.deepEqual(h0.rootHash, zero);
    assert.equal(h0.previousEntryEpoch, 0);

    const tx1 = await bridge.sign(
      signature,
      {
        ...emptySignedData,
        outgoingTransactionSignature,
        txsBatchMRH: hash1,
      },
      { from: validator }
    );
    const h1 = await bridge.getMerkleRootChainEntry(50);
    assert.deepEqual(h1.rootHash, hash1);
    assert.equal(h0.previousEntryEpoch, 0);
  });

  it("getcheckpointBlock should return checkpoint block after it was submitted", async () => {
    await setContractState(50, EpochPhase.Handover);

    const signature =
      "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401";
    const checkpointBlock = {
      hash: "0x0000000000000000000000000000000000000000000000000000000000001234",
      number: 123,
    };

    const before = await bridge.getCheckpointBlock(50);
    assert.deepEqual(
      before.hash,
      "0x0000000000000000000000000000000000000000000000000000000000000000"
    );
    // for some reason the number is transformed into a string
    assert.deepEqual(+before.number, 0);

    await bridge.sign(
      signature,
      {
        ...emptySignedData,
        checkpointBlock,
        checkpointSignature:
          "0x2222222222222222222222222222222222222222222222222222222222222222",
      },
      { from: validator }
    );
    const after = await bridge.getCheckpointBlock(50);
    assert.deepEqual(after.hash, checkpointBlock.hash);
    assert.deepEqual(+after.number, checkpointBlock.number);
  });

  it("sign (with merkle root hash) should not fail in epoch 0", async () => {
    await setContractState(0, EpochPhase.Handover);
    await bridge.sign(
      "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401",
      {
        ...emptySignedData,
        outgoingTransactionSignature:
          "0x1111111111111111111111111111111111111111111111111111111111111111",
        txsBatchMRH:
          "0x0011223344556677889900112233445566778899001122334455667788990011",
      },
      { from: validator }
    );
  });

  it("getTransactionsMerkleRootHash should return correct MRH for given epoch after multiple signs", async () => {
    await setContractState(50, EpochPhase.Handover);

    const signature =
      "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401";
    const outgoingTransactionSignature =
      "0x1111111111111111111111111111111111111111111111111111111111111111";
    const hash1 =
      "0x0011223344556677889900112233445566778899001122334455667788990011";
    const hash2 =
      "0x1122334455667788990011223344556677889900112233445566778899001122";

    const tx1 = await bridge.sign(
      signature,
      {
        ...emptySignedData,
        outgoingTransactionSignature,
        txsBatchMRH: hash1,
      },
      { from: validator }
    );
    await setContractState(150, EpochPhase.Handover);
    const tx2a = await bridge.sign(
      signature,
      {
        ...emptySignedData,
        outgoingTransactionSignature,
        txsBatchMRH: hash2,
      },
      { from: validator }
    );
    const tx2b = await bridge.sign(
      signature,
      {
        ...emptySignedData,
        outgoingTransactionSignature,
        txsBatchMRH: hash2,
      },
      { from: validator }
    );
    const h1 = await bridge.getMerkleRootChainEntry(50);
    assert.deepEqual(h1.rootHash, hash1);
    assert.deepEqual(h1.previousEntryEpoch, UINT256_MAX);
    const h2 = await bridge.getMerkleRootChainEntry(150);
    assert.deepEqual(h2.rootHash, hash2);
    assert.equal(h2.previousEntryEpoch, 50);
  });

  it("should allow signing one epoch with merkle root and the next one without it", async () => {
    await setContractState(50, EpochPhase.Handover);

    const signature =
      "0xf3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401";
    const outgoingTransactionSignature =
      "0x1111111111111111111111111111111111111111111111111111111111111111";
    const hash1 =
      "0x0011223344556677889900112233445566778899001122334455667788990011";
    const hash2 =
      "0x1122334455667788990011223344556677889900112233445566778899001122";

    const tx1 = await bridge.sign(
      signature,
      {
        ...emptySignedData,
        outgoingTransactionSignature,
        txsBatchMRH: hash1,
      },
      { from: validator }
    );
    await setContractState(51, EpochPhase.Handover);
    const tx2a = await bridge.sign(signature, emptySignedData, {
      from: validator,
    });
    const tx2b = await bridge.sign(signature, emptySignedData, {
      from: validator,
    });

    const h1 = await bridge.getPreviousTxsBatchMerkleRootChainEntry();
    assert.deepEqual(h1.rootHash, hash1);
    assert.deepEqual(h1.epoch, "50");
  });
});
