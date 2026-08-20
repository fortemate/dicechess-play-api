package dicechess.play.core

/** Why a game ended. Maps to the analytics `game_termination_enum` at ingest time.
  *
  * `Aborted` is a server-side abort (the writer fiber failed or was cancelled, e.g. on shutdown); the game has no
  * sporting result. `Timeout` is the player to move failing to act within the turn deadline (a forfeit). Their
  * analytics mappings are finalized with the analytics handoff.
  */
enum Termination:
  case KingCaptured, Resign, Draw, Aborted, Timeout

enum GameResult:
  case Win(side: Side)
  case Draw

final case class GameOver(result: GameResult, termination: Termination)

enum GameStatus:
  case Active
  case Ended(over: GameOver)

/** A game's time control, chosen at creation and enforced by the room (`GameRoom` flags a side whose clock runs out).
  * `Unlimited` carries no clocks at all — only the anti-abandonment turn deadline applies — so it is never what a
  * creation request gets by default; see [[TimeControl.Default]]. Distinct from the engine's move-search TimeManager,
  * which budgets a bot's own thinking rather than the authoritative game clock.
  */
enum TimeControl:
  case Unlimited
  case SuddenDeath(initialSeconds: Int)
  case Fischer(initialSeconds: Int, incrementSeconds: Int)
  case PerMove(secondsPerMove: Int)

object TimeControl:

  /** Applied when a creation request omits `timeControl` on any path a human can end up sitting in. Rapid 10+10 is slow
    * enough for a thinking bot (the house fleet already posts exactly this) and for a human on a phone, while still
    * ending a walked-away-from game on the clock instead of leaving it open forever.
    *
    * `Unlimited` used to be this default, which is how clockless games reached the public lobby
    * (rabestro/dicechess-play#99). The wire field stays **optional** rather than becoming required: a third-party bot
    * that omits it keeps working, it just gets a clock. `Unlimited` remains reachable by asking for it explicitly —
    * bot-vs-bot corpus runs legitimately want no clock.
    */
  val Default: TimeControl = Fischer(600, 10)

  private val SuddenDeathForm = """SuddenDeath\((\d+)\)""".r
  private val PerMoveForm     = """PerMove\((\d+)\)""".r
  private val FischerForm     = """Fischer\((\d+),(\d+)\)""".r

  /** The inverse of this enum's own `toString` — `Fischer(300,3)`, `SuddenDeath(300)`, `PerMove(30)`, `Unlimited`.
    *
    * That form is not a debug rendering here: it is what `PgGameStore.finishedGameOf` writes into
    * `game_results.time_control`, what `GET /players/{id}/games` serves, and what the SPA's
    * `parseGameResultsTimeControl` already reads. The parser lives beside the enum so the two cannot drift, and a
    * round-trip test pins every case — a new case with no parse branch would otherwise fail silently, as an
    * uncategorised game rather than an error (see [[RatingCategory.ofStored]], the reason this exists).
    *
    * Deliberately NOT the wire codec: `wire/Codecs` derives the structured JSON the live protocol uses, a separate
    * contract that neither of these two should bend to match (the same split the SPA documents on its side).
    */
  def parse(raw: String): Option[TimeControl] = raw match
    case "Unlimited"                     => Some(Unlimited)
    case SuddenDeathForm(initial)        => initial.toIntOption.map(SuddenDeath.apply)
    case PerMoveForm(perMove)            => perMove.toIntOption.map(PerMove.apply)
    case FischerForm(initial, increment) =>
      // `toIntOption` on both halves, not a bare `toInt`: a value wider than Int is unrepresentable here and must
      // read as unparseable, never throw inside the rating batch's queue drain.
      for
        i   <- initial.toIntOption
        inc <- increment.toIntOption
      yield Fischer(i, inc)
    case _ => None

