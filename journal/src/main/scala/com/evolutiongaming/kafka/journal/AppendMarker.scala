package com.evolutiongaming.kafka.journal

import cats.FlatMap
import cats.effect.Clock
import cats.syntax.all._

trait AppendMarker[F[_]] {
  
  def apply(key: Key): F[Marker]
}

object AppendMarker {

  def apply[F[_] : FlatMap : RandomIdOf : Clock](
    produce: Produce[F],
  ): AppendMarker[F] = {

    key: Key => {
      for {
        randomId        <- RandomIdOf[F].apply
        partitionOffset <- produce.mark(key, randomId)
      } yield {
        Marker(randomId.value, partitionOffset)
      }
    }
  }
}
