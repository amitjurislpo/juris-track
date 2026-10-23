-- V2: the platform super admin can suspend a business, and a business request can ask whether an email is taken anywhere.

-- A suspended business keeps its data, but its users get no sign-in codes and every request of theirs is refused with
-- 403 BUSINESS_SUSPENDED until the super admin reactivates it (sessions are not ended, so they work again afterwards).
ALTER TABLE businesses ADD COLUMN is_suspended BOOLEAN NOT NULL DEFAULT false;

-- Emails are unique across every business (users_email_key), but row-level security hides the other businesses' users from a
-- business request. This function looks across every business and answers only yes or no, so an owner adding an employee gets a
-- clear "email already in use" (409) instead of a bare unique violation. It runs as the caller (no SECURITY DEFINER); only the
-- tenant setting is cleared while it runs.
CREATE FUNCTION juristrack_is_email_in_use(checked_email TEXT, excluded_user_id UUID) RETURNS BOOLEAN
    LANGUAGE sql STABLE
    SET app.business_id = ''
AS $$
    SELECT EXISTS (SELECT 1 FROM users WHERE email = checked_email AND (excluded_user_id IS NULL OR id <> excluded_user_id))
$$;
GRANT EXECUTE ON FUNCTION juristrack_is_email_in_use(TEXT, UUID) TO juristrack_tenant;
