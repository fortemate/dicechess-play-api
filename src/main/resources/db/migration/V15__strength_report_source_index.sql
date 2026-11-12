-- Report reads filter the stored category before loading result payloads. This also
-- serves reverse traversal for the category's pre-window cutoff.
CREATE INDEX game_results_rated_category_finished_idx ON game_results (category, finished_at)
WHERE rated = true;
