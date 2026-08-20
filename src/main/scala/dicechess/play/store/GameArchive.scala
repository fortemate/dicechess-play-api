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
  */
object GameArchive:

  /** The archive payload for a finished, non-aborted game, or `None` — mirrors `PlaysiteIngest.payload`'s own
    * active/aborted exclusions exactly, so the two representations of "should this game be recorded" never drift. Also
    * `None` if `players` is unexpectedly missing a seat (a malformed snapshot, not the normal "still active" case) —
    * the same anomaly `PgGameStore.finishedGameOf` guards against and logs; guarding here too means a malformed row
    * simply has no archive, never one with a null seat.
    */
  def payload(snapshot: GameSnapshot): Option[Json] =
    snapshot.status match
      case GameStatus.Active                                  => None
      case GameStatus.Ended(GameOver(_, Termination.Aborted)) => None
      case GameStatus.Ended(GameOver(result, termination))    =>
        (snapshot.players.get(Seat.White), snapshot.players.get(Seat.Black)).mapN { (white, black) =>
          Json.obj(
            "started_at"   -> snapshot.createdAtEpochMs.asJson,
            "rated"        -> snapshot.rated.getOrElse(false).asJson,
            "time_control" -> snapshot.timeControl.asJson,
            "result"       -> PlaysiteIngest.resultOf(result).asJson,
            "termination"  -> PlaysiteIngest.terminationOf(termination).asJson,
            "players"      -> Json.obj("white" -> white.externalId.asJson, "black" -> black.externalId.asJson),
            "initial_dfen" -> EngineOps.InitialDfen.asJson, // every game starts here (GameRegistry never passes a
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
        }

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
    */
  final case class Record(
      rated: Boolean,
      timeControl: TimeControl,
      result: Int,
      termination: String,
      whiteExternalId: String,
      blackExternalId: String,
      initialDfen: String,
      turns: List[TurnRecord],
      commit: Option[String],
      serverSeed: String,
      clientSeedWhite: String,
      clientSeedBlack: String
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
      result      <- c.get[Int]("result")
      termination <- c.get[String]("termination")
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
      seedBlack
    )
