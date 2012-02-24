package com.linkedin.helix.tools;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.log4j.Logger;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;

import com.linkedin.helix.ConfigScope.ConfigScopeProperty;
import com.linkedin.helix.HelixAdmin;
import com.linkedin.helix.HelixException;
import com.linkedin.helix.PropertyType;
import com.linkedin.helix.ZNRecord;
import com.linkedin.helix.manager.zk.ZKDataAccessor;
import com.linkedin.helix.manager.zk.ZKHelixAdmin;
import com.linkedin.helix.manager.zk.ZNRecordSerializer;
import com.linkedin.helix.manager.zk.ZkClient;
import com.linkedin.helix.model.ExternalView;
import com.linkedin.helix.model.IdealState;
import com.linkedin.helix.model.IdealState.IdealStateModeProperty;
import com.linkedin.helix.model.InstanceConfig;
import com.linkedin.helix.model.StateModelDefinition;
import com.linkedin.helix.util.ZKClientPool;

public class ClusterSetup
{
  private static Logger logger = Logger.getLogger(ClusterSetup.class);
  public static final String zkServerAddress = "zkSvr";

  // List info about the cluster / DB/ Instances
  public static final String listClusters = "listClusters";
  public static final String listResources = "listResources";
  public static final String listInstances = "listInstances";

  // Add, drop, and rebalance
  public static final String addCluster = "addCluster";
  public static final String addCluster2 = "addCluster2";
  public static final String dropCluster = "dropCluster";
  public static final String addInstance = "addNode";
  public static final String addResource = "addResource";
  public static final String addStateModelDef = "addStateModelDef";
  public static final String addIdealState = "addIdealState";
  public static final String disableInstance = "disableNode";
  public static final String dropInstance = "dropNode";
  public static final String rebalance = "rebalance";

  // Query info (TBD in V2)
  public static final String listClusterInfo = "listClusterInfo";
  public static final String listInstanceInfo = "listInstanceInfo";
  public static final String listResourceInfo = "listResourceInfo";
  public static final String listPartitionInfo = "listPartitionInfo";
  public static final String listStateModels = "listStateModels";
  public static final String listStateModel = "listStateModel";

  // enable / disable Instances
  public static final String enableInstance = "enableInstance";
  public static final String help = "help";

  // stats /alerts
  public static final String addStat = "addStat";
  public static final String addAlert = "addAlert";
  public static final String dropStat = "dropStat";
  public static final String dropAlert = "dropAlert";

  static Logger _logger = Logger.getLogger(ClusterSetup.class);
  String _zkServerAddress;
  ZkClient _zkClient;
  HelixAdmin _managementService;

  public ClusterSetup(String zkServerAddress)
  {
    _zkServerAddress = zkServerAddress;
    _zkClient = ZKClientPool.getZkClient(_zkServerAddress);
    _managementService = new ZKHelixAdmin(_zkClient);
  }

  public void addCluster(String clusterName, boolean overwritePrevious)
  {
    _managementService.addCluster(clusterName, overwritePrevious);

    StateModelConfigGenerator generator = new StateModelConfigGenerator();
    addStateModelDef(clusterName, "MasterSlave",
        new StateModelDefinition(generator.generateConfigForMasterSlave()));
    addStateModelDef(clusterName, "LeaderStandby",
        new StateModelDefinition(generator.generateConfigForLeaderStandby()));
    addStateModelDef(clusterName, "StorageSchemata",
        new StateModelDefinition(generator.generateConfigForStorageSchemata()));
    addStateModelDef(clusterName, "OnlineOffline",
        new StateModelDefinition(generator.generateConfigForOnlineOffline()));
  }
  
