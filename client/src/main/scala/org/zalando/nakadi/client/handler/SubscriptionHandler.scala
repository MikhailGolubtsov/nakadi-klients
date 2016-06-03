package org.zalando.nakadi.client.handler

import java.util.concurrent.TimeoutException

import scala.concurrent.ExecutionContext.Implicits.global

import org.slf4j.LoggerFactory
import org.zalando.nakadi.client.actor.SupervisingActor
import org.zalando.nakadi.client.actor.SupervisingActor.SubscribeMsg
import org.zalando.nakadi.client.actor.SupervisingActor.UnsubscribeMsg
import org.zalando.nakadi.client.scala.Connection
import org.zalando.nakadi.client.scala.EventHandler
import org.zalando.nakadi.client.scala.HttpFactory
import org.zalando.nakadi.client.scala.model.Cursor

import com.typesafe.scalalogging.Logger

import akka.NotUsed
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.http.scaladsl.model.EntityStreamException
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.stream.ActorAttributes
import akka.stream.OverflowStrategy
import akka.stream.Supervision
import akka.stream.actor.ActorSubscriber
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Framing
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.stream.StreamTcpException
import akka.stream.StreamTcpException
import scala.util.Success
import scala.util._

trait SubscriptionHandler {
  /**
   * Handles the subscription for an eventHandler.
   */
  def subscribe(eventTypeName: String, endpoint: String, cursor: Option[Cursor], eventHandler: EventHandler)
  def unsubscribe(eventTypeName: String, partition: String, listenerId: String)
  def createPipeline(cursor: Option[Cursor], consumingActor: ActorRef, url: String, eventHandler: EventHandler)
}

class SubscriptionHandlerImpl(val connection: Connection) extends SubscriptionHandler {
  import HttpFactory._
  import SupervisingActor._
  //Local variables
  private implicit val materializer = connection.materializer(decider())

  private val supervisingActor = connection.actorSystem.actorOf(Props(classOf[SupervisingActor], connection, this), "EventReceivingActor-" + System.currentTimeMillis())

  private val RECEIVE_BUFFER_SIZE = 40960
  private val EVENT_DELIMITER = "\n"

  val logger = Logger(LoggerFactory.getLogger(this.getClass))

  def subscribe(eventTypeName: String, endpoint: String, cursor: Option[Cursor], eventHandler: EventHandler) = {
    supervisingActor ! SubscribeMsg(eventTypeName, endpoint, cursor, eventHandler)
  }
  def unsubscribe(eventTypeName: String, partition: String, listenerId: String) = {
    supervisingActor ! UnsubscribeMsg(eventTypeName, partition, listenerId)
  }

  def decider(): Supervision.Decider = {
    case _ => Supervision.Stop
  }

  def createPipeline(cursor: Option[Cursor], consumingActor: ActorRef, url: String, eventHandler: EventHandler) = {

    val subscriber = ActorSubscriber[ByteString](consumingActor)

    //Setup a flow for the request
    val requestFlow = Flow[Option[Cursor]].via(requestCreator(url))
      .via(connection.requestFlow())
      .buffer(RECEIVE_BUFFER_SIZE, OverflowStrategy.fail)
      .via(requestRenderer)
      .withAttributes(ActorAttributes.supervisionStrategy(decider()))

    //create the pipeline
    val result = Source(List(cursor))
      .via(requestFlow).withAttributes(ActorAttributes.supervisionStrategy(decider()))
      .runForeach(_.runWith(Sink.fromSubscriber(subscriber)))

    result.onComplete {
      case Success(requestSource) ⇒
        logger.info("@@@@@@@@@@ YAY @@@@@@@@@@@@@")
      case Failure(e) ⇒
        val msg = "An exception occurred: " + e.getMessage
        eventHandler.handleOnError(url, Some(msg), e)
        logger.error(msg + e)
        Thread.sleep(10000)
        stopActor(consumingActor)
    }
  }

  private def stopActor(actor: ActorRef) = {
    logger.info("Stopping actor {} with a PoisonPill", actor.path.toString())
    actor ! PoisonPill
  }

  private def requestCreator(url: String): Flow[Option[Cursor], HttpRequest, NotUsed] =
    Flow[Option[Cursor]].map(withHttpRequest(url, _, None, connection.tokenProvider))

  private def requestRenderer: Flow[HttpResponse, Source[ByteString, Any], NotUsed] =
    Flow[HttpResponse].filter(x => x.status.isSuccess())
      .map(_.entity.withSizeLimit(Long.MaxValue).dataBytes.via(delimiterFlow))

  private def delimiterFlow = Flow[ByteString]
    .via(Framing.delimiter(ByteString(EVENT_DELIMITER), maximumFrameLength = RECEIVE_BUFFER_SIZE, allowTruncation = true))
}