package edu.nyu.cs.sdn.apps.sps;

import java.util.*;
import net.floodlightcontroller.routing.Link;
import edu.nyu.cs.sdn.apps.util.Host;

public class RoutingManager {
    // Stores computed paths between hosts
    private Map<Host, Map<Host, List<Link>>> pathCache;

    // Graph representation of network
    private Map<Long, Set<Link>> adjacencyList;

    public RoutingManager() {
        this.pathCache = new HashMap<Host, Map<Host, List<Link>>>();
        this.adjacencyList = new HashMap<Long, Set<Link>>();
    }

    /**
     * Update the network topology
     * @param links collection of all current links in the network
     */
    public void updateTopology(Collection<Link> links) {
        // Clear existing topology
        adjacencyList.clear();

        // Build new adjacency list
        for (Link link : links) {
            Long src = link.getSrc();
            if (!adjacencyList.containsKey(src)) {
                adjacencyList.put(src, new HashSet<Link>());
            }
            adjacencyList.get(src).add(link);
        }

        // Clear path cache as topology has changed
        pathCache.clear();
    }

    /**
     * Compute shortest path between two hosts using Dijkstra's algorithm
     */
    public List<Link> computePath(Host src, Host dst) {
        // Check cache first
        if (pathCache.containsKey(src) && pathCache.get(src).containsKey(dst)) {
            return pathCache.get(src).get(dst);
        }

        // Skip if either host isn't connected
        if (!src.isAttachedToSwitch() || !dst.isAttachedToSwitch()) {
            return null;
        }

        // Setup for Dijkstra's algorithm
        Map<Long, Integer> distances = new HashMap<Long, Integer>();
        Map<Long, Long> previous = new HashMap<Long, Long>();
        PriorityQueue<Long> queue = new PriorityQueue<Long>(10,
                new Comparator<Long>() {
                    public int compare(Long s1, Long s2) {
                        return distances.get(s1).compareTo(distances.get(s2));
                    }
                });

        // Initialize all distances to infinity
        for (Long switchId : adjacencyList.keySet()) {
            distances.put(switchId, Integer.MAX_VALUE);
        }

        // Initialize source
        Long srcSwitch = src.getSwitch().getId();
        distances.put(srcSwitch, 0);
        queue.offer(srcSwitch);

        // Run Dijkstra's algorithm
        while (!queue.isEmpty()) {
            Long current = queue.poll();

            // Found destination
            if (current.equals(dst.getSwitch().getId())) {
                break;
            }

            int distance = distances.get(current);

            // Look at all neighbors
            if (adjacencyList.containsKey(current)) {
                for (Link link : adjacencyList.get(current)) {
                    Long neighbor = link.getDst();
                    int newDist = distance + 1;

                    if (newDist < distances.get(neighbor)) {
                        distances.put(neighbor, newDist);
                        previous.put(neighbor, current);
                        queue.remove(neighbor);
                        queue.offer(neighbor);
                    }
                }
            }
        }

        // Build path from destination to source
        List<Link> path = new ArrayList<Link>();
        Long current = dst.getSwitch().getId();
        Long prev = previous.get(current);

        while (prev != null) {
            // Find link between current and previous
            for (Link link : adjacencyList.get(prev)) {
                if (link.getDst().equals(current)) {
                    path.add(0, link);
                    break;
                }
            }
            current = prev;
            prev = previous.get(current);
        }

        // Cache the computed path
        if (!pathCache.containsKey(src)) {
            pathCache.put(src, new HashMap<Host, List<Link>>());
        }
        pathCache.get(src).put(dst, path);

        return path;
    }

    /**
     * Clear all cached paths
     */
    public void clearCache() {
        pathCache.clear();
    }
}