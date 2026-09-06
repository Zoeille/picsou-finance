ALTER TABLE persistent_session
    ADD COLUMN token_version BIGINT;

UPDATE persistent_session session
SET token_version = app_user.token_version
FROM app_user
WHERE session.user_id = app_user.id;

ALTER TABLE persistent_session
    ALTER COLUMN token_version SET NOT NULL;
