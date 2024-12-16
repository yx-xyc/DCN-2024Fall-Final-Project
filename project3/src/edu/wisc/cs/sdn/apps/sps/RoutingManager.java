package edu.wisc.cs.sdn.apps.sps;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.instruction.OFInstruction;
import org.openflow.protocol.instruction.OFInstructionApplyActions;

import edu.wisc.cs.sdn.apps.util.Host;
import edu.wisc.cs.sdn.apps.util.SwitchCommands;

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.routing.Link;
import net.floodlightcontroller.packet.Ethernet;

public class RoutingManager {

    private static Logger log = LoggerFactory.getLogger("ROUTING_MANAGER");

    private class NodeState {
        IOFSwitch nextHop;
        int port;
        int cost;
        
        public NodeState(IOFSwitch nextHop, int port, int cost) {
            this.nextHop = nextHop;
            this.port = port;
            this.cost = cost;
        }
    }

    private Map<IOFSwitch, Map<IOFSwitch, NodeState>> shortestPaths;
    private byte flowTableId;

   public RoutingManager(byte flowTableId) {
       this.flowTableId = flowTableId;
       this.shortestPaths = new ConcurrentHashMap<IOFSwitch, Map<IOFSwitch, NodeState>>();
   }

   public void computeShortestPaths(IOFSwitch source, Collection<IOFSwitch> switches, Collection<Link> links) {
       if (source == null || switches == null || links == null) {
           log.error("Invalid parameters passed to computeShortestPaths");
           return;
       }

       Map<IOFSwitch, NodeState> distances = new HashMap<IOFSwitch, NodeState>();
       Map<IOFSwitch, Boolean> visited = new HashMap<IOFSwitch, Boolean>();
       
       for (IOFSwitch sw : switches) {
           distances.put(sw, new NodeState(null, -1, Integer.MAX_VALUE));
           visited.put(sw, Boolean.FALSE);
       }
       
       distances.get(source).cost = 0;
       
       for (int count = 0; count < switches.size(); count++) {
           IOFSwitch current = null;
           int minDistance = Integer.MAX_VALUE;
           
           for (IOFSwitch sw : switches) {
               if (!visited.get(sw) && distances.get(sw).cost < minDistance) {
                   minDistance = distances.get(sw).cost;
                   current = sw;
               }
           }
           
           if (current == null) break;
           visited.put(current, Boolean.TRUE);
           
           for (Link link : links) {
               if (link.getSrc() != current.getId()) continue;
               
               IOFSwitch neighbor = null;
               for (IOFSwitch sw : switches) {
                   if (sw.getId() == link.getDst()) {
                       neighbor = sw;
                       break;
                   }
               }
                   
               if (neighbor == null || visited.get(neighbor)) continue;
               
               int newCost = distances.get(current).cost + 1;
               if (newCost < distances.get(neighbor).cost) {
                   distances.get(neighbor).cost = newCost;
                   distances.get(neighbor).nextHop = current;
                   distances.get(neighbor).port = link.getSrcPort();
               }
           }
       }
       
       shortestPaths.put(source, distances);
   }

   public void installFlowRules(Host srcHost, Host dstHost) {
       if (srcHost == null || dstHost == null || !srcHost.isAttachedToSwitch() || !dstHost.isAttachedToSwitch()) {
           log.error("Invalid hosts passed to installFlowRules");
           return;
       }

       IOFSwitch currentSwitch = srcHost.getSwitch();
       IOFSwitch targetSwitch = dstHost.getSwitch();
       
       while (currentSwitch != null && !currentSwitch.equals(targetSwitch)) {
           Map<IOFSwitch, NodeState> paths = shortestPaths.get(currentSwitch);
           if (paths == null) break;

           NodeState nextNode = paths.get(targetSwitch);
           if (nextNode == null || nextNode.nextHop == null) break;

           OFMatch match = new OFMatch();
           match.setDataLayerType(Ethernet.TYPE_IPv4);
           match.setNetworkDestination(dstHost.getIPv4Address());

           OFAction outputAction = new OFActionOutput(nextNode.port);
           OFInstruction applyActions = new OFInstructionApplyActions(Arrays.asList(outputAction));

           SwitchCommands.installRule(currentSwitch, flowTableId, 
               SwitchCommands.DEFAULT_PRIORITY, match, Arrays.asList(applyActions));

           currentSwitch = nextNode.nextHop;
       }
   }

   public void removeFlowRules(Host host, Collection<IOFSwitch> switches) {
       if (host == null || switches == null) {
           log.error("Invalid parameters passed to removeFlowRules");
           return;
       }

       for (IOFSwitch sw : switches) {
           OFMatch match = new OFMatch();
           match.setNetworkDestination(host.getIPv4Address());
           SwitchCommands.removeRules(sw, flowTableId, match);
       }
   }

   public void handleTopologyUpdate(Collection<IOFSwitch> switches, Collection<Link> links, Collection<Host> hosts) {
       if (switches == null || links == null || hosts == null) {
           log.error("Invalid parameters passed to handleTopologyUpdate");
           return;
       }

       // Recompute paths for all switches
       for (IOFSwitch sw : switches) {
           computeShortestPaths(sw, switches, links);
       }

       // Reinstall rules for all hosts
       for (Host srcHost : hosts) {
           if (!srcHost.isAttachedToSwitch()) continue;
           
           for (Host dstHost : hosts) {
               if (dstHost.equals(srcHost) || !dstHost.isAttachedToSwitch()) continue;
               installFlowRules(srcHost, dstHost);
           }
       }
   }

}