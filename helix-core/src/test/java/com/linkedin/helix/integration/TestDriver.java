/**
 * Copyright (C) 2012 LinkedIn Inc <opensource@linkedin.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.helix.integration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;
import org.testng.Assert;

import com.linkedin.helix.HelixManager;
import com.linkedin.helix.PropertyPathConfig;
import com.linkedin.helix.PropertyType;
import com.linkedin.helix.TestHelper;
import com.linkedin.helix.TestHelper.StartCMResult;
import com.linkedin.helix.ZNRecord;
import com.linkedin.helix.controller.HelixControllerMain;
import com.linkedin.helix.manager.zk.ZNRecordSerializer;
import com.linkedin.helix.manager.zk.ZkClient;
import com.linkedin.helix.model.IdealState.IdealStateModeProperty;
import com.linkedin.helix.model.IdealState.IdealStateProperty;
import com.linkedin.helix.store.PropertyJsonSerializer;
import com.linkedin.helix.store.PropertyStoreException;
import com.linkedin.helix.tools.ClusterSetup;
import com.linkedin.helix.tools.ClusterStateVerifier;
import com.linkedin.helix.tools.IdealStateCalculatorForStorageNode;
import com.linkedin.helix.tools.TestCommand;
import com.linkedin.helix.tools.TestCommand.CommandType;
import com.linkedin.helix.tools.TestCommand.NodeOpArg;
import com.linkedin.helix.tools.TestExecutor;
import com.linkedin.helix.tools.TestExecutor.ZnodePropertyType;
import com.linkedin.helix.tools.TestTrigger;
import com.linkedin.helix.tools.ZnodeOpArg;

public class TestDriver
{
  private static Logger LOG = Logger.getLogger(TestDriver.class);
  private static final String ZK_ADDR = ZkIntegrationTestBase.ZK_ADDR;

  // private static final String CLUSTER_PREFIX = "TestDriver";
  private static final String STATE_MODEL = "MasterSlave";
  private static final String TEST_DB_PREFIX = "TestDB";
  private static final int START_PORT = 12918;
  private static final String CONTROLLER_PREFIX = "controller";
  private static final String PARTICIPANT_PREFIX = "localhost";
  private static final Random RANDOM = new Random();
  private static final PropertyJsonSerializer<ZNRecord> SERIALIZER = new PropertyJsonSerializer<ZNRecord>(
      ZNRecord.class);

  private static final Map<String, TestInfo> _testInfoMap = new ConcurrentHashMap<String, TestInfo>();

  public static class TestInfo
  {
    public final ZkClient _zkClient;
    public final String _clusterName;
    public final int _numDb;
    public final int _numPartitionsPerDb;
    public final int _numNode;
    public final int _replica;

    // public final Map<String, ZNRecord> _idealStateMap = new
    // ConcurrentHashMap<String, ZNRecord>();
    public final Map<String, StartCMResult> _startCMResultMap = new ConcurrentHashMap<String, StartCMResult>();

    public TestInfo(String clusterName, ZkClient zkClient, int numDb, int numPartitionsPerDb,
        int numNode, int replica)
    {
      this._clusterName = clusterName;
      this._zkClient = zkClient;
      this._numDb = numDb;
      this._numPartitionsPerDb = numPartitionsPerDb;
      this._numNode = numNode;
      this._replica = replica;
    }
  }

  public static TestInfo getTestInfo(String uniqClusterName)
  {
    if (!_testInfoMap.containsKey(uniqClusterName))
    {
      String errMsg = "Cluster hasn't been setup for " + uniqClusterName;
      throw new IllegalArgumentException(errMsg);
    }

    TestInfo testInfo = _testInfoMap.get(uniqClusterName);
    return testInfo;
  }

  public static void setupClusterWithoutRebalance(String uniqClusterName, String zkAddr,
      int numResources, int numPartitionsPerResource, int numInstances, int replica)
      throws Exception
  {
    setupCluster(uniqClusterName, zkAddr, numResources, numPartitionsPerResource, numInstances,
        replica, false);
  }

  public static void setupCluster(String uniqClusterName, String zkAddr, int numResources,
      int numPartitionsPerResource, int numInstances, int replica) throws Exception
  {
    setupCluster(uniqClusterName, zkAddr, numResources, numPartitionsPerResource, numInstances,
        replica, true);
  }

  // public static void setupCluster(String uniqTestName, ZkClient zkClient, int
  // numDb,
  // int numPartitionPerDb, int numNodes, int replica, boolean doRebalance)
  // throws Exception
  public static void setupCluster(String uniqClusterName, String zkAddr, int numResources,
      int numPartitionsPerResource, int numInstances, int replica, boolean doRebalance)
      throws Exception
  {
    ZkClient zkClient = new ZkClient(zkAddr);
    zkClient.setZkSerializer(new ZNRecordSerializer());

    // String clusterName = CLUSTER_PREFIX + "_" + uniqClusterName;
    String clusterName = uniqClusterName;
    if (zkClient.exists("/" + clusterName))
    {
      LOG.warn("test cluster already exists:" + clusterName + ", test name:" + uniqClusterName
          + " is not unique or test has been run without cleaning up zk; deleting it");
      zkClient.deleteRecursive("/" + clusterName);
    }

    if (_testInfoMap.containsKey(uniqClusterName))
    {
      LOG.warn("test info already exists:" + uniqClusterName
          + " is not unique or test has been run without cleaning up test info map; removing it");
      _testInfoMap.remove(uniqClusterName);
    }
    TestInfo testInfo = new TestInfo(clusterName, zkClient, numResources, numPartitionsPerResource,
        numInstances, replica);
    _testInfoMap.put(uniqClusterName, testInfo);

    ClusterSetup setupTool = new ClusterSetup(zkAddr);
    setupTool.addCluster(clusterName, true);

    for (int i = 0; i < numInstances; i++)
    {
      int port = START_PORT + i;
      setupTool.addInstanceToCluster(clusterName, PARTICIPANT_PREFIX + ":" + port);
    }

    for (int i = 0; i < numResources; i++)
    {
      String dbName = TEST_DB_PREFIX + i;
      setupTool.addResourceToCluster(clusterName, dbName, numPartitionsPerResource,
          STATE_MODEL);
      if (doRebalance)
      {
        setupTool.rebalanceStorageCluster(clusterName, dbName, replica);

        // String idealStatePath = "/" + clusterName + "/" +
        // PropertyType.IDEALSTATES.toString() + "/"
        // + dbName;
        // ZNRecord idealState = zkClient.<ZNRecord> readData(idealStatePath);
        // testInfo._idealStateMap.put(dbName, idealState);
      }
    }
  }

  /**
   * starting a dummy participant with a given id
   *
   * @param uniqueTestName
   * @param instanceId
   */
  public static void startDummyParticipant(String uniqClusterName, int instanceId) throws Exception
  {
    startDummyParticipants(uniqClusterName, new int[] { instanceId });
  }

  public static void startDummyParticipants(String uniqClusterName, int[] instanceIds)
      throws Exception
  {
    if (!_testInfoMap.containsKey(uniqClusterName))
    {
      String errMsg = "test cluster hasn't been setup:" + uniqClusterName;
      throw new IllegalArgumentException(errMsg);
    }

    TestInfo testInfo = _testInfoMap.get(uniqClusterName);
    String clusterName = testInfo._clusterName;

    for (int id : instanceIds)
    {
      String instanceName = PARTICIPANT_PREFIX + "_" + (START_PORT + id);

      if (testInfo._startCMResultMap.containsKey(instanceName))
      {
        LOG.warn("Dummy participant:" + instanceName + " has already started; skip starting it");
      } else
      {
        StartCMResult result = TestHelper.startDummyProcess(ZK_ADDR, clusterName, instanceName);
        testInfo._startCMResultMap.put(instanceName, result);
        // testInfo._instanceStarted.countDown();
      }
    }
  }

  public static void startController(String uniqClusterName) throws Exception
  {
    startController(uniqClusterName, new int[] { 0 });
  }

  public static void startController(String uniqClusterName, int[] nodeIds) throws Exception
  {
    if (!_testInfoMap.containsKey(uniqClusterName))
    {
      String errMsg = "test cluster hasn't been setup:" + uniqClusterName;
      throw new IllegalArgumentException(errMsg);
    }

    TestInfo testInfo = _testInfoMap.get(uniqClusterName);
    String clusterName = testInfo._clusterName;

    for (int id : nodeIds)
    {
      String controllerName = CONTROLLER_PREFIX + "_" + id;
      if (testInfo._startCMResultMap.containsKey(controllerName))
      {
        LOG.warn("Controller:" + controllerName + " has already started; skip starting it");
      } else
      {
        StartCMResult result = TestHelper.startController(clusterName, controllerName, ZK_ADDR,
            HelixControllerMain.STANDALONE);
        testInfo._startCMResultMap.put(controllerName, result);
      }
    }
  }

  public static void verifyCluster(String uniqClusterName, long beginTime, long timeout)
      throws Exception
  {
    Thread.sleep(beginTime);

    if (!_testInfoMap.containsKey(uniqClusterName))
    {
      String errMsg = "test cluster hasn't been setup:" + uniqClusterName;
      throw new IllegalArgumentException(errMsg);
    }

    TestInfo testInfo = _testInfoMap.get(uniqClusterName);
    String clusterName = testInfo._clusterName;

    boolean result = ClusterStateVerifier.verifyByPolling(
        new ClusterStateVerifier.BestPossAndExtViewZkVerifier(ZK_ADDR, clusterName), timeout);
    Assert.assertTrue(result);
  }

  public static void stopCluster(String uniqClusterName) throws Exception
  {
    if (!_testInfoMap.containsKey(uniqClusterName))
    {
      String errMsg = "test cluster hasn't been setup:" + uniqClusterName;
      throw new IllegalArgumentException(errMsg);
    }
    TestInfo testInfo = _testInfoMap.remove(uniqClusterName);

    // stop controller first
    for (Iterator<Entry<String, StartCMResult>> it = testInfo._startCMResultMap.entrySet()
        .iterator(); it.hasNext();)
    {
      Map.Entry<String, StartCMResult> entry = it.next();
      String instanceName = entry.getKey();
      if (instanceName.startsWith(CONTROLLER_PREFIX))
      {
        it.remove();
        HelixManager manager = entry.getValue()._manager;
        manager.disconnect();
        Thread thread = entry.getValue()._thread;
        thread.interrupt();
      }
    }

    Thread.sleep(1000);

    // stop the rest
    for (Map.Entry<String, StartCMResult> entry : testInfo._startCMResultMap.entrySet())
    {
      HelixManager manager = entry.getValue()._manager;
      manager.disconnect();
      Thread thread = entry.getValue()._thread;
      thread.interrupt();
    }

    testInfo._zkClient.close();
  }

  public static void stopDummyParticipant(String uniqClusterName, long beginTime, int instanceId)
      throws Exception
  {
    if (!_testInfoMap.containsKey(uniqClusterName))
    {

      String errMsg = "test cluster hasn't been setup:" + uniqClusterName;
      throw new Exception(errMsg);
    }

    TestInfo testInfo = _testInfoMap.get(uniqClusterName);
    // String clusterName = testInfo._clusterName;

    String failHost = PARTICIPANT_PREFIX + "_" + (START_PORT + instanceId);
    StartCMResult result = testInfo._startCMResultMap.remove(failHost);

    // TODO need sync
    if (result == null || result._manager == null || result._thread == null)
    {
      String errMsg = "Dummy participant:" + failHost + " seems not running";
      LOG.error(errMsg);
    } else
    {
      // System.err.println("try to stop participant: " +
      // result._manager.getInstanceName());
      NodeOpArg arg = new NodeOpArg(result._manager, result._thread);
      TestCommand command = new TestCommand(CommandType.STOP, new TestTrigger(beginTime), arg);
      List<TestCommand> commandList = new ArrayList<TestCommand>();
      commandList.add(command);
      TestExecutor.executeTestAsync(commandList, ZK_ADDR);
    }
  }

  public static void setIdealState(String uniqClusterName, long beginTime, int percentage)
      throws Exception
  {
    if (!_testInfoMap.containsKey(uniqClusterName))
    {
      String errMsg = "test cluster hasn't been setup:" + uniqClusterName;
      throw new IllegalArgumentException(errMsg);
    }
    TestInfo testInfo = _testInfoMap.get(uniqClusterName);
    String clusterName = testInfo._clusterName;
    List<String> instanceNames = new ArrayList<String>();

    for (int i = 0; i < testInfo._numNode; i++)
    {
      int port = START_PORT + i;
      instanceNames.add(PARTICIPANT_PREFIX + "_" + port);
    }

    List<TestCommand> commandList = new ArrayList<TestCommand>();
    for (int i = 0; i < testInfo._numDb; i++)
    {
      String dbName = TEST_DB_PREFIX + i;
      ZNRecord destIS = IdealStateCalculatorForStorageNode.calculateIdealState(instanceNames,
          testInfo._numPartitionsPerDb, testInfo._replica - 1, dbName, "MASTER", "SLAVE");
      // destIS.setId(dbName);
      destIS.setSimpleField(IdealStateProperty.IDEAL_STATE_MODE.toString(),
          IdealStateModeProperty.CUSTOMIZED.toString());
      destIS.setSimpleField(IdealStateProperty.NUM_PARTITIONS.toString(),
          Integer.toString(testInfo._numPartitionsPerDb));
      destIS.setSimpleField(IdealStateProperty.STATE_MODEL_DEF_REF.toString(), STATE_MODEL);
      destIS.setSimpleField(IdealStateProperty.REPLICAS.toString(), "" + testInfo._replica);
      // String idealStatePath = "/" + clusterName + "/" +
      // PropertyType.IDEALSTATES.toString() + "/"
      // + TEST_DB_PREFIX + i;
      ZNRecord initIS = new ZNRecord(dbName); // _zkClient.<ZNRecord>
                                              // readData(idealStatePath);
      initIS.setSimpleField(IdealStateProperty.IDEAL_STATE_MODE.toString(),
          IdealStateModeProperty.CUSTOMIZED.toString());
      initIS.setSimpleField(IdealStateProperty.NUM_PARTITIONS.toString(),
          Integer.toString(testInfo._numPartitionsPerDb));
      initIS.setSimpleField(IdealStateProperty.STATE_MODEL_DEF_REF.toString(), STATE_MODEL);
      initIS.setSimpleField(IdealStateProperty.REPLICAS.toString(), "" + testInfo._replica);
      int totalStep = calcuateNumTransitions(initIS, destIS);
      // LOG.info("initIS:" + initIS);
      // LOG.info("destIS:" + destIS);
      // LOG.info("totalSteps from initIS to destIS:" + totalStep);
      // System.out.println("initIS:" + initIS);
      // System.out.println("destIS:" + destIS);

      ZNRecord nextIS;
      int step = totalStep * percentage / 100;
      System.out.println("Resource:" + dbName + ", totalSteps from initIS to destIS:" + totalStep
          + ", walk " + step + " steps(" + percentage + "%)");
      nextIS = nextIdealState(initIS, destIS, step);
      // testInfo._idealStateMap.put(dbName, nextIS);
      String idealStatePath = PropertyPathConfig.getPath(PropertyType.IDEALSTATES, clusterName,
          TEST_DB_PREFIX + i);
      ZnodeOpArg arg = new ZnodeOpArg(idealStatePath, ZnodePropertyType.ZNODE, "+", nextIS);
      TestCommand command = new TestCommand(CommandType.MODIFY, new TestTrigger(beginTime), arg);
      commandList.add(command);
    }

    TestExecutor.executeTestAsync(commandList, ZK_ADDR);

  }

  private static List<String[]> findAllUnfinishPairs(ZNRecord cur, ZNRecord dest)
  {
    // find all (host, resource) pairs that haven't reached destination state
    List<String[]> list = new ArrayList<String[]>();
    Map<String, Map<String, String>> map = dest.getMapFields();
    for (Map.Entry<String, Map<String, String>> entry : map.entrySet())
    {
      String partitionName = entry.getKey();
      Map<String, String> hostMap = entry.getValue();
      for (Map.Entry<String, String> hostEntry : hostMap.entrySet())
      {
        String host = hostEntry.getKey();
        String destState = hostEntry.getValue();
        Map<String, String> curHostMap = cur.getMapField(partitionName);

        String curState = null;
        if (curHostMap != null)
        {
          curState = curHostMap.get(host);
        }

        String[] pair = new String[3];
        if (curState == null)
        {
          if (destState.equalsIgnoreCase("SLAVE"))
          {
            pair[0] = new String(partitionName);
            pair[1] = new String(host);
            pair[2] = new String("1"); // number of transitions required
            list.add(pair);
          } else if (destState.equalsIgnoreCase("MASTER"))
          {
            pair[0] = new String(partitionName);
            pair[1] = new String(host);
            pair[2] = new String("2"); // number of transitions required
            list.add(pair);
          }
        } else
        {
          if (curState.equalsIgnoreCase("SLAVE") && destState.equalsIgnoreCase("MASTER"))
          {
            pair[0] = new String(partitionName);
            pair[1] = new String(host);
            pair[2] = new String("1"); // number of transitions required
            list.add(pair);
          }
        }
      }
    }
    return list;
  }

  private static int calcuateNumTransitions(ZNRecord start, ZNRecord end)
  {
    int totalSteps = 0;
    List<String[]> list = findAllUnfinishPairs(start, end);
    for (String[] pair : list)
    {
      totalSteps += Integer.parseInt(pair[2]);
    }
    return totalSteps;
  }

  private static ZNRecord nextIdealState(final ZNRecord cur, final ZNRecord dest, final int steps)
      throws PropertyStoreException
  {
    // get a deep copy
    ZNRecord next = SERIALIZER.deserialize(SERIALIZER.serialize(cur));
    List<String[]> list = findAllUnfinishPairs(cur, dest);

    // randomly pick up pairs that haven't reached destination state and
    // progress
    for (int i = 0; i < steps; i++)
    {
      int randomInt = RANDOM.nextInt(list.size());
      String[] pair = list.get(randomInt);
      String curState = null;
      Map<String, String> curHostMap = next.getMapField(pair[0]);
      if (curHostMap != null)
      {
        curState = curHostMap.get(pair[1]);
      }
      final String destState = dest.getMapField(pair[0]).get(pair[1]);

      // TODO generalize it using state-model
      if (curState == null && destState != null)
      {
        Map<String, String> hostMap = next.getMapField(pair[0]);
        if (hostMap == null)
        {
          hostMap = new HashMap<String, String>();
        }
        hostMap.put(pair[1], "SLAVE");
        next.setMapField(pair[0], hostMap);
      } else if (curState.equalsIgnoreCase("SLAVE") && destState != null
          && destState.equalsIgnoreCase("MASTER"))
      {
        next.getMapField(pair[0]).put(pair[1], "MASTER");
      } else
      {
        LOG.error("fail to calculate the next ideal state");
      }
      curState = next.getMapField(pair[0]).get(pair[1]);
      if (curState != null && curState.equalsIgnoreCase(destState))
      {
        list.remove(randomInt);
      }
    }

    LOG.info("nextIS:" + next);
    return next;
  }
}
