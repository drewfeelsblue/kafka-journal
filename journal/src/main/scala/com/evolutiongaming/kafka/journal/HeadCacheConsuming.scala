package com.evolutiongaming.kafka.journal

import cats.data.{NonEmptyList => Nel}
import cats.effect.{Resource, Timer}
import cats.implicits._
import com.evolutiongaming.catshelper.{BracketThrowable, Log}
import com.evolutiongaming.kafka.journal.HeadCache.{Consumer, ConsumerRecordToKafkaRecord, KafkaRecord}
import com.evolutiongaming.random.Random
import com.evolutiongaming.retry.{OnError, Retry, Strategy}
import com.evolutiongaming.skafka.consumer.ConsumerRecords
import com.evolutiongaming.skafka.{Offset, Partition, Topic}
import com.evolutiongaming.sstream.Stream
import scodec.bits.ByteVector

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object HeadCacheConsuming {

  def apply[F[_] : BracketThrowable : Timer](
    topic: Topic,
    pointers: F[Map[Partition, Offset]],
    pollTimeout: FiniteDuration,
    consumer: Resource[F, Consumer[F]],
    log: Log[F])(implicit
    consumerRecordToKafkaRecord: ConsumerRecordToKafkaRecord[F]
  ): Stream[F, List[(Partition, Nel[KafkaRecord])]] = {

    def kafkaRecords(records: ConsumerRecords[String, ByteVector]): F[List[(Partition, Nel[KafkaRecord])]] = {
      records
        .values
        .toList
        .traverseFilter { case (partition, records) =>
          records
            .toList
            .traverseFilter { record => consumerRecordToKafkaRecord(record).sequence }
            .map { records => records.toNel.map { records => (partition.partition, records) } }
        }
    }

    def poll(consumer: Consumer[F]) = {
      for {
        records <- consumer.poll(pollTimeout)
        records <- kafkaRecords(records)
      } yield records
    }

    def partitions(consumer: Consumer[F]): F[Nel[Partition]] = {

      val partitions = for {
        partitions <- consumer.partitions(topic)
        partitions <- partitions.toList.toNel.fold {
          NoPartitionsError.raiseError[F, Nel[Partition]]
        } { partitions =>
          partitions.pure[F]
        }
      } yield partitions

      val retry = for {
        random <- Random.State.fromClock[F]()
      } yield {
        val strategy = Strategy
          .fullJitter(10.millis, random)
          .limit(1.minute)
        val onError = OnError.fromLog(log.prefixed(s"consumer.partitions"))
        Retry(strategy, onError)
      }

      for {
        retry      <- retry
        partitions <- retry(partitions)
      } yield partitions
    }

    def offsetsOf(partitions: Nel[Partition], pointers: Map[Partition, Offset]) = {
      for {
        partition <- partitions
        offset     = pointers.get(partition).fold(Offset.Min)(_ + 1L)
      } yield {
        (partition, offset)
      }
    }

    val retry = for {
      random <- Random.State.fromClock[F]()
    } yield {
      val strategy = Strategy
        .fullJitter(10.millis, random)
        .cap(1.second)
        .resetAfter(1.minute)
      val onError = OnError.fromLog(log.prefixed("consuming"))
      Retry(strategy, onError)
    }

    def seek(consumer: Consumer[F]) = {
      for {
        partitions <- partitions(consumer)
        _          <- consumer.assign(topic, partitions)
        pointers   <- pointers
        offsets     = offsetsOf(partitions, pointers)
        _          <- consumer.seek(topic, offsets.toList.toMap)
      } yield {}
    }

    for {
      retry    <- Stream.lift(retry)
      _        <- Stream.around(retry.toFunctionK)
      consumer <- Stream.fromResource(consumer)
      _        <- Stream.lift(seek(consumer))
      records  <- Stream.repeat(poll(consumer)) if records.nonEmpty
    } yield records
  }


  case object NoPartitionsError extends RuntimeException("No partitions") with NoStackTrace
}