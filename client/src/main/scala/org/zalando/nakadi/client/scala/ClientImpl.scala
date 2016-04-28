package org.zalando.nakadi.client.scala

import scala.{ Left, Right }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import org.slf4j.LoggerFactory
import com.typesafe.scalalogging.Logger
import akka.actor.Terminated
import akka.http.scaladsl.model.{ HttpHeader, HttpMethod, HttpMethods, HttpResponse, MediaRange }
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{ Accept, RawHeader }
import akka.http.scaladsl.unmarshalling.Unmarshal
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import org.zalando.nakadi.client.scala.model.JacksonJsonMarshaller
import org.zalando.nakadi.client.Deserializer
import org.zalando.nakadi.client.Serializer
import org.zalando.nakadi.client.scala.model._
import org.zalando.nakadi.client.ClientError



private[client] class ClientImpl(connection: Connection, charSet: String = "UTF-8") extends Client with HttpFactory {
  implicit val materializer = connection.materializer

  val logger = Logger(LoggerFactory.getLogger(this.getClass))
  def getMetrics()(implicit des: Deserializer[Metrics]): Future[Either[ClientError, Option[Metrics]]] = {
    logFutureEither(connection.get(URI_METRICS).flatMap(mapToEither(_)))
  }

  def getEventTypes()(implicit des: Deserializer[Seq[EventType]]): Future[Either[ClientError, Option[Seq[EventType]]]] = {
    logFutureEither(connection.get(URI_EVENT_TYPES).flatMap(mapToEither(_)))
  }

  def createEventType(eventType: EventType)(implicit ser: Serializer[EventType]): Future[Option[ClientError]] = {
    logFutureOption(connection.post(URI_EVENT_TYPES, eventType).flatMap(mapToOption(_)))
  }

  def getEventType(name: String)(implicit des: Deserializer[EventType]): Future[Either[ClientError, Option[EventType]]] = {
    logFutureEither(connection.get(URI_EVENT_TYPE_BY_NAME.format(name)).flatMap(in => mapToEither(in)))
  }

  def updateEventType(name: String, eventType: EventType)(implicit ser: Serializer[EventType]): Future[Option[ClientError]] = {
    val result = connection.put(URI_EVENT_TYPE_BY_NAME.format(name), eventType)
    logFutureOption(result.flatMap(in => mapToOption(in)))
  }

  def deleteEventType(name: String): Future[Option[ClientError]] = {
    logFutureOption(connection.delete(URI_EVENT_TYPE_BY_NAME.format(name)).flatMap(in => mapToOption(in)))
  }

  def publishEvents[T <: Event](eventTypeName: String, events: Seq[T])(implicit ser: Serializer[Seq[T]]): Future[Option[ClientError]] = {
    logFutureOption(connection.post(URI_EVENTS_OF_EVENT_TYPE.format(eventTypeName), events).flatMap(in => mapToOption(in)))
  }

  def publishEvent[T <: Event](name: String, event: T)(implicit ser: Serializer[T]): Future[Option[ClientError]] = {
    logFutureOption(connection.post(URI_EVENTS_OF_EVENT_TYPE.format(name), event).flatMap(in => mapToOption(in)))
  }

  def getPartitions(name: String)(implicit des: Deserializer[Seq[Partition]]): Future[Either[ClientError, Option[Seq[Partition]]]] = {
    logFutureEither(connection.get(URI_PARTITIONS_BY_EVENT_TYPE.format(name)).flatMap(in => mapToEither(in)))
  }

  def getValidationStrategies()(implicit des: Deserializer[Seq[EventValidationStrategy.Value]]): Future[Either[ClientError, Option[Seq[EventValidationStrategy.Value]]]] = {
    logFutureEither(connection.get(URI_VALIDATION_STRATEGIES).flatMap(mapToEither(_)))
  }

  def getEnrichmentStrategies()(implicit des: Deserializer[Seq[EventEnrichmentStrategy.Value]]): Future[Either[ClientError, Option[Seq[EventEnrichmentStrategy.Value]]]] = {
    logFutureEither(connection.get(URI_ENRICHMENT_STRATEGIES).flatMap(mapToEither(_)))
  }

  def getPartitioningStrategies()(implicit des: Deserializer[Seq[PartitionStrategy.Value]]): Future[Either[ClientError, Option[Seq[PartitionStrategy.Value]]]] =
    logFutureEither(connection.get(URI_PARTITIONING_STRATEGIES).flatMap(mapToEither(_)))

  def stop(): Future[Option[ClientError]] = {
    val result = Await.ready(connection.stop(), Duration.Inf)
    Future.successful(None)
  }

  def subscribe[T <: Event](eventType: String, params: StreamParameters, listener: Listener[T])(implicit des: Deserializer[T]): Future[Option[ClientError]] = {
    (eventType, params, listener) match {

      case (_, _, listener) if listener == null =>
        logger.info("listener is null")
        Future.successful(Option(ClientError("Listener may not be empty(null)!", None)))

      case (eventType, _, _) if Option(eventType).isEmpty || eventType == "" =>
        logger.info("eventType is null")
        Future.successful(Option(ClientError("Eventype may not be empty(null)!", None)))

      case (eventType, StreamParameters(cursor, _, _, _, _, _, _), listener) if Option(eventType).isDefined =>
        val url = URI_EVENTS_OF_EVENT_TYPE.format(eventType)

        val request = withHttpRequest(url, HttpMethods.GET,
          RawHeader("Accept", "application/x-json-stream") :: withHeaders(Option(params)), //Headers
          connection.tokenProvider(), Option(params))

        logger.debug("Subscribing listener {} - cursor {} - parameters {} - eventType {} ", listener.id, cursor, params, eventType)
        connection.subscribe(url, request, listener)
        Future.successful(None)
    }
  }

  def unsubscribe[T <: Event](eventType: String, listener: Listener[T]): Future[Option[ClientError]] = ???

  //####################
  //#  HELPER METHODS  #
  //####################

  private[client] def logFutureEither[A, T](future: Future[Either[ClientError, T]]): Future[Either[ClientError, T]] = {
    future recover {
      case e: Throwable =>
        logger.error("A unexpected error occured", e)
        Left(ClientError("Error: " + e.getMessage, None))
    }
  }
  private[client] def logFutureOption(future: Future[Option[ClientError]]): Future[Option[ClientError]] = {
    future recover {
      case e: Throwable =>
        logger.error("A unexpected error occured", e)
        Option(ClientError("Error: " + e.getMessage, None))
    }
  }

  private[client] def mapToEither[T](response: HttpResponse)(implicit deserializer: Deserializer[T]): Future[Either[ClientError, Option[T]]] = {
    logger.debug("received [response={}]", response)
    response match {
      case HttpResponse(status, headers, entity, protocol) if (status.isSuccess()) =>
        try {
          Unmarshal(entity).to[String].map(body => Right(Some(deserializer.from(body))))
        } catch {
          case e: Throwable =>
            val msg = "Failed to deserialise the content with error: %s".format(e.getMessage)
            logger.error(msg)
            Future.successful(Left(ClientError(msg, Some(status.intValue()))))
        }
      case HttpResponse(StatusCodes.NotFound, headers, entity, protocol) =>
        Future.successful(Right(None))
      case HttpResponse(status, headers, entity, protocol) if (status.isRedirection()) =>
        val msg = "Not implemented: http-status (" + status.intValue() + "}) and reason:" + status.reason()
        logger.info(msg)
        Future.successful(Left(ClientError(msg, Some(status.intValue()))))
      case HttpResponse(status, headers, entity, protocol) if (status.isFailure()) =>
        Unmarshal(entity).to[String].map { body =>
          val msg = "An error occurred, http-status: %s (%s) Message: %s".format(status.intValue(), status.reason(), body)
          logger.warn(msg)
          Left(ClientError(msg, Some(status.intValue())))
        }
    }
  }

  private[client] def mapToOption[T](response: HttpResponse): Future[Option[ClientError]] = {
    response.status match {
      case status if (status.isSuccess()) =>
        logger.debug("Success. http-status: %s", status.intValue().toString())
        Future.successful(None)
      case status if (status.isRedirection()) =>
        val msg = "Redirection - http-status: %s, reason[%s]".format(status.intValue().toString(), status.reason())
        logger.info(msg)
        response.entity.toStrict(10.second).map { body =>
          logger.debug("Redirection - http-status: %s, reason[%s], body:[%s]".format(status.intValue().toString(), status.reason(), body.data.decodeString(charSet)))
        }
        Future.successful(Option(ClientError(msg, Some(status.intValue()))))
      case status if (status.isFailure()) =>
        response.entity.toStrict(10.second).map { body =>
          val msg = "Failure - http-status: %s, reason[%s], body:[%s]".format(status.intValue().toString(), status.reason(), body.data.decodeString(charSet))
          logger.warn(msg)
          Option(ClientError(msg, Some(status.intValue())))
        }
    }
  }

}
