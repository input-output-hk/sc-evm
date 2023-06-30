-- Merkle roots --
DO $$
DECLARE
 tx1id integer   := 2001;
 tx2id integer   := 2002;
 owner_addr text := 'merkle_root_test_address';
 tx_hash1 hash32type := decode('000000010077F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1','hex');
 tx_hash2 hash32type := decode('000000010077F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F2','hex');
 datum_hash1 hash32type := decode('4000000000000000000000000000000000000000000000000000000000001003','hex');
 datum_hash2 hash32type := decode('4000000000000000000000000000000000000000000000000000000000001004','hex');
 policy hash28type := decode('73eafe87b46e2ae14e6ffa1ddc1525fd8169892a65126a08a732ba0e', 'hex');
 redeemer1 jsonb := '{"fields": [{"bytes": "bdbd8d6191322e4e3a1788e1496d13fbdd9252e319e8e45372f65a068c5c3c2a"}, {"fields": [], "constructor": 1}, {"list": [{"bytes": "0fb3c5700d5b75daad75e4bc57ea96e0f09132812b1d8f33c53d697deaeaefcd5c472f252cb9219ee7cc7528d755b2a825fd00788c333a60de2f264ef00ae53f"}, {"bytes": "312b0b8a5177a21280a6f316896781aa72ef8b6a6236b8fd70bbbe8407e7950805b7e3e583ca3d65071b6ebb5b20c1c408f4acd2006a05117b38f6d6ce019415"}, {"bytes": "53f4991b567497e32d77dfc48ca08f4720f070fe85fe9dfebcf7f4ef85fc1fdc00886aeccd10649e8d57a01708a42980f8d38f09829755b2b0541da727931b73"}]}, {"list": [{"bytes": "0239bdbb177c51cd405659e39439a7fd65bc377c5b24a4684d335518fc5b618a16"}, {"bytes": "02d552ed9b1e49e243baf80603475f88a0dc1cbbdcc5f66afe171c021bb9705340"}, {"bytes": "02de4771f6630b8c6e945a604b3d91a9d266b78fef7c6b6ef72cead11e608e6eb9"}]}], "constructor": 0}';
 redeemer2 jsonb := '{"fields": [{"bytes": "bdbd8d6191322e4e3a1788e1496d13fbdd9252e319e8e45372f65a068c5c3c2b"}, {"fields": [{"bytes": "bdbd8d6191322e4e3a1788e1496d13fbdd9252e319e8e45372f65a068c5c3c2a"}], "constructor": 0}, {"list": [{"bytes": "0fb3c5700d5b75daad75e4bc57ea96e0f09132812b1d8f33c53d697deaeaefcd5c472f252cb9219ee7cc7528d755b2a825fd00788c333a60de2f264ef00ae53f"}, {"bytes": "312b0b8a5177a21280a6f316896781aa72ef8b6a6236b8fd70bbbe8407e7950805b7e3e583ca3d65071b6ebb5b20c1c408f4acd2006a05117b38f6d6ce019415"}, {"bytes": "53f4991b567497e32d77dfc48ca08f4720f070fe85fe9dfebcf7f4ef85fc1fdc00886aeccd10649e8d57a01708a42980f8d38f09829755b2b0541da727931b73"}]}, {"list": [{"bytes": "0239bdbb177c51cd405659e39439a7fd65bc377c5b24a4684d335518fc5b618a16"}, {"bytes": "02d552ed9b1e49e243baf80603475f88a0dc1cbbdcc5f66afe171c021bb9705340"}, {"bytes": "02de4771f6630b8c6e945a604b3d91a9d266b78fef7c6b6ef72cead11e608e6eb9"}]}], "constructor": 0}';
 merkle_root1 asset32type := decode('bdbd8d6191322e4e3a1788e1496d13fbdd9252e319e8e45372f65a068c5c3c2a', 'hex');
 merkle_root2 asset32type := decode('bdbd8d6191322e4e3a1788e1496d13fbdd9252e319e8e45372f65a068c5c3c2b', 'hex');
 merkle_root1id integer := 15;
 merkle_root2id integer := 16;
 mr_redeemer1id integer := 0;
 mr_redeemer2id integer := 1;
BEGIN

INSERT INTO tx ( id    , hash     , block_id , block_index , out_sum , fee , deposit , size , invalid_before , invalid_hereafter , valid_contract , script_size )
    VALUES     ( tx1id , tx_hash1 , 3        , 2           , 0       , 0   , 0       , 1024 , NULL           , NULL              , TRUE           , 1024        )
              ,( tx2id , tx_hash2 , 4        , 2           , 0       , 0   , 0       , 1024 , NULL           , NULL              , TRUE           , 1024        )
;
INSERT INTO tx_out ( id   , tx_id , index , address    , address_raw , address_has_script , payment_cred , stake_address_id , value , data_hash                  )
            VALUES ( 3005 , tx1id , 0     , owner_addr , ''          , TRUE               , NULL         , NULL             , 0     , datum_hash1                )
                  ,( 3006 , tx2id , 0     , owner_addr , ''          , TRUE               , NULL         , NULL             , 0     , datum_hash2                )
;

INSERT INTO multi_asset ( id             , policy      , name            , fingerprint     )
                 VALUES ( merkle_root1id , policy      , merkle_root1    , 'assetMerkleRoot1')
                       ,( merkle_root2id , policy      , merkle_root2    , 'assetMerkleRoot2')
;

INSERT INTO ma_tx_mint (id   , quantity , tx_id , ident)
            VALUES    (3001  , 1        , tx1id ,  merkle_root1id)
                     ,(3002  , 1        , tx2id ,  merkle_root2id)
;

INSERT INTO redeemer_data (id              , hash               , tx_id              , value )
           VALUES         ( mr_redeemer1id , datum_hash1        , tx1id              , redeemer1 )
                         ,( mr_redeemer2id , datum_hash2        , tx2id              , redeemer2 )
;

INSERT INTO redeemer ( id , tx_id              , unit_mem , unit_steps , fee , purpose , index , script_hash , redeemer_data_id )
              VALUES ( 0  , tx1id              , 0        , 0          , 0   , 'mint'  , 0     , policy      , mr_redeemer1id )
                    ,( 1  , tx2id              , 0        , 0          , 0   , 'mint'  , 0     , policy      , mr_redeemer2id )
;

END $$;
