package dicechess.play.wire

import dicechess.play.core.*
import dicechess.play.server.{BotCreateSeek, BotMove, ChallengeTarget, CreateSeek, PlayBot, Wake}
import dicechess.play.wire.Codecs.given
import io.circe.parser.decode
import io.circe.syntax.*

class CodecsSuite extends munit.FunSuite:

  private def roundtrip[A: io.circe.Codec](value: A): Unit =
    assertEquals(decode[A](value.asJson.noSpaces), Right(value))

  test("GameCommand round-trips"):
    roundtrip[GameCommand](GameCommand.SubmitTurn(List("e2e4", "g1f3")))
    roundtrip[GameCommand](GameCommand.SubmitTurn(List("e2e4", "g1f3"), offerDraw = true))
    roundtrip[GameCommand](GameCommand.RespondDraw(accept = true))
    roundtrip[GameCommand](GameCommand.RespondDraw(accept = false))
    roundtrip[GameCommand](GameCommand.SubmitSeed("a1b2c3d4e5f60718"))
    roundtrip[GameCommand](GameCommand.Resign)

  test("GameEvent round-trips"):
    val ps =
      PublicGameState(
        3L,
        "fen",
        Seat.White,
        dicePending = true,
        GameStatus.Active,
        TimeControl.Fischer(300, 3),
        Some(Clocks(300000, 297000)),
        commit = "00ff",
        seed = None,       // active game: seed not yet revealed
        clientSeeds = None // ditto for the client seeds
      )
    // The snapshot carries the completed-turn history; a forced pass is an entry with empty `moves`.
    val history = List(
      SnapshotTurn(Seat.White, List(1, 2, 6), List("e2e4", "g1f3"), "fen-after-1"),
      SnapshotTurn(Seat.Black, List(3), Nil, "fen-after-2")
    )
    roundtrip[GameEvent](GameEvent.Snapshot(3L, ps, history))
    // An ended snapshot reveals the seeds so a late (re)joiner can still open the commitment.
    val endedPs = PublicGameState(
      9L,
      "fen",
      Seat.White,
      dicePending = false,
      GameStatus.Ended(GameOver(GameResult.Win(Side.White), Termination.KingCaptured)),
      TimeControl.Unlimited,
      clocks = None,
      commit = "00ff",
      seed = Some("ab12"),
      clientSeeds = Some(ClientSeeds("w-seed", "b-seed"))
    )
    roundtrip[GameEvent](GameEvent.Snapshot(9L, endedPs, Nil))
    val tree = MoveTree(Map("e2e4" -> MoveTree(Map("g1f3" -> MoveTree.empty)), "a2a3" -> MoveTree.empty))
    roundtrip[GameEvent](
      GameEvent.DiceRolled(1L, Seat.White, List(1, 2, 6), "dfen", Some(Clocks(180000, 175000)), Some(tree))
    )
    roundtrip[GameEvent](GameEvent.DiceRolled(5L, Seat.Black, List(4), "dfen2", None, None))
    roundtrip[GameEvent](GameEvent.DiceRolled(6L, Seat.Black, List(1, 1, 1), "dfen3", None, Some(MoveTree.empty)))
    roundtrip[GameEvent](
      GameEvent.GameEnded(
        9L,
        GameOver(GameResult.Win(Side.Black), Termination.KingCaptured),
        Some("ab12"),
        Some(ClientSeeds("w", "b"))
      )
    )
    roundtrip[GameEvent](
      GameEvent.GameEnded(7L, GameOver(GameResult.Draw, Termination.Aborted), Some("cd34"), Some(ClientSeeds("w", "b")))
    )
    roundtrip[GameEvent](
      GameEvent.GameEnded(
        8L,
        GameOver(GameResult.Win(Side.White), Termination.Timeout),
        Some("ef56"),
        Some(ClientSeeds("w", "b"))
      )
    )
    roundtrip[GameEvent](GameEvent.DrawOffered(7L, Seat.White))
    roundtrip[GameEvent](GameEvent.DrawDeclined(8L, Seat.Black))
    // `None` is a valid wire shape even though nothing ever actually withholds the reveal post-#190 — the type
    // stays `Option` regardless (see the pinned-JSON test below for why), so decode must still accept it.
    roundtrip[GameEvent](GameEvent.GameEnded(10L, GameOver(GameResult.Draw, Termination.Aborted), None, None))
    roundtrip[GameEvent](GameEvent.Rejected(2L, Seat.Black, "nope"))

  test("Principal round-trips"):
    roundtrip[Principal](Principal.Guest("g1"))
    roundtrip[Principal](Principal.User("u1"))
    roundtrip[Principal](Principal.Bot("acme", "v3"))

  test("TimeControl round-trips"):
    roundtrip[TimeControl](TimeControl.Unlimited)
    roundtrip[TimeControl](TimeControl.SuddenDeath(60))
    roundtrip[TimeControl](TimeControl.Fischer(300, 3))
    roundtrip[TimeControl](TimeControl.PerMove(10))

  test("Clocks round-trips"):
    roundtrip[Clocks](Clocks(60000, 58500))

  // Pin the exact on-the-wire shape: the browser/bot client depends on it, so a future
  // codec change must break these, not silently reshape the protocol.

  test("wire format the server accepts (decode)"):
    assertEquals(decode[GameCommand]("""{"Resign":{}}"""), Right(GameCommand.Resign))
    assertEquals(
      decode[GameCommand]("""{"SubmitTurn":{"moves":["e2e4","g1f3"]}}"""),
      Right(GameCommand.SubmitTurn(List("e2e4", "g1f3")))
    )
    assertEquals(
      decode[GameCommand]("""{"SubmitTurn":{"moves":["e2e4"],"offerDraw":true}}"""),
      Right(GameCommand.SubmitTurn(List("e2e4"), offerDraw = true))
    )
    assertEquals(
      decode[GameCommand]("""{"RespondDraw":{"accept":true}}"""),
      Right(GameCommand.RespondDraw(accept = true))
    )
    assertEquals(
      decode[GameCommand]("""{"RespondDraw":{"accept":false}}"""),
      Right(GameCommand.RespondDraw(accept = false))
    )
    assertEquals(
      decode[GameCommand]("""{"SubmitSeed":{"seed":"deadbeefdeadbeef"}}"""),
      Right(GameCommand.SubmitSeed("deadbeefdeadbeef"))
    )

  test("wire format the server emits (encode)"):
    assertEquals((GameCommand.Resign: GameCommand).asJson.noSpaces, """{"Resign":{}}""")
    assertEquals(
      (GameEvent
        .DiceRolled(
          1L,
          Seat.White,
          List(2, 3, 6),
          "fen",
          Some(Clocks(180000, 175000)),
          Some(MoveTree(Map("e2e4" -> MoveTree(Map("g1f3" -> MoveTree.empty)), "a2a3" -> MoveTree.empty)))
        ): GameEvent).asJson.noSpaces,
      """{"DiceRolled":{"v":1,"seat":"White","dice":[2,3,6],"dfen":"fen","clocks":{"white":180000,"black":175000},"legalMoves":{"a2a3":{},"e2e4":{"g1f3":{}}}}}"""
    )
    // Unlimited games carry no clocks: the field is present and null (Circe's default for None). A null legalMoves
    // means the enumeration was over the inline cap — the full tree is at GET /games/{id}/moves.
    assertEquals(
      (GameEvent.DiceRolled(1L, Seat.White, List(2, 3, 6), "fen", None, None): GameEvent).asJson.noSpaces,
      """{"DiceRolled":{"v":1,"seat":"White","dice":[2,3,6],"dfen":"fen","clocks":null,"legalMoves":null}}"""
    )
    // The empty tree is a forced pass the server plays itself — distinct from null (elided by the cap).
    assertEquals(
      (GameEvent
        .DiceRolled(2L, Seat.Black, List(6, 6, 6), "fen", None, Some(MoveTree.empty)): GameEvent).asJson.noSpaces,
      """{"DiceRolled":{"v":2,"seat":"Black","dice":[6,6,6],"dfen":"fen","clocks":null,"legalMoves":{}}}"""
    )
    // A bot that only knows the pre-legalMoves protocol still decodes today's events (the field is additive), and a
    // recorded pre-upgrade event still decodes today (absent key -> None).
    assertEquals(
      decode[GameEvent]("""{"DiceRolled":{"v":1,"seat":"White","dice":[2,3,6],"dfen":"fen","clocks":null}}"""),
      Right(GameEvent.DiceRolled(1L, Seat.White, List(2, 3, 6), "fen", None, None))
    )
    // GameEnded reveals the server seed plus the two client seeds, so the whole roll transcript is verifiable.
    assertEquals(
      (GameEvent.GameEnded(
        3L,
        GameOver(GameResult.Win(Side.White), Termination.KingCaptured),
        Some("ab12"),
        Some(ClientSeeds("w", "b"))
      ): GameEvent).asJson.noSpaces,
      """{"GameEnded":{"v":3,"over":{"result":{"Win":{"side":"White"}},"termination":"KingCaptured"},"seed":"ab12","clientSeeds":{"white":"w","black":"b"}}}"""
    )
    // `seed`/`clientSeeds` are `Option` on the wire even though nothing withholds them post-#190 — pin the `null`
    // shape too, so a future change can't silently narrow the type and break any client still decoding defensively.
    assertEquals(
      (GameEvent.GameEnded(
        4L,
        GameOver(GameResult.Draw, Termination.Aborted),
        None,
        None
      ): GameEvent).asJson.noSpaces,
      """{"GameEnded":{"v":4,"over":{"result":{"Draw":{}},"termination":"Aborted"},"seed":null,"clientSeeds":null}}"""
    )
    // DrawOffered and DrawDeclined pin their shapes (#327)
    assertEquals(
      (GameEvent.DrawOffered(5L, Seat.White): GameEvent).asJson.noSpaces,
      """{"DrawOffered":{"v":5,"by":"White"}}"""
    )
    assertEquals(
      (GameEvent.DrawDeclined(6L, Seat.Black): GameEvent).asJson.noSpaces,
      """{"DrawDeclined":{"v":6,"by":"Black"}}"""
    )
    // A terminal Snapshot is a public surface too: pin its exact shape so a rename/omission of commit/seed/clientSeeds
    // (the dice-fairness trio revealed at game end) breaks the suite rather than silently reshaping the protocol.
    val terminal = PublicGameState(
      9L,
      "fen",
      Seat.White,
      dicePending = false,
      GameStatus.Ended(GameOver(GameResult.Win(Side.White), Termination.KingCaptured)),
      TimeControl.Unlimited,
      clocks = None,
      commit = "c0ffee",
      seed = Some("ab12"),
      clientSeeds = Some(ClientSeeds("w", "b"))
    )
    assertEquals(
      (GameEvent.Snapshot(9L, terminal, Nil): GameEvent).asJson.noSpaces,
      """{"Snapshot":{"v":9,"state":{"version":9,"dfen":"fen","activeSeat":"White","dicePending":false,"status":{"Ended":{"over":{"result":{"Win":{"side":"White"}},"termination":"KingCaptured"}}},"timeControl":{"Unlimited":{}},"clocks":null,"commit":"c0ffee","seed":"ab12","clientSeeds":{"white":"w","black":"b"},"legalMoves":null,"players":null,"rated":null,"drawOffer":null,"mayOfferDraw":null},"history":[]}}"""
    )

  test("Seek and Players pin their wire shapes (who a lobby row / board is looking at)"):
    val botSeek = Seek("seek-7", TimeControl.Unlimited, PlayerKind.Bot, Some("house greedy"), rated = true)
    roundtrip[Seek](botSeek)
    roundtrip[Seek](Seek("seek-8", TimeControl.PerMove(10), PlayerKind.Human, None, rated = false))
    assertEquals(
      botSeek.asJson.noSpaces,
      """{"id":"seek-7","timeControl":{"Unlimited":{}},"kind":"Bot","name":"house greedy","rated":true}"""
    )
    val players = Players(PublicPlayer(PlayerKind.Bot, Some("house greedy")), PublicPlayer(PlayerKind.Human, None))
    roundtrip[Players](players)
    assertEquals(
      players.asJson.noSpaces,
      """{"white":{"kind":"Bot","name":"house greedy","rating":null},"black":{"kind":"Human","name":null,"rating":null}}"""
    )
    // A pre-upgrade recorded state (no players key) still decodes — the field is additive.
    assertEquals(
      decode[PublicGameState](
        """{"version":1,"dfen":"fen","activeSeat":"White","dicePending":false,"status":{"Active":{}},"timeControl":{"Unlimited":{}},"clocks":null,"commit":"c0","seed":null,"clientSeeds":null}"""
      ).map(_.players),
      Right(None)
    )

  test("the live rated flag and seat ratings are additive: absent decodes, present pins its shape (#290)"):
    // A pre-#290 state (no `rated` key, faces without `rating`) still decodes — both fields are additive `Option`s,
    // so old recorded events and un-updated test fixtures keep parsing. This is the #279 lesson applied up front.
    val pre290 =
      """{"version":1,"dfen":"fen","activeSeat":"White","dicePending":false,"status":{"Active":{}},"timeControl":{"Unlimited":{}},"clocks":null,"commit":"c0","seed":null,"clientSeeds":null,"players":{"white":{"kind":"Bot","name":"house greedy"},"black":{"kind":"Human","name":"kind-otter"}}}"""
    assertEquals(decode[PublicGameState](pre290).map(_.rated), Right(None))
    assertEquals(
      decode[PublicGameState](pre290).map(_.players.map(_.white.rating)),
      Right(Some(None))
    )
    // Present, both encode where every other Option on this wire does — a rated game's board says so, and a named
    // face carries its settled rating.
    val ratedFaces = Players(
      PublicPlayer(PlayerKind.Bot, Some("house greedy"), Some(1642.0)),
      PublicPlayer(PlayerKind.Human, Some("kind-otter"), Some(1756.5))
    )
    roundtrip[Players](ratedFaces)
    assertEquals(
      ratedFaces.asJson.noSpaces,
      """{"white":{"kind":"Bot","name":"house greedy","rating":1642.0},"black":{"kind":"Human","name":"kind-otter","rating":1756.5}}"""
    )
    val ratedState = PublicGameState(
      1L,
      "fen",
      Seat.White,
      dicePending = false,
      GameStatus.Active,
      TimeControl.Unlimited,
      clocks = None,
      commit = "c0",
      seed = None,
      clientSeeds = None,
      players = Some(ratedFaces),
      rated = Some(true)
    )
    roundtrip[PublicGameState](ratedState)
    assert(ratedState.asJson.noSpaces.contains(""""rated":true"""))

  test("drawOffer and mayOfferDraw are additive: absent decodes, present pins its shape (#327)"):
    val pre327 =
      """{"version":1,"dfen":"fen","activeSeat":"White","dicePending":true,"status":{"Active":{}},"timeControl":{"Unlimited":{}},"clocks":null,"commit":"c0","seed":null,"clientSeeds":null}"""
    val decoded = decode[PublicGameState](pre327)
    assertEquals(decoded.map(_.drawOffer), Right(None))
    assertEquals(decoded.map(_.mayOfferDraw), Right(None))

    val stateWithOffer = PublicGameState(
      2L,
      "fen",
      Seat.Black,
      dicePending = true,
      GameStatus.Active,
      TimeControl.Unlimited,
      clocks = None,
      commit = "c0",
      seed = None,
      clientSeeds = None,
      drawOffer = Some(DrawOffer(pending = true)),
      mayOfferDraw = Some(false)
    )
    roundtrip[PublicGameState](stateWithOffer)
    assert(stateWithOffer.asJson.noSpaces.contains(""""drawOffer":{"pending":true}"""))
    assert(stateWithOffer.asJson.noSpaces.contains(""""mayOfferDraw":false"""))

  test("MoveTree round-trips and pins its wire shape"):
    val tree = MoveTree(
      Map(
        "e2e4" -> MoveTree(Map("g1f3" -> MoveTree.empty, "b1c3" -> MoveTree.empty)),
        "d2d4" -> MoveTree.empty
      )
    )
    roundtrip[MoveTree](tree)
    roundtrip[MoveTree](MoveTree.empty)
    // A node is the plain object of its children (keys sorted for a stable wire); a childless node is a complete turn.
    assertEquals(tree.asJson.noSpaces, """{"d2d4":{},"e2e4":{"b1c3":{},"g1f3":{}}}""")

  test("GameMoves pins its wire shape"):
    val body = GameMoves(4L, "fen NBK", dicePending = true, MoveTree(Map("e2e4" -> MoveTree.empty)))
    roundtrip[GameMoves](body)
    assertEquals(
      body.asJson.noSpaces,
      """{"version":4,"dfen":"fen NBK","dicePending":true,"legalMoves":{"e2e4":{}}}"""
    )

  /** A defaulted field on a REQUEST body must tolerate its own absence, or adding one silently breaks every client that
    * predates it. Circe's Scala 3 derivation does NOT honour default values: with plain `Codec.AsObject` these decodes
    * fail with "Missing required field", which is exactly what #279 shipped — `POST /bot/seeks`, `POST /lobby/seeks`
    * and `POST /lobby/play-bot` answered 400 to any un-updated client while the published OpenAPI called the field
    * optional.
    *
    * These assertions are the guard: they fail if one of these types ever goes back to `Codec.AsObject`, or if a new
    * defaulted field is added to a type that is not configured for defaults.
    */
  test("a wire type may omit a defaulted field — the default applies, it is not merely declared"):
    assertEquals(decode[BotCreateSeek]("""{}"""), Right(BotCreateSeek()))
    assertEquals(
      decode[BotCreateSeek]("""{"timeControl":{"Unlimited":{}}}"""),
      Right(BotCreateSeek(Some(TimeControl.Unlimited)))
    )
    assertEquals(decode[CreateSeek]("""{}"""), Right(CreateSeek()))
    assertEquals(
      decode[CreateSeek]("""{"creator":"11111111-1111-1111-1111-111111111111"}"""),
      Right(CreateSeek(Some("11111111-1111-1111-1111-111111111111")))
    )
    assertEquals(
      decode[PlayBot]("""{"team":"acme","name":"alice","timeControl":{"PerMove":{"secondsPerMove":10}}}"""),
      Right(PlayBot(None, "acme", "alice", TimeControl.PerMove(10)))
    )
    // Still explicit when sent, in both directions — the default must not swallow a real `true`.
    assertEquals(decode[BotCreateSeek]("""{"rated":true}"""), Right(BotCreateSeek(None, rated = true)))
    assertEquals(decode[CreateSeek]("""{"rated":true}"""), Right(CreateSeek(None, None, rated = true)))
    // `Wake` is a RESPONSE, so no request ever decodes it and its default could not have bitten anyone. Pinned all the
    // same, because it carries a defaulted non-Option field and the rule this suite guards is about the field, not
    // about which direction of the wire the type happens to travel.
    assertEquals(decode[Wake]("""{"alive":true}"""), Right(Wake(alive = true)))
    assertEquals(decode[Wake]("""{"alive":false,"busy":true}"""), Right(Wake(alive = false, busy = true)))
    // #282's additions: the challenge body a pre-#282 bot sends, and the offer type it reads back.
    assertEquals(
      decode[ChallengeTarget]("""{"team":"acme","name":"bob"}"""),
      Right(ChallengeTarget("acme", "bob"))
    )
    assertEquals(
      decode[Challenge](
        """{"id":"c1","challenger":{"Bot":{"team":"acme","name":"a"}},"target":{"Bot":{"team":"acme","name":"b"}},"timeControl":{"Unlimited":{}}}"""
      ),
      Right(Challenge("c1", Principal.Bot("acme", "a"), Principal.Bot("acme", "b")))
    )
    // #327's additions: BotMove and SubmitTurn tolerate missing optional fields
    assertEquals(
      decode[BotMove]("""{"moves":["e2e4"]}"""),
      Right(BotMove(List("e2e4"), offerDraw = false, acceptDraw = None))
    )
    assertEquals(
      decode[BotMove]("""{"acceptDraw":true}"""),
      Right(BotMove(Nil, offerDraw = false, acceptDraw = Some(true)))
    )
    assertEquals(
      decode[BotMove]("""{"acceptDraw":false}"""),
      Right(BotMove(Nil, offerDraw = false, acceptDraw = Some(false)))
    )
    assertEquals(
      decode[BotMove]("""{"moves":["e2e4"],"offerDraw":true}"""),
      Right(BotMove(List("e2e4"), offerDraw = true, acceptDraw = None))
    )
    assertEquals(
      decode[GameCommand]("""{"SubmitTurn":{"moves":["e2e4"]}}"""),
      Right(GameCommand.SubmitTurn(List("e2e4"), offerDraw = false))
    )

  test("switching to ConfiguredCodec did not reshape what the server emits"):
    // `withDefaults` affects decoding only; pin the encoded shape so nothing silently starts omitting fields.
    assertEquals(BotCreateSeek().asJson.noSpaces, """{"timeControl":null,"rated":false}""")
    assertEquals(Wake(alive = true).asJson.noSpaces, """{"alive":true,"busy":false}""")

  test("GameOrigin travels as its lowercase wire name, and only that vocabulary decodes (ADR-005, #47)"):
    assertEquals(GameOrigin.Showcase.asJson.noSpaces, "\"showcase\"")
    assertEquals(GameOrigin.Legacy.asJson.noSpaces, "\"legacy\"")
    GameOrigin.valuesList.foreach { origin =>
      assertEquals(decode[GameOrigin](origin.asJson.noSpaces), Right(origin), s"$origin must round-trip")
    }
    assert(decode[GameOrigin]("\"arena\"").isLeft, "an unknown origin must not decode to a default")
