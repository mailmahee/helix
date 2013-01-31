package com.linkedin.helix;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;

import org.I0Itec.zkclient.IZkStateListener;
import org.I0Itec.zkclient.ZkConnection;
import org.apache.log4j.Logger;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;

import com.linkedin.helix.PropertyKey.Builder;
import com.linkedin.helix.manager.zk.ZKHelixDataAccessor;
import com.linkedin.helix.manager.zk.ZKHelixManager;
import com.linkedin.helix.manager.zk.ZkBaseDataAccessor;
import com.linkedin.helix.manager.zk.ZkClient;
import com.linkedin.helix.model.ExternalView;

public class ZkTestHelper
{
  private static Logger LOG = Logger.getLogger(ZkTestHelper.class);

  static
  {
    // Logger.getRootLogger().setLevel(Level.DEBUG);
  }

  // zkClusterManager that exposes zkclient
//  public static class TestZkHelixManager extends ZKHelixManager
//  {
//
//    public TestZkHelixManager(String clusterName,
//                              String instanceName,
//                              InstanceType instanceType,
//                              String zkConnectString) throws Exception
//    {
//      super(clusterName, instanceName, instanceType, zkConnectString);
//      // TODO Auto-generated constructor stub
//    }
//
//    public ZkClient getZkClient()
//    {
//      return _zkClient;
//    }
//
//  }

  public static void expireSession(final ZkClient zkClient) throws Exception
  {
    final CountDownLatch waitExpire = new CountDownLatch(1);

    IZkStateListener listener = new IZkStateListener()
    {
      @Override
      public void handleStateChanged(KeeperState state) throws Exception
      {
        // System.err.println("handleStateChanged. state: " + state);
      }

      @Override
      public void handleNewSession() throws Exception
      {
        // make sure zkclient is connected again
        zkClient.waitUntilConnected();

        ZkConnection connection = ((ZkConnection) zkClient.getConnection());
        ZooKeeper curZookeeper = connection.getZookeeper();

        LOG.info("handleNewSession. sessionId: "
            + Long.toHexString(curZookeeper.getSessionId()));
        waitExpire.countDown();
      }
    };

    zkClient.subscribeStateChanges(listener);

    ZkConnection connection = ((ZkConnection) zkClient.getConnection());
    ZooKeeper curZookeeper = connection.getZookeeper();
    LOG.info("Before expiry. sessionId: " + Long.toHexString(curZookeeper.getSessionId()));

    Watcher watcher = new Watcher()
    {
      @Override
      public void process(WatchedEvent event)
      {
        LOG.info("Process watchEvent: " + event);
      }
    };

    final ZooKeeper dupZookeeper =
        new ZooKeeper(connection.getServers(),
                      curZookeeper.getSessionTimeout(),
                      watcher,
                      curZookeeper.getSessionId(),
                      curZookeeper.getSessionPasswd());
    // wait until connected, then close
    while (dupZookeeper.getState() != States.CONNECTED)
    {
      Thread.sleep(10);
    }
    dupZookeeper.close();

    // make sure session expiry really happens
    waitExpire.await();
    zkClient.unsubscribeStateChanges(listener);

    connection = (ZkConnection) zkClient.getConnection();
    curZookeeper = connection.getZookeeper();

    // System.err.println("zk: " + oldZookeeper);
    LOG.info("After expiry. sessionId: " + Long.toHexString(curZookeeper.getSessionId()));
  }

