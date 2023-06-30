DO $$
DECLARE
 reg_tx_id integer   := 0;
 dereg_tx_id integer := 1;
 consumed_tx_id integer  := 4;
 reg_tx_id2 integer  := 5;
 script_addr text    := 'script_addr';
 -- those hashes are not really important but putting them in variables help to make the data more readable
 hash1 hash32type := decode('ABEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1','hex');
 hash2 hash32type := decode('BBEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1','hex');
 hash3 hash32type := decode('CBEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1','hex');
 hash4 hash32type := decode('DBEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1','hex');
 hash5 hash32type := decode('0BEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1','hex');
 hash6 hash32type := decode('01EED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1','hex');
 consumed_tx_hash  hash32type := decode('cdefe62b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13','hex');
 registration jsonb := '{
    "constructor": 0,
    "fields": [
      { "bytes": "bfbee74ab533f40979101057f96de62e95233f2a5216eb16b54106f09fd7350d" },
      { "bytes": "02fe8d1eb1bcb3432b1db5833ff5f2226d9cb5e65cee430558c18ed3a3c86ce1af" },
      { "bytes": "28d1c3b7df297a60d24a3f88bc53d7029a8af35e8dd876764fd9e7a24203a3482a98263cc8ba2ddc7dc8e7faea31c2e7bad1f00e28c43bc863503e3172dc6b0a" },
      { "bytes": "f8ec6c7f935d387aaa1693b3bf338cbb8f53013da8a5a234f9c488bacac01af259297e69aee0df27f553c0a1164df827d016125c16af93c99be2c19f36d2f66e" },
      {
        "fields": [
          {
            "constructor": 0,
            "fields": [ { "bytes": "cdefe62b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13"} ]
          },
          { "int": 1 }
        ],
        "constructor": 0
      }
    ]
  }';
 -- this is identical to the previous one but with the public sidechain key modified
 duplicated_registration jsonb := '{
    "constructor": 0,
    "fields": [
      { "bytes": "bfbee74ab533f40979101057f96de62e95233f2a5216eb16b54106f09fd7350d" },
      { "bytes": "02002d1eb1bcb3432b1db5833ff5f2226d9cb5e65cee430558c18ed3a3c86ce1af" },
      { "bytes": "f8ec6c7f935d387aaa1693b3bf338cbb8f53013da8a5a234f9c488bacac01af259297e69aee0df27f553c0a1164df827d016125c16af93c99be2c19f36d2f66e" },
      { "bytes": "28d1c3b7df297a60d24a3f88bc53d7029a8af35e8dd876764fd9e7a24203a3482a98263cc8ba2ddc7dc8e7faea31c2e7bad1f00e28c43bc863503e3172dc6b0a" },
      {
        "fields": [
          { "bytes": "cdefe62b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13"},
          { "int": 0 }
        ],
        "constructor": 0
      }
    ]
  }';
 registration_not_spo jsonb := '{
    "constructor": 0,
    "fields": [
      { "bytes": "cfbee74ab533f40979101057f96de62e95233f2a5216eb16b54106f09fd7350d" },
      { "bytes": "02fe8d1eb1bcb3432b1db5833ff5f2226d9cb5e65cee430558c18ed3a3c86ce1af" },
      { "bytes": "28d1c3b7df297a60d24a3f88bc53d7029a8af35e8dd876764fd9e7a24203a3482a98263cc8ba2ddc7dc8e7faea31c2e7bad1f00e28c43bc863503e3172dc6b0a" },
      { "bytes": "f8ec6c7f935d387aaa1693b3bf338cbb8f53013da8a5a234f9c488bacac01af259297e69aee0df27f553c0a1164df827d016125c16af93c99be2c19f36d2f66e" },
      {
        "fields": [
          {
            "constructor": 0,
            "fields": [ { "bytes": "cdefe62b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13"} ]
          },
          { "int": 1 }
        ],
        "constructor": 0
      }
    ]
  }';
  registration2 jsonb := '{
    "constructor": 0,
    "fields": [
      { "bytes": "3fd6618bfcb8d964f44beba4280bd91c6e87ac5bca4aa1c8f1cde9e85352660b" },
      { "bytes": "02333e47cab242fefe88d7da1caa713307290291897f100efb911672d317147f72" },
      { "bytes": "1fd2f1e5ad14c829c7359474764701cd74ab9c433c29b0bbafaa6bcf22376e9d651391d08ae6f40b418d2abf827c4c1fcb007e779a2beba7894d68012942c708" },
      { "bytes": "3e8a8b29e513a08d0a66e22422a1a85d1bf409987f30a8c6fcab85ba38a85d0d27793df7e7fb63ace12203b062feb7edb5e6664ac1810b94c38182acc6167425" },
      {
        "fields": [
          {
            "constructor": 0,
            "fields": [ { "bytes": "cdefe62b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13"} ]
          },
          { "int": 2 }
        ],
        "constructor": 0
      }
    ]
  }';
    registration3 jsonb := '{
      "constructor": 0,
      "fields": [
        { "bytes": "3fd6618bfcb8d964f44beba4280bd91c6e87ac5bca4aa1c8f1cde9e85352660b" },
        { "bytes": "02333e47cab242fefe88d7da1caa713307290291897f100efb911672d317147f72" },
        { "bytes": "1fd2f1e5ad14c829c7359474764701cd74ab9c433c29b0bbafaa6bcf22376e9d651391d08ae6f40b418d2abf827c4c1fcb007e779a2beba7894d68012942c708" },
        { "bytes": "3e8a8b29e513a08d0a66e22422a1a85d1bf409987f30a8c6fcab85ba38a85d0d27793df7e7fb63ace12203b062feb7edb5e6664ac1810b94c38182acc6167425" },
        {
          "fields": [
            {
              "constructor": 0,
              "fields": [ { "bytes": "bfbee74ab533f40979101057f96de62e95233f2a5216eb16b54106f09fd7350d"} ]
            },
            { "int": 2 }
          ],
          "constructor": 0
        }
      ]
    }';
