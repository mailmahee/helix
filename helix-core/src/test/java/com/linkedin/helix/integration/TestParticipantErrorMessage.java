package com.linkedin.helix.integration;

import java.util.UUID;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.linkedin.helix.Criteria;
import com.linkedin.helix.InstanceType;
import com.linkedin.helix.PropertyKey.Builder;
import com.linkedin.helix.manager.zk.DefaultParticipantErrorMessageHandlerFactory;
import com.linkedin.helix.manager.zk.DefaultParticipantErrorMessageHandlerFactory.ActionOnError;
import com.linkedin.helix.model.ExternalView;
import com.linkedin.helix.model.Message;
import com.linkedin.helix.model.Message.MessageType;
import com.linkedin.helix.tools.ClusterStateVerifier;
import com.linkedin.helix.tools.ClusterStateVerifier.BestPossAndExtViewZkVerifier;

public class TestParticipantErrorMessage extends ZkStandAloneCMTestBase
{
  @Test()
  public void TestParticipantErrorMessageSend()
  {
    String participant1 = "localhost_" + START_PORT;
    String participant2 = "localhost_" + (START_PORT + 1);
    
    Message errorMessage1
      = new Message(MessageType.PARTICIPANT_ERROR_REPORT, UUID.randomUUID().toString());
    errorMessage1.setTgtSessionId("*");
    errorMessage1.getRecord().setSimpleField(DefaultParticipantErrorMessageHandlerFactory.ACTIONKEY, ActionOnError.DISABLE_INSTANCE.toString());
    Criteria recipientCriteria = new Criteria();
    recipientCriteria.setRecipientInstanceType(InstanceType.CONTROLLER);
    recipientCriteria.setSessionSpecific(false);
    _startCMResultMap.get(participant1)._manager.getMessagingService().send(recipientCriteria, errorMessage1);
    
    Message errorMessage2
    = new Message(MessageType.PARTICIPANT_ERROR_REPORT, UUID.randomUUID().toString());
    errorMessage2.setTgtSessionId("*");
    errorMessage2.setResourceName("TestDB");
    errorMessage2.setPartitionName("TestDB_14");
    errorMessage2.getRecord().setSimpleField(DefaultParticipantErrorMessageHandlerFactory.ACTIONKEY, ActionOnError.DISABLE_PARTITION.toString());
    Criteria recipientCriteria2 = new Criteria();
    recipientCriteria2.setRecipientInstanceType(InstanceType.CONTROLLER);
    recipientCriteria2.setSessionSpecific(false);
    _startCMResultMap.get(participant2)._manager.getMessagingService().send(recipientCriteria2, errorMessage2);
  
    try
    {
      Thread.sleep(1500);
    }
    catch (InterruptedException e)
    {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    boolean result =
        ClusterStateVerifier.verifyByZkCallback(new BestPossAndExtViewZkVerifier(ZK_ADDR,
                                                                                 CLUSTER_NAME));
    Assert.assertTrue(result);
    Builder kb = _startCMResultMap.get(participant2)._manager.getHelixDataAccessor().keyBuilder();
    ExternalView externalView = _startCMResultMap.get(participant2)._manager.getHelixDataAccessor().getProperty(kb.externalView("TestDB"));
    
    for(String partitionName : externalView.getRecord().getMapFields().keySet())
    {
      for(String hostName :  externalView.getRecord().getMapField(partitionName).keySet())
      {
        if(hostName.equals(participant1))
        {
          Assert.assertTrue(externalView.getRecord().getMapField(partitionName).get(hostName).equalsIgnoreCase("OFFLINE"));
        }
      }
    }
    Assert.assertTrue(externalView.getRecord().getMapField("TestDB_14").get(participant2).equalsIgnoreCase("OFFLINE"));
  }
}
