package org.zalando.nakadi.client.utils

import org.zalando.nakadi.client.scala.model._

object TestScalaEntity {
  //
  //  Simple bjects composed out of scalar typers only (without dependency to other Object-models)
  val problem = new Problem("problemType", "title", 312, Option("detail"), Option("instance"))
  val metrics = new Metrics(Map("metrics" -> "test"))
  val partition = new Partition(0, 132, 4423)
  val cursor = new Cursor(0, 120)
  val eventTypeSchema = new EventTypeSchema(SchemaType.JSON, "schema")
  val eventValidationStrategy = EventValidationStrategy.NONE
  val partitionResolutionStrategy = PartitionStrategy.HASH
  val eventEnrichmentStrategy = EventEnrichmentStrategy.METADATA

  //Complex objects
  val eventTypeStatistics = new EventTypeStatistics(Option(9281002), Option(19283), Option(21), Option(312))
  val eventType = new EventType("name", "owner", EventTypeCategory.BUSINESS, Option(List(EventValidationStrategy.NONE)), List(EventEnrichmentStrategy.METADATA), Some(partitionResolutionStrategy), Option(eventTypeSchema), Option(List("dataKeyFields")), Option(List("partitioningKeyFields")), Option(eventTypeStatistics))
  val eventMetadata = new EventMetadata("eid", Option(eventType), "occurredAt", Option("receivedAt"), List("parentEids"), Option("flowId"), Option("partition"))
  case class MyEvent(name: String, metadata: Option[EventMetadata]) extends Event
  val myEvent = new MyEvent("test", Some(eventMetadata))
  val eventStreamBatch = new EventStreamBatch[MyEvent](cursor, Option(List(myEvent)))
  val batchItemResponse = new BatchItemResponse(Option("eid"), BatchItemPublishingStatus.SUBMITTED, Option(BatchItemStep.PUBLISHING), Option("detail"))

  // custom event
  case class CommissionEntity(id: String, ql: List[String])
  val commissionEntity = new CommissionEntity("id2", List("ql1", "ql2"))
  val dataChangeEvent = new DataChangeEvent[CommissionEntity](commissionEntity, "Critical", DataOperation.DELETE, Some(eventMetadata))
}