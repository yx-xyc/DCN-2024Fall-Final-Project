# DCN-2024Fall-Final-Project

## Project Overview
This project implements Software-Defined Networking (SDN) applications using the Floodlight controller:
1. Shortest-Path Switching (SPS) Application - A layer-3 routing implementation that computes and installs shortest paths
2. Distributed Load Balancer Application integrated with SPS

## Project Structure
```
DCN-2024Fall-Final-Project/
├── src/
│   └── edu.wisc.cs.sdn.apps/
│       ├── loadbalancer/    # Load balancer implementation
│       ├── sps/            # Shortest path switching implementation
│       └── util/           # Utility classes
├── .classpath
├── .gitignore
├── .project
├── README.md
├── arpserver.prop
├── build.xml
├── fixmininet.sh
├── floodlight.patch
├── mininet.patch
├── run_mininet.py
├── sps.prop              # Configuration for SPS only
├── sps_balance.prop      # Configuration for SPS with load balancer
└── webserver.py
```

## Setup Instructions

### Prerequisites
- Oracle VirtualBox
- Virtual Box Image with required software
- Java Development Environment
- Mininet network emulator
- OpenFlow
- Floodlight controller

### Installation Steps
1. Install Oracle VirtualBox
2. Import the provided .ova Virtual Box Image
3. Login credentials: username="mininet", password="mininet"
4. Compile Floodlight and applications:
   ```bash
   cd ~/project3/
   ant
   ```

### Running the Applications

1. Start Floodlight controller:

   For SPS only:
   ```bash
   java -jar FloodlightWithApps.jar -cf sps.prop
   ```

   For SPS with load balancer:
   ```bash
   java -jar FloodlightWithApps.jar -cf sps_balance.prop
   ```

2. Start Mininet (in a separate terminal):
   ```bash
   sudo ./run_mininet.py single,3
   ```

## Implementation Details

### Shortest-Path Switching (SPS) Application
- Implements layer-3 routing using shortest path computation
- Uses Bellman-Ford algorithm for path calculation
- Handles network topology changes dynamically
- Manages flow table entries for efficient packet forwarding
- Key files:
    - ShortestPathSwitching.java
    - InterfaceShortestPathSwitching.java

### Load Balancer Application
- Integrates with SPS for packet forwarding
- Implements distributed load balancing for TCP connections
- Supports multiple virtual IP addresses
- Round-robin distribution of incoming connections
- Key files:
    - LoadBalancer.java
    - LoadBalancerInstance.java

## Testing
- Use `pingall` command in Mininet to test connectivity
- Monitor flow tables using:
  ```bash
  sudo ovs-ofctl -O OpenFlow13 dump-flows s1
  ```
- Use `tcpdump` to monitor packet flows:
  ```bash
  tcpdump -v -n -i hN-eth0
  ```

## Troubleshooting
If Floodlight fails to start due to port binding issues:
```bash
sudo update-rc.d -f openvswitch-controller remove
```

## Configuration Files
- `sps.prop`: Configuration for running only the Shortest-Path Switching application
- `sps_balance.prop`: Configuration for running both SPS and load balancer applications
- Note: While l3routing configuration exists in the codebase, it is not used in this implementation

## Acknowledgements
This project is based on software packages developed at the University of Wisconsin and Brown University.
- Original packages: edu.wisc.cs.sdn.apps.loadbalancer and edu.brown.cs.sdn.apps.sps
- The implementation replaces the original l3routing with our own Shortest-Path Switching (SPS) solution