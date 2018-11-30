package com.evolutiongaming.kafka.journal

import java.time.Instant

import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, Timer}
import cats.implicits._
import com.evolutiongaming.kafka.journal.eventual.TopicPointers
import com.evolutiongaming.kafka.journal.util.IOSuite._
import com.evolutiongaming.nel.Nel
import com.evolutiongaming.skafka._
import com.evolutiongaming.skafka.consumer.ConsumerRecords
import org.scalatest.{AsyncWordSpec, Matchers}

import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class HeadCacheSpec extends AsyncWordSpec with Matchers {
  import HeadCacheSpec._

  "HeadCache" should {

    "return result, records are in cache" in {
      val offsetLast = 10l

      implicit val eventual = HeadCache.Eventual.empty[cats.effect.IO]

      val key = Key(id = "id", topic = topic)
      val records = ConsumerRecordsOf {
        for {
          idx <- (0l to offsetLast).toList
          seqNr <- SeqNr.opt(idx + 1)
        } yield {
          val action = Action.Append(key, timestamp, None, Nel(Event(seqNr)))
          ConsumerRecordOf(action, topicPartition, idx)
        }
      }

      val state = TestConsumer.State(
        topics = Map((topic, List(partition))),
        records = Queue(records))

      val result = for {
        ref <- Ref.of[cats.effect.IO, cats.effect.IO[TestConsumer.State]](state.pure[cats.effect.IO])
        consumer = TestConsumer(ref)
        headCache <- headCacheOf(consumer.pure[cats.effect.IO])
        result <- headCache(key = key, partition = partition, offset = offsetLast)
        state <- ref.get
        state <- state
        _ <- headCache.close
      } yield {
        state shouldEqual TestConsumer.State(
          assigns = List(TestConsumer.Assign(topic, Nel(partition))),
          seeks = List(TestConsumer.Seek(topic, Map((partition, 0)))),
          topics = Map((topic, List(partition))))

        result shouldEqual Some(HeadCache.Result(seqNr = Some(SeqNr(11)), deleteTo = None))
      }

      result.run
    }

    "return result, all events are already replicated and cache is empty" in {
      val marker = 10l

      val pointers = Map((partition, marker))
      implicit val eventual = HeadCache.Eventual.const(TopicPointers(pointers).pure[cats.effect.IO])

      val state = TestConsumer.State(
        topics = Map((topic, List(partition))))

      val key = Key(id = "id", topic = topic)

      val result = for {
        ref <- Ref.of[cats.effect.IO, cats.effect.IO[TestConsumer.State]](state.pure[cats.effect.IO])
        consumer = TestConsumer(ref)
        headCache <- headCacheOf(consumer.pure[cats.effect.IO])
        result <- headCache(key = key, partition = partition, offset = marker)
        state <- ref.get
        state <- state
        _ <- headCache.close
      } yield {
        state shouldEqual TestConsumer.State(
          assigns = List(TestConsumer.Assign(topic, Nel(partition))),
          seeks = List(TestConsumer.Seek(topic, Map((partition, marker + 1)))),
          topics = Map((topic, List(partition))))

        result shouldEqual Some(HeadCache.Result(seqNr = None, deleteTo = None))
      }

      result.run
    }

    "return result, after events are replicated" in {
      val marker = 100l

      implicit val eventual = HeadCache.Eventual.empty[cats.effect.IO]

      val state = TestConsumer.State(
        topics = Map((topic, List(0))))

      val key = Key(id = "id", topic = topic)
      val result = for {
        ref <- Ref.of[cats.effect.IO, cats.effect.IO[TestConsumer.State]](state.pure[cats.effect.IO])
        consumer = TestConsumer(ref)
        headCache <- headCacheOf(consumer.pure[cats.effect.IO])
        result <- Concurrent[cats.effect.IO].start {
          headCache(key = key, partition = partition, offset = marker)
        }
        _ <- ref.update { state =>
          for {
            state <- state
          } yield {
            val action = Action.Mark(key, timestamp, None, "mark")
            val record = ConsumerRecordOf(action, topicPartition, marker)
            val records = ConsumerRecordsOf(List(record))
            state.copy(records = state.records.enqueue(records))
          }
        }
        result <- result.join
        state <- ref.get
        state <- state
      } yield {
        state shouldEqual TestConsumer.State(
          assigns = List(TestConsumer.Assign(topic, Nel(0))),
          seeks = List(TestConsumer.Seek(topic, Map((partition, 0)))),
          topics = Map((topic, List(partition))))

        result shouldEqual Some(HeadCache.Result(seqNr = None, deleteTo = None))
      }

      result.run
    }

    "clean cache after events are being replicated" ignore {
      val key = Key(id = "id", topic = topic)

      val offsetLast = 10l
      val records = for {
        offset <- (0l until offsetLast).toList
        seqNr <- SeqNr.opt(offset + 1)
      } yield {
        val action = Action.Append(key, timestamp, None, Nel(Event(seqNr)))
        val record = ConsumerRecordOf(action, topicPartition, offset)
        ConsumerRecordsOf(List(record))
      }

      val state = TestConsumer.State(
        topics = Map((topic, List(0))),
        records = Queue(records: _*))

      val result = for {
        pointers <- Ref.of[cats.effect.IO, Map[Partition, Offset]](Map.empty)
        consumerState <- Ref.of[cats.effect.IO, cats.effect.IO[TestConsumer.State]](state.pure[cats.effect.IO])
        consumer = TestConsumer(consumerState).pure[cats.effect.IO]
        headCache <- {
          val topicPointers = for {
            pointers <- pointers.get
          } yield TopicPointers(pointers)
          implicit val eventual = HeadCache.Eventual.const(topicPointers)
          headCacheOf(consumer)
        }
        result <- headCache(
          key = key,
          partition = partition,
          offset = offsetLast)
        _ <- pointers.update { pointers => pointers ++ Map((partition, offsetLast)) }
      } yield {
        state shouldEqual TestConsumer.State(
          assigns = List(TestConsumer.Assign(topic, Nel(0))),
          seeks = List(TestConsumer.Seek(topic, Map((partition, 0)))),
          topics = Map((topic, List(partition))))

        result shouldEqual Some(HeadCache.Result(seqNr = None, deleteTo = None))
      }

      result.run
    }

    "invalidate cache in case exceeding maxSize" in {
      val state = TestConsumer.State(
        topics = Map((topic, List(0))))

      val config = HeadCacheSpec.config.copy(maxSize = 1)

      val result = for {
        pointers <- Ref.of[cats.effect.IO, Map[Partition, Offset]](Map.empty)
        ref <- Ref.of[cats.effect.IO, cats.effect.IO[TestConsumer.State]](state.pure[cats.effect.IO])
        consumer = TestConsumer(ref)
        headCache <- {
          val topicPointers = for {
            pointers <- pointers.get
          } yield TopicPointers(pointers)
          implicit val eventual = HeadCache.Eventual.const(topicPointers)
          headCacheOf(consumer.pure[cats.effect.IO], config)
        }
        key0 = Key(id = "id0", topic = topic)
        key1 = Key(id = "id1", topic = topic)
        enqueue = (key: Key, offset: Offset) => {
          ref.update { state =>
            for {
              state <- state
            } yield {
              val action = Action.Append(key, timestamp, None, Nel(Event(SeqNr.Min)))
              val record = ConsumerRecordOf(action, topicPartition, offset)
              val records = ConsumerRecordsOf(List(record))
              state.copy(records = state.records.enqueue(records))
            }
          }
        }
        _ <- enqueue(key0, 0l)
        r0 <- headCache(key0, partition, 0l)
        _ <- enqueue(key1, 1l)
        r1 <- headCache(key0, partition, 1l)
        r2 <- headCache(key1, partition, 1l)
        _ <- pointers.update(_ ++ Map((partition, 1l)))
        r3 <- headCache(key1, partition, 1l)
        _ <- enqueue(key0, 2l)
        r4 <- headCache(key0, partition, 2l)
        state <- ref.get
        state <- state
      } yield {
        state shouldEqual TestConsumer.State(
          assigns = List(TestConsumer.Assign(topic, Nel(0))),
          seeks = List(TestConsumer.Seek(topic, Map((partition, 0)))),
          topics = Map((topic, List(partition))))
        r0 shouldEqual Some(HeadCache.Result(seqNr = Some(SeqNr.Min), deleteTo = None))
        r1 shouldEqual None
        r2 shouldEqual None
        r3 shouldEqual None
        r4 shouldEqual Some(HeadCache.Result(seqNr = Some(SeqNr.Min), deleteTo = None))
      }

      result.run
    }

    "close" in {
      val key = Key(id = "id", topic = topic)

      val state = TestConsumer.State(
        topics = Map((topic, List(0))))

      val offset = Offset.Min
      val pointers = Map((partition, offset))
      implicit val eventual = HeadCache.Eventual.const(TopicPointers(pointers).pure[cats.effect.IO])
      val result = for {
        consumerState <- Ref.of[cats.effect.IO, cats.effect.IO[TestConsumer.State]](state.pure[cats.effect.IO])
        consumer = TestConsumer(consumerState).pure[cats.effect.IO]
        headCache <- headCacheOf(consumer)
        _ <- headCache(
          key = key,
          partition = partition,
          offset = offset)
        _ <- headCache.close
        state <- consumerState.get
        state <- state.attempt
      } yield {
        state shouldEqual Left(TestConsumer.ClosedException)
      }

      result.run
    }
  }
}

