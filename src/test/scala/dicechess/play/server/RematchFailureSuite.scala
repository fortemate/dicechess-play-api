package dicechess.play.server

import dicechess.play.core.GameId
import dicechess.play.store.CorruptRematchRecord
import java.sql.SQLException
import java.util.concurrent.TimeoutException

class RematchFailureSuite extends munit.FunSuite:
  test("failure diagnostics retain causes and SQLSTATE without raw messages or private row values"):
    val secret = "private-seat-token-and-snapshot"
    val sql    = new SQLException(s"Failing row contains ($secret)", "23514")
    val error  = new RuntimeException(secret, sql)
    val log    = RematchFailure.describe(error)
    assert(log.contains("java.lang.RuntimeException"))
    assert(log.contains("java.sql.SQLException(sqlState=23514)"))
    assert(!log.contains(secret))
    assert(RematchFailure.describe(new TimeoutException(secret)).contains("TimeoutException"))
    assert(!RematchFailure.describe(new SQLException(secret, secret)).contains(secret))

  test("corrupt row diagnostics retain safe table, game and field context"):
    val id  = GameId("11111111-1111-1111-1111-111111111111")
    val log = RematchFailure.describe(CorruptRematchRecord("rematch_successors", id, "initial_snapshot"))
    assert(log.contains(s"rematch_successors/${id.value}/initial_snapshot"))

  test("cyclic cause chains are bounded"):
    val first  = new RuntimeException("first")
    val second = new RuntimeException("second", first)
    first.initCause(second)
    assertEquals(RematchFailure.describe(first).split(" caused by ").length, 4)
