package dicechess.play.store

import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.dice.DiceSource
import dicechess.play.game.EngineOps
import dicechess.play.ingest.PlaysiteIngest
import dicechess.play.wire.Codecs.given
import io.circe.syntax.*
import io.circe.{Decoder, Json}

/** The immutable, sanitized history record for a finished game (#177) — play's own durable representation of game
  * history, independent of the analytics wire contract (`PlaysiteIngest`) and of `games` snapshot retention (#179
  * prunes ended snapshots once this becomes the serving path for replay, `GET /games/{id}/history`, #178).
  *
  * Unlike `GameSnapshot` this drops the live secrets a client should never retain past the game (seat join tokens);
  * unlike the analytics payload it keeps raw `external_id`s (this table is server-private — anonymization is the
  * READING endpoint's job, via the same `PublicPlayer` rules the live wire uses) and the full fairness block (commit +
  * server seed + client seeds), stored unconditionally.
  *
  * '''Showcase games are archived whatever way they end (ADR-005 §8, #47).''' The general rule — an aborted game has no
  * history worth serving — is overridden for `origin = showcase`: the showcase promises that every played game is
  * recorded, and a technical abort is exactly the case an operator later needs to audit. Such a row carries the full
  * moves, dice and fairness material but `result = null` and `sporting_eligible = false`, so it can never leak into a
  * rating, an analytics corpus (the outbox still excludes it) or a future public win/draw/loss score. Non-showcase
  * aborts keep the pre-#47 behaviour and are not archived.
  */
