package dicechess.play.dice

import dicechess.play.core.LaboratoryProtocol
import dicechess.play.core.LaboratoryProtocol.DicePair
import io.circe.{Json, parser}

import java.nio.file.{Files, Paths}

class LaboratoryDiceSourceSuite extends munit.CatsEffectSuite:
  private val seed = (0 until 32).map(_.toByte).toArray

  private def value[A](result: Either[?, A]): A = result.fold(error => fail(error.toString), identity)

  private val fixture = value(
    parser.parse(
      Files.readString(
        Paths.get("contributor-docs/public/contracts/laboratory-dice-v1-vectors.json")
      )
    )
  )
  private val vectors = value(fixture.hcursor.get[List[Json]]("vectors"))

  test("fixture pins the captured laboratory algorithm"):
    assertEquals(value(fixture.hcursor.get[String]("algorithm")), LaboratoryProtocol.DiceAlgorithm)

  vectors.foreach: vector =>
    val c    = vector.hcursor
    val name = value(c.get[String]("name"))
    test(s"independent published vector: $name"):
      val seedHex = value(c.get[String]("serverSeed"))
      val source  = value(LaboratoryDiceSource.fromHexSeed(seedHex))
      val white   = value(c.downField("clientSeeds").get[String]("white"))
      val black   = value(c.downField("clientSeeds").get[String]("black"))
      val turn    = value(c.get[Long]("turnNumber"))
      val offers  = value(c.get[List[List[Int]]]("offers"))
      assertEquals(source.algorithm, LaboratoryProtocol.DiceAlgorithm)
      assertEquals(source.commit, value(c.get[String]("commit")))
      assertEquals(source.reveal, seedHex.toLowerCase(java.util.Locale.ROOT))
      assertEquals(value(source.offers(turn, white, black)).values.map(_.values), offers)
      // Keep classic code and existing tests untouched; pin its independent legacy transcript here too.
      val classic = value(DiceSource.fromHexSeed(seedHex))
      assertEquals(classic.roll(turn, white, black), value(c.get[List[Int]]("classicDice")))

  test("duplicate commands, out-of-order calls and restart never advance either offer stream"):
    val source   = value(LaboratoryDiceSource.commitReveal(seed))
    val expected = value(source.offers(3, "alice", "bob"))
    (0L until 30L).reverse.foreach(turn => value(source.offers(turn, "alice", "bob")))
    assertEquals(value(source.offers(3, "alice", "bob")), expected)
    val restored = value(LaboratoryDiceSource.fromHexSeed(source.reveal, source.algorithm))
    assertEquals(restored.commit, source.commit)
    assertEquals(value(restored.offers(3, "alice", "bob")), expected)

  test("constructor clones the seed; caller mutation cannot change its commitment or offers"):
    val supplied = seed.clone()
    val source   = value(LaboratoryDiceSource.commitReveal(supplied))
    val expected = value(source.offers(0, "alice", "bob"))
    val commit   = source.commit
    val reveal   = source.reveal
    java.util.Arrays.fill(supplied, 99.toByte)
    assertEquals(source.commit, commit)
    assertEquals(source.reveal, reveal)
    assertEquals(value(source.offers(0, "alice", "bob")), expected)

  test("recovery accepts uppercase hex but refuses unknown algorithm versions"):
    val source = value(LaboratoryDiceSource.commitReveal(seed))
    assertEquals(
      value(LaboratoryDiceSource.fromHexSeed(source.reveal.toUpperCase(java.util.Locale.ROOT))).reveal,
      source.reveal
    )
    assertEquals(
      LaboratoryDiceSource.fromHexSeed(source.reveal, "future-v2"),
      Left("unsupported laboratory dice algorithm")
    )

  test("invalid server seeds fail without echoing the submitted input"):
    List(0, 1, 31, 33, 64).foreach: size =>
      assert(LaboratoryDiceSource.commitReveal(new Array[Byte](size)).isLeft)
    List("", "a" * 63, "b" * 65, "gg" * 32, "+1" * 32, " " + "c" * 64).foreach: bad =>
      assertEquals(LaboratoryDiceSource.fromHexSeed(bad), Left("laboratory server seed must be 64 hex characters"))

  test("turn identity fits the exact JSON integer contract and rejects invalid values"):
    val source = value(LaboratoryDiceSource.commitReveal(seed))
    List(-1L, LaboratoryProtocol.MaxWireVersion + 1, Long.MaxValue).foreach: turn =>
      assertEquals(source.offers(turn, "alice", "bob"), Left("invalid laboratory turn number"))

  test("malformed UTF-16 client input is rejected instead of silently colliding with replacement characters"):
    val source = value(LaboratoryDiceSource.commitReveal(seed))
    assertEquals(source.offers(0, "\ud800", "bob"), Left("laboratory client seed is not valid Unicode"))
    assertEquals(source.offers(0, "alice", "\udc00"), Left("laboratory client seed is not valid Unicode"))

  test("every accepted unsigned byte maps to the prescribed die without modulo bias"):
    val counts = Array.fill(6)(0)
    (0 until 252).foreach: byte =>
      val pair = value(LaboratoryDiceSource.samplePair(_ => Array(byte.toByte, byte.toByte)))
      assertEquals(pair, DicePair(byte % 6 + 1, byte % 6 + 1))
      counts(pair.first - 1) += 1
    assertEquals(counts.toList, List.fill(6)(42))

  test("252 through 255 are rejected; 251 is accepted and generated order is preserved"):
    val pair = value(LaboratoryDiceSource.samplePair(_ => Array(252, 253, 254, 255, 251, 250).map(_.toByte)))
    assertEquals(pair, DicePair(6, 5))

  test("refill retains an accepted die and starts the next numbered block"):
    val calls = scala.collection.mutable.ListBuffer.empty[Long]
    val pair  = value(LaboratoryDiceSource.samplePair: block =>
      calls += block
      if block == 0 then Array(255.toByte, 0.toByte)
      else Array(252.toByte, 251.toByte))
    assertEquals(pair, DicePair(1, 6))
    assertEquals(calls.toList, List(0L, 1L))

  test("counter exhaustion is a deterministic error, never a wrap or biased fallback"):
    val calls  = scala.collection.mutable.ListBuffer.empty[Long]
    val result = LaboratoryDiceSource.samplePair(
      block =>
        calls += block
        Array.fill(32)(255.toByte)
      ,
      lastBlock = 1
    )
    assertEquals(result, Left("laboratory dice counter exhausted"))
    assertEquals(calls.toList, List(0L, 1L))

  test("fresh CSPRNG source has a restorable 32-byte seed"):
    LaboratoryDiceSource
      .newCommitReveal()
      .map: source =>
        assertEquals(source.reveal.length, 64)
        val restored = value(LaboratoryDiceSource.fromHexSeed(source.reveal))
        assertEquals(source.commit, restored.commit)
        assertEquals(source.offers(0, "alice", "bob"), restored.offers(0, "alice", "bob"))