BEGIN

-- one SPO registers during block id 0 (epoch 189)
-- it deregisters during block id 2 (epoch 190)
-- A second SPO registers during block id 2 (epoch 190)
INSERT INTO tx ( id            , hash            , block_id, block_index, out_sum, fee, deposit, size, invalid_before, invalid_hereafter, valid_contract, script_size )
    VALUES     ( consumed_tx_id, consumed_tx_hash, 0       , 0          , 0      , 0  , 0      , 1024, NULL          , NULL             , TRUE          , 1024        )
              ,( reg_tx_id     , hash1           , 0       , 1          , 0      , 0  , 0      , 1024, NULL          , NULL             , TRUE          , 1024        )
              ,( dereg_tx_id   , hash2           , 2       , 0          , 0      , 0  , 0      , 1024, NULL          , NULL             , TRUE          , 1024        )
              ,( reg_tx_id2    , hash5           , 2       , 1          , 0      , 0  , 0      , 1024, NULL          , NULL             , TRUE          , 1024        )
;


-- the transaction reg_tx_id has 2 outputs
-- the second output (index 1) is consumed by dereg_tx_id
INSERT INTO tx_out ( id, tx_id           , index, address     , address_raw, address_has_script, payment_cred, stake_address_id, value, data_hash )
            VALUES ( 0 , consumed_tx_id  , 0    , 'other_addr', ''         , TRUE              , NULL        , NULL            , 0    , NULL      )
                  ,( 1 , consumed_tx_id  , 1    , 'other_addr', ''         , TRUE              , NULL        , NULL            , 0    , NULL      )
                  ,( 2 , consumed_tx_id  , 2    , 'other_addr', ''         , TRUE              , NULL        , NULL            , 0    , NULL      )
                  ,( 3 , reg_tx_id       , 0    , script_addr , ''         , TRUE              , NULL        , NULL            , 0    , hash4     ) -- good registration but overriden by the next
                  ,( 4 , reg_tx_id       , 1    , script_addr , ''         , TRUE              , NULL        , NULL            , 0    , hash1     ) -- good registration
                  ,( 5 , reg_tx_id       , 2    , script_addr , ''         , TRUE              , NULL        , NULL            , 0    , hash2     ) -- wrong format
                  ,( 6 , reg_tx_id       , 3    , script_addr , ''         , TRUE              , NULL        , NULL            , 0    , hash3     ) -- formatted properly but not an spo
                  ,( 7 , dereg_tx_id     , 0    , 'other_addr', ''         , TRUE              , NULL        , NULL            , 0    , hash1     )
                  ,( 8 , reg_tx_id2      , 0    , script_addr , ''         , TRUE              , NULL        , NULL            , 0    , hash5     )
                  ,( 9 , reg_tx_id2      , 1    , script_addr , ''         , TRUE              , NULL        , NULL            , 0    , hash6     ) -- formatted properly but did not consumed advertisedhash
