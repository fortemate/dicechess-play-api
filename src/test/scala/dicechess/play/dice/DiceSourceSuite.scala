package dicechess.play.dice

import java.security.MessageDigest

class DiceSourceSuite extends munit.FunSuite:

  private val seed: Array[Byte] = (0 until 32).map(_.toByte).toArray

  private def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  test("every roll is three dice in 1..6"):
    val source = DiceSource.commitReveal(seed)
    (0L until 200L).foreach: ply =>
      val dice = source.roll(ply, "alice", "bob")
      assertEquals(dice.size, 3, s"ply $ply")
      assert(dice.forall(d => d >= 1 && d <= 6), s"out of range at ply $ply: $dice")

  test("rolls are deterministic for the same seeds"):
    val a = DiceSource.commitReveal(seed)
    val b = DiceSource.commitReveal(seed)
    assertEquals(
      (0L until 50L).map(p => a.roll(p, "alice", "bob")).toList,
      (0L until 50L).map(p => b.roll(p, "alice", "bob")).toList
    )

  test("commit is the SHA-256 of the revealed seed"):
    val source   = DiceSource.commitReveal(seed)
    val revealed = source.reveal.grouped(2).map(h => Integer.parseInt(h, 16).toByte).toArray
    assertEquals(source.commit, sha256Hex(revealed))

  test("different client seeds produce different roll sequences"):
    // Same committed server seed, different post-commit client seeds → different dice.
    val source = DiceSource.commitReveal(seed)
    assertNotEquals(
      (0L until 50L).map(p => source.roll(p, "alice", "bob")).toList,
      (0L until 50L).map(p => source.roll(p, "carol", "dave")).toList
    )

  test("client-seed boundaries do not collide (canonical encoding)"):
    // ("a|b","c") and ("a","b|c") must not hash to the same dice stream.
    val source = DiceSource.commitReveal(seed)
    assertNotEquals(
      (0L until 30L).map(p => source.roll(p, "a|b", "c")).toList,
      (0L until 30L).map(p => source.roll(p, "a", "b|c")).toList
    )
