package com.evolutiongaming.kafka.journal

import cats.effect.Sync
import cats.implicits._
import cats.~>
import com.evolutiongaming.kafka.journal.KafkaConverters._

trait AppendAction[F[_]] {
  
  def apply(action: Action): F[PartitionOffset]
}

object AppendAction {

  def apply[F[_] : Sync](producer: Journal.Producer[F]): AppendAction[F] = {
    action: Action => {
      val partitionOffset = for {
        producerRecord  <- Sync[F].delay { action.toProducerRecord }
        partitionOffset <- producer.send(producerRecord)
      } yield partitionOffset
      partitionOffset.handleErrorWith { cause =>
        val error = JournalError(s"failed to append $action", cause.some)
        error.raiseError[F, PartitionOffset]
      }
    }
  }


  implicit class AppendActionOps[F[_]](val self: AppendAction[F]) extends AnyVal {

    def mapK[G[_]](f: F ~> G): AppendAction[G] = new AppendAction[G] {
      def apply(action: Action) = f(self(action))
    }
  }
}