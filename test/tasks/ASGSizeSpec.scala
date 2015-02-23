package tasks

import actors.WorkflowLog.LogMessage
import actors.workflow.tasks.ASGSize
import actors.workflow.tasks.ASGSize._
import akka.actor._
import akka.testkit.{TestKit, TestProbe}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScaling
import com.amazonaws.services.autoscaling.model.{AutoScalingGroup, DescribeAutoScalingGroupsRequest, DescribeAutoScalingGroupsResult}
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import org.mockito.Mockito
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpecLike, Matchers}
import utils.{ActorFactory, PropFactory, TestConfiguration}

import scala.concurrent.duration._

class ASGSizeSpec extends TestKit(ActorSystem("TestKit", TestConfiguration.testConfig)) with FlatSpecLike with
                          Matchers with MockitoSugar {

  val mockedClient       = mock[AmazonAutoScaling]
  val describeASGReq     = new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames("test-asg-name")
  val failReq            = new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames("expect-fail")
  val clientExceptionReq = new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames("client-exception")
  val asg                = new AutoScalingGroup().withDesiredCapacity(10)
  val describeASGResult  = new DescribeAutoScalingGroupsResult().withAutoScalingGroups(asg)
  
  Mockito.when(mockedClient.describeAutoScalingGroups(describeASGReq)).thenReturn(describeASGResult)
  Mockito.when(mockedClient.describeAutoScalingGroups(failReq)).thenThrow(new AmazonServiceException("failed"))
  Mockito.when(mockedClient.describeAutoScalingGroups(clientExceptionReq)).thenThrow(new AmazonClientException("connection problems")).thenReturn(describeASGResult)

  "An ASGSize actor" should "return an ASG size response if an ASG is queried" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, ASGSize, system, TestActorFactory)

    probe.send(proxy, ASGDesiredSizeQuery("test-asg-name"))
    probe.expectMsg(ASGDesiredSizeResult(10))
  }

  it should "set the desired size of an ASG and return a success message" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, ASGSize, system, TestActorFactory)

    probe.send(proxy, ASGSetDesiredSizeCommand("test-asg-name", 5))
    probe.expectMsg(ASGSetDesiredSizeRequested)
  }

  it should "throw an exception if AWS is down" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, ASGSize, system, TestActorFactory)

    probe.send(proxy, ASGDesiredSizeQuery("expect-fail"))
    val msg = probe.expectMsgClass(classOf[LogMessage])
    msg.message should include("AmazonServiceException")
  }

  it should "support restarts if we had a client communication exception reaching AWS and the supervisor implements AWSSupervisorStrategy" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, ASGSize, system, TestActorFactory)

    probe.send(proxy, ASGDesiredSizeQuery("client-exception"))
    probe.expectMsg(ASGDesiredSizeResult(10))
  }

  val props = Props(new ASGSize(null) {
    override def pauseTime(): FiniteDuration = 5.milliseconds

    override def autoScalingClient(credentials: AWSCredentials): AmazonAutoScaling = mockedClient
  })

  object TestActorFactory extends ActorFactory {
    def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
      ref match {
        case ASGSize => context.actorOf(props)
        case _ => ActorFactory(ref, context, name, args)
      }
    }
  }

}
