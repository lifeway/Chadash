package actors.workflow.tasks

import actors.workflow.RestartableActor
import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.ResumeProcessesRequest

class UnfreezeASG(credentials: AWSCredentials) extends Actor with RestartableActor with ActorLogging {

  import actors.workflow.tasks.UnfreezeASG._

  override def receive: Receive = {
    case msg: UnfreezeASGCommand =>

      val resumeProcessesRequest = new ResumeProcessesRequest()
        .withAutoScalingGroupName(msg.asgName)

      val awsClient = new AmazonAutoScalingClient(credentials)
      awsClient.resumeProcesses(resumeProcessesRequest)
      context.sender() ! UnfreezeASGCompleted(msg.asgName)
  }
}

object UnfreezeASG {

  case class UnfreezeASGCommand(asgName: String)

  case class UnfreezeASGCompleted(asgName: String)

  def props(credentials: AWSCredentials): Props = Props(new UnfreezeASG(credentials))
}