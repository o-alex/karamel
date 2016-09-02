package se.kth.karamel.backend.honeytap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import se.kth.honeytap.scaling.ScalingSuggestion;
import se.kth.honeytap.scaling.core.HoneyTapAPI;
import se.kth.honeytap.scaling.models.MachineType;
import se.kth.karamel.backend.ClusterService;
import se.kth.karamel.backend.running.model.GroupRuntime;
import se.kth.karamel.backend.running.model.MachineRuntime;
import se.kth.karamel.common.exception.KaramelException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import se.kth.karamel.common.launcher.aws.InstanceType;

/**
 * Created with IntelliJ IDEA. Each cluster will be handled by one HoneyTapHandler
 *
 * @author Ashansa Perera
 * @version $Id$
 * @since 1.0
 */
public class HoneyTapHandler {

  private ThreadPoolExecutor executor;
  Log log = LogFactory.getLog(HoneyTapHandler.class);
  private static final ClusterService clusterService = ClusterService.getInstance();
  private HoneyTapAPI honeyTapAPI;
  private Map<String, AutoScalingSuggestionExecutor> groupExecutorMap = new HashMap<>();
  private boolean isAutoScalingActive = false;

  //for simulation
  ///boolean isSimulation = true;
  long scaleOutDelay = 1000 * 60; // 1 min

  public HoneyTapHandler(int noOfGroupsInCluster, HoneyTapAPI honeyTapAPI) {
    this.honeyTapAPI = honeyTapAPI;
    this.executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(noOfGroupsInCluster);
    this.isAutoScalingActive = true;
  }

  public synchronized void startHandlingGroup(GroupRuntime groupRuntime) {
    if (isAutoScalingActive) {
      AutoScalingSuggestionExecutor suggestionExecutor = new AutoScalingSuggestionExecutor(groupRuntime);
      groupExecutorMap.put(groupRuntime.getId(), suggestionExecutor);
      executor.execute(suggestionExecutor);
    } else {
      log.error("Cannot start handling auto-scaling in group. Auto-scaling is set to " + isAutoScalingActive);
    }
  }

  public synchronized void stopHandlingGroup(String groupId) {
    AutoScalingSuggestionExecutor suggestionExecutor = groupExecutorMap.get(groupId);
    groupExecutorMap.remove(groupId);
    suggestionExecutor.stopAutoScalingSuggestionExecution();
  }

  public void stopHandlingCluster() {
    isAutoScalingActive = false;
    for (Map.Entry<String, AutoScalingSuggestionExecutor> executorEntry : groupExecutorMap.entrySet()) {
      groupExecutorMap.remove(executorEntry.getKey());
      executorEntry.getValue().stopAutoScalingSuggestionExecution();
    }
    executor.shutdown();  //already submitted tasked will be completed before shutting down
  }

  class AutoScalingSuggestionExecutor implements Runnable {

    private GroupRuntime groupRuntime;
    private boolean shouldAutoScale = false;
    private ArrayBlockingQueue<ScalingSuggestion> suggestionsQueueOfGroup = null;

    public AutoScalingSuggestionExecutor(GroupRuntime groupRuntime) {
      this.groupRuntime = groupRuntime;
      this.shouldAutoScale = true;

      while (this.suggestionsQueueOfGroup == null) {
        ArrayBlockingQueue<ScalingSuggestion> suggestionQueue = honeyTapAPI.getSuggestionQueue(groupRuntime.getId());
        if (suggestionQueue != null) {
          this.suggestionsQueueOfGroup = suggestionQueue;
          log.info(" ############### AS suggestion queue recieved, group: " + groupRuntime.getId()
              + "#################");
          break;
        }
      }
    }

