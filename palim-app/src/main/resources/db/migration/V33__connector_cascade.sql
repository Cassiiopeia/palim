-- 연동을 지울 수 있게 만든다 — 외래키를 실제로 걸어서.
--
-- 여태 connector 계층에는 외래키가 한 줄도 없었다. 그래서 「지우면 딸린 것이 남는다」 를
-- 서비스 코드의 if 문으로 흉내 냈고, CASCADE 가 있어야 할 자리에 「거부」 가 들어갔다.
-- 결과는 막다른 길이었다 — 한 번이라도 돈 연동은 영원히 지울 수 없고, 실행 이력을 지우는
-- 경로는 어디에도 없다.
--
-- 여기서 관계를 DB 에 적는다. 그러면 지우기는 `DELETE FROM connector` 한 줄이 되고, 앞으로
-- 붙는 표도 외래키만 걸면 자동으로 따라온다. 사람이 지울 목록을 손으로 관리하지 않는다.
--
-- 무엇이 함께 사라지는가:
--   connector → connector_mapping → connector_field_map
--             → connector_post_script → connector_post_script_run
--             → connector_run → connector_run_error / connector_staging /
--                               connector_undo_log / custom_record /
--                               std_item / std_stock_snapshot /
--                               std_stock_movement / std_outbound_order
--
-- 무엇이 남는가:
--   connector_secret  — credential_ref 로 느슨하게 가리킨다. 연동을 지워도 남아 재사용된다
--                       (V17 의 의도 그대로다)
--   unit_conversion   — 연동이 아니라 테넌트에 매달린다
--   reconcile_definition — 연동을 code 문자열로 가리켜 외래키를 걸 수 없다. 이쪽은 여전히
--                       ConnectorRemovalService 가 사람 말로 막는다
--
-- PG14 문법만 쓴다(운영 DB 14.15).

-- ------------------------------------------------------------
-- 1) 고아 정리
--
-- 외래키는 이미 어긋난 행이 하나라도 있으면 붙지 않는다. 외래키가 없던 동안 주인 없는 행이
-- 쌓였을 수 있으므로 먼저 걷어낸다. 여기서 지워지는 것은 가리킬 대상이 이미 사라진 행이라
-- 어차피 아무도 설명할 수 없는 자료다.
-- ------------------------------------------------------------

DELETE FROM connector_field_map
 WHERE mapping_id NOT IN (SELECT id FROM connector_mapping);

DELETE FROM connector_mapping
 WHERE connector_id NOT IN (SELECT id FROM connector);

DELETE FROM connector_post_script
 WHERE connector_id NOT IN (SELECT id FROM connector);

DELETE FROM connector_post_script_run
 WHERE script_id NOT IN (SELECT id FROM connector_post_script);

DELETE FROM connector_run
 WHERE connector_id NOT IN (SELECT id FROM connector);

DELETE FROM connector_run_error
 WHERE run_id NOT IN (SELECT id FROM connector_run);

DELETE FROM connector_staging
 WHERE run_id NOT IN (SELECT id FROM connector_run);

DELETE FROM connector_undo_log
 WHERE run_id NOT IN (SELECT id FROM connector_run);

-- run_id 가 비어 있는 행은 손으로 넣은 것이다 — 연동에 매달리지 않으므로 건드리지 않는다.
UPDATE connector_post_script_run SET connector_run_id = NULL
 WHERE connector_run_id IS NOT NULL
   AND connector_run_id NOT IN (SELECT id FROM connector_run);

UPDATE custom_record SET run_id = NULL
 WHERE run_id IS NOT NULL AND run_id NOT IN (SELECT id FROM connector_run);

UPDATE std_item SET run_id = NULL
 WHERE run_id IS NOT NULL AND run_id NOT IN (SELECT id FROM connector_run);

UPDATE std_stock_snapshot SET run_id = NULL
 WHERE run_id IS NOT NULL AND run_id NOT IN (SELECT id FROM connector_run);

UPDATE std_stock_movement SET run_id = NULL
 WHERE run_id IS NOT NULL AND run_id NOT IN (SELECT id FROM connector_run);

