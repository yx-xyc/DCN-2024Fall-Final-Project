package edu.wisc.cs.sdn.apps.sps;

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.routing.Link;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import edu.wisc.cs.sdn.apps.util.Host;
import edu.wisc.cs.sdn.apps.util.SwitchCommands;

import java.io.IOException;
import java.util.*;

public class RoutingManager {
    private final byte flowTableId;
    private static final Logger log = LoggerFactory.getLogger(RoutingManager.class);

    public RoutingManager(byte flowTableId) {
        this.flowTableId = flowTableId;
    }

    // Inner class to represent a network path
    private static class Path {
        private final List<Long> switchIds;
        private final List<Integer> ports;

        public Path() {
            this.switchIds = new ArrayList<>();
            this.ports = new ArrayList<>();
        }

        public void addNode(long switchId, int port) {
            switchIds.add(switchId);
            ports.add(port);
        }

        public int getNextHopPort() {
            return ports.size() > 1 ? ports.get(1) : ports.get(0);
        }
    }

    // Inner class to represent the network graph
    private static class Graph {
        private final Map<Long, Map<Long, Integer>> adjacencyList;
        private final Map<Long, Map<Long, Integer>> portMap;

        public Graph() {
            this.adjacencyList = new HashMap<>();
            this.portMap = new HashMap<>();
        }

        public void addNode(long nodeId) {
            adjacencyList.putIfAbsent(nodeId, new HashMap<>());
            portMap.putIfAbsent(nodeId, new HashMap<>());
        }

        public void addEdge(long src, long dst, int srcPort, int dstPort) {
            adjacencyList.get(src).put(dst, 1); // Weight of 1 for all edges
            adjacencyList.get(dst).put(src, 1); // Add reverse edge
            portMap.get(src).put(dst, srcPort);
            portMap.get(dst).put(src, dstPort);
        }

        public Map<Long, Path> computeShortestPaths(long source) {
            Map<Long, Integer> distances = new HashMap<>();
            Map<Long, Long> previousNode = new HashMap<>();
            Map<Long, Path> paths = new HashMap<>();
            PriorityQueue<Long> queue = new PriorityQueue<>(
                    (a, b) -> distances.getOrDefault(a, Integer.MAX_VALUE)
                            .compareTo(distances.getOrDefault(b, Integer.MAX_VALUE)));

            // Initialize distances
            for (Long node : adjacencyList.keySet()) {
                distances.put(node, Integer.MAX_VALUE);
            }
            distances.put(source, 0);
            queue.add(source);

            // Dijkstra's algorithm
            while (!queue.isEmpty()) {
                long current = queue.poll();

                for (Map.Entry<Long, Integer> neighbor : adjacencyList.get(current).entrySet()) {
                    long next = neighbor.getKey();
                    int weight = neighbor.getValue();
                    int newDist = distances.get(current) + weight;

                    if (newDist < distances.getOrDefault(next, Integer.MAX_VALUE)) {
                        distances.put(next, newDist);
                        previousNode.put(next, current);
                        queue.add(next);
                    }
                }
            }

            // Construct paths
            for (long node : adjacencyList.keySet()) {
                if (node != source && distances.get(node) < Integer.MAX_VALUE) {
                    Path path = new Path();
                    long current = node;
                    Stack<Long> stack = new Stack<>();
                    Stack<Integer> portStack = new Stack<>();

                    // Traverse from destination to source
                    while (current != source) {
                        long prev = previousNode.get(current);
                        stack.push(current);
                        portStack.push(portMap.get(prev).get(current));
                        current = prev;
                    }
                    stack.push(source);

                    // Build path in correct order
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

        // Create match for the host's MAC address
        OFMatch match = new OFMatch();
        match.setDataLayerType(Ethernet.TYPE_IPv4);
        match.setDataLayerDestination(host.getMACAddress());

        // Remove flow rules from all switches
        for (IOFSwitch sw : switches) {
            try {
                SwitchCommands.removeRules(sw, flowTableId, match);
                log.info(String.format("Removed flow rules for host %s from switch %s",
                        host.getName(), sw.getStringId()));
            } catch (IOException e) {
                log.error(String.format("Failed to remove flow rules for host %s from switch %s",
                        host.getName(), sw.getStringId()), e);
            }
        }
    }

    public void handleTopologyUpdate(Collection<IOFSwitch> switches, Collection<Link> links, Collection<Host> hosts) {
        // Create graph representation
        Graph graph = new Graph();

        // Add switches as nodes
        for (IOFSwitch sw : switches) {
            graph.addNode(sw.getId());
        }

        // Add links as edges
        for (Link link : links) {
            graph.addEdge(link.getSrc(), link.getDst(), link.getSrcPort(), link.getDstPort());
        }

        // For each host
        for (Host host : hosts) {
            if (!host.isAttachedToSwitch()) {
                continue;
            }

            // Get host's attachment point
            IOFSwitch hostSwitch = host.getSwitch();
            int hostPort = host.getPort();

            // Create match for the host's MAC address
            OFMatch match = new OFMatch();
            match.setDataLayerType(Ethernet.TYPE_IPv4);
            match.setDataLayerDestination(host.getMACAddress());

            // Compute shortest paths from all switches to the host's switch
            Map<Long, Path> paths = graph.computeShortestPaths(hostSwitch.getId());

            // Install flow rules on each switch
            for (IOFSwitch sw : switches) {
                // Skip if no path exists
                Path path = paths.get(sw.getId());
                if (path == null) {
                    continue;
                }

                // Get output port for next hop
                int outputPort;
                if (sw.getId() == hostSwitch.getId()) {
                    outputPort = hostPort;
                } else {
                    outputPort = path.getNextHopPort();
                }

                // Create output action
                OFActionOutput action = new OFActionOutput(outputPort);
                List<OFAction> actions = new ArrayList<OFAction>();
                actions.add(action);

                // Install flow rule
                try {
                    SwitchCommands.installRule(sw, flowTableId,
                            SwitchCommands.DEFAULT_PRIORITY, match, actions);
                    log.info(String.format("Installed flow rule on switch %s to route to host %s",
                            sw.getStringId(), host.getName()));
                } catch (IOException e) {
                    log.error(String.format("Failed to install flow rule on switch %s",
                            sw.getStringId()), e);
                }
            }
        }
    }
}