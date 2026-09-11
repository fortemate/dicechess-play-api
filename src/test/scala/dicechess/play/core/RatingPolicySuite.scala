package dicechess.play.core

import dicechess.play.core.RatingDomain.*
import dicechess.play.core.RatingPolicy.{Legacy, Matrix}

/** Pure — the whole eligibility matrix of #146, row by row, under both policies. The batch's own skips (own bot,
  * deleted account, missing result) are downstream of this and live in `RatingBatchSuite`.
  */
class RatingPolicySuite extends munit.FunSuite:

  private val alice = Principal.User("11111111-1111-1111-1111-111111111111")
  private val carol = Principal.User("22222222-2222-2222-2222-222222222222")
  private val bot   = Principal.Bot("acme", "alpha")
  private val rival = Principal.Bot("acme", "beta")
  private val anon  = Principal.Bot(RatingPolicy.AnonBotTeam, "x1")
  private val guest = Principal.Guest("33333333-3333-3333-3333-333333333333")
  private val Blitz = TimeControl.Fischer(300, 3)

  private def under(
      policy: RatingPolicy,
      white: Principal,
      black: Principal,
      requested: Boolean = true,
      control: TimeControl = Blitz,
      ladder: Boolean = false
  ) = RatingPolicy.classify(policy, white, black, requested, control, ladder)

  test("participant kinds come from the identity shape, never from a name"):
    assertEquals(ParticipantKind.of(alice), ParticipantKind.Human)
    assertEquals(ParticipantKind.of(bot), ParticipantKind.Bot)
    assertEquals(ParticipantKind.of(anon), ParticipantKind.Bot)
    assertEquals(ParticipantKind.of(guest), ParticipantKind.Guest)
    assertEquals(ParticipantKind.fromWireName("human"), Some(ParticipantKind.Human))
    assertEquals(ParticipantKind.fromWireName("Human"), None)

  test("legacy: any two registered, non-anonymous participants play rated on a bounded control, whatever the pairing"):
    for (w, b) <- List((alice, carol), (bot, rival), (alice, bot), (bot, alice)) do
      val c = under(Legacy, w, b)
      assert(c.rated, s"$w vs $b")
      assertEquals(c.domain, Competitive)
      assertEquals(c.policy, Legacy)
      assert(c.requestedRated)

  test("both policies: a guest or anonymous seat, self-play, an unbounded control or no request are casual"):
    for policy <- RatingPolicy.values do
      for (w, b) <- List((guest, bot), (bot, guest), (anon, bot), (alice, anon), (guest, guest)) do
        val c = under(policy, w, b)
        assert(!c.rated && c.domain == Casual, s"$policy: $w vs $b must be casual, was $c")
      assertEquals(under(policy, alice, alice).domain, Casual, "self-play carries no rating information")
      assertEquals(under(policy, alice, carol, control = TimeControl.Unlimited).domain, Casual)
      assertEquals(under(policy, alice, carol, control = TimeControl.PerMove(30)).domain, Casual)
      val unrequested = under(policy, alice, carol, requested = false)
      assert(!unrequested.rated && unrequested.domain == Casual && !unrequested.requestedRated)

  test("matrix: two accounts compete"):
    val c = under(Matrix, alice, carol)
    assert(c.rated)
    assertEquals(c.domain, Competitive)
    assertEquals(c.policy.version, 2)

  test("matrix: two bots compete only when the ladder scheduler paired them; a direct game is casual"):
    val scheduled = under(Matrix, bot, rival, ladder = true)
    assert(scheduled.rated)
    assertEquals(scheduled.domain, Competitive)
    val direct = under(Matrix, bot, rival)
    assert(!direct.rated, "a direct bot challenge or seek does not move the canonical bot rating")
    assertEquals(direct.domain, Casual)
    assert(direct.requestedRated, "the request is still recorded as what it was")

  test("matrix: a human against a bot is a training game — rated:false, domain training, whichever seat"):
    for (w, b) <- List((alice, bot), (bot, alice)) do
      val c = under(Matrix, w, b)
      assert(!c.rated, "no canonical rating may move")
      assertEquals(c.domain, Training)
    // …but only when it was asked for: an unrequested human-bot game is plain casual, not training.
    assertEquals(under(Matrix, alice, bot, requested = false).domain, Casual)
    // and the ladder flag changes nothing for a mixed pairing.
    assertEquals(under(Matrix, alice, bot, ladder = true).domain, Training)

  test("legacy is what every game before the policy existed was classified under — version 1"):
    assertEquals(Legacy.version, 1)
    assertEquals(Matrix.version, 2)
    assertEquals(RatingPolicy.parse(None), None)
    assertEquals(RatingPolicy.parse(Some("matrix")), Some(Matrix))
    assertEquals(RatingPolicy.parse(Some(" Legacy ")), Some(Legacy))
    assertEquals(RatingPolicy.parse(Some("v2")), None, "an unknown value is not a policy; the caller falls back")

  test("the wire vocabularies round-trip"):
    RatingDomain.values.foreach(d => assertEquals(RatingDomain.fromWireName(d.wireName), Some(d)))
    RatingOutcome.values.foreach(o => assertEquals(RatingOutcome.fromWireName(o.wireName), Some(o)))
    assertEquals(RatingOutcome.fromWireName("stamped"), None)