    @Override
    public void run() {
      while (shouldAutoScale) {
        //wait on queue, get suggestion and execute suggestion
        try {
          ScalingSuggestion suggestion = suggestionsQueueOfGroup.take();
          log.info("########################## NON simulation got suggestion: " + groupRuntime.getName() + " "
                  + suggestion.getScalingDirection().name() + " ######################################");
          switch (suggestion.getScalingDirection()) {
            case SCALE_IN:
              ArrayList<String> machinesToRemove = suggestion.getScaleInSuggestions();
              handleScaleInSuggestion(machinesToRemove.toArray(new String[machinesToRemove.size()]));
              ///////resetVmInfoAtMonitor(groupRuntime.getId());
              break;
            case SCALE_OUT:
              ArrayList<MachineType> scaleOutMachines = suggestion.getScaleOutSuggestions();
              handleScaleOutSuggestion(scaleOutMachines.toArray(new MachineType[scaleOutMachines.size()]));
              ///////resetVmInfoAtMonitor(groupRuntime.getId());
              break;
            case TMP_SCALEIN:
              ArrayList<String> toRemove = suggestion.getScaleInSuggestions();
              handleScaleInSuggestion(toRemove.toArray(new String[toRemove.size()]));
              /////////resetVmInfoAtMonitor(groupRuntime.getId());
              break;
            default:
              log.warn("Handle scaling has not been implemented for the scaling direction: "
                      + suggestion.getScalingDirection().name());
              break;
          }
          /*}*/
        } catch (InterruptedException e) {
          log.error("Error while taking the auto-scaling suggestion in group: " + groupRuntime.getId());
        }
      }
    }

    public void stopAutoScalingSuggestionExecution() {
      this.shouldAutoScale = false;
    }

    private void handleScaleInSuggestion(String[] vmIds) {
      try {
        /*//TODO-AS temporary removing first Id -remove passing ids
         vmIds = new String[]{groupRuntime.getMachines().get(0).getVmId()};*/

        clusterService.scaleInClusterGroup(groupRuntime.getCluster().getName(), groupRuntime.getName(), vmIds);
      } catch (KaramelException e) {
        log.error("Failed to scale in the group: " + groupRuntime.getName() + " of cluster: "
            + groupRuntime.getCluster().getName());
      }
    }

    private void handleScaleOutSuggestion(MachineType[] machineTypes) {
      try {
        clusterService.scaleOutClusterGroup(groupRuntime.getCluster().getName(), groupRuntime.getName(), machineTypes);
      } catch (KaramelException e) {
        log.error("Failed to scale in the group: " + groupRuntime.getName() + " of cluster: "
            + groupRuntime.getCluster().getName());
      }
    }

    private void resetVmInfoAtMonitor(String groupId) {
      List<MachineRuntime> machineRuntimes = groupRuntime.getMachines();
      for (int i = 0; i < machineRuntimes.size(); ++i) {
        MachineRuntime machineRuntime = machineRuntimes.get(i);
        boolean isStart = false;
        if (i == 0) {
          isStart = true;
        }
        InstanceType instanceType = InstanceType.valueByModel(machineRuntime.getMachineType().split("/")[2]);
        honeyTapAPI.addVmInfo(groupId, machineRuntime.getVmId(), instanceType.numVCpu,
            instanceType.memInGig, instanceType.numDisks, instanceType.diskSize, isStart);
      }
    }

    private void addVmIdToMonitorSimulation(String groupId, String vmId, String machineType) {
      InstanceType instanceType = InstanceType.valueByModel(machineType);
      honeyTapAPI.addSimulatedVmInfo(groupId, vmId, instanceType.numVCpu, instanceType.memInGig,
          instanceType.numDisks, instanceType.diskSize);
      log.info("************** adding machine: type, id ***************************: " + machineType + ", " + vmId);
    }

    private void removeVmIdfromMonitorSimulation(String groupId, String vmId) {
      honeyTapAPI.removeSimulatedVmInfo(groupId, vmId);
      log.info("************** removing machine: id ***************************: " + vmId);
    }
  }
}