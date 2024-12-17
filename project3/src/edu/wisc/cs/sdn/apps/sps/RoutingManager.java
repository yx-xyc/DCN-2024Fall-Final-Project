package edu.wisc.cs.sdn.apps.sps;

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.routing.Link;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.instruction.OFInstruction;
import org.openflow.protocol.instruction.OFInstructionApplyActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import edu.wisc.cs.sdn.apps.util.Host;
import edu.wisc.cs.sdn.apps.util.SwitchCommands;

import java.util.*;

public class RoutingManager {
    private final byte flowTableId;
    private static final Logger log = LoggerFactory.getLogger(RoutingManager.class);

    public RoutingManager(byte flowTableId) {
        this.flowTableId = flowTableId;
    }

    // Path and Graph inner classes remain exactly the same...
    private static class Path {
        private final List<Long> switchIds;
        private final List<Integer> ports;

        public Path() {
            this.switchIds = new ArrayList<Long>();
            this.ports = new ArrayList<Integer>();
        }

        public void addNode(long switchId, int port) {
            switchIds.add(switchId);
            ports.add(port);
        }

        public int getNextHopPort() {
            return ports.size() > 1 ? ports.get(1) : ports.get(0);
        }
    }

    private static class Graph {
        private final Map<Long, Map<Long, Integer>> adjacencyList;
        private final Map<Long, Map<Long, Integer>> portMap;

        public Graph() {
            this.adjacencyList = new HashMap<Long, Map<Long, Integer>>();
            this.portMap = new HashMap<Long, Map<Long, Integer>>();
        }

        public void addNode(long nodeId) {
            if (!adjacencyList.containsKey(nodeId)) {
                adjacencyList.put(nodeId, new HashMap<Long, Integer>());
                portMap.put(nodeId, new HashMap<Long, Integer>());
            }
        }

        public void addEdge(long src, long dst, int srcPort, int dstPort) {
            adjacencyList.get(src).put(dst, 1);
            adjacencyList.get(dst).put(src, 1);
            portMap.get(src).put(dst, srcPort);
            portMap.get(dst).put(src, dstPort);
        }

        public Map<Long, Path> computeShortestPaths(long source) {
            final Map<Long, Integer> distances = new HashMap<Long, Integer>();
            Map<Long, Long> previousNode = new HashMap<Long, Long>();
            Map<Long, Path> paths = new HashMap<Long, Path>();

            PriorityQueue<Long> queue = new PriorityQueue<Long>(10, new Comparator<Long>() {
                public int compare(Long a, Long b) {
                    Integer distA = distances.containsKey(a) ? distances.get(a) : Integer.MAX_VALUE;
                    Integer distB = distances.containsKey(b) ? distances.get(b) : Integer.MAX_VALUE;
                    return distA.compareTo(distB);
                }
            });

            for (Long node : adjacencyList.keySet()) {
                distances.put(node, Integer.MAX_VALUE);
            }
            distances.put(source, 0);
            queue.add(source);

            while (!queue.isEmpty()) {
                long current = queue.poll();

                for (Map.Entry<Long, Integer> neighbor : adjacencyList.get(current).entrySet()) {
                    long next = neighbor.getKey();
                    int weight = neighbor.getValue();
                    int newDist = distances.get(current) + weight;

                    if (newDist < (distances.containsKey(next) ? distances.get(next) : Integer.MAX_VALUE)) {
                        distances.put(next, newDist);
                        previousNode.put(next, current);
                        queue.add(next);
                    }
                }
            }

            for (long node : adjacencyList.keySet()) {
                if (node != source && distances.get(node) < Integer.MAX_VALUE) {
                    Path path = new Path();
                    long current = node;
                    Stack<Long> stack = new Stack<Long>();
                    Stack<Integer> portStack = new Stack<Integer>();

                    while (current != source) {
                        long prev = previousNode.get(current);
                        stack.push(current);
                        portStack.push(portMap.get(prev).get(current));
                        current = prev;
                    }
                    stack.push(source);

                    while (!stack.isEmpty()) {
                        long switchId = stack.pop();
                        int port = portStack.isEmpty() ? -1 : portStack.pop();
                        path.addNode(switchId, port);
                    }

                    paths.put(node, path);
                }
            }

            return paths;
        }
    }

