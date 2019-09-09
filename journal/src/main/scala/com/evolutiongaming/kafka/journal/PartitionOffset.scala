package com.evolutiongaming.kafka.journal

import cats.implicits._
import cats.{Order, Show}
import com.datastax.driver.core.{GettableByNameData, SettableData}
import com.evolutiongaming.scassandra.syntax._
import com.evolutiongaming.scassandra.{DecodeRow, EncodeRow}
import com.evolutiongaming.skafka.consumer.ConsumerRecord
import com.evolutiongaming.skafka.{Offset, Partition}

final case class PartitionOffset(
  partition: Partition = Partition.Min,
  offset: Offset = Offset.Min) {

  override def toString = s"$partition:$offset"
}

object PartitionOffset {

  val Empty: PartitionOffset = PartitionOffset()

  implicit val EncodeRowPartitionOffset: EncodeRow[PartitionOffset] = new EncodeRow[PartitionOffset] {

    def apply[B <: SettableData[B]](data: B, value: PartitionOffset) = {
      data
        .encode("partition", value.partition)
        .encode("offset", value.offset)
    }
  }

  implicit val DecodeRowPartitionOffset: DecodeRow[PartitionOffset] = (data: GettableByNameData) => {
    PartitionOffset(
      partition = data.decode[Partition]("partition"),
      offset = data.decode[Offset]("offset"))
  }


  implicit val showPartitionOffset: Show[PartitionOffset] = Show.fromToString[PartitionOffset]


  implicit val orderPartitionOffset: Order[PartitionOffset] = Order.whenEqual(
    Order.by { a: PartitionOffset => a.partition },
    Order.by { a: PartitionOffset => a.offset })


  def apply(record: ConsumerRecord[_, _]): PartitionOffset = {
    PartitionOffset(
      partition = record.topicPartition.partition,
      offset = record.offset)
  }
}