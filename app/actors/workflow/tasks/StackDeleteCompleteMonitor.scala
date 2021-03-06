package actors.workflow.tasks

import actors.WorkflowLog.LogMessage
import actors.workflow.AWSRestartableActor
import akka.actor.Props
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.cloudformation.model.DescribeStacksRequest
import utils.{AmazonCloudFormationService, PropFactory}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class StackDeleteCompleteMonitor(credentials: AWSCredentialsProvider, stackId: String,
                                 stackName: String) extends AWSRestartableActor with AmazonCloudFormationService {

  import actors.workflow.tasks.StackDeleteCompleteMonitor._

  override def preStart() = scheduleTick()

  // override postRestart so we don't call preStart and schedule a new message
  override def postRestart(reason: Throwable) = {}

  override def receive: Receive = {
    case Tick =>
      val stackFilter = new DescribeStacksRequest()
                        .withStackName(stackId)

      val awsClient = cloudFormationClient(credentials)
      val stackInfo = awsClient.describeStacks(stackFilter).getStacks.asScala.toSeq(0)
      stackInfo.getStackStatus match {
        case "DELETE_COMPLETE" => context.parent ! StackDeleteCompleted(stackName)
        case "DELETE_FAILED" => throw new Exception("Failed to delete the old stack!")
        case "DELETE_IN_PROGRESS" =>
          context.parent ! LogMessage(s"$stackName has not yet reached DELETE_COMPLETE status")
          scheduleTick()
        case _ => throw new Exception("unhandled stack status type")
      }
  }

  def scheduleTick() = context.system.scheduler.scheduleOnce(5.seconds, self, Tick)
}

object StackDeleteCompleteMonitor extends PropFactory {
  case object Tick
  case class StackDeleteCompleted(stackName: String)

  override def props(args: Any*): Props = Props(classOf[StackDeleteCompleteMonitor], args: _*)
}