object GameArchive:

  /** One archive row's worth of data: the payload plus the two values `game_archive` also stores as columns (V5) so a
    * reader can filter by them without decoding JSON. Both are ALSO inside the payload — the columns are a projection
    * of it, never a second source of truth.
    */
  final case class Entry(payload: Json, origin: GameOrigin, sportingEligible: Boolean)

  /** The archive payload for a finished game, or `None` when the game is not archived: still active, aborted outside
    * the showcase (mirrors `PlaysiteIngest.payload`'s own exclusion, so the two representations of "should this game be
    * recorded" drift only where ADR-005 says they must), or unexpectedly missing a seat (a malformed snapshot, not the
    * normal "still active" case — the same anomaly `PgGameStore.finishedGameOf` guards against and logs; guarding here
    * too means a malformed row simply has no archive, never one with a null seat).
    */
  def payload(snapshot: GameSnapshot): Option[Json] = entry(snapshot).map(_.payload)

  /** [[payload]] together with its column projection — what `PgGameStore` writes. */
  def entry(snapshot: GameSnapshot): Option[Entry] =
    val origin = snapshot.effectiveOrigin
    snapshot.status match
      case GameStatus.Active                                                        => None
      case GameStatus.Ended(GameOver(_, Termination.Aborted)) if !origin.isShowcase => None
      case GameStatus.Ended(GameOver(result, termination))                          =>
        val eligible = sportingEligible(termination)
        (snapshot.players.get(Seat.White), snapshot.players.get(Seat.Black)).mapN { (white, black) =>
          val json = Json.obj(
            "started_at"   -> snapshot.createdAtEpochMs.asJson,
            "rated"        -> snapshot.rated.getOrElse(false).asJson,
            "time_control" -> snapshot.timeControl.asJson,
            // A technical abort has no outcome to record: `GameResult.Draw` is only the placeholder the room's abort
            // path carries, and writing its `0` here would fabricate a draw. `null` says "no sporting result" the
            // same way `game_results.result` does.
            "result"            -> Option.when(eligible)(PlaysiteIngest.resultOf(result)).asJson,
            "termination"       -> PlaysiteIngest.terminationOf(termination).asJson,
            "origin"            -> origin.wireName.asJson,
            "sporting_eligible" -> eligible.asJson,
            "players"           -> Json.obj("white" -> white.externalId.asJson, "black" -> black.externalId.asJson),
            "initial_dfen"      -> EngineOps.InitialDfen.asJson, // every game starts here (GameRegistry never passes a
            // custom DFEN) — same invariant PlaysiteIngest's own start-position constant relies on.
            "turns"    -> snapshot.turns.map(t => Json.fromJsonObject(TurnRecord.json(t))).asJson,
            "fairness" -> Json.obj(
              "commit"       -> commitOf(snapshot.serverSeed).asJson,
              "server_seed"  -> snapshot.serverSeed.asJson,
              "client_seeds" -> Json.obj(
                "white" -> seedFor(snapshot.clientSeeds, Seat.White, white).asJson,
                "black" -> seedFor(snapshot.clientSeeds, Seat.Black, black).asJson
              )
            )
          )
          Entry(json, origin, eligible)
        }

  /** Whether a game ended the way this termination describes has a sporting outcome (ADR-005 §8). Every ending a player
    * brought about — capture, resignation, flag fall, agreed or forced draw — counts; only a technical abort (a server,
    * database or webhook failure the players did nothing to cause) does not.
    */
  def sportingEligible(termination: Termination): Boolean = termination != Termination.Aborted

  /** `None` only if `serverSeed` fails to parse as hex — practically impossible (it is always CSPRNG-generated), but a
    * parse failure must not lose the archive row entirely: the row is still written, just without a computed
    * commitment.
    */
  private def commitOf(hexSeed: String): Option[String] =
    DiceSource.fromHexSeed(hexSeed).toOption.map(_.commit)

  /** Mirrors `GameRoom.Session.seedFor`: the seed ACTUALLY folded into the dice, not just what was submitted. A seat
    * that never submitted a client seed before the grace elapsed falls back to its own external id — `clientSeeds`
    * alone (as persisted on `GameSnapshot`) would be missing that seat's entry, understating the fairness block.
    */
  private def seedFor(clientSeeds: Map[Seat, String], seat: Seat, player: Principal): String =
    clientSeeds.getOrElse(seat, player.externalId)

  /** `payload` decoded back into structured values — the read-side counterpart, consumed by `GET /games/{id}/history`
    * (#178). A manual decoder, not derived: the stored JSON is the snake_case shape `payload` builds above, distinct
    * from every in-process type's own camelCase convention (same reason `PlaysiteIngest`/`payload` itself build JSON by
    * hand rather than deriving).
    *
    * `result` is `None` for a technically aborted showcase game and `Some` for every sporting result; `origin` and
    * `sportingEligible` decode to `Legacy`/`true` when the keys are absent, which is exactly right for every row
    * written before #47 — none of those was an abort, and none carried an origin.
    */
  final case class Record(
      rated: Boolean,
      timeControl: TimeControl,
      result: Option[Int],
      termination: String,
      whiteExternalId: String,
      blackExternalId: String,
      initialDfen: String,
      turns: List[TurnRecord],
      commit: Option[String],
      serverSeed: String,
      clientSeedWhite: String,
      clientSeedBlack: String,
      origin: GameOrigin = GameOrigin.Legacy,
      sportingEligible: Boolean = true
  )

  def decode(payload: Json): Decoder.Result[Record] =
    // Scoped to this method, not a package-wide `given`: `GameSnapshot`'s own `Codec[TurnRecord]` (camelCase,
    // operational storage) must never leak in here or be shadowed by this one (snake_case, this archive's own
    // shape) — see `TurnRecord.json`, the mirror of this on the write side.
    given Decoder[TurnRecord] = Decoder.instance { c =>
      for
        turnNumber     <- c.get[Long]("turn_number")
        activeColor    <- c.get[String]("active_color")
        dice           <- c.get[List[Int]]("dice")
        moves          <- c.get[List[String]]("moves")
        fenAfter       <- c.get[String]("fen_after")
        thinkingTimeMs <- c.get[Option[Long]]("thinking_time_ms")
      yield TurnRecord(turnNumber, activeColor, dice, moves, fenAfter, thinkingTimeMs)
    }
    val c           = payload.hcursor
    val players     = c.downField("players")
    val fairness    = c.downField("fairness")
    val clientSeeds = fairness.downField("client_seeds")
    for
      rated       <- c.get[Boolean]("rated")
      timeControl <- c.get[TimeControl]("time_control")
      result      <- c.get[Option[Int]]("result")
      termination <- c.get[String]("termination")
      origin      <- c.get[Option[GameOrigin]]("origin")
      eligible    <- c.get[Option[Boolean]]("sporting_eligible")
      whiteId     <- players.get[String]("white")
      blackId     <- players.get[String]("black")
      initialDfen <- c.get[String]("initial_dfen")
      turns       <- c.get[List[TurnRecord]]("turns")
      commit      <- fairness.get[Option[String]]("commit")
      serverSeed  <- fairness.get[String]("server_seed")
      seedWhite   <- clientSeeds.get[String]("white")
      seedBlack   <- clientSeeds.get[String]("black")
    yield Record(
      rated,
      timeControl,
      result,
      termination,
      whiteId,
      blackId,
      initialDfen,
      turns,
      commit,
      serverSeed,
      seedWhite,
      seedBlack,
      origin = origin.getOrElse(GameOrigin.Legacy),
      sportingEligible = eligible.getOrElse(true)
    )
