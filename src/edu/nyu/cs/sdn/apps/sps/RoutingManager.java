package edu.nyu.cs.sdn.apps.sps;

import java.util.*;
import net.floodlightcontroller.routing.Link;
import net.floodlightcontroller.core.IOFSwitch;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.instruction.OFInstruction;
import org.openflow.protocol.instruction.OFInstructionApplyActions;
import edu.nyu.cs.sdn.apps.util.Host;
import edu.nyu.cs.sdn.apps.util.SwitchCommands;

public interface RoutingManager {
    // Map to store computed shortest paths between switches
    private Map<Long, Map<Long, List<Link>>> shortestPaths;
    // Reference to the main switching application
    private ShortestPathSwitching switchingApp;

    public RoutingManager(ShortestPathSwitching switchingApp) {
        this.switchApp = switchingApp;
        this.shortestPaths = new HashMap<>();
    }
    /**
     * Compute shortest paths between all switches using Dijkstra's algorithm
     * */
    public void computeAllPaths(Map<Long, IOFSwitch> switches, Collection<Link> links) {
        shortestPaths.clear();

        // For each switch as source
        for (Long srcDpid : switches.keySet()) {
            Map<Long, List<Link>> paths = new HashMap<>();
            Map<Long, Integer> distances = new HashMap<>();
            Map<Long, Long> previous = new HashMap<>();
            PriorityQueue<Long> pq = new PriorityQueue<>(
                    (a, b) -> distances.getOrDefault(a, Integer.MAX_VALUE)
                            .compareTo(distances.getOrDefault(b, Integer.MAX_VALUE)));

            // Initialize distances
            for (Long dpid : switches.keySet()) {
                distances.put(dpid, Integer.MAX_VALUE);
                previous.put(dpid, null);
            }
            distances.put(srcDpid, 0);
            pq.offer(srcDpid);

            // Dijkstra's algorithm
            while (!pq.isEmpty()) {
                Long current = pq.poll();

                // Get all links from current switch
                for (Link link : links) {
                    if (link.getSrc() != current) continue;

                    Long neighbor = link.getDst();
                    int newDist = distances.get(current) + 1; // Using hop count as metric

                    if (newDist < distances.getOrDefault(neighbor, Integer.MAX_VALUE)) {
                        distances.put(neighbor, newDist);
                        previous.put(neighbor, current);
                        pq.remove(neighbor);
                        pq.offer(neighbor);
                    }
                }
            }

            // Build paths from computed previous nodes
            for (Long dstDpid : switches.keySet()) {
                if (dstDpid.equals(srcDpid)) continue;
                List<Link> path = buildPath(srcDpid, dstDpid, previous, links);
                if (!path.isEmpty()) {
                    paths.put(dstDpid, path);
                }
            }

            shortestPaths.put(srcDpid, paths);
        }
    }

}