SELECT jsonb_build_object(
  'exportSchemaVersion', 1,
  'gameId', t.game_id,
  'transcript', t.payload
)::text
FROM laboratory.transcripts AS t
JOIN laboratory.games AS g USING (game_id)
WHERE g.expires_at > clock_timestamp()
ORDER BY t.game_id;
