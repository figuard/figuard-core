-- Stores the human-readable denial explanation alongside the machine-readable denial_reason code.
-- denial_reason = enum name (e.g. NO_MATCHING_ALLOCATION)
-- denial_message = actionable developer message (e.g. "claimedCategory 'car_rental' does not match any allocation")
ALTER TABLE spend_events ADD COLUMN denial_message VARCHAR(1000);
