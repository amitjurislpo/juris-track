-- V12: the owner can switch an employee's sign-in off without deactivating the person (PUT /api/employees/{id}/login-access).
-- A person with login disabled stays in the business, the list, the board and the reports, but gets no sign-in code, no session
-- and no JurisOne access until the owner switches it on again. Everybody who exists today keeps their access.
ALTER TABLE users ADD COLUMN is_login_enabled BOOLEAN NOT NULL DEFAULT true;