object HeadCacheSpec {
  val timestamp: Instant = Instant.now()
  val topic: Topic = "topic"
  val partition: Partition = 0
  val topicPartition: TopicPartition = TopicPartition(topic = topic, partition = partition)
  val config: HeadCache.Config = HeadCache.Config(
    pollTimeout = 3.millis,
    cleanInterval = 100.millis)

  def headCacheOf(
    consumer: cats.effect.IO[HeadCache.Consumer[cats.effect.IO]],
    config: HeadCache.Config = config)(implicit
    eventual: HeadCache.Eventual[cats.effect.IO]): cats.effect.IO[HeadCache[cats.effect.IO]] = {

    HeadCache.of[cats.effect.IO](
      config = config,
      consumer = consumer)
  }

  implicit val log: Log[cats.effect.IO] = Log.empty[cats.effect.IO]

  object TestConsumer {

    def apply(ref: Ref[cats.effect.IO, cats.effect.IO[State]])(implicit timer: Timer[cats.effect.IO]): HeadCache.Consumer[cats.effect.IO] = {
      new HeadCache.Consumer[cats.effect.IO] {

        def assign(topic: Topic, partitions: Nel[Partition]) = {
          ref.update { state =>
            for {
              state <- state
            } yield {
              state.copy(assigns = Assign(topic, partitions) :: state.assigns)
            }
          }
        }

        def seek(topic: Topic, offsets: Map[Partition, Offset]) = {
          ref.update { state =>
            for {
              state <- state
            } yield {
              state.copy(seeks = Seek(topic, offsets) :: state.seeks)
            }
          }
        }

        def poll(timeout: FiniteDuration) = {
          for {
            _ <- timer.sleep(timeout)
            records <- ref.modify { state =>
              val result = for {
                state <- state
              } yield {
                state.records.dequeueOption match {
                  case None                    => (state, ConsumerRecords.empty[Id, Bytes])
                  case Some((record, records)) =>
                    val stateUpdated = state.copy(records = records)
                    (stateUpdated, record)
                }
              }

              // TODO use unzip like combinator
              (result.map(_._1), result.map(_._2))
            }
            records <- records
          } yield {
            records
          }
        }

        // TODO what if partition is not created ?
        def partitions(topic: Topic) = {
          for {
            state <- ref.get
            state <- state
            partitions <- cats.effect.IO.delay {
              val partitions = state.topics.getOrElse(topic, Nil)
              Nel.unsafe(partitions)
            }
          } yield {
            partitions
          }
        }

        def close = {
          val closed = cats.effect.IO.raiseError(ClosedException)
          ref.set(closed)
        }
      }
    }

    def of(state: State)(implicit timer: Timer[cats.effect.IO]): cats.effect.IO[HeadCache.Consumer[cats.effect.IO]] = {
      for {
        ref <- Ref.of[cats.effect.IO, cats.effect.IO[State]](state.pure[cats.effect.IO])
      } yield {
        apply(ref)
      }
    }


    final case class Assign(topic: Topic, partitions: Nel[Partition])

    final case class Seek(topic: Topic, offsets: Map[Partition, Offset])

    final case class State(
      assigns: List[Assign] = List.empty,
      seeks: List[Seek] = List.empty,
      topics: Map[Topic, List[Partition]] = Map.empty,
      records: Queue[ConsumerRecords[Id, Bytes]] = Queue.empty)

    object State {
      val Empty: State = State()
    }

    case object ClosedException extends RuntimeException("consumer is closed") with NoStackTrace
  }
}