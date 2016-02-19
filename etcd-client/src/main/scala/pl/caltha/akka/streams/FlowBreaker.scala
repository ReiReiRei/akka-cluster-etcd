package pl.caltha.akka.streams

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.Cancellable
import akka.stream.{Outlet, Inlet, Attributes, FlowShape}
import akka.stream.scaladsl.Flow
import akka.stream.stage._

import scala.concurrent.{ExecutionContext, Promise}

/**
  * Flow element with a materialized value of type `Cancellable` that allows
  * cancelling upstream and completing downstream flow on demand.
  */
object FlowBreaker {
  def apply[T]: Flow[T, T, Cancellable] = Flow.fromGraph(new FlowBreakerStage[T]).named("flow-breaker")

  private object SameThreadExecutionContext extends ExecutionContext {
    override def execute(runnable: Runnable) = runnable.run()
    override def reportFailure(ex: Throwable) = throw ex
  }

  private class FlowBreakerStage[Elem] extends GraphStageWithMaterializedValue[FlowShape[Elem, Elem], Cancellable] {
    val in = Inlet[Elem]("in")
    val out = Outlet[Elem]("out")

    override val shape = FlowShape(in, out)

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
      val signal = Promise[Unit]()

      val cancellable = new Cancellable {
        val cancelled = new AtomicBoolean(false)

        override def cancel() = {
          val cancelling = cancelled.compareAndSet(false, true)
          if (cancelling) signal.success(())
          cancelling
        }

        override def isCancelled = cancelled.get()
      }

      val logic = new GraphStageLogic(shape) {
        val callback = getAsyncCallback[Unit] { _ ⇒ completeStage() }
        signal.future.map { _ ⇒  callback.invoke(()) }(SameThreadExecutionContext)

        setHandler(in, new InHandler {
          override def onPush() = push(out, grab(in))
        })

        setHandler(out, new OutHandler {
          override def onPull() = pull(in)
        })
      }

      (logic, cancellable)
    }
  }
}