package com.evolutiongaming.skafka.consumer

import java.lang.{Long => LongJ}
import java.util.regex.Pattern

import com.evolutiongaming.skafka.Converters._
import com.evolutiongaming.skafka._
import com.evolutiongaming.skafka.consumer.ConsumerConverters._
import org.apache.kafka.clients.consumer.{KafkaConsumer, Consumer => ConsumerJ}

import scala.collection.JavaConverters._
import scala.collection.immutable.Iterable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

object CreateConsumer {

  def apply[K, V](
    config: ConsumerConfig,
    ecBlocking: ExecutionContext)(implicit
    valueFromBytes: FromBytes[V],
    keyFromBytes: FromBytes[K],
    ec: ExecutionContext): Consumer[K, V] = {

    val valueDeserializer = valueFromBytes.asJava
    val keyDeserializer = keyFromBytes.asJava
    val consumer = new KafkaConsumer(config.properties, keyDeserializer, valueDeserializer)
    apply(consumer, ecBlocking)
  }

  def apply[K, V](consumer: ConsumerJ[K, V], ecBlocking: ExecutionContext): Consumer[K, V] = {

    def blocking[T](f: => T): Future[T] = Future(f)(ecBlocking)

    new Consumer[K, V] {

      def assign(partitions: Iterable[TopicPartition]) = {
        val partitionsJ = partitions.map(_.asJava).asJavaCollection
        consumer.assign(partitionsJ)
      }

      def assignment(): Set[TopicPartition] = {
        val partitionsJ = consumer.assignment()
        partitionsJ.asScala.map(_.asScala).toSet
      }

      def subscribe(topics: Iterable[Topic]) = {
        val topicsJ = topics.asJavaCollection
        consumer.subscribe(topicsJ)
      }

      def subscribe(topics: Iterable[Topic], listener: RebalanceListener) = {
        val topicsJ = topics.asJavaCollection
        consumer.subscribe(topicsJ, listener.asJava)
      }

      def subscribe(pattern: Pattern, listener: RebalanceListener) = {
        consumer.subscribe(pattern, listener.asJava)
      }

      def subscribe(pattern: Pattern) = {
        consumer.subscribe(pattern)
      }

      def subscription(): Set[Topic] = {
        consumer.subscription().asScala.toSet
      }

      def unsubscribe() = {
        consumer.unsubscribe()
      }

      def poll(timeout: FiniteDuration): Future[ConsumerRecords[K, V]] = {
        blocking {
          val records = consumer.poll(timeout.toMillis)
          records.asScala
        }
      }

      def commitSync() = {
        consumer.commitSync()
      }

      def commitSync(offsets: Map[TopicPartition, OffsetAndMetadata]) = {
        val offsetsJ = offsets.asJavaMap(_.asJava, _.asJava)
        consumer.commitSync(offsetsJ)
      }

      def commitAsync() = {
        consumer.commitAsync()
      }

      def commitAsync(callback: CommitCallback) = {
        consumer.commitAsync(callback.asJava)
      }

      def commitAsync(offsets: Map[TopicPartition, OffsetAndMetadata], callback: CommitCallback) = {
        val offsetsJ = offsets.asJavaMap(_.asJava, _.asJava)
        consumer.commitAsync(offsetsJ, callback.asJava)
      }

      def seek(partition: TopicPartition, offset: Offset) = {
        consumer.seek(partition.asJava, offset)
      }

      def seekToBeginning(partitions: Iterable[TopicPartition]) = {
        val partitionsJ = partitions.map(_.asJava).asJavaCollection
        consumer.seekToBeginning(partitionsJ)
      }

      def seekToEnd(partitions: Iterable[TopicPartition]) = {
        val partitionsJ = partitions.map(_.asJava).asJavaCollection
        consumer.seekToEnd(partitionsJ)
      }

      def position(partition: TopicPartition): Offset = {
        consumer.position(partition.asJava)
      }

      def committed(partition: TopicPartition) = {
        val partitionJ = partition.asJava
        val offsetAndMetadataJ = consumer.committed(partitionJ)
        offsetAndMetadataJ.asScala
      }

      def partitionsFor(topic: Topic) = {
        val partitionInfosJ = consumer.partitionsFor(topic)
        partitionInfosJ.asScala.map(_.asScala).toList
      }

      def listTopics(): Map[Topic, List[PartitionInfo]] = {
        val result = consumer.listTopics()
        result.asScalaMap(k => k, _.asScala.map(_.asScala).toList)
      }

      def pause(partitions: Iterable[TopicPartition]) = {
        val partitionsJ = partitions.map(_.asJava).asJavaCollection
        consumer.pause(partitionsJ)
      }

      def paused(): Set[TopicPartition] = {
        val partitionsJ = consumer.paused()
        partitionsJ.asScala.map(_.asScala).toSet
      }

      def resume(partitions: Iterable[TopicPartition]) = {
        val partitionsJ = partitions.map(_.asJava).asJavaCollection
        consumer.resume(partitionsJ)
      }

      def offsetsForTimes(timestampsToSearch: Map[TopicPartition, Offset]) = {
        val timestampsToSearchJ = timestampsToSearch.asJavaMap(_.asJava, LongJ.valueOf)
        val result = consumer.offsetsForTimes(timestampsToSearchJ)
        result.asScalaMap(_.asScala, v => Option(v).map(_.asScala))
      }

      def beginningOffsets(partitions: Iterable[TopicPartition]) = {
        val partitionsJ = partitions.map(_.asJava).asJavaCollection
        val result = consumer.beginningOffsets(partitionsJ)
        result.asScalaMap(_.asScala, v => v)
      }

      def endOffsets(partitions: Iterable[TopicPartition]) = {
        val partitionsJ = partitions.map(_.asJava).asJavaCollection
        val result = consumer.endOffsets(partitionsJ)
        result.asScalaMap(_.asScala, v => v)
      }

      def close() = {
        blocking {
          consumer.close()
        }
      }

      def close(timeout: FiniteDuration) = {
        blocking {
          consumer.close(timeout.length, timeout.unit)
        }
      }

      def wakeup() = consumer.wakeup()
    }
  }
}

