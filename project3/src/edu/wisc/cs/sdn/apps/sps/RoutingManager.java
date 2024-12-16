package edu.wisc.cs.sdn.apps.sps;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
    private static final Logger log = LoggerFactory.getLogger("ROUTING_MANAGER");

    // Inner class to store routing information
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
    private int previousLinkCount;

    public RoutingManager(byte flowTableId) {
        this.flowTableId = flowTableId;
        this.shortestPaths = new ConcurrentHashMap<IOFSwitch, Map<IOFSwitch, NodeState>>();
        this.previousLinkCount = 0;
    }

    public void computeShortestPaths(IOFSwitch source, Collection<IOFSwitch> switches, Collection<Link> links) {
        if (source == null || switches == null || links == null) {
            log.error("Invalid parameters passed to computeShortestPaths");
            return;
        }

        Map<IOFSwitch, NodeState> distances = new HashMap<IOFSwitch, NodeState>();
        Map<IOFSwitch, Boolean> visited = new HashMap<IOFSwitch, Boolean>();

        // Initialize distances
        for (IOFSwitch sw : switches) {
            distances.put(sw, new NodeState(null, -1, Integer.MAX_VALUE));
            visited.put(sw, Boolean.FALSE);
        }

        // Set distance to source as 0
        NodeState sourceState = distances.get(source);
        if (sourceState != null) {
            sourceState.cost = 0;
        }

        // Dijkstra's algorithm
        for (int i = 0; i < switches.size(); i++) {
            // Find unvisited node with minimum distance
            IOFSwitch current = null;
            int minDist = Integer.MAX_VALUE;

            for (IOFSwitch sw : switches) {
                if (!visited.get(sw) && distances.get(sw).cost < minDist) {
                    minDist = distances.get(sw).cost;
                    current = sw;
                }
            }

            if (current == null) break;

            visited.put(current, Boolean.TRUE);

            // Update distances to neighbors
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

                int newDist = distances.get(current).cost + 1;
                NodeState neighborState = distances.get(neighbor);
                if (newDist < neighborState.cost) {
                    neighborState.cost = newDist;
                    neighborState.nextHop = current;
                    neighborState.port = link.getSrcPort();
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

        log.info("Installing flow rules from " + srcHost.getName() + " to " + dstHost.getName());

        IOFSwitch currentSwitch = srcHost.getSwitch();
        IOFSwitch targetSwitch = dstHost.getSwitch();

        // Handle direct connection case
        if (currentSwitch.equals(targetSwitch)) {
            OFMatch match = new OFMatch();
            match.setDataLayerType(Ethernet.TYPE_IPv4);
            match.setNetworkDestination(dstHost.getIPv4Address());

            // Remove old rules first
            SwitchCommands.removeRules(currentSwitch, flowTableId, match);

            OFActionOutput outputAction = new OFActionOutput(dstHost.getPort());
            OFInstructionApplyActions applyActions = new OFInstructionApplyActions(
                    Arrays.asList((OFAction)outputAction));

            try {
                SwitchCommands.installRule(
                        currentSwitch,
                        flowTableId,
                        SwitchCommands.DEFAULT_PRIORITY,
                        match,
                        Arrays.asList((OFInstruction)applyActions),
                        SwitchCommands.NO_TIMEOUT,
                        SwitchCommands.NO_TIMEOUT
                );
                log.info("Installed direct connection rule on switch " + currentSwitch.getId());
            } catch (Exception e) {
                log.error("Failed to install rule: " + e.getMessage());
            }
            return;
        }

        // Handle multi-hop case
        while (currentSwitch != null && !currentSwitch.equals(targetSwitch)) {
            Map<IOFSwitch, NodeState> paths = shortestPaths.get(currentSwitch);
            if (paths == null) {
                log.error("No paths found for switch " + currentSwitch.getId());
                break;
            }

            NodeState nextNode = paths.get(targetSwitch);
            if (nextNode == null || nextNode.nextHop == null) {
                log.error("No next hop found for target switch " + targetSwitch.getId());
                break;
            }

            try {
                OFMatch match = new OFMatch();
                match.setDataLayerType(Ethernet.TYPE_IPv4);
                match.setNetworkDestination(dstHost.getIPv4Address());

                // Remove old rules first
                SwitchCommands.removeRules(currentSwitch, flowTableId, match);

                OFActionOutput outputAction = new OFActionOutput(nextNode.port);
                OFInstructionApplyActions applyActions = new OFInstructionApplyActions(
                        Arrays.asList((OFAction)outputAction));

                SwitchCommands.installRule(
                        currentSwitch,
                        flowTableId,
                        SwitchCommands.DEFAULT_PRIORITY,
                        match,
                        Arrays.asList((OFInstruction)applyActions),
                        SwitchCommands.NO_TIMEOUT,
                        SwitchCommands.NO_TIMEOUT
                );
                log.info("Installed rule on switch " + currentSwitch.getId() + " with output port " + nextNode.port);
            } catch (Exception e) {
                log.error("Failed to install rule: " + e.getMessage());
                break;
            }

            currentSwitch = nextNode.nextHop;
        }
    }

    public void handleTopologyUpdate(Collection<IOFSwitch> switches, Collection<Link> links, Collection<Host> hosts) {
        log.info("Handling topology update - Switches: " + switches.size() +
                ", Links: " + links.size() +
                ", Hosts: " + hosts.size());

        // Check if topology actually changed
        if (links.size() == previousLinkCount) {
            log.info("No topology change detected, skipping update");
            return;
        }
        previousLinkCount = links.size();

        // Remove all existing rules first
        for (IOFSwitch sw : switches) {
            OFMatch match = new OFMatch();
            match.setDataLayerType(Ethernet.TYPE_IPv4);
            SwitchCommands.removeRules(sw, flowTableId, match);
        }

        // Recompute all paths
        for (IOFSwitch sw : switches) {
            computeShortestPaths(sw, switches, links);
        }

        // Install new rules for all host pairs
        for (Host srcHost : hosts) {
            if (!srcHost.isAttachedToSwitch()) {
                log.info("Skipping host " + srcHost.getName() + " - not attached to switch");
                continue;
            }

            for (Host dstHost : hosts) {
                if (dstHost.equals(srcHost) || !dstHost.isAttachedToSwitch()) continue;
                installFlowRules(srcHost, dstHost);
            }
        }

        log.info("Finished topology update");
    }
}