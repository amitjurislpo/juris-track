-- V21: roster sync from JurisOne (PUT /api/integration/employees, references/INTEGRATION.md "Roster contract including shifts").
--
-- * shifts.code: the code JurisOne chose for the shift (unique per business; NULL for shifts made in JurisTrack). The sync upserts a
--   shift by this code.
-- * users.is_deactivated_by_roster_sync: TRUE when the person was deactivated by the roster sync. Only such a person is reactivated by
--   a later sync; somebody the owner deactivated stays deactivated. Every manual status change clears it.
ALTER TABLE shifts ADD COLUMN code TEXT;
ALTER TABLE shifts ADD CONSTRAINT shifts_code_length CHECK (code IS NULL OR length(btrim(code)) BETWEEN 1 AND 40);
CREATE UNIQUE INDEX shifts_business_id_code_key ON shifts (business_id, code) WHERE code IS NOT NULL;

ALTER TABLE users ADD COLUMN is_deactivated_by_roster_sync BOOLEAN NOT NULL DEFAULT FALSE;
