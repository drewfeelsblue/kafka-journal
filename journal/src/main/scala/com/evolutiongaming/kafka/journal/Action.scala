package com.evolutiongaming.kafka.journal

import com.evolutiongaming.kafka.journal.Aliases.SeqNr
import play.api.libs.json._

sealed trait Action

object Action {

  implicit val JsonFormat: OFormat[Action] = {

    val AppendFormat = new OFormat[Append] {
      def writes(value: Append) = {
        Json.obj("seqNr" -> Json.obj("from" -> value.from, "to" -> value.to))
      }
      def reads(json: JsValue) = {
        for {
          seqNr <- (json \ "seqNr").validate[JsValue]
          from <- (seqNr \ "from").validate[SeqNr]
          to <- (seqNr \ "to").validate[SeqNr]
        } yield Append(from, to)
      }
    }
    
    val TruncateFormat = new OFormat[Truncate] {
      def writes(value: Truncate) = {
        Json.obj("seqNr" -> Json.obj("to" -> value.to))
      }
      def reads(json: JsValue) = {
        for {
          to <- (json \ "seqNr" \ "to").validate[SeqNr]
        } yield Truncate(to)
      }
    }

    val ReadFormat = Json.format[Read]

    new OFormat[Action] {

      def reads(json: JsValue): JsResult[Action] = {
        def read[T](name: String, reads: Reads[T]) = {
          (json \ name).validate(reads)
        }

        read("append", AppendFormat) orElse read("read", ReadFormat) orElse read("truncate", TruncateFormat)
      }

      def writes(action: Action): JsObject = {

        def write[T](name: String, value: T, writes: Writes[T]) = {
          val json = writes.writes(value)
          Json.obj(name -> json)
        }

        action match {
          case action: Append   => write("append", action, AppendFormat)
          case action: Read     => write("read", action, ReadFormat)
          case action: Truncate => write("truncate", action, TruncateFormat)
        }
      }
    }
  }


  sealed trait AppendOrTruncate extends Action
  case class Append(from: SeqNr, to: SeqNr) extends AppendOrTruncate
  case class Truncate(to: SeqNr) extends AppendOrTruncate
  case class Read(id: String) extends Action
}