;

INSERT INTO datum ( id, hash , tx_id     , value                                            )
           VALUES ( 0 , hash1, reg_tx_id , registration                                     )
                 ,( 1 , hash2, reg_tx_id , '{ "constructor": 0, "fields": [{ "int": 1 }] }' ) -- this transaction has the wrong payload
                 ,( 2 , hash3, reg_tx_id , registration_not_spo                             )
                 ,( 3 , hash4, reg_tx_id , duplicated_registration                          )
                 ,( 4 , hash5, reg_tx_id2, registration2                                    )
                 ,( 5 , hash6, reg_tx_id2, registration3                                    )
;

INSERT INTO tx_in ( id, tx_in_id   , tx_out_id, tx_out_index, redeemer_id )
           VALUES ( 0 , reg_tx_id  , consumed_tx_id, 0      , NULL   )
                 ,( 1 , reg_tx_id  , consumed_tx_id, 1      , NULL   )
                 ,( 2 , reg_tx_id2 , consumed_tx_id, 2      , NULL   )
                 ,( 4 , dereg_tx_id, reg_tx_id     , 0      , NULL   )
                 ,( 5 , dereg_tx_id, reg_tx_id     , 1      , NULL   )
;
END $$;

-- Following section inserts dummy transaction to test getting utxos
DO $$
DECLARE
 tx1id integer   := 1001;
 tx2id integer   := 1002;
 owner_addr text := 'get_utxo_test_address';
 -- those hashes are not really important but putting them in variables help to make the data more readable
 hash1 hash32type := decode('0000000000000000000000000000000000000000000000000000000000001001','hex');
 hash2 hash32type := decode('0000000000000000000000000000000000000000000000000000000000001002','hex');
BEGIN

-- some UTXOs are created during block id 0 (epoch 189)
-- one is consumed and one is created during block 2 (epoch 190)
INSERT INTO tx ( id         , hash , block_id, block_index, out_sum, fee, deposit, size, invalid_before, invalid_hereafter, valid_contract, script_size )
    VALUES     ( tx1id      , hash1, 0       , 3          , 0      , 0  , 0      , 1024, NULL          , NULL             , TRUE          , 1024        )
              ,( tx2id      , hash2, 2       , 2          , 0      , 0  , 0      , 1024, NULL          , NULL             , TRUE          , 1024        )
;


INSERT INTO tx_out ( id  , tx_id, index, address     , address_raw, address_has_script, payment_cred, stake_address_id, value, data_hash )
            VALUES ( 1000, tx1id, 0    , owner_addr      , ''         , TRUE       , NULL               , NULL            , 0    , NULL      )
                  ,( 1001, tx1id, 1    , owner_addr      , ''         , TRUE       , NULL               , NULL            , 0    , NULL      )
                  ,( 1002, tx1id, 2    , owner_addr      , ''         , TRUE       , NULL               , NULL            , 0    , hash1     )
                  ,( 1003, tx1id, 3    , owner_addr      , ''         , TRUE       , NULL               , NULL            , 0    , hash2     )
                  ,( 1004, tx2id, 0    , owner_addr      , ''         , TRUE       , NULL               , NULL            , 0    , hash2     )
;

