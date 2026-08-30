-- Drop legacy single-scale Glicko-2 columns from bots and users tables.
-- Per-category ratings in bot_ratings and user_ratings are the authoritative rating stores.

ALTER TABLE bots
    DROP COLUMN glicko_rating,
    DROP COLUMN glicko_rd,
    DROP COLUMN glicko_vol;

ALTER TABLE users
    DROP COLUMN glicko_rating,
    DROP COLUMN glicko_rd,
    DROP COLUMN glicko_vol;
