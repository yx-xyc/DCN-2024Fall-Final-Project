package edu.wisc.cs.sdn.apps.sps;

import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.instruction.OFInstruction;
import org.openflow.protocol.instruction.OFInstructionApplyActions;

import edu.wisc.cs.sdn.apps.util.Host;

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.routing.Link;

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
       this.shortestPaths = new ConcurrentHashMap<>();
   }

   public void computeShortestPaths(IOFSwitch source, Collection<IOFSwitch> switches, Collection<Link> links) {
       Map<IOFSwitch, NodeState> distances = new HashMap<>();
       Map<IOFSwitch, Boolean> visited = new HashMap<>();
       
       for (IOFSwitch sw : switches) {
           distances.put(sw, new NodeState(null, -1, Integer.MAX_VALUE));
           visited.put(sw, false);
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
           visited.put(current, true);
           
           for (Link link : links) {
               if (link.getSrc() != current.getId()) continue;
               
               IOFSwitch neighbor = switches.stream()
                   .filter(sw -> sw.getId() == link.getDst())
                   .findFirst()
                   .orElse(null);
                   
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
       IOFSwitch currentSwitch = srcHost.getSwitch();
       IOFSwitch targetSwitch = dstHost.getSwitch();
       
       while (currentSwitch != null && !currentSwitch.equals(targetSwitch)) {
           NodeState nextNode = shortestPaths.get(currentSwitch).get(targetSwitch);
           if (nextNode == null || nextNode.nextHop == null) break;

           OFMatch match = new OFMatch()
               .setNetworkDestination(dstHost.getIPv4Address());

           OFAction outputAction = new OFActionOutput(nextNode.port);
           OFInstruction applyActions = new OFInstructionApplyActions(Arrays.asList(outputAction));

           SwitchCommands.installRule(currentSwitch, flowTableId, 
               SwitchCommands.DEFAULT_PRIORITY, match, Arrays.asList(applyActions));

           currentSwitch = nextNode.nextHop;
       }
   }

   public void removeFlowRules(Host host, Collection<IOFSwitch> switches) {
       for (IOFSwitch sw : switches) {
           OFMatch match = new OFMatch()
               .setNetworkDestination(host.getIPv4Address());
           SwitchCommands.removeRules(sw, flowTableId, match);
       }
   }

   public void handleTopologyUpdate(Collection<IOFSwitch> switches, Collection<Link> links, Collection<Host> hosts) {
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