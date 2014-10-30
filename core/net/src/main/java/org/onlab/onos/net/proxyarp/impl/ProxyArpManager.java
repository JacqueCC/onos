/*
 * Copyright 2014 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onlab.onos.net.proxyarp.impl;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.slf4j.LoggerFactory.getLogger;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.onos.net.ConnectPoint;
import org.onlab.onos.net.Device;
import org.onlab.onos.net.Host;
import org.onlab.onos.net.HostId;
import org.onlab.onos.net.Link;
import org.onlab.onos.net.Port;
import org.onlab.onos.net.PortNumber;
import org.onlab.onos.net.device.DeviceEvent;
import org.onlab.onos.net.device.DeviceListener;
import org.onlab.onos.net.device.DeviceService;
import org.onlab.onos.net.flow.DefaultTrafficTreatment;
import org.onlab.onos.net.flow.TrafficTreatment;
import org.onlab.onos.net.host.HostService;
import org.onlab.onos.net.host.InterfaceIpAddress;
import org.onlab.onos.net.host.PortAddresses;
import org.onlab.onos.net.link.LinkEvent;
import org.onlab.onos.net.link.LinkListener;
import org.onlab.onos.net.link.LinkService;
import org.onlab.onos.net.packet.DefaultOutboundPacket;
import org.onlab.onos.net.packet.InboundPacket;
import org.onlab.onos.net.packet.PacketContext;
import org.onlab.onos.net.packet.PacketService;
import org.onlab.onos.net.proxyarp.ProxyArpService;
import org.onlab.packet.ARP;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IpAddress;
import org.onlab.packet.MacAddress;
import org.onlab.packet.VlanId;
import org.slf4j.Logger;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;

@Component(immediate = true)
@Service
public class ProxyArpManager implements ProxyArpService {

    private final Logger log = getLogger(getClass());

    private static final String MAC_ADDR_NULL = "Mac address cannot be null.";
    private static final String REQUEST_NULL = "Arp request cannot be null.";
    private static final String REQUEST_NOT_ARP = "Ethernet frame does not contain ARP request.";
    private static final String NOT_ARP_REQUEST = "ARP is not a request.";
    private static final String NOT_ARP_REPLY = "ARP is not a reply.";

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected LinkService linkService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    private final Multimap<Device, PortNumber> internalPorts =
            HashMultimap.<Device, PortNumber>create();

    private final Multimap<Device, PortNumber> externalPorts =
            HashMultimap.<Device, PortNumber>create();

    /**
     * Listens to both device service and link service to determine
     * whether a port is internal or external.
     */
    @Activate
    public void activate() {
        deviceService.addListener(new InternalDeviceListener());
        linkService.addListener(new InternalLinkListener());
        determinePortLocations();
        log.info("Started");
    }


    @Deactivate
    public void deactivate() {
        log.info("Stopped");
    }

    @Override
    public boolean known(IpAddress addr) {
        checkNotNull(addr, MAC_ADDR_NULL);
        Set<Host> hosts = hostService.getHostsByIp(addr);
        return !hosts.isEmpty();
    }

    @Override
    public void reply(Ethernet eth, ConnectPoint inPort) {
        checkNotNull(eth, REQUEST_NULL);
        checkArgument(eth.getEtherType() == Ethernet.TYPE_ARP,
                REQUEST_NOT_ARP);
        ARP arp = (ARP) eth.getPayload();
        checkArgument(arp.getOpCode() == ARP.OP_REQUEST, NOT_ARP_REQUEST);
        checkNotNull(inPort);

        // If the request came from outside the network, only reply if it was
        // for one of our external addresses.
        if (isOutsidePort(inPort)) {
            IpAddress target =
                IpAddress.valueOf(arp.getTargetProtocolAddress());
            PortAddresses addresses =
                hostService.getAddressBindingsForPort(inPort);

            for (InterfaceIpAddress ia : addresses.ipAddresses()) {
                if (ia.ipAddress().equals(target)) {
                    Ethernet arpReply =
                        buildArpReply(ia.ipAddress(), addresses.mac(), eth);
                    sendTo(arpReply, inPort);
                }
            }
            return;
        } else {
            // If the source address matches one of our external addresses
            // it could be a request from an internal host to an external
            // address. Forward it over to the correct port.
            IpAddress source =
                IpAddress.valueOf(arp.getSenderProtocolAddress());
            PortAddresses sourceAddresses = findPortInSubnet(source);
            if (sourceAddresses != null) {
                for (InterfaceIpAddress ia : sourceAddresses.ipAddresses()) {
                    if (ia.ipAddress().equals(source)) {
                        sendTo(eth, sourceAddresses.connectPoint());
                        return;
                    }
                }
            }
        }

        // Continue with normal proxy ARP case

        VlanId vlan = VlanId.vlanId(eth.getVlanID());
        Set<Host> hosts = hostService.getHostsByIp(IpAddress.valueOf(arp
                .getTargetProtocolAddress()));

        Host dst = null;
        Host src = hostService.getHost(HostId.hostId(eth.getSourceMAC(),
                VlanId.vlanId(eth.getVlanID())));

        for (Host host : hosts) {
            if (host.vlan().equals(vlan)) {
                dst = host;
                break;
            }
        }

        if (src == null || dst == null) {
            flood(eth);
            return;
        }

        // TODO find the correct IP address
        IpAddress ipAddress = dst.ipAddresses().iterator().next();
        Ethernet arpReply = buildArpReply(ipAddress, dst.mac(), eth);
        // TODO: check send status with host service.
        sendTo(arpReply, src.location());
    }

    /**
     * Outputs the given packet out the given port.
     *
     * @param packet the packet to send
     * @param outPort the port to send it out
     */
    private void sendTo(Ethernet packet, ConnectPoint outPort) {
        if (internalPorts.containsEntry(
                deviceService.getDevice(outPort.deviceId()), outPort.port())) {
            // Sanity check to make sure we don't send the packet out an
            // internal port and create a loop (could happen due to
            // misconfiguration).
            return;
        }

        TrafficTreatment.Builder builder = DefaultTrafficTreatment.builder();
        builder.setOutput(outPort.port());
        packetService.emit(new DefaultOutboundPacket(outPort.deviceId(),
                builder.build(), ByteBuffer.wrap(packet.serialize())));
    }

    /**
     * Finds the port with an address in the subnet of the target address, if
     * one exists.
     *
     * @param target the target address to find a matching port for
     * @return a PortAddresses object if one was found, otherwise null
     */
    private PortAddresses findPortInSubnet(IpAddress target) {
        for (PortAddresses addresses : hostService.getAddressBindings()) {
            for (InterfaceIpAddress ia : addresses.ipAddresses()) {
                if (ia.subnetAddress().contains(target)) {
                    return addresses;
                }
            }
        }
        return null;
    }

    /**
     * Returns whether the given port is an outside-facing port with an IP
     * address configured.
     *
     * @param port the port to check
     * @return true if the port is an outside-facing port, otherwise false
     */
    private boolean isOutsidePort(ConnectPoint port) {
        //
        // TODO: Is this sufficient to identify outside-facing ports: just
        // having IP addresses on a port?
        //
        return !hostService.getAddressBindingsForPort(port).ipAddresses().isEmpty();
    }

    @Override
    public void forward(Ethernet eth) {
        checkNotNull(eth, REQUEST_NULL);
        checkArgument(eth.getEtherType() == Ethernet.TYPE_ARP,
                REQUEST_NOT_ARP);
        ARP arp = (ARP) eth.getPayload();
        checkArgument(arp.getOpCode() == ARP.OP_REPLY, NOT_ARP_REPLY);

        Host h = hostService.getHost(HostId.hostId(eth.getDestinationMAC(),
                VlanId.vlanId(eth.getVlanID())));

        if (h == null) {
            flood(eth);
        } else {
            TrafficTreatment.Builder builder = DefaultTrafficTreatment.builder();
            builder.setOutput(h.location().port());
            packetService.emit(new DefaultOutboundPacket(h.location().deviceId(),
                    builder.build(), ByteBuffer.wrap(eth.serialize())));
        }

    }

    @Override
    public boolean handleArp(PacketContext context) {
        InboundPacket pkt = context.inPacket();
        Ethernet ethPkt = pkt.parsed();
        if (ethPkt.getEtherType() == Ethernet.TYPE_ARP) {
            ARP arp = (ARP) ethPkt.getPayload();
            if (arp.getOpCode() == ARP.OP_REPLY) {
                forward(ethPkt);
            } else if (arp.getOpCode() == ARP.OP_REQUEST) {
                reply(ethPkt, context.inPacket().receivedFrom());
            }
            context.block();
            return true;
        }
        return false;
    }

    /**
     * Flood the arp request at all edges in the network.
     * @param request the arp request.
     */
    private void flood(Ethernet request) {
        TrafficTreatment.Builder builder = null;
        ByteBuffer buf = ByteBuffer.wrap(request.serialize());

        synchronized (externalPorts) {
            for (Entry<Device, PortNumber> entry : externalPorts.entries()) {
                ConnectPoint cp = new ConnectPoint(entry.getKey().id(), entry.getValue());
                if (isOutsidePort(cp)) {
                    continue;
                }

                builder = DefaultTrafficTreatment.builder();
                builder.setOutput(entry.getValue());
                packetService.emit(new DefaultOutboundPacket(entry.getKey().id(),
                        builder.build(), buf));
            }
        }
    }

    /**
     * Determines the location of all known ports in the system.
     */
    private void determinePortLocations() {
        Iterable<Device> devices = deviceService.getDevices();
        Iterable<Link> links = null;
        List<PortNumber> ports = null;
        for (Device d : devices) {
            ports = buildPortNumberList(deviceService.getPorts(d.id()));
            links = linkService.getLinks();
            for (Link l : links) {
                // for each link, mark the concerned ports as internal
                // and the remaining ports are therefore external.
                if (l.src().deviceId().equals(d.id())
                        && ports.contains(l.src().port())) {
                    ports.remove(l.src().port());
                    internalPorts.put(d, l.src().port());
                }
                if (l.dst().deviceId().equals(d.id())
                        && ports.contains(l.dst().port())) {
                    ports.remove(l.dst().port());
                    internalPorts.put(d, l.dst().port());
                }
            }
            synchronized (externalPorts) {
                externalPorts.putAll(d, ports);
            }
        }

    }

    private List<PortNumber> buildPortNumberList(List<Port> ports) {
        List<PortNumber> portNumbers = Lists.newLinkedList();
        for (Port p : ports) {
            portNumbers.add(p.number());
        }
        return portNumbers;
    }

    /**
     * Builds an ARP reply based on a request.
     *
     * @param srcIp the IP address to use as the reply source
     * @param srcMac the MAC address to use as the reply source
     * @param request the ARP request we got
     * @return an Ethernet frame containing the ARP reply
     */
    private Ethernet buildArpReply(IpAddress srcIp, MacAddress srcMac,
            Ethernet request) {

        Ethernet eth = new Ethernet();
        eth.setDestinationMACAddress(request.getSourceMACAddress());
        eth.setSourceMACAddress(srcMac.getAddress());
        eth.setEtherType(Ethernet.TYPE_ARP);
        eth.setVlanID(request.getVlanID());

        ARP arp = new ARP();
        arp.setOpCode(ARP.OP_REPLY);
        arp.setProtocolType(ARP.PROTO_TYPE_IP);
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET);

        arp.setProtocolAddressLength((byte) IpAddress.INET_BYTE_LENGTH);
        arp.setHardwareAddressLength((byte) Ethernet.DATALAYER_ADDRESS_LENGTH);
        arp.setSenderHardwareAddress(srcMac.getAddress());
        arp.setTargetHardwareAddress(request.getSourceMACAddress());

        arp.setTargetProtocolAddress(((ARP) request.getPayload())
                .getSenderProtocolAddress());
        arp.setSenderProtocolAddress(srcIp.toInt());
        eth.setPayload(arp);
        return eth;
    }

    public class InternalLinkListener implements LinkListener {

        @Override
        public void event(LinkEvent event) {
            Link link = event.subject();
            Device src = deviceService.getDevice(link.src().deviceId());
            Device dst = deviceService.getDevice(link.dst().deviceId());
            switch (event.type()) {
                case LINK_ADDED:
                    synchronized (externalPorts) {
                        externalPorts.remove(src, link.src().port());
                        externalPorts.remove(dst, link.dst().port());
                        internalPorts.put(src, link.src().port());
                        internalPorts.put(dst, link.dst().port());
                    }

                    break;
                case LINK_REMOVED:
                    synchronized (externalPorts) {
                        externalPorts.put(src, link.src().port());
                        externalPorts.put(dst, link.dst().port());
                        internalPorts.remove(src, link.src().port());
                        internalPorts.remove(dst, link.dst().port());
                    }

                    break;
                case LINK_UPDATED:
                    // don't care about links being updated.
                    break;
                default:
                    break;
            }

        }

    }

    public class InternalDeviceListener implements DeviceListener {

        @Override
        public void event(DeviceEvent event) {
            Device device = event.subject();
            switch (event.type()) {
                case DEVICE_ADDED:
                case DEVICE_AVAILABILITY_CHANGED:
                case DEVICE_MASTERSHIP_CHANGED:
                case DEVICE_SUSPENDED:
                case DEVICE_UPDATED:
                 // nothing to do in these cases; handled when links get reported
                    break;
                case DEVICE_REMOVED:
                    synchronized (externalPorts) {
                        externalPorts.removeAll(device);
                        internalPorts.removeAll(device);
                    }
                    break;
                case PORT_ADDED:
                case PORT_UPDATED:
                    synchronized (externalPorts) {
                        if (event.port().isEnabled()) {
                            externalPorts.put(device, event.port().number());
                            internalPorts.remove(device, event.port().number());
                        }
                    }
                    break;
                case PORT_REMOVED:
                    synchronized (externalPorts) {
                        externalPorts.remove(device, event.port().number());
                        internalPorts.remove(device, event.port().number());
                    }
                    break;
                default:
                    break;

            }

        }

    }

}
