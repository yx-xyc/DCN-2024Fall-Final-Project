package edu.wisc.cs.sdn.apps.loadbalancer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.action.OFActionSetField;
import org.openflow.protocol.instruction.OFInstruction;
import org.openflow.protocol.instruction.OFInstructionApplyActions;
import org.openflow.protocol.instruction.OFInstructionGotoTable;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFType;
import org.openflow.protocol.OFOXMField;
import org.openflow.protocol.OFOXMFieldType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.wisc.cs.sdn.apps.sps.InterfaceShortestPathSwitching;
import edu.wisc.cs.sdn.apps.sps.ShortestPathSwitching;
import edu.wisc.cs.sdn.apps.l3routing.IL3Routing;
import edu.wisc.cs.sdn.apps.util.ArpServer;
import edu.wisc.cs.sdn.apps.util.SwitchCommands;


import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch.PortChangeType;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.internal.DeviceManagerImpl;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.util.MACAddress;

public class LoadBalancer implements IFloodlightModule, IOFSwitchListener,
		IOFMessageListener
{
	public static final String MODULE_NAME = LoadBalancer.class.getSimpleName();
	
	private static final byte TCP_FLAG_SYN = 0x02;
	private static final byte TCP_FLAG_RST = 0x04;
	private static final short IDLE_TIMEOUT = 20;

	// Interface to the logging system
    private static Logger log = LoggerFactory.getLogger(MODULE_NAME);
    
    // Interface to Floodlight core for interacting with connected switches
    private IFloodlightProviderService floodlightProv;
    
    // Interface to device manager service
    private IDeviceService deviceProv;
    
    // Interface to L3Routing application
	// replace with sps
    // private IL3Routing l3RoutingApp;

	private InterfaceShortestPathSwitching spsApp;

    // Switch table in which rules should be installed
    private byte table;
    
    // Set of virtual IPs and the load balancer instances they correspond with
    private Map<Integer,LoadBalancerInstance> instances;

	private  static final boolean isLogging = true;

    /**
     * Loads dependencies and initializes data structures.
     */
	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException 
	{
		log.info(String.format("Initializing %s...", MODULE_NAME));
		
		// Obtain table number from config
		Map<String,String> config = context.getConfigParams(this);
        this.table = Byte.parseByte(config.get("table"));
        
        // Create instances from config
        this.instances = new HashMap<Integer,LoadBalancerInstance>();
        String[] instanceConfigs = config.get("instances").split(";");
        for (String instanceConfig : instanceConfigs)
        {
        	String[] configItems = instanceConfig.split(" ");
        	if (configItems.length != 3)
        	{ 
        		log.error("Ignoring bad instance config: " + instanceConfig);
        		continue;
        	}
        	LoadBalancerInstance instance = new LoadBalancerInstance(
        			configItems[0], configItems[1], configItems[2].split(","));
            this.instances.put(instance.getVirtualIP(), instance);
            log.info("Added load balancer instance: " + instance);
        }
        
		this.floodlightProv = context.getServiceImpl(
				IFloodlightProviderService.class);
        this.deviceProv = context.getServiceImpl(IDeviceService.class);
        this.spsApp = context.getServiceImpl(InterfaceShortestPathSwitching.class);
        
        /*********************************************************************/
        /* TODO: Initialize other class variables, if necessary              */
        
        /*********************************************************************/
	}

	/**
     * Subscribes to events and performs other startup tasks.
     */
	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException 
	{
		log.info(String.format("Starting %s...", MODULE_NAME));
		this.floodlightProv.addOFSwitchListener(this);
		this.floodlightProv.addOFMessageListener(OFType.PACKET_IN, this);
		
		/*********************************************************************/
		/* TODO: Perform other tasks, if necessary                           */
		
		/*********************************************************************/
	}
	
	/**
     * Event handler called when a switch joins the network.
     * @param DPID for the switch
     */
	@Override
	public void switchAdded(long switchId) 
	{
		IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
		log.info(String.format("Switch s%d added", switchId));
		
		/*********************************************************************/
		/* TODO: Install rules to send:                                      */
		/*       (1) packets from new connections to each virtual load       */
		/*       balancer IP to the controller                               */
		/*       (2) ARP packets to the controller, and                      */
		/*       (3) all other packets to the next rule table in the switch  */
		for (int virtualIP : instances.keySet()) {
			OFInstructionApplyActions newInst = new OFInstructionApplyActions();
			newInst.setActions(Arrays.asList((OFAction) new OFActionOutput().setPort(OFPort.OFPP_CONTROLLER.getValue())));
			List<OFInstruction> instList = Arrays.asList((OFInstruction) newInst);

			// ARP matching rule;
			OFMatch arpMatch = new OFMatch();
			arpMatch.setDataLayerType(OFMatch.ETH_TYPE_ARP);
			arpMatch.setField(OFOXMFieldType.ARP_TPA, virtualIP);

			// Install ARP rule to the new switch;
			SwitchCommands.removeRules(sw, table, arpMatch);
			SwitchCommands.installRule(sw, table, (short) (SwitchCommands.DEFAULT_PRIORITY + 1), arpMatch, instList);

			// TCP matching rule;
			OFMatch tcpMatch = new OFMatch();
			tcpMatch.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
			tcpMatch.setNetworkDestination(virtualIP);

			// Install TCP rule to the new switch;
			SwitchCommands.removeRules(sw, table, tcpMatch);
			SwitchCommands.installRule(sw, table, (short) (SwitchCommands.DEFAULT_PRIORITY + 1), tcpMatch, instList);

		}
		// Installing rules for any other packets that needs to go to the next table;
		OFInstructionGotoTable changeTableInst = new OFInstructionGotoTable(ShortestPathSwitching.table);
		SwitchCommands.installRule(sw, table, SwitchCommands.DEFAULT_PRIORITY, new OFMatch(), Arrays.asList((OFInstruction) changeTableInst));
		/*********************************************************************/
	}
	
	/**
	 * Handle incoming packets sent from switches.
	 * @param sw switch on which the packet was received
	 * @param msg message from the switch
	 * @param cntx the Floodlight context in which the message should be handled
	 * @return indication whether another module should also process the packet
	 */
	@Override
	public net.floodlightcontroller.core.IListener.Command receive(
			IOFSwitch sw, OFMessage msg, FloodlightContext cntx) 
	{
		// We're only interested in packet-in messages
		if (msg.getType() != OFType.PACKET_IN)
		{ return Command.CONTINUE; }
		OFPacketIn pktIn = (OFPacketIn)msg;
		
		// Handle the packet
		Ethernet ethPkt = new Ethernet();
		ethPkt.deserialize(pktIn.getPacketData(), 0,
				pktIn.getPacketData().length);
		
		/*********************************************************************/
		/* TODO: Send an ARP reply for ARP requests for virtual IPs; for TCP */
		/*       SYNs sent to a virtual IP, select a host and install        */
		/*       connection-specific rules to rewrite IP and MAC addresses;  */
		/*       for all other TCP packets sent to a virtual IP, send a TCP  */
		/*       reset; ignore all other packets                             */
		short curPacketType = ethPkt.getEtherType();
		if (curPacketType == Ethernet.TYPE_ARP) {
			if (isLogging)
				log.info(String.format("Received ARP request for virtual IP %s from %s",
						IPv4.fromIPv4Address(destVirtualIP),
						MACAddress.valueOf(arpPkt.getSenderHardwareAddress())));

			// Get the payload of current ethPkt as an ARP packet, and obtain its destination IP (virtual IP value);
			ARP arpContent = (ARP) ethPkt.getPayload();
			int destVirtualIP = IPv4.toIPv4Address(arpContent.getTargetProtocolAddress());
			if(arpContent.getOpCode() != ARP.OP_REQUEST || !(instances.keySet().contains(destVirtualIP))) {
				return Command.CONTINUE;
			}
			// log.info("Destination IP address: " + destVirtualIP);
			// Construct ARP reply;
			arpContent.setOpCode(ARP.OP_REPLY);
			// L2 address;
			arpContent.setTargetHardwareAddress(arpContent.getSenderHardwareAddress());
			arpContent.setSenderHardwareAddress(instances.get(destVirtualIP).getVirtualMAC());
			// L3 address;
			arpContent.setTargetProtocolAddress(arpContent.getSenderProtocolAddress());
			arpContent.setSenderProtocolAddress(destVirtualIP);

			// Encapsule this packet into a ethernet frame;
			ethPkt.setDestinationMACAddress(ethPkt.getSourceMACAddress());
			ethPkt.setSourceMACAddress(instances.get(destVirtualIP).getVirtualMAC());

			// send this packet using switch comands
			SwitchCommands.sendPacket(sw, (short) pktIn.getInPort(), ethPkt);
		} else if (curPacketType == Ethernet.TYPE_IPv4) {
			IPv4 ipPkt = (IPv4) ethPkt.getPayload();
			int destVirtualIP = ipPkt.getDestinationAddress();
			if (ipPkt.getProtocol() != IPv4.PROTOCOL_TCP || !(instances.keySet().contains(destVirtualIP))) {
				return Command.CONTINUE;
			}
			TCP tcpPkt = (TCP) ipPkt.getPayload();
			if (tcpPkt.getFlags() == TCP_FLAG_SYN) {
				if (isLogging)
					log.info("TCP_FLAG_SYN Rule");

				int clientIP = ipContent.getSourceAddress();
				short clientPort = tcpContent.getSourcePort();
				int curLoadBalancerHostIP = instances.get(destVirtualIP).getNextHostIP();
				short curLoadBalancerHostPort = tcpContent.getDestinationPort();
				byte[] curLoadBalancerHostMAC = getHostMACAddress(curLoadBalancerHostIP);
				byte[] destVirtualMAC = instances.get(destVirtualIP).getVirtualMAC();

				// Create match for inbound / outbound rules of a host;
				// Inbound: src is the client, and dst is the virtual IP + true port;
				OFMatch inMatch = new OFMatch();
				inMatch.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
				inMatch.setNetworkProtocol(IPv4.PROTOCOL_TCP);
				inMatch.setNetworkSource(clientIP);
				inMatch.setTransportSource(clientPort);
				inMatch.setNetworkDestination(destVirtualIP);
				inMatch.setTransportDestination(curLoadBalancerHostPort);

				// Install inbound rule using idle timeout;
				// Inbound action is to rewrite destination IP and MAC from virtual to actual;
				OFInstructionApplyActions newInst = new OFInstructionApplyActions();
				OFInstructionGotoTable changeTableInst = new OFInstructionGotoTable(ShortestPathSwitching.table);
				OFActionSetField setMACAction = new OFActionSetField();
				OFActionSetField setIPAction = new OFActionSetField();
				setMACAction.setField(new OFOXMField(OFOXMFieldType.ETH_DST, curLoadBalancerHostMAC));
				setIPAction.setField(new OFOXMField(OFOXMFieldType.IPV4_DST, curLoadBalancerHostIP));
				newInst.setActions(Arrays.asList((OFAction) setMACAction, (OFAction) setIPAction));
				List<OFInstruction> instList = Arrays.asList(newInst, changeTableInst);
				SwitchCommands.installRule(sw, table, SwitchCommands.MAX_PRIORITY, inMatch, instList, HARD_TIMEOUT, IDLE_TIMEOUT);

				// Outbound: src is the real host IP + true port, dst is the client;
				// Outbound action is to rewrite source IP and MAC from actual to virtual;
				OFMatch outMatch = new OFMatch();
				outMatch.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
				outMatch.setNetworkProtocol(IPv4.PROTOCOL_TCP);
				outMatch.setNetworkSource(curLoadBalancerHostIP);
				outMatch.setTransportSource(curLoadBalancerHostPort);
				outMatch.setNetworkDestination(clientIP);
				outMatch.setTransportDestination(clientPort);

				// Install outbound rule using idle timeout;
				newInst = new OFInstructionApplyActions();
				changeTableInst = new OFInstructionGotoTable(ShortestPathSwitching.table);
				setMACAction = new OFActionSetField();
				setIPAction = new OFActionSetField();
				setMACAction.setField(new OFOXMField(OFOXMFieldType.ETH_SRC, destVirtualMAC));
				setIPAction.setField(new OFOXMField(OFOXMFieldType.IPV4_SRC, destVirtualIP));
				newInst.setActions(Arrays.asList((OFAction) setMACAction, (OFAction) setIPAction));
				instList = Arrays.asList(newInst, changeTableInst);
				SwitchCommands.installRule(sw, table, SwitchCommands.MAX_PRIORITY, outMatch, instList, HARD_TIMEOUT, IDLE_TIMEOUT);
			} else {
				// Addresses;
				int clientIP = ipContent.getSourceAddress();
				short clientPort = tcpContent.getSourcePort();
				short curLoadBalancerHostPort = tcpContent.getDestinationPort();

				// Construct TCP RESET reply;
				TCP tcpReplyContent = new TCP();
				tcpReplyContent.setFlags(TCP_FLAG_RST);
				tcpReplyContent.setSourcePort(curLoadBalancerHostPort);

				// Construct IPv4 packet;
				IPv4 ipReplyContent = new IPv4();
				ipReplyContent.setProtocol(IPv4.PROTOCOL_TCP);
				ipReplyContent.setSourceAddress(destVirtualIP);
				ipReplyContent.setDestinationAddress(clientIP);
				ipReplyContent.setPayload(tcpReplyContent);

				// Construct Ethernet packet to encapsule IPv4;
				Ethernet replyCarrier = new Ethernet();
				replyCarrier.setEtherType(Ethernet.TYPE_IPv4);
				replyCarrier.setSourceMACAddress(instances.get(destVirtualIP).getVirtualMAC());
				replyCarrier.setDestinationMACAddress(ethPkt.getSourceMACAddress());
				replyCarrier.setPayload(ipReplyContent);

				// Send this packet using SwitchCommands.sendPacket();
				SwitchCommands.sendPacket(sw, (short) pktIn.getInPort(), replyCarrier);
			}
		} else {
			if (isLogging)
				log.info("Other TCP Rule");

			ipPkt.setFlags(TCP_FLAG_RST);
			ipPkt.setDestinationAddress(ipPkt.getSourceAddress());
			ipPkt.setSourceAddress(ipPkt.getDestinationAddress());
			ethPkt.setDestinationMACAddress(ethPkt.getSourceMACAddress());
			ethPkt.setSourceMACAddress(ethPkt.getSourceMACAddress());
			SwitchCommands.sendPacket(sw, (short) pktIn.getInPort(), ethPkt);
		}
		/*********************************************************************/
		
		return Command.CONTINUE;
	}
	
	/**
	 * Returns the MAC address for a host, given the host's IP address.
	 * @param hostIPAddress the host's IP address
	 * @return the hosts's MAC address, null if unknown
	 */
	private byte[] getHostMACAddress(int hostIPAddress)
	{
		Iterator<? extends IDevice> iterator = this.deviceProv.queryDevices(
				null, null, hostIPAddress, null, null);
		if (!iterator.hasNext())
		{ return null; }
		IDevice device = iterator.next();
		return MACAddress.valueOf(device.getMACAddress()).toBytes();
	}

	/**
	 * Event handler called when a switch leaves the network.
	 * @param DPID for the switch
	 */
	@Override
	public void switchRemoved(long switchId) 
	{ /* Nothing we need to do, since the switch is no longer active */ }

	/**
	 * Event handler called when the controller becomes the master for a switch.
	 * @param DPID for the switch
	 */
	@Override
	public void switchActivated(long switchId)
	{ /* Nothing we need to do, since we're not switching controller roles */ }

	/**
	 * Event handler called when a port on a switch goes up or down, or is
	 * added or removed.
	 * @param DPID for the switch
	 * @param port the port on the switch whose status changed
	 * @param type the type of status change (up, down, add, remove)
	 */
	@Override
	public void switchPortChanged(long switchId, ImmutablePort port,
			PortChangeType type) 
	{ /* Nothing we need to do, since load balancer rules are port-agnostic */}

	/**
	 * Event handler called when some attribute of a switch changes.
	 * @param DPID for the switch
	 */
	@Override
	public void switchChanged(long switchId) 
	{ /* Nothing we need to do */ }
	
    /**
     * Tell the module system which services we provide.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() 
	{ return null; }

	/**
     * Tell the module system which services we implement.
     */
	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> 
			getServiceImpls() 
	{ return null; }

	/**
     * Tell the module system which modules we depend on.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> 
			getModuleDependencies() 
	{
		Collection<Class<? extends IFloodlightService >> floodlightService =
	            new ArrayList<Class<? extends IFloodlightService>>();
        floodlightService.add(IFloodlightProviderService.class);
        floodlightService.add(IDeviceService.class);
        return floodlightService;
	}

	/**
	 * Gets a name for this module.
	 * @return name for this module
	 */
	@Override
	public String getName() 
	{ return MODULE_NAME; }

	/**
	 * Check if events must be passed to another module before this module is
	 * notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) 
	{
		return (OFType.PACKET_IN == type 
				&& (name.equals(ArpServer.MODULE_NAME) 
					|| name.equals(DeviceManagerImpl.MODULE_NAME))); 
	}

	/**
	 * Check if events must be passed to another module after this module has
	 * been notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) 
	{ return false; }
}
