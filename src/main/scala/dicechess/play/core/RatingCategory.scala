package dicechess.play.core

/** Which rating scale a game counts on (#280): Bullet, Blitz or Rapid, keyed by how long the control lets the game
  * last. The Lichess model — one scale per speed rather than one scale for everything — because a bot that is strong at
  * 10+10 and a bot that is strong at 1+1 are not the same claim, and folding them into one number makes both wrong.
  *
  * This object is the SINGLE SOURCE OF TRUTH for the mapping. Three other places must agree with it and none of them
  * may re-derive it: `play.rating_category(text)` in the database (V21, pinned against this by a test), the SPA's lobby
  * preset grouping, and any future report that buckets by speed.
  */
enum RatingCategory:
  case Bullet, Blitz, Rapid

  /** The lowercase name used on the wire and in the database `category` column. Derived rather than spelled out, so a
    * new case cannot be added without one.
    */
  def wireName: String = toString.toLowerCase

object RatingCategory:

  /** Expected moves per side in a dice chess game — the multiplier in the estimated-duration formula.
    *
    * '''Measured, not borrowed.''' Lichess uses `initial + 40 × increment`, where 40 is the expected moves per side in
    * chess. Dice chess games are far shorter: over '''94,596 finished games''' in the production archive the median is
    * 14 turns per game, i.e. 7 moves per side — a king falls quickly. That corpus is almost entirely ladder bot-vs-bot
    * at 5+3, so human games may well differ; the bucketing below is insensitive to M anywhere in the 7–15 range, which
    * is why the measurement is good enough to key a permanent scale on (#280).
    */
  val MovesPerSide: Int = 7

  /** The scale a request that names no category is answered on — `GET /leaderboard`, and the scalar rating fields the
    * profiles and `/auth/me` still carry.
    *
    * Blitz because that is the LADDER's own control (5+3), and continuity of that page is the whole reason a default
    * exists: `/leaderboard` was the bot ladder's board before it was anything else, and the strength narrative built on
    * it must not silently start reporting a different population. Written as a constant rather than derived from
    * `LadderScheduler.Config.DefaultTimeControl` on purpose — a public API's default answer is a product decision, and
    * deriving it would let a change to an operational knob quietly repoint every unqualified request. The coupling is
    * PINNED instead: `LadderSchedulerSuite` asserts the ladder control still categorises to this value, so moving the
    * ladder fails a test that names this decision rather than changing the API underneath it.
    */
  val Default: RatingCategory = Blitz

  /** Estimated seconds per player, below which a control is [[Bullet]]. Lichess's own boundary, kept: the boundaries
    * are a naming convention players already know, and only the multiplier above needed re-measuring for dice chess.
    */
  val BulletCeilingSeconds: Int = 180

  /** Estimated seconds per player, below which a control is [[Blitz]] and at or above which it is [[Rapid]]. */
  val BlitzCeilingSeconds: Int = 480

  /** Estimated total seconds one player spends on a game, or `None` when the control does not bound a game's length at
    * all.
    *
    * `Unlimited` has no budget to estimate, and `PerMove` bounds each move rather than the game — a 30 s/move control
    * is a 7-minute game or a 70-minute one depending only on how long the game runs, which is exactly what this
    * function cannot know. Both are therefore uncategorised, and a game played under one counts on no scale (2 games
    * out of 95k in production; `PerMove` is already being phased out of the client).
    */
  def estimatedSeconds(timeControl: TimeControl): Option[Long] = timeControl match
    // `Long`, and load-bearing: nothing validates the seconds a creation request asks for, so an increment near
    // `Int.MaxValue / 7` is storable, and in `Int` arithmetic the product wraps — `Fischer(0, 613566757)` would
    // estimate 3 seconds and read as Bullet. `play.rating_category` computes in `bigint`, so the wrap would also be a
    // silent disagreement between the two implementations of this rule, which is the one thing their parity test
    // exists to prevent.
    case TimeControl.Fischer(initial, increment)        => Some(initial.toLong + MovesPerSide.toLong * increment)
    case TimeControl.SuddenDeath(initial)               => Some(initial.toLong)
    case TimeControl.Unlimited | TimeControl.PerMove(_) => None

  /** The scale this control's games count on, or `None` for an uncategorised control (see [[estimatedSeconds]]).
    *
    * The formula reproduces the SPA's hand-maintained lobby grouping exactly — Blitz = `3+2, 5+3, 5+5, 5 min`, Rapid =
    * `10+5, 10+10, 15+10, 10 min` — and places the bot catalog's `1+1` (67 s) in Bullet. The ladder's own control
    * (`Fischer(300, 3)` = 321 s) lands in Blitz.
    */
  def of(timeControl: TimeControl): Option[RatingCategory] =
    estimatedSeconds(timeControl).map: estimated =>
      if estimated < BulletCeilingSeconds then Bullet
      else if estimated < BlitzCeilingSeconds then Blitz
      else Rapid

  /** The scale a STORED control counts on — `game_results.time_control` holds the `toString` form, not the ADT, so the
    * rating batch reaches the mapping through here. An unparseable value is uncategorised rather than an error: a row
    * written by an older or a future server must not poison the queue.
    */
  def ofStored(rawTimeControl: String): Option[RatingCategory] =
    TimeControl.parse(rawTimeControl).flatMap(of)

  /** The inverse of [[RatingCategory.wireName]], for reading the database `category` column and (in phase 2) a
    * `?category=` query parameter. `None` for anything else, so an unknown value answers 400/absent rather than
    * silently selecting a scale nobody asked for.
    */
  def fromWireName(name: String): Option[RatingCategory] =
    RatingCategory.values.find(_.wireName == name)