/** Remaining time per side, in **milliseconds**, as of the event that carries it. The side to move is still ticking, so
  * a client counts its clock down locally between server updates; the other side's value is exact until its next turn.
  * `Unlimited` games carry no clocks (the field is absent), so it appears only on timed games.
  */
final case class Clocks(white: Long, black: Long)

/** The two clients' post-commit dice seeds, revealed at game end alongside the server seed so anyone can re-derive
  * every roll. The HMAC message is canonical (length-prefixed), not a delimited string:
  * `HMAC-SHA256(serverSeed, uint32be(len(white)) ++ white ++ uint32be(len(black)) ++ black ++ int64be(ply))` (see
  * `DiceSource.rollMessage`). A seat that never submitted a seed falls back to its external id, shown here.
  */
final case class ClientSeeds(white: String, black: String)

/** The legal turns for a pending roll, as a prefix tree of UCI micro-moves — e.g. `{"e2e4": {"g1f3": {}}}`. Every legal
  * turn uses the maximal number of dice (the Maximum Micro-moves Rule), except a turn that captures the king, which
  * ends the game and is exclusively a leaf — so a node with no children IS a complete legal turn: walk any root-to-leaf
  * path and submit it. An empty tree means the roll has no legal move and the server is about to auto-pass. Computed by
  * the engine on the server, so a bot needs no rules implementation of its own.
  */
final case class MoveTree(children: Map[String, MoveTree])

object MoveTree:
  val empty: MoveTree = MoveTree(Map.empty)

  /** Build the tree from UCI move paths. An empty path (a pass) is not representable and is dropped — the wire signals
    * a pass as the empty tree instead.
    */
  def fromPaths(paths: List[List[String]]): MoveTree =
    MoveTree(paths.filter(_.nonEmpty).groupBy(_.head).map((move, group) => move -> fromPaths(group.map(_.tail))))

/** Whether a participant is a human or a bot — the public taxonomy the lobby and boards render. */
enum PlayerKind:
  case Human, Bot

/** The public face of a participant: enough for a board, lobby, or spectator to say WHO plays, never leaking ids. Bots
  * show their team-qualified display name; a registered player shows their nickname (#194 step 4); a guest stays
  * anonymous.
  *
  * `name` is therefore a display string in all three cases and NEVER an id — `user:<uuid>` must not appear here (#249).
  * A guest's `None` is a promise, not an omission: it is what keeps anonymous play anonymous even after that browser's
  * owner signs up and claims the history.
  *
  * `rating` (#290) is the participant's settled Glicko-2 rating under the leaderboard's own visibility rule (absent
  * while provisional), sampled where the carrying object was built — on a game it is the rating as of game start,
  * frozen for the game's duration like the display name. `None` means "the server does not say", never "zero": a guest,
  * an unrated participant, or a server without persistence all simply omit it. It only ever rides on a NAMED face — a
  * stable number on an anonymous face would be exactly the cross-game correlation handle `name = None` exists to deny
  * (see [[PublicPlayer.ofExternalId]]).
  */
final case class PublicPlayer(kind: PlayerKind, name: Option[String], rating: Option[Double] = None)

