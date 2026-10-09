-- Compatibility migration for databases created before ScriptVersion.contentRevision.
-- Existing accounts keep their verified/enabled state when account onboarding is introduced.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=current_schema() AND table_name='users') THEN
    ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified boolean NOT NULL DEFAULT true;
    ALTER TABLE users ADD COLUMN IF NOT EXISTS enabled boolean NOT NULL DEFAULT true;
    ALTER TABLE users ADD COLUMN IF NOT EXISTS token_version bigint NOT NULL DEFAULT 0;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=current_schema() AND table_name='refresh_tokens') THEN
    ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS token_version bigint NOT NULL DEFAULT 0;
  END IF;
END $$@@
CREATE TABLE IF NOT EXISTS account_action_limits (
  id varchar(64) PRIMARY KEY, next_allowed_at timestamp with time zone NOT NULL,
  window_started_at timestamp with time zone NOT NULL, requests integer NOT NULL
)@@

-- Keep this script idempotent: the project still uses Hibernate ddl-auto:update and
-- may start against either a populated legacy database or an empty database.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = current_schema() AND table_name = 'script_versions'
    ) THEN
        ALTER TABLE script_versions ADD COLUMN IF NOT EXISTS content_revision BIGINT;
        UPDATE script_versions SET content_revision = 1 WHERE content_revision IS NULL;
        ALTER TABLE script_versions ALTER COLUMN content_revision SET DEFAULT 1;
        ALTER TABLE script_versions ALTER COLUMN content_revision SET NOT NULL;
    END IF;
END $$@@

-- SB-03 storyboard tables are created explicitly for populated databases as well as
-- fresh Hibernate schemas.  Snapshots deliberately have no FK to script_scenes.
CREATE TABLE IF NOT EXISTS storyboard_drafts (
 id varchar(36) PRIMARY KEY, organization_id bigint NOT NULL, project_id bigint NOT NULL,
 created_by bigint NOT NULL, source_run_id varchar(36) NOT NULL, source_script_id bigint NOT NULL,
 source_script_revision bigint NOT NULL, source_scene_id varchar(128) NOT NULL, source_scene_no varchar(32) NOT NULL,
 source_heading varchar(255) NOT NULL, source_scene_text text NOT NULL, source_scene_hash varchar(64) NOT NULL,
 revision bigint NOT NULL DEFAULT 1, created_at timestamp with time zone NOT NULL DEFAULT now(), updated_at timestamp with time zone NOT NULL DEFAULT now()
)@@
CREATE TABLE IF NOT EXISTS storyboard_shots (
 id varchar(36) PRIMARY KEY, storyboard_id varchar(36) NOT NULL, order_index integer NOT NULL,
 shot_size varchar(24) NOT NULL, camera_movement varchar(24) NOT NULL, visual_description text NOT NULL,
 dialogue text, sound text, duration_seconds integer NOT NULL, image_prompt text NOT NULL, video_prompt text NOT NULL, source_quote text NOT NULL,
 CONSTRAINT uk_storyboard_shot_order UNIQUE(storyboard_id, order_index)
)@@
CREATE TABLE IF NOT EXISTS storyboard_shot_proposals (
 id varchar(36) PRIMARY KEY, storyboard_id varchar(36) NOT NULL, target_shot_id varchar(36) NOT NULL,
 source_run_id varchar(36) NOT NULL, base_storyboard_revision bigint NOT NULL, status varchar(16) NOT NULL,
 shot_size varchar(24) NOT NULL, camera_movement varchar(24) NOT NULL, visual_description text NOT NULL, dialogue text, sound text,
 duration_seconds integer NOT NULL, image_prompt text NOT NULL, video_prompt text NOT NULL, source_quote text NOT NULL,
 created_at timestamp with time zone NOT NULL DEFAULT now(), resolved_at timestamp with time zone
)@@
CREATE TABLE IF NOT EXISTS storyboard_run_contexts (
 run_id varchar(36) PRIMARY KEY, mode varchar(12) NOT NULL, target_shot_count integer, instructions text,
 storyboard_id varchar(36), target_shot_id varchar(36), base_storyboard_revision bigint,
 target_shot_snapshot text, previous_shot_snapshot text, next_shot_snapshot text,
 source_script_id bigint NOT NULL, source_script_revision bigint NOT NULL, source_scene_id varchar(128) NOT NULL,
 source_scene_no varchar(32) NOT NULL, source_heading varchar(255) NOT NULL, source_scene_text text NOT NULL, source_scene_hash varchar(64) NOT NULL,
 result_hash varchar(64), artifact_id varchar(36), artifact_type varchar(24)
)@@
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=current_schema() AND table_name='storyboard_run_contexts') THEN
    ALTER TABLE storyboard_run_contexts ADD COLUMN IF NOT EXISTS target_shot_snapshot text;
    ALTER TABLE storyboard_run_contexts ADD COLUMN IF NOT EXISTS previous_shot_snapshot text;
    ALTER TABLE storyboard_run_contexts ADD COLUMN IF NOT EXISTS next_shot_snapshot text;
  END IF;
END $$@@
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=current_schema() AND table_name='agent_runs') THEN
    ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS result_type varchar(24);
    ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS result_id varchar(36);
    ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS result_storyboard_id varchar(36);
  END IF;
END $$@@
