package edu.wisc.cs.sdn.apps.sps;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.Stack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.routing.Link;

public class RoutingManager {
    private static Logger log = LoggerFactory.getLogger(RoutingManager.class);
    private Map<Long, Map<Long, List<Link>>> pathCache;
    private int previousLinkCount;

    public RoutingManager() {
        this.pathCache = new HashMap<Long, Map<Long, List<Link>>>();
        this.previousLinkCount = 0;
    }

    public boolean routeGeneration(Collection<Link> links) {
        if (links.size() == previousLinkCount) {
            return false;
        }
        previousLinkCount = links.size();

        // Build adjacency map
        Map<Long, Map<Long, Link>> adjacencyMap = new HashMap<Long, Map<Long, Link>>();
        for (Link link : links) {
            long src = link.getSrc();
            long dst = link.getDst();

            if (!adjacencyMap.containsKey(src)) {
                adjacencyMap.put(src, new HashMap<Long, Link>());
            }
            if (!adjacencyMap.containsKey(dst)) {
                adjacencyMap.put(dst, new HashMap<Long, Link>());
            }
            adjacencyMap.get(src).put(dst, link);
            // Add reverse link
            Link reverseLink = new Link(dst, link.getDstPort(), src, link.getSrcPort());
            adjacencyMap.get(dst).put(src, reverseLink);
        }

        // Clear and recompute all paths
        pathCache.clear();

        // Compute shortest paths for each switch pair
        for (Long source : adjacencyMap.keySet()) {
            computePathsFromSource(source, adjacencyMap);
        }

        return true;
    }

    private void computePathsFromSource(Long source, Map<Long, Map<Long, Link>> adjacencyMap) {
        Map<Long, List<Link>> paths = new HashMap<Long, List<Link>>();
        final Map<Long, Integer> distances = new HashMap<Long, Integer>();
        Map<Long, Long> previous = new HashMap<Long, Long>();
        PriorityQueue<Long> queue = new PriorityQueue<Long>(11, new Comparator<Long>() {
            public int compare(Long a, Long b) {
                return distances.get(a).compareTo(distances.get(b));
            }
        });

        // Initialize distances
        for (Long node : adjacencyMap.keySet()) {
            distances.put(node, Integer.MAX_VALUE);
            previous.put(node, null);
        }
        distances.put(source, 0);
        queue.offer(source);

        // Dijkstra's algorithm
        while (!queue.isEmpty()) {
            Long current = queue.poll();

            if (!adjacencyMap.containsKey(current)) continue;

            for (Map.Entry<Long, Link> entry : adjacencyMap.get(current).entrySet()) {
                Long neighbor = entry.getKey();
                int newDist = distances.get(current) + 1;

                if (newDist < distances.get(neighbor)) {
                    distances.put(neighbor, newDist);
                    previous.put(neighbor, current);
                    queue.remove(neighbor);
                    queue.offer(neighbor);
                }
            }
        }

        // Reconstruct paths
        for (Long dest : adjacencyMap.keySet()) {
            if (!dest.equals(source)) {
                List<Link> path = reconstructPath(source, dest, previous, adjacencyMap);
                if (!path.isEmpty()) {
                    paths.put(dest, path);
                }
            }
        }

        pathCache.put(source, paths);
    }

    private List<Link> reconstructPath(Long source, Long dest, Map<Long, Long> previous,
                                       Map<Long, Map<Long, Link>> adjacencyMap) {
        List<Link> path = new ArrayList<Link>();
        Long current = dest;

        while (previous.get(current) != null) {
            Long prev = previous.get(current);
            Link link = adjacencyMap.get(prev).get(current);
            path.add(0, link);
            current = prev;

            if (current.equals(source)) break;
        }

        return path;
    }

    public Link getNextHop(long srcId, long dstId) {
        if (!pathCache.containsKey(srcId) || !pathCache.get(srcId).containsKey(dstId)) {
            return null;
        }
        List<Link> path = pathCache.get(srcId).get(dstId);
        return path.isEmpty() ? null : path.get(0);
    }
}