  public void addCluster(String clusterName, boolean overwritePrevious, String grandCluster)
  {
    _managementService.addCluster(clusterName, overwritePrevious, grandCluster);

    StateModelConfigGenerator generator = new StateModelConfigGenerator();
    addStateModelDef(clusterName, "MasterSlave",
        new StateModelDefinition(generator.generateConfigForMasterSlave()));
    addStateModelDef(clusterName, "LeaderStandby",
        new StateModelDefinition(generator.generateConfigForLeaderStandby()));
    addStateModelDef(clusterName, "StorageSchemata",
        new StateModelDefinition(generator.generateConfigForStorageSchemata()));
    addStateModelDef(clusterName, "OnlineOffline",
        new StateModelDefinition(generator.generateConfigForOnlineOffline()));
  }

  public void deleteCluster(String clusterName)
  {
    _managementService.dropCluster(clusterName);
  }

  public void addInstancesToCluster(String clusterName, String[] InstanceInfoArray)
  {
    for (String InstanceInfo : InstanceInfoArray)
    {
      // the storage Instance info must be hostname:port format.
      if (InstanceInfo.length() > 0)
      {
        addInstanceToCluster(clusterName, InstanceInfo);
      }
    }
  }

  public void addInstanceToCluster(String clusterName, String InstanceAddress)
  {
    // InstanceAddress must be in host:port format
    int lastPos = InstanceAddress.lastIndexOf("_");
    if (lastPos <= 0)
    {
      lastPos = InstanceAddress.lastIndexOf(":");
    }
    if (lastPos <= 0)
    {
      String error = "Invalid storage Instance info format: " + InstanceAddress;
      _logger.warn(error);
      throw new HelixException(error);
    }
    String host = InstanceAddress.substring(0, lastPos);
    String portStr = InstanceAddress.substring(lastPos + 1);
    int port = Integer.parseInt(portStr);
    addInstanceToCluster(clusterName, host, port);
  }

  public void addInstanceToCluster(String clusterName, String host, int port)
  {
    String instanceId = host + "_" + port;
    InstanceConfig config = new InstanceConfig(instanceId);
    config.setHostName(host);
    config.setPort(Integer.toString(port));
    config.setInstanceEnabled(true);
    _managementService.addInstance(clusterName, config);
  }

  public void dropInstancesFromCluster(String clusterName, String[] InstanceInfoArray)
  {
    for (String InstanceInfo : InstanceInfoArray)
    {
      // the storage Instance info must be hostname:port format.
      if (InstanceInfo.length() > 0)
      {
        dropInstanceFromCluster(clusterName, InstanceInfo);
      }
    }
  }

  public void dropInstanceFromCluster(String clusterName, String InstanceAddress)
  {
    // InstanceAddress must be in host:port format
    int lastPos = InstanceAddress.lastIndexOf("_");
    if (lastPos <= 0)
    {
      lastPos = InstanceAddress.lastIndexOf(":");
    }
    if (lastPos <= 0)
    {
      String error = "Invalid storage Instance info format: " + InstanceAddress;
      _logger.warn(error);
      throw new HelixException(error);
    }
    String host = InstanceAddress.substring(0, lastPos);
    String portStr = InstanceAddress.substring(lastPos + 1);
    int port = Integer.parseInt(portStr);
    dropInstanceFromCluster(clusterName, host, port);
  }

  public void dropInstanceFromCluster(String clusterName, String host, int port)
  {
    String instanceId = host + "_" + port;

    ZkClient zkClient = ZKClientPool.getZkClient(_zkServerAddress);
    InstanceConfig config = new ZKDataAccessor(clusterName, zkClient).getProperty(
        InstanceConfig.class, PropertyType.CONFIGS, ConfigScopeProperty.PARTICIPANT.toString(),
        instanceId);
    if (config == null)
    {
      String error = "Node " + instanceId + " does not exist, cannot drop";
      _logger.warn(error);
      throw new HelixException(error);
    }

    // ensure node is disabled, otherwise fail
    if (config.getInstanceEnabled())
    {
      String error = "Node " + instanceId + " is enabled, cannot drop";
      _logger.warn(error);
      throw new HelixException(error);
    }
    _managementService.dropInstance(clusterName, config);
  }

  public HelixAdmin getClusterManagementTool()
  {
    return _managementService;
  }

  public void addStateModelDef(String clusterName, String stateModelDef, StateModelDefinition record)
  {
    _managementService.addStateModelDef(clusterName, stateModelDef, record);
  }

