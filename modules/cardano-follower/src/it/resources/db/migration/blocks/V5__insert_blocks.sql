INSERT INTO block (id, hash                                                                            , epoch_no, slot_no , epoch_slot_no, block_no, previous_id, slot_leader_id, size, "time"                     , tx_count, proto_major, proto_minor, vrf_key, op_cert, op_cert_counter)
VALUES            (0 , decode('ABEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1','hex'), 189     , 51278410, 10           , 0       , NULL       , 0             , 1024, '2022-04-21T16:28:00Z'     , 1       , 0          , 0          , ''     , NULL   , NULL           )
                 ,(1 , decode('ABEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1','hex'), 190     , 51710400, 0            , 1       , NULL       , 0             , 1024, '2022-04-22T16:29:00Z'     , 1       , 0          , 0          , ''     , NULL   , NULL           )
                 ,(2 , decode('BBEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1','hex'), 190     , 51710500, 100          , 2       , NULL       , 0             , 1024, '2022-04-23T16:29:00Z'     , 1       , 0          , 0          , ''     , NULL   , NULL           )
                 ,(3 , decode('CBEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1','hex'), 191     , 52142500, 100          , 3       , NULL       , 0             , 1024, '2022-04-24T16:29:00Z'     , 1       , 0          , 0          , ''     , NULL   , NULL           )
                 ,(4 , decode('DBEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1','hex'), 192     , 52574500, 100          , 4       , NULL       , 0             , 1024, '2022-04-25T16:29:00Z'     , 1       , 0          , 0          , ''     , NULL   , NULL           )
                 ,(5 , decode('EBEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1','hex'), 193     , 53006500, 100          , 5       , NULL       , 0             , 1024, '2022-04-26T16:29:00Z'     , 1       , 0          , 0          , ''     , NULL   , NULL           )
;
-- sometimes the block number can be null so we add this block just to handle that case
INSERT INTO block (id , hash                                                                            , epoch_no, slot_no, epoch_slot_no, block_no, previous_id, slot_leader_id, "size", "time"                   , tx_count, proto_major, proto_minor, vrf_key, op_cert, op_cert_counter) VALUES
	                (100, decode('76B343FB174CE057060B76D9DA6B474A5C1720814B0F34EAB4E2EFBA115F2308','hex'), NULL    , NULL   , NULL         , NULL    , NULL       , 1             , 0     , '2022-06-06 23:00:00.000', 4       , 0          , 0          , NULL   , NULL   , NULL           );
