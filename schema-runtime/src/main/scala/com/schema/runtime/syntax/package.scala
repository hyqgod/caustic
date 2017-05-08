package com.schema.runtime

import akka.actor.Scheduler
import akka.pattern.after
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

package object syntax {

  // Implicit conversions.
  implicit def proxy2ops(proxy: syntax.Proxy): RichTransaction = proxy2txn(proxy)
  implicit def proxy2txn(proxy: syntax.Proxy): Transaction = proxy.owner match {
    case None => proxy.key
    case Some(_) => read(proxy.key)
  }

  // Objects are stored independently from their fields so that different fields of the same object
  // may be simultaneously modified. In order to generate a globally unique key for a field, the
  // library simply concatenates the globally unique object identifier, the reserved field delimiter
  // (which no field or key may contain), and the unique field name.
  val FieldDelimiter: Literal = Literal("@")

  /**
   * Asynchronously executes the transaction generated by the specified function and returns the
   * result of execution.
   *
   * @param f Transaction generator.
   * @param db Implicit database.
   * @param ec Implicit execution context.
   * @return Result of transaction execution, or an exception on failure.
   */
  def Schema(f: Context => Unit)(
    implicit ec: ExecutionContext,
    db: Database
  ): Future[String] = {
    val ctx = Context.empty
    f(ctx)
    db.execute(ctx.txn)
  }

  /**
   * Asynchronously executes the transaction generated by the specified function, returns the result
   * of execution, and automatically retries failures with the specified backoff. Implementation
   * relies on Akka to schedule retries, and is fully compatible with the backoff strategies
   * implemented in Backoff.scala within the Finagle project.
   *
   * @param backoffs Backoff durations.
   * @param f Transaction generator.
   * @param ec Implicit execution context.
   * @param scheduler Implicit Akka scheduler.
   * @param db Implicit database.
   * @return Result of transaction execution, or an exception on retried failure.
   */
  def Schema(backoffs: Stream[FiniteDuration])(f: Context => Unit)(
    implicit ec: ExecutionContext,
    scheduler: Scheduler,
    db: Database
  ): Future[String] =
    Schema(f).recoverWith { case _ if backoffs.nonEmpty =>
      after(backoffs.head, scheduler)(Schema(backoffs.drop(1))(f))
    }

  /**
   * Returns a proxy to the object specified by its globally unique identifier.
   *
   * @param key Globally unique identifier.
   * @param ctx Implicit transaction context.
   * @return Proxy to the requested object.
   */
  def Select(key: Key)(implicit ctx: Context): Proxy = {
    require(!key.contains(FieldDelimiter), "Key may not contain field delimiter.")
    require(key.nonEmpty, "Key must be non-empty.")
    syntax.Proxy(key, None)
  }

  /**
   *
   * @param key
   * @param ctx
   * @return
   */
  def Exists(key: Key)(implicit ctx: Context): Transaction = {
    require(!key.contains(FieldDelimiter), "Key may not contain field delimiter.")
    require(key.nonEmpty, "Key must be non-empty.")
    Select(key).fields.length > 0
  }

  /**
   * Deletes the specified object and its various fields. Deletion requires each object to be mapped
   * to the list of its field names so that they may be efficiently purged. Furthermore, each field
   * modification always requires an additional read to verify that the field name is recorded in
   * the list of the corresponding identifier and may require an additional write in case it has not
   * been already recorded. However, the additional read is inexpensive (at least one read is always
   * performed, and reads are batched together) and the additional write is rarely necessary because
   * most applications have relatively static data models. Delete should only be called on reference
   * types, if delete is called on a field is has undetermined behavior.
   *
   * @param proxy Object to delete.
   * @param ctx Implicit transaction context.
   */
  def Delete(proxy: syntax.Proxy)(implicit ctx: Context): Unit = {
    // Delete all the fields of the object.
    val fields = Collection(proxy.fields)
    For (0, fields.length) { i =>
      ctx += write(read(fields(i)), Literal.Empty)
      ctx += write(fields(i), Literal.Empty)
    }

    // Deletes the object itself.
    ctx += write(fields.length, Literal.Empty)
    ctx += write(proxy.path, Literal.Empty)
  }

  /**
   * Conditionally branches to the first block if the specified comparison is non-empty and to the
   * second otherwise. Implementation relies on structural types (duck typing), and so the language
   * feature scala.language.reflectiveCalls must be in scope to silence compiler warnings.
   *
   * @param cmp Comparison condition.
   * @param success Required If clause.
   * @param ctx Implicit transaction context.
   * @return Optional Else clause.
   */
  def If(cmp: Transaction)(success: => Unit)(implicit ctx: Context) = new {
    private val before = ctx.txn
    ctx.txn = Literal.Empty
    success
    private val pass = ctx.txn
    ctx.txn = before
    ctx += branch(cmp, pass, Literal.Empty)

    def Else(failure: => Unit): Unit = {
      ctx.txn = Literal.Empty
      failure
      val fail = ctx.txn
      ctx.txn = before
      ctx += branch(cmp, pass, fail)
    }
  }

  /**
   *
   * @param from
   * @param to
   * @param by
   * @param block
   * @param ctx
   */
  def For(
    from: Transaction,
    to: Transaction,
    by: Transaction = Literal.One
  )(
    block: Transaction => Unit
  )(
    implicit ctx: Context
  ): Unit = {
    // Reset the context.
    val before = ctx.txn
    ctx.txn = Literal.Empty

    // Perform the loop body, and increment the index.
    block(ctx.index)
    ctx.index = ctx.index + by

    // Reset the context.
    val body = ctx.txn
    ctx.txn = before

    // Set the index and do the loop.
    ctx.index = from
    ctx += loop(ctx.index < to, body)
  }

  /**
   *
   * @param cmp
   * @param block
   */
  def While(cmp: Transaction)(block: => Unit)(implicit ctx: Context): Unit = {
    val before = ctx.txn
    ctx.txn = Literal.Empty
    block
    val body = ctx.txn
    ctx.txn = before
    ctx += loop(cmp, body)
  }

  /**
   * Appends the value of the specified transactions to the transaction builder. Values are
   * concatenated together into a json array for convenience.
   *
   * @param first First transaction to return.
   * @param rest Other transactions to return.
   * @param ctx Implicit transaction context.
   */
  def Return(first: Transaction, rest: Transaction*)(implicit ctx: Context): Unit =
    if (rest.isEmpty)
      ctx += first
    else
      ctx += concat("[", concat(
        rest.+:(first)
          .map(t => concat("\"", concat(t, "\"")))
          .reduceLeft((a, b) => a ++ "," ++ b),
        "]"
      ))

}
