package io.digitalmagic.akka.dsl

import akka.actor.Props
import io.digitalmagic.akka.dsl.API._
import iotaz.TListK.:::
import iotaz.{Cop, CopK, TNil, TNilK}
import scalaz._
import Scalaz._

import scala.reflect.ClassTag

object Adder {
  trait MyEventType extends Event
  case object MyEvent extends MyEventType

  case class MyState(n: Int = 0) extends PersistentState {
    override type EventType = MyEventType
  }

  val myStateProcessor: PersistentStateProcessor[MyState] = new PersistentStateProcessor[MyState] {
    override def empty: MyState = MyState()
    override def process(state: MyState, event: MyEventType): MyState = event match {
      case MyEvent => state.copy(n = state.n + 1)
    }
  }

  def props(implicit api1: Actor1.Query ~> QueryFuture, api2: Actor2.Query ~> QueryFuture): Props = Props(new Adder())

  case object QueryAndAdd extends Command[Int]
}

trait AdderPrograms extends EventSourcedPrograms {

  import Adder._

  override type Environment = Unit

  override type EntityIdType = Unit

  override type EventType = MyEventType
  override lazy val eventTypeTag: ClassTag[MyEventType] = implicitly

  override type State = MyState
  override lazy val stateTag: ClassTag[MyState] = implicitly
  override lazy val persistentState: PersistentStateProcessor[State] = myStateProcessor

  type QueryAlgebra[A] = CopK[Actor1.Query ::: Actor2.Query ::: TNilK, A]
  override val algebraIsQuery: IsQuery[QueryAlgebra] = implicitly

  override type Index = EmptyIndexList
  override val clientQueryRuntimeInject: ClientQueryRuntimeInject[Index#List, Index#ClientAlgebra] = implicitly
  override val clientEventRuntimeInject: ClientEventRuntimeInject[Index#List, Index#ClientEventAlgebra] = implicitly

  val a1 = new ApiHelper[Actor1.Query, QueryAlgebra, Program] with Actor1.Api[QueryAlgebra, Program]
  val a2 = new ApiHelper[Actor2.Query, QueryAlgebra, Program] with Actor2.Api[QueryAlgebra, Program]

  def queryAndAdd: Program[Int] = for {
    v1 <- a1.getValue
    v2 <- a2.getValue
    _  <- emit(_ => MyEvent)
  } yield v1 + v2
}

class Adder()(implicit val api1: Actor1.Query ~> QueryFuture, val api2: Actor2.Query ~> QueryFuture) extends AdderPrograms with EventSourcedActorWithInterpreter {

  import Adder._

  override def entityId: Unit = ()
  override val persistenceId: String = s"${context.system.name}.MyExampleActor"

  override def interpreter: QueryAlgebra ~> QueryFuture = CopK.NaturalTransformation.summon[QueryAlgebra, QueryFuture]
  override def indexInterpreter: Index#Algebra ~> IndexFuture = CopK.NaturalTransformation.summon[Index#Algebra, IndexFuture]
  override def clientApiInterpreter: Index#ClientAlgebra ~> Const[Unit, ?] = CopK.NaturalTransformation.summon[Index#ClientAlgebra, Const[Unit, ?]]
  override def clientEventInterpreter: ClientEventInterpreter = implicitly

  override def getEnvironment(r: Request[_]): Unit = ()

  override def processState(s: Any): Option[State] = s match {
    case x: State => Some(x)
    case _ => None
  }

  override def getProgram: Request ~> MaybeProgram = Lambda[Request ~> MaybeProgram] {
    case QueryAndAdd => Some(queryAndAdd)
    case _ => None
  }
}