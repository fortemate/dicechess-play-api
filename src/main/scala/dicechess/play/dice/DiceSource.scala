package dicechess.play.dice

import cats.effect.IO
import cats.syntax.all.*

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.collection.mutable.ListBuffer

/** Source of a turn's three dice. The server is authoritative over the RNG.
  *
  * `roll` is deterministic and **position-independent** — it depends only on the seeds and the ply index, never on the
  * moves played. That is what lets a tournament replay the exact same dice to a colour-swapped mirror game to cancel
  * luck.
  */
trait DiceSource:
  /** The three dice (each in 1..6) for the given ply, folding in both clients' seeds.
    *
    * The client seeds are supplied at roll time (not at construction): the server commits the server seed *before* it
    * has seen any client seed, then folds the clients' post-commit entropy into every roll — so neither side can grind
    * the dice. The seeds are fixed for the whole game (the room passes the same pair on every ply).
    */
  def roll(ply: Long, clientSeedW: String, clientSeedB: String): List[Int]

  /** SHA-256 of the server seed, published at game start (the commitment). */
  def commit: String

  /** The server seed (hex), published at game end so any roll can be re-verified. */
  def reveal: String

object DiceSource:

  private val DiceCount = 3
  private val Faces     = 6
  // Largest multiple of 6 below 256; reject bytes >= this to avoid modulo bias.
  private val Cutoff = 252

  /** Commit-reveal dice: `roll(ply, clientW, clientB) = HMAC-SHA256(serverSeed, msg)`, where `msg` is the canonical
    * length-prefixed message `uint32be(len(clientW)) ++ clientW ++ uint32be(len(clientB)) ++ clientB ++ int64be(ply)`
    * (see `rollMessage`), mapped to three unbiased values in 1..6. Commit the SHA-256 of `serverSeed` before the game,
    * reveal `serverSeed` (and the client seeds) after — anyone can then re-derive every roll. The client seeds arrive
    * at roll time, *after* the commit, so the server cannot have chosen its seed to bias them.
    */
  def commitReveal(serverSeed: Array[Byte]): DiceSource =
    val seed = serverSeed.clone()
    new DiceSource:
      def commit: String                                                       = hex(sha256(seed))
      def reveal: String                                                       = hex(seed)
      def roll(ply: Long, clientSeedW: String, clientSeedB: String): List[Int] =
        derive(seed, rollMessage(clientSeedW, clientSeedB, ply))

  /** Mint a fresh commit-reveal source with a 32-byte CSPRNG server seed. */
  def newCommitReveal(): IO[DiceSource] =
    IO:
      val seed = new Array[Byte](32)
      SecureRandom().nextBytes(seed)
      commitReveal(seed)

  /** Rebuild a source from a persisted server seed (the hex form `reveal` returns) — used to resume a game after a
    * restart with its committed dice sequence intact. A malformed (corrupt) seed is an error value, so one bad row can
    * be skipped instead of failing the whole resume.
    */
  def fromHexSeed(hex: String): Either[String, DiceSource] =
    Either
      .catchOnly[NumberFormatException](hex.grouped(2).map(pair => Integer.parseInt(pair, 16).toByte).toArray)
      .bimap(_ => s"malformed server seed (not hex): $hex", commitReveal)

  /** Canonical, unambiguous HMAC message: length-prefixed seeds + ply, so different (clientW, clientB) splits can never
    * collide (e.g. ("a|b","c") vs ("a","b|c")).
    */
  private def rollMessage(clientSeedW: String, clientSeedB: String, ply: Long): Array[Byte] =
    val white = clientSeedW.getBytes(UTF_8)
    val black = clientSeedB.getBytes(UTF_8)
    ByteBuffer
      .allocate(4 + white.length + 4 + black.length + 8)
      .putInt(white.length)
      .put(white)
      .putInt(black.length)
      .put(black)
      .putLong(ply)
      .array()

  /** Three unbiased dice via rejection sampling. Bytes >= Cutoff are rejected (no modulo bias); if a block is exhausted
    * we derive the next HMAC block keyed by a counter and keep sampling — so the result is always total AND unbiased.
    */
  private def derive(seed: Array[Byte], base: Array[Byte]): List[Int] =
    val dice  = ListBuffer.empty[Int]
    var block = 0
    while dice.size < DiceCount do
      val bytes = hmac(seed, base ++ intBytes(block))
      var i     = 0
      while dice.size < DiceCount && i < bytes.length do
        val b = bytes(i) & 0xff
        if b < Cutoff then dice += (b % Faces) + 1
        i += 1
      block += 1
    dice.toList

  private def intBytes(n: Int): Array[Byte] = ByteBuffer.allocate(4).putInt(n).array()

  private def hmac(key: Array[Byte], message: Array[Byte]): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(message)

  private def sha256(data: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(data)

  private def hex(bytes: Array[Byte]): String =
    bytes.map(b => f"${b & 0xff}%02x").mkString
