package chana.jpql

import akka.event.LoggingAdapter
import chana.avro.RecordFlatView
import chana.jpql.nodes._
import org.apache.avro.generic.IndexedRecord

final case class WorkSet(selectedItems: List[Any], orderbys: List[Any])

final class JPQLReducerEvaluator(meta: JPQLMeta, log: LoggingAdapter) extends JPQLEvaluator {

  private var _id: String = null
  def id = _id
  private def id_=(id: String) {
    _id = id
  }

  protected def asToEntity = meta.asToEntity
  protected def asToJoin = meta.asToJoin

  private var idToProjection = Map[String, RecordProjection]()
  private var aggrCaches = Map[AggregateExpr, Number]()

  def reset(_idToProjection: Map[String, RecordProjection]) {
    idToProjection = _idToProjection
    aggrCaches = Map()
  }

  def visitGroupbys(_id: String, record: IndexedRecord): List[Any] = {
    meta.stmt match {
      case SelectStatement(select, from, where, groupby, having, orderby) =>
        id = _id
        groupby.fold(List[Any]()) { x => groupbyClause(x, record) }
      case _ =>
        // not applicable for INSERT/UPDATE/DELETE
        throw new UnsupportedOperationException()
    }
  }

  def visitOneRecord(_id: String, record: IndexedRecord): List[WorkSet] = {
    selectedItems = List()
    meta.stmt match {
      case SelectStatement(select, from, where, groupby, having, orderby) =>
        id = _id

        if (meta.asToJoin.nonEmpty) {
          var res = List[WorkSet]()
          val joinFieldName = meta.asToJoin.head._2.tail.head
          val joinField = record.getSchema.getField(joinFieldName)
          val recordFlatView = new RecordFlatView(record, joinField)
          val flatRecs = recordFlatView.iterator

          while (flatRecs.hasNext) {
            val rec = flatRecs.next
            val havingCond = having.fold(true) { x => havingClause(x, record) }
            if (havingCond) {
              selectClause(select, rec)

              val orderbys = orderby.fold(List[Any]()) { x => orderbyClause(x, rec) }

              res ::= WorkSet(selectedItems.reverse, orderbys)
            }
          }
          res

        } else {

          val havingCond = having.fold(true) { x => havingClause(x, record) }
          if (havingCond) {
            selectClause(select, record)

            val orderbys = orderby.fold(List[Any]()) { x => orderbyClause(x, record) }

            List(WorkSet(selectedItems.reverse, orderbys))
          } else {
            List()
          }
        }

      case _ =>
        // not applicable for INSERT/UPDATE/DELETE
        throw new UnsupportedOperationException()
    }
  }

  override def aggregateExpr(expr: AggregateExpr, record: Any) = {
    aggrCaches.getOrElse(expr, {
      // TODO isDistinct
      val value = expr match {
        case AggregateExpr_AVG(isDistinct, expr) =>
          var sum = 0.0
          var count = 0
          val itr = idToProjection.valuesIterator

          while (itr.hasNext) {
            val dataset = itr.next
            count += 1
            scalarExpr(expr, dataset.projection) match {
              case x: Number => sum += x.doubleValue
              case x         => throw new JPQLRuntimeException(x, "is not a number")
            }
          }
          if (count != 0) sum / count else 0

        case AggregateExpr_MAX(isDistinct, expr) =>
          var max = 0.0
          val itr = idToProjection.valuesIterator

          while (itr.hasNext) {
            val dataset = itr.next
            scalarExpr(expr, dataset.projection) match {
              case x: Number => max = math.max(max, x.doubleValue)
              case x         => throw new JPQLRuntimeException(x, "is not a number")
            }
          }
          max

        case AggregateExpr_MIN(isDistinct, expr) =>
          var min = 0.0
          val itr = idToProjection.valuesIterator

          while (itr.hasNext) {
            val dataset = itr.next
            scalarExpr(expr, dataset.projection) match {
              case x: Number => min = math.min(min, x.doubleValue)
              case x         => throw new JPQLRuntimeException(x, "is not a number")
            }
          }
          min

        case AggregateExpr_SUM(isDistinct, expr) =>
          var sum = 0.0
          val itr = idToProjection.valuesIterator

          while (itr.hasNext) {
            val dataset = itr.next
            scalarExpr(expr, dataset.projection) match {
              case x: Number => sum += x.doubleValue
              case x         => throw new JPQLRuntimeException(x, "is not a number")
            }
          }
          sum

        case AggregateExpr_COUNT(isDistinct, expr) =>
          idToProjection.size
      }
      aggrCaches += (expr -> value)

      value
    })
  }
}