  public void addResourceToCluster(String clusterName, String resourceName, int numResources,
      String stateModelRef)
  {
    addResourceToCluster(clusterName, resourceName, numResources, stateModelRef,
        IdealStateModeProperty.AUTO.toString());
  }

  public void addResourceToCluster(String clusterName, String resourceName, int numResources,
      String stateModelRef, String idealStateMode)
  {
    if (!idealStateMode.equalsIgnoreCase(IdealStateModeProperty.CUSTOMIZED.toString()))
    {
      logger.info("ideal state mode is configured to auto for " + resourceName);
      idealStateMode = IdealStateModeProperty.AUTO.toString();
    }
    _managementService.addResource(clusterName, resourceName, numResources, stateModelRef,
        idealStateMode);
  }

  public void dropResourceFromCluster(String clusterName, String resourceName)
  {
    _managementService.dropResource(clusterName, resourceName);
  }

  public void rebalanceStorageCluster(String clusterName, String resourceName, int replica)
  {
    List<String> InstanceNames = _managementService.getInstancesInCluster(clusterName);

    IdealState idealState = _managementService.getResourceIdealState(clusterName, resourceName);
    idealState.setReplicas(Integer.toString(replica));
    int partitions = idealState.getNumPartitions();
    String stateModelName = idealState.getStateModelDefRef();
    StateModelDefinition stateModDef = _managementService.getStateModelDef(clusterName,
        stateModelName);

    if (stateModDef == null)
    {
      throw new HelixException("cannot find state model: " + stateModelName);
    }
    // StateModelDefinition def = new StateModelDefinition(stateModDef);

    List<String> statePriorityList = stateModDef.getStatesPriorityList();

    String masterStateValue = null;
    String slaveStateValue = null;
    replica--;

    for (String state : statePriorityList)
    {
      String count = stateModDef.getNumInstancesPerState(state);
      if (count.equals("1"))
      {
        if (masterStateValue != null)
        {
          throw new HelixException("Invalid or unsupported state model definition");
        }
        masterStateValue = state;
      } else if (count.equalsIgnoreCase("R"))
      {
        if (slaveStateValue != null)
        {
          throw new HelixException("Invalid or unsupported state model definition");
        }
        slaveStateValue = state;
      } else if (count.equalsIgnoreCase("N"))
      {
        if (!(masterStateValue == null && slaveStateValue == null))
        {
          throw new HelixException("Invalid or unsupported state model definition");
        }
        replica = InstanceNames.size() - 1;
        masterStateValue = slaveStateValue = state;
      }
    }
    if (masterStateValue == null && slaveStateValue == null)
    {
      throw new HelixException("Invalid or unsupported state model definition");
    }

    if (masterStateValue == null)
    {
      masterStateValue = slaveStateValue;
    }

    ZNRecord newIdealState = IdealStateCalculatorForStorageNode.calculateIdealState(InstanceNames,
        partitions, replica, resourceName, masterStateValue, slaveStateValue);
    idealState.getRecord().setMapFields(newIdealState.getMapFields());
    idealState.getRecord().setListFields(newIdealState.getListFields());
    _managementService.setResourceIdealState(clusterName, resourceName, idealState);
  }

  /**
   * Sets up a cluster with 6 Instances[localhost:8900 to localhost:8905], 1
   * resource[EspressoDB] with a replication factor of 3
   * 
   * @param clusterName
   */
  public void setupTestCluster(String clusterName)
  {
    addCluster(clusterName, true);
    String storageInstanceInfoArray[] = new String[6];
    for (int i = 0; i < storageInstanceInfoArray.length; i++)
    {
      storageInstanceInfoArray[i] = "localhost:" + (8900 + i);
    }
    addInstancesToCluster(clusterName, storageInstanceInfoArray);
    addResourceToCluster(clusterName, "TestDB", 10, "MasterSlave");
    rebalanceStorageCluster(clusterName, "TestDB", 3);
  }