UPDATE std_outbound_order SET run_id = NULL
 WHERE run_id IS NOT NULL AND run_id NOT IN (SELECT id FROM connector_run);

-- ------------------------------------------------------------
-- 2) 정의 계층
-- ------------------------------------------------------------

ALTER TABLE connector_mapping
    ADD CONSTRAINT fk_connector_mapping_connector
    FOREIGN KEY (connector_id) REFERENCES connector (id) ON DELETE CASCADE;

ALTER TABLE connector_field_map
    ADD CONSTRAINT fk_connector_field_map_mapping
    FOREIGN KEY (mapping_id) REFERENCES connector_mapping (id) ON DELETE CASCADE;

ALTER TABLE connector_post_script
    ADD CONSTRAINT fk_connector_post_script_connector
    FOREIGN KEY (connector_id) REFERENCES connector (id) ON DELETE CASCADE;

ALTER TABLE connector_post_script_run
    ADD CONSTRAINT fk_post_script_run_script
    FOREIGN KEY (script_id) REFERENCES connector_post_script (id) ON DELETE CASCADE;

-- ------------------------------------------------------------
-- 3) 실행 계층
--
-- 실행 이력은 연동에 딸린 부산물이다. 연동이 사라지면 같이 사라지는 것이 맞다 — 남겨 두면
-- 「어느 연동의 실행인지」 를 아무도 답할 수 없는 행만 남는다.
-- ------------------------------------------------------------

ALTER TABLE connector_run
    ADD CONSTRAINT fk_connector_run_connector
    FOREIGN KEY (connector_id) REFERENCES connector (id) ON DELETE CASCADE;

ALTER TABLE connector_run_error
    ADD CONSTRAINT fk_connector_run_error_run
    FOREIGN KEY (run_id) REFERENCES connector_run (id) ON DELETE CASCADE;

ALTER TABLE connector_staging
    ADD CONSTRAINT fk_connector_staging_run
    FOREIGN KEY (run_id) REFERENCES connector_run (id) ON DELETE CASCADE;

ALTER TABLE connector_undo_log
    ADD CONSTRAINT fk_connector_undo_log_run
    FOREIGN KEY (run_id) REFERENCES connector_run (id) ON DELETE CASCADE;

ALTER TABLE connector_post_script_run
    ADD CONSTRAINT fk_post_script_run_connector_run
    FOREIGN KEY (connector_run_id) REFERENCES connector_run (id) ON DELETE CASCADE;

-- ------------------------------------------------------------
-- 4) 담긴 자료
--
-- 「연동을 지운다」 는 「이 원천을 더 쓰지 않는다」 는 뜻이다. 그 원천이 담은 재고 행을
-- 남겨 두면 출처를 설명할 수 없는 채로 대조에 계속 잡혀 오히려 더 나쁘다.
--
-- run_id 가 NULL 인 행 — 손으로 넣었거나 연동 이전부터 있던 것 — 은 CASCADE 대상이 아니다.
-- ------------------------------------------------------------

ALTER TABLE custom_record
    ADD CONSTRAINT fk_custom_record_run
    FOREIGN KEY (run_id) REFERENCES connector_run (id) ON DELETE CASCADE;

ALTER TABLE std_item
    ADD CONSTRAINT fk_std_item_run
    FOREIGN KEY (run_id) REFERENCES connector_run (id) ON DELETE CASCADE;

ALTER TABLE std_stock_snapshot
    ADD CONSTRAINT fk_std_stock_snapshot_run
    FOREIGN KEY (run_id) REFERENCES connector_run (id) ON DELETE CASCADE;

ALTER TABLE std_stock_movement
    ADD CONSTRAINT fk_std_stock_movement_run
    FOREIGN KEY (run_id) REFERENCES connector_run (id) ON DELETE CASCADE;

ALTER TABLE std_outbound_order
    ADD CONSTRAINT fk_std_outbound_order_run
    FOREIGN KEY (run_id) REFERENCES connector_run (id) ON DELETE CASCADE;