INSERT INTO datum ( id, hash , tx_id    , value                                            )
           VALUES ( 1000 , hash1, tx1id, '{ "constructor": 0, "fields": [{ "int": 42 }] }')
                 ,( 1001 , hash2, tx1id, '{ "constructor": 0, "fields": [{ "int": 1  }] }')
;

INSERT INTO tx_in ( id   , tx_in_id, tx_out_id, tx_out_index, redeemer_id )
           VALUES ( 1000 , tx2id   , tx1id    , 0           , NULL        )
                 ,( 1001 , tx2id   , tx1id    , 1           , NULL        )
;
END $$;


-- Following section inserts a mainchain to sidechain transaction
DO $$
DECLARE
 sender_addr text   := 'sender';
 input_addr  text   := 'input_addr'; -- simulates reminder of input going to other UTxO
 policy_hash hash28type := decode('EE67F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1', 'hex');
 policy hash28type := decode('52424aa2e3243dabef86f064cd3497bc176e1ca51d3d7de836db5571', 'hex');
 asset_name_encoded asset32type := decode('4655454C', 'hex'); -- "FUEL" string bytes

 xc_tx_id1 integer   := 101;
 xc_tx_id2 integer   := 102;
 xc_tx_id3 integer   := 103;
 tx_hash1 hash32type := decode('EBEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1','hex');
 tx_hash2 hash32type := decode('EBEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F2','hex');
 tx_hash3 hash32type := decode('EBEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F3','hex');
 datum_hash1 hash32type := decode('AEDA0790DBEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D','hex');
 datum_hash2 hash32type := decode('AEDA0790DBEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22E','hex');
 datum_hash3 hash32type := decode('AEDA0790DBEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22F','hex');
 -- Algorithm to obtain following values:
 -- val (prvKey, pubKey) = ECDSA.generateKeyPair(new SecureRandom(Array.empty))
 -- val address = Address.fromPublicKey(pubKey).bytes // first field
 -- val signature = prvKey.sign(kec256(address)).toBytes // second field
 reedemer jsonb := '{
    "constructor": 0,
    "fields": [
      { "bytes": "CC95F2A1011728FC8B861B3C9FEEFBB4E7449B98" }
    ]
  }';
  invalid_xc_tx_1_id integer := 201;
  invalid_xc_tx_1_hash hash32type := decode('BBBED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1','hex');
  invalid_xc_tx_1_datum_hash hash32type := decode('BBBA0790DBEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D','hex');
  invalid_xc_tx_1_redeemer jsonb := '{
    "constructor": 1,
    "fields": []
  }';

  invalid_xc_tx_2_id integer := 202;
  invalid_xc_tx_2_hash hash32type := decode('CCCED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1','hex');
  invalid_xc_tx_2_datum_hash hash32type := decode('CCCA0790DBEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D','hex');
  invalid_xc_tx_2_redeemer jsonb := '{
    "constructor": 0,
    "fields": [
      { "bytes": "0000000000042" }
    ]
  }';


BEGIN

INSERT INTO tx ( id                 , hash                 , block_id , block_index , out_sum , fee , deposit , size , invalid_before , invalid_hereafter , valid_contract , script_size )
    VALUES     ( xc_tx_id1          , tx_hash1             , 3        , 0           , 0       , 0   , 0       , 1024 , NULL           , NULL              , TRUE           , 1024        )
              ,( xc_tx_id2          , tx_hash2             , 3        , 1           , 0       , 0   , 0       , 1024 , NULL           , NULL              , TRUE           , 1024        )
              ,( xc_tx_id3          , tx_hash3             , 4        , 0           , 0       , 0   , 0       , 1024 , NULL           , NULL              , TRUE           , 1024        )
              ,( invalid_xc_tx_1_id , invalid_xc_tx_1_hash , 4        , 1           , 0       , 0   , 0       , 1024 , NULL           , NULL              , TRUE           , 1024        )
              ,( invalid_xc_tx_2_id , invalid_xc_tx_2_hash , 5        , 0           , 0       , 0   , 0       , 1024 , NULL           , NULL              , TRUE           , 1024        )