object PublicPlayer:

  /** The anonymous human face — every human whose nickname is unknown or deliberately withheld. */
  val anonymousHuman: PublicPlayer = PublicPlayer(PlayerKind.Human, None)

  /** The face of a participant with no name resolution available. Guests AND accounts come out anonymous here, which is
    * correct for a guest and merely incomplete for an account — use [[ofExternalId]] instead wherever a nickname map
    * from `UserStore.nicknamesByExternalId` can be batched first.
    */
  def of(principal: Principal): PublicPlayer = principal match
    case Principal.Bot(team, name) => PublicPlayer(PlayerKind.Bot, Some(s"$team $name"))
    case _                         => anonymousHuman

  /** A registered player's face. Takes the nickname rather than looking it up, so this stays a pure wire type with no
    * store dependency — the caller batches the lookup and passes what it found.
    */
  def user(nickname: String): PublicPlayer = PublicPlayer(PlayerKind.Human, Some(nickname))

  /** The face of an external id, given a nickname map from `UserStore.nicknamesByExternalId`.
    *
    * The one funnel every read path should use, so the guest rule lives in a single place: a bot renders by name, an
    * ACCOUNT id present in the map renders as that nickname, and everything else — guests, unknown, deleted or
    * deactivated accounts — renders anonymous. An id missing from the map can only ever cost a nickname, never
    * anonymity.
    *
    * Note the map is consulted **only** for ids that parse as `user:<uuid>`. That is not redundant with the store's own
    * filtering: it makes a `guest:` key unrenderable here no matter who assembled the map, so this function enforces
    * the rule rather than merely inheriting it from a well-behaved caller. Cheap, and the thing being protected is
    * someone's anonymity.
    *
    * `ratings` (#290) follows the same funnel discipline: a rating is attached only to a face that came out NAMED, so
    * an anonymous face stays completely bare no matter what the caller put in the map — a stable rating on an anonymous
    * player would let anyone correlate them across games, which is the exact leak `name = None` prevents.
    */
  def ofExternalId(
      externalId: String,
      nicknames: Map[String, String],
      ratings: Map[String, Double] = Map.empty
  ): PublicPlayer =
    val face = Principal.fromBotExternalId(externalId) match
      case Some(bot) => of(bot)
      case None      =>
        Principal
          .fromUserExternalId(externalId)
          .flatMap(_ => nicknames.get(externalId))
          .fold(anonymousHuman)(user)
    if face.name.isDefined then face.copy(rating = ratings.get(externalId)) else face

/** Both seats' public faces, as carried on the game state. */
final case class Players(white: PublicPlayer, black: PublicPlayer)

/** Whether a draw offer from the opponent is currently pending for the side on move (#327). */
final case class DrawOffer(pending: Boolean = true)

/** A wire-safe snapshot of a game, sufficient for a (re)joining client or bot to act. */
final case class PublicGameState(
    version: Long,
    dfen: String,
    activeSeat: Seat,
    dicePending: Boolean,
    status: GameStatus,
    timeControl: TimeControl,
    clocks: Option[Clocks],
    // The dice commitment (SHA-256 of the server seed, hex). Published from creation and constant for the game, so a
    // bot that only joins the game stream (and never saw the create response) can still verify the end-of-game reveal.
    commit: String,
    // The revealed server seed (hex), present only once the game has ended — so a client that (re)joins after the end
    // can still open the dice commitment. `None` while the game is active (the seed stays secret mid-game).
    seed: Option[String],
    // The client seeds folded into the dice, revealed together with `seed` (so both are `None` while active).
    clientSeeds: Option[ClientSeeds],
    // The legal turns for the pending roll. Present while `dicePending`, except when the enumeration exceeds the
    // inline cap — then it is `None` and a client fetches the full tree via `GET /games/{id}/moves`. `None` whenever
    // no roll is pending.
    legalMoves: Option[MoveTree] = None,
    // The public faces of both seats — who a board or spectator is looking at (bots by name, humans anonymous).
    players: Option[Players] = None,
    // Whether this game counts toward rating (#290) — the flag `GameRegistry.isRated` decided at creation, surfaced so
    // the live board can say what is at stake. Optional for wire evolution only: every room emits it, but a client
    // must treat absence as "the server does not say", never as "casual" — same rule as `Seek.rated`. No per-seat
    // rating delta rides on GameEnded, deliberately: rating application is the asynchronous `RatingBatch`, so the
    // delta simply does not exist when the game finishes — the client refetches the profile/leaderboard instead of
    // being handed a number the server would have to invent.
    rated: Option[Boolean] = None,
    // Whether a draw offer from the opponent is currently pending for the side on move (#327).
    drawOffer: Option[DrawOffer] = None,
    // Whether the side to move is permitted to offer a draw on this turn under the alternation rule (#327).
    mayOfferDraw: Option[Boolean] = None
)

