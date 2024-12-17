package edu.wisc.cs.sdn.apps.sps;

import java.util.Collection;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.Stack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.routing.Link;

public class RoutingManager {
    // Log information format;
    private static Logger log = LoggerFactory.getLogger("ROUTING_TEST_INFO");

    /**
     * Node structure to record adjacency table entries / shortest path entries;
     * @param dstID is the destination switch ID (adjacency table entry) or the
     * current switch ID (path entry);
     * @param srcPort is the port on the source switch connecting to this switch
     * (adjacency table entry) or the port from current switch to the next switch
     * on the path (path entry);
     */
    class Node {
        long dstID;
        int srcPort;
        Node next;
        Node() { }
        Node(long dstID, int srcPort) {
            this.dstID = dstID;
            this.srcPort = srcPort;
            this.next = null;
        }

        // Print the string format of a node;
        public String toString() {
            return "Switch " + dstID + " on port " + srcPort + ";";
        }
        public String toStringAsPair() {
            return "Switch " + dstID + ".port " + srcPort;
        }
    }
    private Map<Long, Node> adjacencyTable;
    private Map<Long, Map<Long, Node>> shortestPaths;
    private Set<Long> nodeSet;
    private int previousLinkCnt;
    private Map<Long, int []> distTable;
    private Map<Long, Node> pathTable;
    private Stack<Node> pathStack;

    void Routing() {
        adjacencyTable = new HashMap<Long, Node>();
        shortestPaths = new HashMap<Long, Map<Long, Node>>();
        nodeSet = new HashSet<Long>();
        previousLinkCnt = 0;
        distTable = new HashMap<Long, int []>();
        pathTable = new HashMap<Long, Node>();
        pathStack = new Stack<Node>();
    }

    /**
     * Generate topological graph as an adjacency list;
     * @param topoLinks is the collection of links in current network;
     */
    public void topoGeneration(Collection<Link> topoLinks) {
        adjacencyTable.clear();
        for(Link l : topoLinks) {
            long curKey = l.getSrc();
            if(!adjacencyTable.containsKey(curKey)) {
                adjacencyTable.put(curKey, new Node(l.getDst(), l.getSrcPort()));
            }
            else {
                Node temp = adjacencyTable.get(curKey);
                while(temp.next != null) {
                    temp = temp.next;
                }
                temp.next = new Node(l.getDst(), l.getSrcPort());
            }
        }
    }

    /**
     * Print topological graph as an adjacency list;
     */
    public void printTopo() {
        for(long key : adjacencyTable.keySet()) {
            log.info(String.format("Adjacent nodes for switch %d:", key));
            Node temp = adjacencyTable.get(key);
            while(temp != null) {
                log.info(temp.toString());
                temp = temp.next;
            }
        }
    }

    /**
     * Generate shortest paths according to current network topology;
     * @param topoLinks is the collection of links in current network;
     * Normally this should be obtained by getLinks();
     */
    public boolean routeGeneration(Collection<Link> topoLinks) {
        // If topology not changed, then no need to further operate;
        if(topoLinks.size() == previousLinkCnt) {
            return false;
        }
        previousLinkCnt = topoLinks.size();

        // First create network topology and initialize shortest path table;
        topoGeneration(topoLinks);
        shortestPaths.clear();
        int tableSize = adjacencyTable.size();
        for(long curSwitchID : adjacencyTable.keySet()) {

            // Initialize node set;
            nodeSet.clear();
            nodeSet.add(curSwitchID);

            // Initialize Dijkstra calculation table;
            distTable.clear();
            pathTable.clear(); // Node used as Pair<Long, int> here;
            for(long key : adjacencyTable.keySet()) {
                if(key != curSwitchID) {
                    distTable.put(key, new int[tableSize]);
                    distTable.get(key)[0] = 10000;
                    pathTable.put(key, new Node(-1L, -1));
                }
            }
            Node temp = adjacencyTable.get(curSwitchID);
            while(temp != null) {
                if(distTable.keySet().contains(temp.dstID)) {
                    distTable.get(temp.dstID)[0] = 1;
                    pathTable.put(temp.dstID, new Node(curSwitchID, temp.srcPort));
                }
                temp = temp.next;
            }

            // Dijkstra calculation below;
            for(int i = 0; i < tableSize - 1; i ++) {
                int minDist = Integer.MAX_VALUE;
                long curAdded = -1L;

                // Find node that is not in noseSet and distance is minimum;
                for(long key : distTable.keySet()) {
                    if(!nodeSet.contains(key)) {
                        if(distTable.get(key)[i] < minDist) {
                            minDist = distTable.get(key)[i];
                            curAdded = key;
                        }
                    }
                }

                // Add to nodeSet;
                nodeSet.add(curAdded);

                // Set the next dists to prev dist;
                Node adjNode = adjacencyTable.get(curAdded);
                for(long key : distTable.keySet()) {
                    if(!nodeSet.contains(key) && distTable.keySet().contains(key)) {
                        distTable.get(key)[i + 1] = distTable.get(key)[i];
                    }
                }

                // For each node adjacent to this newly added node, update their dist to source;
                while(adjNode != null) {
                    if(!nodeSet.contains(adjNode.dstID)) {
                        int newDist = distTable.get(curAdded)[i] + 1;
                        if(distTable.get(adjNode.dstID) != null && newDist < distTable.get(adjNode.dstID)[i + 1]) {
                            distTable.get(adjNode.dstID)[i + 1] = newDist;
                            pathTable.put(adjNode.dstID, new Node(curAdded, adjNode.srcPort));
                        }
                    }
                    adjNode = adjNode.next;
                }
            }

            // Extract paths and add to the shortest path map;
            shortestPaths.put(curSwitchID, new HashMap<Long, Node>());
            pathStack.clear();
            Map<Long, Node> curPaths = shortestPaths.get(curSwitchID);
            for(long key : pathTable.keySet()) {
                Node curPos = pathTable.get(key);
                while(curPos != null && curPos.dstID != curSwitchID) {
                    pathStack.push(curPos);
                    curPos = pathTable.get(curPos.dstID);
                }
                if(curPos == null) {
                    continue;
                }
                curPaths.put(key, new Node(curPos.dstID, curPos.srcPort));
                Node pathNode = curPaths.get(key);
                if(pathNode != null) {
                    while(!pathStack.empty()) {
                        pathNode.next = new Node(pathStack.peek().dstID, pathStack.peek().srcPort);
                        pathStack.pop();
                        pathNode = pathNode.next;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Print current shortest path table;
     */
    public void printPaths() {
        for(long srcSwitch : shortestPaths.keySet()) {
            Map<Long, Node> curPaths = shortestPaths.get(srcSwitch);
            for(long dstSwitch : curPaths.keySet()) {
                log.info(String.format("Path from switch %d to switch %d:", srcSwitch, dstSwitch));
                Node temp = curPaths.get(dstSwitch);
                String curMsg = "";
                while(temp != null) {
                    curMsg += temp.toStringAsPair() + " -> ";
                    temp = temp.next;
                }
                curMsg += "Destination;";
                log.info(curMsg);
            }
            log.info("===================================================");
        }
    }

    /**
     * Get current shortest path table;
     * Used in flow table generation;
     */
    public Map<Long, Map<Long, Node>> getShortestPaths() {
        return this.shortestPaths;
    }
}