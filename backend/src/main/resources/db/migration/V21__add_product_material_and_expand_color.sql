ALTER TABLE products ADD COLUMN material TEXT;

-- Yaga colors are multi-valued; retain every selected name without truncation.
ALTER TABLE products ALTER COLUMN color TYPE TEXT;