    public void removeFlowRules(Host host, Collection<IOFSwitch> switches) {
        if (host == null || !host.isAttachedToSwitch()) {
            return;
        }

        OFMatch match = new OFMatch();
        match.setDataLayerType(Ethernet.TYPE_IPv4);
        byte[] macBytes = new byte[6];
        long mac = host.getMACAddress();
        for (int i = 5; i >= 0; i--) {
            macBytes[i] = (byte) (mac & 0xFF);
            mac >>= 8;
        }
        match.setDataLayerDestination(macBytes);

        for (IOFSwitch sw : switches) {
            boolean removed = SwitchCommands.removeRules(sw, flowTableId, match);
            if (removed) {
                log.info(String.format("Removed flow rules for host %s from switch %s",
                        host.getName(), sw.getStringId()));
            } else {
                log.error(String.format("Failed to remove flow rules for host %s from switch %s",
                        host.getName(), sw.getStringId()));
            }
        }
    }

    public void handleTopologyUpdate(Collection<IOFSwitch> switches, Collection<Link> links, Collection<Host> hosts) {
        Graph graph = new Graph();

        for (IOFSwitch sw : switches) {
            graph.addNode(sw.getId());
        }

        for (Link link : links) {
            graph.addEdge(link.getSrc(), link.getDst(), link.getSrcPort(), link.getDstPort());
        }

        for (Host host : hosts) {
            if (!host.isAttachedToSwitch()) {
                continue;
            }

            IOFSwitch hostSwitch = host.getSwitch();
            int hostPort = host.getPort();

            // First, create match for IPv4 packets
            OFMatch ipMatch = new OFMatch();
            ipMatch.setDataLayerType(Ethernet.TYPE_IPv4);
            byte[] macBytes = new byte[6];
            long mac = host.getMACAddress();
            for (int i = 5; i >= 0; i--) {
                macBytes[i] = (byte) (mac & 0xFF);
                mac >>= 8;
            }
            ipMatch.setDataLayerDestination(macBytes);

            // Create match for ARP packets
            OFMatch arpMatch = new OFMatch();
            arpMatch.setDataLayerType(Ethernet.TYPE_ARP);
            arpMatch.setDataLayerDestination(macBytes);

            Map<Long, Path> paths = graph.computeShortestPaths(hostSwitch.getId());

            for (IOFSwitch sw : switches) {
                Path path = paths.get(sw.getId());
                if (path == null) {
                    continue;
                }

                int outputPort;
                if (sw.getId() == hostSwitch.getId()) {
                    outputPort = hostPort;
                } else {
                    outputPort = path.getNextHopPort();
                }

                // Create action to output to the correct port
                OFActionOutput action = new OFActionOutput(outputPort);
                List<OFAction> actions = new ArrayList<OFAction>();
                actions.add(action);

                // Create instruction to apply the actions
                OFInstructionApplyActions instruction = new OFInstructionApplyActions();
                instruction.setActions(actions);
                List<OFInstruction> instructions = new ArrayList<OFInstruction>();
                instructions.add(instruction);

                // Install rule for IPv4 traffic
                boolean installedIp = SwitchCommands.installRule(sw, flowTableId,
                        SwitchCommands.DEFAULT_PRIORITY, ipMatch, instructions);
                if (installedIp) {
                    log.info(String.format("Installed IPv4 flow rule on switch %s to route to host %s",
                            sw.getStringId(), host.getName()));
                } else {
                    log.error(String.format("Failed to install IPv4 flow rule on switch %s",
                            sw.getStringId()));
                }

                // Install rule for ARP traffic
                boolean installedArp = SwitchCommands.installRule(sw, flowTableId,
                        SwitchCommands.DEFAULT_PRIORITY, arpMatch, instructions);
                if (installedArp) {
                    log.info(String.format("Installed ARP flow rule on switch %s to route to host %s",
                            sw.getStringId(), host.getName()));
                } else {
                    log.error(String.format("Failed to install ARP flow rule on switch %s",
                            sw.getStringId()));
                }
            }
        }

        // Install table-miss flow entry
        for (IOFSwitch sw : switches) {
            // Match all packets
            OFMatch matchAll = new OFMatch();

            // Create action to send to controller
            OFActionOutput actionController = new OFActionOutput(OFPort.OFPP_CONTROLLER);
            List<OFAction> actionsController = new ArrayList<OFAction>();
            actionsController.add(actionController);

            // Create instruction
            OFInstructionApplyActions instructionController = new OFInstructionApplyActions();
            instructionController.setActions(actionsController);
            List<OFInstruction> instructionsController = new ArrayList<OFInstruction>();
            instructionsController.add(instructionController);

            // Install table-miss entry
            SwitchCommands.installRule(sw, flowTableId,
                    (short)(SwitchCommands.DEFAULT_PRIORITY - 1),  // Lower priority
                    matchAll, instructionsController);
        }
    }
}