  public static void printUsage(Options cliOptions)
  {
    HelpFormatter helpFormatter = new HelpFormatter();
    helpFormatter.setWidth(1000);
    helpFormatter.printHelp("java " + ClusterSetup.class.getName(), cliOptions);
  }

  @SuppressWarnings("static-access")
  private static Options constructCommandLineOptions()
  {
    Option helpOption = OptionBuilder.withLongOpt(help)
        .withDescription("Prints command-line options info").create();

    Option zkServerOption = OptionBuilder.withLongOpt(zkServerAddress)
        .withDescription("Provide zookeeper address").create();
    zkServerOption.setArgs(1);
    zkServerOption.setRequired(true);
    zkServerOption.setArgName("ZookeeperServerAddress(Required)");

    Option listClustersOption = OptionBuilder.withLongOpt(listClusters)
        .withDescription("List existing clusters").create();
    listClustersOption.setArgs(0);
    listClustersOption.setRequired(false);

    Option listResourceOption = OptionBuilder.withLongOpt(listResources)
        .withDescription("List resources hosted in a cluster").create();
    listResourceOption.setArgs(1);
    listResourceOption.setRequired(false);
    listResourceOption.setArgName("clusterName");

    Option listInstancesOption = OptionBuilder.withLongOpt(listInstances)
        .withDescription("List Instances in a cluster").create();
    listInstancesOption.setArgs(1);
    listInstancesOption.setRequired(false);
    listInstancesOption.setArgName("clusterName");

    Option addClusterOption = OptionBuilder.withLongOpt(addCluster)
        .withDescription("Add a new cluster").create();
    addClusterOption.setArgs(1);
    addClusterOption.setRequired(false);
    addClusterOption.setArgName("clusterName");
    
    Option addClusterOption2 =
        OptionBuilder.withLongOpt(addCluster2).withDescription("Add a new cluster").create();
    addClusterOption2.setArgs(2);
    addClusterOption2.setRequired(false);
    addClusterOption2.setArgName("clusterName grandCluster");

    Option deleteClusterOption = OptionBuilder.withLongOpt(dropCluster)
        .withDescription("Delete a cluster").create();
    deleteClusterOption.setArgs(1);
    deleteClusterOption.setRequired(false);
    deleteClusterOption.setArgName("clusterName");

    Option addInstanceOption = OptionBuilder.withLongOpt(addInstance)
        .withDescription("Add a new Instance to a cluster").create();
    addInstanceOption.setArgs(2);
    addInstanceOption.setRequired(false);
    addInstanceOption.setArgName("clusterName InstanceAddress(host:port)");

    Option addResourceOption = OptionBuilder.withLongOpt(addResource)
        .withDescription("Add a resource to a cluster").create();
    addResourceOption.setArgs(4);
    addResourceOption.setRequired(false);
    addResourceOption.setArgName("clusterName resourceName partitionNum stateModelRef");

    Option addStateModelDefOption = OptionBuilder.withLongOpt(addStateModelDef)
        .withDescription("Add a State model to a cluster").create();
    addStateModelDefOption.setArgs(2);
    addStateModelDefOption.setRequired(false);
    addStateModelDefOption.setArgName("clusterName <filename>");

    Option addIdealStateOption = OptionBuilder.withLongOpt(addIdealState)
        .withDescription("Add a State model to a cluster").create();
    addIdealStateOption.setArgs(3);
    addIdealStateOption.setRequired(false);
    addIdealStateOption.setArgName("clusterName reourceName <filename>");

    Option dropInstanceOption = OptionBuilder.withLongOpt(dropInstance)
        .withDescription("Drop an existing Instance from a cluster").create();
    dropInstanceOption.setArgs(2);
    dropInstanceOption.setRequired(false);
    dropInstanceOption.setArgName("clusterName InstanceAddress(host:port)");

    Option rebalanceOption = OptionBuilder.withLongOpt(rebalance)
        .withDescription("Rebalance a resource in a cluster").create();
    rebalanceOption.setArgs(3);
    rebalanceOption.setRequired(false);
    rebalanceOption.setArgName("clusterName resourceName replicas");

    Option InstanceInfoOption = OptionBuilder.withLongOpt(listInstanceInfo)
        .withDescription("Query info of a Instance in a cluster").create();
    InstanceInfoOption.setArgs(2);
    InstanceInfoOption.setRequired(false);
    InstanceInfoOption.setArgName("clusterName InstanceName");

    Option clusterInfoOption = OptionBuilder.withLongOpt(listClusterInfo)
        .withDescription("Query info of a cluster").create();
    clusterInfoOption.setArgs(1);
    clusterInfoOption.setRequired(false);
    clusterInfoOption.setArgName("clusterName");

    Option resourceInfoOption = OptionBuilder.withLongOpt(listResourceInfo)
        .withDescription("Query info of a resource").create();
    resourceInfoOption.setArgs(2);
    resourceInfoOption.setRequired(false);
    resourceInfoOption.setArgName("clusterName resourceName");

    Option partitionInfoOption = OptionBuilder.withLongOpt(listPartitionInfo)
        .withDescription("Query info of a partition").create();
    partitionInfoOption.setArgs(2);
    partitionInfoOption.setRequired(false);
    partitionInfoOption.setArgName("clusterName partitionName");

    Option enableInstanceOption = OptionBuilder.withLongOpt(enableInstance)
        .withDescription("Enable / disable a Instance").create();
    enableInstanceOption.setArgs(3);
    enableInstanceOption.setRequired(false);
    enableInstanceOption.setArgName("clusterName InstanceName true/false");

    Option listStateModelsOption = OptionBuilder.withLongOpt(listStateModels)
        .withDescription("Query info of state models in a cluster").create();
    listStateModelsOption.setArgs(1);
    listStateModelsOption.setRequired(false);
    listStateModelsOption.setArgName("clusterName");

    Option listStateModelOption = OptionBuilder.withLongOpt(listStateModel)
        .withDescription("Query info of a state model in a cluster").create();
    listStateModelOption.setArgs(2);
    listStateModelOption.setRequired(false);
    listStateModelOption.setArgName("clusterName stateModelName");

    Option addStatOption = OptionBuilder.withLongOpt(addStat)
        .withDescription("Add a persistent stat").create();
    addStatOption.setArgs(2);
    addStatOption.setRequired(false);
    addStatOption.setArgName("clusterName statName");
    Option addAlertOption = OptionBuilder.withLongOpt(addAlert).withDescription("Add an alert")
        .create();
    addAlertOption.setArgs(2);
    addAlertOption.setRequired(false);
    addAlertOption.setArgName("clusterName alertName");

    Option dropStatOption = OptionBuilder.withLongOpt(dropStat)
        .withDescription("Drop a persistent stat").create();
    dropStatOption.setArgs(2);
    dropStatOption.setRequired(false);
    dropStatOption.setArgName("clusterName statName");
    Option dropAlertOption = OptionBuilder.withLongOpt(dropAlert).withDescription("Drop an alert")
        .create();
    dropAlertOption.setArgs(2);
    dropAlertOption.setRequired(false);
    dropAlertOption.setArgName("clusterName alertName");

    OptionGroup group = new OptionGroup();
    group.setRequired(true);
    group.addOption(rebalanceOption);
    group.addOption(addResourceOption);
    group.addOption(addClusterOption);
    group.addOption(addClusterOption2);
    group.addOption(deleteClusterOption);
    group.addOption(addInstanceOption);
    group.addOption(listInstancesOption);
    group.addOption(listResourceOption);
    group.addOption(listClustersOption);
    group.addOption(addIdealStateOption);
    group.addOption(rebalanceOption);
    group.addOption(dropInstanceOption);
    group.addOption(InstanceInfoOption);
    group.addOption(clusterInfoOption);
    group.addOption(resourceInfoOption);
    group.addOption(partitionInfoOption);
    group.addOption(enableInstanceOption);
    group.addOption(addStateModelDefOption);
    group.addOption(listStateModelsOption);
    group.addOption(listStateModelOption);
    group.addOption(addStatOption);
    group.addOption(addAlertOption);
    group.addOption(dropStatOption);
    group.addOption(dropAlertOption);

    Options options = new Options();
    options.addOption(helpOption);
    options.addOption(zkServerOption);
    options.addOptionGroup(group);
    return options;
  }