;

INSERT INTO tx_out ( id , tx_id              , index , address     , address_raw , address_has_script , payment_cred , stake_address_id , value , data_hash                  )
            VALUES ( 5  , xc_tx_id1          , 0     , sender_addr , ''          , TRUE               , NULL         , NULL             , 0     , datum_hash1                ) -- MC to SC script address
                  ,( 6  , xc_tx_id1          , 1     , input_addr  , ''          , FALSE              , NULL         , NULL             , 0     , NULL                       ) -- remainder of input goes to sender
                  ,( 7  , xc_tx_id2          , 0     , sender_addr , ''          , TRUE               , NULL         , NULL             , 0     , datum_hash2                ) -- MC to SC script address
                  ,( 8  , xc_tx_id3          , 0     , sender_addr , ''          , TRUE               , NULL         , NULL             , 0     , datum_hash3                ) -- MC to SC script address
                  ,( 9  , invalid_xc_tx_1_id , 0     , sender_addr , ''          , FALSE              , NULL         , NULL             , 0     , invalid_xc_tx_1_datum_hash )
                  ,( 10 , invalid_xc_tx_2_id , 0     , sender_addr , ''          , FALSE              , NULL         , NULL             , 0     , invalid_xc_tx_2_datum_hash )
;

INSERT INTO multi_asset ( id , policy      , name               , fingerprint)
                 VALUES ( 13 , policy      , asset_name_encoded , 'assetFUEL')
;

INSERT INTO ma_tx_mint ( id , quantity , tx_id              , ident )
                VALUES ( 1  , -500     , xc_tx_id1          , 13    )
                      ,( 2  , -500     , xc_tx_id2          , 13    )
                      ,( 3  , -500     , xc_tx_id3          , 13    )
                      ,( 4  , -400     , invalid_xc_tx_1_id , 13    )
                      ,( 5  , -600     , invalid_xc_tx_2_id , 13    )
;

INSERT INTO redeemer_data (id , hash               , tx_id              , value )
           VALUES ( 4 , datum_hash1                , xc_tx_id1          , reedemer )
                 ,( 5 , datum_hash2                , xc_tx_id2          , reedemer )
                 ,( 6 , datum_hash3                , xc_tx_id3          , reedemer )
                 ,( 7 , invalid_xc_tx_1_datum_hash , invalid_xc_tx_1_id , invalid_xc_tx_1_redeemer )
                 ,( 8 , invalid_xc_tx_2_datum_hash , invalid_xc_tx_2_id , invalid_xc_tx_2_redeemer )
;

INSERT INTO redeemer ( id , tx_id              , unit_mem , unit_steps , fee , purpose , index , script_hash , redeemer_data_id )
              VALUES ( 0  , xc_tx_id1          , 0        , 0          , 0   , 'mint'  , 0     , policy_hash , 4        )
                    ,( 1  , xc_tx_id2          , 0        , 0          , 0   , 'mint'  , 0     , policy_hash , 5        )
                    ,( 2  , xc_tx_id3          , 0        , 0          , 0   , 'mint'  , 0     , policy_hash , 6        )
                    ,( 3  , invalid_xc_tx_1_id , 0        , 0          , 0   , 'mint'  , 0     , policy_hash , 7        )
                    ,( 4  , invalid_xc_tx_2_id , 0        , 0          , 0   , 'mint'  , 0     , policy_hash , 8        )
;

END $$;

-- Committee handovers  --
DO $$
DECLARE
 tx1id integer   := 2001;
 tx2id integer   := 2002;
 owner_addr text := 'committee_test_address';
 tx_hash1 hash32type := decode('000000010067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1','hex');
 tx_hash2 hash32type := decode('000000020067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F2','hex');
 datum_hash1 hash32type := decode('3000000000000000000000000000000000000000000000000000000000001003','hex');
 datum_hash2 hash32type := decode('3000000000000000000000000000000000000000000000000000000000001004','hex');
 policy hash28type := decode('636f6d6d697474656586f064cd3497bc176e1ca51d3d7de836db5571', 'hex');
BEGIN

