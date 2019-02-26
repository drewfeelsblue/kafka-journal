package com.evolutiongaming.kafka.journal.eventual.cassandra

import cats.Monad
import cats.effect.{Clock, Concurrent, Resource}
import cats.implicits._
import com.evolutiongaming.kafka.journal._
import com.evolutiongaming.kafka.journal.eventual._
import com.evolutiongaming.kafka.journal.stream.Stream
import com.evolutiongaming.kafka.journal.util.{FromFuture, ToFuture}
import com.evolutiongaming.skafka.Topic

import scala.concurrent.ExecutionContextExecutor


// TODO test EventualCassandra
object EventualCassandra {

  def of[F[_] : Concurrent : Par : Clock : FromFuture : ToFuture : LogOf](
    config: EventualCassandraConfig,
    metrics: Option[EventualJournal.Metrics[F]],
    executor: ExecutionContextExecutor
  ): Resource[F, EventualJournal[F]] = {

    def journal(implicit cassandraCluster: CassandraCluster[F], cassandraSession: CassandraSession[F]) = {
      of(config.schema, metrics)
    }

    for {
      cassandraCluster <- CassandraCluster.of[F](config.client, config.retries, executor)
      cassandraSession <- cassandraCluster.session
      journal          <- Resource.liftF(journal(cassandraCluster, cassandraSession))
    } yield journal
  }

  def of[F[_] : Concurrent : Par : CassandraCluster : CassandraSession : LogOf : Clock : FromFuture : ToFuture](
    schemaConfig: SchemaConfig,
    metrics: Option[EventualJournal.Metrics[F]]
  ): F[EventualJournal[F]] = {

    for {
      log        <- LogOf[F].apply(EventualCassandra.getClass)
      schema     <- SetupSchema[F](schemaConfig)
      statements <- Statements.of[F](schema)
    } yield {
      val journal = apply[F](statements)
      val withLog = journal.withLog(log)
      metrics.fold(withLog) { metrics => withLog.withMetrics(metrics) }
    }
  }


  def apply[F[_] : Monad : Par](statements: Statements[F]): EventualJournal[F] = {

    new EventualJournal[F] {

      def pointers(topic: Topic) = {
        statements.pointers(topic)
      }

      def read(key: Key, from: SeqNr): Stream[F, ReplicatedEvent] = {

        def read(statement: JournalStatement.SelectRecords[F], head: Head) = {

          def read(from: SeqNr) = new Stream[F, ReplicatedEvent] {

            def foldWhileM[L, R](l: L)(f: (L, ReplicatedEvent) => F[Either[L, R]]) = {

              case class S(l: L, seqNr: SeqNr)

              val ff = (s: S, replicated: ReplicatedEvent) => {
                for {
                  result <- f(s.l, replicated)
                } yield {
                  result.leftMap { l => S(l, replicated.event.seqNr) }
                }
              }

              val segment = Segment(from, head.segmentSize)

              (from, segment, l).tailRecM { case (from, segment, l) =>
                val range = SeqRange(from, SeqNr.Max) // TODO do we need range here ?
                for {
                  result <- statement(key, segment.nr, range).foldWhileM[S, R](S(l, from))(ff) // TODO
                } yield result match {
                  case Right(r) => r.asRight[L].asRight[(SeqNr, Segment, L)]
                  case Left(s)  =>
                    val result = for {
                      from    <- s.seqNr.next
                      segment <- segment.next(from)
                    } yield {
                      (from, segment, s.l).asLeft[Either[L, R]]
                    }
                    result getOrElse s.l.asLeft[R].asRight[(SeqNr, Segment, L)]
                }
              }
            }
          }

          head.deleteTo match {
            case None           => read(from)
            case Some(deleteTo) =>
              if (from > deleteTo) read(from)
              else deleteTo.next match {
                case Some(from) => read(from)
                case None       => Stream.empty[F, ReplicatedEvent]
              }
          }
        }

        for {
          head   <- Stream.lift(statements.head(key))
          result <- head.fold(Stream.empty[F, ReplicatedEvent]) { head =>
            read(statements.records, head)
          }
        } yield result
      }

      def pointer(key: Key) = {
        for {
          head <- statements.head(key)
        } yield for {
          head <- head
        } yield {
          Pointer(head.partitionOffset, head.seqNr)
        }
      }
    }
  }


  final case class Statements[F[_]](
    records: JournalStatement.SelectRecords[F],
    head: HeadStatement.Select[F],
    pointers: PointerStatement.SelectPointers[F])

  object Statements {

    def apply[F[_]](implicit F: Statements[F]): Statements[F] = F

    def of[F[_] : Par : Monad : CassandraSession](schema: Schema): F[Statements[F]] = {
      val statements = (
        JournalStatement.SelectRecords.of[F](schema.journal),
        HeadStatement.Select.of[F](schema.head),
        PointerStatement.SelectPointers.of[F](schema.pointer))
      Par[F].mapN(statements)(Statements[F])
    }
  }
}

