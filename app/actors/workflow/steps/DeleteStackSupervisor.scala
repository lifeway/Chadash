package actors.workflow.steps

import actors.WorkflowLog.{Log, LogMessage}
import actors.workflow.steps.DeleteStackSupervisor.{DeleteStackData, DeleteStackStates}
import actors.workflow.tasks.DeleteStack.{DeleteStackCommand, StackDeleteRequested}
import actors.workflow.tasks.StackDeleteCompleteMonitor.StackDeleteCompleted
import actors.workflow.tasks.StackInfo.StackIdQuery
import actors.workflow.tasks.{DeleteStack, StackDeleteCompleteMonitor, StackInfo}
import actors.workflow.{AWSSupervisorStrategy, WorkflowManager}
import akka.actor._
import com.amazonaws.auth.AWSCredentialsProvider
import utils.{ActorFactory, PropFactory}

class DeleteStackSupervisor(credentials: AWSCredentialsProvider,
                            actorFactory: ActorFactory) extends FSM[DeleteStackStates, DeleteStackData]
                                                                with ActorLogging with AWSSupervisorStrategy {

  import actors.workflow.steps.DeleteStackSupervisor._

  startWith(AwaitingDeleteStackCommand, Uninitialized)

  when(AwaitingDeleteStackCommand) {
    case Event(msg: DeleteExistingStack, _) =>
      val stackInfo = actorFactory(StackInfo, context, "stackInfo", credentials)
      context.watch(stackInfo)
      stackInfo ! StackIdQuery(msg.stackName)
      goto(AwaitingStackIdResponse) using StackName(msg.stackName)
  }

  when(AwaitingStackIdResponse) {
    case Event(msg: StackInfo.StackIdResponse, data: StackName) =>
      context.unwatch(sender())
      context.parent ! LogMessage(s"Deleting stack: ${data.stackName}")
      val deleteStack = actorFactory(DeleteStack, context, "stackDeleter", credentials)
      context.watch(deleteStack)
      deleteStack ! DeleteStackCommand(data.stackName)
      goto(AwaitingStackDeletedResponse) using StackIdAndName(msg.stackId, data.stackName)
  }

  when(AwaitingStackDeletedResponse) {
    case Event(StackDeleteRequested, data: StackIdAndName) =>
      context.unwatch(sender())
      context.parent ! LogMessage(s"Stack has been requested to be deleted. Monitoring delete progress")
      val monitor = actorFactory(StackDeleteCompleteMonitor, context, "stackDeleteMonitor", credentials, data.stackId, data.stackName)
      context.watch(monitor)
      goto(AwaitingStackDeleteCompleted)
  }

  when(AwaitingStackDeleteCompleted) {
    case Event(msg: StackDeleteCompleted, _) =>
      context.unwatch(sender())
      context.parent ! LogMessage(s"Stack has reached DELETE_COMPLETE status.")
      context.parent ! DeleteExistingStackFinished
      stop()
  }

  whenUnhandled {
    case Event(msg: Log, _) =>
      context.parent forward msg
      stay()

    case Event(Terminated(actorRef), _) =>
      context.parent ! LogMessage(s"Child of ${this.getClass.getSimpleName} has died unexpectedly. Child Actor: ${actorRef.path.name}")
      context.parent ! WorkflowManager.StepFailed("Failed to delete a stack")
      stop()

    case Event(msg: Any, _) =>
      log.debug(s"Unhandled message: ${msg.toString}")
      stop()
  }

  onTermination {
    case StopEvent(FSM.Failure(cause), state, data) =>
      log.error(s"FSM has failed... $cause $state $data")
  }

  initialize()
}

object DeleteStackSupervisor extends PropFactory {
  //Interaction Messages
  sealed trait DeleteStackMessage
  case class DeleteExistingStack(stackName: String) extends DeleteStackMessage
  case object DeleteExistingStackFinished extends DeleteStackMessage

  //FSM: States
  sealed trait DeleteStackStates
  case object AwaitingDeleteStackCommand extends DeleteStackStates
  case object AwaitingStackIdResponse extends DeleteStackStates
  case object AwaitingStackDeletedResponse extends DeleteStackStates
  case object AwaitingStackDeleteCompleted extends DeleteStackStates

  //FSM: Data
  sealed trait DeleteStackData
  case object Uninitialized extends DeleteStackData
  case class StackId(stackId: String) extends DeleteStackData
  case class StackName(stackName: String) extends DeleteStackData
  case class StackIdAndName(stackId: String, stackName: String) extends DeleteStackData

  override def props(args: Any*): Props = Props(classOf[DeleteStackSupervisor], args: _*)
}