/** The full legal-move tree for a game's pending roll, served by `GET /games/{id}/moves` — never capped, unlike the
  * inline `legalMoves` on `PublicGameState`/`DiceRolled`. `version` and `dfen` tie the tree to the roll it answers; the
  * tree is empty when `dicePending` is false (between turns / game over) or the roll is a forced pass.
  */
final case class GameMoves(version: Long, dfen: String, dicePending: Boolean, legalMoves: MoveTree)

/** One completed turn, replayed to a (re)joining client in a `Snapshot` so its move history starts at move 1 rather
  * than at connect time. `dice` is the roll; `moves` are the UCI micro-moves played (empty for a forced pass);
  * `fenAfter` is the resulting position (also the next turn's starting position — the client chains from the opening).
  */
final case class SnapshotTurn(seat: Seat, dice: List[Int], moves: List[String], fenAfter: String)

/** Transport-neutral commands a player submits. NOT WebSocket/HTTP frames — the website WS edge and the Bot API are
  * codecs over this vocabulary.
  */
enum GameCommand:
  case SubmitTurn(
      moves: List[String],
      offerDraw: Boolean = false
  ) // the turn's micro-moves, in UCI; optional draw offer
  // Post-commit dice entropy: a client submits a high-entropy seed after the server has locked its commitment. The
  // server folds both seats' seeds into every roll, so neither the server nor a player can grind the dice. Accepted
  // once per seat, before the first roll; ignored afterwards. The room withholds the opening roll until both seats
  // submit (or a short grace elapses, after which a missing seat falls back to its external id).
  case SubmitSeed(seed: String)
  case Resign
  case RespondDraw(accept: Boolean)

/** Transport-neutral events the room broadcasts. Each carries a monotonic version `v` so clients can order,
  * de-duplicate, and resync.
  */
enum GameEvent:
  // `history` is every completed turn so far, so a client that (re)joins mid-game renders the whole move list, not
  // just what happens after it connected. Atomically consistent with `v` — no separate fetch, no version race.
  case Snapshot(v: Long, state: PublicGameState, history: List[SnapshotTurn])
  // `legalMoves` carries the roll's legal turns (see MoveTree); `None` only when the enumeration exceeded the inline
  // cap — fetch `GET /games/{id}/moves` then. The empty tree announces a forced pass the server plays itself.
  case DiceRolled(
      v: Long,
      seat: Seat,
      dice: List[Int],
      dfen: String,
      clocks: Option[Clocks],
      legalMoves: Option[MoveTree]
  )
  case TurnPlayed(v: Long, seat: Seat, moves: List[String], fenAfter: String)
  case DrawOffered(v: Long, by: Seat)
  case DrawDeclined(v: Long, by: Seat)
  // `seed` is the revealed server seed encoded as hex. Hex-decode it, then SHA-256 the raw bytes to reproduce the
  // `commit` published at creation (and echoed on every snapshot). With `clientSeeds`, the full roll transcript can be
  // recomputed using the canonical length-prefixed message (see `DiceSource.rollMessage`):
  // `roll(ply) = HMAC-SHA256(seed, uint32be(len(white)) ++ white ++ uint32be(len(black)) ++ black ++ int64be(ply))`.
  // Always `Some` in practice — every game reveals here the instant it ends, with nothing withheld (a CRN mirrored
  // pair once withheld both until its partner also concluded, #101/#115; #190 dropped that mechanism). `Option`
  // stays the wire type regardless: un-`Option`ing a field every client already decodes defensively would be a
  // breaking change bought for no behavioural gain.
  case GameEnded(v: Long, over: GameOver, seed: Option[String], clientSeeds: Option[ClientSeeds])
  case Rejected(v: Long, seat: Seat, reason: String)
