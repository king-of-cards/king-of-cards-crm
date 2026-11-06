-- Additive only: existing users retain NULL until explicitly provisioned by an administrator.
ALTER TABLE identity_user ADD COLUMN display_name VARCHAR(120) NULL;
