package app.cash.sqldelight.driver.r2dbc

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import io.r2dbc.spi.Connection
import io.r2dbc.spi.Statement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

class R2dbcDriver(
  val connection: Connection,
  /**
   * This callback is called after [close]. It either contains an error or null, representing a successful close.
   */
  val closed: (Throwable?) -> Unit = { },
) : SqlDriver {
  override fun <R> executeQuery(
    identifier: Int?,
    sql: String,
    mapper: (SqlCursor) -> QueryResult<R>,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?,
  ): QueryResult<R> {
    val prepared = connection.createStatement(sql).also { statement ->
      R2dbcPreparedStatement(statement).apply { if (binders != null) this.binders() }
    }

    return QueryResult.AsyncValue {
      val result = prepared.execute().awaitSingle()

      val rowSet = result.map { row, rowMetadata ->
        List(rowMetadata.columnMetadatas.size) { index -> row.get(index) }
      }.asFlow().toList()

      return@AsyncValue mapper(R2dbcCursor(rowSet)).await()
    }
  }

  override fun execute(
    identifier: Int?,
    sql: String,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?,
  ): QueryResult<Long> {
    val prepared = connection.createStatement(sql).also { statement ->
      R2dbcPreparedStatement(statement).apply { if (binders != null) this.binders() }
    }

    return QueryResult.AsyncValue {
      val result = prepared.execute().awaitSingle()
      return@AsyncValue result.rowsUpdated.awaitFirstOrNull() ?: 0L
    }
  }

  private val transactions = ThreadLocal<Transacter.Transaction>()
  private var transaction: Transacter.Transaction?
    get() = transactions.get()
    set(value) {
      transactions.set(value)
    }

  override fun newTransaction(): QueryResult<Transacter.Transaction> = QueryResult.AsyncValue {
    val enclosing = transaction
    val transaction = Transaction(enclosing, connection)
    this.transaction = transaction

    if (enclosing == null) {
      connection.beginTransaction().awaitFirstOrNull()
    }

    return@AsyncValue transaction
  }

  override fun currentTransaction(): Transacter.Transaction? = transaction

  override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit
  override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit
  override fun notifyListeners(vararg queryKeys: String) = Unit

  override fun close() {
    // Normally, this is just a Mono, so it completes directly without onNext.
    // But the standard allows any publisher, so we should request unlimited items
    // and wait until the close call is finally completed.
    connection.close().subscribe(object : Subscriber<Void> {
      override fun onSubscribe(sub: Subscription) {
        sub.request(Long.MAX_VALUE)
      }

      override fun onError(error: Throwable) {
        closed(error)
      }

      override fun onComplete() {
        closed(null)
      }

      override fun onNext(t: Void) {
        // Do nothing, we wait until completion.
      }
    })
  }

  private inner class Transaction(
    override val enclosingTransaction: Transacter.Transaction?,
    private val connection: Connection,
  ) : Transacter.Transaction() {
    override fun endTransaction(successful: Boolean): QueryResult<Unit> = QueryResult.AsyncValue {
      if (enclosingTransaction == null) {
        if (successful) {
          connection.commitTransaction().awaitFirstOrNull()
        } else {
          connection.rollbackTransaction().awaitFirstOrNull()
        }
      }
      transaction = enclosingTransaction
    }
  }
}

/**
 * Creates and returns a [R2dbcDriver] with the given [connection].
 *
 * The scope waits until the driver is closed [R2dbcDriver.close].
 */
fun CoroutineScope.R2dbcDriver(
  connection: Connection,
): R2dbcDriver {
  val completed = Job()
  val driver = R2dbcDriver(connection) {
    if (it == null) {
      completed.complete()
    } else {
      completed.completeExceptionally(it)
    }
  }
  launch {
    completed.join()
  }
  return driver
}

// R2DBC uses boxed Java classes instead primitives: https://r2dbc.io/spec/1.0.0.RELEASE/spec/html/#datatypes
class R2dbcPreparedStatement(private val statement: Statement) : SqlPreparedStatement {
  override fun bindBytes(index: Int, bytes: ByteArray?) {
    if (bytes == null) {
      statement.bindNull(index, ByteArray::class.java)
    } else {
      statement.bind(index, bytes)
    }
  }

  override fun bindLong(index: Int, long: Long?) {
    if (long == null) {
      statement.bindNull(index, Long::class.javaObjectType)
    } else {
      statement.bind(index, long)
    }
  }

  override fun bindDouble(index: Int, double: Double?) {
    if (double == null) {
      statement.bindNull(index, Double::class.javaObjectType)
    } else {
      statement.bind(index, double)
    }
  }

  override fun bindString(index: Int, string: String?) {
    if (string == null) {
      statement.bindNull(index, String::class.java)
    } else {
      statement.bind(index, string)
    }
  }

  override fun bindBoolean(index: Int, boolean: Boolean?) {
    if (boolean == null) {
      statement.bindNull(index, Boolean::class.javaObjectType)
    } else {
      statement.bind(index, boolean)
    }
  }

  fun bindObject(index: Int, any: Any?) {
    if (any == null) {
      statement.bindNull(index, Any::class.java)
    } else {
      statement.bind(index, any)
    }
  }
}

/**
 * TODO: Write a better async cursor API
 */
class R2dbcCursor(val rowSet: List<List<Any?>>) : SqlCursor {
  var row = -1
    private set

  override fun next(): QueryResult.Value<Boolean> = QueryResult.Value(++row < rowSet.size)

  override fun getString(index: Int): String? = rowSet[row][index] as String?

  override fun getLong(index: Int): Long? = (rowSet[row][index] as Number?)?.toLong()

  override fun getBytes(index: Int): ByteArray? = rowSet[row][index] as ByteArray?

  override fun getDouble(index: Int): Double? = rowSet[row][index] as Double?

  override fun getBoolean(index: Int): Boolean? = rowSet[row][index] as Boolean?

  inline fun <reified T : Any> getObject(index: Int): T? = rowSet[row][index] as T?

  @Suppress("UNCHECKED_CAST")
  fun <T> getArray(index: Int): Array<T>? = rowSet[row][index] as Array<T>?
}