  private static byte[] readFile(String filePath) throws IOException
  {
    File file = new File(filePath);

    int size = (int) file.length();
    byte[] bytes = new byte[size];
    DataInputStream dis = new DataInputStream(new FileInputStream(file));
    int read = 0;
    int numRead = 0;
    while (read < bytes.length && (numRead = dis.read(bytes, read, bytes.length - read)) >= 0)
    {
      read = read + numRead;
    }
    return bytes;
  }

  private static boolean checkOptionArgsNumber(Option[] options)
  {
    for (Option option : options)
    {
      int argNb = option.getArgs();
      String[] args = option.getValues();
      if (argNb == 0)
      {
        if (args != null && args.length > 0)
        {
          System.err.println(option.getArgName() + " shall have " + argNb + " arguments (was "
              + Arrays.toString(args) + ")");
          return false;
        }
      } else
      {
        if (args == null || args.length != argNb)
        {
          System.err.println(option.getArgName() + " shall have " + argNb + " arguments (was "
              + Arrays.toString(args) + ")");
          return false;
        }
      }
    }
    return true;
  }

  public static int processCommandLineArgs(String[] cliArgs) throws Exception
  {
    CommandLineParser cliParser = new GnuParser();
    Options cliOptions = constructCommandLineOptions();
    CommandLine cmd = null;

    try
    {
      cmd = cliParser.parse(cliOptions, cliArgs);
    } catch (ParseException pe)
    {
      System.err.println("CommandLineClient: failed to parse command-line options: "
          + pe.toString());
      printUsage(cliOptions);
      System.exit(1);
    }
    boolean ret = checkOptionArgsNumber(cmd.getOptions());
    if (ret == false)
    {
      printUsage(cliOptions);
      System.exit(1);
    }

    ClusterSetup setupTool = new ClusterSetup(cmd.getOptionValue(zkServerAddress));

    if (cmd.hasOption(addCluster))
    {
      String clusterName = cmd.getOptionValue(addCluster);
      setupTool.addCluster(clusterName, false);
      return 0;
    }
    
    if (cmd.hasOption(addCluster2))
    {
      String clusterName = cmd.getOptionValues(addCluster2)[0];
      String grandCluster = cmd.getOptionValues(addCluster2)[1];
      setupTool.addCluster(clusterName, false, grandCluster);
      return 0;
    }

    if (cmd.hasOption(dropCluster))
    {
      String clusterName = cmd.getOptionValue(dropCluster);
      setupTool.deleteCluster(clusterName);
      return 0;
    }

    if (cmd.hasOption(addInstance))
    {
      String clusterName = cmd.getOptionValues(addInstance)[0];
      String InstanceAddressInfo = cmd.getOptionValues(addInstance)[1];
      String[] InstanceAddresses = InstanceAddressInfo.split(";");
      setupTool.addInstancesToCluster(clusterName, InstanceAddresses);
      return 0;
    }

    if (cmd.hasOption(addResource))
    {
      String clusterName = cmd.getOptionValues(addResource)[0];
      String resourceName = cmd.getOptionValues(addResource)[1];
      int partitions = Integer.parseInt(cmd.getOptionValues(addResource)[2]);
      String stateModelRef = cmd.getOptionValues(addResource)[3];
      setupTool.addResourceToCluster(clusterName, resourceName, partitions, stateModelRef);
      return 0;
    }

    if (cmd.hasOption(rebalance))
    {
      String clusterName = cmd.getOptionValues(rebalance)[0];
      String resourceName = cmd.getOptionValues(rebalance)[1];
      int replicas = Integer.parseInt(cmd.getOptionValues(rebalance)[2]);
      setupTool.rebalanceStorageCluster(clusterName, resourceName, replicas);
      return 0;
    }

    if (cmd.hasOption(dropInstance))
    {
      String clusterName = cmd.getOptionValues(dropInstance)[0];
      String InstanceAddressInfo = cmd.getOptionValues(dropInstance)[1];
      String[] InstanceAddresses = InstanceAddressInfo.split(";");
      setupTool.dropInstancesFromCluster(clusterName, InstanceAddresses);
      return 0;
    }

    if (cmd.hasOption(listClusters))
    {
      List<String> clusters = setupTool.getClusterManagementTool().getClusters();

      System.out.println("Existing clusters:");
      for (String cluster : clusters)
      {
        System.out.println(cluster);
      }
      return 0;
    }

    if (cmd.hasOption(listResources))
    {
      String clusterName = cmd.getOptionValue(listResources);
      List<String> resourceNames = setupTool.getClusterManagementTool().getResourcesInCluster(
          clusterName);

      System.out.println("Existing resources in cluster " + clusterName + ":");
      for (String resourceName : resourceNames)
      {
        System.out.println(resourceName);
      }
      return 0;
    } else if (cmd.hasOption(listClusterInfo))
    {
      String clusterName = cmd.getOptionValue(listClusterInfo);
      List<String> resourceNames = setupTool.getClusterManagementTool().getResourcesInCluster(
          clusterName);
      List<String> Instances = setupTool.getClusterManagementTool().getInstancesInCluster(
          clusterName);

      System.out.println("Existing resources in cluster " + clusterName + ":");
      for (String resourceName : resourceNames)
      {
        System.out.println(resourceName);
      }

      System.out.println("Instances in cluster " + clusterName + ":");
      for (String InstanceName : Instances)
      {
        System.out.println(InstanceName);
      }
      return 0;
    } else if (cmd.hasOption(listInstances))
    {
      String clusterName = cmd.getOptionValue(listInstances);
      List<String> Instances = setupTool.getClusterManagementTool().getInstancesInCluster(
          clusterName);

      System.out.println("Instances in cluster " + clusterName + ":");
      for (String InstanceName : Instances)
      {
        System.out.println(InstanceName);
      }
      return 0;
    } else if (cmd.hasOption(listInstanceInfo))
    {
      String clusterName = cmd.getOptionValues(listInstanceInfo)[0];
      String instanceName = cmd.getOptionValues(listInstanceInfo)[1];
      InstanceConfig config = setupTool.getClusterManagementTool().getInstanceConfig(clusterName,
          instanceName);

      String result = new String(new ZNRecordSerializer().serialize(config.getRecord()));
      System.out.println("InstanceConfig: " + result);
      return 0;
    } else if (cmd.hasOption(listResourceInfo))
    {
      // print out partition number, db name and replication number
      // Also the ideal states and current states
      String clusterName = cmd.getOptionValues(listResourceInfo)[0];
      String resourceName = cmd.getOptionValues(listResourceInfo)[1];
      IdealState idealState = setupTool.getClusterManagementTool().getResourceIdealState(
          clusterName, resourceName);
      ExternalView externalView = setupTool.getClusterManagementTool().getResourceExternalView(
          clusterName, resourceName);

      System.out.println("IdealState for " + resourceName + ":");
      System.out.println(new String(new ZNRecordSerializer().serialize(idealState.getRecord())));

      System.out.println();
      System.out.println("External view for " + resourceName + ":");
      System.out.println(new String(new ZNRecordSerializer().serialize(externalView.getRecord())));
      return 0;

    } else if (cmd.hasOption(listPartitionInfo))
    {
      // print out where the partition master / slaves locates
    } else if (cmd.hasOption(enableInstance))
    {
      String clusterName = cmd.getOptionValues(enableInstance)[0];
      String instanceName = cmd.getOptionValues(enableInstance)[1];
      boolean enabled = Boolean.parseBoolean(cmd.getOptionValues(enableInstance)[2].toLowerCase());

      setupTool.getClusterManagementTool().enableInstance(clusterName, instanceName, enabled);
      return 0;
    } else if (cmd.hasOption(listStateModels))
    {
      String clusterName = cmd.getOptionValues(listStateModels)[0];

      List<String> stateModels = setupTool.getClusterManagementTool()
          .getStateModelDefs(clusterName);

      System.out.println("Existing state models:");
      for (String stateModel : stateModels)
      {
        System.out.println(stateModel);
      }
      return 0;
    } else if (cmd.hasOption(listStateModel))
    {
      String clusterName = cmd.getOptionValues(listStateModel)[0];
      String stateModel = cmd.getOptionValues(listStateModel)[1];
      StateModelDefinition stateModelDef = setupTool.getClusterManagementTool().getStateModelDef(
          clusterName, stateModel);
      String result = new String(new ZNRecordSerializer().serialize(stateModelDef.getRecord()));
      System.out.println("StateModelDefinition: " + result);
      return 0;
    } else if (cmd.hasOption(addStateModelDef))
    {
      String clusterName = cmd.getOptionValues(addStateModelDef)[0];
      String stateModelFile = cmd.getOptionValues(addStateModelDef)[1];

      ZNRecord stateModelRecord = (ZNRecord) (new ZNRecordSerializer()
          .deserialize(readFile(stateModelFile)));
      if (stateModelRecord.getId() == null || stateModelRecord.getId().length() == 0)
      {
        throw new IllegalArgumentException("ZNRecord for state model definition must have an id");
      }
      setupTool.getClusterManagementTool().addStateModelDef(clusterName, stateModelRecord.getId(),
          new StateModelDefinition(stateModelRecord));
      return 0;
    } else if (cmd.hasOption(addIdealState))
    {
      String clusterName = cmd.getOptionValues(addIdealState)[0];
      String resourceName = cmd.getOptionValues(addIdealState)[1];
      String idealStateFile = cmd.getOptionValues(addIdealState)[2];

      ZNRecord idealStateRecord = (ZNRecord) (new ZNRecordSerializer()
          .deserialize(readFile(idealStateFile)));
      if (idealStateRecord.getId() == null || !idealStateRecord.getId().equals(resourceName))
      {
        throw new IllegalArgumentException("ideal state must have same id as resource name");
      }
      setupTool.getClusterManagementTool().setResourceIdealState(clusterName, resourceName,
          new IdealState(idealStateRecord));
      return 0;
    } else if (cmd.hasOption(addStat))
    {
      String clusterName = cmd.getOptionValues(addStat)[0];
      String statName = cmd.getOptionValues(addStat)[1];

      setupTool.getClusterManagementTool().addStat(clusterName, statName);
    } else if (cmd.hasOption(addAlert))
    {
      String clusterName = cmd.getOptionValues(addAlert)[0];
      String alertName = cmd.getOptionValues(addAlert)[1];

      setupTool.getClusterManagementTool().addAlert(clusterName, alertName);
    } else if (cmd.hasOption(dropStat))
    {
      String clusterName = cmd.getOptionValues(dropStat)[0];
      String statName = cmd.getOptionValues(dropStat)[1];

      setupTool.getClusterManagementTool().dropStat(clusterName, statName);
    } else if (cmd.hasOption(dropAlert))
    {
      String clusterName = cmd.getOptionValues(dropAlert)[0];
      String alertName = cmd.getOptionValues(dropAlert)[1];

      setupTool.getClusterManagementTool().dropAlert(clusterName, alertName);
    } else if (cmd.hasOption(help))
    {
      printUsage(cliOptions);
      return 0;
    }
    return 0;
  }

  /**
   * @param args
   * @throws Exception
   * @throws JsonMappingException
   * @throws JsonGenerationException
   */
  public static void main(String[] args) throws Exception
  {
    if (args.length == 1 && args[0].equals("setup-test-cluster"))
    {
      System.out.println("By default setting up ");
      new ClusterSetup("localhost:2181").setupTestCluster("storage-integration-cluster");
      new ClusterSetup("localhost:2181").setupTestCluster("relay-integration-cluster");
      System.exit(0);
    }

    int ret = processCommandLineArgs(args);
    System.exit(ret);
  }
}