INSERT INTO tx ( id    , hash     , block_id , block_index , out_sum , fee , deposit , size , invalid_before , invalid_hereafter , valid_contract , script_size )
    VALUES     ( tx1id , tx_hash1 , 3        , 2           , 0       , 0   , 0       , 1024 , NULL           , NULL              , TRUE           , 1024        )
              ,( tx2id , tx_hash2 , 4        , 2           , 0       , 0   , 0       , 1024 , NULL           , NULL              , TRUE           , 1024        )
;

INSERT INTO tx_out ( id   , tx_id , index , address    , address_raw , address_has_script , payment_cred , stake_address_id , value , data_hash                  )
            VALUES ( 3005 , tx1id , 0     , owner_addr , ''          , TRUE               , NULL         , NULL             , 0     , datum_hash1                ) -- first committee (consumed)
                  ,( 3006 , tx2id , 0     , owner_addr , ''          , FALSE              , NULL         , NULL             , 0     , datum_hash2                ) -- second committee
;

INSERT INTO tx_in ( id   , tx_in_id , tx_out_id , tx_out_index , redeemer_id )
           VALUES ( 3001 , tx2id    , tx1id     , 0            , NULL        ) -- consume the first committee
;

INSERT INTO multi_asset ( id , policy      , name               , fingerprint     )
                 VALUES ( 15 , policy      , ''                 , 'assetCommittee')
;

INSERT INTO ma_tx_out (id   , quantity , tx_out_id , ident)
            VALUES    (3001 , 1        , 3005      ,  15)
                     ,(3002 , 1        , 3006      ,  15)
;

INSERT INTO datum ( id   , hash        , tx_id , value                                                                                                                          )
           VALUES ( 3001 , datum_hash1 , tx1id , '{"fields": [{"bytes": "ffff2cd23dcd12169df205f8bf659554441885fb39393a82a5e3b13601aa8cab"}, {"int": 1113}], "constructor": 0}' )
                 ,( 3002 , datum_hash2 , tx2id , '{"fields": [{"bytes": "d5462cd23dcd12169df205f8bf659554441885fb39393a82a5e3b13601aa8cab"}, {"int": 1114}], "constructor": 0}' )
;
END $$;

-- Insert one checkpoint NFT  --
DO $$
DECLARE
  tx1_id integer   := 4001;
  owner_addr text := 'checkpoint_test_address';
  tx1_hash hash32type := decode('100000010067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1', 'hex');
  datum1_hash hash32type := decode('4000000000000000000000000000000000000000000000000000000000001003', 'hex');
  checkpoint_nft_policy hash28type := decode('636f6d6d697474656586f064cd3497bc176e1ca51d3d7de836db5572', 'hex');
BEGIN

INSERT INTO tx ( id     , hash     , block_id , block_index , out_sum , fee , deposit , size , invalid_before , invalid_hereafter , valid_contract , script_size )
    VALUES     ( tx1_id , tx1_hash , 5        , 2           , 0       , 0   , 0       , 1024 , NULL           , NULL              , TRUE           , 1024        )
;

INSERT INTO tx_out ( id   , tx_id , index , address    , address_raw , address_has_script , payment_cred , stake_address_id , value , data_hash                  )
            VALUES ( 4005 , tx1_id , 0     , owner_addr , ''          , TRUE               , NULL         , NULL             , 0     , datum1_hash                )
;

INSERT INTO multi_asset ( id , policy               , name      , fingerprint     )
                 VALUES ( 16 , checkpoint_nft_policy, ''        , 'assetCheckpointNft')
;

INSERT INTO ma_tx_out (id   , quantity , tx_out_id , ident)
            VALUES    (4007 , 1        , 4005      ,  16)
;

INSERT INTO datum ( id   , hash        , tx_id , value                                                                                                                          )
           VALUES ( 4007 , datum1_hash , tx1_id , '{"fields": [{"bytes": "abcd2cd23dcd12169df205f8bf659554441885fb39393a82a5e3b13601aa8cab"}, {"int": 667}], "constructor": 0}' )
;
END $$;
