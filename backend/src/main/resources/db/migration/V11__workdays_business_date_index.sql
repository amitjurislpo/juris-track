-- V11: the reports read one business's workdays for a date range (GET /api/reports/workdays and /summaries). The existing indexes
-- start with work_date (all businesses) or employee_id (one person); this one serves the owner's "everybody in the business" query
-- and the row-level-security filter on business_id together.
CREATE INDEX workdays_business_id_work_date_idx ON workdays (business_id, work_date);