  /*
   * stateMap: partition->instance->state
   */
  public static boolean verifyState(ZkClient zkclient,
                                    String clusterName,
                                    String resourceName,
                                    Map<String, Map<String, String>> expectStateMap,
                                    String op)
  {
    boolean result = true;
    ZkBaseDataAccessor<ZNRecord> baseAccessor =
        new ZkBaseDataAccessor<ZNRecord>(zkclient);
    ZKHelixDataAccessor accessor = new ZKHelixDataAccessor(clusterName, baseAccessor);
    Builder keyBuilder = accessor.keyBuilder();

    ExternalView extView = accessor.getProperty(keyBuilder.externalView(resourceName));
    Map<String, Map<String, String>> actualStateMap = extView.getRecord().getMapFields();
    for (String partition : actualStateMap.keySet())
    {
      for (String expectPartiton : expectStateMap.keySet())
      {
        if (!partition.matches(expectPartiton))
        {
          continue;
        }

        Map<String, String> actualInstanceStateMap = actualStateMap.get(partition);
        Map<String, String> expectInstanceStateMap = expectStateMap.get(expectPartiton);
        for (String instance : actualInstanceStateMap.keySet())
        {
          for (String expectInstance : expectStateMap.get(expectPartiton).keySet())
          {
            if (!instance.matches(expectInstance))
            {
              continue;
            }

            String actualState = actualInstanceStateMap.get(instance);
            String expectState = expectInstanceStateMap.get(expectInstance);
            boolean equals = expectState.equals(actualState);
            if (op.equals("==") && !equals || op.equals("!=") && equals)
            {
              System.out.println(partition + "/" + instance
                  + " state mismatch. actual state: " + actualState + ", but expect: "
                  + expectState + ", op: " + op);
              result = false;
            }

          }
        }

      }
    }
    return result;
  }
  
  public static int numberOfListeners(String zkAddr, String path) throws Exception
  {
    int count = 0;
    String splits[] = zkAddr.split(":");
    Socket sock = new Socket(splits[0], Integer.parseInt(splits[1]));
    PrintWriter out = new PrintWriter(sock.getOutputStream(), true);
    BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));

    out.println("wchp");

    String line = in.readLine();
    while (line != null)
    {
      // System.out.println(line);
      if (line.equals(path))
      {
        // System.out.println("match: " + line);

        String nextLine = in.readLine();
        if (nextLine == null)
        {
          break;
        }
        // System.out.println(nextLine);
        while (nextLine.startsWith("\t0x"))
        {
          count++;
          nextLine = in.readLine();
          if (nextLine == null)
          {
            break;
          }
        }
      }
      line = in.readLine();
    }
    sock.close();
    return count;
  }
  
  /**
   * return a map from zk-path to a set of zk-session-id that put watches on the zk-path
   * 
   * @param zkAddr
   * @param path
   * @return
   * @throws Exception
   */
  public static Map<String, Set<String>> getListeners(String zkAddr) throws Exception
  {
    int count = 0;
    String splits[] = zkAddr.split(":");
    Socket sock = new Socket(splits[0], Integer.parseInt(splits[1]));
    PrintWriter out = new PrintWriter(sock.getOutputStream(), true);
    BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));

    out.println("wchp");

    Map<String, Set<String>> listenerMap = new TreeMap<String, Set<String>>();
    String lastPath = null;
    String line = in.readLine();
    while (line != null)
    {
    	line = line.trim();
    	
    	if (line.startsWith("/")) {
    		lastPath = line;
    		if (!listenerMap.containsKey(lastPath)) {
    			listenerMap.put(lastPath, new TreeSet<String>());
    		}
    	} else if (line.startsWith("0x")) {
    		if (lastPath != null && listenerMap.containsKey(lastPath) ) {
    			listenerMap.get(lastPath).add(line);
    		} else
    		{
    			LOG.error("Not path associated with listener sessionId: " + line + ", lastPath: " + lastPath);
    		}
    	} else
    	{
//    		LOG.error("unrecognized line: " + line);
    	}
      line = in.readLine();
    }
    sock.close();
    return listenerMap;
  }
  
  /**
   * convert listenerMap from index by zk-path to session-id
   * 
   * @param listenerMap
   * @return
   */
  public static Map<String, Set<String>> convertListenersMap(Map<String, Set<String>> listenerMap) {
	  Map<String, Set<String>> convertMap = new TreeMap<String, Set<String>>();
	  
	  for (String path : listenerMap.keySet()) {
		  for (String sessionId : listenerMap.get(path)) {
			  if (!convertMap.containsKey(sessionId)) {
				  convertMap.put(sessionId, new TreeSet<String>());
			  }
			  convertMap.get(sessionId).add(path);
		  }
	  }
	  
	  return convertMap;
  }
  
  public static void main(String[] args) throws Exception {
	Map<String, Set<String>> map = getListeners("localhost:2185");
	System.out.println(map);
//	System.out.println(convertListenersMap(map));
  }
}
