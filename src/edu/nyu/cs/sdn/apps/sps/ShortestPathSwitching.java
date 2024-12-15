package edu.nyu.cs.sdn.apps.sps;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.floodlightcontroller.core.*;
import net.floodlightcontroller.core.module.*;
import net.floodlightcontroller.devicemanager.*;
import net.floodlightcontroller.linkdiscovery.*;
import net.floodlightcontroller.routing.Link;
import org.openflow.protocol.*;
import org.openflow.protocol.action.*;
import org.openflow.protocol.instruction.*;
import edu.nyu.cs.sdn.apps.util.Host;
import edu.nyu.cs.sdn.apps.util.SwitchCommands;

public class ShortestPathSwitching implements IFloodlightModule, IOFSwitchListener,
        ILinkDiscoveryListener, IDeviceListener, InterfaceShortestPathSwitching
{
    public static final String MODULE_NAME = ShortestPathSwitching.class.getSimpleName();

    // Logger
    private static Logger log = LoggerFactory.getLogger(MODULE_NAME);

    // Floodlight services
    private IFloodlightProviderService floodlightProv;
    private ILinkDiscoveryService linkDiscProv;
    private IDeviceService deviceProv;

    // Switch table for installing rules
    private byte table;

    // Known hosts in the network
    private Map<IDevice, Host> knownHosts;

    // Routing manager for path computation
    private RoutingManager routingManager;

    @Override
    public void init(FloodlightModuleContext context) throws FloodlightModuleException {
        log.info(String.format("Initializing %s...", MODULE_NAME));

        // Get table number from config
        Map<String,String> config = context.getConfigParams(this);
        this.table = Byte.parseByte(config.get("table"));

        // Get Floodlight services
        this.floodlightProv = context.getServiceImpl(IFloodlightProviderService.class);
        this.linkDiscProv = context.getServiceImpl(ILinkDiscoveryService.class);
        this.deviceProv = context.getServiceImpl(IDeviceService.class);

        // Initialize data structures
        this.knownHosts = new ConcurrentHashMap<IDevice, Host>();
        this.routingManager = new RoutingManager();
    }

    @Override
    public void startUp(FloodlightModuleContext context) {
        log.info(String.format("Starting %s...", MODULE_NAME));

        // Register for events
        this.floodlightProv.addOFSwitchListener(this);
        this.linkDiscProv.addListener(this);
        this.deviceProv.addListener(this);
    }

    @Override
    public byte getTable() {
        return this.table;
    }

    @Override
    public boolean installRoute(long srcHost, long dstHost) {
        // Find source and destination hosts
        Host src = null, dst = null;
        for (Host host : knownHosts.values()) {
            if (host.getMACAddress() == srcHost) {
                src = host;
            }
            if (host.getMACAddress() == dstHost) {
                dst = host;
            }
        }

        if (src == null || dst == null) {
            return false;
        }

        // Compute path
        List<Link> path = routingManager.computePath(src, dst);
        if (path == null) {
            return false;
        }

        // Install rules along the path
        for (Link link : path) {
            IOFSwitch sw = floodlightProv.getSwitch(link.getSrc());

            // Create match
            OFMatch match = new OFMatch();
            match.setDataLayerType(Ethernet.TYPE_IPv4);
            match.setDataLayerDestination(Ethernet.toByteArray(dst.getMACAddress()));

            // Create action to output to port
            OFActionOutput action = new OFActionOutput(link.getSrcPort());
            List<OFAction> actions = new ArrayList<OFAction>();
            actions.add(action);

            // Create instruction
            OFInstructionApplyActions instruction = new OFInstructionApplyActions(actions);
            List<OFInstruction> instructions = new ArrayList<OFInstruction>();
            instructions.add(instruction);

            // Install rule
            SwitchCommands.installRule(sw, this.table, SwitchCommands.DEFAULT_PRIORITY,
                    match, instructions);
        }

        return true;
    }

    @Override
    public boolean removeRoute(long srcHost, long dstHost) {
        // Find destination host
        Host dst = null;
        for (Host host : knownHosts.values()) {
            if (host.getMACAddress() == dstHost) {
                dst = host;
                break;
            }
        }

        if (dst == null) {
            return false;
        }

        // Remove rules from all switches
        for (IOFSwitch sw : floodlightProv.getAllSwitchMap().values()) {
            OFMatch match = new OFMatch();
            match.setDataLayerType(Ethernet.TYPE_IPv4);
            match.setDataLayerDestination(Ethernet.toByteArray(dst.getMACAddress()));
            SwitchCommands.removeRules(sw, this.table, match);
        }

        return true;
    }

    // Event Handlers

    @Override
    public void deviceAdded(IDevice device) {
        Host host = new Host(device, this.floodlightProv);
        if (host.getIPv4Address() != null) {
            log.info(String.format("Host %s added", host.getName()));
            this.knownHosts.put(device, host);

            // Update all routes
            routingManager.clearCache();
            for (Host src : knownHosts.values()) {
                for (Host dst : knownHosts.values()) {
                    if (!src.equals(dst)) {
                        installRoute(src.getMACAddress(), dst.getMACAddress());
                    }
                }
            }
        }
    }

    @Override
    public void deviceRemoved(IDevice device) {
        Host host = this.knownHosts.get(device);
        if (host != null) {
            log.info(String.format("Host %s removed", host.getName()));
            this.knownHosts.remove(device);

            // Remove all routes to this host
            for (Host src : knownHosts.values()) {
                removeRoute(src.getMACAddress(), host.getMACAddress());
            }
        }
    }

    @Override
    public void deviceMoved(IDevice device) {
        Host host = this.knownHosts.get(device);
        if (host != null) {
            if (!host.isAttachedToSwitch()) {
                deviceRemoved(device);
                return;
            }
            log.info(String.format("Host %s moved to s%d:%d", host.getName(),
                    host.getSwitch().getId(), host.getPort()));

            // Update all routes
            routingManager.clearCache();
            for (Host src : knownHosts.values()) {
                for (Host dst : knownHosts.values()) {
                    if (!src.equals(dst)) {
                        installRoute(src.getMACAddress(), dst.getMACAddress());
                    }
                }
            }
        }
    }

    @Override
    public void linkDiscoveryUpdate(List<LDUpdate> updateList) {
        // Update topology
        routingManager.updateTopology(getLinks());

        // Recompute all routes
        for (Host src : knownHosts.values()) {
            for (Host dst : knownHosts.values()) {
                if (!src.equals(dst)) {
                    installRoute(src.getMACAddress(), dst.getMACAddress());
                }
            }
        }
    }

    @Override
    public void switchAdded(long switchId) {
        log.info(String.format("Switch s%d added", switchId));
    }

    @Override
    public void switchRemoved(long switchId) {
        log.info(String.format("Switch s%d removed", switchId));

        // Will get link updates that will trigger route updates
    }

    // Helper methods

    private Collection<Link> getLinks() {
        return linkDiscProv.getLinks().keySet();
    }

    // Required interface implementations omitted for brevity
    // (Service registration methods, etc.)
}